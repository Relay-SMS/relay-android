package com.filestech.sms.e2e

/**
 * How encryption keys and numbers are shown to the user (design §5.2,
 * §7.1).
 *
 * Pure Kotlin, no Android APIs — fully unit testable. Nothing here ever
 * logs: a fingerprint or a safety number is public key material, but it
 * still has no business in a log line (design §8.3).
 */
object E2eDisplayFormat {

    /** Hex characters per fingerprint group ("0123 abcd …"). */
    private const val FINGERPRINT_GROUP_CHARS: Int = 4

    /** Safety-number groups per display line (12 groups → 3 lines of 4). */
    private const val SAFETY_NUMBER_GROUPS_PER_LINE: Int = 4

    /**
     * Our Curve25519 identity key as grouped lowercase hex: 64 hex
     * characters in 16 groups of 4, separated by single spaces
     * ("0123 abcd …"). Shown on the Encryption settings screen so a
     * contact can check it against their panel.
     *
     * @throws IllegalArgumentException if the key is not 32 bytes.
     */
    fun formatFingerprint(curveKey: ByteArray): String {
        require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
        // Mask to unsigned first: `%02x` on a negative byte sign-extends
        // it ("ffffff80"), which would corrupt the fingerprint.
        val hex = curveKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return hex.chunked(FINGERPRINT_GROUP_CHARS).joinToString(" ")
    }

    /**
     * Why an identity load failed, for the Encryption settings
     * screen: "SimpleName: message", with the root cause appended
     * when it differs ("... (caused by RootSimpleName: root
     * message)") so a screenshot of the screen carries the actual
     * reason, not just the wrapper ("encryption is unavailable"
     * alone says nothing about why). Never carries keys,
     * addresses, or PII: the identity path throws our fixed
     * messages, loader messages, or Keystore/Room errors — none
     * of which quotes user data.
     */
    fun describeFailure(throwable: Throwable): String {
        val top = shortDescription(throwable)
        val root = rootCause(throwable)
        return if (root === throwable) top else "$top (caused by ${shortDescription(root)})"
    }

    /** "SimpleName: message", or just the name when the message is blank. */
    private fun shortDescription(throwable: Throwable): String {
        val name = throwable.javaClass.simpleName.ifBlank { "Unknown" }
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message != null) "$name: $message" else name
    }

    /**
     * The deepest cause in the chain (itself when there is none).
     * Shared with [E2eFailureRecorder]: the recorder dedupes on
     * the root cause, so a wrapper (the fallback's
     * `E2eCryptoException`) and the failure it wraps (the native
     * load error) count as one cause. Internal — failure
     * plumbing, not display API.
     */
    internal fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        var cause = current.cause
        while (cause != null && cause !== current) {
            current = cause
            cause = current.cause
        }
        return current
    }

    /**
     * A 60-digit safety number ([E2eSafetyNumber.derive] output: 12 groups
     * of 5 digits) laid out for the verification sheet: 3 lines of 4
     * groups, so it reads as a block instead of one long wrapped line.
     * Anything that is not 12 groups is returned unchanged (defensive —
     * a malformed number still shows, rather than vanishing).
     */
    fun formatSafetyNumberLines(number: String): String {
        val groups = number.split(" ").filter { it.isNotEmpty() }
        if (groups.size != E2eSafetyNumber.GROUP_COUNT) return number
        return groups.chunked(SAFETY_NUMBER_GROUPS_PER_LINE)
            .joinToString("\n") { it.joinToString(" ") }
    }
}
