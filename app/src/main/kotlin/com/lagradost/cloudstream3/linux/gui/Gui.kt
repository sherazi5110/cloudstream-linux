package com.lagradost.cloudstream3.linux.gui

import android.widget.Toast
import com.formdev.flatlaf.FlatDarkLaf
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.Links
import com.lagradost.cloudstream3.linux.Platform
import com.lagradost.cloudstream3.linux.Playable
import com.lagradost.cloudstream3.linux.Player
import com.lagradost.cloudstream3.linux.PluginManager
import com.lagradost.cloudstream3.linux.PluginWrapper
import com.lagradost.cloudstream3.linux.RepositoryManager
import com.lagradost.cloudstream3.linux.statusName
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.DefaultTableModel

private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
private const val POSTER_W = 140
private const val POSTER_H = 210

fun launchGui(repoLink: String? = null) {
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val window = MainWindow()
        Toast.showHandler = { msg -> SwingUtilities.invokeLater { window.status(msg) } }
        window.isVisible = true
        repoLink?.let {
            window.tabs.selectedComponent = window.extensions
            window.extensions.addRepo(it)
        }
        System.getenv("CS_SCREENSHOT")?.let { path -> Screenshots.schedule(window, path) }
    }
}

/** Runs [work] off the UI thread and reports failures in the status bar. */
private fun MainWindow.background(label: String, work: suspend () -> Unit) = ui.launch {
    status("$label…")
    try {
        work()
        status("")
    } catch (t: Throwable) {
        status("$label failed: ${t.message ?: t}")
    }
}

private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

class MainWindow : JFrame("CloudStream for Linux") {
    private val statusLabel = JLabel(" ")
    val tabs = JTabbedPane(JTabbedPane.LEFT)
    val home = HomePanel(this)
    val search = SearchPanel(this)
    val extensions = ExtensionsPanel(this)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        size = Dimension(1180, 760)
        setLocationRelativeTo(null)
        tabs.addTab("Home", home)
        tabs.addTab("Search", search)
        tabs.addTab("Extensions", extensions)
        tabs.addTab("Settings", SettingsPanel(this))
        tabs.addChangeListener { if (tabs.selectedComponent == home) home.refreshProviders() }
        statusLabel.border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(statusLabel, BorderLayout.SOUTH)
        PluginManager.failures.forEach { (name, e) -> status("$name failed to load: ${e.message ?: e}") }
        if (Engine.providers().isEmpty()) {
            tabs.selectedComponent = extensions
            status("No extensions installed yet — add a repository on the Extensions tab")
        }
    }

    fun status(text: String) {
        statusLabel.text = text.ifEmpty { " " }
    }

    fun providersChanged() {
        home.refreshProviders()
        search.refreshProviders()
    }

    fun open(api: MainAPI, item: SearchResponse) = background("Loading ${item.name}") {
        val response = io { Engine.load(api, item.url) } ?: error("provider returned nothing")
        DetailsDialog(this, api, response).isVisible = true
    }
}

/** Poster images, cached in memory and loaded through the same HTTP client plugins use. */
object Posters {
    private val cache = ConcurrentHashMap<String, ImageIcon>()
    private val placeholder by lazy {
        val img = BufferedImage(POSTER_W, POSTER_H, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color(0x2b2f36); fillRect(0, 0, POSTER_W, POSTER_H); dispose() }
        ImageIcon(img)
    }

    fun load(url: String?, label: JLabel, w: Int = POSTER_W, h: Int = POSTER_H) {
        label.icon = placeholder
        if (url.isNullOrBlank()) return
        cache[url]?.let { label.icon = it; return }
        ui.launch {
            val icon = runCatching {
                io {
                    val bytes = app.get(url).okhttpResponse.body.bytes()
                    ImageIO.read(bytes.inputStream())?.getScaledInstance(w, h, Image.SCALE_SMOOTH)?.let(::ImageIcon)
                }
            }.getOrNull() ?: return@launch
            cache[url] = icon
            label.icon = icon
        }
    }
}

