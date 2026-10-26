package com.lagradost.cloudstream3.linux

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * Parent of every plugin loader. Real implementations of the Android/app classes
 * plugins use come from android-shim; anything else under an Android package
 * (Fragment, View, R classes, ...) is generated here as an empty placeholder class,
 * so plugins that reference UI types for their settings screen still load and
 * their MainAPI providers work. Calling into such a placeholder fails, as expected.
 */
object AndroidStubLoader : ClassLoader("android-stubs", AndroidStubLoader::class.java.classLoader) {
    private val stubPrefixes = listOf("android.", "androidx.", "com.google.android.", "dalvik.", "com.lagradost.cloudstream3.")
    private val interfaceHints = listOf("Listener", "Callback", "Watcher", "Observer", "Provider\$Factory")

    val generated = sortedSetOf<String>()

    override fun findClass(name: String): Class<*> {
        if (stubPrefixes.none { name.startsWith(it) }) throw ClassNotFoundException(name)
        val isInterface = interfaceHints.any { name.substringAfterLast('.').contains(it) }
        val writer = ClassWriter(0)
        val internal = name.replace('.', '/')
        if (isInterface) {
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                internal, null, "java/lang/Object", null)
        } else {
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        synchronized(generated) { generated += name }
        return defineClass(name, bytes, 0, bytes.size)
    }
}

/**
 * One loader per plugin, like upstream's one PathClassLoader per .cs3.
 * Lookup order: host (library + shim) -> the plugin's own classes -> generated stubs.
 */
class PluginClassLoader(val jar: File, val original: File) :
    URLClassLoader(
        "plugin:${original.name}",
        listOfNotNull(jar, original.takeIf { it != jar }).map { it.toURI().toURL() }.toTypedArray(),
        PluginClassLoader::class.java.classLoader
    ) {

    override fun findClass(name: String): Class<*> = try {
        super.findClass(name)
    } catch (e: ClassNotFoundException) {
        AndroidStubLoader.loadClass(name)
    }

    /** Same detection the gradle plugin's CompileDexTask uses to write manifest.json. */
    fun findAnnotatedPluginClass(): String? = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }.firstNotNullOfOrNull { entry ->
            val node = ClassNode()
            ClassReader(zip.getInputStream(entry).readBytes()).accept(node, ClassReader.SKIP_CODE)
            val annotations = node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()
            node.name.replace('/', '.').takeIf {
                annotations.any { a -> a.desc == "Lcom/lagradost/cloudstream3/plugins/CloudstreamPlugin;" }
            }
        }
    }
}
