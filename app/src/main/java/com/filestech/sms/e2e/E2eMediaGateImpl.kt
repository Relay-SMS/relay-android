package com.filestech.sms.e2e

import android.content.Context
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.e2e.E2eMediaGate
import com.filestech.sms.domain.e2e.MediaFileInput
import com.filestech.sms.domain.mms.MmsAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * The [E2eMediaGate] implementation (round 48, design §3): decide +
 * encrypt + key-send for one 1:1 MMS, ahead of the PDU build.
 *
 * - **Decide:** ENCRYPTED + media-capable → encrypt; anything else →
 *   plain. A state-read failure fails OPEN to Plain (the mirror write
 *   that follows would fail on the same broken database anyway — the
 *   same rule as [E2eSendManager.decideSend]). A non-ENCRYPTED peer
 *   also triggers a fire-and-forget probe, like the SMS send path:
 *   the key exchange starts now, so the NEXT media send can encrypt.
 * - **Encrypt:** one fresh random 32-byte AES-256-GCM key per file,
 *   via [AeadCipher] (the notes attachment envelope —
 *   `[version:1][iv:12][ciphertext+tag]`, 29 bytes overhead, no new
 *   crypto). The ciphertext lands in a temp file under the app's
 *   private cache (`cacheDir/media_encrypted/`, inside the
 *   system-writeback sandbox), named `relay-media-<id>.bin`
 *   with MIME `application/octet-stream` — the id is the only link
 *   to the key message, so the two must agree exactly.
 * - **Key messages FIRST:** one MEDIA_KEY message per file, over
 *   the encrypted text channel ([E2eSendManager.sendMediaKey]),
 *   sent BEFORE the caller dispatches the MMS — the receiver may
 *   need the key before the part lands, and a key that goes out
 *   after its part is a race. Only after every key is accepted
 *   does the caller dispatch the parts.
 * - **Cap:** the CIPHERTEXT total must fit the caller's cap (the
 *   use case's 300 KB — the envelope adds 29 bytes per file, so
 *   the check runs on the encrypted sizes). Over the cap, the
 *   send is REFUSED loudly — never silently dispatched plain.
 * - **Never silently fail:** any encryption or key-send failure
 *   falls back to plain MMS ([E2eMediaGate.PrepareResult.FallbackToPlain])
 *   and records it in the error log (`e2e-media-fallback`) —
 *   the send is never dropped, and the fallback is never silent.
 *
 * Temp-file ownership: the caller deletes the returned ciphertext
 * files after dispatch (the PDU builder reads the bytes eagerly,
 * so the files are dead the moment the dispatcher returns). This
 * class deletes its own temp files on every failure path, and
 * prunes stale ones (older than 24h — a process death between
 * prepare and delete) on the next prepare.
 */
