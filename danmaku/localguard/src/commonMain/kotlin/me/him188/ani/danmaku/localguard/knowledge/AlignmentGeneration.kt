/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.knowledge

/**
 * 从"同一集的基准字幕 + 实际片源字幕"生成片源对齐。
 *
 * ## 为什么需要它
 *
 * 剧情包的揭晓时间写在**基准版本**的时间轴上。观众手上的片源往往不是基准版本
 * （不同压制、不同片头长度、有无 OP/ED、是否删减），两者之间存在时间偏移。
 * 没有对齐数据，[AlignmentResolver] 只能按 [TimeAlignment.Unaligned] 降级，
 * 事实层的时间判断就全部用不上——也就是整套防剧透退化成"什么都不敢判"。
 *
 * ## 怎么算
 *
 * 同一句台词在两版字幕里的出现时间之差，就是该处的偏移。因此：
 * 1. 用归一化后的台词文本把两版字幕的 cue **配对**（复用字幕解析的归一化规则）；
 * 2. 每对给出一个偏移样本；
 * 3. 用样本的**中位数**作为固定偏移——中位数对少量错误配对不敏感，
 *    而平均值会被一个错配拉偏，进而让整集时间整体偏早。
 * 4. 检查残差：若固定偏移下最大偏差超过容忍值，说明存在**非线性剪辑**
 *    （删减/加速），此时改用分段映射；
 * 5. 分段也拟合不了就**明确失败**，让调用方降级为 Unaligned，而不是给一个坏对齐。
 *
 * ## 三条不可违背的规则
 *
 * 1. **样本不足就拒绝**：配对少于 [MIN_MATCHES] 时不给对齐。
 *    用两三个点拟合出的偏移看起来合理，实际可能整体偏移几十秒。
 * 2. **残差过大就拒绝**：宁可 Unaligned（保守：只按高可信屏蔽），
 *    也不要一个系统性偏早的对齐——偏早会直接放行剧透。
 * 3. **不猜**：不做线性插值填补没有配对证据的时间段；分段之间留空隙（未映射区），
 *    [AlignmentResolver] 对这些区段本来就会返回未映射。
 */

/** 一版字幕里的一条 cue 与它的归一化文本。 */
private data class CueAnchor(
    val startMillis: Long,
    val endMillis: Long,
    val normalizedText: String,
)

/** 对齐生成的诊断信息。 */
data class AlignmentNotice(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
)

/** 对齐生成结果。 */
sealed interface AlignmentGeneration {
    /** 成功生成对齐。 */
    data class Success(
        val alignment: TimeAlignment,
        /** 参与拟合的配对数 */
        val matchCount: Int,
        /** 拟合后所有配对的最大偏差（毫秒） */
        val maxResidualMillis: Long,
        val notices: List<AlignmentNotice>,
    ) : AlignmentGeneration

    /**
     * 无法生成可信对齐。
     *
     * **必须**由调用方转成 [TimeAlignment.Unaligned]，而不是"偏移 0"。
     */
    data class Rejected(
        val reason: String,
        val notices: List<AlignmentNotice>,
    ) : AlignmentGeneration
}

/** 一次配对。 */
data class AlignmentSample(
    val actualMillis: Long,
    val baselineMillis: Long,
    val quote: String,
) {
    /** 基准 = 实际 + offset ⇒ offset = 基准 − 实际 */
    val offsetMillis: Long get() = baselineMillis - actualMillis
}

/** 配对结果。 */
data class AlignmentPairing(
    val samples: List<AlignmentSample>,
    val notices: List<AlignmentNotice> = emptyList(),
)

/**
 * 按台词文本把两版字幕配对。
 *
 * 只在**唯一匹配**时配对：一句台词在某版里出现多次时，无法知道对应的是哪一处，
 * 强行配对会引入错误样本，而错误样本会把中位数拉偏。
 */
fun pairSubtitlesByText(
    actualCues: List<SubtitleCue>,
    baselineCues: List<SubtitleCue>,
    minQuoteLength: Int = RevealAnchor.MIN_QUOTE_LENGTH,
): AlignmentPairing {
    val notices = mutableListOf<AlignmentNotice>()

    fun indexUniquely(cues: List<SubtitleCue>, label: String): Map<String, SubtitleCue> {
        val grouped = cues
            .filter { it.normalizedText.length >= minQuoteLength }
            .groupBy { it.normalizedText }
        val duplicated = grouped.count { it.value.size > 1 }
        if (duplicated > 0) {
            notices += AlignmentNotice(
                ValidationSeverity.WARNING, "AMBIGUOUS_CUES",
                "$label 中有 $duplicated 条台词出现多次，已跳过（无法确定对应关系）",
            )
        }
        return grouped.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }

    val actualByText = indexUniquely(actualCues, "实际片源字幕")
    val baselineByText = indexUniquely(baselineCues, "基准字幕")

    val samples = mutableListOf<AlignmentSample>()
    for ((text, actual) in actualByText) {
        val baseline = baselineByText[text] ?: continue
        samples += AlignmentSample(
            actualMillis = actual.startMillis,
            baselineMillis = baseline.startMillis,
            quote = text,
        )
    }
    samples.sortBy { it.actualMillis }

    return AlignmentPairing(samples, notices)
}

