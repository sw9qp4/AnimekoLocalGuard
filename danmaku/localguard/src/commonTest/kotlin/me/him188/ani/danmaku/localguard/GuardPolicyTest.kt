/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.AlignmentResolver
import me.him188.ani.danmaku.localguard.knowledge.AlignmentSegment
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardDecision
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.SpoilerBlockReason
import me.him188.ani.danmaku.localguard.policy.TierPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MIN = 60_000L

/**
 * 片源时间对齐测试。
 *
 * 覆盖：固定偏移、分段映射、未映射区段不插值、未对齐不当作偏移 0。
 */
class AlignmentTest {

    @Test
    fun constantOffsetMapsExactly() {
        val m = AlignmentResolver.toBaseline(30_000L, TimeAlignment.ConstantOffset(0L))
        assertTrue(m.mapped, "常量偏移 0 应当映射成功")
        assertEquals(30_000L, m.baselineMillis)
    }

    @Test
    fun constantOffsetAppliesShift() {
        // 基准 = 实际 - 10s
        val m = AlignmentResolver.toBaseline(30_000L, TimeAlignment.ConstantOffset(-10_000L))
        assertEquals(20_000L, m.baselineMillis, "应减去 10 秒片头")
    }

    @Test
    fun constantOffsetErrorShiftsConservatively() {
        // 误差必须按保守方向：让基准时间更大，从而更晚解锁
        val m = AlignmentResolver.toBaseline(30_000L, TimeAlignment.ConstantOffset(0L, errorMillis = 5_000L))
        assertEquals(35_000L, m.baselineMillis, "误差应向后偏移（保守）")
    }

    @Test
    fun unalignedIsNotTreatedAsZeroOffset() {
        val m = AlignmentResolver.toBaseline(42_000L, TimeAlignment.Unaligned)
        assertFalse(m.mapped, "未对齐不得标记为已映射")
        assertEquals(null, m.baselineMillis, "未对齐不得返回一个看似可用的基准时间")
        assertEquals("alignment-unverified", m.note)
    }

    @Test
    fun segmentedMapsInsideSegment() {
        // 第一段：实际 0–60s → 基准 0–50s。实际 30s（中点）→ 基准 25s
        val m = AlignmentResolver.toBaseline(30_000L, Fixtures.segmented)
        assertTrue(m.mapped)
        assertEquals(25_000L, m.baselineMillis)
        assertEquals("segment-rescaled", m.note)
    }

    @Test
    fun segmentedMapsSecondSegment() {
        // 第二段：实际 60–120s → 基准 50–110s。实际 90s（中点）→ 基准 80s
        val m = AlignmentResolver.toBaseline(90_000L, Fixtures.segmented)
        assertTrue(m.mapped)
        assertEquals(80_000L, m.baselineMillis)
    }

    @Test
    fun segmentBoundaryIsInclusive() {
        assertEquals(50_000L, AlignmentResolver.toBaseline(60_000L, Fixtures.segmented).baselineMillis)
        assertEquals(110_000L, AlignmentResolver.toBaseline(120_000L, Fixtures.segmented).baselineMillis)
    }

    @Test
    fun unmappedRegionIsNotInterpolated() {
        // 分段只覆盖到实际 120s；150s 属于未映射区
        val m = AlignmentResolver.toBaseline(150_000L, Fixtures.segmented)
        assertFalse(m.mapped, "未映射区段不得标记为已映射")
        assertEquals(null, m.baselineMillis, "未映射区段不得插值出高可信结果")
        assertEquals("unmapped-region", m.note)
    }

    @Test
    fun gapBetweenSegmentsIsUnmapped() {
        val withGap = TimeAlignment.Segmented(
            listOf(
                AlignmentSegment(0L, 10_000L, 0L, 10_000L),
                AlignmentSegment(20_000L, 30_000L, 20_000L, 30_000L),
            ),
        )
        val inGap = AlignmentResolver.toBaseline(15_000L, withGap)
        assertFalse(inGap.mapped, "分段之间的空隙必须视为未映射")
        assertEquals(null, inGap.baselineMillis)
    }

