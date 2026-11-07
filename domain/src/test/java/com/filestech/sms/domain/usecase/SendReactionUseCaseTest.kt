package com.filestech.sms.domain.usecase

import com.filestech.sms.domain.reaction.IncomingReactionDecoder
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.3.2 — vérifie que [buildTapbackBody] produit le format Apple/Google Tapback
 * EXACT attendu par les parseurs iMessage et Google Messages récents. Tout
 * changement de wording (`Reacted`, ` to `, `«»`) casserait la détection automatique
 * côté iPhone : ces tests sont une **garde de format** anti-régression.
 *
 * `buildTapbackBody` est top-level dans le module domain (testable sans mocks).
 */
class SendReactionUseCaseTest {

    @Test fun `short body produces standard tapback`() {
        // Format strict — toute déviation casserait le parsing Apple/Google.
        assertThat(buildTapbackBody("❤️", "Salut on se voit demain ?"))
            .isEqualTo("Reacted ❤️ to «Salut on se voit demain ?»")
    }

    @Test fun `empty body falls back to short tapback without quotes`() {
        // Cas MMS image pure ou message sans texte (pièce jointe seule).
        assertThat(buildTapbackBody("👍", "")).isEqualTo("Reacted 👍")
    }

    @Test fun `whitespace-only body falls back to short form`() {
        // Pas de contexte exploitable, fallback compact.
        assertThat(buildTapbackBody("🎉", " \n\t  ")).isEqualTo("Reacted 🎉")
    }

    @Test fun `long body is truncated at 50 chars with ellipsis`() {
        val long = "Salut, on se voit demain à 15h pour le café avec Jeanne et Pierre"
        val out = buildTapbackBody("🔥", long)
        // Structure préservée : prefix + emoji + " to «" + preview + "…»"
        assertThat(out).startsWith("Reacted 🔥 to «")
        assertThat(out).endsWith("…»")
        // L'aperçu lui-même (entre « et …») n'excède jamais 50 chars.
        val preview = out.substringAfter("«").substringBefore("…")
        assertThat(preview.length).isAtMost(50)
    }

    @Test fun `control characters in body are replaced and collapsed to single spaces`() {
        // CR / LF / NUL / BEL pourraient splitter un SMS ou injecter des caractères
        // trompeurs dans le tapback parsé côté correspondant. On les neutralise + on
        // compresse les espaces multiples résultants.
        val malicious = "Hello\nworld\u0000 test\there"
        val out = buildTapbackBody("🙏", malicious)
        // Aucun caractère de contrôle résiduel.
        assertThat(out).doesNotContain("\n")
        assertThat(out).doesNotContain("\r")
        assertThat(out).doesNotContain("\u0000")
        assertThat(out).doesNotContain("")
        assertThat(out).doesNotContain("\t")
        // Pas de doubles espaces (collapse propre).
        assertThat(out).doesNotContain("  ")
        // Format final attendu.
        assertThat(out).isEqualTo("Reacted 🙏 to «Hello world test here»")
    }

    @Test fun `body with leading and trailing whitespace is trimmed`() {
        assertThat(buildTapbackBody("😢", "   bonjour   "))
            .isEqualTo("Reacted 😢 to «bonjour»")
    }

    @Test fun `Y1 — bidi RLO override is stripped from preview`() {
        // Sans cette garde, un body forgé `"‮Hello"` (RLO + Hello) inverserait
        // visuellement le rendu côté correspondant : `«olleH»` au lieu de `«Hello»`.
        // Pire, ça peut casser le parsing iMessage qui attend EXACTEMENT
        // `Reacted X to «…»`.
        val out = buildTapbackBody("👍", "‮Hello‬ World")
        assertThat(out).doesNotContain("‮")
        assertThat(out).doesNotContain("‬")
        assertThat(out).isEqualTo("Reacted 👍 to «Hello World»")
    }

    @Test fun `Y1 — Unicode line and paragraph separators are stripped`() {
        // U+2028 (LINE SEPARATOR) et U+2029 (PARAGRAPH SEPARATOR) ne sont PAS
        // matchés par C0/C1 mais peuvent splitter le rendu de la trame côté
        // certains stacks SMS.
        val out = buildTapbackBody("🔥", "foo bar baz")
        assertThat(out).doesNotContain(" ")
        assertThat(out).doesNotContain(" ")
        assertThat(out).isEqualTo("Reacted 🔥 to «foo bar baz»")
    }

    @Test fun `Y1 — BOM zero-width is stripped`() {
        val out = buildTapbackBody("🙏", "﻿hello")
        assertThat(out).doesNotContain("﻿")
        assertThat(out).isEqualTo("Reacted 🙏 to «hello»")
    }

