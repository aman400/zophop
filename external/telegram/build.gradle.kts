plugins {
    id("com.android.application")
}

android {
    namespace = "dev.zophop.plugin.telegram"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.zophop.plugin.telegram"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("ZOPHOP_VERSION_CODE").map { it.toInt() }.orElse(1).get()
        versionName = providers.gradleProperty("ZOPHOP_VERSION").orElse("1.0.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE") ?: "release.keystore"
            val storeFileObj =
                file(keystoreFile).takeIf { it.exists() }
                    ?: rootProject.file(keystoreFile)
            if (storeFileObj.exists()) {
                storeFile = storeFileObj
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "zophop_secret"
                keyAlias = System.getenv("KEY_ALIAS") ?: "zophop_key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD") ?: "zophop_secret"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) releaseSigning else signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug") "-Debug" else ""
        val verName = android.defaultConfig.versionName ?: "1.0.0"
        variant.outputs.forEach { output ->
            output.outputFileName.set("ZopHop-Telegram-Plugin$suffix-v$verName.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:plugin-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
