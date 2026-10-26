import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

// Builds an extension the same way recloudstream/gradle does:
//   compile (JVM 1.8) -> dex (classes.dex) -> zip with manifest.json = .cs3
// plus the plain .jar that `isCrossPlatform = true` publishes, and a repo.json/plugins.json.
plugins {
    kotlin("jvm")
}

version = 1
val pluginName = "InternetArchive"
val pluginClass = "com.cloudstreamlinux.archive.InternetArchivePlugin"
val repoBaseUrl = (findProperty("repoBaseUrl") as String?) ?: "http://localhost:8765"

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

val dexer by configurations.creating

dependencies {
    // Provided by the host app (Android CloudStream or cloudstream-linux), never bundled.
    compileOnly(project(":android-shim"))
    dexer("de.femtopedia.dex2jar:d2j-external:2.4.38")
}

val outDir = layout.buildDirectory.dir("repo")
val dexDir = layout.buildDirectory.dir("dex")

val compileDex by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    classpath = dexer
    mainClass.set("com.android.dx.command.Main")
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    doFirst { dexDir.get().asFile.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--dex", "--min-sdk-version=26", "--output=${dexDir.get().file("classes.dex").asFile}", jarFile.get().asFile.path)
    })
}

val writeManifest by tasks.registering {
    val manifest = dexDir.map { it.file("manifest.json") }
    outputs.file(manifest)
    doLast {
        manifest.get().asFile.writeText(
            """{"name":"$pluginName","pluginClassName":"$pluginClass","version":${project.version},"requiresResources":false}"""
        )
    }
}

val makeCs3 by tasks.registering(Zip::class) {
    dependsOn(compileDex, writeManifest)
    from(dexDir) { include("classes.dex", "manifest.json") }
    archiveFileName.set("$pluginName.cs3")
    destinationDirectory.set(outDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val makeJar by tasks.registering(Copy::class) {
    from(tasks.jar) { rename { "$pluginName.jar" } }
    into(outDir)
}

fun sha256(f: File) = "sha256-" + MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

val makeRepo by tasks.registering {
    group = "cloudstream"
    description = "Writes a complete repository (repo.json, plugins.json, .cs3, .jar) to build/repo"
    dependsOn(makeCs3, makeJar)
    doLast {
        val dir = outDir.get().asFile
        val cs3 = dir.resolve("$pluginName.cs3")
        val jar = dir.resolve("$pluginName.jar")
        fun entry(withJar: Boolean) = buildString {
            append("""[{"name":"Internet Archive","internalName":"$pluginName","version":${project.version},"apiVersion":1,""")
            append(""""status":1,"authors":["cloudstream-linux"],"description":"Public-domain films from archive.org",""")
            append(""""language":"en","tvTypes":["Movie"],"url":"$repoBaseUrl/$pluginName.cs3",""")
            append(""""fileSize":${cs3.length()},"fileHash":"${sha256(cs3)}"""")
            if (withJar) append(""","jarUrl":"$repoBaseUrl/$pluginName.jar","jarFileSize":${jar.length()},"jarHash":"${sha256(jar)}"""")
            append("}]")
        }
        dir.resolve("plugins.json").writeText(entry(withJar = true))
        dir.resolve("plugins-cs3-only.json").writeText(entry(withJar = false))
        fun repo(name: String, list: String) =
            """{"name":"$name","description":"Example repository built by cloudstream-linux","manifestVersion":1,"pluginLists":["$repoBaseUrl/$list"]}"""
        dir.resolve("repo.json").writeText(repo("cloudstream-linux examples", "plugins.json"))
        dir.resolve("repo-cs3-only.json").writeText(repo("cloudstream-linux examples (cs3 only)", "plugins-cs3-only.json"))
        println("Repository written to $dir")
    }
}
