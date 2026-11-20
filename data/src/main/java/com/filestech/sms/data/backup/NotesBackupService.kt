package com.filestech.sms.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingOutcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.NoteAttachmentDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.backup.NotesBackupRestorer
import com.filestech.sms.domain.backup.RestoreNotesResult
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.security.AppLockManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.notebk` format (v1) — the notes backup, mirroring `.smsbk` ([BackupService]) with one
 * deliberate difference: attachment bytes are INCLUDED (base64), because a note photo or
 * file has no other copy anywhere — unlike MMS attachments, which also live in the system
 * store. A notes backup that dropped the photos would defeat its purpose.
 *
 *   "NTBK"(4) || version(1) || salt(16) || iter(4 BE) || aeadBlob
 *
 * `aeadBlob` = `AeadCipher.encryptRaw(rawKey, json, aad = MAGIC || version || salt || iter)`
 *  - `rawKey` = PBKDF2-HMAC-SHA512(password, salt, iter, 32 bytes)
 *  - KDF parameters are bound to the ciphertext via AAD: flipping `salt`/`iter` makes
 *    decryption fail-closed.
 *
 * The backup blob NEVER depends on the Keystore notes key (which does not survive an
 * uninstall): export decrypts each attachment from its Keystore envelope and the whole
 * payload is re-encrypted under the passphrase key; import re-encrypts each file under
 * the current notes key via `NoteRepository.addAttachment`.
 *
 * Encryption is mandatory: an empty passphrase is a validation failure.
 *
 * Size and memory: the payload is parsed whole, like `.smsbk`. The AEAD blob must be
 * decrypted whole anyway — GCM authenticates the full blob before any plaintext may be
 * trusted, and `AeadCipher` has no streaming API — so streaming the JSON parse would
 * save one copy at most while diverging from the proven `.smsbk` shape. The caps bound
 * the peak instead: 100 MB total file, 25 MB per attachment, 10,000 notes, 100,000
 * messages. Export enforces the reader's caps BEFORE writing, so a backup this app
 * writes is always a backup it can read back. Nothing is ever silently dropped to fit:
 * an oversize attachment fails the export loudly.
 *
 * The vault second-factor gate does NOT apply here: notes are not vault content. The
 * panic-decoy refusal DOES apply, same guard as `.smsbk`.
 */
@Singleton
class NotesBackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val noteRepository: NoteRepository,
    private val attachmentResolver: NoteAttachmentResolver,
    // Two deliberate DAO uses; the repository stays the write path for notes, messages
    // and attachment rows (see [importNotes] and [buildPayload] for why each exists).
    private val noteDao: NoteDao,
    private val noteAttachmentDao: NoteAttachmentDao,
    private val kdf: PasswordKdf,
    private val aead: AeadCipher,
    private val appLock: AppLockManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) : NotesBackupRestorer {

    override suspend fun restore(uriString: String, password: CharArray): Outcome<RestoreNotesResult> =
        readNotesBackup(Uri.parse(uriString), password)

    @Serializable
    data class NotesBackupHeader(
        val createdAt: Long,
        val app: String,
        val version: Int,
    )

    @Serializable
    data class NoteBackupEntry(
        val uuid: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val pinned: Boolean,
        val archived: Boolean,
        val bubbleColorArgb: Int?,
    )

    @Serializable
    data class NoteMessageBackupEntry(
        val uuid: String,
        val noteUuid: String,
        val body: String,
        val date: Long,
    )

    @Serializable
    data class NoteAttachmentBackupEntry(
        val uuid: String,
        val messageUuid: String,
        val mimeType: String,
        val fileName: String?,
        val sizeBytes: Long,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val dataBase64: String,
    )

    @Serializable
    data class NotesBackupPayload(
        val header: NotesBackupHeader,
        val notes: List<NoteBackupEntry>,
        val messages: List<NoteMessageBackupEntry>,
        val attachments: List<NoteAttachmentBackupEntry>,
    )

    /**
     * Strict JSON parser: unknown keys are rejected to prevent forged-field injection
     * (same rule as `.smsbk`).
     */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /**
     * Writes an encrypted `.notebk` to [uri]. The [password] CharArray is wiped on return,
     * on every path — the `finally` below wraps the whole export, so an early failure
     * (snapshot read, cap check) cannot leave it in memory.
     *
     * **Encryption is mandatory.** Pass an empty CharArray to get a `Validation`
     * failure — the call site is the only place where an explicit user passphrase enters
     * the pipeline, so we keep the contract strict.
     */
    suspend fun writeNotesBackup(uri: Uri, password: CharArray): Outcome<Uri> = withContext(io) {
        // Same panic-decoy guard as `.smsbk`: a decoy session must not walk away with
        // the notes, decryptable off-device with a passphrase of its own choosing.
        // The password is wiped here: the `finally` below sits inside the block we do
        // not reach on this path.
        if (appLock.state.value is AppLockManager.LockState.PanicDecoy) {
            password.wipe()
            return@withContext Outcome.Failure(AppError.Locked())
        }
        try {
            runCatchingOutcome(
                block = {
                    require(password.isNotEmpty()) { "password is required" }
                    val payload = buildPayload()
                    val plainBytes = json.encodeToString(NotesBackupPayload.serializer(), payload)
                        .toByteArray(Charsets.UTF_8)
                    val out = ByteArrayOutputStream()
                    val magic = MAGIC.toByteArray(Charsets.US_ASCII)
                    out.write(magic)
                    out.write(byteArrayOf(VERSION.toByte()))
                    val salt = kdf.newSalt()
                    val iter = kdf.calibrate()
                    val iterBytes = intToBytesBE(iter)
                    out.write(salt)
                    out.write(iterBytes)
                    val aad = ByteArrayOutputStream(magic.size + 1 + salt.size + iterBytes.size).apply {
                        write(magic)
                        write(byteArrayOf(VERSION.toByte()))
                        write(salt)
                        write(iterBytes)
                    }.toByteArray()
                    val rawKey = kdf.derive(password, salt, iter)
                    try {
                        when (val blob = aead.encryptRaw(rawKey, plainBytes, aad = aad)) {
                            is Outcome.Success -> out.write(blob.value)
                            is Outcome.Failure -> error("encrypt failed: ${blob.error}")
                        }
                    } finally {
                        rawKey.wipe()
                        plainBytes.fill(0)
                    }
                    val octets = out.toByteArray()
                    // The writer enforces its own reader's byte cap BEFORE opening the
                    // output stream: opening in "w" TRUNCATES the file, so failing after
                    // the open would destroy the previous backup for an unusable one.
                    if (octets.size > MAX_RESTORE_BYTES) {
                        error(
                            "backup would be ${octets.size} B, above the ${MAX_RESTORE_BYTES} B cap " +
                                "its own reader enforces",
                        )
                    }
                    val os = context.contentResolver.openOutputStream(uri, "w")
                        ?: error("openOutputStream returned null for backup URI")
                    os.use {
                        it.write(octets)
                        it.flush()
                    }
                    // Re-read what was written: a truncated file (disk full, killed
                    // process, document provider giving up) passes the header check —
                    // MAGIC, version, salt and iterations are written FIRST — and only
                    // fails at AEAD open, which the user reads as "wrong passphrase".
                    // The byte count is enough to detect it and costs nothing.
                    val written = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            var total = 0L
                            val buffer = ByteArray(READ_CHUNK_BYTES)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read <= 0) break
                                total += read
                            }
                            total
                        }
                    }.getOrNull()
                    if (written != null && written != octets.size.toLong()) {
                        error("backup written incompletely: $written B on disk, ${octets.size} B expected")
                    }
                    uri
                },
                errorMapper = { AppError.Storage(it) },
            )
        } finally {
            password.wipe()
        }
    }

    /**
     * Transactional snapshot (notes + messages + attachment UUIDs see one instant), then
     * attachment decryption OUTSIDE the transaction — crypto must not hold the DB lock.
     * A file deleted between the snapshot and its decrypt fails the export loudly; an
     * attachment is never silently dropped.
     *
     * The UUID map comes from the DAO because the domain `Attachment` model carries no
     * UUID (the mapper drops it): the export needs the UUID as the payload's dedup key,
     * and the filename convention (`fileForUuid`) is an implementation detail, not a
     * contract to read backwards. Read-only use; no write bypasses the repository.
     */
    private suspend fun buildPayload(): NotesBackupPayload {
        val (notes, messages, uuidByAttachmentId) = database.withTransaction {
            val allNotes = noteRepository.listAllNotes()
            val allMessages = noteRepository.listAllMessages()
            val uuids = noteAttachmentDao.listAll().associate { it.id to it.uuid }
            Triple(allNotes, allMessages, uuids)
        }
        // Count caps BEFORE decrypting any bytes: a hostile-sized snapshot fails fast,
        // without paying for attachment decryption first.
        if (notes.size > MAX_RESTORE_NOTES) {
            error(
                "backup holds ${notes.size} notes, above the " +
                    "$MAX_RESTORE_NOTES its own reader accepts",
            )
        }
        if (messages.size > MAX_RESTORE_MESSAGES) {
            error(
                "backup holds ${messages.size} messages, above the " +
                    "$MAX_RESTORE_MESSAGES its own reader accepts",
            )
        }
        val noteUuidById = notes.associate { it.id to it.uuid }
        val noteEntries = notes.map { note ->
            NoteBackupEntry(
                uuid = note.uuid,
                title = note.title,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                pinned = note.pinned,
                archived = note.archived,
                bubbleColorArgb = note.bubbleColorArgb,
            )
        }
        val messageEntries = messages.map { message ->
            val noteUuid = noteUuidById[message.noteId]
                ?: error("note ${message.noteId} missing from snapshot")
            NoteMessageBackupEntry(
                uuid = message.uuid,
                noteUuid = noteUuid,
                body = message.body,
                date = message.date,
            )
        }
        val attachmentEntries = ArrayList<NoteAttachmentBackupEntry>()
        for (message in messages) {
            for (attachment in message.attachments) {
                if (attachment.sizeBytes > MAX_ATTACHMENT_BYTES) {
                    error(
                        "attachment ${attachment.id} is ${attachment.sizeBytes} B, above the " +
                            "$MAX_ATTACHMENT_BYTES B cap",
                    )
                }
                val uuid = uuidByAttachmentId[attachment.id]
                    ?: error("attachment ${attachment.id} has no uuid in snapshot")
                val plaintext = when (val decrypted = attachmentResolver.decryptToMemory(attachment)) {
                    is Outcome.Success -> decrypted.value
                    is Outcome.Failure -> error(
                        "cannot decrypt attachment ${attachment.id}: ${decrypted.error}",
                    )
                }
                try {
                    if (plaintext.size.toLong() != attachment.sizeBytes) {
                        error(
                            "attachment ${attachment.id} decrypted to ${plaintext.size} B, " +
                                "expected ${attachment.sizeBytes} B",
                        )
                    }
                    attachmentEntries += NoteAttachmentBackupEntry(
                        uuid = uuid,
                        messageUuid = message.uuid,
                        mimeType = attachment.mimeType,
                        fileName = attachment.fileName,
                        sizeBytes = attachment.sizeBytes,
                        width = attachment.width,
                        height = attachment.height,
                        durationMs = attachment.durationMs,
                        dataBase64 = Base64.getEncoder().encodeToString(plaintext),
                    )
                } finally {
                    plaintext.wipe()
                }
            }
        }
        return NotesBackupPayload(
            header = NotesBackupHeader(
                createdAt = System.currentTimeMillis(),
                app = APP_TAG,
                version = VERSION,
            ),
            notes = noteEntries,
            messages = messageEntries,
            attachments = attachmentEntries,
        )
    }

    private fun intToBytesBE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    // ============================================================================
    // Restore .notebk: decrypt + atomic Room import.
    // ============================================================================

    /**
     * Reads, decrypts and imports a `.notebk` v1 file.
     *
     * **Format expected** (same as [writeNotesBackup]):
     *   `"NTBK"(4) || version(1) || salt(16) || iter(4 BE) || aeadBlob`
     * The `aeadBlob` is bound to `magic||version||salt||iter` via AAD, so any tampering
     * with the prefix fails decryption (fail-closed).
     *
     * **Conflict resolution** (all by UUID):
     *  - Notes: matched by `uuid`. On a match the existing row is KEPT as-is, but its
     *    messages are still merged. Otherwise INSERT new with the backed-up uuid and
     *    timestamps, so a round-trip is exact.
     *  - Messages: matched by `uuid`, insert-or-ignore.
     *  - Attachments: matched by `uuid`, insert-or-ignore; the file bytes are written
     *    re-encrypted under the current notes key via `NoteRepository.addAttachment`.
     *  - Re-importing the same file is a no-op.
     *
     * **Atomic import** ([AppDatabase.withTransaction]): a crash or kill mid-import
     * writes nothing (full rollback). One caveat: attachment FILES cannot be rolled
     * back by the database, so files written for rows a later failure rolls back are
     * deleted explicitly afterwards (see [importPayload]).
     *
     * **Security**:
     *  - File size cap (100 MB) to avoid OOM on a hostile file.
     *  - Strict JSON parser (`ignoreUnknownKeys = false`) to reject forged payloads.
     *  - `password` + `rawKey` wiped after use.
     *  - `AppError.Validation` typed for: invalid magic / unknown version / wrong
     *    password / malformed payload.
     *
     * The [password] CharArray is consumed (wiped) on return, success or failure.
     */
    suspend fun readNotesBackup(uri: Uri, password: CharArray): Outcome<RestoreNotesResult> =
        withContext(io) {
            // Same panic-decoy guard as the `.smsbk` restore: a forged backup restored
            // in decoy would return counts telling whether a note already existed — an
            // oracle, without reading a line.
            if (appLock.state.value is AppLockManager.LockState.PanicDecoy) {
                password.wipe()
                return@withContext Outcome.Failure(AppError.Locked())
            }
            try {
                runCatchingOutcome(
                    block = {
                        require(password.isNotEmpty()) { "password is required" }
                        val bytes = readBackupFile(uri)
                        val header = parseHeader(bytes)
                        val payload = decryptPayload(bytes, header, password)
                        importPayload(payload)
                    },
                    errorMapper = { throwable ->
                        // Preserve the error's nature for the UI: Validation (wrong
                        // format / password) vs Storage (read problem) — for a targeted
                        // message. `runCatchingOutcome` wraps exceptions; we look at
                        // the message.
                        val msg = throwable.message.orEmpty()
                        when {
                            msg.contains("invalid magic") ||
                                msg.contains("unsupported version") ||
                                msg.contains("decrypt failed") ||
                                msg.contains("invalid JSON") ||
                                msg.contains("invalid payload") ||
                                msg.contains("too large") -> AppError.Validation(msg)
                            else -> AppError.Storage(throwable)
                        }
                    },
                )
            } finally {
                // Password wiped on ALL paths — success, failure, and cancellation,
                // which `runCatchingOutcome` rethrows.
                password.wipe()
            }
        }

    /**
     * Chunked streaming read with a STRICT cap — a hostile file is cut off as soon as
     * it exceeds [MAX_RESTORE_BYTES], without allocating the rest first.
     */
    private fun readBackupFile(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("openInputStream returned null for restore URI")
        return input.use { stream ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            val out = ByteArrayOutputStream(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buf, 0, buf.size)
                if (read < 0) break
                total += read
                if (total > MAX_RESTORE_BYTES) {
                    error("backup file too large (>$MAX_RESTORE_BYTES bytes)")
                }
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    }

    /**
     * Header parsed after magic + version validation and salt + iter extraction.
     * Non-`data class` because a `data class` holding a `ByteArray` generates
     * reference-based `equals` / `hashCode` — a silent trap for any future use in a
     * `Set` or `Map`. Purely transient type, passed by value in the restore flow.
     */
    private class ParsedHeader(val salt: ByteArray, val iter: Int, val aad: ByteArray, val aeadOffset: Int)

    private fun parseHeader(bytes: ByteArray): ParsedHeader {
        val minLen = MAGIC.length + 1 + SALT_LEN + ITER_LEN
        if (bytes.size < minLen) error("invalid backup: too short for header")
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in magicBytes.indices) {
            if (bytes[i] != magicBytes[i]) error("invalid magic — not a Relay notes backup")
        }
        val version = bytes[MAGIC.length].toInt() and 0xFF
        if (version != VERSION) error("unsupported version $version (expected $VERSION)")
        val salt = bytes.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_LEN)
        val iterOffset = MAGIC.length + 1 + SALT_LEN
        val iter = bytesBEToInt(bytes, iterOffset)
        if (iter < KDF_ITER_MIN || iter > KDF_ITER_MAX) {
            error("invalid KDF iteration count $iter (out of $KDF_ITER_MIN..$KDF_ITER_MAX)")
        }
        val aeadOffset = iterOffset + ITER_LEN
        val aad = bytes.copyOfRange(0, aeadOffset)
        return ParsedHeader(salt = salt, iter = iter, aad = aad, aeadOffset = aeadOffset)
    }

    private fun decryptPayload(bytes: ByteArray, header: ParsedHeader, password: CharArray): NotesBackupPayload {
        val aeadBlob = bytes.copyOfRange(header.aeadOffset, bytes.size)
        val rawKey = kdf.derive(password, header.salt, header.iter)
        val plain = try {
            when (val out = aead.decryptRaw(rawKey, aeadBlob, aad = header.aad)) {
                is Outcome.Success -> out.value
                is Outcome.Failure -> error("decrypt failed — wrong passphrase or corrupted file")
            }
        } finally {
            rawKey.wipe()
        }
        val payload = try {
            json.decodeFromString(NotesBackupPayload.serializer(), plain.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            // Log the exception TYPE only, not the throwable itself — the message
            // could carry a payload fragment in a debug build.
            Timber.w(
                "NotesBackupService.readNotesBackup: invalid JSON payload " +
                    "(${t::class.simpleName})",
            )
            error("invalid JSON payload")
        } finally {
            // Wipe plaintext bytes ASAP — they may hold note content.
            // Note: the UTF-8 `String` derived from them stays in heap (immutable JVM,
            // non-wipeable) — the accepted in-process limitation, same as `.smsbk`.
            plain.fill(0)
        }
        // Hard caps on the deserialized volume, to block a forged file carrying
        // millions of empty entities (import-loop DoS).
        if (payload.notes.size > MAX_RESTORE_NOTES) {
            error("payload too large: ${payload.notes.size} notes")
        }
        if (payload.messages.size > MAX_RESTORE_MESSAGES) {
            error("payload too large: ${payload.messages.size} messages")
        }
        for (note in payload.notes) {
            if (note.uuid.isBlank() || note.title.isBlank()) {
                error("invalid payload: note with blank uuid or title")
            }
        }
        for (message in payload.messages) {
            if (message.uuid.isBlank() || message.noteUuid.isBlank()) {
                error("invalid payload: message with blank uuid or noteUuid")
            }
        }
        for (attachment in payload.attachments) {
            if (attachment.uuid.isBlank() || attachment.messageUuid.isBlank()) {
                error("invalid payload: attachment with blank uuid or messageUuid")
            }
            if (attachment.sizeBytes < 0 || attachment.sizeBytes > MAX_ATTACHMENT_BYTES) {
                error("payload too large: attachment of ${attachment.sizeBytes} B")
            }
        }
        return payload
    }

    /**
     * Running import counts, filled by the three import passes and returned as the
     * [RestoreNotesResult].
     */
    private class ImportTally {
        var notesReused: Int = 0
        var notesCreated: Int = 0
        var messagesImported: Int = 0
        var messagesSkipped: Int = 0
        var attachmentsImported: Int = 0
        var attachmentsSkipped: Int = 0
    }

    /**
     * Atomic import of the payload in Room. Wrapped in [AppDatabase.withTransaction] —
     * either everything lands or the rollback takes it all back. No intermediate state.
     *
     * The one thing a transaction cannot undo is a FILE: `addAttachment` encrypts and
     * writes the file before inserting its row, so a failure late in the import would
     * leave ciphertext files whose rows the rollback removed. Every file written here
     * is tracked, and a failed import deletes them again (best effort). The files are
     * ciphertext under the notes key, so a plain delete is enough — there is no
     * plaintext to wipe.
     */
    private suspend fun importPayload(payload: NotesBackupPayload): RestoreNotesResult {
        val tally = ImportTally()
        val writtenFiles = ArrayList<String>()
        try {
            database.withTransaction {
                val noteIds = importNotes(payload, tally)
                val messageIds = importMessages(payload, noteIds, tally)
                importAttachments(payload, messageIds, tally, writtenFiles)
            }
        } catch (t: Throwable) {
            writtenFiles.forEach { path -> runCatching { File(path).delete() } }
            throw t
        }
        return RestoreNotesResult(
            notesReused = tally.notesReused,
            notesCreated = tally.notesCreated,
            messagesImported = tally.messagesImported,
            messagesSkipped = tally.messagesSkipped,
            attachmentsImported = tally.attachmentsImported,
            attachmentsSkipped = tally.attachmentsSkipped,
        )
    }

    /**
     * Notes first: returns the local Room id per backed-up UUID. On a UUID match the
     * existing row is KEPT as-is — the backup never overwrites a live note's title or
     * flags — but its messages are still merged by [importMessages].
     *
     * The pinned / archived / bubble-color flags go through the DAO, not the
     * repository setters: the repository stamps `updated_at` with now, which would
     * corrupt the round-trip (import must restore the backed-up `updatedAt` exactly).
     * These three columns touch neither the denormalized preview nor the attachment
     * files, so no repository invariant is involved — and the DAO takes `updatedAt`
     * as a parameter for exactly this. Only non-default values are written.
     */
    private suspend fun importNotes(
        payload: NotesBackupPayload,
        tally: ImportTally,
    ): Map<String, Long> {
        val noteIds = HashMap<String, Long>(payload.notes.size)
        for (entry in payload.notes) {
            val existing = noteRepository.findNoteByUuid(entry.uuid)
            if (existing != null) {
                noteIds[entry.uuid] = existing.id
                tally.notesReused++
            } else {
                val created = noteRepository.createNote(
                    entry.title,
                    entry.uuid,
                    entry.createdAt,
                    entry.updatedAt,
                )
                if (entry.pinned) noteDao.setPinned(created.id, true, entry.updatedAt)
                if (entry.archived) noteDao.setArchived(created.id, true, entry.updatedAt)
                if (entry.bubbleColorArgb != null) {
                    noteDao.setBubbleColor(created.id, entry.bubbleColorArgb, entry.updatedAt)
                }
                noteIds[entry.uuid] = created.id
                tally.notesCreated++
            }
        }
        return noteIds
    }

    /**
     * Messages second: returns the local Room id per backed-up UUID (new rows and
     * pre-existing rows alike, so [importAttachments] can attach to either). A message
     * whose note is absent from the backup is skipped defensively — never create a
     * phantom note from a message.
     */
    private suspend fun importMessages(
        payload: NotesBackupPayload,
        noteIds: Map<String, Long>,
        tally: ImportTally,
    ): Map<String, Long> {
        val messageIds = HashMap<String, Long>(payload.messages.size)
        for (entry in payload.messages) {
            val existing = noteRepository.findMessageByUuid(entry.uuid)
            val noteId = noteIds[entry.noteUuid]
            if (existing != null) {
                messageIds[entry.uuid] = existing.id
            }
            if (existing != null || noteId == null) {
                tally.messagesSkipped++
                continue
            }
            val inserted = noteRepository.insertMessage(noteId, entry.body, entry.uuid, entry.date)
            messageIds[entry.uuid] = inserted.id
            tally.messagesImported++
        }
        return messageIds
    }

    /**
     * Attachments last. Each payload entry is base64-decoded, checked against its
     * declared `sizeBytes` (a mismatch means a forged or corrupt payload — fail-closed),
     * then stored re-encrypted under the current notes key. An `addAttachment` failure
     * (notes key lost, I/O error) aborts the WHOLE import: a note restored without its
     * photos would be a silent loss, and the transaction rolls the rest back.
     *
     * The base64 length is checked BEFORE decoding, so a forged entry claiming a small
     * size with a huge blob is refused without allocating the decoded bytes.
     */
    private suspend fun importAttachments(
        payload: NotesBackupPayload,
        messageIds: Map<String, Long>,
        tally: ImportTally,
        writtenFiles: MutableList<String>,
    ) {
        for (entry in payload.attachments) {
            val existing = noteRepository.findAttachmentByUuid(entry.uuid)
            val messageId = messageIds[entry.messageUuid]
            if (existing != null || messageId == null) {
                // Orphan: the message is absent from the backup (or was itself
                // orphan-skipped). Skip defensively. A row that already exists
                // skips the same way.
                tally.attachmentsSkipped++
                continue
            }
            if (entry.dataBase64.length > MAX_ATTACHMENT_BASE64_LENGTH) {
                error(
                    "payload too large: attachment ${entry.uuid} exceeds the " +
                        "$MAX_ATTACHMENT_BYTES B cap",
                )
            }
            val plaintext = try {
                Base64.getDecoder().decode(entry.dataBase64)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "NotesBackupService.importAttachments: attachment ${entry.uuid} is not valid base64")
                error("invalid payload: attachment ${entry.uuid} is not valid base64")
            }
            try {
                if (plaintext.size.toLong() != entry.sizeBytes) {
                    error(
                        "invalid payload: attachment ${entry.uuid} decodes to ${plaintext.size} B, " +
                            "expected ${entry.sizeBytes} B",
                    )
                }
                when (
                    val added = noteRepository.addAttachment(
                        messageId,
                        entry.mimeType,
                        entry.fileName,
                        plaintext,
                        entry.width,
                        entry.height,
                        entry.durationMs,
                        entry.uuid,
                    )
                ) {
                    is Outcome.Success -> {
                        writtenFiles += added.value.localUri
                        tally.attachmentsImported++
                    }
                    is Outcome.Failure -> error(
                        "cannot store attachment ${entry.uuid}: ${added.error}",
                    )
                }
            } finally {
                plaintext.wipe()
            }
        }
    }

    /** Big-endian Int read at [offset] (4 bytes). Inverse of [intToBytesBE]. */
    private fun bytesBEToInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    companion object {
        const val MAGIC = "NTBK"
        const val VERSION = 1
        private const val APP_TAG = "Relay"

        // Caps (design section 7.1): the total file cap is larger than `.smsbk`'s
        // 50 MB because note backups carry photo/file bytes inline as base64.
        private const val MAX_RESTORE_BYTES = 100 * 1024 * 1024
        private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
        private const val MAX_RESTORE_NOTES = 10_000
        private const val MAX_RESTORE_MESSAGES = 100_000
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val SALT_LEN = 16
        private const val ITER_LEN = 4

        /**
         * Base64 length of a [MAX_ATTACHMENT_BYTES] payload, plus slack for padding:
         * checked BEFORE decoding so a forged oversized blob is refused without
         * allocating the decoded bytes.
         */
        private const val MAX_ATTACHMENT_BASE64_LENGTH = (MAX_ATTACHMENT_BYTES + 2) / 3 * 4 + 4

        // KDF bounds: single source of truth is PasswordKdf (same fix as `.smsbk`).
        private val KDF_ITER_MIN = PasswordKdf.MIN_ITERATIONS
        private val KDF_ITER_MAX = PasswordKdf.MAX_ITERATIONS
    }
}
