package com.filestech.sms.data.mms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 48 — the encrypted-media part shape
 * ([RelayMediaFilename]): the filename is the ONLY link between
 * the key message and the ciphertext part, so the match must be
 * strict — a near-miss must never decrypt the wrong part.
 */
class RelayMediaFilenameTest {

    @Test
    fun `a valid name parses to its file id`() {
        assertThat(RelayMediaFilename.parse("relay-media-0123456789abcdef.bin"))
            .isEqualTo("0123456789abcdef")
    }

    @Test
    fun `fileNameFor builds the sender naming`() {
        assertThat(RelayMediaFilename.fileNameFor("0123456789abcdef"))
            .isEqualTo("relay-media-0123456789abcdef.bin")
    }

    @Test
    fun `wrong prefix suffix or hex length parse to null`() {
        assertThat(RelayMediaFilename.parse("relay-media-0123456789abcde.bin")).isNull()
        assertThat(RelayMediaFilename.parse("relay-media-0123456789abcdef0.bin")).isNull()
        assertThat(RelayMediaFilename.parse("relay-media-0123456789abcdef.jpg")).isNull()
        assertThat(RelayMediaFilename.parse("media-0123456789abcdef.bin")).isNull()
        assertThat(RelayMediaFilename.parse("photo.jpg")).isNull()
        assertThat(RelayMediaFilename.parse("")).isNull()
        assertThat(RelayMediaFilename.parse(null)).isNull()
    }

    @Test
    fun `uppercase hex parses to null`() {
        // The sender generates lowercase; a renamed or
        // re-encoded part must not match.
        assertThat(RelayMediaFilename.parse("relay-media-0123456789ABCDEF.bin")).isNull()
    }

    @Test
    fun `isEncryptedPart needs the octet-stream mime AND the name`() {
        assertThat(
            RelayMediaFilename.isEncryptedPart(
                "application/octet-stream",
                "relay-media-0123456789abcdef.bin",
            ),
        ).isTrue()
        // A plain .bin file has the MIME but not the name.
        assertThat(
            RelayMediaFilename.isEncryptedPart("application/octet-stream", "archive.bin"),
        ).isFalse()
        // A renamed part could carry the name with a re-typed MIME.
        assertThat(
            RelayMediaFilename.isEncryptedPart(
                "image/jpeg",
                "relay-media-0123456789abcdef.bin",
            ),
        ).isFalse()
        assertThat(RelayMediaFilename.isEncryptedPart(null, null)).isFalse()
    }

    @Test
    fun `isEncryptedPart treats the mime case-insensitively`() {
        // MMS headers vary in case; the filename stays strict.
        assertThat(
            RelayMediaFilename.isEncryptedPart(
                "Application/Octet-Stream",
                "relay-media-0123456789abcdef.bin",
            ),
        ).isTrue()
    }
}
