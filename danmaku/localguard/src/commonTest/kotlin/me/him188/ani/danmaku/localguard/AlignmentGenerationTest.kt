/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.AlignmentGeneration
import me.him188.ani.danmaku.localguard.knowledge.AlignmentResolver
import me.him188.ani.danmaku.localguard.knowledge.MIN_MATCHES
import me.him188.ani.danmaku.localguard.knowledge.SubtitleCue
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.generateAlignment
import me.him188.ani.danmaku.localguard.knowledge.pairSubtitlesByText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 片源对齐生成的测试。
 *
 * 这一层的错误方向同样是**不对称**的：一个偏早的对齐会让 [AlignmentResolver]
 * 把"实际播放位置"映射到更早的基准时间，从而**提前解锁**未揭晓内容。
 * 因此本文件的重点全在"样本不足就拒绝、残差过大就拒绝"这两条上，
 * 以及"拒绝后必须是 Unaligned，不能退化成偏移 0"。
 *
 * 全部使用虚构字幕。
 */
class AlignmentGenerationTest {

    /** 造一版字幕：台词文本用 "lineNN"，时间由调用方给定。 */
    private fun cues(vararg pairs: Pair<String, Long>): List<SubtitleCue> =
        pairs.mapIndexed { i, (text, start) ->
            SubtitleCue(index = i + 1, startMillis = start, endMillis = start + 2_000L, text = text)
        }

    /** 六条足够长的虚构台词，时间各不同。 */
    private val texts = listOf(
        "这是第一条足够长的虚构台词",
        "这是第二条足够长的虚构台词",
        "这是第三条足够长的虚构台词",
        "这是第四条足够长的虚构台词",
        "这是第五条足够长的虚构台词",
        "这是第六条足够长的虚构台词",
    )

    private fun baselineAt(step: Long = 60_000L): List<SubtitleCue> =
        cues(*texts.mapIndexed { i, t -> t to (i + 1) * step }.toTypedArray())

    private fun actualAt(offset: Long, step: Long = 60_000L): List<SubtitleCue> =
        cues(*texts.mapIndexed { i, t -> t to ((i + 1) * step - offset) }.toTypedArray())

    // ---------- 配对 ----------

    @Test
    fun `pairs identical subtitles by text`() {
        val pairing = pairSubtitlesByText(actualAt(0L), baselineAt())
        assertEquals(texts.size, pairing.samples.size)
        assertTrue(pairing.notices.isEmpty(), "${pairing.notices}")
    }

    @Test
    fun `pairing records which actual time maps to which baseline time`() {
        val pairing = pairSubtitlesByText(actualAt(5_000L), baselineAt())
        val first = pairing.samples.first()
        assertEquals(55_000L, first.actualMillis)
        assertEquals(60_000L, first.baselineMillis)
        assertEquals(5_000L, first.offsetMillis, "基准比实际晚 5s，即实际片源片头短 5s")
    }

    @Test
    fun `cues appearing more than once are skipped and reported`() {
        val repeated = "这句话在片源里出现了两次呢"
        val actual = cues(
            repeated to 1_000L,
            repeated to 900_000L,
            texts[0] to 60_000L,
            texts[1] to 120_000L,
            texts[2] to 180_000L,
            texts[3] to 240_000L,
            texts[4] to 300_000L,
            texts[5] to 360_000L,
        )
        val pairing = pairSubtitlesByText(actual, baselineAt())
        assertTrue(
            pairing.notices.any { it.code == "AMBIGUOUS_CUES" },
            "出现多次的台词必须被跳过并报告：${pairing.notices}",
        )
        assertTrue(pairing.samples.none { it.quote == repeated }, "歧义台词不得进入样本")
    }

    @Test
    fun `short cues are not used as anchors`() {
        val actual = cues(
            "嗯" to 1_000L, // 太短
            *texts.mapIndexed { i, t -> t to ((i + 1) * 60_000L) }.toTypedArray(),
        )
        val pairing = pairSubtitlesByText(actual, baselineAt())
        assertTrue(pairing.samples.none { it.quote == "嗯" })
    }

    // ---------- 固定偏移 ----------

    @Test
    fun `constant offset is detected`() {
        val result = generateAlignment(pairSubtitlesByText(actualAt(7_000L), baselineAt()))
        val success = assertIs<AlignmentGeneration.Success>(result)
        val alignment = assertIs<TimeAlignment.ConstantOffset>(success.alignment)
        assertEquals(7_000L, alignment.offsetMillis)
        assertEquals(0L, success.maxResidualMillis, "无噪声时应零残差")
    }

