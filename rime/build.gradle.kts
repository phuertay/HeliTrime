plugins {
    id("com.android.library")
}

android {
    namespace = "helium314.keyboard.latin.pinyin"
    compileSdk = 37
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 21
        externalNativeBuild {
            cmake {
                // Debug CI stays arm64-only. Pass -Prime_abis=x86_64 when the host
                // cannot boot an arm64 emulator and the instrumented test needs it.
                val rime_abis = ((project.findProperty("rime_abis") ?: rootProject.findProperty("rime_abis")) as String?)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: listOf("arm64-v8a")
                abiFilters(*rime_abis.toTypedArray())
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
