# Consumer rules for :ai.
#
# Applied to whatever app module depends on this library, so the rules that keep the JNI boundary
# intact travel with the module rather than having to be remembered in :android-app.

# The native library resolves these by name through JNI. R8 has no way to see the call, so without
# this the release build strips or renames them and the first generation fails with
# UnsatisfiedLinkError -- in release only, which is the worst place to find it.
-keepclasseswithmembernames,includedescriptorclasses class org.jaagruk.ai.runtime.LlamaBridge {
    native <methods>;
}

# C++ calls back into this interface by method name and signature during generation.
-keep,allowobfuscation interface org.jaagruk.ai.runtime.TokenCallback { *; }
-keepclassmembers class * implements org.jaagruk.ai.runtime.TokenCallback {
    public void onToken(java.lang.String);
    public void onComplete(int);
    public void onError(java.lang.String);
}
