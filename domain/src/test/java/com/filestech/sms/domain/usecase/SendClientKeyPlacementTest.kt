package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Slice 3 — **la clé d'idempotence ne va que sur UNE ligne par envoi.**
 *
 * La colonne `messages.client_message_id` porte un index UNIQUE : deux
 * lignes avec la même clé feraient échouer l'insertion (IGNORE → id
 * -1). La règle est donc : la ligne 1-à-1 pour un envoi simple, l'écho
 * de groupe pour un envoi depuis un groupe — jamais les deux, jamais
 * plusieurs lignes 1-à-1.
 *
 * Le rapport porte aussi l'id de l'écho ([SendReport.groupEchoId]) :
 * c'est LA ligne que l'appareil lié affiche pour un envoi de groupe.
 */
class SendClientKeyPlacementTest {

    private val pat = PhoneAddress.of("0617332729")
    private val moi = PhoneAddress.of("0607231541")

    @Test
    fun `un envoi simple porte la cle sur sa seule ligne`() = runBlocking {
        val mirror = NoopMirror()
        val issue = sendSmsUseCase(DefaultSettingsSource(), RecordingSender(), mirror = mirror)
            .invoke(listOf(pat), "Bonjour", clientMessageId = CLE)

        assertThat(issue).isInstanceOf(Outcome.Success::class.java)
        assertThat(mirror.cles).containsExactly(CLE)
        assertThat(mirror.clesEcho).isEmpty()
        val rapport = (issue as Outcome.Success).value
        assertThat(rapport.groupEchoId).isNull()
    }

    @Test
    fun `un envoi depuis un groupe porte la cle sur l'echo seulement`() {
        // Corps en bloc, pas `= runBlocking` : le dernier `containsExactly` rend
        // un `Ordered`, et JUnit ignore les méthodes de test au retour non-void.
        runBlocking {
            val mirror = NoopMirror()
            val issue = sendSmsUseCase(DefaultSettingsSource(), RecordingSender(), mirror = mirror)
                .invoke(listOf(pat, moi), "Test groupe", echoInGroup = true, clientMessageId = CLE)

            assertThat(issue).isInstanceOf(Outcome.Success::class.java)
            // Les lignes 1-à-1 ne portent PAS la clé — seul l'écho la porte.
            assertThat(mirror.cles).containsExactly(null, null)
            assertThat(mirror.clesEcho).containsExactly(CLE)
            // Et le rapport dit quelle ligne l'écho est (id 3 : deux lignes + l'écho).
            val rapport = (issue as Outcome.Success).value
            assertThat(rapport.groupEchoId).isEqualTo(3L)
            assertThat(rapport.dispatched).containsExactly(1L, 2L)
        }
    }

    @Test
    fun `sans cle, rien ne change et l'echo est quand meme rapporte`() = runBlocking {
        val mirror = NoopMirror()
        val issue = sendSmsUseCase(DefaultSettingsSource(), RecordingSender(), mirror = mirror)
            .invoke(listOf(pat, moi), "Test groupe", echoInGroup = true)

        assertThat(issue).isInstanceOf(Outcome.Success::class.java)
        assertThat(mirror.cles).containsExactly(null, null)
        assertThat(mirror.clesEcho).containsExactly(null)
        assertThat((issue as Outcome.Success).value.groupEchoId).isEqualTo(3L)
    }

    companion object {
        private const val CLE = "3f2a1b00-0000-4000-8000-00000000c41d"
    }
}
