package com.filestech.sms.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Relay typeface: Inter 400 / 500 / 700, bundled in `res/font/` under the SIL Open Font
 * License (see `assets/fonts/OFL.txt`). A SemiBold (600) request resolves to the Bold
 * file — the closest bundled weight.
 */
internal val RelayFont = FontFamily(
    Font(com.filestech.sms.R.font.inter_regular, FontWeight.Normal),
    Font(com.filestech.sms.R.font.inter_medium, FontWeight.Medium),
    Font(com.filestech.sms.R.font.inter_bold, FontWeight.Bold),
)

/**
 * Named type ramp for the Relay design system. Sizes and weights mirror the design
 * reference (pt mapped 1:1 to sp); [AppTypography] maps the ramp onto the Material 3
 * slots so stock components inherit it.
 */
internal object RelayText {
    val LargeTitle = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    )
    val ConvoName = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )
    val ThreadTitle = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    )
    val MessageBody = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )
    val Preview = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
    val SectionHeader = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    )
    val Timestamp = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    )
    val BubbleMeta = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 14.sp,
    )
    val Button = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 16.sp,
    )
    val ButtonText = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 16.sp,
    )
    val Tab = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.1.sp,
    )
    val TimerChip = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 12.sp,
    )
    val SystemNote = TextStyle(
        fontFamily = RelayFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val SafetyNumber = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp,
    )
}

internal val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Bold, fontSize = 57.sp),
    displayMedium = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Bold, fontSize = 45.sp),
    displaySmall = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Bold, fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
    headlineMedium = RelayText.LargeTitle,
    headlineSmall = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = RelayText.ThreadTitle,
    titleMedium = RelayText.ConvoName,
    titleSmall = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = RelayText.MessageBody,
    bodyMedium = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = RelayFont, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = RelayText.Tab,
)
