package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Media key message codec tests (round 48, design §3 — the FIXED
 * contract with Worker 1b's receiver): JSON round-trip, validation,
 * and the part-filename mapping. Pure JVM — no Android APIs.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose body returns a value.
 */
class E2eMediaKeyMessageTest {

    private fun sampleKey(name: String? = "photo.jpg") = E2eMediaKeyMessage.MediaKey(
        id = "0123456789abcdef",
        key = ByteArray(32) { it.toByte() },
        mime = "image/jpeg",
        sizeBytes = 123_456L,
        sha256Hex = "a".repeat(64),
        fileName = name,
    )

    @Test
    fun `encode then decode round-trips every field`() {
        val key = sampleKey()

        val decoded = E2eMediaKeyMessage.decode(E2eMediaKeyMessage.encode(key))

        assertThat(decoded).isEqualTo(key)
    }

    @Test
    fun `round-trip keeps a null filename null`() {
        val key = sampleKey(name = null)

        val decoded = E2eMediaKeyMessage.decode(E2eMediaKeyMessage.encode(key))

        assertThat(decoded).isEqualTo(key)
        assertThat(decoded!!.fileName).isNull()
    }

    @Test
    fun `round-trip escapes quotes backslashes and unicode in the filename`() {
        val key = sampleKey(name = "ma \"photo\" \\ été 🎉.jpg")

        val decoded = E2eMediaKeyMessage.decode(E2eMediaKeyMessage.encode(key))

        assertThat(decoded).isEqualTo(key)
    }

    @Test
    fun `the encoded JSON carries the fixed contract fields`() {
        val json = E2eMediaKeyMessage.encode(sampleKey())

        assertThat(json).contains("\"v\":1")
        assertThat(json).contains("\"id\":\"0123456789abcdef\"")
        assertThat(json).contains("\"mime\":\"image/jpeg\"")
        assertThat(json).contains("\"size\":123456")
        assertThat(json).contains("\"sha256\":\"${"a".repeat(64)}\"")
        assertThat(json).contains("\"name\":\"photo.jpg\"")
        assertThat(json).contains("\"key\":\"${Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })}\"")
    }

    @Test
    fun `decode rejects malformed JSON`() {
        assertThat(E2eMediaKeyMessage.decode("{not json")).isNull()
        assertThat(E2eMediaKeyMessage.decode("")).isNull()
        assertThat(E2eMediaKeyMessage.decode("[]")).isNull()
    }

    @Test
    fun `decode rejects a wrong version`() {
        val json = E2eMediaKeyMessage.encode(sampleKey()).replace("\"v\":1", "\"v\":2")

        assertThat(E2eMediaKeyMessage.decode(json)).isNull()
    }

    @Test
    fun `decode rejects a malformed file id`() {
        val short = E2eMediaKeyMessage.encode(sampleKey()).replace("0123456789abcdef", "0123")
        val upper = E2eMediaKeyMessage.encode(sampleKey()).replace("0123456789abcdef", "0123456789ABCDEF")

        assertThat(E2eMediaKeyMessage.decode(short)).isNull()
        assertThat(E2eMediaKeyMessage.decode(upper)).isNull()
    }

    @Test
    fun `decode rejects a key that is not 32 bytes`() {
        val shortKey = E2eMediaKeyMessage.encode(sampleKey()).replace(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            Base64.getEncoder().encodeToString(ByteArray(16)),
        )
        val badBase64 = E2eMediaKeyMessage.encode(sampleKey()).replace(
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            "!!!not-base64!!!",
        )

        assertThat(E2eMediaKeyMessage.decode(shortKey)).isNull()
        assertThat(E2eMediaKeyMessage.decode(badBase64)).isNull()
    }

    @Test
    fun `decode rejects a blank mime a negative size and a bad sha256`() {
        val blankMime = E2eMediaKeyMessage.encode(sampleKey()).replace("\"image/jpeg\"", "\"  \"")
        val negativeSize = E2eMediaKeyMessage.encode(sampleKey()).replace("\"size\":123456", "\"size\":-1")
        val badSha = E2eMediaKeyMessage.encode(sampleKey()).replace("a".repeat(64), "zz".repeat(32))

        assertThat(E2eMediaKeyMessage.decode(blankMime)).isNull()
        assertThat(E2eMediaKeyMessage.decode(negativeSize)).isNull()
        assertThat(E2eMediaKeyMessage.decode(badSha)).isNull()
    }

    @Test
    fun `decode ignores unknown fields for forward compatibility`() {
        val json = E2eMediaKeyMessage.encode(sampleKey())
            .replaceFirst("{", "{\"future\":\"ignored\",")

        assertThat(E2eMediaKeyMessage.decode(json)).isEqualTo(sampleKey())
    }

    @Test
    fun `generateFileId yields 16 lowercase hex chars and is unique`() {
        val ids = (1..100).map { E2eMediaKeyMessage.generateFileId() }

        assertThat(ids).containsNoDuplicates()
        for (id in ids) {
            assertThat(id).matches("[0-9a-f]{16}")
        }
    }

    @Test
    fun `generateKey yields 32 random bytes`() {
        val key = E2eMediaKeyMessage.generateKey()

        assertThat(key.size).isEqualTo(32)
        assertThat(key).isNotEqualTo(ByteArray(32))
    }

    @Test
    fun `partFileName and fileIdFromPartName round-trip`() {
        val name = E2eMediaKeyMessage.partFileName("0123456789abcdef")

        assertThat(name).isEqualTo("relay-media-0123456789abcdef.bin")
        assertThat(E2eMediaKeyMessage.fileIdFromPartName(name)).isEqualTo("0123456789abcdef")
    }

    @Test
    fun `fileIdFromPartName rejects plain attachment names`() {
        assertThat(E2eMediaKeyMessage.fileIdFromPartName("photo.jpg")).isNull()
        assertThat(E2eMediaKeyMessage.fileIdFromPartName("relay-media-0123.bin")).isNull()
        assertThat(E2eMediaKeyMessage.fileIdFromPartName("relay-media-0123456789ABCDEF.bin")).isNull()
        assertThat(E2eMediaKeyMessage.fileIdFromPartName("relay-media-0123456789abcdef.jpg")).isNull()
    }

    @Test
    fun `toString never prints the key`() {
        val text = sampleKey().toString()

        assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
        assertThat(text).contains("0123456789abcdef")
    }
}
