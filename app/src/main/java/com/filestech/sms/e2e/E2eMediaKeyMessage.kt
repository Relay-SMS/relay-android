package com.filestech.sms.e2e

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/**
 * The media key message (round 48, design §3) — the FIXED contract
 * between the sender (round 48, this half) and the receiver
 * (round 48, Worker 1b). Do not change the JSON shape without
 * changing both halves.
 *
 * A media file itself can never travel the encrypted-text channel
 * (99 fragments × 106 bytes ≈ 10.5 KB), so the sender encrypts the
 * file with a fresh random per-file AES-256-GCM key, sends the key
 * + metadata as ONE ordinary encrypted text message (payload type
 * `0x03` MEDIA_KEY — see [E2eWireFormat.PayloadType]), and sends
 * the ciphertext bytes as the MMS attachment part. The receiver
 * matches the MMS part to the key by the file [id]:
 *
 *     {"v":1,
 *      "id":"<16 hex chars>",
 *      "key":"<base64, 32-byte AES-256-GCM key>",
 *      "mime":"<original MIME>",
 *      "size":<plaintext bytes>,
 *      "sha256":"<hex SHA-256 of the plaintext>",
 *      "name":"<filename or null>"}
 *
 * - [MediaKey.id] is random per file (16 hex chars) and also names
 *   the ciphertext part file (`relay-media-<id>.bin`) — the id is
 *   the ONLY link between the key message and the MMS part, so the
 *   two must agree exactly.
 * - [MediaKey.key] is the raw 32-byte key, base64'd on the wire.
 *   It encrypts with [com.filestech.sms.core.crypto.AeadCipher]
 *   (the notes attachment envelope — `[version:1][iv:12][ct+tag]`,
 *   29 bytes overhead), never with new crypto.
 * - [MediaKey.sha256Hex] is the SHA-256 of the PLAINTEXT: the
 *   receiver verifies the decrypted bytes against it before
 *   rendering, so a carrier that re-encoded the ciphertext part
 *   fails loudly instead of showing garbage (the GCM tag would
 *   fail first — the hash is the second check, and the one that
 *   names the cause).
 * - [MediaKey.fileName] is the original filename (or null): the
 *   receiver shows it on the decrypted attachment, since the part
 *   itself is named `relay-media-<id>.bin`.
 *
 * Key messages only ever go to a media-capable peer (see
 * [E2eMediaCapabilityManager]): an old build throws on the
 * unknown MEDIA_KEY payload type and its receive path turns that
 * into an undecryptable placeholder plus a re-probe, so a key
 * message to an old build would spam it with phantom failed
 * messages.
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the JVM.
 * [decode] never throws: anything malformed (bad JSON, wrong
 * version, bad id/key/sha256 shape, negative size) returns null,
 * and the receiver treats a null key message as "no key" — the
 * ciphertext part stays pending, never garbage.
 */
object E2eMediaKeyMessage {

    /** JSON format version — the only version that exists. */
    const val FORMAT_VERSION: Int = 1

    /** File-id length: 16 hex chars (64 bits of randomness). */
    const val FILE_ID_HEX_CHARS: Int = 16

    /** The per-file key length: 32 bytes (AES-256). */
    const val KEY_BYTES: Int = 32

    /** The SHA-256 hex length: 64 chars. */
    const val SHA256_HEX_CHARS: Int = 64

    /**
     * The MIME type of a ciphertext MMS part: opaque bytes, never
     * the original MIME — a carrier or another app must not try to
     * render or transcode encrypted bytes as media.
     */
    const val MEDIA_PART_MIME: String = "application/octet-stream"

    /** The ciphertext part filename stem: `relay-media-<id>.bin`. */
    const val MEDIA_PART_PREFIX: String = "relay-media-"

    /** The ciphertext part filename suffix. */
    const val MEDIA_PART_SUFFIX: String = ".bin"

    private val json = Json { ignoreUnknownKeys = true }

    private val fileIdRegex = Regex("[0-9a-f]{$FILE_ID_HEX_CHARS}")
    private val sha256Regex = Regex("[0-9a-f]{$SHA256_HEX_CHARS}")
    private val partNameRegex = Regex("relay-media-([0-9a-f]{$FILE_ID_HEX_CHARS})\\.bin")

