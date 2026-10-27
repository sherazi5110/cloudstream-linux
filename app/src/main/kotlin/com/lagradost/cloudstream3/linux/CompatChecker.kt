package com.lagradost.cloudstream3.linux

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * Static linkage check for an extension: every class, method and field it references
 * outside itself must exist in this runtime (CloudStream library + Android shim + JDK).
 * Nothing from the extension is loaded or executed, so it's safe on untrusted files.
 * Missing members are exactly what would surface as NoClassDefFoundError /
 * NoSuchMethodError at runtime, often swallowed by the extension as "no results".
 */
object CompatChecker {
    data class Issue(val kind: String, val owner: String, val member: String, val usedIn: String) {
        override fun toString() = "$kind ${owner.replace('/', '.')}${if (member.isNotEmpty()) ".$member" else ""}  (used in ${usedIn.replace('/', '.')})"
    }

    private val runtime: ClassLoader = CompatChecker::class.java.classLoader

    /** Accepts a .cs3 (translated statically first) or a .jar. */
    fun check(file: File): List<Issue> {
        val jar = if (file.extension == "jar") file else DexTranslator.translate(file)
        val own = ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.associate { entry ->
                val node = ClassNode()
                ClassReader(zip.getInputStream(entry).readBytes()).accept(node, ClassReader.SKIP_FRAMES)
                node.name to node
            }
        }
        val issues = linkedSetOf<Issue>()
        for (cls in own.values) {
            (listOfNotNull(cls.superName) + cls.interfaces).forEach { checkClass(it, own, cls.name, issues) }
            for (method in cls.methods) {
                val where = "${cls.name}.${method.name}"
                for (insn in method.instructions) {
                    when (insn) {
                        is MethodInsnNode -> if (insn.owner.startsWith("[")) Unit
                        else if (checkClass(insn.owner, own, where, issues) && resolveMethod(insn.owner, insn.name, insn.desc, own) == false) {
                            issues += Issue("missing method", insn.owner, insn.name + insn.desc, where)
                        }
                        is FieldInsnNode -> if (checkClass(insn.owner, own, where, issues) && resolveField(insn.owner, insn.name, own) == false) {
                            issues += Issue("missing field", insn.owner, insn.name, where)
                        }
                        is TypeInsnNode -> Type.getObjectType(insn.desc).let { t ->
                            val name = if (t.sort == Type.ARRAY) t.elementType.takeIf { it.sort == Type.OBJECT }?.internalName else t.internalName
                            if (name != null) checkClass(name, own, where, issues)
                        }
                    }
                }
            }
        }
        return issues.toList()
    }

    private fun load(name: String): Class<*>? =
        runCatching { Class.forName(name.replace('/', '.'), false, runtime) }.getOrNull()

    /** True if the class exists (in the extension or the runtime). */
    private fun checkClass(name: String, own: Map<String, ClassNode>, where: String, issues: MutableSet<Issue>): Boolean {
        if (name in own || load(name) != null) return true
        issues += Issue("missing class", name, "", where)
        return false
    }

    /** null = can't tell (e.g. unresolvable superclass, already reported). */
    private fun resolveMethod(owner: String, name: String, desc: String, own: Map<String, ClassNode>): Boolean? {
        own[owner]?.let { node ->
            if (node.methods.any { it.name == name && it.desc == desc }) return true
            val supers = listOfNotNull(node.superName) + node.interfaces
            return supers.map { resolveMethod(it, name, desc, own) }.let { r -> if (r.any { it == true }) true else if (r.any { it == null }) null else false }
        }
        val cls = load(owner) ?: return null
        if (name == "<init>") return cls.declaredConstructors.any { Type.getConstructorDescriptor(it) == desc }
        return hierarchy(cls).any { c ->
            c.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == desc } ||
                // Signature-polymorphic MethodHandle calls, and Object methods on interfaces.
                (c == java.lang.invoke.MethodHandle::class.java && c.declaredMethods.any { it.name == name && Modifier.isNative(it.modifiers) })
        } || (cls.isInterface && Any::class.java.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == desc })
    }

    private fun resolveField(owner: String, name: String, own: Map<String, ClassNode>): Boolean? {
        own[owner]?.let { node ->
            if (node.fields.any { it.name == name }) return true
            val supers = listOfNotNull(node.superName) + node.interfaces
            return supers.map { resolveField(it, name, own) }.let { r -> if (r.any { it == true }) true else if (r.any { it == null }) null else false }
        }
        val cls = load(owner) ?: return null
        return hierarchy(cls).any { c -> c.declaredFields.any { it.name == name } }
    }

    private fun hierarchy(cls: Class<*>): Sequence<Class<*>> = sequence {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque(listOf(cls))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            yield(c)
            c.superclass?.let { queue += it }
            queue += c.interfaces
        }
    }
}
