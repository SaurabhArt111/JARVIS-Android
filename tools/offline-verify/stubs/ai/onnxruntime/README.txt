STUBS - NOT THE REAL LIBRARY, NEVER SHIPPED
These Java files re-declare the few ONNX Runtime classes that the app's single ORT file
(app/src/main/kotlin/com/jarvis/assistant/voice/OrtNeuralRunner.kt) uses, written from knowledge of the public
com.microsoft.onnxruntime:onnxruntime-android Java API. They exist only so tools/offline-verify/build_app.sh can type-check
that file where Maven Central is not reachable. A signature that is wrong here would only be caught by a real Gradle build.
Every method body throws.
