# JARVIS release shrinking rules.
# Components are referenced from the manifest (kept by AGP). ONNX Runtime uses JNI and reflection.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# Tool classes are looked up by name in logs only; nothing else is reflective.
-keepattributes SourceFile,LineNumberTable
