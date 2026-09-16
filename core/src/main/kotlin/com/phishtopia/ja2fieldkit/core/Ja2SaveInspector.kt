package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.HeaderProbe
import com.phishtopia.ja2fieldkit.core.format.SaveHeader
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderParser
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderProbe

/**
 * Public read-only entry point for the parser core.
 *
 * Full format detection and logical parsing are intentionally not exposed until
 * their offsets and encryption rules are backed by fixtures and tests.
 */
class Ja2SaveInspector {
    fun probe(bytes: ByteArray): HeaderProbe = SaveHeaderProbe.probe(bytes)

    /** Parse the evidenced header layout without identifying a save family. */
    fun parseBuild041202Header(bytes: ByteArray): SaveHeader =
        SaveHeaderParser.parseBuild041202(bytes)
}
