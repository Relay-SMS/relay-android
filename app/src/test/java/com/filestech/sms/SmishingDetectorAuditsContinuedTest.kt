package com.filestech.sms

import com.filestech.sms.domain.smishing.SmishingDetector
import com.filestech.sms.domain.smishing.SmishingReason
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Suite de [SmishingDetectorAuditsTest], dont elle reprend les cas.
 *
 * Elle existe pour la meme raison que son aine : detekt refuse une classe de plus de
 * trente-cinq fonctions, et il a raison. Les cas ici sont la fin du corpus de la
 * SECONDE relecture externe — domaines jetables, IBAN, espaces insecables, tirets et
 * fixes allemands — avec la meme doctrine : mieux vaut rater une arnaque que coller
 * un bandeau rouge sur un SMS legitime.
 */
class SmishingDetectorAuditsContinuedTest {

    @Test fun `un nom officiel COURT sur un domaine jetable est signale`() {
        // Le filtre `length >= 4` n'existe que pour borner Levenshtein, et il etait pose
        // DEVANT la regle du nom exact, qui est une EGALITE. Les cinq noms officiels de
        // trois lettres — dhl, cic, lcl, edf, sfr — etaient donc invisibles sur un TLD
        // jetable, alors qu'une egalite ne peut pas creer de faux positif.
        val usurpations = listOf(
            "Ihr Paket konnte nicht zugestellt werden: https://dhl.top/tracking",
            "Votre facture : https://edf.online/payer",
            "Votre espace client : https://sfr.click/connexion",
        )
        for (body in usurpations) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `un nom officiel court sur son VRAI domaine reste legitime`() {
        // Le controle negatif du test precedent : c'est le TLD a bas cout qui decide,
        // pas la longueur du nom.
        val legitimes = listOf(
            "Ihre Sendung: https://dhl.de/verfolgen",
            "Votre facture : https://edf.fr/espace-client",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.TyposquattedDomain)
        }
    }

    @Test fun `iban reste dehors parce qu il est un mot espagnol courant`() {
        // `iban` a ete remis dans les mots d'urgence le 2026-09-17, avec une frontiere de
        // mot qui reglait bien sa collision avec « Taliban » — puis RETIRE le meme jour.
        // Le raisonnement etait mene sur une application francaise ; elle parle cinq
        // langues. En ESPAGNOL, « iban » est l'imparfait du verbe `ir`. Une frontiere de mot
        // protege d'une sous-chaine, pas d'un homographe dans une autre langue.
        //
        // ⚠️ Les phrases ci-dessous n'emploient AUCUN autre mot de la liste. La premiere
        // ecriture de ce test disait « por favor confirme sus datos », qui est lui-meme un
        // mot d'urgence espagnol legitime : le test rougissait pour la mauvaise raison, et
        // il serait reste rouge meme apres correction du code.
        val legitimes = listOf(
            "Tus entradas iban a llegar hoy. Descárgalas: https://bit.ly/3AbC9",
            "Los paquetes iban en el coche del repartidor.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.UrgencyKeyword)
        }
    }

