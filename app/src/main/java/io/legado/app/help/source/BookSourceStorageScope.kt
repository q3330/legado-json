package io.legado.app.help.source

import java.security.MessageDigest

internal object BookSourceStorageScope {

    private const val identityPrefix = "book\u0000"
    private val hexChars = "0123456789abcdef".toCharArray()
    private val sha256 = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    fun namespace(sourceUrl: String): String {
        val digest = sha256.get()!!
        digest.reset()
        val bytes = digest.digest((identityPrefix + sourceUrl).toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size shl 1)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i shl 1] = hexChars[v ushr 4]
            out[(i shl 1) + 1] = hexChars[v and 0x0f]
        }
        return String(out)
    }
}
