package com.filestech.sms.e2e

import com.filestech.sms.e2e.E2eWireFormat.Fragment
import com.filestech.sms.e2e.E2eWireFormat.PayloadType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Wire-format tests (design §3.2 / §10.1): fragment encode/decode,
 * header parsing, invalid-input rejection, the 8,000-char cap, and the
 * 99-fragment ceiling. Pure JVM — no crypto, no Android APIs.
 */
class E2eWireFormatTest {

    @Test
    fun `encode produces the exact expected fragment for a small payload`() {
        val message = EncryptedMessage(
            OlmMessageType.PRE_KEY,
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        )
        assertThat(E2eWireFormat.encode("abc123", message, PayloadType.TEXT))
            .containsExactly("RLY1:abc123:01/01:AQEB3q2+7w==")
    }

    @Test
    fun `encode then parse assemble parsePayload round-trips a small message`() {
        val message = EncryptedMessage(OlmMessageType.NORMAL, "ciphertext-bytes".toByteArray())
        val fragments = E2eWireFormat.encode("00ff99", message, PayloadType.REACTION)
            .map { E2eWireFormat.parseFragment(it) }
        assertThat(fragments).hasSize(1)
        val decoded = E2eWireFormat.parsePayload(E2eWireFormat.assemble(fragments.filterNotNull()))
        assertThat(decoded.message).isEqualTo(message)
        assertThat(decoded.payloadType).isEqualTo(PayloadType.REACTION)
    }

    @Test
    fun `a 300-byte message splits into 3 fragments and reassembles out of order`() {
        val message = EncryptedMessage(OlmMessageType.PRE_KEY, ByteArray(300) { it.toByte() })
        val fragments = E2eWireFormat.encode("123456", message, PayloadType.TEXT)
        assertThat(fragments).hasSize(3)
        assertThat(fragments[0]).startsWith("RLY1:123456:01/03:")
        assertThat(fragments[1]).startsWith("RLY1:123456:02/03:")
        assertThat(fragments[2]).startsWith("RLY1:123456:03/03:")
        assertThat(fragments[0].length).isEqualTo(160)
        assertThat(fragments[1].length).isEqualTo(160)
        val parsed = fragments.map { E2eWireFormat.parseFragment(it)!! }
        val decoded = E2eWireFormat.parsePayload(E2eWireFormat.assemble(parsed.reversed()))
        assertThat(decoded.message).isEqualTo(message)
    }

    @Test
    fun `generateMessageId returns 6 lowercase hex chars`() {
        val id = E2eWireFormat.generateMessageId()
        assertThat(id.length).isEqualTo(6)
        assertThat(id).matches("[0-9a-f]{6}")
        assertThat(E2eWireFormat.generateMessageId()).isNotEqualTo(id)
    }

    @Test
    fun `the plaintext cap is 8000 characters`() {
        assertThat(E2eWireFormat.MAX_PLAINTEXT_CHARS).isEqualTo(8_000)
        assertThat(E2eWireFormat.plaintextExceedsCap("x".repeat(8_000))).isFalse()
        assertThat(E2eWireFormat.plaintextExceedsCap("x".repeat(8_001))).isTrue()
    }

    @Test
    fun `encode rejects a malformed message id`() {
        val message = EncryptedMessage(OlmMessageType.NORMAL, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.encode("abc12", message, PayloadType.TEXT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.encode("abc12g", message, PayloadType.TEXT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.encode("abc1234", message, PayloadType.TEXT)
        }
    }

    @Test
    fun `encode rejects a payload needing more than 99 fragments`() {
        val tooBig = EncryptedMessage(OlmMessageType.NORMAL, ByteArray(10_540))
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.encode("abcdef", tooBig, PayloadType.TEXT)
        }
    }

    @Test
    fun `a payload needing exactly 99 fragments encodes fine`() {
        val maxed = EncryptedMessage(OlmMessageType.NORMAL, ByteArray(10_539))
        assertThat(E2eWireFormat.encode("abcdef", maxed, PayloadType.TEXT)).hasSize(99)
    }

    @Test
    fun `isFragment accepts real fragments and rejects plain text`() {
        assertThat(E2eWireFormat.isFragment("RLY1:abc123:01/01:AQEB3q2+7w==")).isTrue()
        assertThat(E2eWireFormat.isFragment("hello, how are you?")).isFalse()
        assertThat(E2eWireFormat.isFragment("")).isFalse()
        assertThat(E2eWireFormat.isFragment("RLY1:")).isFalse()
    }

    @Test
    fun `parseFragment rejects bad magic`() {
        assertThat(E2eWireFormat.parseFragment("RLY2:abc123:01/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("rly1:abc123:01/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment(" RLY1:abc123:01/01:AQEB3q2+7w==")).isNull()
    }

    @Test
    fun `parseFragment rejects bad message ids`() {
        assertThat(E2eWireFormat.parseFragment("RLY1:abc12:01/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc12g:01/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc1234:01/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1::01/01:AQEB3q2+7w==")).isNull()
    }

    @Test
    fun `parseFragment rejects bad indices`() {
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:00/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/00:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:02/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:1/01:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/1:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/100:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:ab/cd:AQEB3q2+7w==")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01-01:AQEB3q2+7w==")).isNull()
    }