    @Test fun `un numero surtaxe ecrit avec des espaces insecables est reconnu`() {
        // `\s` de Java ne contient NI U+00A0 NI U+202F, que les claviers et traitements de
        // texte posent entre les groupes d'un numero francais. `\p{Zs}` les couvre.
        // Concatene plutot qu'interpole : « $insecable99 » se lirait comme l'identifiant
        // `insecable99`, et des accolades a cet endroit sont refusees par detekt ailleurs.
        val groupes = listOf("08", "99", "12", "34", "56")
        val numeros = listOf(
            "Rappelez le " + groupes.joinToString(INSECABLE),
            "Rappelez le " + groupes.joinToString(FINE_INSECABLE),
        )
        for (body in numeros) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test fun `deux nombres voisins separes par une insecable ne fusionnent toujours pas`() {
        // Le controle negatif du test precedent : elargir la classe de separateurs ne doit
        // pas ramener le faux positif « 32 - 11 » corrige le meme jour. L'espace qui
        // precede le tiret n'est pas suivi d'un chiffre, quelle que soit sa forme.
        val body = "Urgent : résultat du match, 32" + INSECABLE + "-" + INSECABLE +
            "11. Compte rendu demain."
        assertThat(SmishingDetector.analyze(body).reasons)
            .doesNotContain(SmishingReason.PremiumNumber)
    }

    // ──── Suites de l'audit de securite du 2026-09-17 : S8 (tiret) et S6 (fixe allemand) ────

    @Test
    fun `un tiret colle devant ou derriere un numero surtaxe ne le cache plus`() {
        // S8 — le tiret faisait frontiere des DEUX cotes, y compris pour les numeros longs.
        // Un seul caractere colle au numero neutralisait donc l'heuristique 3, et c'est le
        // genre de detail qu'un envoyeur d'arnaques trouve en une soiree.
        val arnaques = listOf(
            "URGENT : rappelez le -0899123456 immédiatement",
            "URGENT : rappelez le 0899123456- immédiatement",
        )
        for (body in arnaques) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .contains(SmishingReason.PremiumNumber)
        }
    }

    @Test
    fun `un tiret PRECEDE d une lettre referme la frontiere`() {
        // Releve par GPT et Gemini le meme jour, sur MA correction du matin : retirer le tiret
        // des frontieres longues ouvrait « commande Réf-0899123456 ». Le regard arriere ne
        // voyait qu'UN caractere — le tiret — et la lettre qui le precede restait hors de
        // portee. Le commentaire affirmait pourtant que « la LETTRE ferme la reference ».
        val legitimes = listOf(
            "Action requise : commande Réf-0899123456 prête au retrait.",
            "Action immédiate : facture INV-0899123456 à régler.",
            "Action requise : dossier 0899123456-SAV en cours.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test
    fun `le tiret garde toujours les numeros COURTS, eux`() {
        // Le controle negatif du test precedent : les numeros courts se lisent sur le texte
        // BRUT, ou un tiret separe vraiment les groupes d'une reference. Retirer la garde
        // des deux cotes aurait ramene « G-3211 » et « 12-3456-78 ».
        val legitimes = listOf(
            "Action immédiate : G-3211 est votre code de validation",
            "Action requise : votre commande réf. 12-3456-78 est prête.",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    @Test
    fun `un fixe allemand de onze chiffres n est pas un surtaxe britannique`() {
        // S6 — `09\d{9}` et `08[47]\d{8}` visaient le premium britannique, et decrivaient mot
        // pour mot le fixe allemand : Nuremberg, Bayreuth, Ansbach, Ingolstadt, Landshut.
        // Chacun de ces messages porte un mot d'urgence allemand : sans ce correctif, c'est
        // le bandeau rouge sur le SMS d'un commercant de Nuremberg.
        val legitimes = listOf(
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0911 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0921 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0981 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0841 1234567",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 0871 1234567",
            // Les voisins que le retrecissement a `090\d{8}` manquait encore, trouves par les
            // deux relectures externes : toute une famille d'indicatifs allemands en 090xx...
            "Bestätigen Sie Ihren Termin, Rückfragen unter 09071 123456",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 09081 123456",
            "Bestätigen Sie Ihren Termin, Rückfragen unter 09091 123456",
            // ...et surtout MESSINE, dont l'indicatif est 090, dans une langue livree.
            "Urgente: confermi l'appuntamento allo 090 12345678",
        )
        for (body in legitimes) {
            assertThat(SmishingDetector.analyze(body).reasons)
                .doesNotContain(SmishingReason.PremiumNumber)
        }
    }

    private companion object {
        /** U+00A0, l'espace insecable ordinaire. */
        const val INSECABLE = " "

        /** U+202F, la fine insecable — celle des claviers francais entre groupes de chiffres. */
        const val FINE_INSECABLE = " "
    }
}
