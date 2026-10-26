import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test fixture: a demo extension with series, slow and dead sources (needs tests/mock-archive/server.py).
plugins {
    kotlin("jvm")
}

version = 1
extra["pluginName"] = "Demo"
extra["pluginClass"] = "com.cloudstreamlinux.demo.DemoPlugin"
extra["pluginDescription"] = "Offline test catalogue served by tests/mock-archive (UI tests, screenshots)"
extra["pluginTypes"] = "\"Movie\",\"TvSeries\""

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Same flags as the official plugin template; class-based lambdas keep the
        // bytecode free of invokedynamic so the legacy dx dexer accepts it.
        freeCompilerArgs.addAll(
            "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions",
            "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline",
        )
    }
}

dependencies {
    // Provided by the host app (Android CloudStream or cloudstream-linux), never bundled.
    compileOnly(project(":android-shim"))
}

apply(from = "../../examples/extension.gradle.kts")