    @Test fun `Y2 — truncation never leaves a high surrogate orphan`() {
        // 50 chars dont les derniers sont des drapeaux composites (2 surrogate pairs
        // chacun = 4 UTF-16 units). Si `take(50)` coupe en plein milieu, on aurait un
        // caractère corrompu (`\uD83C` orphelin) injecté dans le SMS sortant.
        val ascii = "a".repeat(46)
        val flag = "🇫🇷" // 🇫🇷 — 4 UTF-16 units
        val body = ascii + flag // 50 units total : exactement à la limite
        val out = buildTapbackBody("❤️", body)
        // Aucun surrogate orphelin dans le résultat (vérifie aucun high surrogate
        // n'est suivi d'un non-low-surrogate).
        for (i in 0 until out.length - 1) {
            if (Character.isHighSurrogate(out[i])) {
                assertThat(Character.isLowSurrogate(out[i + 1])).isTrue()
            }
        }
        // Aucun low surrogate orphelin en queue.
        if (out.isNotEmpty() && Character.isHighSurrogate(out.last())) {
            assertThat(false).isTrue() // fail explicit
        }
    }

    @Test fun `Y3 — output never exceeds one UCS-2 SMS segment (70 chars)`() {
        // Cap dynamique : le total émis ne doit jamais déborder 70 chars UTF-16
        // peu importe l'emoji ou le body. Sinon basculement multi-part = facturation
        // ×2 et perte du parsing Apple/Google.
        val longBody = "x".repeat(500)
        // Emoji simple (1-2 UTF-16 units)
        assertThat(buildTapbackBody("❤️", longBody).length).isAtMost(70)
        // Emoji drapeau (4 UTF-16 units)
        assertThat(buildTapbackBody("🇫🇷", longBody).length).isAtMost(70)
        // Emoji famille ZWJ (11 UTF-16 units)
        assertThat(buildTapbackBody("👨‍👩‍👧‍👦", longBody).length)
            .isAtMost(70)
    }

    // ──────── buildIosTapbackBody — forme textuelle Apple ────────

    @Test fun `ios tapback maps the six classic emojis to Apple verbs`() {
        assertThat(buildIosTapbackBody("❤️", "Hi")).isEqualTo("Loved \"Hi\"")
        assertThat(buildIosTapbackBody("👍", "Hi")).isEqualTo("Liked \"Hi\"")
        assertThat(buildIosTapbackBody("👎", "Hi")).isEqualTo("Disliked \"Hi\"")
        assertThat(buildIosTapbackBody("😂", "Hi")).isEqualTo("Laughed at \"Hi\"")
        assertThat(buildIosTapbackBody("‼️", "Hi")).isEqualTo("Emphasized \"Hi\"")
        assertThat(buildIosTapbackBody("❓", "Hi")).isEqualTo("Questioned \"Hi\"")
    }

    @Test fun `ios tapback removals use Apple removal wordings`() {
        assertThat(buildIosTapbackBody("❤️", "Hi", isRemoval = true))
            .isEqualTo("Removed a heart from \"Hi\"")
        assertThat(buildIosTapbackBody("👍", "Hi", isRemoval = true))
            .isEqualTo("Removed a like from \"Hi\"")
        assertThat(buildIosTapbackBody("👎", "Hi", isRemoval = true))
            .isEqualTo("Removed a dislike from \"Hi\"")
        assertThat(buildIosTapbackBody("😂", "Hi", isRemoval = true))
            .isEqualTo("Removed a laugh from \"Hi\"")
        assertThat(buildIosTapbackBody("‼️", "Hi", isRemoval = true))
            .isEqualTo("Removed an exclamation from \"Hi\"")
        assertThat(buildIosTapbackBody("❓", "Hi", isRemoval = true))
            .isEqualTo("Removed a question from \"Hi\"")
    }

    @Test fun `ios tapback uses straight ASCII quotes so carriers cannot mangle them`() {
        // Régression du bug rapporté : les «» de EMOJI_WITH_QUOTE arrivaient en
        // <<>> côté correspondant (`👍<<Hi>>`). La forme iOS n'émet que de l'ASCII
        // (verbe + guillemets droits) : rien à transcoder, rien à déformer.
        val out = buildIosTapbackBody("👍", "Hi")
        assertThat(out).doesNotContain("«")
        assertThat(out).doesNotContain("»")
        assertThat(out).doesNotContain("“")
        assertThat(out).doesNotContain("”")
        assertThat(out.all { it.code < 128 }).isTrue()
    }

    @Test fun `ios tapback falls back to generic Reacted form for other emojis`() {
        assertThat(buildIosTapbackBody("🎉", "Hi")).isEqualTo("Reacted 🎉 to \"Hi\"")
        assertThat(buildIosTapbackBody("🎉", "Hi", isRemoval = true))
            .isEqualTo("Removed 🎉 from \"Hi\"")
        // Multi-emoji : la chaîne entière part telle quelle.
        assertThat(buildIosTapbackBody("❤️👍", "Hi")).isEqualTo("Reacted ❤️👍 to \"Hi\"")
    }

