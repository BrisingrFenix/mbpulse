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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mbPulse"
include(":sample-app")
include(":mbpulse")
include(":setup-wizard")
// template-app/ is NOT part of this build: it is a standalone build (own
// settings, wrapper, catalogue) that uses the published library, like every
// study app copied from it. Build it with
//   .\gradlew.bat -p template-app assembleDebug "-Pmbpulse.librarySource=.."