    /**
     * One file's key + metadata. A plain class, not a data class:
     * data-class equality on [key] ([ByteArray]) is referential,
     * which would silently break key comparisons (see
     * [E2eHandshake.HandshakePacket]). [toString] never prints
     * the key.
     */
    class MediaKey(
        /** Random per file (16 hex chars) — also the part filename stem. */
        val id: String,
        /** The raw 32-byte AES-256-GCM key for this file. */
        val key: ByteArray,
        /** The file's original MIME type (e.g. `image/jpeg`). */
        val mime: String,
        /** The plaintext size in bytes (before the 29-byte envelope). */
        val sizeBytes: Long,
        /** Lowercase hex SHA-256 of the plaintext. */
        val sha256Hex: String,
        /** The original filename, or null when there is none. */
        val fileName: String?,
    ) {

        init {
            require(id.matches(fileIdRegex)) { "file id must be $FILE_ID_HEX_CHARS hex chars" }
            require(key.size == KEY_BYTES) { "media key must be $KEY_BYTES bytes" }
            require(mime.isNotBlank()) { "mime must not be blank" }
            require(sizeBytes >= 0) { "size must not be negative" }
            require(sha256Hex.matches(sha256Regex)) {
                "sha256 must be $SHA256_HEX_CHARS hex chars"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MediaKey) return false
            return id == other.id &&
                key.contentEquals(other.key) &&
                mime == other.mime &&
                sizeBytes == other.sizeBytes &&
                sha256Hex == other.sha256Hex &&
                fileName == other.fileName
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + key.contentHashCode()
            result = 31 * result + mime.hashCode()
            result = 31 * result + sizeBytes.hashCode()
            result = 31 * result + sha256Hex.hashCode()
            result = 31 * result + (fileName?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "MediaKey(id=$id, key=<32-byte key>, mime=$mime, sizeBytes=$sizeBytes, " +
                "sha256Hex=$sha256Hex, fileName=$fileName)"
    }

    /** A random file id: 16 lowercase hex chars. */
    fun generateFileId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(FILE_ID_HEX_CHARS / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** A fresh random per-file key: 32 bytes. */
    fun generateKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(KEY_BYTES)
        random.nextBytes(key)
        return key
    }

    /**
     * The ciphertext MMS part filename for one file id:
     * `relay-media-<id>.bin`. The id is the ONLY link between the
     * key message and the part, so the sender names the part file
     * exactly this and the receiver matches on it. The name passes
     * the MMS builder's filename sanitizer unchanged
     * (`[A-Za-z0-9._-]` only, 32 chars).
     */
    fun partFileName(fileId: String): String = "$MEDIA_PART_PREFIX$fileId$MEDIA_PART_SUFFIX"

    /**
     * The file id carried by a ciphertext part filename, or null
     * when the name is not a Relay media part (a plain attachment,
     * or a part another app named). The receiver's matcher.
     */
    fun fileIdFromPartName(name: String): String? =
        partNameRegex.matchEntire(name)?.groupValues?.getOrNull(1)

    /** Encode a key as its JSON wire form. */
    fun encode(key: MediaKey): String {
        return json.encodeToString(
            MediaKeyJson.serializer(),
            MediaKeyJson(
                v = FORMAT_VERSION,
                id = key.id,
                key = Base64.getEncoder().encodeToString(key.key),
                mime = key.mime,
                size = key.sizeBytes,
                sha256 = key.sha256Hex,
                name = key.fileName,
            ),
        )
    }

    /**
     * Decode a key message's JSON plaintext. Returns null for
     * anything malformed — bad JSON, an unknown version, a
     * non-hex id, a key that is not base64 for exactly 32 bytes,
     * a blank MIME, a negative size, a non-hex SHA-256. Never
     * throws on input.
     */
    fun decode(text: String): MediaKey? {
        val parsed = runCatching {
            json.decodeFromString(MediaKeyJson.serializer(), text)
        }.getOrNull() ?: return null
        val keyBytes = runCatching { Base64.getDecoder().decode(parsed.key) }.getOrNull()
        // Round 48 CI fix — the seven-way conjunction tripped
        // ComplexCondition (threshold 4). Same checks, grouped
        // into named pairs so no expression exceeds three terms.
        val versionOk = parsed.v == FORMAT_VERSION
        val idOk = parsed.id.matches(fileIdRegex)
        val keyOk = keyBytes != null && keyBytes.size == KEY_BYTES
        val mimeOk = parsed.mime.isNotBlank()
        val sizeOk = parsed.size >= 0
        val shaOk = parsed.sha256.matches(sha256Regex)
        val headerOk = versionOk && idOk && keyOk
        val payloadOk = mimeOk && sizeOk && shaOk
        return if (headerOk && payloadOk) {
            MediaKey(
                id = parsed.id,
                key = keyBytes,
                mime = parsed.mime,
                sizeBytes = parsed.size,
                sha256Hex = parsed.sha256,
                fileName = parsed.name,
            )
        } else {
            null
        }
    }

    /**
     * The JSON wire shape. Field names are the contract — `v`,
     * `id`, `key`, `mime`, `size`, `sha256`, `name`. `name` has a
     * default so a sender that omits it still decodes (null).
     */
    @Serializable
    private data class MediaKeyJson(
        val v: Int,
        val id: String,
        val key: String,
        val mime: String,
        val size: Long,
        val sha256: String,
        val name: String? = null,
    )
}
