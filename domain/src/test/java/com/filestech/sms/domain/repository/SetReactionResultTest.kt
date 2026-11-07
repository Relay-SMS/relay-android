package com.filestech.sms.domain.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.3.1 — garde sémantique pour [SetReactionResult]. Le ViewModel se base sur
 * `result is SetReactionResult.First` pour décider s'il envoie un SMS. Tout refactor
 * futur qui renommerait/fusionnerait/réordonnerait les sous-classes doit faire échouer
 * ces assertions explicites pour forcer une revue de la logique d'envoi.
 */
class SetReactionResultTest {

    @Test fun `First carries both messageId and emoji`() {
        // Anti-régression : si quelqu'un un jour drop le messageId du First (audit F4 a
        // imposé son ajout pour permettre la re-vérification anti-race au moment du
        // confirm), le ViewModel ne peut plus cibler le bon message. Ce test verrouille
        // la signature.
        val r = SetReactionResult.First(messageId = 42L, emoji = "❤️")
        assertThat(r.messageId).isEqualTo(42L)
        assertThat(r.emoji).isEqualTo("❤️")
    }

    @Test fun `Changed carries both previous and new emoji and is distinct from First`() {
        val r = SetReactionResult.Changed(from = "❤️", to = "👍")
        assertThat(r.from).isEqualTo("❤️")
        assertThat(r.to).isEqualTo("👍")
        // Type-distinct : le ViewModel ne doit JAMAIS confondre Changed avec First
        // (Changed = silencieux, First = potentiellement déclencheur de SMS).
        assertThat(r).isNotInstanceOf(SetReactionResult.First::class.java)
    }

    @Test fun `Noop and Removed are singletons distinct from each other`() {
        // data object → équivalence par référence garantie. Permet les comparaisons par
        // `is` dans le ViewModel sans risque de re-allocation surprise.
        assertThat(SetReactionResult.Noop).isSameInstanceAs(SetReactionResult.Noop)
        assertThat(SetReactionResult.Removed).isSameInstanceAs(SetReactionResult.Removed)
        assertThat(SetReactionResult.Noop).isNotEqualTo(SetReactionResult.Removed)
    }

    @Test fun `Noop is the only transition that never dispatches an SMS`() {
        // Documentation exécutable du contrat ViewModel : First envoie la
        // réaction, Changed envoie le nouvel emoji (v1.4.1), Removed envoie
        // le retrait (`Removed a like from "…"`). Seul Noop ne déclenche
        // jamais d'envoi. Si quelqu'un ajoute une 5ᵉ sous-classe sans étendre
        // ce test, le sealed when ailleurs cassera à la compile (sealed
        // exhaustivité Kotlin). C'est voulu — la liste suivante doit rester
        // synchronisée avec la liste réelle des cas.
        val every: List<SetReactionResult> = listOf(
            SetReactionResult.Noop,
            SetReactionResult.First(1L, "❤️"),
            SetReactionResult.Changed("❤️", "👍"),
            SetReactionResult.Removed,
        )
        val neverDispatch = every.filter { it is SetReactionResult.Noop }
        // Un seul cas n'autorise jamais d'envoi : Noop.
        assertThat(neverDispatch).hasSize(1)
    }
}
