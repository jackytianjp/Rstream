plugins {
    alias(libs.plugins.android.application)
}

android {
    // 内置中继（Go 编译的 arm64 可执行文件，按 .so 打包进 jniLibs）：别让 NDK 去 strip 它
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libtsrelay.so"
            // 必须让安装时把 .so 解压出来（默认 extractNativeLibs=false 时，
            // nativeLibraryDir 指向 APK 内部，没法 exec 里面的中继二进制）
            useLegacyPackaging = true
        }
    }

    namespace = "com.takano.rstream"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.takano.rstream"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
}
