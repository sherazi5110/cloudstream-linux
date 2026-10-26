package com.lagradost.cloudstream3.linux

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.extractorApis
import android.content.res.Resources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Same fields as upstream PluginData, plus the repository it came from. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
    val repositoryUrl: String? = null,
    val name: String? = null,
)

class LoadedPlugin(
    val data: PluginData,
    val manifest: BasePlugin.Manifest,
    val instance: BasePlugin,
    val loader: PluginClassLoader,
) {
    val providers: List<MainAPI> get() = APIHolder.allProviders.filter { it.sourcePlugin == instance.filename }
    val extractorCount: Int get() = extractorApis.count { it.sourcePlugin == instance.filename }
}

data class LoadProgress(val done: Int, val total: Int) {
    val running: Boolean get() = done < total
}

object PluginManager {
    private const val PLUGINS_KEY = "PLUGINS_KEY"
    private val prefs get() = Platform.context.getSharedPrefs()

    val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    val failures = ConcurrentHashMap<String, Throwable>()

    private val _progress = MutableStateFlow(LoadProgress(0, 0))
    val progress: StateFlow<LoadProgress> = _progress

    /** Bumped whenever the set of providers changes, so screens can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private fun changed() {
        _version.value++
    }

    /** register* calls in Plugin.load mutate global lists; translation and class loading stay parallel. */
    private val registerLock = Any()

    fun installed(): List<PluginData> =
        prefs.getString(PLUGINS_KEY, null)?.let { mapper.readValue<List<PluginData>>(it) } ?: emptyList()

    private fun saveInstalled(list: List<PluginData>) = synchronized(this) {
        prefs.edit().putString(PLUGINS_KEY, mapper.writeValueAsString(list)).apply()
    }

    private fun record(data: PluginData) = synchronized(this) {
        saveInstalled(installed().filter { it.internalName != data.internalName } + data)
    }

    /**
     * Loads every installed plugin, like upstream's loadAllOnlinePlugins + loadAllLocalPlugins,
     * but in parallel: first-run .cs3 translation is CPU bound and dominates startup.
     */
    suspend fun loadAll(): Map<String, Throwable> = coroutineScope {
        Platform.init()
        val all = installed()
        _progress.value = LoadProgress(0, all.size)
        all.map { data ->
            async(Engine.dispatcher) {
                val file = File(data.filePath)
                if (!file.exists()) failures[data.internalName] = IllegalStateException("Missing file ${data.filePath}")
                else loadPlugin(file, data)
                _progress.value = _progress.value.let { it.copy(done = it.done + 1) }
            }
        }.awaitAll()
        System.err.println("I/plugins: loaded ${loaded.size}/${all.size} extensions")
        changed()
        failures
    }

    /** Port of upstream PluginManager.loadPlugin (app/.../plugins/PluginManager.kt). */
    fun loadPlugin(file: File, data: PluginData): Result<LoadedPlugin> = runCatching {
        loaded[file.absolutePath]?.let { return@runCatching it }

        // .jar = cross-platform build, already JVM bytecode. .cs3 = Dalvik, translate first.
        val jar = if (file.extension == "jar") file else DexTranslator.translate(file)
        val loader = PluginClassLoader(jar, file)

        val manifest = loader.getResourceAsStream("manifest.json")?.use {
            mapper.readValue<BasePlugin.Manifest>(it.readBytes())
        } ?: BasePlugin.Manifest().apply {
            name = data.name ?: data.internalName
            version = data.version
            pluginClassName = loader.findAnnotatedPluginClass()
        }
        val className = manifest.pluginClassName ?: error("No plugin class found in ${file.name}")

        val instance = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
        instance.filename = file.absolutePath
        if (manifest.requiresResources) (instance as? Plugin)?.resources = Resources.EMPTY

        synchronized(registerLock) {
            if (instance is Plugin) instance.load(Platform.context) else instance.load()
        }

        LoadedPlugin(data.copy(version = manifest.version ?: data.version), manifest, instance, loader).also {
            loaded[file.absolutePath] = it
            failures.remove(data.internalName)
            changed()
        }
    }.onFailure {
        failures[data.internalName] = it
        System.err.println("Failed to load ${file.name}: $it")
    }

    /** Port of upstream unloadPlugin: removes everything the plugin registered. */
    fun unloadPlugin(absolutePath: String) {
        val plugin = loaded.remove(absolutePath) ?: return
        runCatching { plugin.instance.beforeUnload() }
        val owner = plugin.instance.filename
        APIHolder.apis.filter { it.sourcePlugin == owner }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == owner } }
        extractorApis.withLock { extractorApis.removeAll { it.sourcePlugin == owner } }
        runCatching { plugin.loader.close() }
        changed()
    }

    /** Downloads (preferring the native .jar when the repo publishes one) and loads a plugin. */
    suspend fun install(wrapper: PluginWrapper): Result<LoadedPlugin> {
        val p = wrapper.plugin
        val useJar = p.jarUrl != null
        val target = RepositoryManager.pluginPath(p.internalName, wrapper.repositoryData.url, if (useJar) "jar" else "cs3")
        installed().find { it.internalName == p.internalName }?.let { uninstall(it.internalName) }
        return runCatching {
            if (useJar) RepositoryManager.download(p.jarUrl!!, target, p.jarHash)
            else RepositoryManager.download(p.url, target, p.fileHash)
        }.mapCatching { file ->
            val data = PluginData(p.internalName, p.url, true, file.absolutePath, p.version, wrapper.repositoryData.url, p.name)
            record(data)
            loadPlugin(file, data).getOrThrow()
        }
    }

    /** Installs a local .cs3 or .jar, like dropping a file into the Android "local plugins" folder. */
    fun sideload(source: File): Result<LoadedPlugin> = runCatching {
        require(source.extension in setOf("cs3", "jar")) { "Expected a .cs3 or .jar file" }
        val target = File(RepositoryManager.extensionsDir, "local/${source.name}")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        unloadPlugin(target.absolutePath)
        val data = PluginData(source.nameWithoutExtension, null, false, target.absolutePath, 0, null, source.nameWithoutExtension)
        record(data)
        loadPlugin(target, data).getOrThrow()
    }

    fun uninstall(internalName: String) {
        val data = installed().find { it.internalName == internalName } ?: return
        unloadPlugin(File(data.filePath).absolutePath)
        File(data.filePath).delete()
        saveInstalled(installed().filter { it.internalName != internalName })
    }

    /** Returns the plugins whose repository advertises a higher version, and installs them. */
    suspend fun updateAll(): List<String> {
        val byRepo = installed().filter { it.isOnline && it.repositoryUrl != null }.groupBy { it.repositoryUrl!! }
        val updated = mutableListOf<String>()
        for ((repoUrl, plugins) in byRepo) {
            val repoData = RepositoryManager.getRepositories().find { it.url == repoUrl } ?: continue
            val available = RepositoryManager.getRepoPlugins(repoData) ?: continue
            for (local in plugins) {
                val remote = available.find { it.plugin.internalName == local.internalName } ?: continue
                if (remote.plugin.version > local.version && install(remote).isSuccess) updated += local.internalName
            }
        }
        return updated
    }
}
