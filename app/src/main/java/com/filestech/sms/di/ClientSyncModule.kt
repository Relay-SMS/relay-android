package com.filestech.sms.di

import android.content.Context
import android.os.Build
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.clientmode.ClientAvatarGateway
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.sync.client.AndroidDeviceIdleMonitor
import com.filestech.sms.sync.client.AndroidNetworkAvailabilityMonitor
import com.filestech.sms.sync.client.ClientActionSender
import com.filestech.sms.sync.client.ClientConnectionManager
import com.filestech.sms.sync.client.ClientContactSync
import com.filestech.sms.sync.client.ClientIdentityStore
import com.filestech.sms.sync.client.ClientLinkStore
import com.filestech.sms.sync.client.ClientMirror
import com.filestech.sms.sync.client.ClientSendManager
import com.filestech.sms.sync.client.ClientSyncChannel
import com.filestech.sms.sync.client.ConversationMirrorAdapter
import com.filestech.sms.sync.client.DataStoreSyncPauseStore
import com.filestech.sms.sync.client.DeviceIdleMonitor
import com.filestech.sms.sync.client.NetworkAvailabilityMonitor
import com.filestech.sms.sync.client.NoteMirror
import com.filestech.sms.sync.client.NoteMirrorAdapter
import com.filestech.sms.sync.client.RoomSyncStateStore
import com.filestech.sms.sync.client.SyncPauseStore
import com.filestech.sms.sync.client.SyncStateStore
import com.filestech.sms.sync.client.TofuPinStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Client mode — the Hilt bindings for the linked-device graph.
 *
 * Only the two file-backed stores need providers here: their
 * constructor takes the client directory (and, for the identity, the
 * display-name default), which Hilt cannot invent. Everything else
 * in `sync.client` is `@Inject`-constructed (`ClientLinkStore`,
 * `ClientTransport`, `NsdDiscovery`, `ClientConnectionManager`,
 * `AutoLinker`, `ClientCacheWiper`).
 *
 * The client graph is deliberately lazy from the hub's side: the
 * entry points that gate on the mode (`MainApplication`,
 * `MainActivity`, `BootReceiver`) hold the client types in
 * `dagger.Lazy` (or not at all), so a hub phone never pays for the
 * client graph at startup — and the client service is the only
 * entry point that eagerly holds client types.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClientSyncModule {

    /**
     * The client directory: `filesDir/sync-client/`, app-private,
     * holding the device key seed and the TOFU pin next to each other
     * (the desktop's `keys.rs` layout). Created on first provision.
     */
    private fun clientDir(context: Context): File =
        File(context.filesDir, "sync-client").also { it.mkdirs() }

    @Provides
    @Singleton
    fun provideTofuPinStore(@ApplicationContext context: Context): TofuPinStore =
        TofuPinStore(clientDir(context))

    /**
     * The device identity store. The display-name default is the
     * model name (`Build.MODEL`) — the name the hub sees in its
     * device list; there is no edit UI in v1.
     */
    @Provides
    @Singleton
    fun provideClientIdentityStore(
        @ApplicationContext context: Context,
        linkStore: ClientLinkStore,
    ): ClientIdentityStore =
        ClientIdentityStore(clientDir(context), linkStore, Build.MODEL)

    /**
     * Client mode C2 — the engine talks to the connection through
     * the narrow [ClientSyncChannel] interface (so its tests can
     * use a fake). The manager is the only implementation.
     */
    @Provides
    @Singleton
    fun provideClientSyncChannel(manager: ClientConnectionManager): ClientSyncChannel = manager

    /**
     * The reconnect loop's network wakeup is the Android
     * implementation; the manager depends on the narrow
     * [NetworkAvailabilityMonitor] interface so its tests can use a
     * fake that signals availability on demand.
     */
    @Provides
    @Singleton
    fun provideNetworkAvailabilityMonitor(
        impl: AndroidNetworkAvailabilityMonitor,
    ): NetworkAvailabilityMonitor = impl

    /**
     * Pause/resume — the device-idle signal is the Android
     * implementation; the engine depends on the narrow
     * [DeviceIdleMonitor] interface so its tests can use a fake
     * that drives the idle + screen-on flows on demand.
     */
    @Provides
    @Singleton
    fun provideDeviceIdleMonitor(
        impl: AndroidDeviceIdleMonitor,
    ): DeviceIdleMonitor = impl

    /**
     * Pause/resume — the pause record is the DataStore-backed
     * one; the engine depends on the narrow [SyncPauseStore]
     * interface so its tests can use an in-memory fake.
     */
    @Provides
    @Singleton
    fun provideSyncPauseStore(impl: DataStoreSyncPauseStore): SyncPauseStore = impl

    /** Client mode C2 — the sync-state store is the Room-backed one. */
    @Provides
    @Singleton
    fun provideSyncStateStore(impl: RoomSyncStateStore): SyncStateStore = impl

    /** Client mode C2 — the client mirror is the `ConversationMirror` adapter. */
    @Provides
    @Singleton
    fun provideClientMirror(adapter: ConversationMirrorAdapter): ClientMirror = adapter

    /** Round 36 phase 2 — the note mirror is the `NoteRepository` adapter. */
    @Provides
    @Singleton
    fun provideNoteMirror(adapter: NoteMirrorAdapter): NoteMirror = adapter

    /** Client mode C3 — the send gateway is the send manager. */
    @Provides
    @Singleton
    fun provideClientSendGateway(impl: ClientSendManager): ClientSendGateway = impl

    /** Client mode C3 — the write-back gateway is the action sender. */
    @Provides
    @Singleton
    fun provideClientActionGateway(impl: ClientActionSender): ClientActionGateway = impl

    /** Client mode C4 — the lazy avatar gateway is the contact sync. */
    @Provides
    @Singleton
    fun provideClientAvatarGateway(impl: ClientContactSync): ClientAvatarGateway = impl
}
