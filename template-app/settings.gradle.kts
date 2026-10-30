// A study app is its own Gradle build: it uses the mbPulse library as a pinned
// Maven artifact (gradle/libs.versions.toml → "mbpulse"), not as source.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The library, published with `.\gradlew.bat :mbpulse:publishToMavenLocal`
        // in the mbPulse repository. Hosted elsewhere (GitHub Packages,
        // JitPack, …)? Add that repository here instead.
        mavenLocal { content { includeGroup("pt.ua.mbpulse") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "mystudy"   // TODO(agent): your app's name (kebab-case)

// Developing the library and this app together: build against the library's
// SOURCE instead of the published version, without publishing —
//   .\gradlew.bat assembleDebug "-Pmbpulse.librarySource=<path to the mbPulse repository>"
// (inside the repository: "-Pmbpulse.librarySource=.."). Never needed to build a study.
providers.gradleProperty("mbpulse.librarySource").orNull?.let { path ->
    includeBuild(path) {
        dependencySubstitution {
            substitute(module("pt.ua.mbpulse:mbpulse")).using(project(":mbpulse"))
        }
    }
}
