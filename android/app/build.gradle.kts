plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "io.github.sherazi5110.cloudstream"
    compileSdk = 36

    defaultConfig {
        // Installs next to the official app (com.lagradost.cloudstream3).
        applicationId = "io.github.sherazi5110.cloudstream"
        minSdk = 23
        targetSdk = 36
        // CI passes the release tag (-PappVersion=7.0.0); local builds use the numbers below.
        val tagVersion = (findProperty("appVersion") as String?)?.removePrefix("v")?.removePrefix("V")
        versionName = tagVersion ?: "7.0.0"
        versionCode = tagVersion?.let { v ->
            val (major, minor, patch) = (v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
            major * 10000 + minor * 100 + patch
        } ?: 70000
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key comes from the environment (GitHub Actions secrets, see android/README.md) and is never
    // committed. Without it, release builds are signed with the debug key: fine for testing, but each machine's
    // debug key differs, so such builds can't update each other.
    val keystore = System.getenv("CS_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("CS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CS_KEY_ALIAS")
            keyPassword = System.getenv("CS_KEY_PASSWORD") ?: System.getenv("CS_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Extensions are loaded at runtime and call into the library, Jackson, OkHttp, jsoup, Rhino…
            // by name, so nothing may be renamed or removed.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        // libVLC is ~40-60 MB per CPU type: compress it in the APK (it is extracted once on install).
        jniLibs.useLegacyPackaging = true
    }

    // One APK per CPU type (smaller downloads), plus a universal one that runs everywhere.
    // Most phones are arm64-v8a; many Android TV boxes and Fire TV sticks are armeabi-v7a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // We are the host app, like upstream's :app module.
        optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease")
    }
}

dependencies {
    implementation(project(":cs-library"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Posters and backdrops (OkHttp, so the app's DNS/cookie settings apply; WebP is native on Android).
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // The player: libVLC for Android (Maven Central), with this app's own controls.
    implementation("org.videolan.android:libvlc-all:3.6.5")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
