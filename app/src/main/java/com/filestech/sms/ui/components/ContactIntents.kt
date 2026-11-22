package com.filestech.sms.ui.components

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import timber.log.Timber

/**
 * Point d'entrée unique pour ouvrir l'éditeur de contacts du système.
 *
 * Factorise le lancement de l'intent « créer un contact » partagé par l'écran de
 * composition ([com.filestech.sms.ui.screens.compose.ComposeScreen]) et le menu
 * overflow de la liste des conversations
 * ([com.filestech.sms.ui.screens.conversations.ConversationsScreen]).
 *
 * Le [com.filestech.sms.ui.screens.thread.ThreadScreen] utilise [editContact] pour
 * « Modifier le contact » (`ACTION_EDIT` + URI du contact, via
 * `Event.OpenEditContact`) ; il construit encore son propre
 * `Intent(ACTION_INSERT_OR_EDIT)` (via `Event.OpenAddContact`) pour « ajouter à un
 * contact existant ou en créer un », sémantique distincte du « créer une fiche
 * vierge » exposé ici.
 *
 * On utilise [Intent.ACTION_INSERT] (et non `ACTION_INSERT_OR_EDIT`) : l'action
 * demandée est explicitement « **créer** un contact », donc on veut ouvrir l'éditeur
 * sur une fiche vierge (pré-remplie si un numéro est fourni). `ACTION_INSERT_OR_EDIT`
 * sans URI de contact ouvre au contraire un **sélecteur** de contact existant sur
 * certaines ROMs (Samsung One UI notamment), ce qui n'est pas le comportement voulu.
 * Le thread conserve `ACTION_INSERT_OR_EDIT` de son côté car il part d'un numéro déjà
 * en contexte (« ajouter à un contact existant ou en créer un »).
 *
 * Le MIME doit être [ContactsContract.Contacts.CONTENT_TYPE] (type répertoire) pour
 * `ACTION_INSERT`, et non `CONTENT_ITEM_TYPE`.
 *
 * Aucune permission requise : l'action est déléguée à l'app Contacts, qui s'exécute
 * dans son propre process. Le `startActivity` est protégé par [runCatching] pour ne
 * pas crasher sur une ROM dépourvue d'app Contacts (le retour `false` laisse
 * l'appelant afficher un feedback).
 */
object ContactIntents {

    /**
     * Ouvre l'éditeur de contacts sur une fiche vierge (nouveau contact).
     *
     * @param phoneNumber numéro à pré-remplir, ou `null`/vide pour une fiche totalement vierge.
     * @return `true` si l'app Contacts a bien été lancée, `false` sinon (aucune app
     *   disponible) — à charge de l'appelant d'informer l'utilisateur.
     */
    fun createContact(context: Context, phoneNumber: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            if (!phoneNumber.isNullOrBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
            }
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "Contact editor intent failed") }
            .isSuccess
    }

    /**
     * Ouvre l'éditeur de contacts sur la fiche d'un contact existant.
     *
     * `ACTION_EDIT` avec l'URI du contact (`Contacts.CONTENT_URI` + id) : l'éditeur
     * s'ouvre directement sur CETTE fiche. C'est ce qui distingue l'édition de
     * l'ajout : `ACTION_INSERT_OR_EDIT` sans URI ouvre un sélecteur de contact
     * existant sur certaines ROMs (voir le KDoc du fichier), ce qui n'est pas
     * « modifier ce contact ».
     *
     * @param contactId l'identifiant du contact (`Contact.id`, issu de `PhoneLookup._ID`).
     * @return `true` si l'app Contacts a bien été lancée, `false` sinon (aucune app
     *   disponible) — à charge de l'appelant d'informer l'utilisateur.
     */
    fun editContact(context: Context, contactId: Long): Boolean {
        val uri = ContentUris.withAppendedId(
            ContactsContract.Contacts.CONTENT_URI,
            contactId,
        )
        val intent = Intent(Intent.ACTION_EDIT, uri)
        return runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "Contact editor intent failed") }
            .isSuccess
    }
}
