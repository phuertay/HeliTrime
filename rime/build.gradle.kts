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
// SDK lookup stays inside the action. Reading local.properties while the task
// is created fails CI, which has ANDROID_HOME and no local.properties.
val copyPrebuiltRimeStl = tasks.register("copyPrebuiltRimeStl") {
    val jniDir = file("src/main/jniLibs/arm64-v8a")
    val ndkVersionName = android.ndkVersion
    val sdkDir = findAndroidSdkDir()
    onlyIf {
        jniDir.resolve("librime_jni.so").isFile && !jniDir.resolve("libc++_shared.so").isFile
    }
    doLast {
        val root = sdkDir ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")
        val ndkDir = root.resolve("ndk/$ndkVersionName")
        val host = ndkDir.resolve("toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
            ?: error("NDK prebuilt host missing under $ndkDir")
        val stl = host.resolve("sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        check(stl.isFile) { "missing $stl" }
        stl.copyTo(jniDir.resolve("libc++_shared.so"))
    }
}

fun findAndroidSdkDir(): java.io.File? {
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        val line = local.readLines().firstOrNull { it.startsWith("sdk.dir=") }
        if (line != null) {
            val path = line.substringAfter("=").trim()
            if (path.isNotEmpty()) return file(path)
        }
    }
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!env.isNullOrBlank()) return file(env)
    return null
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
