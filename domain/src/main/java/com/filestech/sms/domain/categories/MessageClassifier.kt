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
 * 3. **Promotional** — marketing, deals or political content, a cold sales
 *    pitch from a stranger, or a sender that is a short code or an
 *    alphanumeric label.
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

    /** Round 18 — distinct service nouns for the list pitch (see [isColdSalesPitch]). */
    private const val MIN_LIST_PITCH_SERVICES = 2

    /**
     * Bank / payment / delivery / appointment content.
     *
     * Deliberately NOT in this list: bare "paid", "order", "free", "save",
     * "charge", "balance" — all common in personal messages. "charged on"
     * counts (the billing pattern "you'll be charged $X on [date]"), but
     * bare "charged" stays out ("I charged my phone"). "en camino"
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
        "charged to your card", "you ll be charged", "will be charged",
        "charged on", "tap to pay", "transaction alert", "your transaction",
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
     * ("unsubscribe", "coupon", "election", "ballot") count. Bare "vote"
     * together with a party word is Promotional too — see [isPoliticalVote].
     * The "Stop to End" / "Stop2End" opt-out footer ("stop2end",
     * "stop to end") is a promo signal real personal messages don't carry.
     * Bare "campagne" (FR) / "campagna" (IT) are out — both also mean
     * "countryside" ("à la campagne"); only "campagne électorale" /
     * "campagna elettorale" count. Bare "campana" (ES) is out too (a bell);
     * only "campaña electoral" / "campaña política" count. Bare "wahl"
     * (DE) is out ("die Wahl haben" is having a choice); only "wahlen"
     * and "wahltag" count. Bare "eleccion" (ES) is out (also "choice");
     * only "elecciones" counts.
     */
    private val PROMOTIONAL_KEYWORDS = listOf(
        // EN
        "unsubscribe", "opt out", "reply stop", "to opt out", "stop2end",
        "stop to end", "limited time",
        "special offer", "exclusive offer", "limited offer", "offer ends",
        "offer expires", "discount code", "promo code", "percent off",
        "sale ends", "flash sale", "clearance sale", "big sale",
        "black friday", "free shipping", "free trial", "free gift",
        "free dvd", "buy one get one", "deal of the day", "coupon", "voucher",
        "sweepstakes", "you have won", "claim your", "claim your prize",
        "congratulations you have won",
        // FR
        "desinscrire", "vous desinscrire", "pour vous desinscrire",
        "offre speciale", "offre exclusive", "offre limitee",
        "offre de bienvenue", "code promo", "code de reduction",
        "bon de reduction", "en promotion", "soldes", "reduction",
        "livraison gratuite", "essai gratuit", "cadeau offert",
        "ventes flash", "gagnez", "vous avez gagne",
        "felicitations vous avez gagne",
        // DE
        "abmelden", "sonderangebot", "exklusives angebot",
        "begrenztes angebot", "rabattcode", "gutscheincode", "gutschein",
        "rabatt", "aktion endet", "kostenloser versand",
        "kostenlose lieferung", "gratis", "gewinnen",
        "sie haben gewonnen",
        // ES
        "darse de baja", "cancelar suscripcion", "oferta especial",
        "oferta exclusiva", "oferta limitada", "codigo promocional",
        "codigo de descuento", "descuento", "cupon", "promocion",
        "rebajas", "venta flash", "envio gratis", "prueba gratis",
        "regalo gratis", "has ganado", "felicidades has ganado",
        // IT
        "annulla iscrizione", "cancellati", "disiscriviti",
        "offerta speciale", "offerta esclusiva", "offerta limitata",
        "codice promozionale", "codice sconto", "sconto", "promozione",
        "saldi", "vendita lampo", "spedizione gratuita",
        "prova gratuita", "regalo gratuito", "hai vinto",
        "congratulazioni hai vinto",
    )

    /**
     * Round 13 — political phrases, split out of [PROMOTIONAL_KEYWORDS].
     * These are ordinary language in a personal chat ("Did you vote for
     * the Democrat?"), so they only count as promotional from a stranger
     * (see [classify]: gated on `!isContact && !isGroup`). A campaign text
     * from a non-contact still matches; a contact or group talking politics
     * does not.
     */
    private val POLITICAL_KEYWORDS = listOf(
        // EN
        "election", "ballot", "vote for",
        "vote now", "your vote", "voting day", "candidate", "campaign",
        "donate", "donation",
        // FR
        "scrutin", "votez pour",
        "candidat", "campagne electorale", "faites un don", "faire un don",
        // DE
        "wahltag", "wahlen", "stimmzettel",
        "kandidat", "kampagne", "spenden", "spende",
        // ES
        "elecciones", "dia de las elecciones", "boleta", "vota por",
        "tu voto", "candidato", "campana electoral", "campana politica",
        "dona ahora", "donar",
        // IT
        "elezioni", "elezione",
        "scheda elettorale", "vota per", "il tuo voto", "candidato",
        "campagna elettorale", "dona ora", "donazione",
    )

    /**
     * Round 17 — the cold-pitch offer markers and home-service nouns.
     * Bare "free" is deliberately NOT in [PROMOTIONAL_KEYWORDS] ("the
     * concert was free"), but in a pair with a service noun, from a
     * stranger, it is the lead-gen pitch ("free estimate", "no-cost roof
     * inspection") — the same pair logic as [isPoliticalVote]. The nouns
     * are home services because that is the cold-text family: roofing,
     * solar, HVAC, duct cleaning, windows. "quote"/"estimate" are in the
     * list knowingly: a stranger's first text reporting a free quote is
     * far rarer than a pitch using the phrase, and classification follows
     * the latest message, so a real person's next normal text re-files
     * the conversation as Personal.
     *
     * Round 18 — three more pitches Kristin reported: "offering" ("We're
     * offering affordable tree trimming") and "reaching out" ("I'm a local
     * investor and reaching out") join the offer markers, and tree work
     * ("tree", "trimming", "removal", "arborist"), vent cleaning ("vent",
     * "vents", "cleaning") and house investors ("investor", "investors")
     * join the nouns. [COLD_PITCH_CLOSERS] is the pitch's call to action
     * for the list rule in [isColdSalesPitch].
     */
    private val COLD_PITCH_OFFER_MARKERS = listOf(
        "no cost",
        "free",
        "complimentary",
        "offering",
        "reaching out",
    )
    private val COLD_PITCH_SERVICE_NOUNS = listOf(
        "roof", "roofing", "solar", "inspection", "inspections",
        "estimate", "estimates", "quote", "quotes", "consultation",
        "hvac", "duct", "ducts", "gutter", "gutters", "siding",
        "plumbing", "remodel", "remodeling", "driveway", "paving",
        "window", "windows",
        "tree", "trimming", "removal", "arborist", "vent", "vents",
        "cleaning", "investor", "investors",
    )
    private val COLD_PITCH_CLOSERS = listOf("interested")

    /**
     * Round 17 — a business self-introduction in the folded body: "it's
     * Maria with Roofing Pros", "this is Tom with Bright Solar". The word
     * after "with" must not be a determiner or pronoun, so "it's Sarah
     * with the kids" and "it's Frank with my brother" do not match. "from"
     * never matches either, so "Hi, it's Frank from the gym" stays
     * Personal. Two-word first names ("Mary Jane") do not match — the
     * offer half of [isColdSalesPitch] still catches those pitches.
     */
    private val BUSINESS_INTRO_REGEX = Regex(
        " (?:it s|this is) [a-z]+ with " +
            "(?!the |my |a |an |our |your |his |her |me |us |them |him )[a-z]+ ",
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
                isStrangerOnlyPromo(paddedBody, isContact, isGroup) ||
                isShortCode(digits) || isAlphanumericSender(sender) -> MessageCategory.PROMOTIONAL
            isContact || isGroup || isRegularNumber(digits) -> MessageCategory.PERSONAL
            else -> MessageCategory.OTHER
        }
    }

    /**
     * Round 13 + 17 — promotional signals that only count from a stranger.
     * Political talk and cold sales pitches are ordinary language in a
     * personal chat ("Did you vote for the Democrat?", "Hi, it's Frank"),
     * so a contact or a group stays Personal and only a stranger's
     * message can match. This covers the political phrases
     * ([POLITICAL_KEYWORDS], e.g. "vote for"), the vote+party pair
     * ([isPoliticalVote]) and the cold-pitch pair ([isColdSalesPitch]);
     * without the guard any of them caught a contact's chat.
     */
    private fun isStrangerOnlyPromo(
        paddedBody: String,
        isContact: Boolean,
        isGroup: Boolean,
    ): Boolean {
        if (isContact || isGroup) return false
        return containsAny(paddedBody, POLITICAL_KEYWORDS) || isPoliticalVote(paddedBody) ||
            isColdSalesPitch(paddedBody)
    }

    /**
     * Round 17 — a cold sales pitch: the lead-gen template ("Hey Kristin,
     * it's Maria with Roofing Pros. We're doing no-cost roof inspections
     * in Springfield"). Either half alone is ordinary personal language —
     * "Hi, it's Frank", "it's Sarah with the kids", "I got a free quote",
     * "the concert was free" — so only a combination counts. Three of
     * them: an offer marker together with a home-service noun (the pitch
     * with or without an introduction), a business introduction together
     * with an offer marker or a service noun (the pitch whose offer is
     * worded without "free"/"no cost"), or — round 18 — the list pitch:
     * two or more distinct service nouns together with the closer
     * ("We provide quotes … for air duct and dryer vent cleaning.
     * Interested?"), which carries no offer marker and no "with"
     * introduction. English only for now — this is the US lead-gen
     * template; other locales are a later addition.
     */
    private fun isColdSalesPitch(paddedBody: String): Boolean {
        val hasOffer = containsAny(paddedBody, COLD_PITCH_OFFER_MARKERS)
        val hasService = containsAny(paddedBody, COLD_PITCH_SERVICE_NOUNS)
        if (hasOffer && hasService) return true
        if (hasBusinessIntro(paddedBody) && (hasOffer || hasService)) return true
        return containsAny(paddedBody, COLD_PITCH_CLOSERS) &&
            distinctServiceCount(paddedBody) >= MIN_LIST_PITCH_SERVICES
    }

    /** Distinct home-service nouns in the body (repeats count once). */
    private fun distinctServiceCount(paddedBody: String): Int =
        COLD_PITCH_SERVICE_NOUNS.count { noun -> paddedBody.contains(" $noun ") }

    private fun hasBusinessIntro(paddedBody: String): Boolean =
        BUSINESS_INTRO_REGEX.containsMatchIn(paddedBody)

    /**
     * A political text: a vote word ("vote", "voted", "voting", "votes")
     * together with a party word ("republican(s)", "democrat(s)") in the
     * same body. Either word alone is ordinary personal language ("Did you
     * vote today?", "My uncle is a Democrat"), so only the pair counts.
     */
    private fun isPoliticalVote(paddedBody: String): Boolean {
        val voteWords = listOf("vote", "voted", "voting", "votes")
        val partyWords = listOf("republican", "republicans", "democrat", "democrats")
        return containsAny(paddedBody, voteWords) && containsAny(paddedBody, partyWords)
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
