import org.gradle.api.DefaultTask
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

abstract class SyncVersionTask @Inject constructor(
    private val layout: ProjectLayout
) : DefaultTask() {

    @get:Input
    abstract val appVersion: Property<String>

    @TaskAction
    fun sync() {
        val versionName = appVersion.get()

        logger.lifecycle("Syncing version '$versionName' across project documentation and release guides...")

        // 1. Update DOCUMENTATION.md
        val docFile = layout.projectDirectory.file("DOCUMENTATION.md").asFile
        if (docFile.exists()) {
            val updated = docFile.readText()
                .replace(Regex("""ZopHop-v\d+\.\d+\.\d+\.apk"""), "ZopHop-v$versionName.apk")
            docFile.writeText(updated)
            logger.lifecycle("Updated DOCUMENTATION.md -> v$versionName")
        }

        // 2. Update RELEASE_GUIDE.md
        val releaseGuideFile = layout.projectDirectory.file("RELEASE_GUIDE.md").asFile
        if (releaseGuideFile.exists()) {
            val updated = releaseGuideFile.readText()
                .replace(Regex("""<!-- APP_VERSION = "[^"]+" -->"""), "<!-- APP_VERSION = \"$versionName\" -->")
                .replace(Regex("""VERSION="[^"]+""""), "VERSION=\"$versionName\"")
                .replace(Regex("""ZopHop-v\d+\.\d+\.\d+\.apk"""), "ZopHop-v$versionName.apk")
            releaseGuideFile.writeText(updated)
            logger.lifecycle("Updated RELEASE_GUIDE.md -> VERSION=\"$versionName\"")
        }

        // 3. Update docs/*.html (updates const APP_VERSION variable across all HTML pages)
        listOf("docs/index.html", "docs/manual.html", "docs/plugins.html", "docs/releases.html").forEach { path ->
            val htmlFile = layout.projectDirectory.file(path).asFile
            if (htmlFile.exists()) {
                val updated = htmlFile.readText()
                    .replace(Regex("""const APP_VERSION = "[^"]+";"""), "const APP_VERSION = \"$versionName\";")
                htmlFile.writeText(updated)
                logger.lifecycle("Updated $path -> const APP_VERSION = \"$versionName\"")
            }
        }

        logger.lifecycle("✅ Version synchronization complete!")
    }
}

tasks.register<SyncVersionTask>("syncVersion") {
    group = "versioning"
    description = "Synchronizes ZOPHOP_VERSION across DOCUMENTATION.md, RELEASE_GUIDE.md, and docs/*.html"
    appVersion.set(providers.gradleProperty("ZOPHOP_VERSION").orElse("3.0.0"))
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        ignoreFailures = false
        config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            txt.required.set(true)
            html.required.set(true)
        }
        var prevVersion = ""
        doFirst {
            prevVersion = System.getProperty("java.version") ?: ""
            System.setProperty("java.version", "21.0.0")
        }
        doLast {
            if (prevVersion.isNotEmpty()) {
                System.setProperty("java.version", prevVersion)
            }
        }
    }
}
