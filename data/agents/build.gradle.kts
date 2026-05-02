plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mingeek.forge.data.agents"
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
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":agent:memory"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.moshi.kotlin)
}
