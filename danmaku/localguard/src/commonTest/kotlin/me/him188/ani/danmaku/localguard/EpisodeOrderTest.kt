/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.EpisodeMappingResult
import me.him188.ani.danmaku.localguard.knowledge.EpisodeOrder
import me.him188.ani.danmaku.localguard.knowledge.EpisodeOrderMapper
import me.him188.ani.danmaku.localguard.knowledge.SpecialEpisodeMapping
import me.him188.ani.danmaku.localguard.knowledge.compareEpisodeOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 剧集序投影测试。
 *
 * 覆盖总任务说明第 7.A 条与第 8.4 条的要求：
 * - 不得把小数集序强制取整
 * - 不得把数据库 ID 当集数（本模块不接收任何 ID，只接收集约序号）
 * - 特别篇按显式顺序，不参与数值比较
 * - 无法解析不得猜测
 */
class EpisodeOrderTest {

    // ---------- 解析 ----------

    @Test
    fun parsesIntegerEpisode() {
        val r = EpisodeOrder.parse("1")
        val n = assertIs<EpisodeOrder.Numbered>(r)
        assertEquals(1.0, n.number)
        assertTrue(!n.partial, "整数不是半集")
    }

    @Test
    fun keepsDecimalEpisodeNumberWithoutRounding() {
        // 上游 EpisodeSort.Normal 只保证整数与 .5，但本模型不得因此把其它小数取整
        for ((raw, expected) in listOf("24.5" to 24.5, "1.1" to 1.1, "7.25" to 7.25, "0.5" to 0.5)) {
            val n = assertIs<EpisodeOrder.Numbered>(EpisodeOrder.parse(raw), "应解析为数值集序: $raw")
            assertEquals(expected, n.number, "小数集序不得被取整: $raw")
        }
    }

    @Test
    fun marksHalfEpisodesAsPartial() {
        assertTrue(assertIs<EpisodeOrder.Numbered>(EpisodeOrder.parse("24.5")).partial, "x.5 应标记为半集")
        assertTrue(!assertIs<EpisodeOrder.Numbered>(EpisodeOrder.parse("24")).partial, "整数不应标记为半集")
        assertTrue(!assertIs<EpisodeOrder.Numbered>(EpisodeOrder.parse("1.25")).partial, "1.25 不是半集")
    }

    @Test
    fun parsesSpecialWithType() {
        val s = assertIs<EpisodeOrder.Special>(EpisodeOrder.parse("3", specialType = "OVA"))
        assertEquals("OVA", s.specialType)
        assertEquals(3.0, s.number)
    }

    @Test
    fun parsesSpecialWithoutNumber() {
        val s = assertIs<EpisodeOrder.Special>(EpisodeOrder.parse("", specialType = "SP"))
        assertEquals("SP", s.specialType)
        assertEquals(null, s.number)
    }

    @Test
    fun unparsableBecomesUnknownAndIsNotGuessed() {
        for (raw in listOf("S", "SP-1", "abc", "-1", "")) {
            val u = assertIs<EpisodeOrder.Unknown>(EpisodeOrder.parse(raw), "无法解析必须标记为 Unknown: '$raw'")
            assertEquals(raw, u.raw, "Unknown 应保留原始串以便诊断")
        }
    }

    @Test
    fun negativeNumberIsNotAcceptedAsEpisode() {
        assertIs<EpisodeOrder.Unknown>(EpisodeOrder.parse("-5"), "负数集序不得被当作有效集序")
    }

    // ---------- 比较语义 ----------

    @Test
    fun normalEpisodesCompareNumerically() {
        assertTrue(compareEpisodeOrder(EpisodeOrder.Numbered(1.0), EpisodeOrder.Numbered(2.0)) < 0)
        assertTrue(compareEpisodeOrder(EpisodeOrder.Numbered(24.5), EpisodeOrder.Numbered(24.0)) > 0)
        assertEquals(0, compareEpisodeOrder(EpisodeOrder.Numbered(3.0), EpisodeOrder.Numbered(3.0)))
    }

    @Test
    fun allNormalComeBeforeAllSpecial() {
        // 与上游 EpisodeSort.compareTo 一致：Normal < Special，无论数值大小
        assertTrue(
            compareEpisodeOrder(EpisodeOrder.Numbered(999.0), EpisodeOrder.Special("OVA", 1.0)) < 0,
            "正片整体排在特别篇之前（不使用数值跨类比较）",
        )
    }

    @Test
    fun specialsCompareByTypeThenExplicitOrderThenNumber() {
        val ova1 = EpisodeOrder.Special("OVA", 1.0, explicitOrder = 1)
        val ova2 = EpisodeOrder.Special("OVA", 2.0, explicitOrder = 2)
        assertTrue(compareEpisodeOrder(ova1, ova2) < 0, "同类别按序号")

        val sp = EpisodeOrder.Special("SP", 1.0, explicitOrder = 99)
        assertTrue(compareEpisodeOrder(ova1, sp) < 0, "先按类别名比较")

        // 显式顺序优先于类别内序号
        val a = EpisodeOrder.Special("OVA", 9.0, explicitOrder = 1)
        val b = EpisodeOrder.Special("OVA", 1.0, explicitOrder = 2)
        assertTrue(compareEpisodeOrder(a, b) < 0, "显式顺序优先于类别内序号")
    }

    @Test
    fun unknownsSortLastAndAreNotOrderedAmongThemselves() {
        assertTrue(
            compareEpisodeOrder(EpisodeOrder.Special("OVA", 1.0), EpisodeOrder.Unknown("X")) < 0,
            "未知排在最后",
        )
        assertEquals(
            0,
            compareEpisodeOrder(EpisodeOrder.Unknown("X"), EpisodeOrder.Unknown("Y")),
            "两个未知之间无法比较，调用方必须按未知处理",
        )
    }

