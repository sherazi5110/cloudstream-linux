package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """cloudstream-linux — run CloudStream extensions on Linux

  cloudstream-linux                       open the desktop app
  repo add <url|shortcode>                add an extension repository
  repo list | repo remove <url>
  plugins [repo-url]                      list plugins available in your repositories
  install <internalName|all> [repo-url]   download and load a plugin (.jar if published, else .cs3)
  update                                  update installed plugins to the latest repo versions
  installed | uninstall <internalName>
  sideload <file.cs3|file.jar>            install a local plugin file
  providers                               list loaded providers (MainAPI)
  home <provider>                         show the provider's home page sections
  search <query> [--provider NAME]
  info <provider> <url>                   load details and list playable items
  links <provider> <url> [--item N]       resolve stream links for item N (default 1)
  key get <key> | key set <key> <value>   read/write the key store extensions use (CloudStreamApp.getKey)
  doctor                                  print diagnostics for bug reports
  compat <file.cs3|file.jar>              check an extension for APIs missing on Linux (doesn't run it)
"""

fun main(args: Array<String>) {
    // cloudstreamrepo:// links are registered as a URL handler by the .desktop file, like
    // the intent filter in upstream AndroidManifest.xml.
    val repoLink = args.firstOrNull()?.takeIf { it.startsWith("cloudstreamrepo://") || it.startsWith("https://cs.repo") }
    if (args.isEmpty() || args[0] == "gui" || repoLink != null) {
        Logs.init()
        Platform.init()
        Network.init()
        // The window opens immediately; extensions load in the background (see ui.AppWindow).
        com.lagradost.cloudstream3.linux.ui.DesktopApp.launch(repoLink)
        return
    }
    val code = runBlocking { runCatching { cli(args.toList()) } }.fold({ it }, {
        System.err.println("error: ${it.message ?: it}")
        1
    })
    exitProcess(code)
}

private fun List<String>.option(name: String): String? = indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
private fun List<String>.positional(): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < size) {
        if (this[i].startsWith("--")) i += 2 else out += this[i++]
    }
    return out
}

private fun requireProvider(name: String): MainAPI =
    Engine.provider(name) ?: error("No provider '$name'. Loaded: ${Engine.providers().joinToString { it.name }}")

private suspend fun loadItem(api: MainAPI, url: String): LoadResponse =
    Engine.load(api, url) ?: error("${api.name} returned nothing for $url")

