package com.filestech.sms.ui.components

import com.filestech.sms.data.local.datastore.ViewerKind
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 33 — the viewer-picker decision: when an open goes
 * straight to an app and when it raises the picker. The
 * remember/fallback behaviour lives here (the store itself is
 * covered by `ViewerChoiceStoreTest`).
 */
class ViewerPickerTest {

    private val gallery = ViewerApp("com.example.gallery", "Gallery", null)
    private val photos = ViewerApp("com.example.photos", "Photos", null)

    @Test
    fun `no handlers means no handler`() {
        assertThat(resolveViewerOpen(null, emptyList()))
            .isEqualTo(ViewerOpenDecision.NoHandler)
    }

    @Test
    fun `one handler opens directly without a picker`() {
        assertThat(resolveViewerOpen(null, listOf(gallery)))
            .isEqualTo(ViewerOpenDecision.Open("com.example.gallery"))
    }

    @Test
    fun `a remembered handler opens directly`() {
        assertThat(resolveViewerOpen("com.example.gallery", listOf(gallery, photos)))
            .isEqualTo(ViewerOpenDecision.Open("com.example.gallery"))
    }

    @Test
    fun `no remembered handler shows the picker`() {
        assertThat(resolveViewerOpen(null, listOf(gallery, photos)))
            .isEqualTo(ViewerOpenDecision.Pick(listOf(gallery, photos)))
    }

    @Test
    fun `a remembered app that is gone shows the picker again`() {
        // The remembered package was uninstalled: it is not among
        // the handlers, so the picker shows instead of a dead open.
        assertThat(resolveViewerOpen("com.example.gone", listOf(gallery, photos)))
            .isEqualTo(ViewerOpenDecision.Pick(listOf(gallery, photos)))
    }

    @Test
    fun `viewer kind follows the MIME type`() {
        assertThat(ViewerKind.fromMimeType("image/png")).isEqualTo(ViewerKind.IMAGE)
        assertThat(ViewerKind.fromMimeType("image/gif")).isEqualTo(ViewerKind.IMAGE)
        assertThat(ViewerKind.fromMimeType("video/mp4")).isEqualTo(ViewerKind.VIDEO)
        assertThat(ViewerKind.fromMimeType("audio/mpeg")).isEqualTo(ViewerKind.AUDIO)
        assertThat(ViewerKind.fromMimeType("application/pdf")).isNull()
        assertThat(ViewerKind.fromMimeType("text/vcard")).isNull()
    }
}
