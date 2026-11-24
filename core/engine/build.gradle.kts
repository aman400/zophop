plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.zophop.core.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
    }
    packaging {
        resources {
            merges += "META-INF/services/dev.zophop.plugin.api.NodePluginModule"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    api(project(":core:plugin-api"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":plugins:sms"))
    testImplementation(project(":plugins:notification"))
    testImplementation(project(":plugins:ntfy"))
    testImplementation(project(":plugins:gotify"))
    testImplementation(project(":plugins:call"))
    testImplementation(project(":plugins:gps"))
}
