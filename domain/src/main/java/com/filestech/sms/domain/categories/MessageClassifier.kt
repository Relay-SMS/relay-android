package com.filestech.sms.domain.categories

import com.filestech.sms.core.ext.foldForSearch
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.otp.OtpCodeDetector

/**
 * Sorts a message — and, through [classifyConversation], a conversation by
 * its latest message — into one of the home-screen tab categories
 * ([MessageCategory]).
 *
 * Priority, first match wins:
 * 1. **OTP** — [OtpCodeDetector] finds a login code in the body.
 * 2. **Transactions** — bank / payment / delivery / appointment content.
 * 3. **Promotional** — marketing, deals or political content, or a sender
 *    that is a short code or an alphanumeric label.
 * 4. **Personal** — a contact, a group, or a regular phone number. A number
 *    that is not in contacts yet is still a real person, so a new friend is
 *    never hidden in an off-screen tab.
 * 5. **Other** — nothing else matched.
 *
 * # Doctrine (same as the OTP detector's)
 *
 * A missed promo costs one message in the wrong tab; a personal message
 * misfiled as promo hides a real person. The keyword lists are therefore
 * curated and conservative: phrases and domain-specific words, never bare
 * common verbs. "I paid for lunch" stays Personal — "paid" is not a
 * keyword, "payment received" is.
 *
 * Matching is word-boundary aware: the body is folded (lowercase,
 * accents stripped via [foldForSearch]) and every non-letter/digit becomes
 * a single space, so "paid" inside "unpaid" or "delivery" inside
 * "deliveryman" never matches. Keywords are stored already folded
 * (lowercase, no accents, spaces only — never hyphens or apostrophes, so
 * "rendez-vous" matches the keyword "rendez vous").
 *
 * Keywords cover the app's five locales (EN, FR, DE, ES, IT).
 */
object MessageClassifier {

    private const val SHORT_CODE_MAX_DIGITS = 6
    private const val REGULAR_NUMBER_MIN_DIGITS = 7

    /**
     * Bank / payment / delivery / appointment content.
     *
     * Deliberately NOT in this list: bare "paid", "order", "free", "save",
     * "charge", "balance" — all common in personal messages. "en camino"
     * (ES) is out too: "estoy en camino" ("I'm on my way") is everyday
     * personal Spanish; only "paquete/pedido en camino" counts. Bare
     * "cita" (ES) is out for the same reason ("tengo una cita" is a date,
     * not a booking) — only "su cita" and its phrases count. Bare "solde"
     * (FR) is out ("les soldes" is a sale); only "votre solde" and
     * "solde de votre compte" count. Bare "termin" (DE) is out ("ich habe
     * einen Termin" is personal); only "ihr termin" and its phrases count.
     */
    private val TRANSACTION_KEYWORDS = listOf(
        // EN
        "your order", "order shipped", "order delivered", "order confirmed",
        "order cancelled", "your package", "your parcel", "out for delivery",
        "delivery scheduled", "delivery attempted", "your delivery",
        "delivery update", "tracking number", "shipment", "shipped",
        "package delivered", "parcel delivered", "your shipment",
        "payment received", "payment confirmed", "payment successful",
        "payment due", "your payment", "payment failed", "card charged",
        "charged to your card", "transaction alert", "your transaction",
        "account balance", "your account", "your balance", "low balance",
        "statement ready", "direct debit", "withdrawal", "deposit received",
        "your receipt", "receipt for", "invoice", "your invoice",
        "amount due", "refund issued", "your refund",
        "your appointment", "appointment reminder", "appointment confirmed",
        "appointment scheduled", "appointment cancelled", "your booking",
        "booking confirmation", "booking confirmed", "your reservation",
        "reservation confirmed", "reservation cancelled", "boarding pass",
        "your flight",
        // FR
        "votre commande", "commande expediee", "commande livree",
        "commande confirmee", "votre colis", "en cours de livraison",
        "livraison prevue", "votre livraison", "numero de suivi",
        "suivi de colis", "expedition", "colis livre", "votre paiement",
        "paiement recu", "paiement confirme", "paiement accepte",
        "votre compte", "solde de votre compte", "votre solde",
        "solde insuffisant", "virement recu", "virement effectue",
        "prelevement", "retrait effectue", "votre facture", "facture",
        "montant du", "remboursement effectue", "votre rendez vous",
        "rendez vous confirme", "rappel de rendez vous",
        "votre reservation", "reservation confirmee", "votre recu",
        "recu de paiement", "carte d embarquement",
        // DE
        "ihre bestellung", "bestellung versandt", "bestellung geliefert",
        "bestellung bestatigt", "ihre sendung", "sendungsverfolgung",
        "lieferung", "ihre lieferung", "lieferung geplant",
        "paket zugestellt", "ihre rechnung", "rechnung",
        "zahlung erhalten", "zahlung eingegangen", "zahlung bestatigt",
        "ihre zahlung", "kontostand", "ihr konto", "lastschrift",
        "abbuchung", "auszahlung", "gutschrift", "erstattung", "ihr termin",
        "terminerinnerung", "terminbestatigung", "termin abgesagt",
        "ihre buchung", "buchungsbestatigung", "ihre reservierung",
        "bordkarte", "ihr flug",
        // ES
        "su pedido", "pedido enviado", "pedido entregado",
        "pedido confirmado", "su paquete", "paquete entregado",
        "paquete en camino", "pedido en camino", "en reparto",
        "su entrega", "entrega programada", "numero de seguimiento",
        "seguimiento de envio", "su envio", "factura", "su factura",
        "pago recibido", "pago confirmado", "su pago", "pago rechazado",
        "saldo de su cuenta", "su cuenta", "su saldo",
        "saldo insuficiente", "retiro realizado", "transferencia recibida",
        "transferencia realizada", "su cita", "cita confirmada",
        "recordatorio de cita", "cita programada", "su reserva",
        "reserva confirmada", "tarjeta de embarque", "su vuelo",
        "recibo de pago", "reembolso emitido", "su reembolso",
        // IT
        "il tuo ordine", "ordine spedito", "ordine consegnato",
        "ordine confermato", "il tuo pacco", "pacco consegnato",
        "in consegna", "consegna programmata", "la tua consegna",
        "numero di tracciamento", "tracciamento pacco", "spedizione",
        "fattura", "la tua fattura", "pagamento ricevuto",
        "pagamento confermato", "il tuo pagamento", "pagamento rifiutato",
        "saldo del tuo conto", "il tuo conto", "il tuo saldo",
        "saldo insufficiente", "prelievo effettuato", "bonifico ricevuto",
        "bonifico effettuato", "il tuo appuntamento",
        "appuntamento confermato", "promemoria appuntamento",
        "appuntamento annullato", "la tua prenotazione",
        "prenotazione confermata", "carta di imbarco", "il tuo volo",
        "ricevuta di pagamento", "rimborso emesso", "il tuo rimborso",
    )

