package com.filestech.sms.ui.screens.thread

/**
 * Round 41 — turns the IME inset (px) into the per-frame scroll delta that keeps the
 * thread glued to the keyboard while it slides in or out.
 *
 * The keyboard's slide is the system IME animation: the inset grows frame by frame
 * over ~300ms, and the composer rides it via `imePadding`. The message list has no
 * such ride — it must be scrolled by the same delta, each frame, so the content
 * moves WITH the keyboard instead of after it (the old fixed 320ms delay + animated
 * scroll read as slide, pause, snap). This is the frame-locked behavior a
 * bottom-anchored list gets for free from its layout; here it is reproduced by
 * driving the scroll offset from the inset deltas, because this list is not
 * reversed.
 *
 * The arithmetic lives here, alone and testable; the screen only applies it.
 * One instance per tracking collector — the previous inset is the collector's
 * own memory, not shared state.
 */
class ImeDeltaTracker {

    private var previousInsetPx: Int? = null

    /**
     * The px to scroll for the inset's move since the last call: positive while the
     * keyboard rises (scroll the content up by the same amount), negative while it
     * falls. The first observation returns 0 — there is no previous inset to measure
     * against, and treating "no previous" as 0 would scroll by the full keyboard
     * height when a thread opens with the keyboard already up. An unchanged inset
     * also returns 0.
     */
    fun onInsetChanged(currentInsetPx: Int): Float {
        val previous = previousInsetPx
        previousInsetPx = currentInsetPx
        if (previous == null || previous == currentInsetPx) return 0f
        return (currentInsetPx - previous).toFloat()
    }
}
