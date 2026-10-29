package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.linux.ContentFilter
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.KidsFilter
import com.lagradost.cloudstream3.linux.Profile
import com.lagradost.cloudstream3.linux.Profiles
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.PasswordField
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser

/** Switching profile, asking for the target's PIN first when it has one. */
object ProfileSwitch {
    /** At startup even the last-used profile asks for its PIN; Cancel goes back to the picker. */
    fun to(target: Profile, startup: Boolean = false) {
        if (target.id == Profiles.current.id && !startup) return
        val pin = target.pin
        if (pin == null) {
            if (target.id != Profiles.current.id) Profiles.switchTo(target.id)
            return
        }
        Nav.window.showOverlay(PinPrompt("Enter ${target.name}'s PIN",
            onCancel = if (startup) ({ Nav.window.showOverlay(ProfilePicker(startup = true)) }) else null) { entered ->
            (entered == pin).also { if (it && target.id != Profiles.current.id) Profiles.switchTo(target.id) }
        })
    }
}

/** The menu under the avatar: switch profile, manage profiles. */
object ProfileMenu {
    fun show(anchor: Node) {
        val current = Profiles.current
        val rows = Profiles.all().map { p ->
            Popover.row(Avatars.node(p.avatar, 28.0, round = true), p.name,
                listOfNotNull(if (p.kids) "Kids" else null, if (p.pin != null) "PIN" else null).joinToString(" · ").ifBlank { null },
                selected = p.id == current.id) { ProfileSwitch.to(p) }
        }.toMutableList()
        rows += Popover.separator()
        if (!current.kids) {
            rows += Popover.row(icon(Icons.PEOPLE, 18.0), "Manage profiles") { Nav.window.showOverlay(ProfilePicker(startup = false, manage = true)) }
            rows += Popover.row(icon(Icons.SETTINGS, 18.0), "Settings") { Nav.window.show(Tab.SETTINGS) }
        } else {
            rows += Popover.row(icon(Icons.PEOPLE, 18.0), "Who's watching?") { Nav.window.showOverlay(ProfilePicker(startup = false)) }
        }
        Popover.show(anchor, Popover.panel(*rows.toTypedArray()))
    }
}

/** "Who's watching?": the profiles as big avatars, plus add and edit. */
class ProfilePicker(private val startup: Boolean, private var manage: Boolean = false) : StackPane(), Disposable, Blocking {
    override val blocking get() = startup
    private val grid = FlowPane(28.0, 28.0).apply { alignment = Pos.CENTER; maxWidth = 980.0 }
    private val title = label("", "picker-title")
    private val manageButton = button("", Icons.EDIT, "btn-ghost") { manage = !manage; render() }

    init {
        styleClass += "profile-picker"
        val close = button(null, Icons.CLOSE, "btn-ghost") { Nav.window.closeOverlay() }.tooltip("Close (Esc)")
        val center = vbox(36.0, Logo(64.0), title, grid, manageButton).apply { alignment = Pos.CENTER }
        children += center
        if (!startup) {
            children += close
            StackPane.setAlignment(close, Pos.TOP_RIGHT)
            StackPane.setMargin(close, javafx.geometry.Insets(24.0))
        }
        render()
    }

    private fun render() {
        val kids = Profiles.isKids
        title.text = if (manage) "Manage profiles" else "Who's watching?"
        manageButton.text = if (manage) "Done" else "Manage profiles"
        manageButton.isVisible = !kids
        val tiles = Profiles.all().map { p -> tile(p) }.toMutableList()
        if (!kids && Profiles.all().size < 8) tiles += addTile()
        grid.children.setAll(tiles)
    }

