package com.filestech.sms.sync.client

import com.filestech.sms.sync.DeviceKind
import com.filestech.sms.sync.QrPairing
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Client mode — the pairing URI a client displays ([QrPairing
 * .buildPairUri]) must round-trip through the hub's strict
 * [QrPairing.parse]: whatever the client builds, the hub's scanner
 * accepts with every field intact. The key here is 32 zero bytes (43
 * base64url chars): a real key is not needed to test the *shape* —
 * signing against a real keypair lives in [ClientIdentityStoreTest].
 */
class QrPairUriTest {

    @Test fun `phone uri round-trips with all fields`() {
        val uri = QrPairing.buildPairUri(DEVICE_ID, DeviceKind.PHONE, "Pixel 8", KEY_32)
        val result = QrPairing.parse(uri)
        assertThat(result).isInstanceOf(QrPairing.Result.Valid::class.java)
        val invite = (result as QrPairing.Result.Valid).invite
        assertThat(invite.deviceId).isEqualTo(DEVICE_ID)
        assertThat(invite.kind).isEqualTo(DeviceKind.PHONE)
        assertThat(invite.displayName).isEqualTo("Pixel 8")
        assertThat(invite.publicKeyBase64Url).isEqualTo(KEY_32)
    }

    @Test fun `tablet uri round-trips`() {
        val uri = QrPairing.buildPairUri(DEVICE_ID, DeviceKind.TABLET, "Galaxy Tab", KEY_32)
        val result = QrPairing.parse(uri)
        assertThat(result).isInstanceOf(QrPairing.Result.Valid::class.java)
        val invite = (result as QrPairing.Result.Valid).invite
        assertThat(invite.kind).isEqualTo(DeviceKind.TABLET)
        assertThat(invite.displayName).isEqualTo("Galaxy Tab")
    }

    @Test fun `display name with apostrophe and spaces round-trips`() {
        val uri = QrPairing.buildPairUri(DEVICE_ID, DeviceKind.PHONE, "Kristin's Phone", KEY_32)
        val result = QrPairing.parse(uri)
        assertThat(result).isInstanceOf(QrPairing.Result.Valid::class.java)
        val invite = (result as QrPairing.Result.Valid).invite
        assertThat(invite.displayName).isEqualTo("Kristin's Phone")
    }

    @Test fun `display name with ampersand and equals does not break the query`() {
        val uri = QrPairing.buildPairUri(DEVICE_ID, DeviceKind.PHONE, "A&B=C", KEY_32)
        val result = QrPairing.parse(uri)
        assertThat(result).isInstanceOf(QrPairing.Result.Valid::class.java)
        val invite = (result as QrPairing.Result.Valid).invite
        assertThat(invite.displayName).isEqualTo("A&B=C")
        assertThat(invite.publicKeyBase64Url).isEqualTo(KEY_32)
    }

    @Test fun `built uri has the relay pair shape`() {
        val uri = QrPairing.buildPairUri(DEVICE_ID, DeviceKind.PHONE, "Pixel", KEY_32)
        assertThat(uri).startsWith("relay://pair?v=1&id=$DEVICE_ID&kind=phone&name=Pixel&key=")
    }

    companion object {
        private val DEVICE_ID = UUID.randomUUID().toString()

        /** 32 zero bytes, base64url — 43 `A`s. */
        private val KEY_32 = Base64Url.encode(ByteArray(32))
    }
}
