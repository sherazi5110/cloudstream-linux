package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.DnsProvider
import io.github.sherazi5110.cloudstream.LiveTv
import io.github.sherazi5110.cloudstream.Metadata
import io.github.sherazi5110.cloudstream.Network
import io.github.sherazi5110.cloudstream.PluginManager
import io.github.sherazi5110.cloudstream.Profiles
import io.github.sherazi5110.cloudstream.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Section(val title: String, val text: String, val icon: ImageVector) {
    EXTENSIONS("Extensions", "Repositories, Stremio add-ons", Icons.Filled.Extension),
    PROFILES("Profiles", "Who's watching, kids, adult content", Icons.Filled.People),
    APPEARANCE("Appearance", "Theme, accent colour, layout", Icons.Filled.Palette),
    PLAYER("Player", "Sources, quality, subtitles, audio", Icons.Filled.PlayCircle),
    LIVETV("Live TV", "M3U playlists, Xtream Codes, guides", Icons.Filled.LiveTv),
    METADATA("Metadata & integrations", "TMDB, IMDb ratings, AniList", Icons.Filled.Star),
    NETWORK("Network", "DNS over HTTPS, cookies", Icons.Filled.Public),
    ABOUT("About", "Version, source code", Icons.Filled.Info),
}

/** Settings (the "More" tab on phones): sections in a list; on wide screens the section shows beside it. */
@Composable
fun SettingsScreen() {
    var selected by remember { mutableStateOf(Section.PROFILES) }
    val wide = isWide
    Row(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.then(if (wide) Modifier.width(300.dp) else Modifier.fillMaxWidth()).fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (!wide) ProfileButton(32.dp)
            }
            Section.entries.forEach { s ->
                val on = wide && s == selected
                Row(
                    Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f) {
                        when {
                            s == Section.EXTENSIONS -> Nav.select(Tab.EXTENSIONS)
                            wide -> selected = s
                            else -> Nav.open(Screen.SettingsPage(s.name))
                        }
                    }.background(if (on) Palette.accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, tint = if (on) Palette.accent else Palette.muted)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        if (!wide) Text(s.text, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!wide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.muted)
                }
            }
        }
        if (wide) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp)) {
            Text(selected.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            SectionContent(selected)
        }
    }
}

/** Portrait: one section on its own page. */
@Composable
fun SettingsPageScreen(screen: Screen.SettingsPage) {
    val section = Section.valueOf(screen.section)
    Column(Modifier.fillMaxSize()) {
        BackBar(section.title)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) { SectionContent(section) }
    }
}

@Composable
private fun SectionContent(s: Section) {
    when (s) {
        Section.EXTENSIONS -> Unit
        Section.PROFILES -> ProfilesSection()
        Section.APPEARANCE -> AppearanceSection()
        Section.PLAYER -> PlayerSection()
        Section.LIVETV -> LiveTvSection()
        Section.METADATA -> MetadataSection()
        Section.NETWORK -> NetworkSection()
        Section.ABOUT -> AboutSection()
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.accent,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))

// ---------------------------------------------------------------- sections