    @Test
    fun ratePreservingSegmentIsNoted() {
        val oneToOne = TimeAlignment.Segmented(listOf(AlignmentSegment(0L, 10_000L, 5_000L, 15_000L)))
        assertEquals("segment-1:1", AlignmentResolver.toBaseline(1_000L, oneToOne).note)
    }
}

/**
 * 三档策略测试。
 *
 * 覆盖：单调嵌套（属性测试）、余量语义、严格档不因对齐失败而更宽松。
 */
class TierPolicyTest {

    @Test
    fun marginsAreMonotonic() {
        assertTrue(
            TierPolicy.LENIENT.revealMarginMillis <= TierPolicy.BALANCED.revealMarginMillis,
            "宽松余量必须 ≤ 均衡",
        )
        assertTrue(
            TierPolicy.BALANCED.revealMarginMillis <= TierPolicy.STRICT.revealMarginMillis,
            "均衡余量必须 ≤ 严格",
        )
    }

    @Test
    fun thresholdsAreMonotonicDecreasing() {
        assertTrue(
            TierPolicy.LENIENT.explicitSpoilerThreshold >= TierPolicy.BALANCED.explicitSpoilerThreshold,
            "宽松阈值必须 ≥ 均衡（宽松更难命中）",
        )
        assertTrue(
            TierPolicy.BALANCED.explicitSpoilerThreshold >= TierPolicy.STRICT.explicitSpoilerThreshold,
            "均衡阈值必须 ≥ 严格",
        )
        assertTrue(
            TierPolicy.LENIENT.hintSpoilerThreshold >= TierPolicy.BALANCED.hintSpoilerThreshold,
            "暗示阈值单调性（宽松 ≥ 均衡）",
        )
        assertTrue(
            TierPolicy.BALANCED.hintSpoilerThreshold >= TierPolicy.STRICT.hintSpoilerThreshold,
            "暗示阈值单调性（均衡 ≥ 严格）",
        )
    }

    @Test
    fun defaultTierIsBalanced() {
        assertEquals(GuardTier.BALANCED, GuardTier.Default, "默认档位必须是均衡")
    }

    @Test
    fun marginInitialValuesAreDocumentedTestValues() {
        // 这三个值是测试初值而不是已验证最优值；本测试固定它们，防止被无意改动。
        assertEquals(0L, TierPolicy.LENIENT.revealMarginMillis)
        assertEquals(15_000L, TierPolicy.BALANCED.revealMarginMillis)
        assertEquals(30_000L, TierPolicy.STRICT.revealMarginMillis)
    }

    @Test
    fun everyTierHasPolicy() {
        for (tier in GuardTier.entries) {
            val p = TierPolicy.of(tier)
            assertEquals(tier, p.tier, "档位与策略必须对应")
        }
    }
}

/**
 * 判定引擎核心行为测试。
 *
 * 全部使用**虚构**剧情（见 [Fixtures]）。
 */
class DanmakuGuardEngineTest {

    private fun engine(
        alignment: TimeAlignment = Fixtures.aligned,
        pack: me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack = Fixtures.pack(),
    ) = me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine(pack, alignment)

    private fun semantics(
        explicit: Double = 0.0,
        hint: Double = 0.0,
        content: Double = 0.0,
        facts: Set<String> = emptySet(),
    ) = me.him188.ani.danmaku.localguard.policy.DanmakuSemantics(
        scores = buildMap {
            if (explicit > 0) put(GuardCategory.SPOILER_EXPLICIT, explicit)
            if (hint > 0) put(GuardCategory.SPOILER_HINT, hint)
            if (content > 0) put(GuardCategory.EXPLICIT_OR_HARASSMENT, content)
        },
        factIds = facts,
    )

    private fun pos(episode: Double, millis: Long) =
        me.him188.ani.danmaku.localguard.policy.PlaybackPosition(episode, millis)

    // ---------- 时间边界 ----------