    @Test
    fun `negative offset is detected too`() {
        // 实际片源片头更长 → 基准时间应更早
        val result = generateAlignment(pairSubtitlesByText(actualAt(-4_000L), baselineAt()))
        val success = assertIs<AlignmentGeneration.Success>(result)
        assertEquals(-4_000L, assertIs<TimeAlignment.ConstantOffset>(success.alignment).offsetMillis)
    }

    @Test
    fun `median resists a single bad pairing`() {
        // 10 个配对里 9 个是好的（+4000），1 个错配（+496000）。
        // 用中位数时偏移仍为 +4000，且 90 分位残差为 0 → 常量偏移分支成立；
        // 若用平均值，偏移会被那一条拉到 +53,200，整集时间将系统性偏晚。
        val good = (1..9).map { i -> texts[i % texts.size] + "之$i" }
        val bad = "这是一条被错配的台词样本呢"
        val actual = cues(
            *(good.mapIndexed { i, t -> t to (i + 1) * 60_000L }).toTypedArray(),
            bad to 500_000L,
        )
        val baseline = cues(
            *(good.mapIndexed { i, t -> t to (i + 1) * 60_000L + 4_000L }).toTypedArray(),
            bad to 10_000L,
        )

        val success = assertIs<AlignmentGeneration.Success>(
            generateAlignment(pairSubtitlesByText(actual, baseline)),
            "一条错配不应否掉整体：判据用的是分位残差而不是最大值",
        )
        assertEquals(
            4_000L, assertIs<TimeAlignment.ConstantOffset>(success.alignment).offsetMillis,
            "中位数应落在多数好样本上",
        )
        assertTrue(
            success.notices.any { it.code == "OUTLIER_PAIRINGS" },
            "离群配对必须被报告出来供人工复核：${success.notices}",
        )
    }

    @Test
    fun `average would be dragged by the same outlier which is why median is used`() {
        // 对照实验：同一批样本下，平均值确实被拉偏——这就是选中位数的理由。
        val good = (1..9).map { i -> texts[i % texts.size] + "之$i" }
        val bad = "这是一条被错配的台词样本呢"
        val actual = cues(
            *(good.mapIndexed { i, t -> t to (i + 1) * 60_000L }).toTypedArray(),
            bad to 500_000L,
        )
        val baseline = cues(
            *(good.mapIndexed { i, t -> t to (i + 1) * 60_000L + 4_000L }).toTypedArray(),
            bad to 10_000L,
        )
        val offsets = pairSubtitlesByText(actual, baseline).samples.map { it.offsetMillis }
        val mean = offsets.sum() / offsets.size
        assertEquals(4_000L, offsets.sorted()[offsets.size / 2], "中位数 = 4000")
        assertTrue(
            kotlin.math.abs(mean - 4_000L) > 10_000L,
            "平均值被拉偏到 $mean，这正是不能用的原因",
        )
    }

    // ---------- 拒绝路径（本文件最要紧的部分） ----------

    @Test
    fun `too few matches is rejected`() {
        val few = texts.take(MIN_MATCHES - 1)
        val actual = cues(*few.mapIndexed { i, t -> t to (i + 1) * 60_000L }.toTypedArray())
        val baseline = cues(*few.mapIndexed { i, t -> t to (i + 1) * 60_000L }.toTypedArray())
        val result = generateAlignment(pairSubtitlesByText(actual, baseline))
        val rejected = assertIs<AlignmentGeneration.Rejected>(
            result,
            "样本太少时必须拒绝：用两三个点拟合出的偏移可能整体偏差几十秒",
        )
        assertTrue(rejected.reason.isNotBlank())
    }

    @Test
    fun `no matches at all is rejected`() {
        val result = generateAlignment(
            pairSubtitlesByText(
                cues("完全不同的台词甲长度足够" to 1_000L),
                cues("完全不同的台词乙长度足够" to 1_000L),
            ),
        )
        assertIs<AlignmentGeneration.Rejected>(result)
    }

    @Test
    fun `large drift that cannot be segmented is rejected`() {
        // 交替偏移，任何单调分段都拟合不了 → 必须拒绝
        val actual = cues(
            *texts.mapIndexed { i, t -> t to ((i + 1) * 60_000L + if (i % 2 == 0) 0L else 30_000L) }
                .toTypedArray(),
        )
        val result = generateAlignment(pairSubtitlesByText(actual, baselineAt()))
        assertIs<AlignmentGeneration.Rejected>(
            result,
            "无法拟合的对齐必须拒绝，而不是给一个系统性偏早的结果",
        )
    }