    /**
     * Marketing, deals and political content.
     *
     * Deliberately NOT in this list: bare "free", "save", "offer", "sale",
     * "deal", "vote" — all common in personal messages ("I vote for
     * pizza", "garage sale", "job offer"). Only phrases ("free shipping",
     * "special offer", "sale ends", "vote for") and domain-specific words
     * ("unsubscribe", "coupon", "election", "ballot") count. Bare
     * "campagne" (FR) / "campagna" (IT) are out — both also mean
     * "countryside" ("à la campagne"); only "campagne électorale" /
     * "campagna elettorale" count. Bare "campana" (ES) is out too (a bell);
     * only "campaña electoral" / "campaña política" count. Bare "wahl"
     * (DE) is out ("die Wahl haben" is having a choice); only "wahlen"
     * and "wahltag" count. Bare "eleccion" (ES) is out (also "choice");
     * only "elecciones" counts.
     */
    private val PROMOTIONAL_KEYWORDS = listOf(
        // EN
        "unsubscribe", "opt out", "reply stop", "to opt out", "limited time",
        "special offer", "exclusive offer", "limited offer", "offer ends",
        "offer expires", "discount code", "promo code", "percent off",
        "sale ends", "flash sale", "clearance sale", "big sale",
        "black friday", "free shipping", "free trial", "free gift",
        "buy one get one", "deal of the day", "coupon", "voucher",
        "sweepstakes", "you have won", "claim your prize",
        "congratulations you have won", "election", "ballot", "vote for",
        "vote now", "your vote", "voting day", "candidate", "campaign",
        "donate", "donation",
        // FR
        "desinscrire", "vous desinscrire", "pour vous desinscrire",
        "offre speciale", "offre exclusive", "offre limitee",
        "offre de bienvenue", "code promo", "code de reduction",
        "bon de reduction", "en promotion", "soldes", "reduction",
        "livraison gratuite", "essai gratuit", "cadeau offert",
        "ventes flash", "gagnez", "vous avez gagne",
        "felicitations vous avez gagne", "scrutin", "votez pour",
        "candidat", "campagne electorale", "faites un don", "faire un don",
        // DE
        "abmelden", "sonderangebot", "exklusives angebot",
        "begrenztes angebot", "rabattcode", "gutscheincode", "gutschein",
        "rabatt", "aktion endet", "kostenloser versand",
        "kostenlose lieferung", "gratis", "gewinnen",
        "sie haben gewonnen", "wahltag", "wahlen", "stimmzettel",
        "kandidat", "kampagne", "spenden", "spende",
        // ES
        "darse de baja", "cancelar suscripcion", "oferta especial",
        "oferta exclusiva", "oferta limitada", "codigo promocional",
        "codigo de descuento", "descuento", "cupon", "promocion",
        "rebajas", "venta flash", "envio gratis", "prueba gratis",
        "regalo gratis", "has ganado", "felicidades has ganado",
        "elecciones", "dia de las elecciones", "boleta", "vota por",
        "tu voto", "candidato", "campana electoral", "campana politica",
        "dona ahora", "donar",
        // IT
        "annulla iscrizione", "cancellati", "disiscriviti",
        "offerta speciale", "offerta esclusiva", "offerta limitata",
        "codice promozionale", "codice sconto", "sconto", "promozione",
        "saldi", "vendita lampo", "spedizione gratuita",
        "prova gratuita", "regalo gratuito", "hai vinto",
        "congratulazioni hai vinto", "elezioni", "elezione",
        "scheda elettorale", "vota per", "il tuo voto", "candidato",
        "campagna elettorale", "dona ora", "donazione",
    )

