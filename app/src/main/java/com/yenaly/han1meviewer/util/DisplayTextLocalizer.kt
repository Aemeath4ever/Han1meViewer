package com.yenaly.han1meviewer.util

import com.yenaly.yenaly_libs.utils.LanguageHelper
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object DisplayTextLocalizer {

    private val viewsRegex = Regex("^(.+?)(万次|萬次|次)$")
    private val bareViewsRegex = Regex("""[\d\s,，\u00A0]+""")
    private val relativeTimeRegex = Regex("^(?:ge)?(.+?)(分钟|分鐘|小时|小時|天|周|週|个月|個月|年)前$")

    fun localizeViews(text: String): String {
        val normalized = text.replace('\u00A0', ' ').trim()
        val match = viewsRegex.matchEntire(normalized)
        if (match == null) return formatBareViews(normalized) ?: text
        val count = match.groupValues[1]
        val unit = match.groupValues[2]
        return when (language()) {
            Locale.SIMPLIFIED_CHINESE.language -> when (unit) {
                "万次", "萬次" -> "${count}万次"
                else -> "${count}次"
            }

            Locale.ENGLISH.language -> when (unit) {
                "万次", "萬次" -> "${count.toKViews()} views"
                else -> "$count views"
            }

            Locale.JAPANESE.language -> when (unit) {
                "万次", "萬次" -> "${count}万回"
                else -> "${count}回"
            }

            else -> when (unit) {
                "万次", "萬次" -> "${count}萬次"
                else -> "${count}次"
            }
        }
    }

    /**
     * 只有數字的觀看數（jable 卡片是 `1080234` / `14 130` 這種純數字文本）。
     *
     * 站點自身用空格分組千位，這裡一併接受空格、逗號和全角逗號。
     * 不足一萬保持原樣；一萬以上用「w」：`1080234 -> 108w`、`28309 -> 2.8w`。
     */
    private fun formatBareViews(text: String): String? {
        if (text.isEmpty() || !bareViewsRegex.matches(text)) return null
        val count = text.filter(Char::isDigit).toLongOrNull() ?: return null
        if (count < 10_000L) return text
        val tenThousands = BigDecimal(count)
            .divide(BigDecimal(10_000), 4, RoundingMode.HALF_UP)
        val formatted = if (tenThousands >= BigDecimal(100)) {
            tenThousands.setScale(0, RoundingMode.HALF_UP)
        } else {
            tenThousands.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
        }
        return "${formatted.toPlainString()}w"
    }

    fun localizeRelativeTime(text: String): String {
        val match = relativeTimeRegex.matchEntire(text.trim()) ?: return text
        val count = match.groupValues[1]
        val unit = match.groupValues[2]
        return when (language()) {
            Locale.SIMPLIFIED_CHINESE.language -> "$count${unit.toSimplifiedUnit()}前"
            Locale.ENGLISH.language -> "$count ${unit.toEnglishUnit(count)} ago"
            Locale.JAPANESE.language -> "$count${unit.toJapaneseUnit()}前"
            else -> "$count${unit.toTraditionalUnit()}前"
        }
    }

    private fun language(): String = LanguageHelper.preferredLanguage.language

    private fun String.toSimplifiedUnit(): String = when (this) {
        "分鐘", "分钟" -> "分钟"
        "小時", "小时" -> "小时"
        "週", "周" -> "周"
        "個月", "个月" -> "个月"
        else -> this
    }

    private fun String.toTraditionalUnit(): String = when (this) {
        "分钟", "分鐘" -> "分鐘"
        "小时", "小時" -> "小時"
        "周", "週" -> "週"
        "个月", "個月" -> "個月"
        else -> this
    }

    private fun String.toJapaneseUnit(): String = when (this) {
        "分钟", "分鐘" -> "分"
        "小时", "小時" -> "時間"
        "天" -> "日"
        "周", "週" -> "週間"
        "个月", "個月" -> "か月"
        "年" -> "年"
        else -> this
    }

    private fun String.toEnglishUnit(count: String): String {
        val singular = count == "1"
        return when (this) {
            "分钟", "分鐘" -> if (singular) "minute" else "minutes"
            "小时", "小時" -> if (singular) "hour" else "hours"
            "天" -> if (singular) "day" else "days"
            "周", "週" -> if (singular) "week" else "weeks"
            "个月", "個月" -> if (singular) "month" else "months"
            "年" -> if (singular) "year" else "years"
            else -> this
        }
    }

    private fun String.toKViews(): String {
        return runCatching {
            BigDecimal(this).multiply(BigDecimal.TEN).stripTrailingZeros().toPlainString() + "K"
        }.getOrElse { "${this}0K" }
    }
}
