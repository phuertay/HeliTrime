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

// Prebuilt librime_jni.so is linked to c++_shared. The cmake early-return
// builds no native target, so AGP does not package the STL on its own.
val copyPrebuiltRimeStl = tasks.register("copyPrebuiltRimeStl") {
    val jniDir = file("src/main/jniLibs/arm64-v8a")
    val sdkDir = rootProject.file("local.properties").readLines()
        .first { it.startsWith("sdk.dir=") }.substringAfter("=")
    val ndkDir = file(sdkDir).resolve("ndk/${android.ndkVersion}")
    onlyIf {
        jniDir.resolve("librime_jni.so").isFile && !jniDir.resolve("libc++_shared.so").isFile
    }
    doLast {
        val host = ndkDir.resolve("toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK prebuilt host missing under $ndkDir")
        val stl = host.resolve("sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        check(stl.isFile) { "missing $stl" }
        stl.copyTo(jniDir.resolve("libc++_shared.so"))
    }
}
tasks.named("preBuild").configure { dependsOn(copyPrebuiltRimeStl) }

fun rimeDepsDir(): String {
    val candidates = listOf(
        rootProject.file("third_party/rime"),
        file("/tmp/rime-src"),
    )
    return candidates.firstOrNull { it.resolve("librime/CMakeLists.txt").exists() }?.absolutePath
        ?: candidates.first().absolutePath
}
