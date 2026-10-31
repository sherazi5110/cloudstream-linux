package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.TvType
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.KidsFilter
import io.github.sherazi5110.cloudstream.Profile
import io.github.sherazi5110.cloudstream.Profiles

/**
 * Profile pictures: the Linux app's twelve characters (as emoji here) on one of eight colours,
 * stored the same way ("character:color").
 */
object Avatars {
    val characters = linkedMapOf(
        "smile" to "😊", "cat" to "🐱", "bear" to "🐻", "panda" to "🐼", "robot" to "🤖", "alien" to "👽",
        "ghost" to "👻", "fox" to "🦊", "frog" to "🐸", "owl" to "🦉", "monster" to "👾", "astro" to "🧑‍🚀",
    )
    val colors = linkedMapOf(
        "blue" to "#3b82f6", "red" to "#ef4444", "green" to "#10b981", "purple" to "#8b5cf6",
        "orange" to "#f97316", "pink" to "#ec4899", "teal" to "#14b8a6", "yellow" to "#eab308",
    )
}

@Composable
fun Avatar(avatar: String, size: Dp, modifier: Modifier = Modifier) {
    val (character, color) = avatar.split(':').let { (it.getOrNull(0) ?: "smile") to (it.getOrNull(1) ?: "blue") }
    val base = colorOf(Avatars.colors[color] ?: Avatars.colors.getValue("blue"))
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.linearGradient(listOf(base.copy(alpha = 0.85f), base))),
        contentAlignment = Alignment.Center,
    ) {
        Text(Avatars.characters[character] ?: "😊", fontSize = (size.value * 0.55f).sp, textAlign = TextAlign.Center)
    }
}

/** Profile flows that can open from anywhere (top bar, rail, Settings). */
object ProfileUi {
    var current by mutableStateOf(Profiles.current)
        private set
    /** "Who's watching?" (also opened from the profile menu). */
    var picker by mutableStateOf(Profiles.askOnStart && Profiles.all().size > 1)
    var editing by mutableStateOf<Profile?>(null)
    var creating by mutableStateOf(false)
    var pin by mutableStateOf<PinRequest?>(null)

    class PinRequest(val title: String, val text: String, val check: (String) -> Boolean, val onOk: (String) -> Unit)

    fun refresh() { current = Profiles.current }

    /** Switches profile, asking for a PIN when the target (or leaving a kids profile) needs one. */
    fun switchTo(p: Profile) {
        val unlock = Profiles.unlockFor(p)
        if (unlock == null) doSwitch(p)
        else pin = PinRequest("Enter PIN", if (Profiles.isKids && !p.kids) "A parent PIN is needed to leave a kids profile." else "${p.name} is locked.", unlock) { doSwitch(p) }
    }

    private fun doSwitch(p: Profile) {
        Profiles.switchTo(p.id)
        picker = false
        Nav.stack.clear()
        Nav.tab = Tab.HOME
    }

    /** Things only grown-ups may change (Settings, extensions): asks for the parent PIN in a kids profile. */
    fun guarded(action: () -> Unit) {
        if (!Profiles.isKids) action()
        else pin = PinRequest("Parent PIN", "Ask a parent to enter the PIN.", Profiles::checkParentPin) { action() }
    }
}

