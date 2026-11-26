pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZopHop"
include(":app")
include(":core:model")
include(":core:plugin-api")
include(":core:data")
include(":core:engine")
include(":core:ui")
include(":feature:workflows")
include(":feature:preconditions")
include(":feature:backup")
include(":plugins:call")
include(":plugins:sms")
include(":plugins:gps")
include(":plugins:ntfy")
include(":plugins:gotify")
include(":plugins:notification")
include(":external:telegram")
include(":external:smtp")
