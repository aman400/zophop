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
        val versionName = appVersion.get().trim().ifBlank { "1.0.0" }
        val semverPattern = """[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[A-Za-z0-9.]+)?"""

        logger.lifecycle("Syncing ZOPHOP_VERSION '$versionName' across docs/version.js and markdown documentation...")

        fun replaceVersionTokens(content: String): String =
            content
                .replace(Regex("""ZopHop-v$semverPattern\.apk"""), "ZopHop-v$versionName.apk")
                .replace(Regex("""ZopHop-Telegram-Plugin-v$semverPattern\.apk"""), "ZopHop-Telegram-Plugin-v$versionName.apk")
                .replace(Regex("""ZopHop-SMTP-Plugin-v$semverPattern\.apk"""), "ZopHop-SMTP-Plugin-v$versionName.apk")
                .replace(Regex("""zophop-plugin-api-v$semverPattern\.aar"""), "zophop-plugin-api-v$versionName.aar")
                .replace(Regex("""zophop-core-model-v$semverPattern\.aar"""), "zophop-core-model-v$versionName.aar")
                .replace(Regex("""com\.github\.aman400\.zophop:plugin-api:v$semverPattern"""), "com.github.aman400.zophop:plugin-api:v$versionName")
                .replace(Regex("""com\.github\.aman400\.zophop:core-model:v$semverPattern"""), "com.github.aman400.zophop:core-model:v$versionName")

        // 1. Update docs/version.js (single source of truth consumed by all docs/*.html pages)
        val versionJsFile = layout.projectDirectory.file("docs/version.js").asFile
        versionJsFile.writeText(
            """
            // Auto-generated from gradle.properties (ZOPHOP_VERSION) via `./gradlew syncVersion`.
            // Default fallback in docs.js when undefined is "1.0.0".
            const APP_VERSION = "$versionName";
            """.trimIndent() + "\n",
        )
        logger.lifecycle("Updated docs/version.js -> APP_VERSION = \"$versionName\"")

        // 2. Update README.md
        val readmeFile = layout.projectDirectory.file("README.md").asFile
        if (readmeFile.exists()) {
            val updated =
                replaceVersionTokens(readmeFile.readText())
                    .replace(Regex("""Version-v$semverPattern-10b981"""), "Version-v$versionName-10b981")
                    .replace(Regex("""## Key Features \(v$semverPattern\)"""), "## Key Features (v$versionName)")
            readmeFile.writeText(updated)
            logger.lifecycle("Updated README.md -> v$versionName")
        }

        // 3. Update DOCUMENTATION.md
        val docFile = layout.projectDirectory.file("DOCUMENTATION.md").asFile
        if (docFile.exists()) {
            val updated = replaceVersionTokens(docFile.readText())
            docFile.writeText(updated)
            logger.lifecycle("Updated DOCUMENTATION.md -> v$versionName")
        }

        // 4. Update RELEASE_GUIDE.md
        val releaseGuideFile = layout.projectDirectory.file("RELEASE_GUIDE.md").asFile
        if (releaseGuideFile.exists()) {
            val updated =
                replaceVersionTokens(releaseGuideFile.readText())
                    .replace(Regex("""<!-- APP_VERSION = "[^"]+" -->"""), "<!-- APP_VERSION = \"$versionName\" -->")
            releaseGuideFile.writeText(updated)
            logger.lifecycle("Updated RELEASE_GUIDE.md -> v$versionName")
        }

        logger.lifecycle("✅ Version synchronization complete!")
    }
}

tasks.register<SyncVersionTask>("syncVersion") {
    group = "versioning"
    description = "Synchronizes ZOPHOP_VERSION (default 1.0.0) across docs/version.js, README.md, DOCUMENTATION.md, and RELEASE_GUIDE.md"
    appVersion.set(providers.gradleProperty("ZOPHOP_VERSION").orElse("1.0.0"))
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
