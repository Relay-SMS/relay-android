package com.filestech.sms.sync.client

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Client mode — base64url without padding: the wire encoding for
 * keys, challenges and signatures. Round-trips exactly, never emits
 * `+`, `/` or `=`, and rejects garbage instead of throwing.
 */
class Base64UrlTest {

    @Test fun `encode has no padding and no url unsafe characters`() {
        val encoded = Base64Url.encode(ByteArray(32) { 0xFF.toByte() })
        assertThat(encoded).doesNotContain("=")
        assertThat(encoded).doesNotContain("+")
        assertThat(encoded).doesNotContain("/")
        assertThat(encoded.length).isEqualTo(43)
    }

    @Test fun `encode and decode round-trip`() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        assertThat(Base64Url.decode(Base64Url.encode(bytes))).isEqualTo(bytes)
    }

    @Test fun `empty bytes encode to an empty string`() {
        assertThat(Base64Url.encode(ByteArray(0))).isEmpty()
        assertThat(Base64Url.decode("")).isEqualTo(ByteArray(0))
    }

    @Test fun `decode rejects invalid input`() {
        assertThat(Base64Url.decode("not base64!")).isNull()
        assertThat(Base64Url.decode("a")).isNull()
    }
}
