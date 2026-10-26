package com.lagradost.cloudstream3.linux

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexMethodNode
import org.objectweb.asm.MethodVisitor
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A .cs3 is a zip of manifest.json + classes.dex (+ optional Android resources).
 * Android loads it with PathClassLoader; a desktop JVM cannot run Dalvik bytecode,
 * so we translate every classes*.dex to JVM .class files once and cache the jar,
 * keyed by the .cs3's sha256 so updates retranslate automatically.
 */
object DexTranslator {
    private val cacheDir get() = File(Platform.context.getCacheDir(), "translated")

    fun translate(cs3: File): File {
        val hash = RepositoryManager.sha256(cs3).removePrefix("sha256-")
        val out = File(cacheDir, "$hash.jar")
        if (out.exists()) return out
        cacheDir.mkdirs()

        val classesDir = Files.createTempDirectory("d2j").toFile()
        try {
            ZipFile(cs3).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .toList()
                require(dexEntries.isNotEmpty()) { "${cs3.name} contains no classes.dex" }
                for (entry in dexEntries) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    Dex2jar.from(bytes)
                        .reUseReg(false)
                        .topoLogicalSort()
                        .skipDebug(false)
                        .optimizeSynchronized()
                        // Kotlin uses JVM-legal names like `constructor-impl` (inline classes);
                        // dex2jar's default renaming would break linking against the stdlib.
                        .dontSanitizeNames(true)
                        .withExceptionHandler(LoggingHandler(cs3.name))
                        .to(classesDir.toPath())
                }
            }
            val tmp = File(cacheDir, "$hash.jar.tmp")
            zipDirectory(classesDir, tmp)
            tmp.renameTo(out)
        } finally {
            classesDir.deleteRecursively()
        }
        return out
    }

    private fun zipDirectory(dir: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** dex2jar's default would abort the whole plugin over one untranslatable method. */
    private class LoggingHandler(private val name: String) : DexExceptionHandler {
        override fun handleFileException(e: Exception) {
            System.err.println("dex2jar: $name: $e")
        }

        override fun handleMethodTranslateException(
            method: Method, methodNode: DexMethodNode, mv: MethodVisitor, e: Exception
        ) {
            System.err.println("dex2jar: $name: could not translate $method: $e")
        }
    }
}
