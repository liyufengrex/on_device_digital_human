plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

val cubismNativeDir = project.file("sdk/cubism-sdk-native")
val motionSyncDir = project.file("sdk/cubism-sdk-motionsync")

android {
    namespace = "com.ondevice.live2d"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(
            cubismNativeDir.resolve("Framework/src/Rendering/OpenGL/Shaders/StandardES").absolutePath,
        )
        variant.sources.jniLibs?.addStaticSourceDirectory(
            motionSyncDir.resolve("Core/CRI/dll/Android").absolutePath,
        )
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
}
