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
        maven { url = uri("https://maven.scandit.com") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_PROJECT (not FAIL_ON_PROJECT_REPOS): Kotlin/Wasm registers
    // project-level Ivy repos for Node.js + Yarn downloads. Those must win
    // during :webApp:wasmJsBrowserDistribution.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ImuFlux"
include(":app")
include(":shared")
include(":desktopApp")
include(":backofficeCore")
include(":webApp")
