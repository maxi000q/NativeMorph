// NativeMorph - JNI registration shim for the relocated Godot 3.6.2 runtime.
//
// The bundled 3.x engine (libgodot3x_droid.so, from the official 3.6.2
// export template) binds its Java native methods via exported symbols named
// Java_org_godotengine_godot_* (standard JNI name-based binding; 3.6.2 has
// no RegisterNatives call). The Java layer in this APK is relocated to
// org.nativemorph.god3x to coexist with the Godot 4.7.2 AAR
// (org.godotengine.godot), so the name-based lookup would fail.
//
// This shim is loaded by org.nativemorph.god3x.GodotLib right AFTER the
// engine library itself. Its JNI_OnLoad resolves the ORIGINAL exported
// symbols with dlsym and registers them on the RELOCATED classes with
// RegisterNatives - no ELF surgery, no symbol renaming, and the two engine
// runtimes never share a process (Game3Activity runs in :g3).
#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <string>

#define LOGE(...) \
  __android_log_print(ANDROID_LOG_ERROR, "nm-g3-jni", __VA_ARGS__)

namespace {

struct Method {
  const char* name;
  const char* sig;
};

// Signatures transcribed from the relocated org.nativemorph.god3x sources
// (GodotLib.java / plugin.GodotPlugin.java).
constexpr Method kGodotLib[] = {
    {"initialize",
     "(Landroid/app/Activity;Lorg/nativemorph/god3x/Godot;"
     "Landroid/content/res/AssetManager;Lorg/nativemorph/god3x/GodotIO;"
     "Lorg/nativemorph/god3x/utils/GodotNetUtils;"
     "Lorg/nativemorph/god3x/io/directory/DirectoryAccessHandler;"
     "Lorg/nativemorph/god3x/io/file/FileAccessHandler;Z)V"},
    {"ondestroy", "()V"},
    {"setup", "([Ljava/lang/String;Lorg/nativemorph/god3x/tts/GodotTTS;)Z"},
    {"resize", "(II)V"},
    {"newcontext", "()V"},
    {"back", "()V"},
    {"step", "()Z"},
    {"ttsCallback", "(III)V"},
    {"dispatchTouchEvent", "(III[FZ)V"},
    {"dispatchMouseEvent", "(IIFFFFZZ)V"},
    {"magnify", "(FFF)V"},
    {"pan", "(FFFF)V"},
    {"accelerometer", "(FFF)V"},
    {"gravity", "(FFF)V"},
    {"magnetometer", "(FFF)V"},
    {"gyroscope", "(FFF)V"},
    {"key", "(IIIZ)V"},
    {"joybutton", "(IIZ)V"},
    {"joyaxis", "(IF)V"},
    {"joyhat", "(II)V"},
    {"joyconnectionchanged", "(IZLjava/lang/String;)V"},
    {"focusin", "()V"},
    {"focusout", "()V"},
    {"getGlobal", "(Ljava/lang/String;)Ljava/lang/String;"},
    {"getEditorSetting", "(Ljava/lang/String;)Ljava/lang/String;"},
    {"callobject", "(JLjava/lang/String;[Ljava/lang/Object;)V"},
    {"calldeferred", "(JLjava/lang/String;[Ljava/lang/Object;)V"},
    {"requestPermissionResult", "(Ljava/lang/String;Z)V"},
    {"setVirtualKeyboardHeight", "(I)V"},
    {"onRendererResumed", "()V"},
    {"onRendererPaused", "()V"},
};

constexpr Method kGodotPlugin[] = {
    {"nativeRegisterSingleton", "(Ljava/lang/String;Ljava/lang/Object;)V"},
    {"nativeRegisterMethod",
     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V"},
    {"nativeRegisterGDNativeLibraries", "([Ljava/lang/String;)V"},
    {"nativeRegisterSignal",
     "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V"},
    {"nativeEmitSignal",
     "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"},
};

constexpr int kNumGodotLib = int(sizeof(kGodotLib) / sizeof(kGodotLib[0]));
constexpr int kNumPlugin = int(sizeof(kGodotPlugin) / sizeof(kGodotPlugin[0]));

jint register_class(JNIEnv* env, const char* class_path, const char* sym_prefix,
                    const Method* methods, int count) {
  jclass cls = env->FindClass(class_path);
  if (cls == nullptr) {
    LOGE("FindClass failed: %s", class_path);
    return -1;
  }
  JNINativeMethod table[40];
  for (int i = 0; i < count; i++) {
    std::string sym = std::string("Java_org_godotengine_godot_") + sym_prefix +
                      methods[i].name;
    void* fn = dlsym(RTLD_DEFAULT, sym.c_str());
    if (fn == nullptr) {
      LOGE("engine symbol missing: %s", sym.c_str());
      return -2;
    }
    table[i].name = const_cast<char*>(methods[i].name);
    table[i].signature = const_cast<char*>(methods[i].sig);
    table[i].fnPtr = fn;
  }
  jint r = env->RegisterNatives(cls, table, count);
  if (r != JNI_OK) LOGE("RegisterNatives failed for %s: %d", class_path, r);
  return r;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    return -1;

  // GodotLib's static initializer loads the engine library first; make sure
  // its symbols are visible to this namespace (same classloader namespace).
  if (dlopen("libgodot3x_droid.so", RTLD_NOW | RTLD_NOLOAD) == nullptr) {
    if (dlopen("libgodot3x_droid.so", RTLD_NOW) == nullptr) {
      LOGE("libgodot3x_droid.so not loaded yet (loading it now failed too)");
    }
  }

  if (register_class(env, "org/nativemorph/god3x/GodotLib", "GodotLib_",
                     kGodotLib, kNumGodotLib) != JNI_OK)
    return -1;
  if (register_class(env, "org/nativemorph/god3x/plugin/GodotPlugin",
                     "plugin_GodotPlugin_", kGodotPlugin, kNumPlugin) != JNI_OK)
    return -1;
  return JNI_VERSION_1_6;
}
