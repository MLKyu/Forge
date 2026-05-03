plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mingeek.forge.runtime.executorch"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":runtime:core"))
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    // PyTorch ExecuTorch Android bindings — provides LlmModule for .pte-based
    // text generation. License: BSD-3-Clause (free, no signup required).
    // QNN delegate is bundled but only activates on Snapdragon hardware.
    compileOnly(libs.executorch.android)
    runtimeOnly(libs.executorch.android)
}
