/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.knowledge

import kotlinx.serialization.Serializable

/**
 * 揭晓边界的证据锚：**用哪句台词**证明某条事实已经对观众公开。
 *
 * 为什么要锚到台词而不是直接写秒数：写秒数是"凭感觉"，无法复核；
 * 锚到台词则可以在任何时候重新验证——把字幕换一版再跑一次，就能知道时间是否还成立。
 * 这正是总任务说明要求"证据必须可定位"的含义。
 *
 * 可序列化：它是声明文件（`<workId>.facts.json`）的一部分，由人手写。
 */
@Serializable
data class RevealAnchor(
    /** 所属集（条目内集序，允许小数） */
    val episodeNumber: Double,
    /**
     * 台词引用。与字幕做**归一化后**的包含匹配（见 [normalizeSubtitleText]）。
     * 必须足够长以避免误匹配；过短的引用由提取器直接拒绝。
     */
    val quote: String,
) {
    /** 引用是否短到不适合作为证据。 */
    val isTooShort: Boolean get() = normalizeSubtitleText(quote).length < MIN_QUOTE_LENGTH

    companion object {
        /**
         * 引用长度下限（归一化后字符数）。
         *
         * 依据：两三个字的引用（"是我"、"真的？"）在整集字幕里几乎必然误命中，
         * 一旦误命中就会把揭晓时间**提前**，从而让本该受保护的弹幕被放行。
         * 宁可要求更长的引用，也不要冒这个方向的错。
         */
        const val MIN_QUOTE_LENGTH: Int = 6
    }
}

/** 提取失败的类别。 */
enum class BoundaryExtractionFailure {
    /** 该集没有字幕 */
    NO_SUBTITLE_FOR_EPISODE,

    /** 引文太短，不足以作为证据 */
    QUOTE_TOO_SHORT,

    /** 在字幕里找不到引文 */
    QUOTE_NOT_FOUND,

    /** 引文在字幕里出现多次，无法确定哪一处是揭晓点 */
    QUOTE_AMBIGUOUS,
}

/** 单条锚的提取结果。 */
sealed interface AnchorExtraction {
    /** 成功：返回该锚对应的时间区间（毫秒） */
    data class Resolved(val startMillis: Long, val endMillis: Long, val cueIndex: Int) : AnchorExtraction

    data class Failed(val reason: BoundaryExtractionFailure, val detail: String) : AnchorExtraction
}

/** 一条事实的揭晓边界提取结果。 */
sealed interface BoundaryExtraction {
    /**
     * 成功解析出时间。
     *
     * [latestMillis] 取所有锚中最晚的 cue **结束时间**，即"完整揭晓的上界"——
     * 与 [RevealBoundary] 的保守约定一致（用上界判定解锁）。
     */
    data class Resolved(
        val episodeNumber: Double,
        val earliestMillis: Long,
        val latestMillis: Long,
        val precision: String,
    ) : BoundaryExtraction

    /**
     * 没有可用锚：**时间保持未知**。
     *
     * 这不是错误而是正常状态——"这条事实在第 3 集揭晓，但字幕里没有可定位的台词"。
     * 此时必须产出 [VerificationState] 不足以自动解锁的事实，而**不是**编一个时间。
     */
    data class TimingUnknown(val episodeNumber: Double, val reason: String) : BoundaryExtraction

    /** 提取失败。生成流程必须把它当作错误报告出来，而不是跳过。 */
    data class Failed(val failures: List<Pair<RevealAnchor, AnchorExtraction.Failed>>) : BoundaryExtraction
}

/**
 * 从字幕推导揭晓边界。
 *
 * 判定规则（每条都为了"宁可保守，不可提前解锁"）：
 *
 * 1. 引文归一化后长度不足 [RevealAnchor.MIN_QUOTE_LENGTH] → 拒绝。
 * 2. 引文在整集中出现**多于一次** → 拒绝。多义意味着无法确定揭晓点，
 *    而猜错方向会把揭晓时间提前。
 * 3. 所有锚都必须命中；任一锚找不到 → 整条事实报失败，不产出"部分时间"。
 * 4. 时间区间 = 最早锚的 cue 起点 .. 最晚锚的 cue 终点。
 * 5. `precision` 依锚数量与是否同一 cue 给出 "cue" / "cues"，
 *    明确它**不是**"秒级精确"。
 */
object RevealBoundaryExtractor {

    fun extract(
        anchors: List<RevealAnchor>,
        cuesByEpisode: Map<Double, List<SubtitleCue>>,
    ): BoundaryExtraction {
        if (anchors.isEmpty()) {
            return BoundaryExtraction.TimingUnknown(
                episodeNumber = Double.NaN,
                reason = "没有提供证据锚",
            )
        }

        val episode = anchors.first().episodeNumber
        if (anchors.any { it.episodeNumber != episode }) {
            return BoundaryExtraction.Failed(
                anchors.map {
                    it to AnchorExtraction.Failed(
                        BoundaryExtractionFailure.QUOTE_NOT_FOUND,
                        "同一条事实的证据锚必须属于同一集，发现 ${it.episodeNumber}",
                    )
                },
            )
        }

        val cues = cuesByEpisode[episode]
            ?: return BoundaryExtraction.TimingUnknown(
                episodeNumber = episode,
                reason = "第 $episode 集没有字幕文件",
            )

        val resolved = mutableListOf<AnchorExtraction.Resolved>()
        val failures = mutableListOf<Pair<RevealAnchor, AnchorExtraction.Failed>>()

        for (anchor in anchors) {
            when (val r = resolveAnchor(anchor, cues)) {
                is AnchorExtraction.Resolved -> resolved += r
                is AnchorExtraction.Failed -> failures += anchor to r
            }
        }

        if (failures.isNotEmpty()) return BoundaryExtraction.Failed(failures)

        val start = resolved.minOf { it.startMillis }
        val end = resolved.maxOf { it.endMillis }
        return BoundaryExtraction.Resolved(
            episodeNumber = episode,
            earliestMillis = start,
            latestMillis = end,
            precision = if (resolved.size == 1) "cue" else "cues(${resolved.size})",
        )
    }

    private fun resolveAnchor(
        anchor: RevealAnchor,
        cues: List<SubtitleCue>,
    ): AnchorExtraction {
        if (anchor.isTooShort) {
            return AnchorExtraction.Failed(
                BoundaryExtractionFailure.QUOTE_TOO_SHORT,
                "引文归一化后不足 ${RevealAnchor.MIN_QUOTE_LENGTH} 个字符：" +
                        "'${normalizeSubtitleText(anchor.quote)}'",
            )
        }
        val needle = normalizeSubtitleText(anchor.quote)
        val hits = cues.filter { matchesSubtitleQuote(it.normalizedText, needle) }
        return when (hits.size) {
            0 -> AnchorExtraction.Failed(
                BoundaryExtractionFailure.QUOTE_NOT_FOUND,
                "在第 ${anchor.episodeNumber} 集字幕里找不到引文：'${anchor.quote.take(40)}'",
            )

            1 -> AnchorExtraction.Resolved(
                startMillis = hits[0].startMillis,
                endMillis = hits[0].endMillis,
                cueIndex = hits[0].index,
            )

            else -> AnchorExtraction.Failed(
                BoundaryExtractionFailure.QUOTE_AMBIGUOUS,
                "引文在第 ${anchor.episodeNumber} 集出现 ${hits.size} 次" +
                        "（cue ${hits.joinToString(",") { it.index.toString() }}），" +
                        "无法确定哪一处是揭晓点；请改用更长的引文",
            )
        }
    }
}
