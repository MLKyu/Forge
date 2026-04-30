# =============================================================================
# Forge — ProGuard / R8 keep rules
# =============================================================================
# Keep crash-line numbers; obfuscation otherwise stays on.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# =============================================================================
# JNI — llama.cpp
# =============================================================================
# Native methods on LlamaCppNative are bound by JNI symbol name, so the class
# and its native methods must keep their original names.
-keep class com.mingeek.forge.runtime.llamacpp.LlamaCppNative {
    public static <methods>;
}
-keepclasseswithmembernames class com.mingeek.forge.runtime.llamacpp.** {
    native <methods>;
}

# =============================================================================
# JNI — MediaPipe Tasks GenAI
# =============================================================================
# MediaPipe's native code calls back into Java for callbacks (result listeners,
# native handle wrappers); keep all of it.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# =============================================================================
# Moshi — generated adapters discovered by reflection
# =============================================================================
-keep,allowobfuscation,allowshrinking class kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoaderImpl
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
# Generated *_JsonAdapter classes
-keep,allowobfuscation class * extends com.squareup.moshi.JsonAdapter
-keep,allowobfuscation class **JsonAdapter { <init>(...); *; }
# @JsonClass annotated classes (data classes)
-keepclasseswithmembers,includedescriptorclasses class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
    <fields>;
}
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# =============================================================================
# Retrofit
# =============================================================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
# Retrofit-generated proxies need access to Continuation
-keep class kotlin.coroutines.Continuation

# =============================================================================
# OkHttp / Okio — runtime warnings about optional providers
# =============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# =============================================================================
# Kotlinx Coroutines
# =============================================================================
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# =============================================================================
# AndroidX DataStore (uses generated code in some configurations)
# =============================================================================
-dontwarn androidx.datastore.**

# =============================================================================
# Compose / Lifecycle / Navigation — generally R8-friendly, keep just in case.
# =============================================================================
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# =============================================================================
# Forge domain models — referenced by Moshi adapters and across modules.
# Keep public types so reflection-based access (DataStore JSON, debugging) works.
# =============================================================================
-keep class com.mingeek.forge.domain.** { *; }
-keep class com.mingeek.forge.data.storage.InstalledModel { *; }
-keep class com.mingeek.forge.data.storage.BenchmarkRecord { *; }
-keep class com.mingeek.forge.data.catalog.huggingface.Hf* { *; }
