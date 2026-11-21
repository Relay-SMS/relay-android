package com.filestech.sms.data.local.db

import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.mapper.toDomain
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * E2E (schema v22) — the `encrypted` / `decrypt_failed` / `ciphertext` columns on
 * `messages`: entity defaults and the mapper threading.
 *
 * The flags flow to the domain [com.filestech.sms.domain.model.Message] (the thread
 * renders the lock and the undecryptable placeholder from the domain model, like
 * `reactionEmoji`); `ciphertext` stays on the entity — it never reaches the UI or
 * the sync layer, and the retry path reads it via `MessageDao.findDecryptFailed`.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * body returns a value.
 */
class MessageE2eFlagsTest {

    private fun message(
        encrypted: Boolean = false,
        decryptFailed: Boolean = false,
        ciphertext: String? = null,
    ) = MessageEntity(
        conversationId = 1L,
        telephonyUri = null,
        address = "+33612345678",
        body = "salut",
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = 1_700_000_000_000L,
        dateSent = null,
        status = MessageStatus.RECEIVED,
        encrypted = encrypted,
        decryptFailed = decryptFailed,
        ciphertext = ciphertext,
    )

    @Test
    fun `new message rows default to plain, decryptable, no ciphertext`() {
        val entity = message()

        assertThat(entity.encrypted).isFalse()
        assertThat(entity.decryptFailed).isFalse()
        assertThat(entity.ciphertext).isNull()
    }

    @Test
    fun `encrypted flag threads through to the domain model`() {
        val domain = message(encrypted = true).toDomain()

        assertThat(domain.encrypted).isTrue()
        assertThat(domain.decryptFailed).isFalse()
    }

    @Test
    fun `decrypt-failed flag threads through to the domain model`() {
        val domain = message(encrypted = true, decryptFailed = true, ciphertext = "UkxZMQ==")
            .toDomain()

        assertThat(domain.encrypted).isTrue()
        assertThat(domain.decryptFailed).isTrue()
    }

    @Test
    fun `plain messages keep both flags false in the domain model`() {
        val domain = message().toDomain()

        assertThat(domain.encrypted).isFalse()
        assertThat(domain.decryptFailed).isFalse()
    }
}
