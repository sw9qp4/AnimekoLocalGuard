/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.localguard.knowledge.AlignmentGeneration
import me.him188.ani.danmaku.localguard.knowledge.FactDeclaration
import me.him188.ani.danmaku.localguard.knowledge.KnowledgeLoadResult
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackCodec
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackGenerator
import me.him188.ani.danmaku.localguard.knowledge.RevealAnchor
import me.him188.ani.danmaku.localguard.knowledge.StoryEpisode
import me.him188.ani.danmaku.localguard.knowledge.TextStoryKnowledgeSource
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.knowledge.generateAlignment
import me.him188.ani.danmaku.localguard.knowledge.loadFromText
import me.him188.ani.danmaku.localguard.knowledge.pairSubtitlesByText
import me.him188.ani.danmaku.localguard.knowledge.parseSubtitles
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardDecision
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.PlaybackPosition
import me.him188.ani.danmaku.localguard.policy.TierPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 端到端管线测试：**字幕 → 剧情包 → 落盘 → 装载 → 对齐 → 驱动判定**。
 *
 * 为什么需要它：各段都有自己的单测，但"每段单独正确"不等于"接得上"。
 * 本项目已经反复出现"实现了但没接线"的问题（缓存类、`GuardDecision.Failed`、
 * knowledge/alignment 参数、`modelFailed`、校验器），因此这里明确地把整条链路走一遍，
 * 让**接口层面的断裂**能被抓到，而不是等到真机上才发现资料根本没被用上。
 *
 * 全部使用虚构字幕与虚构事实声明。
 */
class PipelineEndToEndTest {

    private val workId = Fixtures.WORK_ID

    /**
     * 基准字幕：三集，每集三条台词。
     *
     * 每集给三条而不是一条，因为对齐生成要求至少 [me.him188.ani.danmaku.localguard.knowledge.MIN_MATCHES]
     * 个配对——真实字幕本来就有几十上百条，一条的夹具既不现实也测不到对齐。
     */
    private val baselineSubtitles = mapOf(
        1.0 to """
            1
            00:05:00,000 --> 00:05:20,000
            虚构角色甲其实是虚构角色乙的同伴

            2
            00:06:00,000 --> 00:06:20,000
            这是第一集里第二条足够长的虚构台词

            3
            00:07:00,000 --> 00:07:20,000
            这是第一集里第三条足够长的虚构台词
        """.trimIndent(),
        2.0 to """
            1
            00:10:00,000 --> 00:10:30,000
            虚构角色乙的动机是保护甲

            2
            00:11:00,000 --> 00:11:20,000
            这是第二集里第二条足够长的虚构台词

            3
            00:12:00,000 --> 00:12:20,000
            这是第二集里第三条足够长的虚构台词
        """.trimIndent(),
        3.0 to """
            1
            00:20:00,000 --> 00:20:40,000
            结局是甲与乙分开

            2
            00:21:00,000 --> 00:21:20,000
            这是第三集里第二条足够长的虚构台词

            3
            00:22:00,000 --> 00:22:20,000
            这是第三集里第三条足够长的虚构台词
        """.trimIndent(),
    )

    private fun cuesOf(episode: Double) = parseSubtitles(baselineSubtitles.getValue(episode)).cues

    private fun declarations() = listOf(
        FactDeclaration(
            factId = "F_IDENTITY_E2E",
            proposition = "虚构角色甲的真实身份",
            entityIds = listOf("E_A"),
            anchors = listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")),
            sourceId = "FIXTURE",
            verification = VerificationState.VERIFIED,
        ),
        FactDeclaration(
            factId = "F_ENDING_E2E",
            proposition = "虚构作品结局",
            entityIds = listOf("E_A", "E_B"),
            anchors = listOf(RevealAnchor(3.0, "结局是甲与乙分开")),
            sourceId = "FIXTURE",
            verification = VerificationState.VERIFIED,
        ),
    )

