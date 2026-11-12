package com.filestech.sms.data.mms

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.FichiersDePiecesJointes
import com.filestech.sms.data.repository.IncomingAttachment
import com.filestech.sms.data.sms.SystemThreadResolver
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Round 16 — the Qik-shaped group routing: a received group MMS is written
 * into the system provider first, and the thread Android assigned to that
 * row is what the mirror routes on (with the provider URI attached, so the
 * later bulk import recognises it and never duplicates it).
 *
 * What is held here is the DECISION — which thread id and which URI reach
 * the mirror in each case. The provider write itself is mocked: it needs a
 * real provider and the default-SMS role, neither of which a unit test has.
 */
class IntegrationMmsRecuProviderTest {

    @TempDir
    lateinit var racine: File

    private val mirror = mockk<ConversationMirror>()
    private val messages = mockk<MessageDao>()
    private val attachments = mockk<AttachmentDao>(relaxed = true)
    private val fichiers = mockk<FichiersDePiecesJointes>(relaxed = true)
    private val resolver = mockk<SystemThreadResolver>()
    private val writeback = mockk<MmsSystemWriteback>()

    private val ecrivain = IntegrationMmsRecu.EcrivainDePieces { partie ->
        IncomingAttachment(File(racine, "part.bin"), partie.mime)
    }

    private fun integration() = IntegrationMmsRecu(
        mirror,
        attachments,
        messages,
        fichiers,
        ecrivain,
        resolver,
        writeback,
    )

    private fun contenu() = ContenuMmsRecu(
        expediteur = ALICE,
        date = DATE_MS,
        sujet = null,
        legende = "hello",
        parties = listOf(PartieMms(byteArrayOf(1, 2, 3), "image/jpeg")),
        destinataires = listOf(BOB, CAROL),
        copies = emptyList(),
    )

    private fun membres() = listOf(
        PhoneAddress.of(ALICE),
        PhoneAddress.of(BOB),
        PhoneAddress.of(CAROL),
    )

    private fun preparerInscription() {
        coEvery { messages.findIdByTransactionKey(any()) } returns null
        coEvery {
            mirror.inscrireMmsRecu(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ConversationMirror.InscriptionMmsRecu(MESSAGE_ID, nouveau = true)
    }

    @Test
    fun `le fil systeme et l'URI fournisseur atteignent le miroir quand l'ecriture reussit`() = runTest {
        preparerInscription()
        coEvery {
            writeback.insertInbox(any(), any(), any(), any(), any(), any(), any())
        } returns MmsSystemWriteback.InboxWrite(MMS_ID, THREAD_ID)

        val resultat = integration().integrer(contenu(), SUB_ID, membres(), inclutSoi = false, CLE) { true }

        assertThat(resultat).isEqualTo(IntegrationMmsRecu.Resultat.Ecrit(MESSAGE_ID))
        // The provider row's thread wins outright — no separate resolution.
        verify(exactly = 0) { resolver.resolve(any()) }
        coVerify {
            writeback.insertInbox(
                ALICE,
                listOf(BOB, CAROL),
                emptyList(),
                listOf(ALICE, BOB, CAROL),
                any(),
                "hello",
                DATE_MS,
            )
        }
        coVerify {
            mirror.inscrireMmsRecu(
                any(),
                any(),
                any(),
                any(),
                any(),
                "content://mms/$MMS_ID",
                any(),
                any(),
                any(),
                any(),
                any(),
                THREAD_ID,
            )
        }
    }

    @Test
    fun `l'ecriture fournisseur refusee retombe sur la resolution de fil, sans URI`() = runTest {
        preparerInscription()
        coEvery {
            writeback.insertInbox(any(), any(), any(), any(), any(), any(), any())
        } returns null
        every { resolver.resolve(any()) } returns FALLBACK_THREAD_ID

        val resultat = integration().integrer(contenu(), SUB_ID, membres(), inclutSoi = false, CLE) { true }

        assertThat(resultat).isEqualTo(IntegrationMmsRecu.Resultat.Ecrit(MESSAGE_ID))
        verify { resolver.resolve(listOf(ALICE, BOB, CAROL)) }
        coVerify {
            mirror.inscrireMmsRecu(
                any(),
                any(),
                any(),
                any(),
                any(),
                null,
                any(),
                any(),
                any(),
                any(),
                any(),
                FALLBACK_THREAD_ID,
            )
        }
    }

    @Test
    fun `un MMS 1-1 n'ecrit rien dans le fournisseur et ne resout aucun fil`() = runTest {
        preparerInscription()

        val resultat = integration().integrer(contenu(), SUB_ID, null, inclutSoi = false, CLE) { true }

        assertThat(resultat).isEqualTo(IntegrationMmsRecu.Resultat.Ecrit(MESSAGE_ID))
        coVerify(exactly = 0) {
            writeback.insertInbox(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { resolver.resolve(any()) }
        coVerify {
            mirror.inscrireMmsRecu(
                any(),
                any(),
                any(),
                any(),
                any(),
                null,
                any(),
                any(),
                any(),
                any(),
                any(),
                0L,
            )
        }
    }

    private companion object {
        const val ALICE = "+15550001111"
        const val BOB = "+15550002222"
        const val CAROL = "+15550003333"
        const val DATE_MS = 1_700_000_000_000L
        const val SUB_ID = 1
        const val CLE = "TX-1"
        const val MESSAGE_ID = 99L
        const val MMS_ID = 42L
        const val THREAD_ID = 7L
        const val FALLBACK_THREAD_ID = 55L
    }
}
