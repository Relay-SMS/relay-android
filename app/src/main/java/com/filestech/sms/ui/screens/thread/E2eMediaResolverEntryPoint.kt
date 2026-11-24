package com.filestech.sms.ui.screens.thread

import com.filestech.sms.e2e.E2eMediaResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The Hilt entry point for [E2eMediaResolver], for the
 * encrypted-media thread bubble ([EncryptedMediaBubble]) — the
 * same pattern as `ViewerChoiceStoreEntryPoint`
 * ([com.filestech.sms.ui.components.rememberAttachmentOpener]).
 * The resolver cannot come through the ViewModel: its constructor
 * is at the Detekt 20-parameter limit.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface E2eMediaResolverEntryPoint {
    fun mediaResolver(): E2eMediaResolver
}