@Singleton
class E2eMediaGateImpl @Inject constructor(
    private val peers: E2ePeerRepository,
    private val sendManager: E2eSendManager,
    private val probeManager: E2eProbeManager,
    private val cipher: AeadCipher,
    private val failureRecorder: E2eFailureRecorder,
    @ApplicationContext private val context: Context,
) : E2eMediaGate {

    override suspend fun prepareMediaSend(
        address: String,
        files: List<MediaFileInput>,
        maxTotalBytes: Long,
        date: Long,
        subId: Int?,
    ): E2eMediaGate.PrepareResult {
        if (files.isEmpty()) return E2eMediaGate.PrepareResult.Plain
        val state = runCatching { peers.state(address) }.getOrNull()
        val capable = state == StoredPeerState.ENCRYPTED &&
            runCatching { peers.isMediaCapable(address) }.getOrDefault(false)
        return when {
            state == null -> E2eMediaGate.PrepareResult.Plain
            state != StoredPeerState.ENCRYPTED -> {
                // Like the SMS send path: a 1:1 send to a peer that
                // is not ENCRYPTED yet starts the key exchange in
                // the background, so the next media send can
                // encrypt.
                probeManager.probeForSend(address, subId)
                E2eMediaGate.PrepareResult.Plain
            }
            !capable -> E2eMediaGate.PrepareResult.Plain
            else -> encryptAll(address, files, maxTotalBytes, date, subId)
        }
    }

    /**
     * One prepared file: the ciphertext temp file + its key-message
     * JSON. The JSON is sent (all files) before the caller
     * dispatches (all parts) — keys first, parts second.
     */
    private data class PreparedFile(val file: File, val keyJson: String)

    private suspend fun encryptAll(
        address: String,
        files: List<MediaFileInput>,
        maxTotalBytes: Long,
        date: Long,
        subId: Int?,
    ): E2eMediaGate.PrepareResult {
        pruneStaleCiphertext()
        val dir = File(context.cacheDir, CIPHER_DIR).apply { mkdirs() }
        val prepared = mutableListOf<PreparedFile>()
        return try {
            for (input in files) {
                when (val one = prepareOne(input, dir)) {
                    is PrepareOneResult.Ok -> prepared += one.file
                    is PrepareOneResult.Failed -> return fallback(prepared, one.reason)
                }
            }
            // The cap runs on the CIPHERTEXT total (the envelope
            // adds 29 bytes per file) — and BEFORE any key message
            // goes out, so a refused send leaves no orphan keys.
            val total = prepared.sumOf { it.file.length() }
            if (total > maxTotalBytes) {
                deleteAll(prepared)
                return E2eMediaGate.PrepareResult.Refuse(
                    "encrypted attachments exceed the MMS size cap ($total bytes)",
                )
            }
            // Keys FIRST: every key message accepted before the
            // caller dispatches any part.
            for (item in prepared) {
                val sent = sendManager.sendMediaKey(address, item.keyJson, date, subId)
                if (sent is Outcome.Failure) {
                    return fallback(prepared, "the media key could not be sent")
                }
            }
            E2eMediaGate.PrepareResult.Encrypted(
                prepared.map {
                    MmsAttachment(
                        file = it.file,
                        mimeType = E2eMediaKeyMessage.MEDIA_PART_MIME,
                        kind = MmsAttachment.Kind.OTHER,
                    )
                },
            )
        } catch (ce: CancellationException) {
            deleteAll(prepared)
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "E2E: unexpected media encryption failure")
            fallback(prepared, "unexpected media encryption failure")
        }
    }

    /**
     * One file's encryption + staging, as a value: the per-file
     * failure points (unreadable, empty, encrypt, stage) all
     * report a reason instead of returning early, so [encryptAll]
     * stays under the return-count limit.
     */
    private sealed interface PrepareOneResult {
        data class Ok(val file: PreparedFile) : PrepareOneResult
        data class Failed(val reason: String) : PrepareOneResult
    }

    private fun prepareOne(input: MediaFileInput, dir: File): PrepareOneResult {
        val plaintext = runCatching { input.file.readBytes() }.getOrNull()
        return when {
            plaintext == null ->
                PrepareOneResult.Failed("could not read the attachment")
            plaintext.isEmpty() -> PrepareOneResult.Failed("attachment is empty")
            else -> {
                val id = E2eMediaKeyMessage.generateFileId()
                val key = E2eMediaKeyMessage.generateKey()
                when (val result = cipher.encryptRaw(key, plaintext)) {
                    is Outcome.Success -> {
                        val outFile = File(dir, E2eMediaKeyMessage.partFileName(id))
                        val staged = runCatching {
                            outFile.writeBytes(result.value)
                        }.isSuccess
                        if (!staged) {
                            PrepareOneResult.Failed("could not stage the encrypted file")
                        } else {
                            val keyJson = E2eMediaKeyMessage.encode(
                                E2eMediaKeyMessage.MediaKey(
                                    id = id,
                                    key = key,
                                    mime = input.mimeType,
                                    sizeBytes = plaintext.size.toLong(),
                                    sha256Hex = sha256Hex(plaintext),
                                    fileName = input.file.name,
                                ),
                            )
                            PrepareOneResult.Ok(PreparedFile(outFile, keyJson))
                        }
                    }
                    is Outcome.Failure -> PrepareOneResult.Failed("encryption failed")
                }
            }
        }
    }

    /**
     * Fall back to plain MMS: delete the temp files staged so far,
     * record the failure in the error log (fixed message — no
     * addresses, no filenames), and tell the caller to dispatch
     * the originals plain. Never throws.
     */
    private fun fallback(prepared: List<PreparedFile>, reason: String): E2eMediaGate.PrepareResult {
        deleteAll(prepared)
        failureRecorder.recordEvent(
            MEDIA_FALLBACK_TAG,
            "Encrypted media failed ($reason); sent plain instead.",
        )
        return E2eMediaGate.PrepareResult.FallbackToPlain
    }

    private fun deleteAll(prepared: List<PreparedFile>) {
        for (item in prepared) runCatching { item.file.delete() }
    }

    /**
     * Best-effort prune of ciphertext temp files older than 24h —
     * the only leak path is a process death between prepare and
     * the caller's post-dispatch delete. Fresh files (an in-flight
     * send's parts) are never touched.
     */
    private fun pruneStaleCiphertext() {
        runCatching {
            val dir = File(context.cacheDir, CIPHER_DIR)
            val cutoff = System.currentTimeMillis() - STALE_CIPHER_MS
            dir.listFiles()
                ?.filter { it.isFile && it.lastModified() < cutoff }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        /** Error-log tag for plain fallbacks (round 48). */
        const val MEDIA_FALLBACK_TAG = "e2e-media-fallback"

        /** Ciphertext temp files live here (inside the private cache sandbox). */
        private const val CIPHER_DIR = "media_encrypted"

        /** A ciphertext temp file older than this is a leaked one (pruned on prepare). */
        private const val STALE_CIPHER_MS = 24 * 60 * 60 * 1000L
    }
}
