package io.github.sherazi5110.cloudstream.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.PluginManager
import io.github.sherazi5110.cloudstream.PluginWrapper
import io.github.sherazi5110.cloudstream.RepositoryData
import io.github.sherazi5110.cloudstream.RepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A repository link opened from outside (cloudstreamrepo://…), waiting for the user to confirm. */
object PendingRepo {
    var url by mutableStateOf<String?>(null)
}

@Composable
fun ExtensionsScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version by PluginManager.version.collectAsState()
    var repos by remember { mutableStateOf(RepositoryManager.getRepositories()) }
    val installed = remember(version) { PluginManager.installed().sortedBy { (it.name ?: it.internalName).lowercase() } }
    var adding by remember { mutableStateOf(PendingRepo.url != null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.let { if (it.endsWith(".cs3")) it else "$it.cs3" } ?: "local.cs3"
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { PluginManager.sideload(name, it).getOrThrow() } }
            }
            App.toast(result.fold({ "Installed ${it.manifest.name ?: name}" }, { "Couldn't install: ${it.message}" }))
        }
    }

    var stremio by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Extensions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (busy) Spinner(Modifier.size(32.dp))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("CloudStream extensions", "Stremio add-ons").forEachIndexed { i, label ->
                val on = (i == 1) == stremio
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.card(RoundedCornerShape(50)) { stremio = i == 1 }
                    .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (stremio) { StremioTab(); return@Column }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                // Wraps onto a second line on narrow phones.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Add repository", Icons.Filled.Add) { adding = true }
                    PillButton("Update all", Icons.Filled.Refresh, primary = false) {
                        busy = true
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) { PluginManager.updateAll() }
                            busy = false
                            App.toast(if (updated.isEmpty()) "Everything is up to date" else "Updated ${updated.joinToString()}")
                        }
                    }
                    if (!isTv) PillButton("From file", Icons.Filled.FileOpen, primary = false) { picker.launch(arrayOf("*/*")) }
                }
            }
            item { SectionTitle("Repositories", Modifier.padding(top = 12.dp)) }
            if (repos.isEmpty()) item {
                Text("Add a repository by its address, a cloudstreamrepo:// link or a shortcode, the same ones the official app accepts.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(repos, key = { "repo|${it.url}" }) { repo ->
                ListRow(repo.name, repo.description ?: repo.url, repo.iconUrl, onClick = { Nav.open(Screen.Repository(repo.url, repo.name)) }) {
                    IconAction(Icons.Filled.Delete, "Remove") {
                        RepositoryManager.removeRepository(repo.url)
                        repos = RepositoryManager.getRepositories()
                        PluginManager.bumpVersion()
                    }
                }
            }
            item { SectionTitle("Installed · ${installed.size}", Modifier.padding(top = 12.dp)) }
            items(installed, key = { "plugin|${it.internalName}" }) { data ->
                val failure = PluginManager.failures[data.internalName]
                val loaded = PluginManager.loaded.values.firstOrNull { it.data.internalName == data.internalName }
                val detail = when {
                    failure != null -> "Failed to load: ${failure.message ?: failure}"
                    loaded != null -> listOf("v${loaded.data.version}", loaded.providers.joinToString { it.name }.ifBlank { null }).filterNotNull().joinToString(" · ")
                    else -> "Loading…"
                }
                ListRow(data.name ?: data.internalName, detail, null, warning = failure != null) {
                    IconAction(Icons.Filled.Delete, "Uninstall") { PluginManager.uninstall(data.internalName); PluginManager.bumpVersion() }
                }
            }
        }
    }

    if (adding) AddRepoDialog(PendingRepo.url.orEmpty(), onDismiss = { adding = false; PendingRepo.url = null }) { input ->
        adding = false
        PendingRepo.url = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RepositoryManager.addRepository(input) } }
            busy = false
            result.onSuccess {
                repos = RepositoryManager.getRepositories()
                Nav.open(Screen.Repository(it.url, it.name))
            }.onFailure { App.toast(it.message ?: "Not a repository") }
        }
    }
}

@Composable
private fun AddRepoDialog(initial: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add repository") },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("https://…/repo.json or shortcode") })
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onAdd(text.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Palette.surface,
    )
}

@Composable
fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Icon(icon, label, tint = tint, modifier = Modifier.size(40.dp).card(RoundedCornerShape(50), onClick = onClick).padding(9.dp))
}