private fun posterCard(window: MainWindow, api: MainAPI, item: SearchResponse): JComponent {
    val image = JLabel()
    Posters.load(item.posterUrl, image)
    val title = JLabel("<html><body style='width:${POSTER_W - 10}px'>${item.name}</body></html>")
    title.font = title.font.deriveFont(Font.PLAIN, 12f)
    return JPanel(BorderLayout(0, 4)).apply {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(image, BorderLayout.CENTER)
        add(title, BorderLayout.SOUTH)
        toolTipText = "${item.name} — ${api.name}"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = window.open(api, item).let { }
        })
    }
}

private fun providerCombo(includeAll: Boolean): JComboBox<String> =
    JComboBox((listOfNotNull("All providers".takeIf { includeAll }) + Engine.providers().map { it.name }).toTypedArray())

class HomePanel(private val window: MainWindow) : JPanel(BorderLayout()) {
    private var providers = providerCombo(includeAll = false)
    private val top = JPanel(FlowLayout(FlowLayout.LEFT))
    private val sections = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        top.add(JLabel("Provider:"))
        top.add(providers)
        top.add(JButton("Refresh").apply { addActionListener { reload() } })
        add(top, BorderLayout.NORTH)
        add(JScrollPane(sections).apply { verticalScrollBar.unitIncrement = 24 }, BorderLayout.CENTER)
        providers.addActionListener { reload() }
        reload()
    }

    fun refreshProviders() {
        val current = providers.selectedItem
        val fresh = Engine.providers().map { it.name }
        if (fresh == (0 until providers.itemCount).map { providers.getItemAt(it) }) return
        providers.removeAllItems()
        fresh.forEach { providers.addItem(it) }
        if (current in fresh) providers.selectedItem = current
    }

    private fun reload() {
        val api = Engine.provider(providers.selectedItem as? String ?: return) ?: return
        sections.removeAll()
        sections.revalidate()
        window.background("Loading ${api.name} home page") {
            val lists: List<HomePageList> = io { Engine.mainPage(api) }
            if (lists.isEmpty()) sections.add(JLabel("  ${api.name} has no home page — use Search"))
            for (list in lists) {
                sections.add(JLabel(list.name).apply {
                    font = font.deriveFont(Font.BOLD, 16f)
                    border = BorderFactory.createEmptyBorder(12, 8, 4, 8)
                    alignmentX = LEFT_ALIGNMENT
                })
                val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
                list.list.forEach { row.add(posterCard(window, api, it)) }
                sections.add(JScrollPane(row, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
                    border = null
                    alignmentX = LEFT_ALIGNMENT
                    preferredSize = Dimension(10, POSTER_H + 70)
                    maximumSize = Dimension(Int.MAX_VALUE, POSTER_H + 70)
                    horizontalScrollBar.unitIncrement = 24
                })
            }
            sections.revalidate()
            sections.repaint()
        }
    }
}

class SearchPanel(private val window: MainWindow) : JPanel(BorderLayout()) {
    private val query = JTextField(32)
    private var providers = providerCombo(includeAll = true)
    private val top = JPanel(FlowLayout(FlowLayout.LEFT))
    private val results = JPanel(GridLayout(0, 6, 4, 4))

    init {
        val go = JButton("Search")
        top.add(query)
        top.add(providers)
        top.add(go)
        add(top, BorderLayout.NORTH)
        add(JScrollPane(JPanel(BorderLayout()).apply { add(results, BorderLayout.NORTH) }).apply {
            verticalScrollBar.unitIncrement = 24
        }, BorderLayout.CENTER)
        go.addActionListener { run() }
        query.addActionListener { run() }
    }

    fun refreshProviders() {
        top.remove(providers)
        providers = providerCombo(includeAll = true)
        top.add(providers, 1)
        top.revalidate()
    }

