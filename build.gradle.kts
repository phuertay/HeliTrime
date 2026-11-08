// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    val kotlinVersion = "2.4.0"
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath(kotlin("gradle-plugin", version = kotlinVersion))

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        // Maven Central redirects this jar to a GitHub release, and that URL
        // returns 504. The mirror hosts the jar itself.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
            }
            filter {
                includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
            }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