    private fun generatePack(): String {
        val result = KnowledgePackGenerator.generate(
            workId = workId,
            title = "虚构测试作品",
            seasonLabel = "S1",
            coveredEpisodes = 1.0..3.0,
            episodes = listOf(
                StoryEpisode(1.0, stableId = "ep1"),
                StoryEpisode(2.0, stableId = "ep2"),
                StoryEpisode(3.0, stableId = "ep3"),
            ),
            entities = Fixtures.pack().entities,
            declarations = declarations(),
            cuesByEpisode = mapOf(
                1.0 to cuesOf(1.0),
                2.0 to cuesOf(2.0),
                3.0 to cuesOf(3.0),
            ),
            contentVersion = "e2e-1",
        )
        assertTrue(
            result.isStructurallyValid,
            "生成的包必须结构合法：${result.validation.structuralIssues}",
        )
        return KnowledgePackCodec.encode(result.pack)
    }

    /** 造一版"实际片源"字幕：整体比基准晚 [offsetMillis]（例如片头更长）。 */
    private fun actualSubtitlesWithOffset(offsetMillis: Long): Map<Double, List<me.him188.ani.danmaku.localguard.knowledge.SubtitleCue>> =
        baselineSubtitles.mapValues { (_, text) ->
            val shifted = text.lineSequence().map { line ->
                if (line.contains("-->")) {
                    line.split("-->").joinToString("-->") { side ->
                        val code = side.trim()
                        shiftTimeCode(code, offsetMillis)
                    }
                } else {
                    line
                }
            }.joinToString("\n")
            parseSubtitles(shifted).cues
        }

