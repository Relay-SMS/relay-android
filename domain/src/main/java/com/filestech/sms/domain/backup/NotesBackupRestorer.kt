package com.filestech.sms.domain.backup

import com.filestech.sms.core.result.Outcome

/**
 * Port domaine : restauration d'une sauvegarde de notes chiffrée `.notebk`.
 *
 * [com.filestech.sms.domain.usecase.RestoreNotesBackupUseCase] en dépend. L'implémentation
 * [com.filestech.sms.data.backup.NotesBackupService] gère le déchiffrement, l'import Room et
 * la transaction atomique. La source est passée sous forme d'URI **chaîne** (l'UI fournit
 * `uri.toString()`), de sorte que `domain/` reste sans import `android.net.Uri`.
 */
interface NotesBackupRestorer {

    /**
     * Lit, déchiffre et importe le fichier `.notebk` pointé par [uriString] avec [password].
     * Renvoie le bilan d'import, ou une [Outcome.Failure] (URI illisible, mot de passe erroné,
     * format invalide).
     */
    suspend fun restore(uriString: String, password: CharArray): Outcome<RestoreNotesResult>
}
