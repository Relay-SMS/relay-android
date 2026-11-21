package com.filestech.sms.data.local.db

import com.filestech.sms.data.local.db.entity.E2eAccountEntity
import com.filestech.sms.data.local.db.entity.E2eFragmentEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.data.local.db.entity.E2eSessionEntity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * E2E (schema v22) — the new entities and their type converters, on the plain JVM.
 *
 * Room itself cannot run here (no KSP-generated `AppDatabase_Impl` on the harness,
 * no SQLite on plain JVM): the DAO SQL is proven against a real database by the
 * CI-only `E2eDaoDbTest`, and the migration by `MigrationTest`. What CAN be proven
 * here is the shape Room will see — defaults, the single-row Account id, and the
 * enum converters (including the unknown-name fallback, which guards forward
 * compatibility with rows written by a future build).
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test` whose
 * body returns a value.
 */
class E2eEntitiesTest {

    private val converters = MessageEnumConverters()

    @Test
    fun `account entity defaults to the single-row id`() {
        val account = E2eAccountEntity(pickle = "pickle-bytes")

        assertThat(account.id).isEqualTo(E2eAccountEntity.SINGLE_ROW_ID)
        assertThat(account.id).isEqualTo(1)
    }

    @Test
    fun `peer entity defaults to UNKNOWN unverified with an empty probe budget`() {
        val peer = E2ePeerEntity(address = "+33612345678", updatedAt = 1_700_000_000_000L)

        assertThat(peer.state).isEqualTo(E2ePeerState.UNKNOWN)
        assertThat(peer.theirCurveKey).isNull()
        assertThat(peer.theirEdKey).isNull()
        assertThat(peer.verified).isFalse()
        assertThat(peer.lastProbeAt).isNull()
        assertThat(peer.probeAttempts).isEqualTo(0)
        assertThat(peer.windowStartedAt).isNull()
        assertThat(peer.lastBundleReplyAt).isNull()
    }

    @Test
    fun `session entity defaults to a non-current fallback`() {
        val session = E2eSessionEntity(
            address = "+33612345678",
            sessionId = "session-1",
            pickle = "pickle-bytes",
            createdAt = 1_700_000_000_000L,
            lastUsedAt = 1_700_000_000_000L,
        )

        assertThat(session.id).isEqualTo(0L)
        assertThat(session.isCurrent).isFalse()
    }

    @Test
    fun `fragment entity carries its set position`() {
        val fragment = E2eFragmentEntity(
            sender = "+33612345678",
            msgId = "ab12cd",
            idx = 1,
            total = 3,
            chunk = "YmFzZTY0",
            receivedAt = 1_700_000_000_000L,
        )

        assertThat(fragment.idx).isEqualTo(1)
        assertThat(fragment.total).isEqualTo(3)
    }

    @Test
    fun `notice entity defaults to an uninserted row`() {
        val notice = E2eNoticeEntity(
            conversationId = 7L,
            kind = E2eNoticeKind.ACTIVATED,
            date = 1_700_000_000_000L,
        )

        assertThat(notice.id).isEqualTo(0L)
    }

    @Test
    fun `peer state converter round-trips every state by name`() {
        for (state in E2ePeerState.entries) {
            assertThat(converters.e2ePeerStateFromRaw(converters.e2ePeerStateToRaw(state)))
                .isEqualTo(state)
        }
        assertThat(converters.e2ePeerStateToRaw(E2ePeerState.ENCRYPTED)).isEqualTo("ENCRYPTED")
    }

    @Test
    fun `peer state converter falls back to UNKNOWN on an unknown name or null`() {
        assertThat(converters.e2ePeerStateFromRaw("SOME_FUTURE_STATE"))
            .isEqualTo(E2ePeerState.UNKNOWN)
        assertThat(converters.e2ePeerStateFromRaw(null)).isEqualTo(E2ePeerState.UNKNOWN)
    }

    @Test
    fun `notice kind converter round-trips every kind by name`() {
        for (kind in E2eNoticeKind.entries) {
            assertThat(converters.e2eNoticeKindFromRaw(converters.e2eNoticeKindToRaw(kind)))
                .isEqualTo(kind)
        }
        assertThat(converters.e2eNoticeKindToRaw(E2eNoticeKind.KEY_CHANGED_VERIFIED))
            .isEqualTo("KEY_CHANGED_VERIFIED")
    }

    @Test
    fun `notice kind converter falls back to ACTIVATED on an unknown name or null`() {
        assertThat(converters.e2eNoticeKindFromRaw("SOME_FUTURE_KIND"))
            .isEqualTo(E2eNoticeKind.ACTIVATED)
        assertThat(converters.e2eNoticeKindFromRaw(null)).isEqualTo(E2eNoticeKind.ACTIVATED)
    }
}
