plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mingeek.forge.runtime.registry"
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
    implementation(project(":core:hardware"))
    implementation(project(":data:storage"))
    implementation(libs.kotlinx.coroutines.android)
}
