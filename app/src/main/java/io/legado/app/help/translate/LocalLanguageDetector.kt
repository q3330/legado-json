package io.legado.app.help.translate

/**
 * 轻量端侧语种识别，用于将未指定源语种的文本路由至对应 NMT 模型或 ML Kit 语言包。
 */
object LocalLanguageDetector {

    fun detect(text: String): String {
        val sample = text.trim()
        if (sample.isEmpty()) return "en"

        var kanaCount = 0
        var hangulCount = 0
        var cjkCount = 0
        var cyrillicCount = 0
        var latinCount = 0
        var germanHints = 0
        var frenchHints = 0
        var spanishHints = 0

        for (ch in sample) {
            val block = Character.UnicodeBlock.of(ch)
            when {
                block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA ||
                    block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> kanaCount++
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO ||
                    block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> hangulCount++
                block == Character.UnicodeBlock.CYRILLIC ||
                    block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> cyrillicCount++
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> cjkCount++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latinCount++
                ch in "äöüÄÖÜß" -> {
                    latinCount++
                    germanHints++
                }
                ch in "éèêëàâçôùûœÉÈÊËÀÂÇÔÙÛŒ" -> {
                    latinCount++
                    frenchHints++
                }
                ch in "ñáíóúü¿¡ÑÁÍÓÚÜ" -> {
                    latinCount++
                    spanishHints++
                }
                ch.isLetter() -> latinCount++
            }
        }

        return when {
            kanaCount > 0 -> "ja"
            hangulCount > 0 -> "ko"
            cyrillicCount > latinCount && cyrillicCount > cjkCount -> "ru"
            cjkCount >= latinCount && cjkCount > 0 -> "zh"
            germanHints > frenchHints && germanHints > spanishHints -> "de"
            frenchHints > germanHints && frenchHints > spanishHints -> "fr"
            spanishHints > 0 -> "es"
            else -> "en"
        }
    }

    fun toNllbCode(langCode: String, targetSpec: TranslateTargetLang? = null): String {
        targetSpec?.nllbCode?.takeIf { it.isNotBlank() }?.let { return it }
        val normalized = langCode.trim().lowercase()
        return when {
            normalized == "zh-tw" || normalized == "zh-hant" || normalized == "cht" -> "zho_Hant"
            normalized.startsWith("zh") -> "zho_Hans"
            normalized.startsWith("en") -> "eng_Latn"
            normalized.startsWith("ja") || normalized == "jp" -> "jpn_Jpan"
            normalized.startsWith("ko") || normalized == "kor" -> "kor_Hang"
            normalized.startsWith("ru") -> "rus_Cyrl"
            normalized.startsWith("fr") -> "fra_Latn"
            normalized.startsWith("de") -> "deu_Latn"
            normalized.startsWith("es") -> "spa_Latn"
            else -> "eng_Latn"
        }
    }
}
