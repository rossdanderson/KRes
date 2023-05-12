plugins {
    kotlin("multiplatform") version "1.8.21"
}

group = "me.rossd"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    mingwX64("native") {
        binaries {
            executable {
                entryPoint = "main"
                linkerOpts("-Wl,--subsystem,windows")
            }
        }
    }
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0-RC")
                implementation("com.squareup.okio:okio:3.3.0")
            }
        }
        val nativeTest by getting
    }
}
