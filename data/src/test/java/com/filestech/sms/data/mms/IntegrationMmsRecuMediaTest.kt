package com.filestech.sms.data.mms

import android.content.Context
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.FichiersDePiecesJointes
import com.filestech.sms.data.repository.IncomingAttachment
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Round 48 — the encrypted-media receive path: the writer
 * preserves ONLY the relay-media filename (the key lookup), and
 * `integrer` flags the message encrypted only for a 1:1 MMS
 * carrying a ciphertext part (the part SHAPE is the signal —
 * groups stay plain, plain MMS is untouched).
 */
class IntegrationMmsRecuMediaTest {

    @TempDir
    lateinit var racine: File

    private val mirror = mockk<ConversationMirror>()
    private val messages = mockk<MessageDao>()
    private val attachments = mockk<AttachmentDao>(relaxed = true)
    private val fichiers = mockk<FichiersDePiecesJointes>(relaxed = true)

    private val ecrivain = IntegrationMmsRecu.EcrivainDePieces { partie ->
        IncomingAttachment(File(racine, "part.bin"), partie.mime)
    }

    // No resolver / writeback: 1:1 tests never touch them, and
    // the group test falls back to thread 0 — the mirror call is
    // what these tests assert on.
    private fun integration() = IntegrationMmsRecu(
        mirror,
        attachments,
        messages,
        fichiers,
        ecrivain,
    )

    private fun preparerInscription() {
        coEvery { messages.findIdByTransactionKey(any()) } returns null
        coEvery {
            mirror.inscrireMmsRecu(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ConversationMirror.InscriptionMmsRecu(MESSAGE_ID, nouveau = true)
    }

    private fun contenu(parties: List<PartieMms>) = ContenuMmsRecu(
        expediteur = ALICE,
        date = DATE_MS,
        sujet = null,
        legende = null,
        parties = parties,
        destinataires = listOf(BOB),
        copies = emptyList(),
    )

    private fun relayPart() = PartieMms(
        byteArrayOf(1, 2, 3),
        "application/octet-stream",
        "relay-media-0123456789abcdef.bin",
    )

    // ---- The writer: filename preservation ----

    @Test
    fun `a relay-media part keeps its PDU name on disk`() {
        val context = mockk<Context>()
        every { context.filesDir } returns racine
        val writer = EcrivainDePiecesSurDisque(context)

        val written = writer.ecrire(relayPart())

        assertThat(written).isNotNull()
        assertThat(written!!.file.name).isEqualTo("relay-media-0123456789abcdef.bin")
        assertThat(written.file.exists()).isTrue()
        assertThat(written.file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a plain part keeps its generated name, not the PDU name`() {
        val context = mockk<Context>()
        every { context.filesDir } returns racine
        val writer = EcrivainDePiecesSurDisque(context)
        val plain = PartieMms(byteArrayOf(1, 2, 3), "image/jpeg", "photo.jpg")

        val written = writer.ecrire(plain)

        assertThat(written).isNotNull()
        assertThat(written!!.file.name).startsWith("in-")
        assertThat(written.file.name).endsWith(".jpg")
        assertThat(written.file.name).isNotEqualTo("photo.jpg")
    }

    @Test
    fun `the writer preserves by name alone, whatever the mime`() {
        // The writer only parses the name; the MIME gate lives in
        // the encrypted flag (below), not here. A relay-named
        // part with a re-typed MIME keeps its name but the
        // message is not flagged encrypted.
        val context = mockk<Context>()
        every { context.filesDir } returns racine
        val writer = EcrivainDePiecesSurDisque(context)
        val mistyped = PartieMms(
            byteArrayOf(1, 2, 3),
            "image/jpeg",
            "relay-media-0123456789abcdef.bin",
        )

        val written = writer.ecrire(mistyped)

        assertThat(written!!.file.name).isEqualTo("relay-media-0123456789abcdef.bin")
    }

    // ---- integrer: the encrypted flag ----

    @Test
    fun `a 1-1 relay-media MMS reaches the mirror encrypted`() = runTest {
        preparerInscription()

        val resultat = integration().integrer(contenu(listOf(relayPart())), SUB_ID, null, inclutSoi = false, CLE) { true }

        assertThat(resultat).isEqualTo(IntegrationMmsRecu.Resultat.Ecrit(MESSAGE_ID))
        coVerify {
            mirror.inscrireMmsRecu(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), true)
        }
    }

    @Test
    fun `a group MMS with a relay-media part stays plain`() = runTest {
        preparerInscription()
        val membres = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB), PhoneAddress.of(CAROL))

        val resultat = integration().integrer(contenu(listOf(relayPart())), SUB_ID, membres, inclutSoi = false, CLE) { true }

        assertThat(resultat).isEqualTo(IntegrationMmsRecu.Resultat.Ecrit(MESSAGE_ID))
        coVerify {
            mirror.inscrireMmsRecu(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), false)
        }
    }

    @Test
    fun `a plain 1-1 MMS reaches the mirror unencrypted`() = runTest {
        preparerInscription()
        val plain = PartieMms(byteArrayOf(1, 2, 3), "image/jpeg", "photo.jpg")

        val resultat = integration().integrer(contenu(listOf(plain)), SUB_ID, null, inclutSoi = false, CLE) { true }

        assertThat(resultat).isEqualTo(IntegrationMmsRecu.Resultat.Ecrit(MESSAGE_ID))
        coVerify {
            mirror.inscrireMmsRecu(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), false)
        }
    }

    @Test
    fun `a relay-named part with a re-typed mime does not flag the message`() = runTest {
        preparerInscription()
        val mistyped = PartieMms(
            byteArrayOf(1, 2, 3),
            "image/jpeg",
            "relay-media-0123456789abcdef.bin",
        )

        integration().integrer(contenu(listOf(mistyped)), SUB_ID, null, inclutSoi = false, CLE) { true }

        coVerify {
            mirror.inscrireMmsRecu(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), false)
        }
    }

    private companion object {
        const val ALICE = "+15550001111"
        const val BOB = "+15550002222"
        const val CAROL = "+15550003333"
        const val MESSAGE_ID = 42L
        const val DATE_MS = 1_700_000_000_000L
        const val SUB_ID = 3
        const val CLE = "transaction-key"
    }
}
