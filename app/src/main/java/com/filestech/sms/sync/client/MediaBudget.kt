package com.filestech.sms.sync.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.filestech.sms.core.mms.MmsConstants
import com.filestech.sms.core.result.AppError
import com.filestech.sms.domain.usecase.SendMediaMmsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Client mode C5 — the media budget for wire sends: a port of the
 * desktop's `mediaPrep` rules (`mediaPrep.ts`), which mirror the
 * phone's own MMS prep — so the client prepares uploads to satisfy
 * the phone's sender, and the phone's guards stay the backstop.
 *
 * The phone's rules, restated (the client mirrors them exactly):
 * - Total payload cap: 280 KB, shared across the message's text +
 *   all attachments. A refusal uses the phone's own words
 *   ([AppError.ClientMmsCapReached] → the existing
 *   `snack_thread_attach_cap_reached` string), never new wording.
 * - Budget split (`partParImage`): the cap minus text bytes minus
 *   non-compressible attachments (video, audio, files — served
 *   first, never compressed), divided by the number of images; a
 *   share below 48 KB is a refusal.
 * - Photos are recompressed to fit: max dimension 1600 → 1024 →
 *   800 px, JPEG quality 75 → 60 → 45 → 30, first combination
 *   fitting the budget wins; nothing fitting is a refusal, not a
 *   silent send. A photo already under its share is sent as-is
 *   (original bytes and MIME — the phone does the same).
 * - GIFs are never JPEG-compressed (re-encoding kills the
 *   animation): as-is when they fit, refused when they don't.
 * - Video is never transcoded: it must fit the remaining budget
 *   as-is.
 *
 * The thread UI already compresses staged photos to their share
 * before they reach the send path, so [prepare] is usually a cheap
 * passthrough + the safety-net total check — the ladder only runs
 * for a file that arrived unprepared (or grew past its share when
 * the text grew after staging).
 *
 * The budget math ([partParImage]) is pure arithmetic and
 * unit-tested on the JVM; the JPEG ladder needs real Bitmaps, so on
 * the plain-JVM harness a photo over its share reports
 * [AppError.ClientPhotoPrepareFailed] (the decode stubs throw) —
 * the ladder itself is device-verified. Every Bitmap call sits
 * under `runCatching`, which catches `Throwable` (including
 * `OutOfMemoryError`), so a decode failure is a refusal, never a
 * crash — the same discipline as the thread's own compressor.
 */
object MediaBudget {

    /** The phone's carrier payload cap: 280 KB, shared by text + attachments. */
    const val MMS_PAYLOAD_CAP_BYTES: Long = MmsConstants.CARRIER_PAYLOAD_CAP_BYTES

    /** A per-image budget share below this is a refusal, not a send. */
    const val MIN_IMAGE_BYTES: Long = 48L * 1024L

    /**
     * One prepared attachment: the file to upload + its wire
     * metadata. [fileName] is the name the phone + the recipient
     * see: the staged file's name for a passthrough, the
     * recompressed file's name for a ladder output — never the
     * durable copy's internal `out-…` name (the send manager copies
     * [file] to durable storage, and [fileName] survives the copy).
     */
    data class PreparedItem(
        val file: File,
        val mimeType: String,
        val fileName: String,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
    )

    sealed interface PrepareResult {
        data class Ready(val items: List<PreparedItem>) : PrepareResult
        data class Refused(val error: AppError) : PrepareResult
    }

    private sealed interface PhotoResult {
        data class Prepared(val item: PreparedItem) : PhotoResult
        data class Refused(val error: AppError) : PhotoResult
    }

    /**
     * The phone's `MmsAttachmentBudget.partParImage`: the per-image
     * budget share — the 280 KB cap minus text bytes minus
     * non-compressible attachments, divided by the number of
     * images. Zero images share nothing (0); a negative share means
     * the text + files alone already exceed the cap.
     */
    fun partParImage(textBytes: Long, otherBytes: Long, imageCount: Int): Long {
        if (imageCount <= 0) return 0
        return (MMS_PAYLOAD_CAP_BYTES - textBytes - otherBytes) / imageCount
    }

    /**
     * Prepares every attachment against the phone's budget, in
     * order. A refusal returns BEFORE any optimistic row exists —
     * the composer shows the refusal and the staged items stay put.
     */
    suspend fun prepare(
        attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
        body: String,
    ): PrepareResult {
        val textBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        var otherBytes = 0L
        var imageCount = 0
        for (att in attachments) {
            if (isImage(att.mimeType)) imageCount++ else otherBytes += att.file.length()
        }
        shareRefusal(textBytes, otherBytes, imageCount)?.let {
            return PrepareResult.Refused(it)
        }
        val share = partParImage(textBytes, otherBytes, imageCount)
        val items = mutableListOf<PreparedItem>()
        for (att in attachments) {
            when (val one = prepareOne(att, share)) {
                is PhotoResult.Prepared -> items += one.item
                is PhotoResult.Refused -> return PrepareResult.Refused(one.error)
            }
        }
        // Safety net: the share math should guarantee the total, but
        // a prepared item is trusted only after this check.
        val total = textBytes + items.sumOf { it.file.length() }
        return if (total > MMS_PAYLOAD_CAP_BYTES) {
            PrepareResult.Refused(AppError.ClientMmsCapReached)
        } else {
            PrepareResult.Ready(items)
        }
    }

    /** The share gate: null when the send may proceed, the refusal otherwise. */
    private fun shareRefusal(textBytes: Long, otherBytes: Long, imageCount: Int): AppError? {
        val share = partParImage(textBytes, otherBytes, imageCount)
        return when {
            imageCount > 0 && share < MIN_IMAGE_BYTES -> AppError.ClientMmsCapReached
            imageCount == 0 && textBytes + otherBytes > MMS_PAYLOAD_CAP_BYTES ->
                AppError.ClientMmsCapReached
            else -> null
        }
    }

