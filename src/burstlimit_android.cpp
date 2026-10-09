// Android: the on-screen controller (android/.../TouchControllerView.java).
//
// The touch overlay drives an SDL virtual gamepad, so the input driver sees it
// like any other controller (Xbox layout: A/B/X/Y, LB/RB, LT/RT, sticks,
// D-pad, Back/Start, L3/R3). It is attached once SDL's joystick subsystem is up
// and the overlay first reports a change.

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include <cxxabi.h>
#include <dirent.h>
#include <dlfcn.h>
#include <signal.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <ucontext.h>
#include <unistd.h>

#include <SDL3/SDL.h>

#include <rex/cvar.h>
#include <rex/logging.h>

REXCVAR_DEFINE_INT32(android_thread_dump, 25, "Android",
                     "Seconds after start to log every thread's call stack (where a stuck game "
                     "waits), and again 30 s later; 0 = off");

namespace {

std::mutex g_pad_mutex;
SDL_JoystickID g_pad_id = 0;
SDL_Joystick* g_pad = nullptr;

// Call with g_pad_mutex held.
SDL_Joystick* EnsureVirtualPad() {
  if (g_pad) {
    return g_pad;
  }
  if (!SDL_WasInit(SDL_INIT_JOYSTICK)) {
    return nullptr;
  }
  SDL_VirtualJoystickDesc desc;
  SDL_INIT_INTERFACE(&desc);
  desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
  desc.naxes = SDL_GAMEPAD_AXIS_COUNT;
  desc.nbuttons = SDL_GAMEPAD_BUTTON_COUNT;
  desc.vendor_id = 0x045E;   // Microsoft
  desc.product_id = 0x028E;  // Xbox 360 Controller
  desc.name = "Touch Controller";
  g_pad_id = SDL_AttachVirtualJoystick(&desc);
  if (!g_pad_id) {
    SDL_Log("Touch controller: SDL_AttachVirtualJoystick failed: %s", SDL_GetError());
    return nullptr;
  }
  g_pad = SDL_OpenJoystick(g_pad_id);
  if (!g_pad) {
    SDL_Log("Touch controller: SDL_OpenJoystick failed: %s", SDL_GetError());
    SDL_DetachVirtualJoystick(g_pad_id);
    g_pad_id = 0;
  }
  return g_pad;
}

}  // namespace

extern "C" {

// button: SDL_GamepadButton.
JNIEXPORT void JNICALL Java_com_dbzburstlimit_recompiled_TouchControllerView_nativeSetButton(
    JNIEnv*, jclass, jint button, jboolean down) {
  std::lock_guard lock(g_pad_mutex);
  if (SDL_Joystick* pad = EnsureVirtualPad()) {
    SDL_SetJoystickVirtualButton(pad, button, down == JNI_TRUE);
  }
}

// axis: SDL_GamepadAxis; value -1..1 (triggers 0..1).
JNIEXPORT void JNICALL Java_com_dbzburstlimit_recompiled_TouchControllerView_nativeSetAxis(
    JNIEnv*, jclass, jint axis, jfloat value) {
  if (value > 1.0f) {
    value = 1.0f;
  } else if (value < -1.0f) {
    value = -1.0f;
  }
  Sint16 raw;
  if (axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER || axis == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER) {
    // SDL maps a virtual trigger's full axis range to released..pressed.
    if (value < 0.0f) {
      value = 0.0f;
    }
    raw = static_cast<Sint16>(SDL_JOYSTICK_AXIS_MIN +
                              value * (SDL_JOYSTICK_AXIS_MAX - SDL_JOYSTICK_AXIS_MIN));
  } else {
    raw = static_cast<Sint16>(value * SDL_JOYSTICK_AXIS_MAX);
  }
  std::lock_guard lock(g_pad_mutex);
  if (SDL_Joystick* pad = EnsureVirtualPad()) {
    SDL_SetJoystickVirtualAxis(pad, axis, raw);
  }
}

// Removed while a physical controller is connected, so it doesn't take the
// first player slot from it.
JNIEXPORT void JNICALL Java_com_dbzburstlimit_recompiled_TouchControllerView_nativeDetach(
    JNIEnv*, jclass) {
  std::lock_guard lock(g_pad_mutex);
  if (g_pad) {
    SDL_CloseJoystick(g_pad);
    g_pad = nullptr;
  }
  if (g_pad_id) {
    SDL_DetachVirtualJoystick(g_pad_id);
    g_pad_id = 0;
  }
}

}  // extern "C"

// --- Thread stacks for the log -------------------------------------------
//
// Android gives an app no debugger, and a stuck game logs nothing. Each
// thread is interrupted with a signal whose handler walks its frame records
// (AArch64 keeps x29 chains); the walk reads memory through a pipe (the
// kernel checks the address), so a bad frame pointer ends the walk instead
// of crashing. Recompiled game
// functions show up by guest address (sub_82xxxxxx).

