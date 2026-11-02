plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val cubismNativeDir = rootProject.file("third_party/cubism-sdk-native")
val motionSyncDir = rootProject.file("third_party/cubism-sdk-motionsync")
val motionSyncAppDir =
    motionSyncDir.resolve("Samples/OpenGL/Demo/proj.android.cmake/MotionSync/app")

android {
    namespace = "com.ondevice.digitalhuman"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.ondevice.digitalhuman"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DNATIVE_DIR=${cubismNativeDir.absolutePath}",
                    "-DSDK_ROOT_PATH=${motionSyncDir.absolutePath}",
                    "-DCSM_MOTION_DEMO=ON",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // MotionSync opens the CRI engine with dlopen(nativeLibraryDir).
            // AGP otherwise leaves that directory empty and loads libs from the APK.
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = motionSyncAppDir.resolve("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.named("main") {
        java.directories.add(motionSyncAppDir.resolve("src/main/java").absolutePath)
        assets.directories.add(
            cubismNativeDir.resolve("Samples/OpenGL/Shaders/StandardES").absolutePath,
        )
        assets.directories.add(
            cubismNativeDir.resolve("Framework/src/Rendering/OpenGL/Shaders/StandardES").absolutePath,
        )
        jniLibs.directories.add(motionSyncDir.resolve("Core/CRI/dll/Android").absolutePath)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
