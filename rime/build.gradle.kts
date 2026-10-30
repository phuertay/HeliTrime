plugins {
    id("com.android.library")
}

android {
    namespace = "helium314.keyboard.latin.rime"
    compileSdk = 37
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DRIME_DEPS_DIR=" + rimeDepsDir(),
                )
                cppFlags += "-std=c++17"
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

    sourceSets {
        getByName("androidTest") {
            assets.srcDir(rootProject.file("app/src/main/assets/rime"))
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

fun rimeDepsDir(): String {
    val candidates = listOf(
        rootProject.file("third_party/rime"),
        file("/tmp/rime-src"),
    )
    return candidates.firstOrNull { it.resolve("librime/CMakeLists.txt").exists() }?.absolutePath
        ?: candidates.first().absolutePath
}