/** The avatar button (top bar, rail): opens a menu to switch or manage profiles. */
@Composable
fun ProfileButton(size: Dp = 36.dp, showName: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val me = ProfileUi.current
    Box {
        Row(Modifier.card(RoundedCornerShape(50)) { open = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(me.avatar, size)
            if (showName) Text(me.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 6.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Profiles.all().forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name + if (p.kids) "  · kids" else "") },
                    leadingIcon = { Avatar(p.avatar, 28.dp) },
                    trailingIcon = { if (p.id == me.id) Icon(Icons.Filled.Check, null, tint = Palette.accent) else if (p.pin != null) Icon(Icons.Filled.Lock, null) },
                    onClick = { open = false; if (p.id != me.id) ProfileUi.switchTo(p) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Manage profiles") }, leadingIcon = { Icon(Icons.Filled.People, null) },
                onClick = { open = false; ProfileUi.guarded { ProfileUi.picker = true } })
            if (me.kids) DropdownMenuItem(text = { Text("Exit kids profile") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                onClick = {
                    open = false
                    Profiles.all().firstOrNull { !it.kids }?.let { ProfileUi.switchTo(it) }
                })
        }
    }
}

/** Overlays that belong to profiles: "Who's watching?", the editor and PIN prompts. Drawn by AppRoot. */
@Composable
fun ProfileOverlays() {
    if (ProfileUi.picker) WhoIsWatching()
    ProfileUi.editing?.let { ProfileEditor(it, isNew = false) { ProfileUi.editing = null } }
    if (ProfileUi.creating) ProfileEditor(Profile("", "", "smile:${Avatars.colors.keys.random()}"), isNew = true) { ProfileUi.creating = false }
    ProfileUi.pin?.let { req -> PinDialog(req) { ProfileUi.pin = null } }
}

@Composable
private fun WhoIsWatching() {
    var manage by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { ProfileUi.picker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Palette.background).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Logo(56.dp)
            Text(if (manage) "Manage profiles" else "Who's watching?", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(vertical = 24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Profiles.all().forEach { p ->
                    Column(Modifier.width(120.dp).card(RoundedCornerShape(16.dp)) {
                        if (manage) ProfileUi.editing = p else ProfileUi.switchTo(p)
                    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Avatar(p.avatar, 96.dp)
                            if (manage) Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp))
                            else if (p.pin != null) Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp))
                        }
                        Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                        if (p.kids) Text("Kids", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                    }
                }
                Column(Modifier.width(120.dp).card(RoundedCornerShape(16.dp)) { ProfileUi.creating = true }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).border(2.dp, Palette.outline, RoundedCornerShape(23.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = Palette.muted, modifier = Modifier.size(40.dp))
                    }
                    Text("Add profile", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (manage) "Done" else "Manage profiles", if (manage) Icons.Filled.Check else Icons.Filled.Edit, primary = manage) { manage = !manage }
            }
        }
    }
}

@Composable
private fun PinDialog(req: ProfileUi.PinRequest, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    SimpleDialog(req.title, onClose, confirm = "OK", onConfirm = {
        if (req.check(text)) { onClose(); req.onOk(text) } else { wrong = true; text = "" }
    }) {
        Text(if (wrong) "Wrong PIN, try again." else req.text, style = MaterialTheme.typography.bodyMedium, color = if (wrong) Color(0xFFF87171) else Palette.muted)
        OutlinedTextField(text, { v -> text = v.filter(Char::isDigit).take(8) }, singleLine = true, modifier = Modifier.padding(top = 12.dp),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            placeholder = { Text("PIN") })
    }
}

/** A themed dialog with a title, content and Cancel / confirm buttons (works with a remote). */
@Composable
fun SimpleDialog(
    title: String,
    onClose: () -> Unit,
    confirm: String? = null,
    onConfirm: () -> Unit = {},
    extra: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().background(Palette.surface.copy(alpha = 1f).compositeOverBackground(), RoundedCornerShape(16.dp)).padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                extra?.invoke()
                Spacer(Modifier.weight(1f))
                PillButton("Cancel", null, primary = false, compact = true) { onClose() }
                if (confirm != null) PillButton(confirm, Icons.Filled.Check, compact = true) { onConfirm() }
            }
        }
    }
}

/** Glass surfaces are translucent; dialogs need an opaque card. */
@Composable
fun Color.compositeOverBackground(): Color = if (alpha >= 1f) this else androidx.compose.ui.graphics.lerp(Palette.background, this.copy(alpha = 1f), alpha * 2.5f)

@Composable
fun SwitchRow(title: String, text: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f) { onChange(!checked) }.padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent))
    }
}

