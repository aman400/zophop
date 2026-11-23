plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.zophop"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "dev.zophop"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE") ?: "release.keystore"
            val storeFileObj = file(keystoreFile)
            if (storeFileObj.exists()) {
                storeFile = storeFileObj
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "zophop_secret"
                keyAlias = System.getenv("KEY_ALIAS") ?: "zophop_key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD") ?: "zophop_secret"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "ZopHop Debug"
        }
        release {
            manifestPlaceholders["appName"] = "ZopHop"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) releaseSigning else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            merges += "META-INF/services/dev.zophop.plugin.api.NodePluginModule"
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as? com.android.build.api.variant.HasUnitTestBuilder)?.enableUnitTest = true
    }
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug") "-Debug" else ""
        val verName = android.defaultConfig.versionName ?: "2.0.0"
        variant.outputs.forEach { output ->
            output.outputFileName.set("ZopHop$suffix-v$verName.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.whenTaskAdded {
    if (name.startsWith("lintVital")) {
        enabled = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:plugin-api"))
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:ui"))
    implementation(project(":feature:workflows"))
    implementation(project(":feature:preconditions"))

    implementation(project(":plugins:call"))
    implementation(project(":plugins:sms"))
    implementation(project(":plugins:gps"))
    implementation(project(":plugins:ntfy"))
    implementation(project(":plugins:gotify"))
    implementation(project(":plugins:notification"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(project(":plugins:call"))
    testImplementation(project(":plugins:sms"))
    testImplementation(project(":plugins:gps"))
    testImplementation(project(":plugins:ntfy"))
    testImplementation(project(":plugins:gotify"))
    testImplementation(project(":plugins:notification"))
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.json)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
