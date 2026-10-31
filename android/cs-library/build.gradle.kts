// Compiles CloudStream's own `library` module (the extension API) for Android, straight from the
// pinned upstream checkout, like the official app does. Extensions (.cs3, Dalvik bytecode) link
// against these exact classes when the app loads them with a DexClassLoader.
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    kotlin("plugin.serialization")
}

val upstream = rootProject.file("../upstream/cloudstream/library/src")
val niceHttp = rootProject.file("../upstream/nicehttp/library/src/main/java")

check(upstream.isDirectory) { "Missing $upstream: run scripts/fetch-upstream.sh in the repository root first" }

kotlin {
    androidLibrary {
        namespace = "com.lagradost.api"
        compileSdk = 36
        minSdk = 23
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }

    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-nowarn")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }
        commonMain {
            kotlin.srcDir(upstream.resolve("commonMain/kotlin"))
            // Single Android target, so commonMain may use the JVM-only NiceHttp/OkHttp like upstream does.
            kotlin.srcDir(niceHttp)
            kotlin.srcDir("src/shim/kotlin")
            dependencies {
                // Same versions as upstream's version catalog (several are pinned for old Android TVs).
                api("androidx.annotation:annotation:1.10.0")
                api("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
                api("org.jsoup:jsoup:1.22.1")
                api("org.jetbrains.kotlinx:atomicfu:0.33.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                api("com.fleeksoft.ksoup:ksoup:0.2.6")
                api("io.ktor:ktor-http:3.5.0")
                api("org.mozilla:rhino:1.8.1")
                api("dev.whyoleg.cryptography:cryptography-core:0.6.0")
                api("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
                // NiceHttp
                api("com.squareup.okhttp3:okhttp:5.3.2")
                api("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2")
                api("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
            }
        }
        androidMain {
            kotlin.srcDir(upstream.resolve("androidMain/kotlin"))
            // jvmCommonMain minus the NewPipe-based YouTube extractor (NewPipe is jitpack-only).
            kotlin.srcDir(upstream.resolve("jvmCommonMain/kotlin"))
            kotlin.exclude("**/YoutubeExtractor.jvmCommon.kt")
            kotlin.srcDir("src/androidShim/kotlin")
        }
    }
}
