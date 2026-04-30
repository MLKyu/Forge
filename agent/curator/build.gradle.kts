plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mingeek.forge.agent.curator"
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
    implementation(project(":agent:core"))
    implementation(project(":agent:orchestrator"))
    implementation(project(":runtime:core"))
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
}
