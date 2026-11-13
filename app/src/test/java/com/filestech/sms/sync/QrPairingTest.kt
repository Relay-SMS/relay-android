package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Slice 1 — QR pairing validation: every field of the `relay://pair`
 * URI is checked, and every rejection carries an honest reason the
 * screen can show. The key here is 32 zero bytes (43 base64url chars):
 * a real key is not needed to test the *shape* checks — signature
 * verification against a real keypair lives in [Ed25519Test] and
 * [SyncSessionHandlerTest].
 */
class QrPairingTest {

    @Test fun `valid code parses with all fields`() {
        val result = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=desktop&name=Kristin%27s%20MacBook&key=$KEY_32",
        )
        assertThat(result).isInstanceOf(QrPairing.Result.Valid::class.java)
        val invite = (result as QrPairing.Result.Valid).invite
        assertThat(invite.deviceId).isEqualTo(DEVICE_ID)
        assertThat(invite.kind).isEqualTo(DeviceKind.DESKTOP)
        assertThat(invite.displayName).isEqualTo("Kristin's MacBook")
        assertThat(invite.publicKeyBase64Url).isEqualTo(KEY_32)
    }

    @Test fun `phone and tablet kinds parse`() {
        val phone = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=phone&name=Pixel&key=$KEY_32",
        )
        assertThat((phone as QrPairing.Result.Valid).invite.kind).isEqualTo(DeviceKind.PHONE)
        val tablet = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=tablet&name=iPad&key=$KEY_32",
        )
        assertThat((tablet as QrPairing.Result.Valid).invite.kind).isEqualTo(DeviceKind.TABLET)
    }

    @Test fun `non relay scheme is not a pairing code`() {
        val result = QrPairing.parse("https://example.com/pair?v=1&id=$DEVICE_ID")
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.NotAPairingCode),
        )
    }

    @Test fun `plain text is not a pairing code`() {
        val result = QrPairing.parse("hello world")
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.NotAPairingCode),
        )
    }

    @Test fun `relay scheme with wrong host is not a pairing code`() {
        val result = QrPairing.parse(
            "relay://other?v=1&id=$DEVICE_ID&kind=desktop&name=X&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.NotAPairingCode),
        )
    }

    @Test fun `version 2 is unsupported, not a damaged code`() {
        val result = QrPairing.parse(
            "relay://pair?v=2&id=$DEVICE_ID&kind=desktop&name=X&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.UnsupportedVersion),
        )
    }

    @Test fun `missing version is unsupported`() {
        val result = QrPairing.parse(
            "relay://pair?id=$DEVICE_ID&kind=desktop&name=X&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.UnsupportedVersion),
        )
    }

    @Test fun `missing id names the field`() {
        val result = QrPairing.parse(
            "relay://pair?v=1&kind=desktop&name=X&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.InvalidField("id")),
        )
    }

    @Test fun `malformed uuid names the field`() {
        val result = QrPairing.parse(
            "relay://pair?v=1&id=not-a-uuid&kind=desktop&name=X&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.InvalidField("id")),
        )
    }

    @Test fun `unknown kind names the field`() {
        val result = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=watch&name=X&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.InvalidField("kind")),
        )
    }

    @Test fun `blank name names the field`() {
        val result = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=desktop&name=%20%20&key=$KEY_32",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.InvalidField("name")),
        )
    }

    @Test fun `key of 31 bytes names the field`() {
        // 31 bytes base64url = 42 chars; one char short of a real key.
        val shortKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val result = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=desktop&name=X&key=$shortKey",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.InvalidField("key")),
        )
    }

    @Test fun `key that is not base64 names the field`() {
        val result = QrPairing.parse(
            "relay://pair?v=1&id=$DEVICE_ID&kind=desktop&name=X&key=!!!not-base64!!!",
        )
        assertThat(result).isEqualTo(
            QrPairing.Result.Invalid(QrPairing.Failure.InvalidField("key")),
        )
    }

    @Test fun `leading and trailing whitespace is trimmed`() {
        val result = QrPairing.parse(
            "  relay://pair?v=1&id=$DEVICE_ID&kind=desktop&name=X&key=$KEY_32\n",
        )
        assertThat(result).isInstanceOf(QrPairing.Result.Valid::class.java)
    }

    companion object {
        private const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"

        /** 32 zero bytes, base64url without padding (43 chars). */
        private const val KEY_32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
