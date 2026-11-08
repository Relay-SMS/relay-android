package com.filestech.sms.domain.otp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Positive and negative cases for [OtpCodeDetector]. The negative cases are
 * the point: dates, amounts, phone numbers, long IDs and order numbers must
 * never surface a copy pill — a false positive teaches the user to ignore
 * it. See the detector's KDoc for the doctrine behind each rule.
 */
class OtpCodeDetectorTest {

    // ──────────────── Tier 1 : explicit code context ────────────────

    @Test fun `code after keyword, english`() {
        assertThat(OtpCodeDetector.detect("Your verification code is 123456"))
            .isEqualTo("123456")
    }

    @Test fun `code before keyword, english`() {
        assertThat(OtpCodeDetector.detect("123456 is your verification code"))
            .isEqualTo("123456")
    }

    @Test fun `code after colon`() {
        assertThat(OtpCodeDetector.detect("Verification code: 987654")).isEqualTo("987654")
    }

    @Test fun `four digit code with context`() {
        assertThat(OtpCodeDetector.detect("Your code is 1234")).isEqualTo("1234")
    }

    @Test fun `eight digit code with context`() {
        assertThat(OtpCodeDetector.detect("Your security code is 12345678"))
            .isEqualTo("12345678")
    }

    @Test fun `google style letter dash code with context`() {
        assertThat(OtpCodeDetector.detect("G-123456 is your Google verification code"))
            .isEqualTo("123456")
    }

    @Test fun `grouped code with context`() {
        assertThat(OtpCodeDetector.detect("Your verification code is 123 456"))
            .isEqualTo("123456")
        assertThat(OtpCodeDetector.detect("Your verification code is 123-456"))
            .isEqualTo("123456")
    }

    @Test fun `french code`() {
        assertThat(OtpCodeDetector.detect("Votre code de vérification est 654321"))
            .isEqualTo("654321")
    }

    @Test fun `german code`() {
        assertThat(OtpCodeDetector.detect("Ihr Bestätigungscode lautet 987654"))
            .isEqualTo("987654")
    }

    @Test fun `spanish code`() {
        assertThat(OtpCodeDetector.detect("Tu código de verificación es 445566"))
            .isEqualTo("445566")
    }

    @Test fun `italian code`() {
        assertThat(OtpCodeDetector.detect("Il tuo codice di verifica è 112233"))
            .isEqualTo("112233")
    }

    @Test fun `otp word`() {
        assertThat(OtpCodeDetector.detect("Your OTP is 778899")).isEqualTo("778899")
    }

    @Test fun `passcode word`() {
        assertThat(OtpCodeDetector.detect("Your passcode: 556677")).isEqualTo("556677")
    }

    // ──────────────── Tier 2 : standalone ────────────────

    @Test fun `bare six digit message`() {
        assertThat(OtpCodeDetector.detect("123456")).isEqualTo("123456")
    }

    @Test fun `bare grouped message`() {
        assertThat(OtpCodeDetector.detect("123 456")).isEqualTo("123456")
    }

    @Test fun `bare letter dash message`() {
        assertThat(OtpCodeDetector.detect("G-123456")).isEqualTo("123456")
    }

    @Test fun `single six digit run in a number free message`() {
        assertThat(OtpCodeDetector.detect("Here it is: 123456")).isEqualTo("123456")
    }

    // ──────────────── Negative : dates, amounts, phones, IDs ────────────────

    @Test fun `blank body has no code`() {
        assertThat(OtpCodeDetector.detect("")).isNull()
        assertThat(OtpCodeDetector.detect("   ")).isNull()
    }

    @Test fun `year alone is not a code`() {
        assertThat(OtpCodeDetector.detect("See you in 2026")).isNull()
    }

    @Test fun `time is not a code`() {
        assertThat(OtpCodeDetector.detect("Meet at 17:50")).isNull()
    }

    @Test fun `date is not a code`() {
        assertThat(OtpCodeDetector.detect("Your appointment is on 12/05/2026")).isNull()
    }

    @Test fun `amount is not a code`() {
        assertThat(OtpCodeDetector.detect("Your balance is \$1,234.56")).isNull()
        assertThat(OtpCodeDetector.detect("Payment of 12,50 € received")).isNull()
    }

    @Test fun `phone number is not a code`() {
        assertThat(OtpCodeDetector.detect("Call me at +33 6 12 34 56 78")).isNull()
    }

    @Test fun `long id is not a code`() {
        assertThat(OtpCodeDetector.detect("Your order #123456789 has shipped")).isNull()
        assertThat(OtpCodeDetector.detect("Reference AB12345678")).isNull()
    }

    @Test fun `nine digit run with code word is not a code`() {
        assertThat(OtpCodeDetector.detect("Your verification code is 123456789")).isNull()
    }

    @Test fun `ten digit run with code word is not a code`() {
        assertThat(OtpCodeDetector.detect("Your verification code is 1234567890")).isNull()
    }

    @Test fun `five digit order number without context is not a code`() {
        assertThat(OtpCodeDetector.detect("Your order 12345 ships today")).isNull()
    }

    @Test fun `multiple numbers without context are not a code`() {
        assertThat(OtpCodeDetector.detect("Order 123456 shipped, ref 789")).isNull()
    }

    @Test fun `iban groups are not a code`() {
        assertThat(OtpCodeDetector.detect("Virement vers FR76 3000 4000 0312 3456 7890"))
            .isNull()
    }

    @Test fun `postal code is not a code`() {
        assertThat(OtpCodeDetector.detect("Your postal code is 75001")).isNull()
    }

    @Test fun `spaced digit sequence is not a code`() {
        assertThat(OtpCodeDetector.detect("Your verification code is 1234 5678")).isNull()
    }

    @Test fun `two digit code with context is not a code`() {
        assertThat(OtpCodeDetector.detect("Your code is 12")).isNull()
    }

    @Test fun `code word without digits is not a code`() {
        assertThat(OtpCodeDetector.detect("Your verification code is on its way")).isNull()
    }
}
