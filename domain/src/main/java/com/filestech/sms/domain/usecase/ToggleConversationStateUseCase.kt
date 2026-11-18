package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.clientmode.NoOpClientActionGateway
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.vault.VaultMover
import javax.inject.Inject

class ToggleConversationStateUseCase @Inject constructor(
    private val repo: ConversationRepository,
    private val vault: VaultMover,
    /**
     * Client mode C3 — the local block list (the optimistic change).
     * Hub mode keeps its own block path ([BlockNumberUseCase] in the
     * ViewModels); this use case never blocks in hub mode.
     */
    private val blockedRepo: BlockedNumberRepository,
    /**
     * Client mode C3 — the write-back gateway. Default NoOp keeps the
     * hub tests compiling untouched; Hilt always provides the real one.
     */
    private val clientActions: ClientActionGateway = NoOpClientActionGateway,
) {
    suspend fun setPinned(id: Long, pinned: Boolean) = repo.setPinned(id, pinned)
    suspend fun setArchived(id: Long, archived: Boolean) = repo.setArchived(id, archived)
    suspend fun setMuted(id: Long, muted: Boolean) = repo.setMuted(id, muted)

    /**
     * Long-press "Mark unread / Mark read" (desktop parity).
     * `unread = true` re-opens the latest incoming message
     * ([ConversationRepository.markUnread]); `false` is the
     * plain mark-read. Client mode: the unread mark is
     * local-only (no mark-unread frame exists); the read mark
     * still writes back to the hub like every other mark-read.
     */
    suspend fun setUnread(id: Long, unread: Boolean) =
        if (unread) repo.markUnread(id) else repo.markRead(id)

    /**
     * Client mode C3 — blocks (or unblocks) every address of a
     * conversation from a linked device: the local block list is
     * updated optimistically, then the `set_blocked` frame goes to
     * the hub phone, which blocks the numbers for real. The sender
     * runs the revert (un-blocks the addresses that actually changed)
     * when the frame cannot go out or the hub answers `ok:false`.
     *
     * Hub mode keeps its own block path ([BlockNumberUseCase], called
     * directly by the ViewModels) — this method refuses there, so the
     * two paths can never be mixed up.
     */
    suspend fun setBlocked(id: Long, blocked: Boolean): Outcome<Unit> {
        if (!clientActions.isClientMode()) {
            return Outcome.Failure(AppError.Validation("setBlocked is only available on a linked device"))
        }
        val conv = repo.findById(id) ?: return Outcome.Failure(AppError.NotFound("conversation"))
        // Only the addresses that actually change are tracked: an
        // address already in the target state must not be reverted
        // into the opposite state by a refused frame.
        val changed = mutableListOf<String>()
        for (address in conv.addresses) {
            if (blockedRepo.isBlocked(address.raw) == blocked) continue
            val outcome = if (blocked) {
                blockedRepo.block(address.raw, conv.displayName)
            } else {
                blockedRepo.unblock(address.raw)
            }
            if (outcome is Outcome.Success) changed += address.raw
        }
        val sent = clientActions.sendAction(ClientAction.SetBlocked(id, blocked)) {
            for (raw in changed) {
                if (blocked) blockedRepo.unblock(raw) else blockedRepo.block(raw)
            }
        }
        return if (sent) Outcome.Success(Unit) else Outcome.Failure(AppError.ClientOffline)
    }

    /** v1.28.3 — nommer un groupe ; la saisie brute est normalisée ici, une fois pour les deux entrées. */
    suspend fun rename(id: Long, raw: String?) =
        repo.setCustomName(id, com.filestech.sms.domain.model.GroupName.normalize(raw))

    /**
     * v1.11.0 — Sujet 5 apparence : couleur bulle sortante + avatar custom.
     * `null` sur un argument = reset au défaut (bleu marque / avatar contact).
     */
    suspend fun setAppearance(id: Long, bubbleColorArgb: Int?, avatarUri: String?) =
        repo.setAppearance(id, bubbleColorArgb, avatarUri)

    /**
     * Audit P0-1 (v1.2.0): vault toggling **must** go through [VaultMover] (impl
     * [com.filestech.sms.security.VaultManager]), which gates the
     * operation against the panic-decoy session. Earlier versions called `repo.moveToVault`
     * directly here, which short-circuited the guard — a coerced decoy session could hide
     * (or unhide) any conversation, defeating the whole point of the vault.
     *
     * Cette signature historique reste utilisée par [com.filestech.sms.ui.screens.vault.VaultScreen]
     * une fois sa session armée. Pour un appel "from outside" (long-press liste,
     * overflow ThreadScreen), utiliser [requestMoveToVault] qui check AppLockState
     * et auto-arme sessionUnlocked.
     */
    /**
     * v1.26.1 (audit F11) — RETIRÉE de l'API publique du use case.
     *
     * Recensement fait : zéro appelant. Les trois ViewModels passent tous par
     * [requestMoveToVault], qui refuse en session leurre. Cette variante-ci, elle, ne vérifie
     * rien : la laisser exposée à côté de sa jumelle gardée était un piège — le premier futur
     * appelant aurait contourné en silence la politique anti-leurre, c'est-à-dire rouvert le
     * trou que le CHANGELOG décrit comme « P0-1 Vault bypass closed ».
     *
     * Conservée en `private` plutôt que supprimée : c'est elle qui porte l'aiguillage
     * entrée/sortie, réutilisé par [requestMoveToVault].
     */
    private suspend fun moveToVault(id: Long, inVault: Boolean): Outcome<Unit> =
        if (inVault) vault.moveToVault(id) else vault.moveOutOfVault(id)

    /**
     * v1.11.0 — wrapper pour appels depuis l'extérieur de VaultScreen. Voir
     * [com.filestech.sms.security.VaultManager.requestMoveToVault] pour la politique de sécurité
     * (refus PanicDecoy + Locked ; v1.27.2 : plus d'auto-unlock de la session coffre, et la
     * SORTIE du coffre exige une session coffre déjà déverrouillée).
     */
    suspend fun requestMoveToVault(id: Long, intoVault: Boolean): Outcome<Unit> =
        vault.requestMoveToVault(id, intoVault)

    /**
     * v1.14.8 R8 — Bulk move atomique. Wrap [com.filestech.sms.security.VaultManager.requestBulkMoveToVault] qui
     * délègue à [ConversationRepository.bulkMoveToVault] (transaction Room).
     */
    suspend fun requestBulkMoveToVault(ids: List<Long>, intoVault: Boolean): Outcome<Int> =
        vault.requestBulkMoveToVault(ids, intoVault)

    suspend fun delete(id: Long) = repo.delete(id)
}
