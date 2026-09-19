/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.policy

import me.him188.ani.danmaku.localguard.knowledge.StoryFact
import me.him188.ani.danmaku.localguard.knowledge.VerificationState

/**
 * 一条弹幕的语义分析结果（模型输出）。
 *
 * 关键约定：**分数是"该类别成立的程度"，不是概率。**
 * 按独立验证集校准后才可当作概率使用；本阶段阈值是可测量的取舍参数，
 * 不得写成"0.9 表示 90% 准确"。
 */
data class DanmakuSemantics(
    /** 每个类别的置信度，取值 [0, 1]。未出现的类别视为 0。 */
    val scores: Map<GuardCategory, Double>,
    /**
     * 该弹幕涉及的剧情事实 id（可多个）。
     * 一条弹幕可能透露多个事实，**必须逐项检查**：只要仍有一项未解锁，就按档位屏蔽。
     */
    val factIds: Set<String> = emptySet(),
    /** 是否为明确的否定句（"不是他"）。否定句不能一律判安全，也不能一律判违规。 */
    val negated: Boolean = false,
    /** 是否为疑问句 */
    val interrogative: Boolean = false,
    /** 是否为反讽 */
    val ironic: Boolean = false,
    /** 分析所用模型/规则版本，用于缓存键与一致性校验 */
    val analyzerVersion: String = "unknown",
    /**
     * 分析本身是否失败（模型损坏 / 不可用 / 推理出错）。
     *
     * **必须与"语义不确定"分开**（总任务说明第 12 节）：
     * 语义不确定是"我们判断不出这条弹幕说了什么"，属于能力降级，按档位保守处理；
     * 而分析失败是"分析过程本身坏了"，此时**未审核内容不得显示**，
     * 不能静默退回原来的弹幕还声称保护中。
     *
     * 置为 true 时 [scores] 与 [factIds] 的内容不参与判定。
     */
    val analysisFailed: Boolean = false,
    /** 失败原因，仅用于诊断；为空时表示未提供。**不得**写入弹幕正文。 */
    val failureReason: String = "",
) {
    init {
        require(scores.values.all { it in 0.0..1.0 }) { "scores must be within [0, 1]" }
    }

    fun score(category: GuardCategory): Double = scores[category] ?: 0.0

    companion object {
        /** 无任何语义信息（例如模型不可用）。**不是**"安全"的意思。 */
        val UNKNOWN = DanmakuSemantics(emptyMap(), analyzerVersion = "unavailable")
    }
}

/**
 * 档位策略参数。
 *
 * 三档**共用同一份 [DanmakuSemantics]**，只在这里改变：
 * 分类阈值、未知信息策略、时间保守余量。
 */
data class TierPolicy(
    val tier: GuardTier,
    /**
     * 内容维度（低俗/攻击）阈值：分数 ≥ 该值即屏蔽。
     * 这类与剧情时间无关，所有档位都会屏蔽，只是阈值不同。
     */
    val contentViolationThreshold: Double,
    /** 明确事实型剧透的阈值 */
    val explicitSpoilerThreshold: Double,
    /** 提前暗示型剧透的阈值 */
    val hintSpoilerThreshold: Double,
    /**
     * 时间保守余量（毫秒）。
     * 判定式：`基准进度 ≥ 揭晓上界 + 余量` 才允许解锁。
     * 任何档位都**不能**通过减小余量来提前放出已确认的未来事实。
     */
    val revealMarginMillis: Long,
    /**
     * 片源无法映射（对齐未验证）时的策略。
     * 严格档不得比对位失败更宽松的档位放出更多内容。
     */
    val onUnmappedAlignment: UnmappedPolicy,
    /**
     * "无法确认该事实已揭晓"时（时间未知 / 核对状态不足）的保守置信度门槛。
     *
     * 与 [onUnmappedAlignment] 区分：对齐问题只影响时间映射是否可用，
     * 而这里影响的是"这条事实到底能不能用来判断"。二者都是不确定性，
     * 但**未知时间绝不能被更宽松的档位当成正常内容放行**。
     *
     * 该值必须 ≥ 本档剧透阈值，且随档位单调**下降**（严格档更容易触发保守屏蔽），
     * 否则会破坏三档单调嵌套。
     */
    val unknownFactFloor: Double,
) {
    init {
        require(contentViolationThreshold in 0.0..1.0) { "contentViolationThreshold out of range" }
        require(explicitSpoilerThreshold in 0.0..1.0) { "explicitSpoilerThreshold out of range" }
        require(hintSpoilerThreshold in 0.0..1.0) { "hintSpoilerThreshold out of range" }
        require(unknownFactFloor in 0.0..1.0) { "unknownFactFloor out of range" }
        require(revealMarginMillis >= 0) { "revealMarginMillis must be >= 0" }
        require(unknownFactFloor >= explicitSpoilerThreshold) {
            "unknownFactFloor must be >= explicitSpoilerThreshold"
        }
    }

    companion object {
        /**
         * 宽松：只屏蔽高可信的明确剧透与明显不合适内容；较多保留证据不足的暗示与推测。
         */
        val LENIENT = TierPolicy(
            tier = GuardTier.LENIENT,
            contentViolationThreshold = 0.80,
            explicitSpoilerThreshold = 0.85,
            hintSpoilerThreshold = 0.95,
            revealMarginMillis = 0L,
            onUnmappedAlignment = UnmappedPolicy.BlockOnlyHighConfidenceSpoilers,
            unknownFactFloor = 0.95,
        )

        /**
         * 均衡：默认档。扩大对可信暗示和较隐晦不合适内容的识别。
         */
        val BALANCED = TierPolicy(
            tier = GuardTier.BALANCED,
            contentViolationThreshold = 0.60,
            explicitSpoilerThreshold = 0.60,
            hintSpoilerThreshold = 0.80,
            revealMarginMillis = 15_000L,
            onUnmappedAlignment = UnmappedPolicy.BlockOnlyHighConfidenceSpoilers,
            unknownFactFloor = 0.80,
        )

        /**
         * 严格：防剧透优先。对涉及尚未揭晓事件的可疑表述更保守，允许较高正常弹幕误杀。
         */
        val STRICT = TierPolicy(
            tier = GuardTier.STRICT,
            contentViolationThreshold = 0.35,
            explicitSpoilerThreshold = 0.35,
            hintSpoilerThreshold = 0.55,
            revealMarginMillis = 30_000L,
            onUnmappedAlignment = UnmappedPolicy.BlockAllSpoilersAboveThreshold,
            unknownFactFloor = 0.55,
        )

        /**
         * 余量初值说明：0s / 15s / 30s 是**测试初值，不是已验证的最优值**。
         * 必须保持 宽松 ≤ 均衡 ≤ 严格，否则会破坏三档单调嵌套。
         */
        val ByTier: Map<GuardTier, TierPolicy> = mapOf(
            GuardTier.LENIENT to LENIENT,
            GuardTier.BALANCED to BALANCED,
            GuardTier.STRICT to STRICT,
        )

        fun of(tier: GuardTier): TierPolicy = ByTier.getValue(tier)
    }
}