    @Test
    fun `parseFragment rejects bad chunks`() {
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/01:")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/01:AQEB!")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/01:AQEB 3q2+")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/01:${"A".repeat(143)}")).isNull()
        assertThat(E2eWireFormat.parseFragment("RLY1:abc123:01/01:AQEB:extra")).isNull()
    }

    @Test
    fun `parseFragment accepts uppercase hex message ids`() {
        val fragment = E2eWireFormat.parseFragment("RLY1:ABCDEF:01/01:AQEB3q2+7w==")
        assertThat(fragment).isNotNull()
        assertThat(fragment!!.messageId).isEqualTo("ABCDEF")
    }

    @Test
    fun `assemble rejects an empty set`() {
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(emptyList())
        }
    }

    @Test
    fun `assemble rejects mixed message ids and mixed totals`() {
        val a = Fragment("aaaaaa", 1, 2, "A".repeat(142))
        val b = Fragment("bbbbbb", 2, 2, "AQID")
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(listOf(a, b))
        }
        val c = Fragment("aaaaaa", 2, 3, "AQID")
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(listOf(a, c))
        }
    }

    @Test
    fun `assemble rejects missing and duplicate indices`() {
        val first = Fragment("aaaaaa", 1, 3, "A".repeat(142))
        val third = Fragment("aaaaaa", 3, 3, "AQID")
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(listOf(first, third))
        }
        val firstOfTwo = Fragment("aaaaaa", 1, 2, "A".repeat(142))
        val duplicateOfFirst = Fragment("aaaaaa", 1, 2, "B".repeat(142))
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(listOf(firstOfTwo, duplicateOfFirst))
        }
    }

    @Test
    fun `assemble rejects a short non-final chunk`() {
        val short = Fragment("aaaaaa", 1, 2, "AQID")
        val last = Fragment("aaaaaa", 2, 2, "AQID")
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(listOf(short, last))
        }
    }

    @Test
    fun `assemble rejects invalid base64`() {
        // "A" is a valid base64 char, but a 1-char base64 string is malformed.
        val bad = Fragment("aaaaaa", 1, 1, "A")
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.assemble(listOf(bad))
        }
    }

    @Test
    fun `parsePayload rejects bad versions types and short payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.parsePayload(byteArrayOf(0x02, 0x01, 0x01, 0x00))
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.parsePayload(byteArrayOf(0x01, 0x03, 0x01, 0x00))
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.parsePayload(byteArrayOf(0x01, 0x01, 0x03, 0x00))
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.parsePayload(byteArrayOf(0x01, 0x01, 0x01))
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eWireFormat.parsePayload(byteArrayOf())
        }
    }

    @Test
    fun `encodePayload and parsePayload round-trip both message types`() {
        val preKey = EncryptedMessage(OlmMessageType.PRE_KEY, byteArrayOf(9, 8, 7))
        val decodedPreKey = E2eWireFormat.parsePayload(
            E2eWireFormat.encodePayload(preKey, PayloadType.TEXT),
        )
        assertThat(decodedPreKey.message).isEqualTo(preKey)
        assertThat(decodedPreKey.payloadType).isEqualTo(PayloadType.TEXT)
        val normal = EncryptedMessage(OlmMessageType.NORMAL, byteArrayOf(1, 2, 3, 4))
        val decodedNormal = E2eWireFormat.parsePayload(
            E2eWireFormat.encodePayload(normal, PayloadType.REACTION),
        )
        assertThat(decodedNormal.message).isEqualTo(normal)
        assertThat(decodedNormal.payloadType).isEqualTo(PayloadType.REACTION)
    }
}
