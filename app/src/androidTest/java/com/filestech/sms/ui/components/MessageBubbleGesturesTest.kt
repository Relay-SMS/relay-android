package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Feedback round 6 — l'appui long sur une bulle **ouvre le même menu que le tap**
 * (épingler, réagir, copier, …). L'ancienne entrée « Sélectionner le texte » a été
 * supprimée : ni le tap ni l'appui long ne l'ouvrent plus. Ces tests vérifient que les
 * deux gestes ouvrent bien le menu, y compris sur un écran étroit de 320 dp.
 */
class MessageBubbleGesturesTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val BODY = "Rendez-vous demain à 9 h devant la gare."
        const val LONG_BODY =
            "Rendez-vous demain a 9 h devant la gare, prends ton billet a l'avance parce que le guichet " +
                "ferme tot et qu'il y aura du monde avec les vacances scolaires qui commencent samedi."

        fun message(body: String = BODY, direction: Message.Direction = Message.Direction.INCOMING) = Message(
            id = 1L,
            conversationId = 1L,
            address = "+33612345678",
            body = body,
            type = Message.Type.SMS,
            direction = direction,
            date = 1_700_000_000_000L,
            dateSent = null,
            read = true,
            starred = false,
            status = Message.Status.RECEIVED,
            errorCode = null,
            attachmentsCount = 0,
            subId = null,
            scheduledAt = null,
        )
    }

    @Test
    fun lAppuiLongOuvreLeMenuCommeLeTap() {
        var copies = 0
        compose.setContent {
            MessageBubble(
                message = message(),
                showTimestamp = false,
                onCopy = { copies++ },
            )
        }

        compose.onNodeWithText(BODY).performTouchInput { longClick(center) }
        compose.onNodeWithText(context.getString(R.string.action_copy)).performClick()
        compose.waitForIdle()

        assertThat(copies).isEqualTo(1)
    }

    /**
     * Relay (2026-11-06) — **le bouton ⋮ a disparu : c'est le tap sur la bulle qui
     * ouvre le menu.** L'ancien test v1.28.7 vérifiait que le bouton gardait 40 dp sur un
     * écran étroit ; ce bouton n'existe plus. Le menu doit donc s'ouvrir par tap sur la
     * bulle, y compris sur un écran étroit de 320 dp, pour les messages entrants comme
     * sortants.
     */
    @Test
    fun surUnEcranEtroit_leTapSurLaBulleOuvreLeMenu_messageEntrant() =
        tapSurEcranEtroit(Message.Direction.INCOMING)

    @Test
    fun surUnEcranEtroit_leTapSurLaBulleOuvreLeMenu_messageSortant() =
        tapSurEcranEtroit(Message.Direction.OUTGOING)

    private fun tapSurEcranEtroit(direction: Message.Direction) {
        var copies = 0
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                MessageBubble(
                    message = message(LONG_BODY, direction),
                    showTimestamp = false,
                    onCopy = { copies++ },
                )
            }
        }

        compose.onNodeWithText(LONG_BODY).performClick()
        compose.onNodeWithText(context.getString(R.string.action_copy)).performClick()
        compose.waitForIdle()

        assertThat(copies).isEqualTo(1)
    }

    @Test
    fun leMenuProposeCopierLeTexte() {
        var copies = 0
        compose.setContent {
            MessageBubble(
                message = message(),
                showTimestamp = false,
                onCopy = { copies++ },
            )
        }

        compose.onNodeWithText(BODY).performClick()
        compose.onNodeWithText(context.getString(R.string.action_copy)).performClick()
        compose.waitForIdle()

        assertThat(copies).isEqualTo(1)
    }
}
