/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.policy

import me.him188.ani.danmaku.localguard.knowledge.AlignmentResolver
import me.him188.ani.danmaku.localguard.knowledge.BaselineMapping
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment

/**
 * 当前播放位置。
 *
 * 约束（总任务说明第 8.3 条）：必须是**播放器实际媒体位置**，
 * 不得使用墙上时钟或"开始播放后经过多久"。
 */
data class PlaybackPosition(
    /** 当前集（条目内集序，允许小数） */
    val episodeNumber: Double,
    /** 当前集内媒体位置（毫秒） */
    val positionMillis: Long,
) {
    init {
        require(positionMillis >= 0) { "positionMillis must be >= 0" }
    }
}

/** 单条事实的揭晓评估结果。 */
data class FactEvaluation(
    val factId: String,
    /** 是否已可讨论（已揭晓且满足余量） */
    val unlocked: Boolean,
    /** 未解锁时的原因 */
    val reason: SpoilerBlockReason?,
)

/**
 * 时间线评估器。
 *
 * 判定式（总任务说明第 8.4 条）：
 * ```
 * 当前基准剧情进度的可靠下界 ≥ 完整揭晓时间的上界 + 档位保守余量
 * ```
 *
 * 跨集**先按显式剧集顺序判断**，不直接比较不同集的毫秒数。
 *
 * 刻意不做的事：
 * - **不**把未知时间当作"无限远"后宣称已查明；未知走独立分支。
 * - **不**使用历史最大进度、预加载进度、下载进度或"看完"标记；
 *   传入的 [PlaybackPosition] 必须就是当前真实位置（拖回开头即回到开头）。
 * - **不**在未映射区段线性插值。
 */
class TimelineEvaluator(
    private val pack: StoryKnowledgePack,
) {

    /**
     * 评估某条事实在当前位置是否已解锁。
     */
    fun evaluate(
        factId: String,
        position: PlaybackPosition,
        alignment: TimeAlignment,
        policy: TierPolicy,
    ): FactEvaluation {
        val fact = pack.factsById[factId]
            ?: return FactEvaluation(factId, unlocked = false, reason = SpoilerBlockReason.TimingUnknown)

        // 覆盖范围内永不解锁
        if (fact.neverUnlocksInScope) {
            return FactEvaluation(factId, unlocked = false, reason = SpoilerBlockReason.NeverUnlocksInScope)
        }

        // 1) 跨集：先按显式剧集顺序判断
        val revealEpisode = fact.reveal.episodeNumber
        if (position.episodeNumber > revealEpisode) {
            return FactEvaluation(factId, unlocked = true, reason = null)
        }
        if (position.episodeNumber < revealEpisode) {
            return FactEvaluation(factId, unlocked = false, reason = SpoilerBlockReason.FutureEpisode)
        }

        // 2) 同集：需要可靠时间与可信核对状态
        if (!fact.reveal.verification.trustedForUnlock) {
            // 时间未知或核对状态不足 → 不得自动解锁
            return FactEvaluation(factId, unlocked = false, reason = SpoilerBlockReason.TimingUnknown)
        }

        val upper = fact.reveal.conservativeUpperBoundMillis
            ?: return FactEvaluation(factId, unlocked = false, reason = SpoilerBlockReason.TimingUnknown)

        val mapped: BaselineMapping = AlignmentResolver.toBaseline(position.positionMillis, alignment)
        if (!mapped.mapped || mapped.baselineMillis == null) {
            return FactEvaluation(
                factId,
                unlocked = false,
                reason = if (alignment is TimeAlignment.Unaligned) {
                    SpoilerBlockReason.AlignmentUnverified
                } else {
                    SpoilerBlockReason.UnmappedRegion
                },
            )
        }

        val unlockAt = upper + policy.revealMarginMillis
        return if (mapped.baselineMillis >= unlockAt) {
            FactEvaluation(factId, unlocked = true, reason = null)
        } else {
            FactEvaluation(factId, unlocked = false, reason = SpoilerBlockReason.SameEpisodeNotYetRevealed)
        }
    }
}

/**
 * 单条弹幕的过滤判定引擎。
 *
 * 职责边界：只回答"这条弹幕在当前档位、当前播放位置下是否允许显示"。
 * 不负责排队、缓存、模型调用与显示层装配（由上层负责）。
 *
 * 单调性保证（必须由属性测试覆盖）：
 * ```
 * blocked(LENIENT) ⊆ blocked(BALANCED) ⊆ blocked(STRICT)
 * ```
 * 实现上依赖两条不变量：
 * 1. 阈值随档位单调**下降**（严格档阈值更低 → 更容易命中）；
 * 2. 余量随档位单调**上升**（严格档余量更大 → 更晚解锁）。
 */
