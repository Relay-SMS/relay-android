package com.filestech.sms.domain.categories

/**
 * The categories a conversation is sorted into for the tab row on the home
 * screen (round 11).
 *
 * A conversation's category comes from its latest message (see
 * [MessageClassifier]), except [PERSONAL], which is also sender-based: a
 * real phone number — a contact or a new friend not in contacts yet — is
 * Personal, so nobody is missed in an off-screen tab.
 *
 * Persisted BY NAME (`conv.mutedCategories` stores the names): add entries,
 * never rename them. There is no "All" entry — All is the unfiltered view,
 * not a classification.
 */
enum class MessageCategory {
    PERSONAL,
    OTP,
    PROMOTIONAL,
    TRANSACTIONS,
    OTHER,
}
