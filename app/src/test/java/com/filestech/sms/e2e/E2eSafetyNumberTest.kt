package com.filestech.sms.e2e

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Safety-number tests (design §5.1 / §10.1). The expected values are
 * fixed vectors computed independently with Python's `hashlib`
 * (SHA-256 over the sorted key pairs, 12 × 20-bit groups mod 100000) —
 * hardcoded here so a regression in the derivation cannot hide behind
 * a shared implementation.
 */
class E2eSafetyNumberTest {

    @Test
    fun `all-zero keys against all-0xFF keys give the known number`() {
        val number = E2eSafetyNumber.derive(
            ourCurveKey = ByteArray(32),
            theirCurveKey = ByteArray(32) { 0xFF.toByte() },
            ourEdKey = ByteArray(32),
            theirEdKey = ByteArray(32) { 0xFF.toByte() },
        )
        assertThat(number).isEqualTo("28328 95711 34129 07198 23148 68457 44730 22175 53160 55956 31378 42932")
    }

    @Test
    fun `sequential key bytes give the known number`() {
        val number = E2eSafetyNumber.derive(
            ourCurveKey = ByteArray(32) { it.toByte() },
            theirCurveKey = ByteArray(32) { (it + 32).toByte() },
            ourEdKey = ByteArray(32) { (it + 64).toByte() },
            theirEdKey = ByteArray(32) { (it + 96).toByte() },
        )
        assertThat(number).isEqualTo("91323 07146 46513 28759 94769 14425 21260 24914 10661 12576 45968 55338")
    }

    @Test
    fun `seeded keys give the known number`() {
        val number = E2eSafetyNumber.derive(
            ourCurveKey = hex("f48d0530511764d5061840a8b61c3785411806a7bcc6fd00ca446bb7621c9e1b"),
            theirCurveKey = hex("615d2b04bb1ffef5bb825b5f9a19f87b754b2f4426b2752e78e74c4a540ec88d"),
            ourEdKey = hex("6ea1c4ba99dc7e4c4b8c60dd259d75b041beab5416bfb229c151d50ecef1a162"),
            theirEdKey = hex("749d1538791e51e9e4fefd5c5d3a8feee15c1133ffc71b92cfcb1b01a3acfab7"),
        )
        assertThat(number).isEqualTo("05536 33035 33026 62235 75379 52535 24305 05917 73673 17728 84975 10943")
    }

    @Test
    fun `the number is symmetric — both sides compute the same number`() {
        val aliceCurve = hex("f48d0530511764d5061840a8b61c3785411806a7bcc6fd00ca446bb7621c9e1b")
        val bobCurve = hex("615d2b04bb1ffef5bb825b5f9a19f87b754b2f4426b2752e78e74c4a540ec88d")
        val aliceEd = hex("6ea1c4ba99dc7e4c4b8c60dd259d75b041beab5416bfb229c151d50ecef1a162")
        val bobEd = hex("749d1538791e51e9e4fefd5c5d3a8feee15c1133ffc71b92cfcb1b01a3acfab7")
        val fromAlice = E2eSafetyNumber.derive(aliceCurve, bobCurve, aliceEd, bobEd)
        val fromBob = E2eSafetyNumber.derive(bobCurve, aliceCurve, bobEd, aliceEd)
        assertThat(fromBob).isEqualTo(fromAlice)
    }

    @Test
    fun `the number is 12 groups of 5 digits separated by spaces`() {
        val number = E2eSafetyNumber.derive(
            ourCurveKey = ByteArray(32) { 7 },
            theirCurveKey = ByteArray(32) { 9 },
            ourEdKey = ByteArray(32) { 3 },
            theirEdKey = ByteArray(32) { 5 },
        )
        assertThat(number.length).isEqualTo(12 * 5 + 11)
        val groups = number.split(" ")
        assertThat(groups).hasSize(12)
        groups.forEach { group ->
            assertThat(group.length).isEqualTo(5)
            assertThat(group).matches("[0-9]{5}")
        }
    }

    @Test
    fun `changing one key byte changes the number`() {
        val base = E2eSafetyNumber.derive(
            ourCurveKey = ByteArray(32),
            theirCurveKey = ByteArray(32) { 0xFF.toByte() },
            ourEdKey = ByteArray(32),
            theirEdKey = ByteArray(32) { 0xFF.toByte() },
        )
        val changedCurve = ByteArray(32)
        changedCurve[31] = 0x01
        val changed = E2eSafetyNumber.derive(
            ourCurveKey = changedCurve,
            theirCurveKey = ByteArray(32) { 0xFF.toByte() },
            ourEdKey = ByteArray(32),
            theirEdKey = ByteArray(32) { 0xFF.toByte() },
        )
        assertThat(changed).isNotEqualTo(base)
    }

    @Test
    fun `derive rejects keys that are not 32 bytes`() {
        val good = ByteArray(32)
        val short = ByteArray(31)
        assertThrows(IllegalArgumentException::class.java) {
            E2eSafetyNumber.derive(short, good, good, good)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eSafetyNumber.derive(good, good, good, ByteArray(33))
        }
    }

    private fun hex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
