package io.github.sherazi5110.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mapper

/**
 * Who is watching. Each profile has its own library (history, progress, bookmarks), home
 * layout, look and player preferences; extensions and app-wide settings are shared.
 *
 * Storage: the first profile ("default") uses the original preference files, so existing
 * data simply becomes the first profile. Other profiles use "<file>@<id>" copies.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Profile(
    val id: String,
    val name: String,
    /** "character:color" for a built-in avatar (see ui/Avatars.kt), or "file:<path>" for a picture. */
    val avatar: String,
    val kids: Boolean = false,
    /** 4-digit PIN asked before switching to this profile; null = none. */
    val pin: String? = null,
    val filter: KidsFilter = KidsFilter(),
)

/**
 * What a kids profile may see. The extension allow-list is the real protection (extensions
 * don't always label content); types, age ratings and words narrow it down further.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KidsFilter(
    /** Extension names the profile may use. Empty = none, so a new kids profile starts closed. */
    val extensions: Set<String> = emptySet(),
    /** Allowed [TvType] names; items of other types are hidden (items without a type are kept). */
    val types: Set<String> = DEFAULT_TYPES,
    /** Highest age a rating may require (e.g. 12 hides "PG-13", "TV-14", "R", "16"…). */
    val maxAge: Int = 12,
    val blockedWords: List<String> = DEFAULT_WORDS,
) {
    companion object {
        val DEFAULT_TYPES = setOf(TvType.Cartoon, TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries, TvType.Documentary).map { it.name }.toSet()
        val DEFAULT_WORDS = listOf("horror", "erotic", "adult", "hentai", "ecchi", "18+", "xxx", "nsfw", "gore", "slasher", "porn", "sex")
    }
}

object Profiles {
    const val DEFAULT = "default"
    private val prefs get() = App.context.getSharedPreferences("profiles", 0)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Runs after switching profile or editing one. Returns a function that unregisters it. */
    fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() = listeners.forEach { it() }

    fun all(): List<Profile> {
        val saved = prefs.getString("list", null)?.let { runCatching { mapper.readValue<List<Profile>>(it) }.getOrNull() }
        return saved?.takeIf { it.isNotEmpty() } ?: listOf(Profile(DEFAULT, "Me", "smile:blue"))
    }

    private fun save(list: List<Profile>) {
        prefs.edit().putString("list", mapper.writeValueAsString(list)).apply()
    }

    @Volatile private var currentId: String? = null

    val current: Profile
        get() {
            val all = all()
            val id = currentId ?: prefs.getString("current", null)
            return all.firstOrNull { it.id == id } ?: all.first()
        }

    val isKids get() = current.kids

    /** Show "Who's watching?" at startup (only matters with more than one profile). */
    var askOnStart: Boolean
        get() = prefs.getString("ask_on_start", null) != "false"
        set(v) { prefs.edit().putString("ask_on_start", v.toString()).apply() }

    /**
     * The parent PIN guards leaving a kids profile. It's created when the first kids profile
     * is made, and stored as a salted hash.
     */
    val hasParentPin get() = !prefs.getString("parent_pin", null).isNullOrBlank()

    fun setParentPin(pin: String?) {
        prefs.edit().putString("parent_pin", pin?.let(::hash) ?: "").apply()
    }

    fun checkParentPin(pin: String) = hasParentPin && prefs.getString("parent_pin", null) == hash(pin)