    @Test fun `ios tapback lookup ignores the variation selector`() {
        // "❤" sans VS16 et "‼" sans VS16 sont les mêmes réactions que "❤️"/"‼️".
        assertThat(buildIosTapbackBody("❤", "Hi")).isEqualTo("Loved \"Hi\"")
        assertThat(buildIosTapbackBody("‼", "Hi")).isEqualTo("Emphasized \"Hi\"")
    }

    @Test fun `ios tapback empty body falls back without quotes`() {
        assertThat(buildIosTapbackBody("👍", "")).isEqualTo("Reacted 👍")
        assertThat(buildIosTapbackBody("👍", "", isRemoval = true)).isEqualTo("Removed 👍")
    }

    @Test fun `ios tapback truncates long previews within one segment`() {
        val longBody = "x".repeat(500)
        val out = buildIosTapbackBody("👍", longBody)
        assertThat(out.length).isAtMost(70)
        assertThat(out).startsWith("Liked \"")
        assertThat(out).endsWith("…\"")
        // Le verbe de retrait le plus long reste aussi dans 1 segment.
        val removed = buildIosTapbackBody("‼️", longBody, isRemoval = true)
        assertThat(removed.length).isAtMost(70)
        assertThat(removed).startsWith("Removed an exclamation from \"")
    }

    @Test fun `ios tapback keeps inner quotes and still round-trips`() {
        // Les guillemets DANS la citation sont conservés : le décodeur matche
        // jusqu'au DERNIER guillemet, et le préfixe reste exact.
        val out = buildIosTapbackBody("👍", "say \"hi\" now")
        assertThat(out).isEqualTo("Liked \"say \"hi\" now\"")
        val decoded = IncomingReactionDecoder.decode(out)
        assertThat(decoded?.emoji).isEqualTo("👍")
        assertThat(decoded?.previewPrefix).isEqualTo("say \"hi\" now")
        assertThat(decoded?.isRemoval).isFalse()
    }

    @Test fun `ios tapback round-trips through our own decoder for every verb`() {
        val cases = listOf(
            "❤️" to "Loved",
            "👍" to "Liked",
            "👎" to "Disliked",
            "😂" to "Laughed at",
            "‼️" to "Emphasized",
            "❓" to "Questioned",
        )
        for ((emoji, _) in cases) {
            val added = buildIosTapbackBody(emoji, "Hello world")
            val decodedAdd = IncomingReactionDecoder.decode(added)
            assertThat(decodedAdd?.emoji).isEqualTo(emoji)
            assertThat(decodedAdd?.previewPrefix).isEqualTo("Hello world")
            assertThat(decodedAdd?.isRemoval).isFalse()

            val removed = buildIosTapbackBody(emoji, "Hello world", isRemoval = true)
            assertThat(removed).startsWith("Removed ")
            val decodedRemove = IncomingReactionDecoder.decode(removed)
            assertThat(decodedRemove?.emoji).isEqualTo(emoji)
            assertThat(decodedRemove?.previewPrefix).isEqualTo("Hello world")
            assertThat(decodedRemove?.isRemoval).isTrue()
        }
        // Forme générique (emoji hors classiques) : aller-retour aussi.
        val generic = buildIosTapbackBody("🎉", "Hello world")
        val decodedGeneric = IncomingReactionDecoder.decode(generic)
        assertThat(decodedGeneric?.emoji).isEqualTo("🎉")
        assertThat(decodedGeneric?.previewPrefix).isEqualTo("Hello world")
        val genericRemoved = buildIosTapbackBody("🎉", "Hello world", isRemoval = true)
        val decodedGenericRemoved = IncomingReactionDecoder.decode(genericRemoved)
        assertThat(decodedGenericRemoved?.emoji).isEqualTo("🎉")
        assertThat(decodedGenericRemoved?.isRemoval).isTrue()
    }

    @Test fun `ios tapback truncated preview round-trips with wasTruncated`() {
        val out = buildIosTapbackBody("👍", "x".repeat(500))
        val decoded = IncomingReactionDecoder.decode(out)
        assertThat(decoded?.emoji).isEqualTo("👍")
        assertThat(decoded?.wasTruncated).isTrue()
        assertThat(decoded?.previewPrefix?.length).isAtMost(50)
    }

    @Test fun `ios tapback empty-body add round-trips, removal fallback is text only`() {
        // `Reacted 👍` se décode (cible = message sortant le plus récent).
        val decoded = IncomingReactionDecoder.decode(buildIosTapbackBody("👍", ""))
        assertThat(decoded?.emoji).isEqualTo("👍")
        assertThat(decoded?.previewPrefix).isNull()
        // `Removed 👍` n'a pas de forme décodable (pas de citation) : il reste
        // lisible comme texte côté correspondant, c'est documenté, pas un bug.
        assertThat(IncomingReactionDecoder.decode(buildIosTapbackBody("👍", "", true))).isNull()
    }
}