    private fun tile(p: Profile): Node {
        val face = StackPane(Avatars.node(p.avatar, 128.0)).apply { styleClass += "picker-avatar" }
        if (manage) face.children += StackPane(icon(Icons.EDIT, 34.0)).apply { styleClass += "picker-edit" }
        else if (p.pin != null) face.children += StackPane(icon(Icons.LOCK, 14.0)).apply {
            styleClass += "picker-lock"; maxWidth = 26.0; maxHeight = 26.0
            StackPane.setAlignment(this, Pos.BOTTOM_RIGHT); StackPane.setMargin(this, javafx.geometry.Insets(6.0))
        }
        val name = label(p.name, "picker-name")
        val tag = if (p.kids) label("KIDS", "picker-kids") else Region()
        return vbox(10.0, face, name, tag).apply {
            alignment = Pos.TOP_CENTER
            styleClass += "picker-tile"
            if (p.id == Profiles.current.id && !startup) styleClass += "current"
            onClick {
                if (manage) {
                    // Editing a protected profile needs its PIN.
                    val pin = p.pin
                    if (pin != null && p.id != Profiles.current.id) Nav.window.showOverlay(PinPrompt("Enter ${p.name}'s PIN") { e -> (e == pin).also { if (it) Nav.window.showOverlay(ProfileEditor(p)) } })
                    else Nav.window.showOverlay(ProfileEditor(p))
                } else {
                    Nav.window.closeOverlay(force = true)
                    ProfileSwitch.to(p, startup)
                }
            }
        }
    }

    private fun addTile(): Node = vbox(10.0,
        StackPane(icon(Icons.ADD, 48.0)).apply { styleClass += "picker-add"; setMinSize(128.0, 128.0); setMaxSize(128.0, 128.0) },
        label("Add profile", "picker-name"),
    ).apply {
        alignment = Pos.TOP_CENTER
        styleClass += "picker-tile"
        onClick { Nav.window.showOverlay(ProfileEditor(null)) }
    }

    override fun dispose() = Unit
}

/** Overlays that Esc and clicking outside must not close (e.g. the startup profile picker). */
interface Blocking {
    val blocking: Boolean
}

/** Four-digit PIN pad. [check] returns whether the PIN was right. */
class PinPrompt(title: String, private val onCancel: (() -> Unit)? = null, private val check: (String) -> Boolean) : StackPane(), Blocking {
    override val blocking get() = onCancel != null
    private val field = PasswordField().apply {
        styleClass += "pin-field"
        promptText = "••••"
        maxWidth = 180.0
        textProperty().addListener { _, old, new ->
            if (new.length > 4 || new.any { !it.isDigit() }) text = old
            else if (new.length == 4) submit()
        }
    }
    private val error = label("", "error-text")

    init {
        val cancel = { onCancel?.invoke() ?: Nav.window.closeOverlay() }
        val scrim = Region().apply { styleClass += "scrim"; onClick { cancel() } }
        val box = vbox(16.0, icon(Icons.LOCK, 30.0).apply { styleClass += "accent-icon" }, label(title, "h3"), field, error,
            button("Cancel", null, "btn-ghost") { cancel() }).apply {
            styleClass += "sheet"
            alignment = Pos.CENTER
            pad(28.0, 40.0, 24.0, 40.0)
            maxWidth = 360.0; maxHeight = Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, box)
        sceneProperty().addListener { _, _, s -> if (s != null) javafx.application.Platform.runLater { field.requestFocus() } }
    }

    private fun submit() {
        val text = field.text
        javafx.application.Platform.runLater {
            if (check(text)) {
                if (Nav.window.isTopOverlay(this)) Nav.window.closeOverlay(force = true)
            } else {
                error.text = "Wrong PIN"
                field.clear()
            }
        }
    }
}

/** Create or edit a profile: name, avatar, PIN, and the kids settings. */
class ProfileEditor(private val existing: Profile?) : StackPane() {
    private var avatar = existing?.avatar ?: "${Avatars.characters.random()}:${Avatars.colors.keys.random()}"
    private val name = TextField(existing?.name ?: "").apply { promptText = "Name"; styleClass += "text-input" }
    private var kids = existing?.kids ?: false
    private var filter = existing?.filter ?: KidsFilter()
    private val preview = StackPane()
    private val characters = FlowPane(10.0, 10.0)
    private val colors = hbox(10.0)
    private val kidsBox = VBox(14.0)
    private val pin = PasswordField().apply {
        promptText = if (existing?.pin != null) "PIN set · type a new one to change" else "No PIN"
        styleClass += "text-input"; maxWidth = 260.0
        textProperty().addListener { _, old, new -> if (new.length > 4 || new.any { !it.isDigit() }) text = old }
    }
    private var removePin = false
    private val words = TextArea(filter.blockedWords.joinToString(", ")).apply { isWrapText = true; prefRowCount = 2; styleClass += "text-input" }
    private val error = label("", "error-text")

