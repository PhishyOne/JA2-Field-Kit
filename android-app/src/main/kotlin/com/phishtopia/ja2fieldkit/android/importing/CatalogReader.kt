package com.phishtopia.ja2fieldkit.android.importing

import com.phishtopia.ja2fieldkit.core.catalog.CatalogFailure
import com.phishtopia.ja2fieldkit.core.catalog.CatalogResult
import com.phishtopia.ja2fieldkit.core.catalog.GogEnglishItemCatalog
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Never queries provider size, and consumes at most the exact size plus one byte. */
object CatalogReader {
    fun read(scheme: String?, open: () -> InputStream?): CatalogResult {
        if (scheme != "content") return CatalogResult.Rejected(CatalogFailure.NOT_CONTENT_URI)
        return try {
            open()?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val limit = GogEnglishItemCatalog.SIZE + 1
                while (output.size() < limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count < 0) break
                    if (count == 0) {
                        val byte = input.read()
                        if (byte < 0) break
                        output.write(byte)
                    } else output.write(buffer, 0, count)
                }
                GogEnglishItemCatalog.admit(output.toByteArray())
            } ?: CatalogResult.Rejected(CatalogFailure.UNAVAILABLE)
        } catch (_: SecurityException) {
            CatalogResult.Rejected(CatalogFailure.UNAVAILABLE)
        } catch (_: java.io.IOException) {
            CatalogResult.Rejected(CatalogFailure.READ_FAILED)
        } catch (_: RuntimeException) {
            CatalogResult.Rejected(CatalogFailure.READ_FAILED)
        }
    }
}
