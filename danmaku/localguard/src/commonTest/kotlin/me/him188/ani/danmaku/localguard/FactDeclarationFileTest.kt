/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.FactDeclaration
import me.him188.ani.danmaku.localguard.knowledge.FactDeclarationCodec
import me.him188.ani.danmaku.localguard.knowledge.FactDeclarationFile
import me.him188.ani.danmaku.localguard.knowledge.KnowledgeLoadResult
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackCodec
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackGenerator
import me.him188.ani.danmaku.localguard.knowledge.RevealAnchor
import me.him188.ani.danmaku.localguard.knowledge.StoryEpisode
import me.him188.ani.danmaku.localguard.knowledge.SubtitleCue
import me.him188.ani.danmaku.localguard.knowledge.ValidationSeverity
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.knowledge.loadFromText
import me.him188.ani.danmaku.localguard.knowledge.parseSubtitles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 事实声明**文件格式**的测试。
 *
 * 声明文件是产包流程的人工输入端（`<workId>.facts.json`）。它必须能被人手写、
 * 能被工具读回、并且**坏文件要给出可定位的错误**，而不是静默产出空包。
 *
 * 这一层的价值在于消除"给字幕后还要改代码"：声明与字幕都是文件。
 *
 * 全部使用虚构作品与虚构事实。
 */
class FactDeclarationFileTest {

    private val workId = Fixtures.WORK_ID

    private val subtitles = mapOf(
        1.0 to parseSubtitles(
            """
            1
            00:05:00,000 --> 00:05:20,000
            虚构角色甲其实是虚构角色乙的同伴
            """.trimIndent(),
        ).cues,
    )

    private fun declarationFile(
        workIdInFile: String = workId,
        facts: List<FactDeclaration> = listOf(fact()),
        coveredStart: Double = 1.0,
        coveredEnd: Double = 3.0,
        declarationVersion: Int = FactDeclarationFile.CURRENT_DECLARATION_VERSION,
    ) = FactDeclarationFile(
        declarationVersion = declarationVersion,
        workId = workIdInFile,
        title = "虚构测试作品",
        seasonLabel = "S1",
        coveredEpisodesStart = coveredStart,
        coveredEpisodesEnd = coveredEnd,
        episodes = listOf(
            StoryEpisode(1.0, stableId = "ep1"),
            StoryEpisode(2.0, stableId = "ep2"),
            StoryEpisode(3.0, stableId = "ep3"),
        ),
        entities = Fixtures.pack().entities,
        contentVersion = "decl-1",
        facts = facts,
    )

    private fun fact(
        id: String = "F_A",
        anchors: List<RevealAnchor> = listOf(RevealAnchor(1.0, "虚构角色甲其实是虚构角色乙的同伴")),
    ) = FactDeclaration(
        factId = id,
        proposition = "虚构命题 $id",
        entityIds = listOf("E_A"),
        anchors = anchors,
        episodeNumberWhenTimingUnknown = 2.0,
        sourceId = "FIXTURE",
        verification = VerificationState.VERIFIED,
    )

    // ---------- 文件往返 ----------

    @Test
    fun `declaration file survives a round trip`() {
        val file = declarationFile()
        val restored = assertNotNull(FactDeclarationCodec.decodeOrNull(FactDeclarationCodec.encode(file)))
        assertEquals(file, restored, "写出再读回必须完全相等")
    }

    @Test
    fun `covered range is derived from scalars and not written twice`() {
        val text = FactDeclarationCodec.encode(declarationFile())
        assertFalse(
            text.contains("coveredEpisodes\""),
            "覆盖范围是派生值，磁盘上不应出现第二份表示：$text",
        )
        val restored = assertNotNull(FactDeclarationCodec.decodeOrNull(text))
        assertEquals(1.0, restored.coveredEpisodes.start)
        assertEquals(3.0, restored.coveredEpisodes.endInclusive)
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val text = FactDeclarationCodec.encode(declarationFile())
            .replaceFirst("{", """{"futureField":123,""")
        val restored = assertNotNull(
            FactDeclarationCodec.decodeOrNull(text),
            "未知字段必须被忽略，否则一次格式演进就会让所有旧版声明文件失效",
        )
        assertEquals(workId, restored.workId)
    }