    init {
        val scrim = Region().apply { styleClass += "scrim"; onClick { Nav.window.closeOverlay() } }
        renderAvatar()
        val picture = button("Use a picture…", Icons.IMAGE, "btn-ghost", "btn-small") {
            FileChooser().apply {
                title = "Choose a profile picture"
                extensionFilters += FileChooser.ExtensionFilter("Pictures", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.gif", "*.bmp")
            }.showOpenDialog(scene.window)?.let { avatar = "file:${it.absolutePath}"; renderAvatar() }
        }
        val kidsSwitch = Switch(kids) { kids = it; renderKids() }
        val pinRow = hbox(10.0, pin, *listOfNotNull(existing?.pin?.let {
            button("Remove PIN", Icons.DELETE, "btn-ghost", "btn-small") { removePin = true; pin.clear(); pin.promptText = "No PIN" }
        }).toTypedArray())
        val body = vbox(20.0,
            hbox(24.0, preview, vbox(10.0, label("Name", "bold"), name, error, picture)).apply { alignment = Pos.CENTER_LEFT },
            vbox(10.0, label("Character", "bold"), characters),
            vbox(10.0, label("Colour", "bold"), colors),
            hbox(12.0, vbox(2.0, label("Kids profile", "bold"),
                label("Only the extensions and content you allow below. No Settings or Extensions pages.", "faint", "small")), spacer(), kidsSwitch).apply { styleClass += "card-row" },
            kidsBox,
            vbox(8.0, label("PIN", "bold"), label("Asked before switching to this profile. Give grown-up profiles a PIN so kids can't switch to them.", "faint", "small"), pinRow),
        )
        val scroll = pageScroll(body).apply {
            maxHeightProperty().bind(Nav.window.heightProperty().multiply(0.72))
            prefViewportHeightProperty().bind(body.heightProperty())
        }
        val delete = existing?.takeIf { Profiles.all().size > 1 }?.let { p ->
            button("Delete profile", Icons.DELETE, "btn-danger") {
                Nav.window.showOverlay(ConfirmSheet("Delete ${p.name}?", "Their history, list and settings are deleted too.", "Delete") {
                    Profiles.delete(p.id)
                    Nav.window.showOverlay(ProfilePicker(startup = false, manage = true))
                })
            }
        }
        val footer = hbox(10.0, *listOfNotNull(delete, spacer(),
            button("Cancel", null, "btn-ghost") { Nav.window.showOverlay(ProfilePicker(startup = false, manage = true)) },
            button(if (existing == null) "Create profile" else "Save", Icons.CHECK, "btn-accent") { save() }).toTypedArray())
        val sheet = VBox(16.0, label(if (existing == null) "New profile" else "Edit profile", "h2"), scroll, footer).apply {
            styleClass += "sheet"
            pad(26.0, 28.0, 22.0, 28.0)
            maxWidth = 760.0; maxHeight = Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        renderKids()
    }

    private fun renderAvatar() {
        preview.children.setAll(Avatars.node(avatar, 112.0))
        val (character, color) = avatar.split(':').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "blue") }
        val colorName = if (avatar.startsWith("file:")) "blue" else color
        characters.children.setAll(Avatars.characters.map { c ->
            StackPane(Avatars.node("$c:$colorName", 54.0)).apply {
                styleClass += "avatar-choice"
                if (c == character) styleClass += "selected"
                onClick { avatar = "$c:$colorName"; renderAvatar() }
            }
        })
        colors.children.setAll(Avatars.colors.map { (key, hex) ->
            Region().apply {
                styleClass += "color-dot"
                if (key == color && !avatar.startsWith("file:")) styleClass += "selected"
                style = "-fx-background-color: $hex;"
                onClick {
                    val c = if (avatar.startsWith("file:")) Avatars.characters.first() else character
                    avatar = "$c:$key"; renderAvatar()
                }
            }
        })
    }

