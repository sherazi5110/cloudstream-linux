// Compiles CloudStream's own `library` module (the extension API) for plain JVM,
// straight from the pinned upstream checkout, so extensions link against the
// exact same classes and signatures they were built for on Android.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val upstream = rootProject.file("upstream/cloudstream/library/src")
val niceHttp = rootProject.file("upstream/nicehttp/library/src/main/java")

kotlin {
    jvm {
        // Extensions are compiled for JVM 1.8 (see TestPlugins/build.gradle.kts) and inline
        // library functions, so the library must not target a newer bytecode level.
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
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
            kotlin.srcDir("src/shim/kotlin")
            // Single JVM target, so commonMain may use JVM-only NiceHttp/OkHttp like upstream's Android build does.
            kotlin.srcDir(niceHttp)
            dependencies {
                api("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
                api("org.jsoup:jsoup:1.22.1")
                api("org.jetbrains.kotlinx:atomicfu:0.33.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
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
                api("org.json:json:20251224")
            }
        }
        jvmMain {
            kotlin.srcDir(upstream.resolve("jvmMain/kotlin"))
            kotlin.srcDir("src/jvmShim/kotlin")
            // jvmCommonMain minus the NewPipe-based YouTube extractor (NewPipe is jitpack-only).
            kotlin.srcDir(upstream.resolve("jvmCommonMain/kotlin"))
            kotlin.exclude("**/YoutubeExtractor.jvmCommon.kt")
            dependencies {
                api("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
            }
        }
    }
}

