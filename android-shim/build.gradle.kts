// Linux implementations of the Android framework and CloudStream app-module classes
// that extensions link against. Names and signatures must match the originals.
plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        // Same bytecode level as extensions, which inline DataStore/CloudStreamApp.getKey.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        // We are the host app, like upstream's :app module.
        optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease")
    }
}

dependencies {
    api(project(":cs-library"))
}