    private fun renderKids() {
        kidsBox.isVisible = kids
        kidsBox.isManaged = kids
        if (!kids) return
        val apis = Engine.allProviders().filter { TvType.NSFW !in it.supportedTypes }
        val extensionChecks = FlowPane(10.0, 10.0).apply {
            children.setAll(apis.map { api ->
                CheckBox(api.name).apply {
                    isSelected = api.name in filter.extensions
                    selectedProperty().addListener { _, _, on ->
                        filter = filter.copy(extensions = if (on) filter.extensions + api.name else filter.extensions - api.name)
                    }
                }
            })
        }
        val typeNames = listOf(TvType.Cartoon to "Cartoons", TvType.Anime to "Anime", TvType.AnimeMovie to "Anime films", TvType.Movie to "Movies",
            TvType.TvSeries to "Series", TvType.Documentary to "Documentaries", TvType.AsianDrama to "Asian dramas", TvType.Live to "Live TV")
        val types = FlowPane(8.0, 8.0).apply {
            children.setAll(typeNames.map { (t, label) ->
                label(label, "chip").apply {
                    if (t.name in filter.types) styleClass += "selected"
                    onClick {
                        filter = filter.copy(types = if (t.name in filter.types) filter.types - t.name else filter.types + t.name)
                        if (t.name in filter.types) styleClass += "selected" else styleClass.remove("selected")
                    }
                }
            })
        }
        val ages = listOf(0 to "All ages", 7 to "7+", 10 to "10+", 12 to "12+", 13 to "13+", 16 to "16+")
        val ageChips = ChipGroup { i -> filter = filter.copy(maxAge = ages[i].first) }.apply {
            setItems(ages.map { it.second }, ages.indexOfFirst { it.first == filter.maxAge }.coerceAtLeast(0))
        }
        kidsBox.children.setAll(
            vbox(8.0, label("Allowed extensions", "bold"),
                label(if (apis.isEmpty()) "No extensions installed yet." else "The kids profile only sees these. Extensions marked NSFW are never shown.", "faint", "small"),
                extensionChecks),
            vbox(8.0, label("Allowed types", "bold"), types),
            vbox(8.0, label("Highest age rating", "bold"), label("Titles rated above this are hidden when the extension provides a rating.", "faint", "small"), ageChips),
            vbox(8.0, label("Blocked words", "bold"), label("Titles, genres or descriptions containing these are hidden. Separate with commas.", "faint", "small"), words),
        )
    }

    private fun save() {
        val n = name.text.trim()
        if (n.isBlank()) { error.text = "Enter a name"; return }
        if (pin.text.isNotEmpty() && pin.text.length != 4) { error.text = "The PIN needs 4 digits"; return }
        val blocked = words.text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val newPin = when {
            pin.text.length == 4 -> pin.text
            removePin -> null
            else -> existing?.pin
        }
        val f = filter.copy(blockedWords = blocked)
        if (existing == null) {
            val created = Profiles.add(n, avatar, kids)
            Profiles.update(created.copy(pin = newPin, filter = f))
        } else {
            Profiles.update(existing.copy(name = n, avatar = avatar, kids = kids, pin = newPin, filter = f))
        }
        if (kids && f.extensions.isEmpty()) Toasts.show("This kids profile has no extensions allowed yet, so it shows nothing", seconds = 6.0)
        Nav.window.showOverlay(ProfilePicker(startup = false, manage = true))
    }
}

/** A small yes/no sheet. */
class ConfirmSheet(title: String, message: String, confirm: String, action: () -> Unit) : StackPane() {
    init {
        val scrim = Region().apply { styleClass += "scrim"; onClick { Nav.window.closeOverlay() } }
        val box = vbox(14.0, label(title, "h3"), label(message, "muted").apply { isWrapText = true },
            hbox(10.0, spacer(), button("Cancel", null, "btn-ghost") { Nav.window.closeOverlay() },
                button(confirm, null, "btn-danger") { Nav.window.closeOverlay(); action() })).apply {
            styleClass += "sheet"
            pad(24.0, 28.0, 20.0, 28.0)
            maxWidth = 420.0; maxHeight = Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, box)
    }
}

/** For the details page: whether the kids filter hides this title. */
fun blockedForKids(response: com.lagradost.cloudstream3.LoadResponse) = !ContentFilter.allows(response)