    fun run(text: String = query.text) {
        query.text = text
        if (text.isBlank()) return
        val selected = providers.selectedItem as String
        val apis = if (selected == "All providers") Engine.providers() else listOfNotNull(Engine.provider(selected))
        results.removeAll()
        window.background("Searching ${apis.size} provider(s) for \"$text\"") {
            val found = io { Engine.search(text, apis) }
            found.forEach { (api, item) -> results.add(posterCard(window, api, item)) }
            window.status("${found.size} result(s)")
            results.revalidate()
            results.repaint()
        }
    }
}

class DetailsDialog(private val window: MainWindow, private val api: MainAPI, private val response: LoadResponse) :
    JDialog(window, response.name, false) {
    private val playables = Engine.playables(response)
    private val list = JList(playables.map { it.label }.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        if (playables.isNotEmpty()) selectedIndex = 0
    }

    init {
        size = Dimension(820, 560)
        setLocationRelativeTo(window)
        val poster = JLabel().also { Posters.load(response.posterUrl, it, 220, 330) }
        val title = JLabel(response.name).apply { font = font.deriveFont(Font.BOLD, 20f) }
        val meta = JLabel(listOfNotNull(response.year?.toString(), response.type.name, api.name).joinToString("  •  "))
        val plot = JTextArea(response.plot ?: "").apply {
            lineWrap = true; wrapStyleWord = true; isEditable = false; isOpaque = false
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
        }
        val play = JButton("▶  Play").apply { font = font.deriveFont(Font.BOLD, 14f) }
        val sources = JButton("Sources…")
        play.addActionListener { selected()?.let { resolve(it, autoplay = true) } }
        sources.addActionListener { selected()?.let { resolve(it, autoplay = false) } }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selected()?.let { resolve(it, autoplay = true) }
            }
        })

        val info = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(title); add(Box.createVerticalStrut(4)); add(meta)
            }, BorderLayout.NORTH)
            add(JScrollPane(plot).apply { border = null; preferredSize = Dimension(10, 110) }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(JLabel(if (playables.size > 1) "Episodes" else "Watch"), BorderLayout.NORTH)
                add(JScrollPane(list).apply { preferredSize = Dimension(10, 200) }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(play); add(sources) }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        contentPane.add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 0)
            add(poster, BorderLayout.NORTH)
        }, BorderLayout.WEST)
        contentPane.add(info, BorderLayout.CENTER)
    }

    private fun selected(): Playable? = playables.getOrNull(list.selectedIndex)

    private fun resolve(playable: Playable, autoplay: Boolean) = window.background("Finding sources for ${playable.label}") {
        val links = io { Engine.links(api, playable.data) }
        require(links.links.isNotEmpty()) { "no playable sources" }
        val title = if (playables.size > 1) "${response.name} — ${playable.label}" else response.name
        if (autoplay) play(window, links, 0, title) else SourcesDialog(window, links, title).isVisible = true
    }
}

private fun play(window: MainWindow, links: Links, index: Int, title: String) {
    try {
        Player.play(links.links[index], links.subtitles, title)
        window.status("Playing \"$title\" in ${Player.preferred}")
    } catch (t: Throwable) {
        JOptionPane.showMessageDialog(window, t.message, "Cannot play", JOptionPane.ERROR_MESSAGE)
    }
}

