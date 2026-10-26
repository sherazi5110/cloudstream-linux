import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// An extension built the same way recloudstream/gradle builds them (see ../extension.gradle.kts).
plugins {
    kotlin("jvm")
}

version = 1
extra["pluginName"] = "InternetArchive"
extra["pluginDisplayName"] = "Internet Archive"
extra["pluginClass"] = "com.cloudstreamlinux.archive.InternetArchivePlugin"
extra["pluginDescription"] = "Public-domain films from archive.org"
extra["pluginTypes"] = "\"Movie\""

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

apply(from = "../extension.gradle.kts")
