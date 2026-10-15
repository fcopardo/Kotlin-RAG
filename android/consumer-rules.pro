# Applied to apps that use the library (R8/ProGuard).
# ONNX Runtime's native code finds its Java classes and constructors by name (JNI); its AAR only
# keeps its telemetry classes, so a minified app crashed on the first embedding without these.
-keep class ai.onnxruntime.** { *; }
