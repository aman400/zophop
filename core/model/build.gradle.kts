plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

val isJitPack =
    providers.environmentVariable("JITPACK").orNull == "true" ||
        providers.gradleProperty("jitpack").orNull == "true"
val defaultAppVersion = providers.gradleProperty("ZOPHOP_VERSION").orElse("3.0.0")

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
    namespace = "dev.zophop.core.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroup
            artifactId = "core-model"
            version = sdkVersion

            pom {
                name.set("ZopHop Core Model")
                description.set("Core workflow, trigger, action, and plugin domain models for ZopHop.")
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
