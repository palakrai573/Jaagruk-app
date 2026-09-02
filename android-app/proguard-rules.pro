# Jaagruk release rules.
#
# R8 is on for release builds, so anything reached only by reflection has to be kept explicitly.
# The failure mode is nasty: the app builds, installs and runs, then throws on the one screen the
# stripped class was needed for.

# --- kotlinx.serialization -------------------------------------------------
# Serializers are generated companions and found reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.jaagruk.safety.**$$serializer { *; }
-keepclassmembers class org.jaagruk.safety.** {
    *** Companion;
}
-keepclasseswithmembers class org.jaagruk.safety.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit --------------------------------------------------------------
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# --- BouncyCastle ----------------------------------------------------------
# Only the lightweight crypto API is used, never the JCE provider, so the bulk can go. Ed25519 and
# the digest classes must survive: they verify every certificate.
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.params.Ed25519** { *; }
-keep class org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator { *; }
-dontwarn org.bouncycastle.jce.**
-dontwarn org.bouncycastle.jcajce.**
-dontwarn javax.naming.**

# --- ARCore ----------------------------------------------------------------
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# --- MediaPipe -------------------------------------------------------------
# Tasks are instantiated through a native bridge.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# --- ML Kit ----------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }

# --- Nearby Connections ----------------------------------------------------
-keep class com.google.android.gms.nearby.** { *; }

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- Hilt / WorkManager ----------------------------------------------------
-keep class * extends androidx.work.ListenableWorker { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# --- :core -----------------------------------------------------------------
# The canonical encoder and the chain verifier are the trust boundary. Their field order and
# behaviour are pinned by cross-language fixtures; leave them alone.
-keep class org.jaagruk.core.cert.** { *; }
-keep class org.jaagruk.core.crypto.** { *; }
-keep class org.jaagruk.core.catalog.** { *; }

# --- Diagnostics -----------------------------------------------------------
# Keep line numbers so a field crash report is actionable, but hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Annotation-processor leakage -----------------------------------------
# MediaPipe pulls `com.google.auto.value:auto-value` as a runtime dependency rather than
# `compileOnly`. That drags an annotation processor onto the release classpath, and it references
# `javax.lang.model.*`, which does not exist on Android. R8 is right to complain: the classes are
# genuinely unresolvable. They are also genuinely unreachable at runtime, because nothing on a phone
# runs an annotation processor.
#
# The dependency is excluded in `build.gradle.kts`; these rules cover the same ground for anything
# that arrives through a future transitive path, so a dependency bump cannot silently break the
# release build.
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.processing.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn com.squareup.javapoet.**

# OkHttp and Retrofit reference optional Conscrypt, BouncyCastle JSSE and OpenJSSE providers. None
# ship here; the platform provider is used instead.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn okhttp3.internal.platform.**

# Kotlin's `Result` and coroutine internals are referenced reflectively by Retrofit's suspend adapter.
-keepclassmembers class kotlin.Result { *; }
