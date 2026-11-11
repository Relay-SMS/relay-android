package com.filestech.sms.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 — **une seule règle de titre**, `nom choisi > nom résolu > numéros`. Avant, six sites
 * recalculaient `displayName ?: numéros` chacun de leur côté (liste, fil, PDF, dialogue de
 * réaction…) : un nom choisi aurait dû être ajouté six fois.
 */
class ConversationTitleTest {

    private fun conv(customName: String?, displayName: String?, vararg adresses: String) = Conversation(
        id = 1L,
        threadId = null,
        addresses = adresses.map { PhoneAddress.of(it) },
        displayName = displayName,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
        customName = customName,
    )

    @Test
    fun `le nom choisi prime sur le nom resolu`() {
        assertThat(conv("Famille", "Pat, 0607231541", "0617332729", "0607231541").title).isEqualTo("Famille")
    }

    @Test
    fun `sans nom choisi le nom resolu puis les numeros`() {
        assertThat(conv(null, "Pat", "0617332729").title).isEqualTo("Pat")
        assertThat(conv(null, null, "0617332729", "0607231541").title).isEqualTo("0617332729, 0607231541")
        assertThat(conv("  ", "", "0617332729").title).isEqualTo("0617332729")
    }

    @Test
    fun `les numeros nord-americains sont formates dans le titre`() {
        // 1:1 sans contact : le numero brut est affiche formate.
        assertThat(conv(null, null, "9787984094").title).isEqualTo("(978) 798-4094")
        assertThat(conv(null, null, "19787984094").title).isEqualTo("1 (978) 798-4094")
        assertThat(conv(null, null, "+19787984094").title).isEqualTo("+1 (978) 798-4094")
        // Groupe : chaque numero est formate a part.
        assertThat(conv(null, null, "9787984094", "4847069999").title)
            .isEqualTo("(978) 798-4094, (484) 706-9999")
        // Nom affiche qui est un numero : formate aussi.
        assertThat(conv(null, "9787984094", "9787984094").title).isEqualTo("(978) 798-4094")
        // Un numero non nord-americain et un nom restent tels quels.
        assertThat(conv(null, null, "0617332729").title).isEqualTo("0617332729")
        assertThat(conv(null, "Pat", "0617332729").title).isEqualTo("Pat")
        // Le nom choisi n'est jamais reformate.
        assertThat(conv("9787984094", null, "9787984094").title).isEqualTo("9787984094")
    }
}
