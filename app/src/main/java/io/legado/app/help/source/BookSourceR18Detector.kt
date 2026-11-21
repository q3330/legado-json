package io.legado.app.help.source

import io.legado.app.data.entities.BookSource

/**
 * 书源 R18 内容特征识别器（纯本地毫秒级加权评分，兼顾高召回与低误杀）。
 */
internal object BookSourceR18Detector {

    const val R18_GROUP_NAME = "R18"
    private const val SCORE_THRESHOLD = 3

    private val STRONG_KEYWORDS = listOf(
        "r18", "r-18", "18+", "18禁", "18岁", "满18", "未满18",
        "成人", "里番", "禁漫", "本子", "绅士", "色情", "情色",
        "肉文", "h漫", "h文", "小黄文", "黄漫", "污漫", "工口",
        "色漫", "无修", "无码", "步兵", "骑兵", "nsfw", "hentai",
        "porn", "erotic", "doujinshi", "成人漫画", "成人小说", "情色小说",
        "禁忌书屋", "高h", "np肉", "辣文", "春宫", "福利漫画", "羞羞漫画",
        "歪歪漫画", "污污漫画", "色色", "啪啪", "乱伦", "人妻", "熟女",
        "调教", "触手", "催眠", "痴汉", "巨乳", "丝袜美腿", "av女优",
    )

    private val STRONG_DOMAIN_KEYWORDS = listOf(
        "18comic", "jmcomic", "jmd8", "91jmd", "nhentai", "e-hentai",
        "exhentai", "wnacg", "hitomi", "hanime", "91porn", "18av",
        "javdb", "javbus", "pornhub", "xvideos", "hentai", "ero",
        "sex", "porn", "avtb", "se8", "18h", "hcomic", "manwa18",
        "rouman", "hm18", "mh18", "hmcomic", "禁漫",
    )

    private val MEDIUM_EXPLORE_KEYWORDS = listOf(
        "同人志", "全彩", "无删减", "韩漫", "日漫", "cosplay",
        "ntr", "bl肉", "耽美肉", "百合肉", "重口", "猎奇", "扶她",
        "后宫肉", "艳情", "激情", "伦理", "风月", "香艳", "诱惑",
        "出轨", "少妇", "萝莉控", "御姐控",
    )

    private val STRONG_TOKEN_REGEX = Regex(
        """(?<![a-z0-9])(r18|18\+|nsfw|hentai|porn|jav|av|ero|sm|ntr|futa|ecchi)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    fun isR18(
        name: String? = null,
        url: String? = null,
        group: String? = null,
        comment: String? = null,
        exploreUrl: String? = null,
        searchUrl: String? = null,
        extraTexts: Iterable<String?> = emptyList(),
    ): Boolean {
        return score(
            name = name,
            url = url,
            group = group,
            comment = comment,
            exploreUrl = exploreUrl,
            searchUrl = searchUrl,
            extraTexts = extraTexts,
        ) >= SCORE_THRESHOLD
    }

    fun isR18(source: BookSource, extraTexts: Iterable<String?> = emptyList()): Boolean {
        return isR18(
            name = source.bookSourceName,
            url = source.bookSourceUrl,
            group = source.bookSourceGroup,
            comment = source.bookSourceComment,
            exploreUrl = source.exploreUrl,
            searchUrl = source.searchUrl,
            extraTexts = extraTexts,
        )
    }

    internal fun score(
        name: String? = null,
        url: String? = null,
        group: String? = null,
        comment: String? = null,
        exploreUrl: String? = null,
        searchUrl: String? = null,
        extraTexts: Iterable<String?> = emptyList(),
    ): Int {
        var total = 0

        val urlLower = url.orEmpty().lowercase()
        if (urlLower.isNotEmpty()) {
            if (STRONG_DOMAIN_KEYWORDS.any { urlLower.contains(it) }) {
                total += 3
            }
            if (total >= SCORE_THRESHOLD) return total
        }

        val primaryFields = listOf(name, group, comment, searchUrl)
        for (field in primaryFields) {
            if (field.isNullOrBlank()) continue
            val lower = field.lowercase()
            if (STRONG_KEYWORDS.any { lower.contains(it) } || STRONG_TOKEN_REGEX.containsMatchIn(lower)) {
                total += 3
                if (total >= SCORE_THRESHOLD) return total
            }
        }

        if (!exploreUrl.isNullOrBlank()) {
            val lower = exploreUrl.lowercase()
            if (STRONG_KEYWORDS.any { lower.contains(it) } || STRONG_TOKEN_REGEX.containsMatchIn(lower)) {
                total += 3
                if (total >= SCORE_THRESHOLD) return total
            }
            var mediumHits = 0
            for (kw in MEDIUM_EXPLORE_KEYWORDS) {
                if (lower.contains(kw)) {
                    mediumHits++
                    if (mediumHits >= 2) {
                        total += 3
                        return total
                    }
                }
            }
            if (mediumHits == 1) total += 1
        }

        for (extra in extraTexts) {
            if (extra.isNullOrBlank()) continue
            val lower = extra.lowercase()
            if (STRONG_KEYWORDS.any { lower.contains(it) } || STRONG_TOKEN_REGEX.containsMatchIn(lower)) {
                total += 3
                if (total >= SCORE_THRESHOLD) return total
            }
            if (MEDIUM_EXPLORE_KEYWORDS.any { lower.contains(it) }) {
                total += 2
                if (total >= SCORE_THRESHOLD) return total
            }
        }

        return total
    }
}
