package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.backup.NotesBackupRestorer
import com.filestech.sms.domain.backup.RestoreNotesResult
import javax.inject.Inject

/**
 * Restore d'une sauvegarde de notes chiffrée `.notebk`. Miroir de [RestoreBackupUseCase]
 * pour les notes.
 *
 * Pure orchestration : le déchiffrement, l'import Room et la transaction atomique vivent dans
 * l'implémentation de [NotesBackupRestorer]. Le UseCase est ici pour donner au ViewModel un point
 * d'entrée stable (le service est implementation detail) et pour aligner sur le pattern projet
 * (one UseCase = one user-intent). La source est reçue sous forme d'URI chaîne (l'UI fournit
 * `uri.toString()`), pour que `domain/` reste sans import `android.net.Uri`.
 */
class RestoreNotesBackupUseCase @Inject constructor(
    private val notesBackupRestorer: NotesBackupRestorer,
) {
    suspend operator fun invoke(uriString: String, password: CharArray): Outcome<RestoreNotesResult> =
        notesBackupRestorer.restore(uriString, password)
}
