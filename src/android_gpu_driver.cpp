// Android: optional custom Vulkan driver (Mesa Turnip) through libadrenotools.
//
// The stock Adreno driver draws these games wrong; the RB2-Android port
// (github.com/moddedrounds/RB2-Android, BSD-3-Clause), which shares this
// runtime on the same GPUs, runs them on Turnip. This is its approach: before
// SDL_main, GameActivity asks for a driver folder; libadrenotools opens a
// private libvulkan.so that uses the driver in it instead of the system one,
// a throwaway instance checks that it finds the GPU, and the runtime gets the
// library handle through REX_VULKAN_LOADER_HANDLE (see the SDK patch,
// vulkan_instance.cpp). Anything going wrong leaves the system driver in use.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>

#include <cstdint>
#include <cstdio>
#include <string>

#include <vulkan/vulkan.h>

#if defined(BURSTLIMIT_HAVE_ADRENOTOOLS)
#include <adrenotools/driver.h>
#endif

namespace {

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "burstlimit-driver", __VA_ARGS__)

#if defined(BURSTLIMIT_HAVE_ADRENOTOOLS)
// Empty when the driver finds a GPU with what the runtime needs, otherwise why
// not. A Turnip build that doesn't know the GPU reports no device here instead
// of failing later in the runtime. *name gets the device name.
std::string Verify(void* handle, std::string* name) {
  auto get_proc =
      reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(handle, "vkGetInstanceProcAddr"));
  if (!get_proc) {
    return "no vkGetInstanceProcAddr";
  }
  auto create_instance =
      reinterpret_cast<PFN_vkCreateInstance>(get_proc(nullptr, "vkCreateInstance"));
  if (!create_instance) {
    return "no vkCreateInstance";
  }
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "driver check";
  app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  info.pApplicationInfo = &app;
  VkInstance instance = VK_NULL_HANDLE;
  VkResult result = create_instance(&info, nullptr, &instance);
  if (result != VK_SUCCESS) {
    return "vkCreateInstance failed (" + std::to_string(int(result)) + ")";
  }
  auto destroy_instance =
      reinterpret_cast<PFN_vkDestroyInstance>(get_proc(instance, "vkDestroyInstance"));
  auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
      get_proc(instance, "vkEnumeratePhysicalDevices"));
  auto get_features = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures>(
      get_proc(instance, "vkGetPhysicalDeviceFeatures"));
  auto get_properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
      get_proc(instance, "vkGetPhysicalDeviceProperties"));
  std::string problem;
  uint32_t count = 0;
  if (!enumerate || !get_features || !get_properties ||
      enumerate(instance, &count, nullptr) != VK_SUCCESS || count == 0) {
    problem = "the driver found no GPU";
  } else {
    count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    enumerate(instance, &count, &device);
    VkPhysicalDeviceFeatures features{};
    get_features(device, &features);
    VkPhysicalDeviceProperties properties{};
    get_properties(device, &properties);
    *name = properties.deviceName;
    if (!features.independentBlend || !features.fragmentStoresAndAtomics ||
        !features.vertexPipelineStoresAndAtomics) {
      problem = *name + " lacks a feature the game needs";
    }
  }
  if (destroy_instance) {
    destroy_instance(instance, nullptr);
  }
  return problem;
}
#endif

std::string Load(const std::string& driver_dir, const std::string& library,
                 const std::string& hook_dir) {
#if !defined(BURSTLIMIT_HAVE_ADRENOTOOLS)
  (void)driver_dir;
  (void)library;
  (void)hook_dir;
  return "system driver (this build has no libadrenotools)";
#else
  void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr,
                                            (hook_dir + "/").c_str(), (driver_dir + "/").c_str(),
                                            library.c_str(), nullptr, nullptr);
  if (!handle) {
    return "system driver (Turnip could not be loaded)";
  }
  std::string name;
  const std::string problem = Verify(handle, &name);
  if (!problem.empty()) {
    return "system driver (Turnip: " + problem + ")";
  }
  char text[40];
  snprintf(text, sizeof(text), "0x%llx",
           static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(handle)));
  setenv("REX_VULKAN_LOADER_HANDLE", text, 1);
  return "Turnip (" + name + ")";
#endif
}

std::string ToString(JNIEnv* env, jstring s) {
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string result = chars ? chars : "";
  if (chars) {
    env->ReleaseStringUTFChars(s, chars);
  }
  return result;
}

}  // namespace

// What nativeLoadVulkanDriver ended up with, for the game log
// (burstlimit_android.cpp); set before SDL_main starts the game.
std::string g_burstlimit_vulkan_driver_status = "system driver (not asked for another one)";

extern "C" JNIEXPORT jstring JNICALL
Java_com_dbzburstlimit_recompiled_GameActivity_nativeLoadVulkanDriver(JNIEnv* env, jclass,
                                                                      jstring driver_dir,
                                                                      jstring library,
                                                                      jstring hook_dir) {
  const std::string status =
      Load(ToString(env, driver_dir), ToString(env, library), ToString(env, hook_dir));
  LOGW("Vulkan driver: %s", status.c_str());
  g_burstlimit_vulkan_driver_status = status;
  return env->NewStringUTF(status.c_str());
}