    @Test
    fun `broken json returns null instead of throwing`() {
        assertNull(FactDeclarationCodec.decodeOrNull("{ not json"))
        assertNull(FactDeclarationCodec.decodeOrNull(""))
    }

    @Test
    fun `anchors and verification are preserved`() {
        val restored = assertNotNull(
            FactDeclarationCodec.decodeOrNull(FactDeclarationCodec.encode(declarationFile())),
        )
        val f = restored.facts.single()
        assertEquals(VerificationState.VERIFIED, f.verification)
        assertEquals(1, f.anchors.size)
        assertEquals(1.0, f.anchors.single().episodeNumber)
        assertFalse(f.anchors.single().isTooShort)
    }

    // ---------- 从声明文件产包 ----------

    @Test
    fun `generates a valid pack from a declaration file`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertTrue(result.isStructurallyValid, "结构问题：${result.validation.structuralIssues}")
        assertEquals(1, result.pack.facts.size)
        assertTrue(result.pack.factsById.getValue("F_A").reveal.hasTiming)
        assertEquals(5 * 60_000L + 20_000L, result.pack.factsById.getValue("F_A").reveal.latestMillis)
        assertEquals(workId, result.pack.work.workId)
        assertEquals("decl-1", result.pack.contentVersion)
    }

    @Test
    fun `generated pack loads through the production path`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(
            loadFromText(KnowledgePackCodec.encode(result.pack)),
        )
        assertEquals(workId, loaded.pack.work.workId)
    }

    // ---------- 文件级错误必须可定位 ----------

    @Test
    fun `work id mismatch is reported`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(workIdInFile = "SOME_OTHER_WORK"),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertTrue(
            result.errors.any { it.code == "WORK_ID_MISMATCH" },
            "把 A 作品的声明写成 B 作品的文件必须报错：${result.notices}",
        )
    }

    @Test
    fun `declaration version newer than supported is reported`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(
                declarationVersion = FactDeclarationFile.CURRENT_DECLARATION_VERSION + 1,
            ),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertTrue(result.errors.any { it.code == "DECLARATION_VERSION_TOO_NEW" })
    }

    @Test
    fun `inverted covered range is reported`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(coveredStart = 5.0, coveredEnd = 1.0),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertTrue(result.errors.any { it.code == "COVERED_RANGE_INVERTED" })
    }

    @Test
    fun `empty declaration list is reported`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(facts = emptyList()),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertTrue(
            result.errors.any { it.code == "NO_FACTS" },
            "空包没有意义，必须报错而不是产出一个不保护任何内容的包",
        )
    }

    @Test
    fun `missing subtitle for an anchored episode is reported`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(),
            expectedWorkId = workId,
            // 故意不给第 1 集字幕
            cuesByEpisode = emptyMap(),
        )
        assertTrue(
            result.errors.any { it.code == "SUBTITLE_MISSING" },
            "有锚但没有字幕是最该看见的信息：${result.notices}",
        )
        assertNull(
            result.pack.factsById.getValue("F_A").reveal.earliestMillis,
            "缺字幕时时间必须保持未知",
        )
    }

    @Test
    fun `missing subtitle does not block the facts that do have subtitles`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(
                facts = listOf(
                    fact("F_OK"),
                    fact("F_NO_SUB", anchors = listOf(RevealAnchor(2.0, "第二集的一句台词"))),
                ),
            ),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertTrue(result.pack.factsById.getValue("F_OK").reveal.hasTiming, "有字幕的事实不受影响")
        assertFalse(result.pack.factsById.getValue("F_NO_SUB").reveal.hasTiming)
        assertTrue(result.errors.any { it.code == "SUBTITLE_MISSING" })
    }

    @Test
    fun `only anchored episodes are reported as missing`() {
        // 声明里没有锚指向第 3 集 → 不该因为第 3 集没有字幕而报错
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertFalse(
            result.errors.any { it.code == "SUBTITLE_MISSING" },
            "只为**有锚**的集报缺字幕，不能因为别的集没给就报：${result.notices}",
        )
    }

    @Test
    fun `severity is carried from declaration into the pack`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        assertEquals(
            me.him188.ani.danmaku.localguard.policy.SpoilerSeverity.MAJOR,
            result.pack.factsById.getValue("F_A").severity,
        )
    }

    /** 声明文件里的一条提示应当是 ERROR 级别才被视为"必须修正"。 */
    @Test
    fun `file level problems are errors not warnings`() {
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = declarationFile(workIdInFile = "X"),
            expectedWorkId = workId,
            cuesByEpisode = subtitles,
        )
        val mismatch = result.notices.first { it.code == "WORK_ID_MISMATCH" }
        assertEquals(ValidationSeverity.ERROR, mismatch.severity)
    }

    /** 生成的包仍只由工具写出；声明文件本身不含任何推导出的时间。 */
    @Test
    fun `declaration file contains no derived timing fields`() {
        val text = FactDeclarationCodec.encode(declarationFile())
        for (derived in listOf("earliestMillis", "latestMillis", "precision", "reveal")) {
            assertFalse(
                text.contains("\"$derived\""),
                "声明文件不应含推导字段 '$derived'——它是人工输入，不是工具产物：$text",
            )
        }
    }

    /**
     * 示例文件本身必须与当前格式一致。
     *
     * 为什么要有这条：示例文件是给人看的"格式说明"，很容易在格式演进后悄悄失效。
     * 让它进测试，示例一旦与实现脱节就会立刻失败。
     *
     * 工作目录假定为仓库根（独立运行器与 Gradle 测试任务都是如此）。
     * 若在别的目录下运行导致找不到文件，则**跳过**并打印原因——
     * "当前目录不对"不是实现缺陷，不该让测试变红。
     */
    @Test
    fun `shipped example declaration file is valid and produces a pack`() {
        val examplePath = java.io.File("docs/examples/fictional-sample.facts.json")
        if (!examplePath.isFile) {
            println(
                "SKIP 示例声明文件校验：当前工作目录 ${java.io.File(".").absolutePath} " +
                    "下找不到 docs/examples/fictional-sample.facts.json",
            )
            return
        }

        val file = assertNotNull(
            FactDeclarationCodec.decodeOrNull(examplePath.readText()),
            "示例声明文件必须能按当前格式解析：${examplePath.absolutePath}",
        )

        // 示例用的台词来自随仓库发布的虚构字幕 docs/examples/fictional-sample.ep{1,2,3}.vtt
        val cues = exampleCues()
        if (cues == null) {
            println(
                "SKIP 示例声明文件校验：当前工作目录 ${java.io.File(".").absolutePath} " +
                    "下找不到 docs/examples/fictional-sample.ep1.vtt 或示例字幕自身解析失败",
            )
            return
        }

        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = file,
            expectedWorkId = file.workId,
            cuesByEpisode = cues,
        )
        assertTrue(
            result.errors.isEmpty(),
            "示例声明文件不应产生任何 ERROR：${result.errors}",
        )
        assertTrue(result.isStructurallyValid, "${result.validation.structuralIssues}")
        assertEquals(file.facts.size, result.pack.facts.size)

        // 带锚的三条必须推出时间；没锚的那条必须保持未知；永不解锁的必须保留标记
        assertTrue(result.pack.factsById.getValue("F_IDENTITY").reveal.hasTiming)
        assertTrue(result.pack.factsById.getValue("F_MOTIVE").reveal.hasTiming)
        assertTrue(result.pack.factsById.getValue("F_ENDING").reveal.hasTiming)
        assertNull(
            result.pack.factsById.getValue("F_NO_ANCHOR").reveal.earliestMillis,
            "没有可定位台词时必须保持时间未知",
        )
        assertTrue(result.pack.factsById.getValue("F_NEVER").neverUnlocksInScope)
    }

    /**
     * 从随仓库发布的示例字幕里推导出的揭晓时间必须**正好**是这些值。
     *
     * 为什么值得写死具体毫秒：这是整条链路上唯一一处"人工写的引文 → 具体时间"的转换，
     * 一旦字幕被改动、或匹配/时间码解析回归，时间就会漂移。漂移方向若是**提前**，
     * 本该受保护的弹幕就会被放行——这正是本项目最不能出的错。
     *
     * 期望值同时钉住三件事：cue 的**结束**时间才是上界、锚点引文命中的是正确的那一条 cue、
     * 以及毫秒位按三位小数解释（`.750` = 750ms，不是 75ms 或 7500ms）。
     */
    @Test
    fun `shipped example subtitles resolve to exact reveal bounds`() {
        val cues = exampleCues()
        if (cues == null) {
            println("SKIP 示例字幕时间校验：找不到示例字幕文件")
            return
        }

        val file = assertNotNull(
            FactDeclarationCodec.decodeOrNull(
                java.io.File("docs/examples/fictional-sample.facts.json").readText(),
            ),
        )
        val result = KnowledgePackGenerator.generateFromDeclarations(
            file = file,
            expectedWorkId = file.workId,
            cuesByEpisode = cues,
        )

        val identity = result.pack.factsById.getValue("F_IDENTITY").reveal
        assertEquals(22_750L, identity.earliestMillis, "F_IDENTITY 起点应为 ep1 cue 8 的 00:00:22.750")
        assertEquals(26_500L, identity.latestMillis, "F_IDENTITY 上界应为该 cue 的结束时间 00:00:26.500")

        val motive = result.pack.factsById.getValue("F_MOTIVE").reveal
        assertEquals(13_500L, motive.earliestMillis, "F_MOTIVE 起点应为 ep2 cue 5 的 00:00:13.500")
        assertEquals(17_000L, motive.latestMillis, "F_MOTIVE 上界应为该 cue 的结束时间 00:00:17.000")

        val ending = result.pack.factsById.getValue("F_ENDING").reveal
        assertEquals(11_000L, ending.earliestMillis, "F_ENDING 起点应为 ep3 cue 4 的 00:00:11.000")
        assertEquals(15_250L, ending.latestMillis, "F_ENDING 上界应为该 cue 的结束时间 00:00:15.250")
    }

    /**
     * 示例字幕必须只解析出 cue，不产出任何解析问题。
     *
     * 这条覆盖 WebVTT 的两个易错处：`NOTE` 块必须整体跳过，
     * 以及时间行前面**没有序号**的 cue（ep3 刻意这么写）也必须能解析。
     */
    @Test
    fun `shipped example subtitles parse without issues`() {
        val dir = java.io.File("docs/examples")
        if (!dir.isDirectory) {
            println("SKIP 示例字幕解析校验：找不到 ${dir.absolutePath}")
            return
        }
        for (episode in 1..3) {
            val source = "fictional-sample.ep$episode.vtt"
            val parsed = parseSubtitles(java.io.File(dir, source).readText(), source)
            assertTrue(
                parsed.issues.isEmpty(),
                "$source 不应产生解析问题：${parsed.issues}",
            )
            assertTrue(parsed.cues.isNotEmpty(), "$source 必须至少有一条 cue")
        }
    }

    /**
     * 读示例字幕；任何一份缺失或解析失败都返回 null，让调用方跳过。
     *
     * 之所以缺失就跳过：工作目录不对不是实现缺陷，不该让测试变红。
     * 之所以**解析失败也算缺失**：那种情况下面那条"不产出解析问题"的测试会明确报出来，
     * 不会因为这里静默返回空 map 而把问题藏起来。
     */
    private fun exampleCues(): Map<Double, List<SubtitleCue>>? {
        val map = mutableMapOf<Double, List<SubtitleCue>>()
        for (episode in 1..3) {
            val path = java.io.File("docs/examples/fictional-sample.ep$episode.vtt")
            if (!path.isFile) return null
            val parsed = parseSubtitles(path.readText(), path.name)
            if (parsed.cues.isEmpty()) return null
            map[episode.toDouble()] = parsed.cues
        }
        return map
    }
}