@Composable
private fun ListRow(
    title: String,
    detail: String?,
    iconUrl: String?,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.card(RoundedCornerShape(12.dp), focusScale = 1.02f, onClick = onClick) else Modifier)
            .background(Palette.surface, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconUrl != null) NetImage(iconUrl, Modifier.size(40.dp).background(Palette.surfaceHigh, RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        else if (warning) Icon(Icons.Filled.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

private val statusText = mapOf(0 to "Down", 2 to "Slow", 3 to "Beta")

/** One repository's extensions, with install / update / uninstall. */
@Composable
fun RepositoryScreen(screen: Screen.Repository) {
    val scope = rememberCoroutineScope()
    val version by PluginManager.version.collectAsState()
    var plugins by remember(screen) { mutableStateOf<List<PluginWrapper>?>(null) }
    var failed by remember(screen) { mutableStateOf(false) }
    val working = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(screen) {
        val data = RepositoryManager.getRepositories().firstOrNull { it.url == screen.url } ?: RepositoryData(name = screen.name, url = screen.url)
        val list = withContext(Dispatchers.IO) { RepositoryManager.getRepoPlugins(data) }
        if (list == null) failed = true else plugins = list.sortedBy { it.plugin.name.lowercase() }
    }
    val installed = remember(version) { PluginManager.installed().associateBy { it.internalName } }

    Column(Modifier.fillMaxSize()) {
        BackBar(screen.name)
        when {
            failed -> EmptyState("Couldn't read this repository", screen.url, Modifier.fillMaxWidth())
            plugins == null -> Spinner(Modifier.fillMaxWidth().height(200.dp))
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    PillButton("Install all", Icons.Filled.Download, Modifier.padding(bottom = 8.dp)) {
                        scope.launch {
                            plugins.orEmpty().filter { it.plugin.internalName !in installed && it.plugin.status != 0 }.forEach { w ->
                                working[w.plugin.internalName] = true
                                withContext(Dispatchers.IO) { PluginManager.install(w) }
                                working.remove(w.plugin.internalName)
                            }
                        }
                    }
                }
                items(plugins.orEmpty(), key = { it.plugin.internalName }) { w ->
                    val p = w.plugin
                    val have = installed[p.internalName]
                    val detail = listOfNotNull(
                        statusText[p.status],
                        p.language?.uppercase(),
                        p.tvTypes?.take(3)?.joinToString(", "),
                        "v${p.version}",
                        p.description,
                    ).joinToString(" · ")
                    ListRow(p.name, detail, p.iconUrl?.replace("%size%", "64")) {
                        when {
                            working[p.internalName] == true -> Spinner(Modifier.size(40.dp))
                            have == null -> IconAction(Icons.Filled.Download, "Install") {
                                working[p.internalName] = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { PluginManager.install(w) }
                                    working.remove(p.internalName)
                                    r.onFailure { App.toast("${p.name}: ${it.message}") }
                                }
                            }
                            p.version > have.version -> IconAction(Icons.Filled.Refresh, "Update", Palette.accent) {
                                working[p.internalName] = true
                                scope.launch { withContext(Dispatchers.IO) { PluginManager.install(w) }; working.remove(p.internalName) }
                            }
                            else -> IconAction(Icons.Filled.Delete, "Uninstall") { PluginManager.uninstall(p.internalName); PluginManager.bumpVersion() }
                        }
                    }
                }
            }
        }
    }
}


/** Stremio add-ons: install by manifest address (or stremio:// link); their catalogs join Home and Search. */
@Composable
private fun StremioTab() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val addons = remember(version) { io.github.sherazi5110.cloudstream.StremioAddons.all() }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Add-ons are installed by the address of their manifest.json (or a stremio:// link). Streams from every add-on that has them " +
                "play in the built-in player; torrent-only add-ons aren't supported.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("https://…/manifest.json") })
                PillButton("Install", Icons.Filled.Download, Modifier.padding(start = 10.dp), compact = true) {
                    if (url.isBlank() || busy) return@PillButton
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { io.github.sherazi5110.cloudstream.StremioAddons.install(url) } }
                        busy = false
                        r.onSuccess { App.toast("Installed ${it.manifest.name}"); url = ""; version++ }.onFailure { App.toast(it.message ?: "Not a Stremio add-on") }
                    }
                }
            }
            if (busy) Spinner(Modifier.fillMaxWidth().height(48.dp))
        }
        items(addons, key = { it.url }) { a ->
            ListRow(a.manifest.name, listOf("v${a.manifest.version}", a.manifest.types.joinToString(", "), a.manifest.description).filter { it.isNotBlank() }.joinToString(" · "),
                a.manifest.logo) {
                androidx.compose.material3.Switch(a.enabled, onCheckedChange = { io.github.sherazi5110.cloudstream.StremioAddons.setEnabled(a.url, it); version++ },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.accent))
                IconAction(Icons.Filled.Delete, "Remove") { io.github.sherazi5110.cloudstream.StremioAddons.remove(a.url); version++ }
            }
        }
    }
}
