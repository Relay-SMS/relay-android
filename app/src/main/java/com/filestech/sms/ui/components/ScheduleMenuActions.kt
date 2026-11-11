package com.filestech.sms.ui.components

/**
 * Round 13 — the two schedule actions the long-press menu on the send button
 * needs, bundled into one parameter so [ComposerBar] stays under the
 * 20-parameter Detekt limit. [onPresetSelected] schedules the draft for a preset
 * epoch through the existing `scheduleSend` path; [onPickDateTime] opens the
 * existing DatePicker → TimePicker scheduling flow.
 */
data class ScheduleMenuActions(
    val onPresetSelected: (Long) -> Unit,
    val onPickDateTime: () -> Unit,
)
