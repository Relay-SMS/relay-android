package com.filestech.sms.domain.mms

import com.filestech.sms.domain.model.PhoneAddress

/**
 * v1.28.4 — **les membres d'un groupe, reconstitués depuis un MMS reçu.**
 *
 * Un MMS de groupe est un seul PDU adressé à plusieurs : son en-tête porte l'expéditeur (`From`)
 * et tous les destinataires (`To`, `Cc`), **nous compris**. Le groupe, c'est tout ce monde-là
 * moins nous. Si nous ne savons pas qui nous sommes — « Mon numéro » non renseigné — [of] ne
 * reconstitue rien : mieux vaut une conversation individuelle qu'un groupe dont l'utilisateur
 * serait membre en double. Mais le PDU porte quand même le groupe : [participantsOf] rend alors
 * tous les participants, et l'appelant peut les rapprocher d'une conversation existante dont les
 * membres sont ces participants moins un (cf. le rapprochement « moins un » du miroir) — le
 * groupe est retrouvé sans que le numéro soit saisi.
 *
 * Les adresses sont comparées par leur **clé d'identité** ([identityKey], la même règle de
 * rapprochement que partout ailleurs dans l'application — E.164 quand c'est possible) : un PDU
 * écrit `+33612345678` là où le contact est enregistré `06 12 34 56 78`, et les deux sont la
 * même personne. La fonction est injectée : `domain` ne connaît pas la région du téléphone.
 *
 * @return la liste des membres (au moins deux, l'expéditeur en tête), ou `null` quand ce n'est
 *   pas un groupe pour nous : nous-mêmes inconnus, ou un seul autre participant.
 */
object GroupMmsMembers {

    fun of(
        from: String,
        to: List<String>,
        cc: List<String>,
        self: String?,
        identityKey: (String) -> String,
    ): List<PhoneAddress>? {
        val cleMoi = self?.let(identityKey)?.takeIf { it.isNotBlank() } ?: return null
        val membres = LinkedHashMap<String, PhoneAddress>()
        for (brut in listOf(from) + to + cc) {
            val cle = identityKey(brut)
            if (cle.isBlank() || cle == cleMoi) continue
            membres.putIfAbsent(cle, PhoneAddress.of(brut))
        }
        return membres.values.toList().takeIf { it.size >= 2 }
    }

    /**
     * v1.28.10 — **tous les participants d'un MMS, nous compris.** Même lecture de l'en-tête que [of],
     * sans retirer qui que ce soit : quand « Mon numéro » n'est pas renseigné, on ne sait pas qui
     * retirer, et [of] rend `null` — mais le PDU porte quand même le groupe. L'appelant peut alors
     * rapprocher ces participants d'une conversation de groupe existante dont les membres sont ces
     * participants moins un (cet un-là, c'est nous). L'expéditeur est en tête, comme dans [of].
     *
     * @return les participants dédupliqués, ou une liste vide quand l'en-tête ne porte rien.
     */
    fun participantsOf(
        from: String,
        to: List<String>,
        cc: List<String>,
        identityKey: (String) -> String,
    ): List<PhoneAddress> {
        val participants = LinkedHashMap<String, PhoneAddress>()
        for (brut in listOf(from) + to + cc) {
            val cle = identityKey(brut)
            if (cle.isBlank()) continue
            participants.putIfAbsent(cle, PhoneAddress.of(brut))
        }
        return participants.values.toList()
    }
}
