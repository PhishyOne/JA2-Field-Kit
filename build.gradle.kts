plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.android.application") version "9.3.2" apply false
}

allprojects {
    group = "com.phishtopia.ja2fieldkit"
    version = "0.1.0-SNAPSHOT"

    repositories {
        google()
        mavenCentral()
    }
}
