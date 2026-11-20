package com.filestech.sms.core.mms

/**
 * Audit C3 (v1.14.8) — Constantes MMS partagées. Avant : `280L * 1024L` était dupliqué dans
 * [com.filestech.sms.ui.screens.thread.ThreadViewModel.CARRIER_PAYLOAD_CAP_BYTES] et dans
 * [com.filestech.sms.data.voice.VoiceRecorder.MAX_SIZE_BYTES]. Même valeur (cap MMSC tightest
 * empiriquement observé : 280 KB), noms différents, packages différents → un futur ajustement
 * sur un côté laissait l'autre désynchronisé silencieusement. Désormais : SOURCE UNIQUE,
 * référencée par les deux call sites.
 */
object MmsConstants {

    /**
     * Cap maximal du payload MMS (PDU complet : SMIL + media + headers) accepté par la majorité
     * des MMSCs opérateurs. Au-delà, le carrier rejette silencieusement OU tronque. Valeur
     * empirique conservative (la plage observée 300-1024 KB selon opérateurs ; on prend le
     * minimum pour assurer la délivrance partout sans tester carrier-par-carrier).
     */
    const val CARRIER_PAYLOAD_CAP_BYTES: Long = 280L * 1024L

    /**
     * Round 33 follow-up — the filename marker for our own stickers.
     * `stickerSendUri` renders a sticker to `sticker-<codepoint>-512.png`;
     * the send path must carry this prefix through every rename (the
     * staged cache copy, then the durable promotion) so the stored row's
     * filename still contains "sticker" — that is what the Photos tab's
     * filename arm matches on. Single source, like the cap above: the
     * staging side (`outgoingCacheFileName`) and the promotion side
     * (`durableAttachmentFileName`) must agree on the marker, or the
     * rename in between strips it silently.
     */
    const val STICKER_FILENAME_PREFIX = "sticker-"
}