    private suspend fun prepareOne(
        att: SendMediaMmsUseCase.AttachmentPayload,
        share: Long,
    ): PhotoResult = when {
        isGif(att.mimeType) -> {
            // GIFs are never JPEG-compressed: as-is when they fit,
            // refused when they don't.
            if (att.file.length() > share) {
                PhotoResult.Refused(AppError.ClientMmsCapReached)
            } else {
                PhotoResult.Prepared(passthrough(att))
            }
        }
        isImage(att.mimeType) -> preparePhoto(att, share)
        // Video, audio, files: never transcoded, never recompressed
        // — served as-is, and the total check refuses what doesn't fit.
        else -> PhotoResult.Prepared(
            PreparedItem(att.file, att.mimeType, att.file.name, att.width, att.height, att.durationMs),
        )
    }

    /**
     * Prepares one photo for its budget share. A photo already under
     * its share is sent as-is; otherwise the phone's ladder runs.
     */
    private suspend fun preparePhoto(
        att: SendMediaMmsUseCase.AttachmentPayload,
        budget: Long,
    ): PhotoResult {
        if (att.file.length() <= budget) return PhotoResult.Prepared(passthrough(att))
        return recompress(att, budget)
    }

    /** A passthrough item, with dimensions decoded when the payload did not carry them. */
    private fun passthrough(att: SendMediaMmsUseCase.AttachmentPayload): PreparedItem {
        val dims = if (att.width == null || att.height == null) {
            decodeDimensions(att.file)
        } else {
            null
        }
        return PreparedItem(
            file = att.file,
            mimeType = att.mimeType,
            fileName = att.file.name,
            width = att.width ?: dims?.first,
            height = att.height ?: dims?.second,
            durationMs = att.durationMs,
        )
    }

    /**
     * Re-encodes the photo as JPEG until it fits [budget]: four
     * qualities at 1600 px, then 1024 px, then 800 px. The first fit
     * wins; nothing fitting is a cap refusal. A file that decodes at
     * no dimension is a prepare failure, not a cap refusal.
     *
     * Unlike the thread's own compressor, the source is NEVER
     * deleted here: the staged original stays put, so a failed send
     * can retry from the composer. The recompressed copy is a
     * sibling (`-client.jpg`); the send manager copies it to
     * durable storage, and the cache pruners reclaim the sibling.
     */
    private suspend fun recompress(
        att: SendMediaMmsUseCase.AttachmentPayload,
        budget: Long,
    ): PhotoResult = withContext(Dispatchers.IO) {
        val out = File(att.file.parentFile, att.file.nameWithoutExtension + "-client.jpg")
        var decodedAny = false
        for (maxDim in PHOTO_MAX_DIMS) {
            val scaled = decodeScaled(att.file, maxDim) ?: continue
            decodedAny = true
            try {
                for (quality in PHOTO_JPEG_QUALITIES) {
                    try {
                        out.outputStream().use { os ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality, os)
                        }
                        if (out.length() in 1..budget) {
                            return@withContext PhotoResult.Prepared(
                                PreparedItem(
                                    out,
                                    "image/jpeg",
                                    out.name,
                                    scaled.width,
                                    scaled.height,
                                    att.durationMs,
                                ),
                            )
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "MediaBudget: JPEG compress dim=%d quality=%d failed", maxDim, quality)
                    }
                }
            } finally {
                scaled.recycle()
            }
        }
        runCatching { out.delete() }
        if (decodedAny) {
            PhotoResult.Refused(AppError.ClientMmsCapReached)
        } else {
            PhotoResult.Refused(AppError.ClientPhotoPrepareFailed)
        }
    }

    /**
     * Decodes [src] scaled to [maxDim] at most, in two passes (the
     * thread's `decoderReduite`, same shape): the first pass reads
     * only the header, the second decodes near the wanted size, so
     * a 12 Mpx photo never allocates its full bitmap first. Null
     * when the file cannot be decoded at all.
     */
    private fun decodeScaled(src: File, maxDim: Int): Bitmap? {
        val opts = decodeOptions(src, maxDim) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(src.absolutePath, opts) }
            .getOrNull() ?: return null
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true,
            )
        }.getOrNull()
        bitmap.recycle()
        return scaled
    }

    /**
     * Bounds + sample-size options for [src], or null when the file
     * cannot be decoded at all. Guarded end to end: on the plain-JVM
     * harness the android.jar stubs throw from the Options
     * constructor itself, and on device a corrupt file can fail
     * anywhere here.
     */
    private fun decodeOptions(src: File, maxDim: Int): BitmapFactory.Options? {
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }
        }.getOrNull() ?: return null
        runCatching { BitmapFactory.decodeFile(src.absolutePath, bounds) }
        return runCatching {
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            }
        }.getOrNull()
    }

    /** Intrinsic dimensions of an image file, or null when undecodable (bounds-only, no pixels). */
    private fun decodeDimensions(file: File): Pair<Int, Int>? {
        // Guarded end to end (see decodeScaled): the Options
        // constructor itself throws on the plain-JVM harness.
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }
        }.getOrNull() ?: return null
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    /** The power-of-two downsample that lands near [maxDim] (the thread's `echantillonnagePour`). */
    internal fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun isImage(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true)

    private fun isGif(mimeType: String): Boolean =
        mimeType.equals("image/gif", ignoreCase = true)

    private val PHOTO_MAX_DIMS = intArrayOf(1600, 1024, 800)
    private val PHOTO_JPEG_QUALITIES = intArrayOf(75, 60, 45, 30)
}
