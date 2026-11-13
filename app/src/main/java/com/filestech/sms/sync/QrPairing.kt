package com.filestech.sms.sync

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/**
 * Parses the pairing QR code a linking device shows, and validates it
 * strictly. Format (SYNC_PROTOCOL.md):
 *
 * `relay://pair?v=1&id=<uuid>&kind=<desktop|phone|tablet>&name=<url-encoded>&key=<base64url 32-byte Ed25519 public key>`
 *
 * Anything else is rejected with an honest reason — the screen shows
 * that reason instead of silently ignoring the code. This file is
 * standalone pure Kotlin: no Android imports, no app imports.
 */
object QrPairing {

    /** A pairing code that passed every check, ready for the user to confirm. */
    data class Invite(
        val deviceId: String,
        val kind: DeviceKind,
        val displayName: String,
        val publicKeyBase64Url: String,
    )

    /** Why a scanned code was rejected. The UI maps these to honest errors. */
    sealed interface Failure {
        /** Not a `relay://pair` URI at all (someone else's QR code). */
        data object NotAPairingCode : Failure

        /** A pairing code, but for a protocol version this build does not speak. */
        data object UnsupportedVersion : Failure

        /** A pairing code with a missing or malformed field. */
        data class InvalidField(val field: String) : Failure
    }

    sealed interface Result {
        data class Valid(val invite: Invite) : Result
        data class Invalid(val failure: Failure) : Result
    }

    /**
     * Parses and validates [text] (the raw QR payload). Never throws.
     * Every field is checked: scheme + host, `v=1`, a real UUID, a known
     * kind, a non-blank name, and a key that base64url-decodes to exactly
     * 32 bytes. One `when`, not a chain of early returns — Detekt caps
     * returns per function at four.
     */
    fun parse(text: String): Result {
        val params = runCatching { URI(text.trim()) }.getOrNull()
            ?.takeIf { it.scheme == "relay" && it.host == "pair" }
            ?.let { parseQuery(it.rawQuery.orEmpty()) }
        val id = params?.get("id")
        val kind = params?.get("kind")?.let(DeviceKind::fromWire)
        val name = params?.get("name")?.takeIf { it.isNotBlank() }
        val key = params?.get("key")
        return when {
            params == null -> Result.Invalid(Failure.NotAPairingCode)
            params["v"] != SyncProtocol.PROTOCOL_VERSION.toString() ->
                Result.Invalid(Failure.UnsupportedVersion)
            id.isNullOrBlank() || !isUuid(id) -> Result.Invalid(Failure.InvalidField("id"))
            kind == null -> Result.Invalid(Failure.InvalidField("kind"))
            name == null -> Result.Invalid(Failure.InvalidField("name"))
            key.isNullOrBlank() || !is32ByteKey(key) -> Result.Invalid(Failure.InvalidField("key"))
            else -> Result.Valid(
                Invite(deviceId = id, kind = kind, displayName = name, publicKeyBase64Url = key),
            )
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val params = mutableMapOf<String, String>()
        for (pair in rawQuery.split("&")) {
            val equals = pair.indexOf('=')
            if (equals > 0) {
                val value = runCatching {
                    URLDecoder.decode(pair.substring(equals + 1), Charsets.UTF_8)
                }.getOrNull()
                if (value != null) {
                    params.putIfAbsent(pair.substring(0, equals), value)
                }
            }
        }
        return params
    }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess

    private fun is32ByteKey(value: String): Boolean {
        val bytes = runCatching { Base64.getUrlDecoder().decode(padBase64(value)) }.getOrNull()
            ?: return false
        return bytes.size == SyncProtocol.PUBLIC_KEY_BYTES
    }

    /**
     * `java.util.Base64` wants its padding back: QR payloads omit it
     * (43 chars for 32 bytes), so the missing `=` are restored before
     * decoding. A length that cannot be padded (`% 4 == 1`) is invalid.
     */
    internal fun padBase64(value: String): String {
        val remainder = value.length % 4
        if (remainder == 1) return value
        return value + "=".repeat((4 - remainder) % 4)
    }
}
