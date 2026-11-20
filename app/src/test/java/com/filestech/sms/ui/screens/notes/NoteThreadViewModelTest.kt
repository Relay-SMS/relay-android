package com.filestech.sms.ui.screens.notes

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.data.voice.VoiceRecorder
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Note
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.domain.pdf.PdfExportResult
import com.filestech.sms.domain.usecase.ExportNotePdfUseCase
import com.filestech.sms.ui.components.AttachmentKind
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Notes — the note thread ViewModel: saving (text / with staged
 * attachments / empty), deleting an entry, draft persistence,
 * load-older paging, rename / bubble color, PDF export, note
 * deletion, voice staging, playback failure, save-a-copy, and the
 * details-panel attachment pairing.
 *
 * Not covered here: the playback SUCCESS path calls
 * `Uri.fromFile` (an Android stub that throws on plain JVM), and
 * the contact-attachment path goes through the real
 * `ContactCardSource` — both need a device or Robolectric.
 */
class NoteThreadViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("note-vm-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun note(
        id: Long = NOTE_ID,
        title: String = "Groceries",
        draft: String? = null,
        bubbleColorArgb: Int? = null,
    ) = Note(
        id = id,
        uuid = UUID.randomUUID().toString(),
        title = title,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lastMessageAt = null,
        lastMessagePreview = null,
        draft = draft,
        pinned = false,
        archived = false,
        bubbleColorArgb = bubbleColorArgb,
    )

    private fun message(
        id: Long,
        body: String,
        date: Long = 2_000L + id,
        attachments: List<Attachment> = emptyList(),
    ) = NoteMessage(
        id = id,
        uuid = UUID.randomUUID().toString(),
        noteId = NOTE_ID,
        body = body,
        date = date,
        attachments = attachments,
    )

    private fun attachment(
        id: Long = 1L,
        messageId: Long = 1L,
        mimeType: String = "audio/3gpp",
    ) = Attachment(
        id = id,
        messageId = messageId,
        mimeType = mimeType,
        fileName = "clip.3gp",
        sizeBytes = 10L,
        localUri = "note://$id",
        width = null,
        height = null,
        durationMs = 1_200L,
    )

    private class Harness(
        val vm: NoteThreadViewModel,
        val repo: FakeNoteRepository,
        val resolver: NoteAttachmentResolver,
        val exportPdf: ExportNotePdfUseCase,
        val voiceRecorder: VoiceRecorder,
        val playbackController: VoicePlaybackController,
        val context: Context,
        val contentResolver: ContentResolver,
    )

    private fun harness(initialNote: Note? = note()): Harness {
        val repo = FakeNoteRepository(initialNote)
        val resolver = mockk<NoteAttachmentResolver>(relaxed = true)
        val exportPdf = mockk<ExportNotePdfUseCase>()
        val voiceRecorder = mockk<VoiceRecorder>()
        val playbackController = mockk<VoicePlaybackController>()
        every { playbackController.state } returns
            MutableStateFlow(VoicePlaybackController.PlaybackState())
        val contentResolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        every { context.cacheDir } returns tempDir
        every { context.contentResolver } returns contentResolver
        every { context.getString(any()) } returns "stub"
        val vm = NoteThreadViewModel(
            savedStateHandle = SavedStateHandle(mapOf("noteId" to NOTE_ID)),
            noteRepo = repo,
            resolver = resolver,
            exportPdf = exportPdf,
            voiceRecorder = voiceRecorder,
            playbackController = playbackController,
            context = context,
            io = dispatcher,
        )
        return Harness(vm, repo, resolver, exportPdf, voiceRecorder, playbackController, context, contentResolver)
    }

    /** Stages one fake image pick through the real `onAttachmentPicked` path. */
    private fun Harness.stageImage(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): Uri {
        val uri = mockk<Uri>()
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns true
        every { cursor.isNull(0) } returns false
        every { cursor.getLong(0) } returns bytes.size.toLong()
        every { cursor.getString(0) } returns "photo.jpg"
        every { cursor.close() } just Runs
        every { contentResolver.query(uri, any(), null, null, null) } returns cursor
        every { contentResolver.getType(uri) } returns "image/jpeg"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        vm.onAttachmentPicked(uri, AttachmentKind.PHOTO)
        return uri
    }

    @Test fun `save text inserts the entry and clears the draft`() = runTest(dispatcher) {
        val h = harness()
        h.vm.updateDraft("Buy milk")
        advanceUntilIdle()

        h.vm.save()
        advanceUntilIdle()

        assertThat(h.repo.messagesFlow.value).hasSize(1)
        assertThat(h.repo.messagesFlow.value[0].body).isEqualTo("Buy milk")
        assertThat(h.vm.state.value.draft).isEmpty()
        assertThat(h.repo.setDraftCalls.last()).isEqualTo(NOTE_ID to null)
    }

    @Test fun `save with a staged attachment stores the original bytes`() = runTest(dispatcher) {
        val h = harness()
        h.stageImage()
        advanceUntilIdle()
        assertThat(h.vm.state.value.pendingAttachments).hasSize(1)
        val stagedPath = h.vm.state.value.pendingAttachments[0].path
        assertThat(File(stagedPath).exists()).isTrue()

        h.vm.save()
        advanceUntilIdle()

        assertThat(h.repo.addAttachmentCalls).hasSize(1)
        val call = h.repo.addAttachmentCalls[0]
        assertThat(call.mimeType).isEqualTo("image/jpeg")
        assertThat(call.fileName).isEqualTo("photo.jpg")
        assertThat(call.plaintext).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(h.vm.state.value.pendingAttachments).isEmpty()
        assertThat(File(stagedPath).exists()).isFalse()
    }

    @Test fun `save with no text and no attachments inserts nothing`() = runTest(dispatcher) {
        val h = harness()

        h.vm.save()
        advanceUntilIdle()

        assertThat(h.repo.messagesFlow.value).isEmpty()
        assertThat(h.repo.addAttachmentCalls).isEmpty()
    }

    @Test fun `delete message removes it from the window`() = runTest(dispatcher) {
        val h = harness()
        h.repo.messagesFlow.value = listOf(message(1L, "one"), message(2L, "two"))
        advanceUntilIdle()
        assertThat(h.vm.state.value.messages).hasSize(2)

        h.vm.deleteMessage(1L)
        advanceUntilIdle()

        assertThat(h.vm.state.value.messages.map { it.id }).containsExactly(2L)
        assertThat(h.vm.state.value.totalCount).isEqualTo(1)
    }

    @Test fun `draft persists to the repository on every keystroke`() = runTest(dispatcher) {
        val h = harness()

        h.vm.updateDraft("hel")
        h.vm.updateDraft("hello")
        advanceUntilIdle()

        assertThat(h.vm.state.value.draft).isEqualTo("hello")
        assertThat(h.repo.setDraftCalls)
            .containsExactly(NOTE_ID to "hel", NOTE_ID to "hello").inOrder()
    }

    @Test fun `load older widens the window by one page`() = runTest(dispatcher) {
        val h = harness()
        h.repo.messagesFlow.value = (1L..60L).map { message(it, "entry $it") }
        advanceUntilIdle()
        assertThat(h.vm.state.value.messages).hasSize(50)
        assertThat(h.vm.state.value.hasMore).isTrue()
        assertThat(h.vm.state.value.totalCount).isEqualTo(60)

        h.vm.loadOlder()
        advanceUntilIdle()

        assertThat(h.vm.state.value.messages).hasSize(60)
        assertThat(h.vm.state.value.hasMore).isFalse()
    }

    @Test fun `rename trims and caps the title at 40 chars`() = runTest(dispatcher) {
        val h = harness()

        h.vm.renameNote("  New name  ")
        h.vm.renameNote("x".repeat(50))
        advanceUntilIdle()

        assertThat(h.repo.renameCalls[0]).isEqualTo(NOTE_ID to "New name")
        assertThat(h.repo.renameCalls[1].second).hasLength(40)
        assertThat(h.vm.state.value.note?.title).isEqualTo("x".repeat(40))
    }

    @Test fun `blank rename is ignored`() = runTest(dispatcher) {
        val h = harness()

        h.vm.renameNote("   ")
        advanceUntilIdle()

        assertThat(h.repo.renameCalls).isEmpty()
        assertThat(h.vm.state.value.note?.title).isEqualTo("Groceries")
    }

    @Test fun `setBubbleColor forwards to the repository`() = runTest(dispatcher) {
        val h = harness()

        h.vm.setBubbleColor(0xFF123456.toInt())
        advanceUntilIdle()

        assertThat(h.repo.colorCalls).containsExactly(NOTE_ID to 0xFF123456.toInt())
        assertThat(h.vm.state.value.note?.bubbleColorArgb).isEqualTo(0xFF123456.toInt())
    }

    @Test fun `export success emits PdfReady`() = runTest(dispatcher) {
        val h = harness()
        h.repo.messagesFlow.value = listOf(message(1L, "one"))
        advanceUntilIdle()
        val shareUri = "content://com.filestech.sms.fileprovider/exports/note.pdf"
        coEvery { h.exportPdf(NOTE_ID) } returns
            Outcome.Success(PdfExportResult(shareUri, 2))

        h.vm.events.test {
            h.vm.exportToPdf()
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(
                NoteThreadViewModel.Event.PdfReady(shareUri, 2),
            )
        }
    }

    @Test fun `export failure emits an error snackbar`() = runTest(dispatcher) {
        val h = harness()
        h.repo.messagesFlow.value = listOf(message(1L, "one"))
        advanceUntilIdle()
        coEvery { h.exportPdf(NOTE_ID) } returns
            Outcome.Failure(AppError.Validation("no messages to export"))

        h.vm.events.test {
            h.vm.exportToPdf()
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(
                NoteThreadViewModel.Event.ShowSnackbar("stub", isError = true),
            )
        }
    }

    @Test fun `deleting the note emits NoteDeleted and clears the state`() = runTest(dispatcher) {
        val h = harness()

        h.vm.events.test {
            h.vm.deleteNote()
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(NoteThreadViewModel.Event.NoteDeleted)
            assertThat(h.vm.state.value.note).isNull()
            assertThat(h.repo.deleteNoteCalls).isEqualTo(1)
        }
    }

    @Test fun `voice recording stages a clip on completion`() = runTest(dispatcher) {
        val h = harness()
        val voiceEvents = MutableSharedFlow<VoiceRecorder.Event>()
        every { h.voiceRecorder.record() } returns voiceEvents
        every { h.voiceRecorder.pruneOld() } just Runs

        h.vm.startRecording()
        voiceEvents.emit(VoiceRecorder.Event.Tick(500L, 10))
        advanceUntilIdle()
        assertThat(h.vm.state.value.voiceState).isEqualTo(
            NoteThreadViewModel.VoiceState.Recording(500L),
        )

        val clip = File(tempDir, "clip.3gp").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        voiceEvents.emit(
            VoiceRecorder.Event.Completed(
                VoiceRecorder.Result(clip, 1_200L, 3L, "audio/3gpp"),
                cappedByLimit = false,
            ),
        )
        advanceUntilIdle()

        assertThat(h.vm.state.value.voiceState).isEqualTo(NoteThreadViewModel.VoiceState.Idle)
        assertThat(h.vm.state.value.pendingAttachments).hasSize(1)
        assertThat(h.vm.state.value.pendingAttachments[0].durationMs).isEqualTo(1_200L)
        assertThat(h.vm.state.value.pendingAttachments[0].mimeType).isEqualTo("audio/3gpp")
        assertThat(clip.exists()).isFalse()
    }

    @Test fun `playback decrypt failure emits an error snackbar`() = runTest(dispatcher) {
        val h = harness()
        coEvery { h.resolver.decryptToPlaybackFile(any()) } returns
            Outcome.Failure(AppError.Crypto("key lost"))

        h.vm.events.test {
            h.vm.togglePlayback(attachment())
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(
                NoteThreadViewModel.Event.ShowSnackbar("stub", isError = true),
            )
        }
    }

    @Test fun `save-a-copy writes through the resolver`() = runTest(dispatcher) {
        val h = harness()
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        every { h.contentResolver.openOutputStream(target) } returns out
        coEvery { h.resolver.decryptToStream(any(), any()) } returns Outcome.Success(Unit)

        val saved = h.vm.saveAttachmentCopy(attachment(), target)

        assertThat(saved).isTrue()
    }

    @Test fun `panel attachments pair message date and body, newest first`() = runTest(dispatcher) {
        val h = harness()
        val old = message(1L, "old", date = 100L, attachments = listOf(attachment(id = 1L, messageId = 1L)))
        val new = message(2L, "new", date = 200L, attachments = listOf(attachment(id = 2L, messageId = 2L)))
        h.repo.messagesFlow.value = listOf(old, new)
        advanceUntilIdle()

        val paired = h.vm.state.value.panelAttachments
        assertThat(paired.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(paired[0].messageDate).isEqualTo(200L)
        assertThat(paired[0].messageBody).isEqualTo("new")
        assertThat(paired[1].messageDate).isEqualTo(100L)
    }

    companion object {
        private const val NOTE_ID: Long = 1L
    }
}