class DanmakuGuardEngine(
    private val pack: StoryKnowledgePack,
    private val alignment: TimeAlignment,
) {
    private val timeline = TimelineEvaluator(pack)

    fun decide(
        semantics: DanmakuSemantics,
        position: PlaybackPosition,
        policy: TierPolicy,
    ): GuardDecision {
        // --- 0) 分析失败优先于一切判断 ---
        // 与"语义不确定"不同：那是能力降级（按档位保守处理），这是分析过程本身坏了。
        // 此时未审核内容不得显示（总任务说明第 12 节），也不得因为"分数看起来不高"而放行。
        if (semantics.analysisFailed) {
            return GuardDecision.Failed(
                reason = semantics.failureReason.ifBlank { "analysis failed" },
            )
        }

        // --- A) 内容维度：与剧情时间无关 ---
        val contentCategory = worstContentCategory(semantics)
        if (contentCategory != null && semantics.score(contentCategory) >= policy.contentViolationThreshold) {
            return GuardDecision.BlockedContent(contentCategory, semantics.score(contentCategory), policy.tier)
        }

        // --- B) 剧透维度 ---
        val spoiler = worstSpoilerCategory(semantics, policy) ?: return GuardDecision.Visible

        // 没有关联事实：无法证明安全。只要有足够强的剧透信号就保守屏蔽。
        if (semantics.factIds.isEmpty()) {
            return GuardDecision.BlockedSpoiler(
                category = spoiler.first,
                factId = "",
                score = spoiler.second,
                tier = policy.tier,
                reason = SpoilerBlockReason.TimingUnknown,
            )
        }

        // 多事实：逐项检查，**只要有一项仍未解锁就屏蔽**
        var sawAnyLocked = false
        var firstReason: SpoilerBlockReason? = null
        for (factId in semantics.factIds) {
            val eval = timeline.evaluate(factId, position, alignment, policy)
            if (!eval.unlocked) {
                sawAnyLocked = true
                if (firstReason == null) firstReason = eval.reason
            }
        }

        if (!sawAnyLocked) return GuardDecision.Visible

        val reason = firstReason ?: SpoilerBlockReason.TimingUnknown

        // ---- 不确定性降级 ----
        // 两类不确定性必须分开处理，且都不能让"更宽松的档位"反而放出更多内容：
        //
        // (a) 对齐/映射类：本集时间映射不可用。宽松/均衡只在高可信时屏蔽，严格一律屏蔽。
        // (b) 事实可用性类：时间未知或核对状态不足 —— 这类根本不能证明"已揭晓"，
        //     因此所有档位都按"保守门槛"处理，而不是当成普通内容放行。
        val isAvailabilityUncertain = reason == SpoilerBlockReason.TimingUnknown
        if (isAvailabilityUncertain) {
            if (spoiler.second < policy.unknownFactFloor) {
                // 证据不足：按档位保留（宽松保留更多）。这属于"证据不足的推测"，
                // 不是"已确认的未来事实"，因此允许放行。
                return GuardDecision.Visible
            }
            return GuardDecision.BlockedSpoiler(
                category = spoiler.first,
                factId = semantics.factIds.first(),
                score = spoiler.second,
                tier = policy.tier,
                reason = reason,
            )
        }

        val alignmentRelated = reason == SpoilerBlockReason.AlignmentUnverified ||
            reason == SpoilerBlockReason.UnmappedRegion
        if (alignmentRelated && policy.onUnmappedAlignment == UnmappedPolicy.BlockOnlyHighConfidenceSpoilers) {
            val highConfidenceFloor = (spoiler.second).coerceAtLeast(
                maxOf(policy.explicitSpoilerThreshold, policy.hintSpoilerThreshold),
            )
            if (spoiler.second < highConfidenceFloor) {
                return GuardDecision.Visible
            }
        }

        return GuardDecision.BlockedSpoiler(
            category = spoiler.first,
            factId = semantics.factIds.first(),
            score = spoiler.second,
            tier = policy.tier,
            reason = reason,
        )
    }

    private fun worstContentCategory(semantics: DanmakuSemantics): GuardCategory? {
        var best: GuardCategory? = null
        var bestScore = -1.0
        for (category in GuardCategory.entries) {
            if (!category.isContentViolation) continue
            val s = semantics.score(category)
            if (s > bestScore) {
                bestScore = s
                best = category
            }
        }
        return best?.takeIf { bestScore > 0.0 }
    }

    /** 返回 (类别, 分数)：达到本档阈值的最强剧透类别；未达到则 null。 */
    private fun worstSpoilerCategory(
        semantics: DanmakuSemantics,
        policy: TierPolicy,
    ): Pair<GuardCategory, Double>? {
        var best: Pair<GuardCategory, Double>? = null

        val explicit = semantics.score(GuardCategory.SPOILER_EXPLICIT)
        if (explicit >= policy.explicitSpoilerThreshold) {
            best = GuardCategory.SPOILER_EXPLICIT to explicit
        }

        val hint = semantics.score(GuardCategory.SPOILER_HINT)
        if (hint >= policy.hintSpoilerThreshold) {
            if (best == null || hint > best.second) {
                best = GuardCategory.SPOILER_HINT to hint
            }
        }

        return best
    }
}