    private fun shiftTimeCode(code: String, deltaMillis: Long): String {
        val ms = me.him188.ani.danmaku.localguard.knowledge.parseTimeCode(code) ?: return code
        val shifted = ms + deltaMillis
        val h = shifted / 3_600_000
        val m = (shifted % 3_600_000) / 60_000
        val s = (shifted % 60_000) / 1_000
        val milli = shifted % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    // ---------- 端到端 ----------

    @Test
    fun `subtitles become a pack that survives the disk and drives a decision`() {
        val text = generatePack()
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(loadFromText(text))
        val pack = loaded.pack

        // 第 2 集：F_ENDING 在第 3 集才揭晓 → 必须仍受保护
        val engine = DanmakuGuardEngine(pack, TimeAlignment.ConstantOffset(0L))
        val decision = engine.decide(
            semantics = DanmakuSemantics(
                scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                factIds = setOf("F_ENDING_E2E"),
            ),
            position = PlaybackPosition(2.0, 0L),
            policy = TierPolicy.of(GuardTier.BALANCED),
        )
        assertIs<GuardDecision.BlockedSpoiler>(
            decision,
            "端到端：字幕推导出的时间必须真的能驱动判定",
        )
    }

    @Test
    fun `pack is loaded through the text source used in production`() = runTest {
        val packText = generatePack()
        // 用命名参数：reader 是第一个参数，尾随 lambda 语法会绑错位置。
        val source = TextStoryKnowledgeSource(
            reader = { path ->
                when (path) {
                    "story/$workId.json" -> packText
                    else -> null
                }
            },
        )
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(source.load(workId))
        assertEquals(2, loaded.pack.facts.size)
        assertEquals(
            5 * 60_000L + 20_000L,
            loaded.pack.factsById.getValue("F_IDENTITY_E2E").reveal.latestMillis,
            "字幕里的 5:20 必须原样出现在包与装载结果里",
        )
    }

    @Test
    fun `alignment generated from two subtitle versions is usable by the resolver`() = runTest {
        // 实际片源比基准整体晚 5 秒（片头更长）。
        // 偏移样本的定义是 "基准 − 实际"，因此这里期望 **−5000**：
        // 实际时间要减掉 5 秒才回到基准时间轴。
        val actual = actualSubtitlesWithOffset(5_000L)
        val base = baselineSubtitles.mapValues { (_, t) -> parseSubtitles(t).cues }

        // 逐集配对（对齐是"同一集两版字幕"的关系）
        val samples = base.keys.flatMap { ep ->
            pairSubtitlesByText(actual.getValue(ep), base.getValue(ep)).samples
        }
        val generated = generateAlignment(
            me.him188.ani.danmaku.localguard.knowledge.AlignmentPairing(samples),
        )
        val success = assertIs<AlignmentGeneration.Success>(
            generated,
            "两版字幕整体偏移 5 秒时应能生成对齐",
        )
        assertEquals(
            -5_000L, assertIs<TimeAlignment.ConstantOffset>(success.alignment).offsetMillis,
            "偏移 = 基准 − 实际；实际更晚则为负",
        )
    }

    @Test
    fun `generated pack plus generated alignment produce the expected verdict`() = runTest {
        // 完整链路：包（基准时间轴）+ 对齐（实际→基准）→ 判定
        val pack = assertIs<KnowledgeLoadResult.Loaded>(loadFromText(generatePack())).pack

        val actual = actualSubtitlesWithOffset(5_000L)
        val base = baselineSubtitles.mapValues { (_, t) -> parseSubtitles(t).cues }
        val samples = base.keys.flatMap { ep ->
            pairSubtitlesByText(actual.getValue(ep), base.getValue(ep)).samples
        }
        val alignment = assertIs<AlignmentGeneration.Success>(
            generateAlignment(me.him188.ani.danmaku.localguard.knowledge.AlignmentPairing(samples)),
        ).alignment

        val semantics = DanmakuSemantics(
            scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
            factIds = setOf("F_IDENTITY_E2E"),
        )
        val engine = DanmakuGuardEngine(pack, alignment)

        // 基准揭晓上界 5:20；均衡档余量 15s → 基准 5:35 后解锁。
        // 实际片源比基准晚 5 秒（偏移 −5000），所以实际 5:39:59 时基准才 5:34:59，仍受保护。
        val justBefore = engine.decide(
            semantics, PlaybackPosition(1.0, 5 * 60_000L + 39_000L), TierPolicy.of(GuardTier.BALANCED),
        )
        assertIs<GuardDecision.BlockedSpoiler>(
            justBefore,
            "对齐生效时，实际时间要换算到基准之后才能解锁",
        )

        val wellAfter = engine.decide(
            semantics, PlaybackPosition(1.0, 6 * 60_000L), TierPolicy.of(GuardTier.BALANCED),
        )
        assertIs<GuardDecision.Visible>(wellAfter, "远超揭晓时间后应放行")
    }

    @Test
    fun `a pack with an unlocatable fact still loads and only that fact lacks timing`() {
        val result = KnowledgePackGenerator.generate(
            workId = workId,
            title = "虚构测试作品",
            seasonLabel = "S1",
            coveredEpisodes = 1.0..3.0,
            episodes = listOf(StoryEpisode(1.0), StoryEpisode(2.0), StoryEpisode(3.0)),
            entities = Fixtures.pack().entities,
            declarations = declarations() + FactDeclaration(
                factId = "F_UNLOCATABLE",
                proposition = "找不到证据的虚构事实",
                anchors = listOf(RevealAnchor(1.0, "字幕里根本没有这句话")),
                episodeNumberWhenTimingUnknown = 2.0,
                verification = VerificationState.VERIFIED,
            ),
            cuesByEpisode = mapOf(
                1.0 to cuesOf(1.0),
                2.0 to cuesOf(2.0),
                3.0 to cuesOf(3.0),
            ),
            contentVersion = "e2e-2",
        )
        // 一条定位不到的事实会产出错误提示，但**其余事实照常生成**，包仍可装载
        assertTrue(result.errors.any { it.factId == "F_UNLOCATABLE" })
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(
            loadFromText(KnowledgePackCodec.encode(result.pack)),
            "一条坏证据不应让整包不可用（结构仍然合法）",
        )
        val fact = loaded.pack.factsById.getValue("F_UNLOCATABLE")
        assertEquals(null, fact.reveal.earliestMillis, "定位不到就必须保持时间未知")
        assertTrue(
            loaded.pack.factsById.getValue("F_IDENTITY_E2E").reveal.hasTiming,
            "其余事实的时间必须不受影响",
        )
    }
}
