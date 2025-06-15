plugins {
    kotlin("multiplatform") version "2.0.0"
}

group = "com.monkopedia"
version = "1.0"

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.monkopedia.frameworkled.main"
            }
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
}
