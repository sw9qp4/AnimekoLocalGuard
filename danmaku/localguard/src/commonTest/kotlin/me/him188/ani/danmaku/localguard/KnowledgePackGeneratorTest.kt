/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.FactDeclaration
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackCodec
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackGenerator
import me.him188.ani.danmaku.localguard.knowledge.RevealAnchor
import me.him188.ani.danmaku.localguard.knowledge.StoryEpisode
import me.him188.ani.danmaku.localguard.knowledge.SubtitleCue
import me.him188.ani.danmaku.localguard.knowledge.ValidationSeverity
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.knowledge.loadFromText
import me.him188.ani.danmaku.localguard.knowledge.parseSubtitles
import me.him188.ani.danmaku.localguard.knowledge.KnowledgeLoadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 剧情包生成器测试。
 *
 * 生成器是 G2 的入口：把"人手写的事实声明"与"带时间字幕"合成为可发布的包。
 * 三条不可违背的规则在这里逐条验证：
 *
 * 1. **不发明时间**：锚定位不到时时间必须保持未知，绝不填猜测值。
 * 2. **不静默丢事实**：提取失败必须产出可见的提示。
 * 3. **不放过结构错误**：结构校验报告必须随包一起产出。
 *
 * 全部使用虚构字幕与虚构事实声明，不代表任何真实作品。
 */
class KnowledgePackGeneratorTest {

    /** 虚构字幕：三集，各含可定位的台词。 */
    private val subtitles = mapOf(
        1.0 to parseSubtitles(
            """
            1
            00:05:00,000 --> 00:05:20,000
            虚构角色甲其实是虚构角色乙的同伴

            2
            00:10:00,000 --> 00:10:30,000
            别的台词
            """.trimIndent(),
            "ep1.vtt",
        ).cues,
        2.0 to parseSubtitles(
            """
            1
            00:10:00,000 --> 00:10:30,000
            虚构角色乙的动机是保护甲
            """.trimIndent(),
            "ep2.vtt",
        ).cues,
        3.0 to parseSubtitles(
            """
            1
            00:20:00,000 --> 00:20:40,000
            结局是甲与乙分开
            """.trimIndent(),
            "ep3.vtt",
        ).cues,
    )

    private val episodes = listOf(
        StoryEpisode(1.0, stableId = "ep1", displayLabel = "第1集"),
        StoryEpisode(2.0, stableId = "ep2", displayLabel = "第2集"),
        StoryEpisode(3.0, stableId = "ep3", displayLabel = "第3集"),
    )

    private val entities = Fixtures.pack().entities

    private fun generate(
        declarations: List<FactDeclaration>,
        cues: Map<Double, List<SubtitleCue>> = subtitles,
    ) = KnowledgePackGenerator.generate(
        workId = Fixtures.WORK_ID,
        title = "虚构测试作品",
        seasonLabel = "S1",
        coveredEpisodes = 1.0..3.0,
        episodes = episodes,
        entities = entities,
        declarations = declarations,
        cuesByEpisode = cues,
        contentVersion = "generated-1",
        sourceNote = "测试用虚构数据",
    )

    private fun declaration(
        id: String = "F_A",
        anchors: List<RevealAnchor> = emptyList(),
        episodeWhenUnknown: Double? = 2.0,
        verification: VerificationState = VerificationState.VERIFIED,
    ) = FactDeclaration(
        factId = id,
        proposition = "虚构命题 $id",
        entityIds = listOf("E_A"),
        anchors = anchors,
        episodeNumberWhenTimingUnknown = episodeWhenUnknown,
        sourceId = "FIXTURE",
        verification = verification,
    )

    // ---------- 成功路径 ----------

    @Test
    fun `generates a pack with extracted timing`() {
        val result = generate(
            listOf(
                declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴"))),
                declaration("F_B", listOf(RevealAnchor(2.0, "虚构角色乙的动机是保护甲"))),
                declaration("F_C", listOf(RevealAnchor(3.0, "结局是甲与乙分开"))),
            ),
        )
        assertEquals(3, result.pack.facts.size)
        val fact = result.pack.factsById.getValue("F_A")
        assertEquals(1.0, fact.reveal.episodeNumber)
        assertEquals(5 * 60_000L, fact.reveal.earliestMillis)
        assertEquals(5 * 60_000L + 20_000L, fact.reveal.latestMillis)
        assertTrue(fact.reveal.hasTiming, "有可定位字幕时时间必须被推导出来")
    }

