package com.filestech.sms.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.filestech.sms.data.local.datastore.ViewerChoiceStore
import com.filestech.sms.data.local.datastore.ViewerChoices
import com.filestech.sms.data.local.datastore.ViewerKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * Round 33 — the remembered viewers: per kind, clearable one by
 * one or all at once. Plain JVM (no Robolectric): the store takes
 * its DataStore directly, so each test gets its own file — the
 * same shape as `magasinDeTest`, without the JUnit 4 rule.
 */
class ViewerChoiceStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `starts with nothing remembered`() {
        runBlocking {
            assertThat(store().flow.first()).isEqualTo(ViewerChoices())
        }
    }

    @Test
    fun `remember stores per kind`() {
        runBlocking {
            val store = store()
            store.remember(ViewerKind.IMAGE, "com.example.gallery")
            store.remember(ViewerKind.VIDEO, "com.example.player")
            assertThat(store.flow.first()).isEqualTo(
                ViewerChoices(image = "com.example.gallery", video = "com.example.player"),
            )
        }
    }

    @Test
    fun `remembering again replaces the viewer for that kind`() {
        runBlocking {
            val store = store()
            store.remember(ViewerKind.IMAGE, "com.example.gallery")
            store.remember(ViewerKind.IMAGE, "com.example.photos")
            assertThat(store.flow.first().image).isEqualTo("com.example.photos")
        }
    }

    @Test
    fun `clear forgets one kind only`() {
        runBlocking {
            val store = store()
            store.remember(ViewerKind.IMAGE, "com.example.gallery")
            store.remember(ViewerKind.AUDIO, "com.example.audio")
            store.clear(ViewerKind.IMAGE)
            assertThat(store.flow.first()).isEqualTo(
                ViewerChoices(audio = "com.example.audio"),
            )
        }
    }

    @Test
    fun `clearAll forgets everything`() {
        runBlocking {
            val store = store()
            store.remember(ViewerKind.IMAGE, "com.example.gallery")
            store.remember(ViewerKind.VIDEO, "com.example.player")
            store.remember(ViewerKind.AUDIO, "com.example.audio")
            store.clearAll()
            assertThat(store.flow.first()).isEqualTo(ViewerChoices())
        }
    }

    @Test
    fun `anyRemembered is false only when nothing is stored`() {
        runBlocking {
            val store = store()
            assertThat(store.flow.first().anyRemembered).isFalse()
            store.remember(ViewerKind.AUDIO, "com.example.audio")
            assertThat(store.flow.first().anyRemembered).isTrue()
        }
    }

    private fun store(): ViewerChoiceStore {
        val file = File(tempDir, "test-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        return ViewerChoiceStore(dataStore)
    }
}
