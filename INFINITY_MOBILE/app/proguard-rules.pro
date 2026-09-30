# ── JNI Bridge ────────────────────────────────────────────────────────────────
# These classes are called by name from C++ via JNI.
# If ProGuard renames them, the JNI calls will fail with UnsatisfiedLinkError.
-keep class com.infinity.ai.ai.runtime.LlamaJniBridge { *; }
-keep interface com.infinity.ai.ai.runtime.LlamaCallback { *; }
-keepclassmembers class com.infinity.ai.ai.runtime.LlamaJniBridge {
    native <methods>;
}

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── DataStore ─────────────────────────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