    @Test
    fun `generated pack survives serialization and reload`() {
        val result = generate(
            listOf(declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")))),
        )
        val reloaded = assertIs<KnowledgeLoadResult.Loaded>(
            loadFromText(KnowledgePackCodec.encode(result.pack)),
        )
        assertEquals(result.pack, reloaded.pack, "生成的包必须能原样读回")
    }

    @Test
    fun `generated pack records a locatable evidence reference`() {
        val result = generate(
            listOf(declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")))),
        )
        val evidence = result.pack.factsById.getValue("F_A").evidence
        assertTrue(evidence.isNotEmpty(), "结论必须可复核：至少记录锚引文")
        assertTrue(evidence.any { it.kind == "subtitle-quote" })
        assertTrue(evidence.any { it.locator.contains("ep1") })
    }

    // ---------- 规则 1：不发明时间 ----------

    @Test
    fun `unlocatable anchor leaves timing unknown instead of guessing`() {
        val result = generate(
            listOf(declaration("F_A", listOf(RevealAnchor(1.0, "字幕里根本没有这句话"))))
        )
        val fact = result.pack.factsById.getValue("F_A")
        assertNull(fact.reveal.earliestMillis, "找不到证据时不得填时间")
        assertNull(fact.reveal.latestMillis)
        assertFalse(fact.reveal.hasTiming)
        assertEquals("unknown", fact.reveal.precision)
    }

    @Test
    fun `no anchors means timing unknown at the declared episode`() {
        val result = generate(listOf(declaration("F_A", anchors = emptyList())))
        val fact = result.pack.factsById.getValue("F_A")
        assertEquals(2.0, fact.reveal.episodeNumber, "集号来自声明")
        assertNull(fact.reveal.earliestMillis, "没有锚就没有时间，不得猜")
        assertFalse(fact.reveal.hasTiming)
    }

    @Test
    fun `missing subtitle for an episode yields unknown timing not a guess`() {
        val result = generate(
            declarations = listOf(declaration("F_A", listOf(RevealAnchor(1.0, "某句证据台词")))) ,
            cues = emptyMap(),
        )
        val fact = result.pack.factsById.getValue("F_A")
        assertNull(fact.reveal.earliestMillis)
        assertTrue(result.warnings.any { it.code == "TIMING_UNKNOWN" })
    }

    // ---------- 规则 2：不静默丢事实 ----------

    @Test
    fun `ambiguous anchor produces a visible error notice`() {
        val duplicated = mapOf(
            1.0 to listOf(
                SubtitleCue(1, 1_000L, 2_000L, "这句话在整集里出现了两次"),
                SubtitleCue(2, 900_000L, 920_000L, "这句话在整集里出现了两次"),
            ),
        )
        val result = generate(
            declarations = listOf(declaration("F_A", listOf(RevealAnchor(1.0, "这句话在整集里出现了两次")))),
            cues = duplicated,
        )
        assertTrue(
            result.errors.any { it.factId == "F_A" && it.code == "QUOTE_AMBIGUOUS" },
            "有歧义的引文必须产出可见错误：${result.notices}",
        )
        assertNull(result.pack.factsById.getValue("F_A").reveal.earliestMillis)
    }

    @Test
    fun `a broken fact does not discard the other facts`() {
        val result = generate(
            listOf(
                declaration("F_OK", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴"))),
                declaration("F_BAD", listOf(RevealAnchor(1.0, "完全不存在的一句话"))),
            ),
        )
        assertEquals(2, result.pack.facts.size, "一次坏证据不该让整个包作废")
        assertTrue(result.pack.factsById.getValue("F_OK").reveal.hasTiming)
        assertFalse(result.pack.factsById.getValue("F_BAD").reveal.hasTiming)
        assertTrue(result.errors.any { it.factId == "F_BAD" })
    }

    @Test
    fun `fact with neither anchor nor episode is reported as not locatable`() {
        val result = generate(listOf(declaration("F_A", anchors = emptyList(), episodeWhenUnknown = null)))
        assertTrue(
            result.errors.any { it.code == "FACT_NOT_LOCATABLE" },
            "既无锚又无集号必须报错，而不是静默产出一条无法定位的事实：${result.notices}",
        )
    }

    @Test
    fun `duplicate fact ids are reported`() {
        val result = generate(
            listOf(
                declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴"))),
                declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴"))),
            ),
        )
        assertTrue(result.errors.any { it.code == "DUPLICATE_DECLARATION" })
        assertEquals(1, result.pack.facts.size, "重复声明应被去重，只产出一条事实")
    }

    @Test
    fun `few facts produces a warning`() {
        val result = generate(
            listOf(declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")))),
        )
        assertTrue(result.warnings.any { it.code == "FEW_FACTS" }, "${result.notices}")
    }

    // ---------- 规则 3：结构校验随包产出 ----------

    @Test
    fun `validation report is produced alongside the pack`() {
        val result = generate(
            listOf(declaration("F_A", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")))),
        )
        assertTrue(result.isStructurallyValid, "结构问题：${result.validation.structuralIssues}")
    }

    @Test
    fun `anchor pointing outside the covered range surfaces as a structural error`() {
        // 锚指向第 9 集，但覆盖范围只有 1..3：必须被校验器抓到
        val cues = mapOf(9.0 to listOf(SubtitleCue(1, 1_000L, 2_000L, "覆盖范围之外的台词")))
        val result = generate(
            declarations = listOf(declaration("F_A", listOf(RevealAnchor(9.0, "覆盖范围之外的台词")))),
            cues = cues,
        )
        assertFalse(result.isStructurallyValid, "覆盖范围之外的揭晓集必须报结构错误")
        assertTrue(result.validation.structuralIssues.any { it.severity == ValidationSeverity.ERROR })
    }

    @Test
    fun `unverified declaration produces evidence issue not structural error`() {
        val result = generate(
            listOf(
                declaration(
                    "F_A",
                    listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")),
                    verification = VerificationState.AI_CANDIDATE,
                ),
            ),
        )
        // 结构与证据分开报告：核对状态不足属于证据问题，不是结构问题
        assertTrue(result.isStructurallyValid, "核对状态不足不应报结构错误")
        assertTrue(result.pack.factsById.getValue("F_A").verification.trustedForUnlock.not())
    }

    // ---------- 端到端：生成的包能真的驱动判定 ----------

    @Test
    fun `generated pack drives the guard engine`() {
        val result = generate(
            listOf(
                declaration("F_LATE", listOf(RevealAnchor(3.0, "结局是甲与乙分开"))),
                declaration("F_EARLY", listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴"))),
            ),
        )
        val pack = result.pack

        // 第 2 集：F_LATE（第 3 集揭晓）必须仍受保护
        val engine = me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine(
            pack,
            me.him188.ani.danmaku.localguard.knowledge.TimeAlignment.ConstantOffset(0L),
        )
        val semantics = me.him188.ani.danmaku.localguard.policy.DanmakuSemantics(
            scores = mapOf(me.him188.ani.danmaku.localguard.policy.GuardCategory.SPOILER_EXPLICIT to 0.99),
            factIds = setOf("F_LATE"),
        )
        val decision = engine.decide(
            semantics = semantics,
            position = me.him188.ani.danmaku.localguard.policy.PlaybackPosition(2.0, 0L),
            policy = me.him188.ani.danmaku.localguard.policy.TierPolicy.of(
                me.him188.ani.danmaku.localguard.policy.GuardTier.BALANCED,
            ),
        )
        assertIs<me.him188.ani.danmaku.localguard.policy.GuardDecision.BlockedSpoiler>(
            decision,
            "生成的包必须能真的驱动判定，而不只是能通过校验",
        )
    }
}
