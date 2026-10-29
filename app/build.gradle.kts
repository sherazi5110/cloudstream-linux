plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // We are the host app, like upstream's :app module.
        optIn.addAll("com.lagradost.cloudstream3.InternalAPI", "com.lagradost.cloudstream3.Prerelease")
    }
}

dependencies {
    implementation(project(":android-shim"))
    // Translates Dalvik bytecode (classes.dex inside .cs3) to JVM bytecode.
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.11.0")
    // JavaFX 21 LTS (runs on the Java 21 Ubuntu ships); GPU-accelerated, CSS-styled UI.
    val fxPlatform = if (System.getProperty("os.arch") == "aarch64") "linux-aarch64" else "linux"
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:21.0.8:$fxPlatform")
    }
    // Many streaming sites serve WebP posters, which JavaFX cannot decode itself.
    implementation("com.github.usefulness:webp-imageio:0.11.0")
    // Built-in player: libVLC 3 (from the system's VLC packages) through JNA.
    implementation("uk.co.caprica:vlcj:4.12.1")
    testImplementation(kotlin("test"))
}

application {
    applicationName = "cloudstream-linux"
    mainClass.set("com.lagradost.cloudstream3.linux.MainKt")
    applicationDefaultJvmArgs = listOf(
        // Plugin bytecode comes from dex2jar and may reference Android classes we only stub.
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:-BytecodeVerificationRemote",
        "--enable-native-access=ALL-UNNAMED",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.test {
    useJUnitPlatform()
    // The tests load the example extension both as a real Dalvik .cs3 and as a cross-platform .jar.
    val repo = project(":examples:InternetArchiveProvider").layout.buildDirectory.dir("repo")
    val demo = project(":tests:DemoProvider").layout.buildDirectory.dir("repo")
    dependsOn(":examples:InternetArchiveProvider:makeRepo", ":tests:DemoProvider:makeRepo")
    systemProperty("example.repo", repo.get().asFile.absolutePath)
    systemProperty("demo.repo", demo.get().asFile.absolutePath)
    systemProperty("tests.dir", rootProject.file("tests").absolutePath)
    systemProperty("cloudstream.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
}

// Self-contained .deb (bundles a Java runtime) built with the JDK's jpackage:
//   ./gradlew :app:packageDeb  ->  app/build/jpackage/cloudstream-linux_<version>_amd64.deb
val packageDeb by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(tasks.installDist)
    val lib = layout.buildDirectory.dir("install/cloudstream-linux/lib")
    val out = layout.buildDirectory.dir("jpackage")
    val resources = layout.buildDirectory.dir("jpackage-resources")
    val packaging = rootProject.file("packaging/linux")
    doFirst {
        out.get().asFile.deleteRecursively()
        val res = resources.get().asFile.apply { mkdirs() }
        // jpackage picks up <name>.desktop from --resource-dir, so the link handler is registered on install.
        res.resolve("cloudstream-linux.desktop").writeText(
            packaging.resolve("cloudstream-linux.desktop").readText()
                .replace("@EXEC@", "/opt/cloudstream-linux/bin/cloudstream-linux")
                .replace("@ICON@", "/opt/cloudstream-linux/lib/cloudstream-linux.png")
        )
    }
    commandLine(buildList {
        // -PjpackageType=app-image builds the unpacked app without dpkg/fakeroot.
        val type = (findProperty("jpackageType") as String?) ?: "deb"
        addAll(listOf("jpackage", "--type", type, "--name", "cloudstream-linux", "--app-version", "2.0.0"))
        addAll(listOf("--input", lib.get().asFile.path, "--main-jar", "app.jar"))
        addAll(listOf("--main-class", application.mainClass.get(), "--dest", out.get().asFile.path))
        addAll(listOf("--resource-dir", resources.get().asFile.path))
        addAll(listOf("--icon", packaging.resolve("cloudstream-linux.png").path))
        addAll(listOf("--description", "Run CloudStream extensions on Linux"))
        addAll(listOf("--jlink-options", "--strip-debug --no-man-pages --no-header-files --strip-native-commands"))
        // Android gives extensions roughly java.base; the rest is for Swing, TLS, Jackson, Rhino and okhttp.
        addAll(listOf("--add-modules", listOf(
            "java.base", "java.desktop", "java.logging", "java.management", "java.naming", "java.net.http",
            "java.prefs", "java.scripting", "java.sql", "java.xml", "jdk.charsets", "jdk.crypto.ec",
            "jdk.localedata", "jdk.unsupported", "jdk.zipfs", "jdk.httpserver",
        ).joinToString(",")))
        if (type == "deb") {
            addAll(listOf("--linux-shortcut", "--linux-menu-group", "AudioVideo;Video", "--linux-app-category", "video"))
            addAll(listOf("--linux-deb-maintainer", "cloudstream-linux@users.noreply.github.com"))
            addAll(listOf("--linux-package-deps", "libvlc5, vlc-plugin-base"))
        }
        application.applicationDefaultJvmArgs.forEach { addAll(listOf("--java-options", it)) }
    })
}
