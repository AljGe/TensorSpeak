# ONNX Runtime uses reflection for EP / tensor type discovery.
-keep class ai.onnxruntime.** { *; }

# JNI: keep EspeakNative and any class that declares native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.github.aljge.tensorspeak.EspeakNative { *; }

# OkHttp / Okio (cloud TTS). Consumer rules usually cover this; keep the Kotlin
# extension entry points so release minify cannot strip request body helpers.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
