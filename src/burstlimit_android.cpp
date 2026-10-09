// Android: the on-screen controller (android/.../TouchControllerView.java).
//
// The touch overlay drives an SDL virtual gamepad, so the input driver sees it
// like any other controller (Xbox layout: A/B/X/Y, LB/RB, LT/RT, sticks,
// D-pad, Back/Start, L3/R3). It is attached once SDL's joystick subsystem is up
// and the overlay first reports a change.

#include <jni.h>

#include <mutex>

#include <SDL3/SDL.h>

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
