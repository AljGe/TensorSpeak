# ONNX Runtime uses reflection for EP / tensor type discovery.
-keep class ai.onnxruntime.** { *; }

# JNI: keep EspeakNative and any class that declares native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.github.aljge.tensorspeak.EspeakNative { *; }