/** Chips to pick one or several values. */
@Composable
fun <T> ChoiceChips(options: List<T>, label: (T) -> String, selected: (T) -> Boolean, onClick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = selected(o)
            Text(label(o), style = MaterialTheme.typography.labelMedium, modifier = Modifier
                .card(RoundedCornerShape(50)) { onClick(o) }
                .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun ProfileEditor(initial: Profile, isNew: Boolean, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var avatar by remember { mutableStateOf(initial.avatar) }
    var kids by remember { mutableStateOf(initial.kids) }
    var pin by remember { mutableStateOf(initial.pin.orEmpty()) }
    var filter by remember { mutableStateOf(initial.filter) }
    var newParentPin by remember { mutableStateOf("") }
    val (character, color) = avatar.split(':').let { (it.getOrNull(0) ?: "smile") to (it.getOrNull(1) ?: "blue") }
    val lastGrownUp = !initial.kids && Profiles.all().count { !it.kids } <= 1 && !isNew
    val needsParentPin = kids && !Profiles.hasParentPin

    fun save() {
        if (needsParentPin) {
            if (newParentPin.length < 4) { io.github.sherazi5110.cloudstream.App.toast("Choose a parent PIN of at least 4 digits"); return }
            Profiles.setParentPin(newParentPin)
        }
        val p = initial.copy(name = name.trim().ifBlank { "Profile" }, avatar = avatar, kids = kids && !lastGrownUp,
            pin = pin.takeIf { it.length >= 4 }, filter = filter)
        if (isNew) Profiles.add(p.name, p.avatar, p.kids).let { Profiles.update(it.copy(pin = p.pin, filter = p.filter)) } else Profiles.update(p)
        onClose()
    }

    SimpleDialog(if (isNew) "Add profile" else "Edit profile", onClose, confirm = "Save", onConfirm = { save() }, extra = {
        if (!isNew && Profiles.all().size > 1) PillButton("Delete", null, primary = false, compact = true) {
            Profiles.delete(initial.id); onClose()
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(avatar, 72.dp)
            OutlinedTextField(name, { name = it.take(24) }, singleLine = true, placeholder = { Text("Name") }, modifier = Modifier.padding(start = 16.dp).weight(1f))
        }
        Text("Picture", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatars.characters.keys.forEach { c ->
                Avatar("$c:$color", 44.dp, Modifier.card(RoundedCornerShape(11.dp)) { avatar = "$c:$color" }
                    .then(if (c == character) Modifier.border(2.dp, Color.White, RoundedCornerShape(11.dp)) else Modifier))
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatars.colors.forEach { (k, hex) ->
                Box(Modifier.size(30.dp).card(CircleShape) { avatar = "$character:$k" }.background(colorOf(hex), CircleShape)
                    .then(if (k == color) Modifier.border(2.dp, Color.White, CircleShape) else Modifier))
            }
        }
        OutlinedTextField(pin, { v -> pin = v.filter(Char::isDigit).take(8) }, singleLine = true, modifier = Modifier.padding(top = 16.dp),
            label = { Text("PIN (optional, 4+ digits)") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(8.dp))
        if (!lastGrownUp) SwitchRow("Kids profile", "Only the extensions you allow, chosen types and ages; no Settings or Extensions pages.", kids) { kids = it }
        if (kids) {
            if (needsParentPin) OutlinedTextField(newParentPin, { v -> newParentPin = v.filter(Char::isDigit).take(8) }, singleLine = true,
                label = { Text("New parent PIN (needed to leave kids profiles)") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.padding(vertical = 8.dp))
            Text("Allowed extensions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            val names = Engine.allProviders().filter { TvType.NSFW !in it.supportedTypes }.map { it.name }.distinct()
            if (names.isEmpty()) Text("No extensions installed yet.", style = MaterialTheme.typography.bodySmall)
            ChoiceChips(names, { it }, { it in filter.extensions }) { n ->
                filter = filter.copy(extensions = if (n in filter.extensions) filter.extensions - n else filter.extensions + n)
            }
            Text("Types", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            val types = listOf(TvType.Cartoon, TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries, TvType.Documentary).map { it.name }
            ChoiceChips(types, { it }, { it in filter.types }) { t ->
                filter = filter.copy(types = if (t in filter.types) filter.types - t else filter.types + t)
            }
            Text("Maximum age rating", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            ChoiceChips(listOf(0, 7, 12, 16), { if (it == 0) "All ages" else "$it+" }, { it == filter.maxAge }) { filter = filter.copy(maxAge = it) }
            Text("Blocked words", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            var words by remember { mutableStateOf(filter.blockedWords.joinToString(", ")) }
            OutlinedTextField(words, { words = it; filter = filter.copy(blockedWords = it.split(',').map(String::trim).filter(String::isNotBlank)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (lastGrownUp) Text("This is the last grown-up profile, so it can't become a kids profile.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp))
    }
}
