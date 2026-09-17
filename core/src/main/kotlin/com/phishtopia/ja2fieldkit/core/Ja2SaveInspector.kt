package com.phishtopia.ja2fieldkit.core

import com.phishtopia.ja2fieldkit.core.format.EncryptedProfileFrame
import com.phishtopia.ja2fieldkit.core.format.HeaderProbe
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileDecoder
import com.phishtopia.ja2fieldkit.core.format.NormalNonLinuxProfileFramer
import com.phishtopia.ja2fieldkit.core.format.SaveHeader
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderParser
import com.phishtopia.ja2fieldkit.core.format.SaveHeaderProbe
import com.phishtopia.ja2fieldkit.core.model.MercProfile

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

    /** Frame the encrypted profiles in the evidenced normal non-Linux layout without decrypting. */
    fun frameBuild041202NormalNonLinuxProfiles(bytes: ByteArray): EncryptedProfileFrame =
        NormalNonLinuxProfileFramer.frameBuild041202(bytes)

    /**
     * Frame, recover, decrypt, and parse all 170 profiles in the evidenced normal non-Linux layout.
     * The returned table is not a hired-player roster.
     */
    fun parseBuild041202NormalNonLinuxProfiles(bytes: ByteArray): List<MercProfile> =
        NormalNonLinuxProfileDecoder.decodeBuild041202(bytes)
}
