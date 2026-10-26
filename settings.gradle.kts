pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "cloudstream-linux"
include(":cs-library", ":android-shim", ":app")
include(":examples:InternetArchiveProvider", ":tests:DemoProvider")
