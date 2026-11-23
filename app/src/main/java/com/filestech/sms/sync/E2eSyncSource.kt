package com.filestech.sms.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Round 42 — the seam between the sync engine and the phone's E2E
 * state (SYNC_PROTOCOL.md, E2E safety numbers + notices). The sync
 * package defines this contract and never sees Room, the DAOs, the
 * key material, or the probe manager: the implementation lives
 * outside the sync package and maps peer rows + notices to the
 * wire DTOs.
 *
 * A separate interface from [SyncMessageSource] on purpose: the E2E
 * store is a different subsystem (its own Room tables, its own
 * identity), and the engine's E2E surface — two full-set frames
 * plus two commands — deserves its own seam, like notes
 * ([NoteSyncSource]). The phone is the source of truth: the
 * desktop replaces its safety-number + notice sets wholesale on
 * every frame, and writes through the phone for verified flags
 * and probe retries.
 *
 * Verification is OPTIONAL (TOFU): messaging works encrypted
 * whether or not a peer is verified — the verified flag is just a
 * marker the user sets after comparing safety numbers out of
 * band. Nothing here gates sending on verification.
 *
 * All ids on the wire are decimal strings of the phone's Room ids.
 * The safety number crosses the link as the FORMATTED string (12
 * groups of 5 digits) — the desktop stores and displays it
 * verbatim, never re-deriving. The derivation itself lives in
 * exactly one place ([com.filestech.sms.e2e.E2eSafetyNumber]), so
 * the desktop number is byte-identical to the phone's.
 */
interface E2eSyncSource {

    /**
     * Every peer with a row, address order — the snapshot source
     * for `e2e_safety_numbers`. Peers without keys yet carry a
     * null [SyncedE2ePeer.safetyNumber]. Never creates an
     * identity: our keys are only loaded when at least one peer
     * holds keys AND an account already exists.
     */
    suspend fun safetyNumbers(): List<SyncedE2ePeer>

    /**
     * The live version of [safetyNumbers]: re-emits whenever any
     * peer row changes, so a state flip, a key arrival, or a
     * verified toggle re-sends the full set.
     */
    fun observeSafetyNumbers(): Flow<List<SyncedE2ePeer>>

    /**
     * Every in-thread E2E notice, oldest first — the snapshot
     * source for `e2e_notices`.
     */
    suspend fun notices(): List<SyncedE2eNotice>

    /**
     * The live version of [notices]: re-emits whenever any notice
     * row changes, so a notice recorded while a desktop is linked
     * re-sends the full set.
     */
    fun observeNotices(): Flow<List<SyncedE2eNotice>>

    /**
     * Round 48 — the file ids of newly stored media keys, in
     * arrival order. The engine re-pushes the owning conversation
     * on each arrival: a pending message becomes decryptable, and
     * the message table itself never changes on a key arrival, so
     * the conversation watch would not fire on its own.
     */
    fun observeMediaKeyArrivals(): Flow<String>

    /**
     * Round 48 — the conversation owning the pending message for
     * one media file id (via the part filename
     * `relay-media-<id>.bin`), or null when no message carries
     * that part yet (the key arrived before its MMS part — the
     * part's own arrival then pushes the message through the
     * normal new-message path).
     */
    suspend fun conversationForMediaKey(fileId: String): Long?

    /**
     * Applies `set_e2e_verified`: marks (or unmarks) the peer
     * verified in the phone's store. Creates the peer row when
     * none exists yet, like the phone's own sheet does. The
     * collector's re-send of the full set is the confirmation —
     * the desktop applies the broadcast, not just the ack.
     */
    suspend fun setVerified(address: String, verified: Boolean): ActionResult

    /**
     * Applies `retry_e2e_probe`: runs the manual probe for the
     * peer — the same one-probe-now path as the phone's Details
     * panel encryption-row tap (budget + discovery-toggle gates
     * lifted, address / blocked / ENCRYPTED gates still apply).
     * Answers `ok: false` `unknown_conversation` when no 1:1
     * conversation exists for the address, `send_failed` when the
     * probe could not go out.
     */
    suspend fun retryProbe(address: String): ActionResult
}

/**
 * Round 42 — one peer of the `e2e_safety_numbers` frame. [state]
 * is the lowercase wire string of the peer state (`"encrypted"`,
 * `"probing"`, `"unknown"`, `"not_relay"`); [safetyNumber] is the
 * formatted 60-digit number (12 groups of 5 digits), or null when
 * the peer has no keys yet. The desktop displays the string
 * verbatim — it never derives a number itself.
 */
@Serializable
data class SyncedE2ePeer(
    @SerialName("address") val address: String,
    @SerialName("state") val state: String,
    @SerialName("safety_number") val safetyNumber: String? = null,
    @SerialName("verified") val verified: Boolean,
)

/**
 * Round 42 — one notice of the `e2e_notices` frame. [kind] is the
 * lowercase wire string of the notice kind (`"activated"`,
 * `"key_changed"`, `"key_changed_verified"`, `"deactivated"`);
 * [date] is epoch millis — the notice's position in the thread.
 * Ids are decimal strings of the phone's Room ids, per the
 * protocol v1 convention.
 */
@Serializable
data class SyncedE2eNotice(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("kind") val kind: String,
    @SerialName("date") val date: Long,
)
