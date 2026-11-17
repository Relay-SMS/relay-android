package com.filestech.sms.sync.client

/**
 * Client mode — LICENSE-GATE-STUB.
 *
 * Linked-device sync is the paid tier (like the desktop app), so the
 * link flow calls [LicenseGate.checkLicense] before linking
 * proceeds — but there is NO license server yet, so this is a stub
 * that ALWAYS passes. It must PASS THROUGH and NEVER block linking,
 * so device testing works.
 *
 * When accounts and licensing arrive, this file grows the real check
 * (signed token, expiry, trial) behind the same call site. Do NOT
 * build trial logic, key entry, expiry, or any enforcement here in
 * the meantime — that is a separate future workstream.
 *
 * Mirrors the desktop stub (`license.rs`): same three statuses, same
 * always-Valid check, same `isValid` rule.
 */
enum class LicenseStatus {
    Valid,
    Expired,
    Missing;

    /** Only [Valid] passes the gate. */
    fun isValid(): Boolean = this == Valid
}

/**
 * LICENSE-GATE-STUB — the paywall hook for the link flow. Always
 * returns [LicenseStatus.Valid] until a license server exists.
 */
object LicenseGate {

    /**
     * Checks whether this device may link to a hub. STUB: always
     * [LicenseStatus.Valid] — linking is never blocked.
     */
    fun checkLicense(): LicenseStatus = LicenseStatus.Valid
}
