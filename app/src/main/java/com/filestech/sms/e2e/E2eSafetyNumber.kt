package com.filestech.sms.e2e

import java.security.MessageDigest

/**
 * The safety number (design §5.1): both sides compute the same 60-digit
 * number from the two Curve25519 identity keys and the two Ed25519
 * identity keys, so the two people can compare it out-of-band and confirm
 * no one sits in the middle.
 *
 *     curvePair = sort([ourCurveKey, theirCurveKey])   // byte-wise lexicographic
 *     edPair    = sort([ourEdKey, theirEdKey])
 *     hash      = SHA-256(curvePair[0] || curvePair[1] || edPair[0] || edPair[1])
 *     number    = for i in 0..11: take 20 bits of hash (bytes 0..29), mod 100000,
 *                 zero-padded to 5 digits
 *     display   = "12345 67890 12345 ..."  // 12 groups of 5 digits
 *
 * Sorting makes the number symmetric — both phones show the identical
 * number for the same pair. Pure Kotlin (no native code), fully unit
 * testable with fixed test vectors.
 */
object E2eSafetyNumber {

    /** Number of 5-digit groups in the displayed safety number. */
    const val GROUP_COUNT: Int = 12

    /** Digits per group. */
    const val GROUP_DIGITS: Int = 5

    private const val BITS_PER_GROUP: Int = 20
    private const val GROUP_MASK: Int = 0xFFFFF
    private const val GROUP_MODULUS: Int = 100_000

    /**
     * Derive the 60-digit safety number for a key pair, as 12 groups of
     * 5 digits separated by single spaces. The order of the two sides
     * does not matter (both orders give the same number).
     *
     * @throws IllegalArgumentException if any key is not 32 bytes.
     */
    fun derive(
        ourCurveKey: ByteArray,
        theirCurveKey: ByteArray,
        ourEdKey: ByteArray,
        theirEdKey: ByteArray,
    ): String {
        require(ourCurveKey.size == E2E_KEY_LENGTH) { "our curve key must be 32 bytes" }
        require(theirCurveKey.size == E2E_KEY_LENGTH) { "their curve key must be 32 bytes" }
        require(ourEdKey.size == E2E_KEY_LENGTH) { "our ed key must be 32 bytes" }
        require(theirEdKey.size == E2E_KEY_LENGTH) { "their ed key must be 32 bytes" }
        val (curveFirst, curveSecond) = sortPair(ourCurveKey, theirCurveKey)
        val (edFirst, edSecond) = sortPair(ourEdKey, theirEdKey)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(curveFirst)
        digest.update(curveSecond)
        digest.update(edFirst)
        digest.update(edSecond)
        val hash = digest.digest()
        return (0 until GROUP_COUNT).joinToString(" ") { groupIndex ->
            "%05d".format(groupValue(hash, groupIndex) % GROUP_MODULUS)
        }
    }

    /**
     * The value of one 20-bit group: bits [20*i, 20*i+20) of the hash,
     * read big-endian (the hash's most significant bit first). Groups
     * start on a byte boundary or 4 bits into a byte (20 ≡ 4 mod 8), so
     * three bytes always cover a group: drop the low bits we do not need
     * (4 - bitInByte of them), then keep the low 20 bits.
     */
    private fun groupValue(hash: ByteArray, groupIndex: Int): Int {
        val startBit = groupIndex * BITS_PER_GROUP
        val byteIndex = startBit / 8
        val bitInByte = startBit % 8
        val window = ((hash[byteIndex].toInt() and 0xFF) shl 16) or
            ((hash[byteIndex + 1].toInt() and 0xFF) shl 8) or
            (hash[byteIndex + 2].toInt() and 0xFF)
        return (window shr (4 - bitInByte)) and GROUP_MASK
    }

    /** Sort a key pair byte-wise lexicographically (unsigned bytes). */
    private fun sortPair(first: ByteArray, second: ByteArray): Pair<ByteArray, ByteArray> =
        if (compareUnsigned(first, second) <= 0) first to second else second to first

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (i in first.indices) {
            val a = first[i].toInt() and 0xFF
            val b = second[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return 0
    }
}
