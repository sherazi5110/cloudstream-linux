package android.util

/** android.util.Base64 on top of java.util.Base64; extensions use it heavily to decode stream urls. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic fun decode(str: String?, flags: Int): ByteArray = decode(str.orEmpty().toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray = decode(input, 0, input.size, flags)

    @JvmStatic fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        // Android's decoder ignores whitespace, accepts missing padding, and accepts both alphabets.
        var s = String(input, offset, len, Charsets.US_ASCII).filterNot { it.isWhitespace() }.trimEnd('=')
        s = s.replace('-', '+').replace('_', '/')
        return try {
            java.util.Base64.getDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base-64", e)
        }
    }

    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray = encode(input, 0, input.size, flags)

    @JvmStatic fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray =
        encodeToString(input, offset, len, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String = encodeToString(input, 0, input.size, flags)

    @JvmStatic fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val encoded = encoder.encodeToString(input.copyOfRange(offset, offset + len))
        if (flags and NO_WRAP != 0) return encoded
        // DEFAULT wraps at 76 chars and ends with a newline, like Android.
        val newline = if (flags and CRLF != 0) "\r\n" else "\n"
        return encoded.chunked(76).joinToString(newline) + newline
    }
}