suspend fun cli(args: List<String>): Int {
    Platform.init()
    Network.init()
    val pos = args.positional()
    val needsPlugins = pos[0] in setOf("providers", "home", "search", "info", "links", "doctor")
    if (needsPlugins) PluginManager.loadAll().forEach { (n, e) -> System.err.println("warn: $n failed to load: $e") }

    when (pos[0]) {
        "repo" -> when (pos.getOrNull(1)) {
            "add" -> RepositoryManager.addRepository(pos[2]).let { println("Added ${it.name} -> ${it.url}") }
            "remove" -> RepositoryManager.removeRepository(pos[2]).also { println("Removed ${pos[2]}") }
            else -> RepositoryManager.getRepositories().forEach { println("${it.name}\t${it.url}") }
        }
        "plugins" -> {
            val installed = PluginManager.installed().associateBy { it.internalName }
            RepositoryManager.getRepositories().filter { pos.size < 2 || it.url == pos[1] }.forEach { repo ->
                println("== ${repo.name} (${repo.url})")
                RepositoryManager.getRepoPlugins(repo).orEmpty().forEach { (_, _, p) ->
                    val mark = installed[p.internalName]?.let { if (it.version < p.version) "U" else "*" } ?: " "
                    val kind = if (p.jarUrl != null) "jar" else "cs3"
                    println("$mark ${p.internalName.padEnd(28)} v${p.version}  ${p.language ?: "--"}  $kind  ${statusName(p.status)}  ${p.description.orEmpty().take(60)}")
                }
            }
            println("(* installed, U update available)")
        }
        "install" -> {
            val repos = RepositoryManager.getRepositories().filter { pos.size < 3 || it.url == pos[2] }
            val all = repos.flatMap { RepositoryManager.getRepoPlugins(it).orEmpty() }
            val targets = if (pos[1] == "all") all.filter { it.plugin.status != 0 }
            else listOfNotNull(all.find { it.plugin.internalName.equals(pos[1], true) || it.plugin.name.equals(pos[1], true) })
            require(targets.isNotEmpty()) { "No plugin '${pos[1]}' in your repositories" }
            targets.forEach { w ->
                PluginManager.install(w).fold(
                    { println("Installed ${w.plugin.name} v${w.plugin.version}: ${it.providers.joinToString { p -> p.name }.ifEmpty { "no providers" }}") },
                    { println("FAILED ${w.plugin.name}: $it") })
            }
        }
        "update" -> PluginManager.updateAll().let { println(if (it.isEmpty()) "Everything up to date" else "Updated: ${it.joinToString()}") }
        "installed" -> PluginManager.installed().forEach { println("${it.internalName}\tv${it.version}\t${it.filePath}") }
        "uninstall" -> PluginManager.uninstall(pos[1]).also { println("Uninstalled ${pos[1]}") }
        "sideload" -> PluginManager.sideload(File(pos[1])).getOrThrow().let {
            println("Loaded ${it.manifest.name} (${it.manifest.pluginClassName}): providers=${it.providers.map { p -> p.name }} extractors=${it.extractorCount}")
            if (AndroidStubLoader.generated.isNotEmpty()) println("Stubbed Android classes: ${AndroidStubLoader.generated}")
        }
        "providers" -> Engine.providers().forEach {
            println("${it.name.padEnd(24)} ${it.lang}  ${it.supportedTypes.joinToString(",")}  ${it.mainUrl}  [${it.sourcePlugin?.let { f -> File(f).name } ?: "built-in"}]")
        }
        "home" -> Engine.home(listOf(requireProvider(pos[1]))).toList().sortedBy { it.order }.forEach { row ->
            println("== ${row.list.name}")
            row.list.list.forEach { println("  ${it.name}\t${it.url}") }
        }
        "search" -> {
            val apis = args.option("--provider")?.let { listOf(requireProvider(it)) } ?: Engine.providers()
            Engine.search(pos.drop(1).joinToString(" "), apis).collect { batch ->
                batch.results.forEach { r -> println("[${batch.api.name}] ${r.name} (${r.type ?: "?"})\t${r.url}") }
            }
        }
        "info" -> {
            val api = requireProvider(pos[1])
            val item = loadItem(api, pos[2])
            println("${item.name} (${item.year ?: "?"}) — ${item.type}")
            item.plot?.let { println(it) }
            Engine.playables(item).forEachIndexed { i, p -> println("  ${i + 1}. ${p.label}") }
        }
        "links" -> {
            val api = requireProvider(pos[1])
            val item = loadItem(api, pos[2])
            val playables = Engine.playables(item)
            val index = (args.option("--item")?.toInt() ?: 1) - 1
            val playable = playables.getOrNull(index) ?: error("Item ${index + 1} not found (${playables.size} items)")
            val result = Engine.allLinks(api, playable.data)
            result.links.forEachIndexed { i, l ->
                println("${i + 1}. [${Qualities.getStringByInt(l.quality).ifEmpty { "?" }}] ${l.name} (${l.type})\n   ${l.url}")
            }
            result.subtitles.forEach { println("sub: ${it.lang}\t${it.url}") }
        }
        "key" -> when (pos.getOrNull(1)) {
            "set" -> CloudStreamApp.setKey(pos[2], pos[3]).also { println("${pos[2]} = ${pos[3]}") }
            else -> println(CloudStreamApp.getKey<Any>(pos[2]) ?: "(unset)")
        }
        "compat" -> {
            val issues = CompatChecker.check(File(pos[1]))
            if (issues.isEmpty()) println("OK: every class, method and field the extension uses exists in this runtime")
            else {
                println("${issues.size} unresolved reference(s):")
                issues.groupBy { it.kind + " " + it.owner + "." + it.member }.forEach { (_, list) ->
                    println("  ${list.first()}" + if (list.size > 1) "  +${list.size - 1} more" else "")
                }
            }
        }
        "doctor" -> {
            println("java      ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            println("data      ${AppDirs.data}")
            println("log       ${Logs.file}")
            println("libVLC    ${com.lagradost.cloudstream3.linux.ui.Vlc.version ?: "not found — sudo apt install libvlc5 vlc-plugin-base"}")
            println("dns       ${Settings.dns.label}")
            println("repos     ${RepositoryManager.getRepositories().size}")
            println("plugins   ${PluginManager.loaded.size} loaded, ${PluginManager.failures.size} failed")
            PluginManager.failures.forEach { (n, e) -> println("  FAILED $n: $e") }
            println("providers ${Engine.providers().joinToString { it.name }}")
            if (AndroidStubLoader.generated.isNotEmpty()) println("stubbed   ${AndroidStubLoader.generated.joinToString()}")
        }
        "help", "--help", "-h" -> print(USAGE)
        else -> {
            print(USAGE)
            return 2
        }
    }
    return 0
}

fun statusName(status: Int) = when (status) {
    0 -> "down"
    1 -> "ok"
    2 -> "slow"
    3 -> "beta"
    else -> "?"
}
