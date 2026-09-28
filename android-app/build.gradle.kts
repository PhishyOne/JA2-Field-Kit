import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

val testKeystorePath = providers.environmentVariable("FIELD_KIT_TEST_KEYSTORE_PATH").orNull
val testKeystorePassword = providers.environmentVariable("FIELD_KIT_TEST_KEYSTORE_PASSWORD").orNull
check((testKeystorePath == null && testKeystorePassword == null) ||
    (!testKeystorePath.isNullOrBlank() && !testKeystorePassword.isNullOrBlank())) {
    "Test signing requires both FIELD_KIT_TEST_KEYSTORE_PATH and FIELD_KIT_TEST_KEYSTORE_PASSWORD, nonblank, or neither"
}

android {
    namespace = "com.phishtopia.ja2fieldkit.android"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    if (testKeystorePath != null && testKeystorePassword != null) {
        signingConfigs.getByName("debug") {
            storeFile = file(testKeystorePath)
            storePassword = testKeystorePassword
            keyPassword = testKeystorePassword
            storeType = "PKCS12"
            keyAlias = "ja2-field-kit-test"
        }
    }

    defaultConfig {
        applicationId = "com.phishtopia.ja2fieldkit"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.systemProperty("androidAppProjectDir", projectDir.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.13.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.10")
}
