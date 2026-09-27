package com.phishtopia.ja2fieldkit.core.catalog

import java.security.MessageDigest
import java.util.Collections

/** Ephemeral display decoration. No archive storage or save authority. */
class BaseItemCatalog internal constructor(names: Map<Int, String>) {
    val label: String get() = GogEnglishItemCatalog.LABEL
    val names: Map<Int, String> = Collections.unmodifiableMap(HashMap(names))
}

enum class CatalogFailure {
    WRONG_SIZE, WRONG_IDENTITY, MALFORMED_ARCHIVE, TARGET_NOT_UNIQUE, MALFORMED_ITEMDESC,
    NOT_CONTENT_URI, UNAVAILABLE, READ_FAILED,
}

sealed interface CatalogResult {
    data class Loaded(val catalog: BaseItemCatalog) : CatalogResult
    data class Rejected(val reason: CatalogFailure) : CatalogResult
}

object GogEnglishItemCatalog {
    const val LABEL = "GOG English v1.12 (Build 04.12.02)"
    const val SIZE = 2_047_959
    const val MD5 = "ffd1c49977c891d9c7ffc7756a25f741"

    fun admit(bytes: ByteArray): CatalogResult {
        if (bytes.size != SIZE) return CatalogResult.Rejected(CatalogFailure.WRONG_SIZE)
        // MD5 identifies this one resource; it is not authentication or a security guarantee.
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val expected = MD5.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!digest.contentEquals(expected)) return CatalogResult.Rejected(CatalogFailure.WRONG_IDENTITY)
        return parseAdmitted(bytes)
    }

    /** Internal seam for invented fixtures; production callers must use exact admission. */
    internal fun parseAdmitted(bytes: ByteArray): CatalogResult {
        fun reject(reason: CatalogFailure = CatalogFailure.MALFORMED_ARCHIVE) = CatalogResult.Rejected(reason)
        if (bytes.size < 532) return reject()
        fun uint(at: Int): Long = (0..3).fold(0L) { value, i ->
            value or ((bytes[at + i].toLong() and 255) shl (i * 8))
        }
        fun path(at: Int): String? {
            val end = (at until at + 256).firstOrNull { bytes[it] == 0.toByte() } ?: return null
            if ((at until end).any { (bytes[it].toInt() and 255) !in 32..126 }) return null
            return buildString {
                for (i in at until end) {
                    val c = bytes[i].toInt().toChar()
                    append(if (c == '\\') '/' else if (c in 'A'..'Z') c + 32 else c)
                }
            }
        }
        path(0) ?: return reject()
        val libraryPath = path(256) ?: return reject()
        val count = uint(512)
        val table = bytes.size.toLong() - count * 280L
        if (count == 0L || count > Int.MAX_VALUE || table < 532L) return reject()
        var targetOffset = 0L
        var targetLength = 0L
        var matches = 0
        for (i in 0 until count.toInt()) {
            val entry = (table + i.toLong() * 280).toInt()
            val entryPath = path(entry) ?: return reject()
            // Both direct nested paths and paths relative to the library directory are allowed.
            fun denotesTarget(value: String): Boolean {
                if (value.isEmpty() || value.endsWith('/')) return false
                val parts = value.split('/').filter { it.isNotEmpty() }
                return parts.isNotEmpty() && parts.none { it == "." || it == ".." } &&
                    parts.last() == "itemdesc.edt"
            }
            if (bytes[entry + 264] == 0.toByte() &&
                (denotesTarget(entryPath) || denotesTarget("$libraryPath/$entryPath"))) {
                matches++
                targetOffset = uint(entry + 256)
                targetLength = uint(entry + 260)
            }
        }
        if (matches != 1) return reject(CatalogFailure.TARGET_NOT_UNIQUE)
        if (targetOffset < 532 || targetOffset + targetLength > table) return reject()
        if (targetLength == 0L || targetLength % 800 != 0L) return reject(CatalogFailure.MALFORMED_ITEMDESC)
        val names = mutableMapOf<Int, String>()
        repeat((targetLength / 800).toInt()) { id ->
            val start = (targetOffset + id.toLong() * 800 + 160).toInt()
            val name = buildString {
                for (unit in 0 until 79) {
                    val at = start + unit * 2
                    val encoded = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
                    if (encoded == 0) break
                    append((if (encoded > 33) encoded - 1 else encoded).toChar())
                }
            }
            if (usable(name)) names[id] = name
        }
        return CatalogResult.Loaded(BaseItemCatalog(names))
    }

    private fun usable(name: String): Boolean {
        if (name.isBlank()) return false
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= name.length || !name[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate() || c.isISOControl() || c == '\uFFFE' || c == '\uFFFF') return false
                i++
            }
        }
        return true
    }
}
