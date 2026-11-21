package com.filestech.sms.domain.clientmode

import java.io.File

/**
 * Phase 2 — one staged file for a client-mode note save: the staging
 * file (plaintext, in the note thread's staging directory) plus the
 * metadata the hub needs to rebuild the attachment row. The send
 * manager uploads the bytes (`kind: "note"`), inserts the local
 * attachment row, and sends the frame — the hub encrypts its own
 * copy from the uploaded bytes, so the local file is never the
 * source of truth past the upload.
 */
class NoteSaveAttachment(
    val file: File,
    val mimeType: String,
    val fileName: String?,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)