    // ---------- 映射到知识库 ----------

    @Test
    fun numberedMapsDirectly() {
        val r = EpisodeOrderMapper.toKnowledgeEpisodeNumber(EpisodeOrder.Numbered(7.5))
        assertEquals(7.5, assertIs<EpisodeMappingResult.Mapped>(r).episodeNumber, "小数集序应原样映射")
    }

    @Test
    fun unknownIsUnmappableNotGuessed() {
        val r = EpisodeOrderMapper.toKnowledgeEpisodeNumber(EpisodeOrder.Unknown("S"))
        val u = assertIs<EpisodeMappingResult.Unmappable>(r)
        assertTrue(u.reason.contains("S"), "原因应包含原始串以便诊断")
    }

    @Test
    fun specialRequiresExplicitMappingFromPack() {
        val s = EpisodeOrder.Special("OVA", 2.0)
        val r = EpisodeOrderMapper.toKnowledgeEpisodeNumber(s)
        assertIs<EpisodeMappingResult.SpecialNeedsExplicitMapping>(r, "特别篇必须显式映射，不得当成正片集数")
    }

    @Test
    fun specialResolvesViaKnowledgePackDeclaration() {
        val specials = listOf(
            SpecialEpisodeMapping(specialId = "OVA", label = "OVA", episodeNumber = 7.5),
        )
        val r = EpisodeOrderMapper.resolveSpecial(EpisodeOrder.Special("OVA", 1.0), specials)
        assertEquals(7.5, assertIs<EpisodeMappingResult.Mapped>(r).episodeNumber)
    }

    @Test
    fun specialNotDeclaredInPackIsUnmappable() {
        val r = EpisodeOrderMapper.resolveSpecial(EpisodeOrder.Special("MAD", 1.0), emptyList())
        assertIs<EpisodeMappingResult.Unmappable>(r, "知识包未声明的特别篇不得猜测位置")
    }

    // ---------- 与判定的衔接 ----------

    @Test
    fun decimalEpisodeNumberWorksWithTimelineEvaluator() {
        // 端到端小链路：第 3 集 20:00–20:40 揭晓的事实。
        // 分数取 0.90 并用 SPOILER_EXPLICIT：达到宽松档的明确剧透阈值 0.85，
        // 同时不给内容维度打分，避免内容维度先命中而混淆断言。
        val pack = Fixtures.pack()
        val engine = me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine(pack, Fixtures.aligned)
        val semantics = me.him188.ani.danmaku.localguard.policy.DanmakuSemantics(
            scores = mapOf(me.him188.ani.danmaku.localguard.policy.GuardCategory.SPOILER_EXPLICIT to 0.90),
            factIds = setOf("F_ENDING"),
        )
        val lenient = me.him188.ani.danmaku.localguard.policy.TierPolicy.LENIENT

        // 第 2 集：该事实属于未来集 → 屏蔽
        val at2 = engine.decide(semantics, me.him188.ani.danmaku.localguard.policy.PlaybackPosition(2.0, 0L), lenient)
        val b2 = assertIs<me.him188.ani.danmaku.localguard.policy.GuardDecision.BlockedSpoiler>(at2)
        assertEquals(
            me.him188.ani.danmaku.localguard.policy.SpoilerBlockReason.FutureEpisode,
            b2.reason,
            "跨集必须按剧集顺序判定",
        )

        // 第 3 集但尚未揭晓（0:00 < 20:40）→ 仍屏蔽
        val at3Early = engine.decide(semantics, me.him188.ani.danmaku.localguard.policy.PlaybackPosition(3.0, 0L), lenient)
        val b3 = assertIs<me.him188.ani.danmaku.localguard.policy.GuardDecision.BlockedSpoiler>(at3Early)
        assertEquals(
            me.him188.ani.danmaku.localguard.policy.SpoilerBlockReason.SameEpisodeNotYetRevealed,
            b3.reason,
        )

        // 第 3 集、已过揭晓上界(20:40) → 放行
        val at3Late = engine.decide(
            semantics,
            me.him188.ani.danmaku.localguard.policy.PlaybackPosition(3.0, 21 * 60_000L),
            lenient,
        )
        assertIs<me.him188.ani.danmaku.localguard.policy.GuardDecision.Visible>(at3Late)
    }

    @Test
    fun hintCategoryRespectsTierThreshold() {
        // 记录一个容易被误用的语义：暗示类在**宽松档阈值最高**（0.95），
        // 因此 0.90 的暗示分在宽松档不触发屏蔽，但在严格档（0.55）会触发。
        // 这正是"三档只改阈值、共用同一份语义输出"的直接体现。
        val pack = Fixtures.pack()
        val engine = me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine(pack, Fixtures.aligned)
        val hint = me.him188.ani.danmaku.localguard.policy.DanmakuSemantics(
            scores = mapOf(me.him188.ani.danmaku.localguard.policy.GuardCategory.SPOILER_HINT to 0.90),
            factIds = setOf("F_ENDING"),
        )
        val pos = me.him188.ani.danmaku.localguard.policy.PlaybackPosition(2.0, 0L)

        assertIs<me.him188.ani.danmaku.localguard.policy.GuardDecision.Visible>(
            engine.decide(hint, pos, me.him188.ani.danmaku.localguard.policy.TierPolicy.LENIENT),
            "0.90 < 宽松档暗示阈值 0.95",
        )
        assertIs<me.him188.ani.danmaku.localguard.policy.GuardDecision.BlockedSpoiler>(
            engine.decide(hint, pos, me.him188.ani.danmaku.localguard.policy.TierPolicy.STRICT),
            "0.90 ≥ 严格档暗示阈值 0.55",
        )
    }
}