    /**
     * Classifies one message. [sender] is the sender's address (`null` for
     * a group message or an unknown sender); [isContact] is true when the
     * sender is in contacts; [isGroup] is true for a group conversation.
     * A `null` sender never counts as a short code or an alphanumeric
     * sender — it falls through to Personal via [isGroup], else Other.
     */
    fun classify(
        body: String?,
        sender: PhoneAddress?,
        isContact: Boolean,
        isGroup: Boolean,
    ): MessageCategory {
        val paddedBody = if (body.isNullOrBlank()) " " else normalizeForMatching(body)
        val isOtp = !body.isNullOrBlank() && OtpCodeDetector.detect(body) != null
        val digits = numericDigits(sender)
        return when {
            isOtp -> MessageCategory.OTP
            containsAny(paddedBody, TRANSACTION_KEYWORDS) -> MessageCategory.TRANSACTIONS
            containsAny(paddedBody, PROMOTIONAL_KEYWORDS) ||
                isShortCode(digits) || isAlphanumericSender(sender) -> MessageCategory.PROMOTIONAL
            isContact || isGroup || isRegularNumber(digits) -> MessageCategory.PERSONAL
            else -> MessageCategory.OTHER
        }
    }

    /**
     * Classifies a conversation by its latest message
     * ([Conversation.lastMessagePreview]). Groups pass a `null` sender —
     * the first address of a group is one member, not the sender — so a
     * group is Personal unless an earlier rule matched its latest message.
     * [Conversation.displayName] being non-null is what marks a contact.
     */
    fun classifyConversation(conversation: Conversation): MessageCategory {
        val sender = if (conversation.isGroup) null else conversation.addresses.firstOrNull()
        return classify(
            body = conversation.lastMessagePreview,
            sender = sender,
            isContact = conversation.displayName != null,
            isGroup = conversation.isGroup,
        )
    }

    /**
     * Folds the body for matching: lowercase + accents stripped, every
     * non-letter/digit collapsed to a single space, padded with one space
     * on each side so a keyword check is word-boundary aware
     * (" delivery " matches, " deliveryman " does not).
     */
    private fun normalizeForMatching(body: String): String {
        val folded = body.foldForSearch()
        val cleaned = StringBuilder(folded.length + 2)
        cleaned.append(' ')
        var lastWasSpace = true
        for (char in folded) {
            if (char.isLetterOrDigit()) {
                cleaned.append(char)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                cleaned.append(' ')
                lastWasSpace = true
            }
        }
        if (!lastWasSpace) cleaned.append(' ')
        return cleaned.toString()
    }

    private fun containsAny(paddedBody: String, keywords: List<String>): Boolean =
        keywords.any { keyword -> paddedBody.contains(" $keyword ") }

    /**
     * The sender's digits when the sender is purely numeric (an optional
     * leading `+` allowed), `null` otherwise — for an alphanumeric sender,
     * a `null` sender, or anything carrying `*`, `#` or letters.
     */
    private fun numericDigits(sender: PhoneAddress?): String? {
        val normalized = sender?.normalized ?: return null
        if (!normalized.removePrefix("+").all { it.isDigit() }) return null
        return normalized.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
    }

    private fun isShortCode(digits: String?): Boolean {
        val digitsValue = digits ?: return false
        return digitsValue.length <= SHORT_CODE_MAX_DIGITS
    }

    private fun isRegularNumber(digits: String?): Boolean {
        val digitsValue = digits ?: return false
        return digitsValue.length >= REGULAR_NUMBER_MIN_DIGITS
    }

    private fun isAlphanumericSender(sender: PhoneAddress?): Boolean =
        sender?.normalized?.any { it.isLetter() } ?: false
}