    private fun hash(pin: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("cloudstream-linux:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Whether going from the current profile to [target] needs a PIN, and which PINs open it. */
    fun unlockFor(target: Profile): ((String) -> Boolean)? {
        val leavingKids = current.kids && !target.kids
        val own = target.pin
        return when {
            !leavingKids && own == null -> null
            !leavingKids -> { p -> p == own || checkParentPin(p) }
            own == null && !hasParentPin -> null // set up before parent PINs existed: nothing to ask
            else -> { p -> (own != null && p == own) || checkParentPin(p) }
        }
    }

    fun switchTo(id: String) {
        if (all().none { it.id == id }) return
        currentId = id
        prefs.edit().putString("current", id).apply()
        changed()
    }

    fun add(name: String, avatar: String, kids: Boolean): Profile {
        val profile = Profile(java.util.UUID.randomUUID().toString().take(8), name.trim().ifBlank { "Profile" }, avatar, kids)
        save(all() + profile)
        changed()
        return profile
    }

    fun update(profile: Profile) {
        save(all().map { if (it.id == profile.id) profile else it })
        changed()
    }

    /** Deletes a profile and its data. The last remaining profile can't be deleted. */
    fun delete(id: String) {
        val all = all()
        if (all.size <= 1 || all.none { it.id == id }) return
        if (id != DEFAULT) PROFILE_FILES.forEach { App.context.getSharedPreferences("$it@$id", 0).edit().clear().apply() }
        else PROFILE_FILES.forEach { App.context.getSharedPreferences(it, 0).edit().clear().apply() }
        save(all.filterNot { it.id == id })
        if (current.id == id || currentId == id) {
            currentId = null
            prefs.edit().putString("current", all().first().id).apply()
        }
        changed()
    }

    /** The preference file [name] for the current profile. */
    fun file(name: String): String = current.id.let { if (it == DEFAULT) name else "$name@$it" }

    /** Preference files that belong to a profile. */
    val PROFILE_FILES = listOf("library", "home_catalogs", "profile_settings")
}

/** Applies the current kids profile's rules; everything passes for other profiles. */
object ContentFilter {
    private val filter: KidsFilter? get() = Profiles.current.takeIf { it.kids }?.filter

    val active get() = filter != null

    /** Adult content is shown only to grown-up profiles that turned it on (Settings → Profiles). */
    val nsfwAllowed get() = !Profiles.isKids && Settings.showNsfw

    fun allows(api: MainAPI): Boolean {
        if (TvType.NSFW in api.supportedTypes && !nsfwAllowed) return false
        val f = filter ?: return true
        return api.name in f.extensions
    }

    fun allows(item: SearchResponse): Boolean {
        if (item.type == TvType.NSFW && !nsfwAllowed) return false
        val f = filter ?: return true
        if (item.type != null && item.type!!.name !in f.types) return false
        return !hasBlockedWord(f, item.name)
    }

    /** For a details page: rating, type, title, tags and plot. */
    fun allows(response: LoadResponse): Boolean {
        if (response.type == TvType.NSFW && !nsfwAllowed) return false
        val f = filter ?: return true
        if (response.type == TvType.NSFW || response.type.name !in f.types) return false
        val age = response.contentRating?.let(::ageOf)
        if (age != null && age > f.maxAge) return false
        return !hasBlockedWord(f, listOf(response.name, response.tags.orEmpty().joinToString(" "), response.plot.orEmpty()).joinToString(" "))
    }

    private fun hasBlockedWord(f: KidsFilter, text: String): Boolean {
        val lower = text.lowercase()
        return f.blockedWords.any { w -> w.isNotBlank() && Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(w.lowercase().trim())}($|[^\\p{L}\\p{N}])").containsMatchIn(lower) }
    }

    /** Minimum age for common ratings (US film/TV, and plain numbers such as "12", "16+", "FSK 16"). */
    fun ageOf(rating: String): Int? {
        val r = rating.uppercase().replace(" ", "").replace("_", "-")
        return when (r) {
            "G", "TV-Y", "TV-G", "U", "ALL", "0", "0+" -> 0
            "TV-Y7", "TV-Y7-FV" -> 7
            "PG", "TV-PG" -> 8
            "PG-13" -> 13
            "TV-14" -> 14
            "R", "TV-MA", "NC-17", "X", "18", "18+", "R18", "R18+" -> 18
            else -> Regex("(\\d{1,2})").find(r)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..21 }
        }
    }
}
