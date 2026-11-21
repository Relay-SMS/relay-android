package com.filestech.sms.data.local.db

import androidx.room.TypeConverter
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.ScheduledState

/**
 * v1.16.0 — TypeConverter Room pour les enums `MessageStatus` / `MessageType` /
 * `MessageDirection` ↔ Int stocké en base.
 *
 * **Schéma Room** : les colonnes restent `INTEGER NOT NULL` (cf. v7 schéma). Le converter
 * mappe seulement le rendu Kotlin. Aucune migration nécessaire — `identityHash` du schéma
 * Room calculé sur le DDL SQL pur, indépendamment du type Kotlin.
 *
 * **Tolérance aux Int inconnus** : si une row d'un futur build (forward compat) ou d'un
 * fichier de restore custom contient une valeur Int hors enum, [fromRaw] tombe sur le
 * fallback documenté (PENDING / SMS / INCOMING) + log Timber.w. Pas de crash sur lecture.
 *
 * **Pourquoi pas de mapper inline dans l'entité** : Room demande des méthodes top-level
 * statiques (`@TypeConverter`), donc une classe dédiée déclarée via `@TypeConverters` sur
 * l'AppDatabase. Pattern recommandé Google Codelab.
 */
class MessageEnumConverters {
    @TypeConverter
    fun statusFromRaw(rawValue: Int): MessageStatus = MessageStatus.fromRaw(rawValue)

    @TypeConverter
    fun statusToRaw(status: MessageStatus): Int = status.rawValue

    @TypeConverter
    fun typeFromRaw(rawValue: Int): MessageType = MessageType.fromRaw(rawValue)

    @TypeConverter
    fun typeToRaw(type: MessageType): Int = type.rawValue

    @TypeConverter
    fun directionFromRaw(rawValue: Int): MessageDirection = MessageDirection.fromRaw(rawValue)

    @TypeConverter
    fun directionToRaw(direction: MessageDirection): Int = direction.rawValue

    // v1.17.0 audit BACK-M1 — ScheduledState (PENDING/SENT/FAILED/CANCELLED).
    @TypeConverter
    fun scheduledStateFromRaw(rawValue: Int): ScheduledState = ScheduledState.fromRaw(rawValue)

    @TypeConverter
    fun scheduledStateToRaw(state: ScheduledState): Int = state.rawValue

    // Round 35 (schema v20) — the sticky category, stored BY NAME like
    // `mutedCategories` (see `MessageCategory`: add entries, never rename
    // them). An unknown name — a row from a future build — reads as `null`
    // (no stored category, live classification applies) instead of
    // crashing the read; the next incoming message rewrites it.
    @TypeConverter
    fun stickyCategoryFromRaw(rawValue: String?): MessageCategory? =
        rawValue?.let { runCatching { MessageCategory.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun stickyCategoryToRaw(category: MessageCategory?): String? = category?.name

    // E2E (schema v22) — the peer state machine, stored BY NAME like the sticky
    // category. An unknown name — a row from a future build — reads as UNKNOWN
    // (no session assumed, probing may resume) instead of crashing the read.
    @TypeConverter
    fun e2ePeerStateFromRaw(rawValue: String?): E2ePeerState =
        rawValue?.let { runCatching { E2ePeerState.valueOf(it) }.getOrNull() }
            ?: E2ePeerState.UNKNOWN

    @TypeConverter
    fun e2ePeerStateToRaw(state: E2ePeerState): String = state.name

    // E2E (schema v22) — the notice kind, stored BY NAME. An unknown name reads
    // as ACTIVATED (the notice still renders as an encryption event, not as a
    // key-change warning) instead of crashing the read.
    @TypeConverter
    fun e2eNoticeKindFromRaw(rawValue: String?): E2eNoticeKind =
        rawValue?.let { runCatching { E2eNoticeKind.valueOf(it) }.getOrNull() }
            ?: E2eNoticeKind.ACTIVATED

    @TypeConverter
    fun e2eNoticeKindToRaw(kind: E2eNoticeKind): String = kind.name
}
