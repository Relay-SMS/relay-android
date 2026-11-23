package com.filestech.sms.domain.e2e

import com.filestech.sms.domain.mms.MmsAttachment
import java.io.File

/**
 * One file offered for an encrypted media send: the durable file
 * bytes + the MIME type the sender's side knows it by. The
 * filename travels inside the key message; the part on the wire is
 * named `relay-media-<id>.bin`.
 */
data class MediaFileInput(
    val file: File,
    val mimeType: String,
)

/**
 * Domain port for the end-to-end media-encryption send path
 * (round 48, design §3): decide + encrypt + key-send for one 1:1
 * MMS, ahead of the PDU build.
 *
 * `SendMediaMmsUseCase` and `SendVoiceMmsUseCase` live in
 * `:domain`, which cannot see the encryption code (`:app`,
 * `com.filestech.sms.e2e`), so the media decision and the
 * per-file encryption go through this port. The real
 * implementation ([com.filestech.sms.e2e.E2eMediaGateImpl]) is
 * bound by Hilt; [NoOp] keeps `:domain` tests compiling untouched
 * and is the plain-only behavior when encryption is unavailable.
 *
 * The contract the use cases rely on:
 *
 * - [prepareMediaSend] answers "does this 1:1 MMS go out
 *   encrypted?" AND does the encryption when it does: one fresh
 *   random key per file, the key message sent FIRST (over the
 *   encrypted text channel), then the ciphertext parts returned
 *   for dispatch. Encrypted only when the peer is ENCRYPTED *and*
 *   media-capable; anything else is [PrepareResult.Plain].
 * - [PrepareResult.Encrypted] carries ciphertext TEMP files under
 *   the app's private cache. The caller MUST delete them after
 *   dispatch (the PDU builder reads the bytes eagerly, so the
 *   files are dead the moment the dispatcher returns) — a
 *   `try/finally` around the dispatch. On any failure the gate
 *   deletes its own temp files before returning.
 * - [PrepareResult.FallbackToPlain] means encryption failed
 *   (encrypt error, key-send refusal, unreadable file): the
 *   caller dispatches the ORIGINAL files plain. The gate already
 *   recorded the failure in the error log — the fallback is never
 *   silent, and the send is never dropped.
 * - [PrepareResult.Refuse] means the ciphertext would exceed the
 *   carrier cap ([maxTotalBytes]): the caller FAILS the send with
 *   the message, never dispatches plain instead — a message at
 *   the cap that silently went plain would lie about its state.
 */
interface E2eMediaGate {

    /** The outcome of preparing one 1:1 MMS for sending. */
    sealed interface PrepareResult {
        /**
         * Encrypted: dispatch [attachments] (ciphertext parts,
         * `application/octet-stream`, `relay-media-<id>.bin`)
         * and mirror the row encrypted. The caller deletes the
         * temp files after dispatch (see the interface KDoc).
         */
        data class Encrypted(val attachments: List<MmsAttachment>) : PrepareResult

        /**
         * Plain by decision (peer not ENCRYPTED, or not
         * media-capable): dispatch the original files, mirror
         * plain. No error-log entry — this is the normal state
         * for most peers.
         */
        data object Plain : PrepareResult

        /**
         * Plain by fallback: encryption was attempted and failed.
         * Dispatch the original files plain; the gate already
         * recorded the failure in the error log.
         */
        data object FallbackToPlain : PrepareResult

        /**
         * Refuse the send: the ciphertext would exceed the cap.
         * Fail loudly with [message] — never dispatch plain.
         */
        data class Refuse(val message: String) : PrepareResult
    }

    /**
     * Prepares one 1:1 MMS to [address] for sending. [files] are
     * the durable plaintext files; [maxTotalBytes] is the
     * carrier cap the CIPHERTEXT total must fit (the use case's
     * cap — the envelope adds 29 bytes per file, so the check
     * runs on the encrypted sizes, not the plaintext ones);
     * [date] is the message timestamp, written on the key-message
     * fragment rows in the system sent box.
     */
    suspend fun prepareMediaSend(
        address: String,
        files: List<MediaFileInput>,
        maxTotalBytes: Long,
        date: Long,
        subId: Int?,
    ): PrepareResult

    companion object {
        /**
         * Plain-only gate: every send is [PrepareResult.Plain].
         * The default for the use-case constructors, so `:domain`
         * tests that never touch encryption keep compiling — and
         * the plain path is byte-identical to the pre-encryption
         * behavior.
         */
        val NoOp: E2eMediaGate = object : E2eMediaGate {
            override suspend fun prepareMediaSend(
                address: String,
                files: List<MediaFileInput>,
                maxTotalBytes: Long,
                date: Long,
                subId: Int?,
            ): PrepareResult = PrepareResult.Plain
        }
    }
}
