// In-process embeddings with ONNX Runtime (SPEC §15.2). The runtime is compile-only so each app
// brings its own build: com.microsoft.onnxruntime:onnxruntime on desktop, onnxruntime-android on Android.
dependencies {
    api(project(":core"))
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
}