namespace {

constexpr int kMaxFrames = 40;

struct StackCapture {
  std::atomic<pid_t> target{0};
  std::atomic<bool> done{false};
  uintptr_t pcs[kMaxFrames];
  int count = 0;
};
StackCapture g_capture;

int DumpSignal() {
  return SIGRTMIN + 8;  // The runtime uses SIGRTMIN + 0..2.
}

uintptr_t StripPointer(uintptr_t value) {
  // Top byte tags and pointer authentication codes.
  return value & ((uintptr_t(1) << 48) - 1);
}

int g_read_pipe[2] = {-1, -1};

// write() fails with EFAULT on an unmapped address rather than faulting.
bool SafeRead(uintptr_t address, uintptr_t* out) {
  if (write(g_read_pipe[1], reinterpret_cast<const void*>(address), sizeof(*out)) !=
      sizeof(*out)) {
    return false;
  }
  return read(g_read_pipe[0], out, sizeof(*out)) == sizeof(*out);
}

void DumpSignalHandler(int, siginfo_t*, void* context) {
  if (g_capture.target.load(std::memory_order_acquire) != gettid()) {
    return;
  }
  auto* uc = static_cast<ucontext_t*>(context);
  int count = 0;
  g_capture.pcs[count++] = StripPointer(uc->uc_mcontext.pc);
  g_capture.pcs[count++] = StripPointer(uc->uc_mcontext.regs[30]);
  uintptr_t fp = uc->uc_mcontext.regs[29];
  while (count < kMaxFrames && fp && (fp & 15) == 0) {
    uintptr_t next = 0;
    uintptr_t ret = 0;
    if (!SafeRead(fp, &next) || !SafeRead(fp + 8, &ret) || !ret) {
      break;
    }
    g_capture.pcs[count++] = StripPointer(ret);
    if (next <= fp) {
      break;
    }
    fp = next;
  }
  g_capture.count = count;
  g_capture.done.store(true, std::memory_order_release);
}

std::string ReadSmallFile(const std::string& path) {
  FILE* file = std::fopen(path.c_str(), "r");
  if (!file) {
    return {};
  }
  char buffer[512];
  size_t length = std::fread(buffer, 1, sizeof(buffer) - 1, file);
  std::fclose(file);
  buffer[length] = 0;
  std::string text(buffer);
  while (!text.empty() && (text.back() == '\n' || text.back() == ' ')) {
    text.pop_back();
  }
  return text;
}

std::string Describe(uintptr_t pc) {
  Dl_info info{};
  // pc - 1: a return address points past the call.
  if (!dladdr(reinterpret_cast<void*>(pc - 1), &info) || !info.dli_fname) {
    char raw[32];
    std::snprintf(raw, sizeof(raw), "0x%llx", static_cast<unsigned long long>(pc));
    return raw;
  }
  const char* library = std::strrchr(info.dli_fname, '/');
  library = library ? library + 1 : info.dli_fname;
  char text[512];
  if (info.dli_sname) {
    int status = 0;
    char* demangled = abi::__cxa_demangle(info.dli_sname, nullptr, nullptr, &status);
    std::snprintf(text, sizeof(text), "%s!%s+0x%llx", library,
                  status == 0 && demangled ? demangled : info.dli_sname,
                  static_cast<unsigned long long>(pc - reinterpret_cast<uintptr_t>(info.dli_saddr)));
    std::free(demangled);
  } else {
    std::snprintf(text, sizeof(text), "%s+0x%llx", library,
                  static_cast<unsigned long long>(pc - reinterpret_cast<uintptr_t>(info.dli_fbase)));
  }
  return text;
}

void DumpThreadStacks(int pass) {
  struct sigaction action{};
  action.sa_flags = SA_SIGINFO | SA_RESTART;
  action.sa_sigaction = DumpSignalHandler;
  sigemptyset(&action.sa_mask);
  sigaction(DumpSignal(), &action, nullptr);
  if (g_read_pipe[0] < 0 && pipe2(g_read_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
    REXLOG_INFO("[ThreadDump] pipe2 failed");
    return;
  }

  REXLOG_INFO("[ThreadDump] pass {}: call stacks of every thread", pass);
  DIR* tasks = opendir("/proc/self/task");
  if (!tasks) {
    return;
  }
  const pid_t self = gettid();
  while (dirent* entry = readdir(tasks)) {
    pid_t tid = static_cast<pid_t>(std::atoi(entry->d_name));
    if (tid <= 0 || tid == self) {
      continue;
    }
    std::string base = "/proc/self/task/" + std::to_string(tid);
    std::string name = ReadSmallFile(base + "/comm");
    std::string stat = ReadSmallFile(base + "/stat");
    // State is the field after the ")" that closes the name.
    size_t close = stat.rfind(')');
    char state = close != std::string::npos && close + 2 < stat.size() ? stat[close + 2] : '?';

    g_capture.done.store(false, std::memory_order_release);
    g_capture.count = 0;
    g_capture.target.store(tid, std::memory_order_release);
    bool answered = false;
    if (syscall(SYS_tgkill, getpid(), tid, DumpSignal()) == 0) {
      for (int i = 0; i < 200 && !answered; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        answered = g_capture.done.load(std::memory_order_acquire);
      }
    }
    g_capture.target.store(0, std::memory_order_release);

    if (!answered) {
      REXLOG_INFO("[ThreadDump] tid {} '{}' state {}: no answer", tid, name, state);
      continue;
    }
    std::string frames;
    for (int i = 0; i < g_capture.count; ++i) {
      frames += "\n    #" + std::to_string(i) + " " + Describe(g_capture.pcs[i]);
    }
    REXLOG_INFO("[ThreadDump] tid {} '{}' state {}:{}", tid, name, state, frames);
  }
  closedir(tasks);
}

struct ThreadDumpStarter {
  ThreadDumpStarter() {
    std::thread([] {
      // The cvars are parsed by the time the first dump is due.
      std::this_thread::sleep_for(std::chrono::seconds(5));
      int delay = REXCVAR_GET(android_thread_dump);
      if (delay <= 0) {
        return;
      }
      std::this_thread::sleep_for(std::chrono::seconds(delay > 5 ? delay - 5 : 0));
      DumpThreadStacks(1);
      std::this_thread::sleep_for(std::chrono::seconds(30));
      DumpThreadStacks(2);
    }).detach();
  }
} g_thread_dump_starter;

}  // namespace
