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
        google()
        mavenCentral()
        // BRouter (offline routing) is only published via JitPack. Restricted to its group.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.abrensch.brouter") }
        }
    }
}

rootProject.name = "Samizdat"
include(":app")
