package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Group-send own-number fix — just-in-time detection and fail-fast
 * on the two group-MMS send paths.
 *
 * The router ([EnvoyerMessageUseCase]) covers text and media group
 * sends — immediate, scheduled, notification-reply and
 * desktop-initiated alike — while [SendVoiceMmsUseCase] covers
 * group voice sends, which bypass the router. Both guards sit
 * BEFORE any mirror row is created: a still-blank number after
 * detection must leave no PENDING row behind to stick on "sending".
 *
 * The detection itself is faked here ([FakeOwnNumberEnsurer]): what
 * these tests prove is the guard — proceed on `true`, fail fast with
 * [AppError.OwnNumberMissing] on `false`, and never consult the
 * ensurer at all off the group-MMS path. The ensurer's own rules
 * (when detection runs, what a throw means) are pinned in
 * `OwnNumberEnsurerImplTest` in `:app`.
 */
class OwnNumberGroupSendTest {

    private class GroupSettings(groupMms: Boolean) : AppSettingsSource {
        private val value = AppSettings().let { it.copy(sending = it.sending.copy(groupMms = groupMms)) }
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private val sms = RecordingSender()
    private val mms = RecordingMmsDispatcher()
    private val miroir = NoopMirror()
    private val ensurer = FakeOwnNumberEnsurer()

    private fun routeur(groupMms: Boolean): EnvoyerMessageUseCase {
        val reglages = GroupSettings(groupMms)
        return EnvoyerMessageUseCase(
            sendSms = sendSmsUseCase(settings = reglages, sender = sms, mirror = miroir),
            sendMediaMms = SendMediaMmsUseCase(
                defaultAppManager = alwaysDefaultSmsApp(),
                blockedRepo = NeverBlocked(),
                mirror = miroir,
                sender = mms,
                settings = reglages,
                attachmentStore = PassthroughAttachmentStore(),
            ),
            settings = reglages,
            ownNumberEnsurer = ensurer,
        )
    }

    private fun voix(): SendVoiceMmsUseCase {
        return SendVoiceMmsUseCase(
            defaultAppManager = alwaysDefaultSmsApp(),
            blockedRepo = NeverBlocked(),
            mirror = miroir,
            sender = mms,
            settings = GroupSettings(groupMms = true),
            attachmentStore = PassthroughAttachmentStore(),
            ownNumberEnsurer = ensurer,
        )
    }

    private val groupe = listOf(PhoneAddress.of("+33600000001"), PhoneAddress.of("+33600000002"))
    private val seul = listOf(PhoneAddress.of("+33600000001"))

    private fun clip(dir: File) = File(dir, "clip.m4a").apply { writeBytes(ByteArray(64) { 1 }) }

    // ───────────────────────── Router (text / media) ─────────────────────────

    @Test
    fun `group text, just-in-time detection fills the blank number - send proceeds as one group MMS`() {
        runBlocking {
            ensurer.result = true
            val res = routeur(groupMms = true).invoke(groupe, "salut")

            assertThat(res).isInstanceOf(Outcome.Success::class.java)
            assertThat(ensurer.calls).isEqualTo(1)
            assertThat(mms.appels).isEqualTo(1)
            assertThat(mms.destinatairesTentes).containsExactly("+33600000001", "+33600000002")
            // The single group mirror row exists — the send really went out.
            assertThat(miroir.lignes).hasSize(1)
            assertThat(sms.sentTexts).isEmpty()
        }
    }

    @Test
    fun `group text, detection finds nothing - fails fast with OwnNumberMissing, nothing sent, no mirror row`() {
        runBlocking {
            ensurer.result = false
            val res = routeur(groupMms = true).invoke(groupe, "salut")

            assertThat(res).isInstanceOf(Outcome.Failure::class.java)
            assertThat((res as Outcome.Failure).error).isEqualTo(AppError.OwnNumberMissing)
            assertThat(ensurer.calls).isEqualTo(1)
            assertThat(mms.appels).isEqualTo(0)
            assertThat(sms.sentTexts).isEmpty()
            // No PENDING row anywhere: nothing can stick on "sending".
            assertThat(miroir.lignes).isEmpty()
            assertThat(miroir.lignesMms).isEmpty()
            assertThat(miroir.echos).isEmpty()
        }
    }

    @Test
    fun `1-1 text with a blank number - no detection run, SMS proceeds`() {
        runBlocking {
            // Would fail the send if consulted: proves the guard never ran.
            ensurer.result = false
            val res = routeur(groupMms = true).invoke(seul, "salut")

            assertThat(res).isInstanceOf(Outcome.Success::class.java)
            assertThat(ensurer.calls).isEqualTo(0)
            assertThat(sms.sentTexts).hasSize(1)
            assertThat(mms.appels).isEqualTo(0)
        }
    }

    @Test
    fun `group text with group MMS setting off - no detection run, SMS per recipient`() {
        runBlocking {
            ensurer.result = false
            val res = routeur(groupMms = false).invoke(groupe, "salut")

            assertThat(res).isInstanceOf(Outcome.Success::class.java)
            assertThat(ensurer.calls).isEqualTo(0)
            assertThat(sms.sentTexts).hasSize(2)
            assertThat(mms.appels).isEqualTo(0)
        }
    }

    // ───────────────────────── Voice (bypasses the router) ─────────────────────────

    @Test
    fun `group voice, just-in-time detection fills the blank number - proceeds as one group MMS`(
        @TempDir dir: File,
    ) {
        runBlocking {
            ensurer.result = true
            val res = voix().invoke(
                recipients = groupe,
                audioFile = clip(dir),
                mimeType = "audio/mp4",
                durationMs = 3_000L,
                groupMms = true,
            )

            assertThat(res).isInstanceOf(Outcome.Success::class.java)
            assertThat(ensurer.calls).isEqualTo(1)
            assertThat(mms.appels).isEqualTo(1)
            assertThat(mms.destinatairesTentes).containsExactly("+33600000001", "+33600000002")
            assertThat(miroir.lignes).hasSize(1)
        }
    }

    @Test
    fun `group voice, detection finds nothing - fails fast, no mirror row, dispatcher not called`(
        @TempDir dir: File,
    ) {
        runBlocking {
            ensurer.result = false
            val res = voix().invoke(
                recipients = groupe,
                audioFile = clip(dir),
                mimeType = "audio/mp4",
                durationMs = 3_000L,
                groupMms = true,
            )

            assertThat(res).isInstanceOf(Outcome.Failure::class.java)
            assertThat((res as Outcome.Failure).error).isEqualTo(AppError.OwnNumberMissing)
            assertThat(ensurer.calls).isEqualTo(1)
            assertThat(mms.appels).isEqualTo(0)
            assertThat(miroir.lignes).isEmpty()
            assertThat(miroir.lignesMms).isEmpty()
        }
    }

    @Test
    fun `1-1 voice with a blank number - no detection run, voice MMS proceeds`(@TempDir dir: File) {
        runBlocking {
            ensurer.result = false
            val res = voix().invoke(
                recipients = seul,
                audioFile = clip(dir),
                mimeType = "audio/mp4",
                durationMs = 3_000L,
                groupMms = true,
            )

            assertThat(res).isInstanceOf(Outcome.Success::class.java)
            assertThat(ensurer.calls).isEqualTo(0)
            assertThat(mms.appels).isEqualTo(1)
        }
    }
}
