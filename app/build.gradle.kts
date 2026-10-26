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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.formdev:flatlaf:3.7")
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
    dependsOn(":examples:InternetArchiveProvider:makeRepo")
    systemProperty("example.repo", repo.get().asFile.absolutePath)
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
        addAll(listOf("jpackage", "--type", type, "--name", "cloudstream-linux", "--app-version", "1.0.0"))
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
            "jdk.localedata", "jdk.unsupported", "jdk.zipfs",
        ).joinToString(",")))
        if (type == "deb") {
            addAll(listOf("--linux-shortcut", "--linux-menu-group", "AudioVideo;Video", "--linux-app-category", "video"))
            addAll(listOf("--linux-deb-maintainer", "cloudstream-linux@users.noreply.github.com"))
            addAll(listOf("--linux-package-deps", "mpv | vlc | ffmpeg"))
        }
        application.applicationDefaultJvmArgs.forEach { addAll(listOf("--java-options", it)) }
    })
}
