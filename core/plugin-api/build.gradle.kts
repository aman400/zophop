plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    `maven-publish`
}

val isJitPack =
    providers.environmentVariable("JITPACK").orNull == "true" ||
        providers.gradleProperty("jitpack").orNull == "true"
val defaultAppVersion = providers.gradleProperty("ZOPHOP_VERSION").orElse("1.0.0")

val sdkVersion =
    if (isJitPack) {
        providers.gradleProperty("version")
            .orElse(providers.environmentVariable("VERSION"))
            .orElse(defaultAppVersion)
            .get()
    } else {
        providers.gradleProperty("publishVersion")
            .orElse(providers.environmentVariable("PUBLISH_VERSION"))
            .orElse(defaultAppVersion)
            .get()
            .removePrefix("v")
    }

val sdkGroup =
    if (isJitPack) {
        providers.gradleProperty("publishGroup")
            .orElse("com.github.aman400.zophop")
            .get()
    } else {
        "dev.zophop"
    }

group = sdkGroup
version = sdkVersion

android {
    namespace = "dev.zophop.plugin.api"
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

    sourceSets {
        getByName("main") {
            aidl.srcDirs("src/main/aidl")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroup
            artifactId = "plugin-api"
            version = sdkVersion

            pom {
                name.set("ZopHop Plugin API")
                description.set("AIDL contract and SDK models for building external ZopHop workflow plugins.")
                url.set("https://github.com/aman400/zophop")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            val repo =
                providers.environmentVariable("GITHUB_REPOSITORY")
                    .orElse("aman400/zophop")
                    .get()
            url = uri("https://maven.pkg.github.com/$repo")
            credentials {
                username =
                    providers.environmentVariable("GITHUB_ACTOR")
                        .orElse(providers.gradleProperty("gpr.user"))
                        .orNull
                password =
                    providers.environmentVariable("GITHUB_TOKEN")
                        .orElse(providers.gradleProperty("gpr.key"))
                        .orNull
            }
        }
    }
}