class SourcesDialog(window: MainWindow, links: Links, title: String) : JDialog(window, "Sources — $title", false) {
    init {
        size = Dimension(900, 380)
        setLocationRelativeTo(window)
        val model = object : DefaultTableModel(arrayOf("Quality", "Source", "Type", "URL"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        links.links.forEach {
            model.addRow(arrayOf(Qualities.getStringByInt(it.quality).ifEmpty { "?" }, it.name, it.type.name, it.url))
        }
        val table = JTable(model).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            if (rowCount > 0) setRowSelectionInterval(0, 0)
            columnModel.getColumn(3).preferredWidth = 480
        }
        val subs = JLabel("Subtitles: " + links.subtitles.joinToString { it.lang }.ifEmpty { "none" })
        contentPane.add(JScrollPane(table), BorderLayout.CENTER)
        contentPane.add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            add(subs, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(JButton("Copy URL").apply {
                    addActionListener {
                        val url = links.links.getOrNull(table.selectedRow)?.url ?: return@addActionListener
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(url), null)
                    }
                })
                add(JButton("▶  Play in ${Player.preferred}").apply {
                    addActionListener { if (table.selectedRow >= 0) play(window, links, table.selectedRow, title) }
                })
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }
}

class ExtensionsPanel(private val window: MainWindow) : JPanel(BorderLayout()) {
    private val repoModel = DefaultListModel<String>()
    private val repoList = JList(repoModel)
    private var available: List<PluginWrapper> = emptyList()
    private val pluginModel = object : DefaultTableModel(arrayOf("Name", "Version", "Language", "Format", "Status", "Installed", "Description"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val pluginTable = JTable(pluginModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }

    init {
        val repoButtons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Add repository…").apply { addActionListener { addRepo(null) } })
            add(JButton("Remove").apply { addActionListener { removeRepo() } })
            add(JButton("Reload").apply { addActionListener { refresh() } })
        }
        val repoPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Repositories")
            add(JScrollPane(repoList), BorderLayout.CENTER)
            add(repoButtons, BorderLayout.SOUTH)
        }
        val pluginButtons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Install / Update").apply { addActionListener { installSelected() } })
            add(JButton("Uninstall").apply { addActionListener { uninstallSelected() } })
            add(JButton("Update all").apply {
                addActionListener {
                    window.background("Updating extensions") {
                        val updated = io { PluginManager.updateAll() }
                        refresh()
                        window.status(if (updated.isEmpty()) "Everything up to date" else "Updated ${updated.joinToString()}")
                    }
                }
            })
            add(JButton("Install from file…").apply { addActionListener { sideload() } })
        }
        val pluginPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Extensions")
            add(JScrollPane(pluginTable), BorderLayout.CENTER)
            add(pluginButtons, BorderLayout.SOUTH)
        }
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, repoPanel, pluginPanel).apply { dividerLocation = 170 }, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        repoModel.clear()
        val repos = RepositoryManager.getRepositories()
        repos.forEach { repoModel.addElement("${it.name}   —   ${it.url}") }
        window.background("Reading ${repos.size} repositories") {
            available = io { repos.flatMap { RepositoryManager.getRepoPlugins(it).orEmpty() } }
            val installed = PluginManager.installed().associateBy { it.internalName }
            val local = installed.values.filter { local -> available.none { it.plugin.internalName == local.internalName } }
            pluginModel.rowCount = 0
            available.forEach { (_, _, p) ->
                val mine = installed[p.internalName]
                val state = when {
                    mine == null -> ""
                    mine.version < p.version -> "update → v${p.version}"
                    else -> "✓ v${mine.version}"
                }
                pluginModel.addRow(arrayOf<Any>(p.name, p.version, p.language ?: "", if (p.jarUrl != null) "jar" else "cs3", statusName(p.status), state, p.description ?: ""))
            }
            local.forEach { pluginModel.addRow(arrayOf<Any>(it.name ?: it.internalName, it.version, "", File(it.filePath).extension, "local", "✓", it.filePath)) }
            window.status("${available.size} extension(s) available, ${installed.size} installed, ${Engine.providers().size} provider(s) loaded")
        }
    }

    /** With [link] (from a cloudstreamrepo:// URL) the user confirms instead of typing. */
    fun addRepo(link: String? = null) {
        val input = if (link == null) {
            JOptionPane.showInputDialog(window, "Repository URL or shortcode", "Add repository", JOptionPane.PLAIN_MESSAGE)
        } else {
            link.takeIf {
                JOptionPane.showConfirmDialog(window, "Add this extension repository?\n$link", "Add repository",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION
            }
        } ?: return
        window.background("Adding repository") {
            val repo = io { RepositoryManager.addRepository(input) }
            window.status("Added ${repo.name}")
            refresh()
        }
    }

    private fun removeRepo() {
        val repo = RepositoryManager.getRepositories().getOrNull(repoList.selectedIndex) ?: return
        if (JOptionPane.showConfirmDialog(window, "Remove ${repo.name} and its extensions?", "Remove repository",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        RepositoryManager.removeRepository(repo.url)
        window.providersChanged()
        refresh()
    }

    private fun installSelected() {
        val wrapper = available.getOrNull(pluginTable.selectedRow) ?: return
        window.background("Installing ${wrapper.plugin.name}") {
            val loaded = io { PluginManager.install(wrapper) }.getOrThrow()
            window.providersChanged()
            refresh()
            window.status("Installed ${wrapper.plugin.name}: ${loaded.providers.joinToString { it.name }}")
        }
    }

    private fun uninstallSelected() {
        val row = pluginTable.selectedRow
        val name = available.getOrNull(row)?.plugin?.internalName
            ?: PluginManager.installed().filter { l -> available.none { it.plugin.internalName == l.internalName } }
                .getOrNull(row - available.size)?.internalName
            ?: return
        PluginManager.uninstall(name)
        window.providersChanged()
        refresh()
    }

    private fun sideload() {
        val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("CloudStream extension (.cs3, .jar)", "cs3", "jar") }
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return
        window.background("Loading ${chooser.selectedFile.name}") {
            val loaded = io { PluginManager.sideload(chooser.selectedFile) }.getOrThrow()
            window.providersChanged()
            refresh()
            window.status("Loaded ${loaded.manifest.name}: ${loaded.providers.joinToString { it.name }}")
        }
    }
}

class SettingsPanel(window: MainWindow) : JPanel(BorderLayout()) {
    init {
        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        }
        val installed = Player.installed()
        val players = JComboBox(Player.supported.map { if (it in installed) it else "$it (not installed)" }.toTypedArray())
        players.selectedIndex = Player.supported.indexOf(Player.preferred).coerceAtLeast(0)
        players.addActionListener { Player.preferred = Player.supported[players.selectedIndex] }
        val jsdelivr = JCheckBox("Load GitHub-hosted repositories through the jsDelivr CDN", RepositoryManager.useJsDelivr)
        jsdelivr.addActionListener { RepositoryManager.useJsDelivr = jsdelivr.isSelected }

        fun row(label: String, c: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel(label).apply { preferredSize = Dimension(140, 24); horizontalAlignment = SwingConstants.LEFT })
            add(c)
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 40)
        }
        form.add(row("Video player", players))
        form.add(row("Repositories", jsdelivr))
        form.add(row("Data folder", JLabel(Platform.context.dataDir.absolutePath)))
        if (installed.isEmpty()) {
            form.add(row("", JLabel("No player found. Install one: sudo apt install mpv")))
        }
        add(form, BorderLayout.NORTH)
    }
}

/** CS_SCREENSHOT=/path/prefix captures each tab to PNG (used for docs and smoke tests). */
private object Screenshots {
    fun schedule(window: MainWindow, prefix: String) = ui.launch {
        kotlinx.coroutines.delay(4000)
        for (i in 0 until window.tabs.tabCount) {
            window.tabs.selectedIndex = i
            kotlinx.coroutines.delay(2500)
            capture(window, "$prefix-${window.tabs.getTitleAt(i).lowercase()}.png")
        }
        System.getenv("CS_SCREENSHOT_SEARCH")?.let { q ->
            window.tabs.selectedComponent = window.search
            window.search.run(q)
            kotlinx.coroutines.delay(4000)
            capture(window, "$prefix-search-results.png")
            val first = Engine.search(q).firstOrNull()
            if (first != null) {
                window.open(first.first, first.second)
                kotlinx.coroutines.delay(4000)
                java.awt.Window.getWindows().filterIsInstance<DetailsDialog>().lastOrNull()?.let { capture(it, "$prefix-details.png") }
            }
        }
        System.exit(0)
    }

    private fun capture(c: java.awt.Component, path: String) {
        val img = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(path))
    }
}
