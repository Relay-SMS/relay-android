package com.filestech.sms.data.mms

/**
 * Round 48 — the encrypted-media part shape (design §3): a
 * ciphertext MMS part is an `application/octet-stream` part named
 * `relay-media-<fileId>.bin`, where `<fileId>` is the 16-hex-char
 * id from the key message ([com.filestech.sms.e2e.E2eMediaKeyMessage]).
 * The filename is the ONLY link between the key message (regular
 * SMS) and the ciphertext part (MMS) — the two channels deliver
 * independently, so the receiver matches them by this name.
 *
 * Lives in `:data`, not `:app`: the import path
 * ([ConversationMirror][com.filestech.sms.data.repository.ConversationMirror],
 * [IntegrationMmsRecu]) needs the shape test, and `:data` cannot
 * see `:app`'s E2E code — the same reason `E2E_FRAGMENT_PREFIX`
 * is duplicated in the mirror's companion.
 *
 * The filename survives the sender's `safeAttachmentName`
 * byte-identical (32 chars, only `[a-z0-9.-]`), and the live
 * receive path preserves it at write time
 * ([EcrivainDePiecesSurDisque]) — every other part keeps its
 * generated name, so plain MMS is untouched.
 */
object RelayMediaFilename {

    /** `relay-media-` + 16 lowercase hex + `.bin`, nothing else. */
    private val PATTERN = Regex("^relay-media-([0-9a-f]{16})\\.bin$")

    /** The part filename for one file id (the sender's naming). */
    fun fileNameFor(fileId: String): String = "relay-media-$fileId.bin"

    /**
     * The file id carried by [fileName], or null when the name is
     * not a relay-media part name. Strict: only lowercase hex
     * matches — the sender generates lowercase, and the name
     * survives the carrier byte-identical.
     */
    fun parse(fileName: String?): String? {
        if (fileName == null) return null
        return PATTERN.matchEntire(fileName)?.groupValues?.getOrNull(1)
    }

    /**
     * Whether a part is an encrypted-media ciphertext part: the
     * opaque MIME *and* the relay-media filename, both. Either
     * alone is not enough — a plain `.bin` file has the MIME but
     * not the name, and a renamed part could carry the name with
     * a re-typed MIME. The MIME check is case-insensitive (MMS
     * headers vary in case); the filename check is strict.
     */
    fun isEncryptedPart(mime: String?, fileName: String?): Boolean =
        mime.equals("application/octet-stream", ignoreCase = true) &&
            parse(fileName) != null
}