    @Test
    fun `rejection carries a reason and is not a success`() {
        val result = generateAlignment(
            pairSubtitlesByText(cues("只有一条长度足够的台词甲" to 1_000L), baselineAt()),
        )
        val rejected = assertIs<AlignmentGeneration.Rejected>(result)
        assertTrue(rejected.reason.isNotBlank(), "拒绝必须给出原因，便于人工排查")
        // 契约：拒绝就是拒绝。调用方据此降级为 Unaligned，而不是拿一个偏移 0 去用。
        assertIs<AlignmentGeneration.Rejected>(result)
    }

    // ---------- 分段路径 ----------

    /**
     * 造一个"中段有剪辑"的样本集：前 [preCut] 条无偏移，之后整体前移 [shiftMillis]
     * （模拟片源删掉了一段）。
     */
    private fun cutSamples(
        total: Int,
        preCut: Int,
        shiftMillis: Long,
        step: Long = 60_000L,
    ): Pair<List<SubtitleCue>, List<SubtitleCue>> {
        val names = (1..total).map { "第${it}条用于配对的长台词需要足够长" }
        val actual = cues(
            *names.mapIndexed { i, t ->
                val shifted = if (i >= preCut) shiftMillis else 0L
                t to (i + 1) * step - shifted
            }.toTypedArray(),
        )
        val baseline = cues(*names.mapIndexed { i, t -> t to (i + 1) * step }.toTypedArray())
        return actual to baseline
    }

    @Test
    fun `non-linear cut is fitted with segments`() {
        val (actual, baseline) = cutSamples(total = 8, preCut = 4, shiftMillis = 20_000L)
        val result = generateAlignment(pairSubtitlesByText(actual, baseline))
        val success = assertIs<AlignmentGeneration.Success>(
            result,
            "存在非线性剪辑时应改用分段映射：${(result as? AlignmentGeneration.Rejected)?.reason}",
        )
        val segmented = assertIs<TimeAlignment.Segmented>(success.alignment)
        assertTrue(segmented.segments.isNotEmpty())
        assertTrue(
            success.notices.any { it.code == "DRIFT_DETECTED" },
            "必须报告检测到漂移：${success.notices}",
        )
    }

    @Test
    fun `segments are sorted and non overlapping`() {
        val (actual, baseline) = cutSamples(total = 8, preCut = 4, shiftMillis = 20_000L)
        val success = assertIs<AlignmentGeneration.Success>(
            generateAlignment(pairSubtitlesByText(actual, baseline)),
        )
        val segmented = assertIs<TimeAlignment.Segmented>(success.alignment)
        // TimeAlignment.Segmented 的 init 本身要求有序不重叠；这里再显式确认一次语义
        segmented.segments.zipWithNext().forEach { (a, b) ->
            assertTrue(a.actualEndMillis <= b.actualStartMillis, "分段必须有序且不重叠")
        }
    }

    @Test
    fun `gaps between segments stay unmapped instead of being interpolated`() {
        val (actual, baseline) = cutSamples(total = 8, preCut = 4, shiftMillis = 20_000L)
        val success = assertIs<AlignmentGeneration.Success>(
            generateAlignment(pairSubtitlesByText(actual, baseline)),
        )
        val segmented = assertIs<TimeAlignment.Segmented>(success.alignment)

        // 取一个落在分段之间的时间点（若有空隙），必须返回未映射
        segmented.segments.zipWithNext().forEach { (a, b) ->
            if (a.actualEndMillis < b.actualStartMillis) {
                val inGap = (a.actualEndMillis + b.actualStartMillis) / 2
                val mapping = AlignmentResolver.toBaseline(inGap, segmented)
                assertFalse(mapping.mapped, "分段之间的空隙不得被插值")
                assertNull(mapping.baselineMillis)
            }
        }
    }

    // ---------- 与判定链路的一致性 ----------

    @Test
    fun `generated constant offset maps actual to baseline as expected`() {
        val success = assertIs<AlignmentGeneration.Success>(
            generateAlignment(pairSubtitlesByText(actualAt(5_000L), baselineAt())),
        )
        val mapping = AlignmentResolver.toBaseline(60_000L, success.alignment)
        assertTrue(mapping.mapped)
        assertEquals(65_000L, mapping.baselineMillis, "基准 = 实际 + 偏移")
    }

    @Test
    fun `rejected alignment leads the caller to unaligned and never to zero offset`() {
        // 模拟调用方按约定处理拒绝
        val rejected = assertIs<AlignmentGeneration.Rejected>(
            generateAlignment(pairSubtitlesByText(cues("不够多" to 1_000L), baselineAt())),
        )
        val alignment = TimeAlignment.Unaligned
        val mapping = AlignmentResolver.toBaseline(60_000L, alignment)
        assertFalse(mapping.mapped, "以 Unaligned 降级后必须返回未映射")
        assertEquals("alignment-unverified", mapping.note)
        assertTrue(rejected.reason.isNotBlank())
    }
}
