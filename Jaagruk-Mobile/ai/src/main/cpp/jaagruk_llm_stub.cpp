// -----------------------------------------------------------------------------
// jaagruk_llm_stub.cpp — the same JNI surface with no inference behind it.
//
// Built when the vendored llama.cpp tree is absent. It exists so `:ai` compiles on
// a checkout without the vendored sources and so the Kotlin layer reports
// AiCapability.MODEL_MISSING through its normal path rather than failing to link.
//
// Every entry point is the honest answer for "there is no engine here": load
// fails, isLoaded is false, and generate reports an error rather than hanging.
// -----------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "JaagrukLlm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static const char* UNAVAILABLE =
    "this build has no inference engine: the llama.cpp sources were not vendored";

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeLoadModel(
        JNIEnv*, jobject, jstring, jint, jint) {
    LOGW("%s", UNAVAILABLE);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeGenerate(
        JNIEnv* env, jobject, jstring, jint, jfloat, jfloat, jint, jobject callback) {
    jclass type = env->GetObjectClass(callback);
    jmethodID onError = env->GetMethodID(type, "onError", "(Ljava/lang/String;)V");
    if (!onError) return;
    jstring message = env->NewStringUTF(UNAVAILABLE);
    env->CallVoidMethod(callback, onError, message);
    env->DeleteLocalRef(message);
}

JNIEXPORT void JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeStop(JNIEnv*, jobject) {}

JNIEXPORT void JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeUnloadModel(JNIEnv*, jobject) {}

JNIEXPORT jboolean JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeIsLoaded(JNIEnv*, jobject) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_jaagruk_ai_runtime_LlamaBridge_nativeContextTokens(JNIEnv*, jobject) {
    return 0;
}

} // extern "C"