    @Test
    fun spoilerBeforeRevealIsBlockedAtSameEpisode() {
        val d = engine().decide(
            semantics(explicit = 0.95, facts = setOf("F_IDENTITY")),
            pos(1.0, 1 * MIN), // 揭晓在 5:20
            TierPolicy.BALANCED,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(SpoilerBlockReason.SameEpisodeNotYetRevealed, blocked.reason)
    }

    @Test
    fun spoilerAfterRevealPlusMarginIsVisible() {
        // 揭晓上界 5:20，均衡余量 15s → 5:35 起可见
        val d = engine().decide(
            semantics(explicit = 0.95, facts = setOf("F_IDENTITY")),
            pos(1.0, 5 * MIN + 35_000L),
            TierPolicy.BALANCED,
        )
        assertIs<GuardDecision.Visible>(d)
    }

    @Test
    fun marginBoundaryIsExact() {
        val e = engine()
        val s = semantics(explicit = 0.95, facts = setOf("F_IDENTITY"))
        // 5:34 仍未到（上界 5:20 + 15s = 5:35）
        assertIs<GuardDecision.BlockedSpoiler>(e.decide(s, pos(1.0, 5 * MIN + 34_000L), TierPolicy.BALANCED))
        // 恰好 5:35 解锁
        assertIs<GuardDecision.Visible>(e.decide(s, pos(1.0, 5 * MIN + 35_000L), TierPolicy.BALANCED))
    }

    @Test
    fun strictUnlocksLaterThanLenient() {
        val e = engine()
        val s = semantics(explicit = 0.95, facts = setOf("F_IDENTITY"))
        // 5:20 整：宽松（余量 0）已解锁；严格（余量 30s）未解锁
        assertIs<GuardDecision.Visible>(e.decide(s, pos(1.0, 5 * MIN + 20_000L), TierPolicy.LENIENT))
        val strict = assertIs<GuardDecision.BlockedSpoiler>(
            e.decide(s, pos(1.0, 5 * MIN + 20_000L), TierPolicy.STRICT),
        )
        assertEquals(SpoilerBlockReason.SameEpisodeNotYetRevealed, strict.reason)
    }

    @Test
    fun futureEpisodeIsBlockedRegardlessOfMilliseconds() {
        // 第 3 集事实在只看第 1 集时（哪怕毫秒数很大）必须屏蔽
        val d = engine().decide(
            semantics(explicit = 0.95, facts = setOf("F_ENDING")),
            pos(1.0, 999 * MIN),
            TierPolicy.LENIENT,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(SpoilerBlockReason.FutureEpisode, blocked.reason, "跨集必须先按剧集顺序判断")
    }

    @Test
    fun pastEpisodeIsUnlocked() {
        val d = engine().decide(
            semantics(explicit = 0.95, facts = setOf("F_IDENTITY")),
            pos(2.0, 0L),
            TierPolicy.STRICT,
        )
        assertIs<GuardDecision.Visible>(d, "已播完的集数不再受保护")
    }

    // ---------- 未知信息 ----------

    @Test
    fun unknownTimingIsNotAutoUnlocked() {
        val e = engine()
        // F_UNKNOWN_TIME 的揭晓集是第 2 集；这里把当前位置放在**同一集**，
        // 才能真正走到"时间未知"分支（若放到第 3 集，会先被剧集顺序解锁，测不到本分支）。
        val s = semantics(explicit = 0.99, facts = setOf("F_UNKNOWN_TIME"))
        for (tier in GuardTier.entries) {
            val d = e.decide(s, pos(2.0, 999 * MIN), TierPolicy.of(tier))
            val blocked = assertIs<GuardDecision.BlockedSpoiler>(d, "时间未知时不得自动解锁（$tier）")
            assertEquals(SpoilerBlockReason.TimingUnknown, blocked.reason)
        }
    }

    @Test
    fun explicitEpisodeOrderUnlocksEvenWhenTimingIsUnknown() {
        // 记录一条容易被误读的语义：**跨集先按显式剧集顺序判断**。
        // 第 3 集播放时，第 2 集的事实已经在前一集揭晓完毕，
        // 因此即使该事实缺少集内精确时间，也应当解锁。
        // 这与"时间未知不得自动解锁"不矛盾：后者针对的是**同一集内**无法证明是否已揭晓。
        val e = engine()
        val s = semantics(explicit = 0.99, facts = setOf("F_UNKNOWN_TIME"))
        for (tier in GuardTier.entries) {
            assertIs<GuardDecision.Visible>(
                e.decide(s, pos(3.0, 0L), TierPolicy.of(tier)),
                "已播完的集数不再受保护（$tier）",
            )
        }
    }

    @Test
    fun unknownTimingWithWeakEvidenceIsRetainedByLenientButNotStrict() {
        // 证据不足的"时间未知"事实（同一集内）：宽松保留，严格屏蔽
        val e = engine()
        val s = semantics(explicit = 0.60, facts = setOf("F_UNKNOWN_TIME"))
        assertIs<GuardDecision.Visible>(
            e.decide(s, pos(2.0, 30 * MIN), TierPolicy.LENIENT),
            "宽松档对证据不足者更宽容（0.60 < 0.95）",
        )
        assertIs<GuardDecision.BlockedSpoiler>(
            e.decide(s, pos(2.0, 30 * MIN), TierPolicy.STRICT),
            "严格档对同一输入必须更保守（0.60 ≥ 0.55）",
        )
    }

    @Test
    fun unknownFactFloorIsMonotonicAndAboveSpoilerThreshold() {
        val tiers = listOf(TierPolicy.LENIENT, TierPolicy.BALANCED, TierPolicy.STRICT)
        for (p in tiers) {
            assertTrue(
                p.unknownFactFloor >= p.explicitSpoilerThreshold,
                "unknownFactFloor 必须 ≥ explicitSpoilerThreshold（${p.tier}）",
            )
        }
        assertTrue(
            TierPolicy.LENIENT.unknownFactFloor >= TierPolicy.BALANCED.unknownFactFloor &&
                TierPolicy.BALANCED.unknownFactFloor >= TierPolicy.STRICT.unknownFactFloor,
            "unknownFactFloor 必须随档位单调下降",
        )
    }

    @Test
    fun unverifiedFactIsNotAutoUnlocked() {
        // F_UNVERIFIED 时间已知（1:00–1:10）但核对状态为 AI 候选
        val d = engine().decide(
            semantics(explicit = 0.99, facts = setOf("F_UNVERIFIED")),
            pos(1.0, 10 * MIN),
            TierPolicy.LENIENT,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(
            SpoilerBlockReason.TimingUnknown, blocked.reason,
            "核对状态不足时不得自动解锁（结构校验通过 ≠ 剧情已核对）",
        )
    }

    @Test
    fun neverUnlocksFactStaysBlockedLate() {
        val d = engine().decide(
            semantics(explicit = 0.99, facts = setOf("F_NEVER")),
            pos(3.0, 999 * MIN),
            TierPolicy.LENIENT,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(SpoilerBlockReason.NeverUnlocksInScope, blocked.reason)
    }

    // ---------- 多事实 ----------

    @Test
    fun multiFactBlocksIfAnyStillProtected() {
        // F_IDENTITY 在第 2 集已解锁，但 F_ENDING（第 3 集）仍未解锁 → 必须屏蔽
        val d = engine().decide(
            semantics(explicit = 0.95, facts = setOf("F_IDENTITY", "F_ENDING")),
            pos(2.0, 30 * MIN),
            TierPolicy.LENIENT,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(
            SpoilerBlockReason.FutureEpisode, blocked.reason,
            "不能匹配到一个已知事实就忽略其余未来事实",
        )
    }

    @Test
    fun multiFactVisibleOnlyWhenAllUnlocked() {
        val d = engine().decide(
            semantics(explicit = 0.95, facts = setOf("F_IDENTITY", "F_MOTIVE")),
            pos(2.0, 11 * MIN),
            TierPolicy.LENIENT,
        )
        assertIs<GuardDecision.Visible>(d)
    }

    @Test
    fun spoilerWithoutFactIsBlockedConservatively() {
        val d = engine().decide(
            semantics(explicit = 0.95, facts = emptySet()),
            pos(1.0, 0L),
            TierPolicy.BALANCED,
        )
        assertIs<GuardDecision.BlockedSpoiler>(d, "无法证明安全时不得默认放行")
    }

    // ---------- 内容维度（与时间无关） ----------

    @Test
    fun contentViolationIsBlockedInEveryTier() {
        val e = engine()
        val s = semantics(content = 0.99)
        for (tier in GuardTier.entries) {
            val d = e.decide(s, pos(1.0, 0L), TierPolicy.of(tier))
            assertIs<GuardDecision.BlockedContent>(d, "低俗/攻击类不因档位或时间而放行（$tier）")
        }
    }

    // ---------- 正常内容 ----------

    @Test
    fun normalContentIsVisible() {
        val d = engine().decide(
            me.him188.ani.danmaku.localguard.policy.DanmakuSemantics(
                scores = mapOf(GuardCategory.NORMAL to 0.99, GuardCategory.CRITICISM to 0.9),
            ),
            pos(1.0, 0L),
            TierPolicy.STRICT,
        )
        assertIs<GuardDecision.Visible>(d, "普通批评/吐槽不因档位被屏蔽")
    }

    @Test
    fun speculationIsVisible() {
        val d = engine().decide(
            me.him188.ani.danmaku.localguard.policy.DanmakuSemantics(
                scores = mapOf(GuardCategory.SPECULATION to 0.99),
            ),
            pos(1.0, 0L),
            TierPolicy.STRICT,
        )
        assertIs<GuardDecision.Visible>(d, "正常猜测不属于剧透类别")
    }

    // ---------- 对齐失败降级 ----------

    @Test
    fun unalignedDegradesConservatively() {
        val e = engine(alignment = Fixtures.unaligned)
        // 明确剧透 + 高可信 → 对齐失败时仍屏蔽
        val d = e.decide(
            semantics(explicit = 0.99, facts = setOf("F_IDENTITY")),
            pos(1.0, 99 * MIN),
            TierPolicy.BALANCED,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(SpoilerBlockReason.AlignmentUnverified, blocked.reason)
    }

    @Test
    fun unmappedRegionHasItsOwnReason() {
        // 分段映射只到实际 120s；150s 落在未映射区
        val e = engine(alignment = Fixtures.segmented)
        val d = e.decide(
            semantics(explicit = 0.99, facts = setOf("F_IDENTITY")),
            pos(1.0, 150_000L),
            TierPolicy.BALANCED,
        )
        val blocked = assertIs<GuardDecision.BlockedSpoiler>(d)
        assertEquals(SpoilerBlockReason.UnmappedRegion, blocked.reason)
    }

    @Test
    fun alignmentFailureDoesNotMakeStrictLaxerThanLenient() {
        // 同一输入下，严格档的屏蔽集合必须 ⊇ 宽松档
        val e = engine(alignment = Fixtures.unaligned)
        val s = semantics(hint = 0.85, facts = setOf("F_IDENTITY"))
        val lenient = e.decide(s, pos(1.0, 0L), TierPolicy.LENIENT)
        val strict = e.decide(s, pos(1.0, 0L), TierPolicy.STRICT)
        val lenientBlocked = lenient !is GuardDecision.Visible
        val strictBlocked = strict !is GuardDecision.Visible
        assertTrue(
            !lenientBlocked || strictBlocked,
            "严格档不能因对齐失败比宽松档放出更多内容",
        )
    }

    // ---------- 拖动进度条 ----------

    @Test
    fun seekingBackReLocksContent() {
        val e = engine()
        val s = semantics(explicit = 0.95, facts = setOf("F_IDENTITY"))
        // 先播到 6:00（已解锁）
        assertIs<GuardDecision.Visible>(e.decide(s, pos(1.0, 6 * MIN), TierPolicy.BALANCED))
        // 拖回 1:00 → 必须重新屏蔽（不使用历史最大进度解锁）
        assertIs<GuardDecision.BlockedSpoiler>(e.decide(s, pos(1.0, 1 * MIN), TierPolicy.BALANCED))
    }
}