/**
 * 从配对样本生成对齐。
 *
 * @param toleranceMillis 残差容忍值。超过它说明固定偏移不够用。
 * @param segmentToleranceMillis 分段拟合的残差容忍值（通常更严）。
 */
fun generateAlignment(
    pairing: AlignmentPairing,
    toleranceMillis: Long = DEFAULT_TOLERANCE_MILLIS,
    segmentToleranceMillis: Long = DEFAULT_SEGMENT_TOLERANCE_MILLIS,
): AlignmentGeneration {
    val notices = pairing.notices.toMutableList()
    val samples = pairing.samples

    if (samples.size < MIN_MATCHES) {
        return AlignmentGeneration.Rejected(
            reason = "可配对的台词只有 ${samples.size} 条，少于 $MIN_MATCHES 条；" +
                    "样本太少时拟合出的偏移可能整体偏差很大",
            notices = notices,
        )
    }

    // 固定偏移：用中位数，避免个别错配把整体拉偏
    val offset = median(samples.map { it.offsetMillis })
    val residuals = samples.map { kotlin.math.abs(it.offsetMillis - offset) }.sorted()

    // 判据用**分位数**而不是最大值。
    //
    // 为什么：一条错配（或一处字幕与片源本来就不同步的小片段）会让最大残差变得很大，
    // 从而把整体推向分段模型；而分段模型段数受限，最终往往整体被拒绝——
    // 于是一个本来完全可用的常量偏移被一条坏样本否掉了。
    // 用 90 分位则表达"绝大多数配对都在容忍范围内"。
    val robustResidual = quantile(residuals, RESIDUAL_QUANTILE)
    val worstResidual = residuals.lastOrNull() ?: 0L

    if (robustResidual <= toleranceMillis) {
        notices += AlignmentNotice(
            ValidationSeverity.WARNING, "CONSTANT_OFFSET_OK",
            "使用固定偏移 ${offset}ms（${samples.size} 个配对，" +
                    "${(RESIDUAL_QUANTILE * 100).toInt()} 分位残差 ${robustResidual}ms，" +
                    "最大 ${worstResidual}ms）",
        )
        if (worstResidual > toleranceMillis) {
            notices += AlignmentNotice(
                ValidationSeverity.WARNING, "OUTLIER_PAIRINGS",
                "有配对的偏差达到 ${worstResidual}ms，可能是错配或小段不同步；" +
                        "已按分位判据接受固定偏移，但这些配对值得人工复核",
            )
        }
        return AlignmentGeneration.Success(
            alignment = TimeAlignment.ConstantOffset(offsetMillis = offset),
            matchCount = samples.size,
            maxResidualMillis = worstResidual,
            notices = notices,
        )
    }

    // 固定偏移不够：可能存在删减/加速等非线性剪辑 → 尝试分段
    notices += AlignmentNotice(
        ValidationSeverity.WARNING, "DRIFT_DETECTED",
        "${(RESIDUAL_QUANTILE * 100).toInt()} 分位残差 ${robustResidual}ms 超过容忍值 " +
                "${toleranceMillis}ms，改用分段映射（可能存在删减或加速）",
    )
    val segmented = fitSegments(samples, segmentToleranceMillis)
    if (segmented == null) {
        return AlignmentGeneration.Rejected(
            reason = "固定偏移与分段拟合都无法达到容忍值（分段容忍 ${segmentToleranceMillis}ms）；" +
                    "这通常意味着字幕与片源并非同一剪辑，或台词配对有误",
            notices = notices,
        )
    }

    val (segments, segmentedMaxResidual) = segmented
    notices += AlignmentNotice(
        ValidationSeverity.WARNING, "SEGMENTED_OK",
        "使用 ${segments.size} 段映射（最大偏差 ${segmentedMaxResidual}ms）",
    )
    return AlignmentGeneration.Success(
        alignment = TimeAlignment.Segmented(segments = segments),
        matchCount = samples.size,
        maxResidualMillis = segmentedMaxResidual,
        notices = notices,
    )
}

/** 至少需要多少个配对才允许给出对齐。 */
const val MIN_MATCHES: Int = 5

/** 固定偏移的残差容忍值。
 *
 * 1000ms 的依据：揭晓判定本来就有秒级余量（均衡档 15s），对齐误差远小于余量时
 * 不会改变结论；而一个几秒的系统性偏早会直接放行剧透。
 */
const val DEFAULT_TOLERANCE_MILLIS: Long = 1_000L

/**
 * 判据所用的残差分位数（0..1）。
 *
 * 0.9 的含义："至少九成配对落在容忍范围内"。
 * 不用最大值：一条错配或一小段本来就不同步会否掉一个完全可用的常量偏移。
 * 不用中位数：那会放过"接近一半的配对都偏了"这种情况。
 */
const val RESIDUAL_QUANTILE: Double = 0.9

