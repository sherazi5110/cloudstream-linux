package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** The parts of android.net.Uri extensions use for picking urls apart. */
class Uri private constructor(private val raw: String) {
    private val uri: URI? = runCatching { URI(raw) }.getOrNull()

    fun getScheme(): String? = uri?.scheme
    fun getHost(): String? = uri?.host
    fun getPort(): Int = uri?.port ?: -1
    fun getAuthority(): String? = uri?.authority
    fun getPath(): String? = uri?.path
    fun getEncodedPath(): String? = uri?.rawPath
    fun getQuery(): String? = uri?.query
    fun getEncodedQuery(): String? = uri?.rawQuery
    fun getFragment(): String? = uri?.fragment
    fun getPathSegments(): List<String> = getPath().orEmpty().split('/').filter { it.isNotEmpty() }
    fun getLastPathSegment(): String? = getPathSegments().lastOrNull()

    private fun params(): List<Pair<String, String>> = uri?.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        decode(it.substringBefore('='))!! to decode(it.substringAfter('=', ""))!!
    }

    fun getQueryParameter(key: String): String? = params().firstOrNull { it.first == key }?.second
    fun getQueryParameters(key: String): List<String> = params().filter { it.first == key }.map { it.second }
    fun getQueryParameterNames(): Set<String> = params().mapTo(LinkedHashSet()) { it.first }
    fun isAbsolute(): Boolean = getScheme() != null

    override fun toString() = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()

    companion object {
        @JvmField val EMPTY = Uri("")

        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic fun encode(s: String?): String? = encode(s, null)

        @JvmStatic fun encode(s: String?, allow: String?): String? = s?.let {
            val encoded = URLEncoder.encode(it, "UTF-8").replace("+", "%20").replace("%7E", "~")
            if (allow == null) encoded
            else allow.fold(encoded) { acc, c -> acc.replace(URLEncoder.encode(c.toString(), "UTF-8"), c.toString()) }
        }

        @JvmStatic fun decode(s: String?): String? = s?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    }
}