@Composable
private fun ProfilesSection() {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = Profiles.onChange { version++ }; onDispose { off() } }
    val all = remember(version) { Profiles.all() }
    all.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).card(RoundedCornerShape(10.dp), focusScale = 1.02f) { ProfileUi.editing = p }
            .background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.avatar, 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.name + if (p.id == ProfileUi.current.id) "  (you)" else "", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull("Kids".takeIf { p.kids }, "PIN".takeIf { p.pin != null }).joinToString(" · ").ifBlank { "Grown-up" }, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.Edit, "Edit", tint = Palette.muted)
        }
    }
    PillButton("Add profile", Icons.Filled.Add, Modifier.padding(top = 8.dp), compact = true) { ProfileUi.creating = true }
    var ask by remember { mutableStateOf(Profiles.askOnStart) }
    Heading("Start")
    SwitchRow("Ask \"Who's watching?\" at start", "Only with more than one profile.", ask) { ask = it; Profiles.askOnStart = it }
    if (!ProfileUi.current.kids) {
        Heading("Adult content")
        var nsfw by remember(version) { mutableStateOf(Settings.showNsfw) }
        var confirm by remember { mutableStateOf(false) }
        SwitchRow("Show adult (NSFW) content", "For ${ProfileUi.current.name} only. Like the official app, adult extensions and titles are hidden until this is on.", nsfw) { on ->
            if (!on) { Settings.showNsfw = false; nsfw = false; PluginManager.bumpVersion() } else confirm = true
        }
        if (confirm) SimpleDialog("Show adult content?", { confirm = false }, confirm = "Turn on", onConfirm = {
            confirm = false
            val enable = { Settings.showNsfw = true; nsfw = true; PluginManager.bumpVersion() }
            if (Profiles.hasParentPin) ProfileUi.pin = ProfileUi.PinRequest("Parent PIN", "Enter the parent PIN to allow adult content.", Profiles::checkParentPin) { enable() }
            else enable()
        }) { Text("Adult extensions and titles will show on Home, in Search and in Extensions for this profile.", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun AppearanceSection() {
    Heading("Presets")
    ChoiceChips(ThemePreset.entries, { it.label }, { ThemeState.preset == it }) { ThemeState.apply(it) }
    Heading("Theme")
    ChoiceChips(ThemeMode.entries, { it.label }, { ThemeState.mode == it }) { ThemeState.changeMode(it) }
    Heading("Accent colour")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Accent.entries.forEach { a ->
            val on = ThemeState.accentHex.equals(a.hex, true)
            Box(Modifier.size(40.dp).card(CircleShape) { ThemeState.changeAccent(a.hex) }.background(colorOf(a.hex), CircleShape)
                .then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier))
        }
    }
    Hint("Each profile has its own look.")
    Heading("Layout")
    var layout by remember { mutableStateOf(Settings.layout) }
    val auto = if (App.detectTv()) "TV" else "Phone"
    ChoiceChips(listOf("auto", "phone", "tv"), { when (it) { "tv" -> "TV (remote)"; "phone" -> "Phone (touch)"; else -> "Automatic ($auto)" } }, { it == layout }) {
        layout = it; Settings.layout = it
    }
    Hint("Phone/TV changes how the app reacts to touch and the remote; it applies when the app restarts. Landscape screens always get the side rail.")
    Heading("Home")
    var slider by remember { mutableStateOf(Settings.heroSlider) }
    SwitchRow("Banner slides through the top titles", null, slider) { slider = it; Settings.heroSlider = it }
    var spoiler by remember { mutableStateOf(Settings.antiSpoiler) }
    SwitchRow("Anti-spoiler", "Hide pictures and descriptions of episodes you haven't watched.", spoiler) { spoiler = it; Settings.antiSpoiler = it }
}

@Composable
private fun PlayerSection() {
    var autoPlay by remember { mutableStateOf(Settings.autoPlay) }
    SwitchRow("Start the best source automatically", "Otherwise you pick from the list.", autoPlay) { autoPlay = it; Settings.autoPlay = it }
    var next by remember { mutableStateOf(Settings.autoPlayNext) }
    SwitchRow("Play the next episode automatically", null, next) { next = it; Settings.autoPlayNext = it }
    Heading("Preferred quality")
    var quality by remember { mutableStateOf(Settings.preferredQuality) }
    ChoiceChips(listOf(0, 2160, 1080, 720, 480), { if (it == 0) "Best" else "${it}p" }, { it == quality }) { quality = it; Settings.preferredQuality = it }
    Heading("Wait for sources")
    var timeout by remember { mutableStateOf(Settings.sourcesTimeoutSeconds) }
    ChoiceChips(listOf(30, 60, 90, 120), { "$it s" }, { it == timeout }) { timeout = it; Settings.sourcesTimeoutSeconds = it }
    Heading("Languages")
    var subs by remember { mutableStateOf(Settings.subtitleLanguage) }
    OutlinedTextField(subs, { subs = it; Settings.subtitleLanguage = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Subtitles to turn on, e.g. en or ar,en") })
    var audio by remember { mutableStateOf(Settings.audioLanguage) }
    OutlinedTextField(audio, { audio = it; Settings.audioLanguage = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Audio to pick, e.g. ja or hi,en") })
    Hint("Leave empty for the stream's default. Online subtitles (OpenSubtitles, no account) are in the player's Subtitles menu.")
}

@Composable
private fun LiveTvSection() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = LiveTv.onChange { version++ }; onDispose { off() } }
    val sources = remember(version) { LiveTv.sources() }
    if (sources.isEmpty()) Hint("No sources yet. Add an M3U playlist (with an optional XMLTV guide), an Xtream Codes login, or a Stremio add-on with TV channels.")
    sources.forEach { src ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(src.name, style = MaterialTheme.typography.titleMedium)
                Text("${src.kind.label} · ${src.url}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            androidx.compose.material3.Switch(src.enabled, onCheckedChange = { LiveTv.setEnabled(src.id, it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.accent))
            IconAction(Icons.Filled.Delete, "Remove") { LiveTv.remove(src.id) }
        }
    }
    Heading("Add a source")
    var kind by remember { mutableStateOf(LiveTv.Kind.M3U) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ChoiceChips(LiveTv.Kind.entries, { it.label }, { it == kind }) { kind = it }
    OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = {
        Text(when (kind) { LiveTv.Kind.M3U -> "Playlist address (.m3u)"; LiveTv.Kind.XTREAM -> "Server address"; LiveTv.Kind.STREMIO -> "Add-on manifest address" })
    })
    if (kind != LiveTv.Kind.STREMIO) OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Name (optional)") })
    if (kind == LiveTv.Kind.M3U) OutlinedTextField(epg, { epg = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("XMLTV guide address (optional)") })
    if (kind == LiveTv.Kind.XTREAM) {
        OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Username") })
        OutlinedTextField(pass, { pass = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation())
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Add", Icons.Filled.Add, compact = true) {
            if (url.isBlank() || busy) return@PillButton
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { LiveTv.add(kind, url, name, epg, user, pass) } }
                busy = false
                r.onSuccess { (src, n) -> App.toast("${src.name}: $n channels"); url = ""; name = ""; epg = ""; user = ""; pass = ""; version++ }
                    .onFailure { App.toast(it.message ?: "Couldn't add that source") }
            }
        }
        if (busy) Spinner(Modifier.padding(start = 12.dp).size(28.dp))
    }
    Heading("Options")
    var daily by remember { mutableStateOf(LiveTv.refreshDaily) }
    SwitchRow("Refresh channel lists daily", "Otherwise they're kept until you refresh them on the Live TV page.", daily) { daily = it; LiveTv.refreshDaily = it }
    var kids by remember { mutableStateOf(LiveTv.allowKids) }
    SwitchRow("Live TV in kids profiles", "Only channels in children's groups.", kids) { kids = it; LiveTv.allowKids = it }
}

@Composable
private fun MetadataSection() {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Metadata.tmdbKey) }
    var status by remember { mutableStateOf<String?>(null) }
    Hint("Add your free TMDB key (themoviedb.org → Settings → API) for ratings on posters, wide backdrops and title logos, cast with photos, trailers and similar titles. IMDb ratings (Cinemeta) and AniList scores need no key.")
    OutlinedTextField(key, { key = it.trim(); Metadata.tmdbKey = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("TMDB API key (v3) or read access token (v4)") })
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Test key", null, primary = false, compact = true) {
            status = "Testing…"
            scope.launch { status = Metadata.testKey(key) ?: "The key works." }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp)) }
    }
    Heading("Use")
    var tmdb by remember { mutableStateOf(Metadata.tmdbEnabled) }
    SwitchRow("TMDB", null, tmdb) { tmdb = it; Metadata.tmdbEnabled = it }
    var posters by remember { mutableStateOf(Metadata.posterRatings) }
    SwitchRow("Ratings on posters", null, posters) { posters = it; Metadata.posterRatings = it }
    var art by remember { mutableStateOf(Metadata.artwork) }
    SwitchRow("Backdrops and title logos", null, art) { art = it; Metadata.artwork = it }
    var extras by remember { mutableStateOf(Metadata.extras) }
    SwitchRow("Cast, trailers and similar titles", null, extras) { extras = it; Metadata.extras = it }
    var cinemeta by remember { mutableStateOf(Metadata.cinemeta) }
    SwitchRow("IMDb ratings (Cinemeta)", null, cinemeta) { cinemeta = it; Metadata.cinemeta = it }
    var anilist by remember { mutableStateOf(Metadata.anilist) }
    SwitchRow("AniList scores for anime", null, anilist) { anilist = it; Metadata.anilist = it }
    var lang by remember { mutableStateOf(Metadata.language) }
    OutlinedTextField(lang, { lang = it.trim(); Metadata.language = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Language, e.g. en-US") })
    Heading("Cache")
    var stats by remember { mutableStateOf(Metadata.cacheStats()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${stats.first} entries · ${stats.second / 1024} KB", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillButton("Clear", Icons.Filled.Delete, primary = false, compact = true) { Metadata.clearCache(); stats = Metadata.cacheStats() }
    }
}

@Composable
private fun NetworkSection() {
    Heading("DNS over HTTPS")
    var dns by remember { mutableStateOf(Settings.dns) }
    ChoiceChips(DnsProvider.entries, { it.label }, { it == dns }) { dns = it; Settings.dns = it }
    var fallback by remember { mutableStateOf(Settings.dnsFallback) }
    SwitchRow("Fall back to system DNS", "When the resolver is slow or blocked.", fallback) { fallback = it; Settings.dnsFallback = it }
    Hint("Applies when the app restarts. Helps when your network blocks some sites.")
    Heading("Cookies")
    var cookies by remember { mutableStateOf(Network.cookies.size) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$cookies cookies stored", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillButton("Clear", Icons.Filled.Delete, primary = false, compact = true) { Network.cookies.clear(); cookies = 0 }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Logo(48.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text("CloudStream", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${versionName ?: "?"}", style = MaterialTheme.typography.bodySmall)
        }
    }
    PillButton("Source code on GitHub", null, primary = false, compact = true) {
        com.lagradost.cloudstream3.CloudStreamApp.openBrowser("https://github.com/sherazi5110/cloudstream-linux", context.findActivity())
    }
    Hint("GPL-3.0. Extensions and their content come from third parties; this app hosts nothing.")
}
