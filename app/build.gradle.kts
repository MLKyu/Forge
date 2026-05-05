plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

private val appName = "Forge"
private val appVersionName = "1.0"
private val appVersionCode = 1

android {
    namespace = "com.mingeek.forge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mingeek.forge"
        minSdk = 36
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Until a proper release keystore exists, sign release with the debug key
            // so the artifact is installable. Replace before publishing.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/licenses/**",
                "META-INF/*.kotlin_module",
                "META-INF/native-image/**",
                "META-INF/proguard/**",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "kotlin/**.kotlin_builtins",
                "DebugProbesKt.bin",
            )
        }
        jniLibs {
            useLegacyPackaging = false
            // libc++_shared.so ships in both llama.cpp and ExecuTorch's fbjni;
            // pick one and let the others use it.
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Output APK filename: Forge-1.0-release.apk / Forge-1.0-debug.apk
// Gradle appends "-${variantName}" automatically when archivesName is set.
base {
    archivesName.set("$appName-$appVersionName")
}

dependencies {
    // Feature modules
    implementation(project(":feature:discover"))
    implementation(project(":feature:library"))
    implementation(project(":feature:catalog"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:compare"))
    implementation(project(":feature:workflows"))
    implementation(project(":feature:settings"))

    // Core
    implementation(project(":core:ui"))
    implementation(project(":core:common"))
    implementation(project(":core:hardware"))
    implementation(project(":domain"))

    // Data
    implementation(project(":data:catalog"))
    implementation(project(":data:discovery"))
    implementation(project(":data:download"))
    implementation(project(":data:storage"))
    implementation(project(":data:agents"))

    // Agent
    implementation(project(":agent:memory"))
    implementation(project(":agent:core"))

    // Runtime
    implementation(project(":runtime:core"))
    implementation(project(":runtime:registry"))
    implementation(project(":runtime:llamacpp"))
    implementation(project(":runtime:mediapipe"))
    implementation(project(":runtime:executorch"))
    implementation(project(":runtime:mlc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