/** 取升序列表的分位数（最近秩法，不插值）。 */
private fun quantile(sorted: List<Long>, q: Double): Long {
    if (sorted.isEmpty()) return 0L
    val index = kotlin.math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size) - 1
    return sorted[index]
}

/** 分段拟合的残差容忍值：更严，因为分段本身已经是"更贴身"的模型。 */
const val DEFAULT_SEGMENT_TOLERANCE_MILLIS: Long = 500L

/**
 * 分段数量的上限。
 *
 * 依据：单集片源的实际剪辑点很少（片头有无、广告插播、片尾删减），
 * 几段就能覆盖绝大多数情况。上限设 6 是为了留余量，同时挡住"过度切分"。
 */
const val MAX_SEGMENTS: Int = 6

/**
 * 每个分段至少要覆盖多少个配对样本（按**平均**计）。
 *
 * 为什么必须有这条：只用两个点就能连出一条线，于是"每两个点一段"可以把任意数据
 * 拟合到零残差——包括**交替出现的噪声**。那等于把噪声当成剪辑点，
 * 给出一个看似精确、实则没有依据的时间映射。
 *
 * 用"段数 ≤ 样本数 / 本值"而不是"每段至少 N 点"，是因为锚点由切分点决定，
 * 各段长度本来就不均匀；用平均值能表达同一个意思而不会误伤合理的长短段。
 */
const val MIN_SAMPLES_PER_SEGMENT_AVERAGE: Int = 3

/**
 * 判定"发生了一次剪辑"所需的偏移跳变量（毫秒）。
 *
 * 依据：字幕与片源的正常同步误差在数百毫秒量级，而一次删减/插播造成的偏移
 * 至少是秒级。取 2000ms 能把"噪声"与"剪辑"分开。
 */
const val CUT_DETECTION_THRESHOLD_MILLIS: Long = 2_000L

/**
 * 分段拟合：按**偏移的阶跃**切分，每段用段内偏移的中位数作为常量偏移。
 *
 * 为什么按阶跃切分，而不是"用端点连一条线再检查残差"：
 * 阶梯状数据里只要混入一个属于另一侧的点，端点连线就会被拉斜，
 * 于是段内残差必然超限、整条映射被拒——而实际上那是一处很干净的剪辑。
 * 逐对比较相邻样本的偏移，能直接看到"哪里跳了"，这才是剪辑的直接证据。
 *
 * 段与段之间**不做插值**：段边界只到实际配对样本的时间点，
 * 中间的空隙留给 [AlignmentResolver] 按未映射区处理。
 *
 * 返回 null 表示结构不合理（段太多 / 样本相对段数太少）或段内残差超限。
 */
private fun fitSegments(
    samples: List<AlignmentSample>,
    toleranceMillis: Long,
): Pair<List<AlignmentSegment>, Long>? {
    require(samples.size >= 2) { "segments need at least 2 samples" }
    val sorted = samples.sortedBy { it.actualMillis }

    // 1) 找出偏移发生阶跃的位置。切点 i 表示"第 i 个样本起属于新的一段"，
    //    因此每段的样本区间是 [start_i, start_{i+1} - 1]，**不重叠**。
    val starts = mutableListOf(0)
    for (i in 1 until sorted.size) {
        val jump = kotlin.math.abs(sorted[i].offsetMillis - sorted[i - 1].offsetMillis)
        if (jump >= CUT_DETECTION_THRESHOLD_MILLIS) starts += i
    }

    // 2) 结构约束：段数必须与样本量相称
    val segmentCount = starts.size
    if (segmentCount > MAX_SEGMENTS) return null
    if (segmentCount * MIN_SAMPLES_PER_SEGMENT_AVERAGE > sorted.size) return null

    // 3) 每段一个常量偏移（段内中位数，抵御段内个别错配）
    val segments = mutableListOf<AlignmentSegment>()
    var worst = 0L
    for (i in 0 until segmentCount) {
        val from = starts[i]
        val to = if (i + 1 < segmentCount) starts[i + 1] - 1 else sorted.size - 1
        val slice = sorted.subList(from, to + 1)
        val offset = median(slice.map { it.offsetMillis })
        for (s in slice) {
            worst = maxOf(worst, kotlin.math.abs(s.offsetMillis - offset))
        }
        // 段的时间跨度取该段首末配对样本；常量偏移意味着基准 = 实际 + offset
        segments += AlignmentSegment(
            actualStartMillis = slice.first().actualMillis,
            actualEndMillis = slice.last().actualMillis,
            baselineStartMillis = slice.first().actualMillis + offset,
            baselineEndMillis = slice.last().actualMillis + offset,
        )
    }

    return if (worst <= toleranceMillis) segments to worst else null
}

/** 中位数；空列表调用方已排除。 */
private fun median(values: List<Long>): Long {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        // 偶数个时取两个中间值的平均（向下取整）。取平均而不是取任一侧，
        // 是为了不引入方向性偏差——偏早会放行剧透。
        (sorted[mid - 1] + sorted[mid]) / 2
    }
}