/**
 * 片源对齐失败时的降级策略。
 *
 * 总任务说明第 8.10 条要求：来源或版本不确定时显示"分钟级对齐未验证"，并按明确策略降级。
 * 关键约束：**严格档不能因对齐失败比弱档放出更多内容。**
 */
enum class UnmappedPolicy {
    /**
     * 只屏蔽"高可信"的剧透（高于本档阈值一个固定余量）。
     * 用于宽松/均衡：对齐失败时不因不确定而过度屏蔽。
     */
    BlockOnlyHighConfidenceSpoilers,

    /**
     * 只要达到本档剧透阈值就屏蔽，不考虑时间。
     * 用于严格：对齐失败时按最保守处理。
     */
    BlockAllSpoilersAboveThreshold,
}

/**
 * 判定结果。
 *
 * 状态集刻意区分排队、未判定、过期、失败（总任务说明第 12 节）：
 * 这些状态**不能从统计分母里消失**。
 */
sealed interface GuardDecision {
    /** 允许显示 */
    data object Visible : GuardDecision

    /** 按类别屏蔽（低俗/攻击），与剧情时间无关 */
    data class BlockedContent(
        val category: GuardCategory,
        val score: Double,
        val tier: GuardTier,
    ) : GuardDecision

    /** 按剧透屏蔽：事实在当前位置尚未揭晓 */
    data class BlockedSpoiler(
        val category: GuardCategory,
        val factId: String,
        val score: Double,
        val tier: GuardTier,
        /** 用于诊断：为什么判定为未揭晓（不含剧情正文） */
        val reason: SpoilerBlockReason,
    ) : GuardDecision

    /** 语义分析尚未完成（排队中）。**不算允许**，按超时策略处理。 */
    data object Pending : GuardDecision

    /** 判定超时（到达显示期限仍未完成）。本次跳过，不稍后补发。 */
    data object Expired : GuardDecision

    /** 分析或知识包故障。与"语义不确定"分开。 */
    data class Failed(val reason: String) : GuardDecision
}

/** 剧透屏蔽的具体原因（用于诊断，不含剧情正文）。 */
enum class SpoilerBlockReason {
    /** 事实在当前剧集之后的剧集才揭晓 */
    FutureEpisode,

    /** 同集内进度尚未到达揭晓上界 + 余量 */
    SameEpisodeNotYetRevealed,

    /** 事实时间未知，且其核对状态不足以自动解锁 */
    TimingUnknown,

    /** 事实在覆盖范围内永不解锁 */
    NeverUnlocksInScope,

    /** 片源对齐未验证，按降级策略保守屏蔽 */
    AlignmentUnverified,

    /** 揭晓时间落在未映射区段 */
    UnmappedRegion,
}
