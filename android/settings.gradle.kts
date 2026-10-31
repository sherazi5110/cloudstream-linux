// The Android app: a separate Gradle build so the Linux build and its CI stay untouched.
// The CloudStream library is compiled from the same pinned upstream checkout as the Linux app
// (run ../scripts/fetch-upstream.sh first).
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cloudstream-android"
include(":cs-library", ":app")
