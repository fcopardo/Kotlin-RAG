plugins {
    id("com.android.application")
}

android {
    namespace = "rag.sample"
    compileSdk = 36 // current AndroidX needs 36; the library itself compiles against 35
    defaultConfig {
        applicationId = "rag.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // ONNX Runtime and SQLite ship native code for four ABIs (~150 MB); phones need arm64 only.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // sample only
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // Several library modules register services under the same META-INF/services names.
        resources.merges += "META-INF/services/**"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("rag:android:0.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
}
