/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.BoundaryExtraction
import me.him188.ani.danmaku.localguard.knowledge.BoundaryExtractionFailure
import me.him188.ani.danmaku.localguard.knowledge.RevealAnchor
import me.him188.ani.danmaku.localguard.knowledge.RevealBoundaryExtractor
import me.him188.ani.danmaku.localguard.knowledge.SubtitleCue
import me.him188.ani.danmaku.localguard.knowledge.matchesSubtitleQuote
import me.him188.ani.danmaku.localguard.knowledge.normalizeSubtitleText
import me.him188.ani.danmaku.localguard.knowledge.parseSubtitles
import me.him188.ani.danmaku.localguard.knowledge.parseTimeCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 字幕解析与揭晓边界提取的测试。
 *
 * 这些是 G2 生成流程的基础：把"某条事实在第几集第几句台词被说清"变成可复核的时间。
 * 由于时间判断错误的方向性是**不对称的**——
 * 时间偏早会让本该受保护的弹幕被放行（剧透），偏晚只是多屏蔽一些——
 * 因此本文件的重点全在"宁可保守"这一侧的边界条件上。
 *
 * 全部使用虚构字幕。
 */
class SubtitleAndBoundaryTest {

    // ---------- 时间码 ----------

    @Test
    fun `parses srt time code with comma`() {
        assertEquals(1_234_567L, parseTimeCode("00:20:34,567"))
    }

    @Test
    fun `parses webvtt time code with dot`() {
        assertEquals(1_234_567L, parseTimeCode("00:20:34.567"))
    }

    @Test
    fun `parses time code without hours`() {
        assertEquals(154_567L, parseTimeCode("02:34.567"))
    }

    @Test
    fun `millisecond digits are scaled not padded to the right`() {
        // ".5" 是 500ms；若当成 5ms，揭晓时间会整体偏早近半秒
        assertEquals(1_500L, parseTimeCode("00:00:01.5"))
        assertEquals(1_050L, parseTimeCode("00:00:01.05"))
        assertEquals(1_005L, parseTimeCode("00:00:01.005"))
    }

    @Test
    fun `rejects malformed time codes rather than guessing`() {
        assertNull(parseTimeCode(""))
        assertNull(parseTimeCode("abc"))
        assertNull(parseTimeCode("00:99:00.000"), "秒数越界必须拒绝")
        assertNull(parseTimeCode("1:2:3:4"))
        assertNull(parseTimeCode("00:00:xx.000"))
    }

    @Test
    fun `seconds 60 is rejected`() {
        assertNull(parseTimeCode("00:00:60.000"), "60 秒应进位而不是被接受")
    }

    // ---------- 解析 SRT ----------

    private val srt = """
        1
        00:00:05,000 --> 00:00:07,500
        第一句台词

        2
        00:01:10,000 --> 00:01:12,000
        第二句台词
        换行部分

        3
        00:20:34,000 --> 00:20:40,000
        虚构角色甲的真实身份是虚构角色乙的同伴
    """.trimIndent()

    @Test
    fun `parses srt cues`() {
        val result = parseSubtitles(srt, "fake.srt")
        assertEquals(3, result.cues.size)
        assertTrue(result.issues.isEmpty(), "合法 SRT 不应产生问题：${result.issues}")
    }

    @Test
    fun `parses srt times and multi line text`() {
        val cues = parseSubtitles(srt).cues
        assertEquals(5_000L, cues[0].startMillis)
        assertEquals(7_500L, cues[0].endMillis)
        assertEquals("第二句台词\n换行部分", cues[1].text)
    }

    @Test
    fun `cue index starts at one and increments`() {
        val cues = parseSubtitles(srt).cues
        assertEquals(listOf(1, 2, 3), cues.map { it.index })
    }

    // ---------- 解析 WebVTT ----------

    private val vtt = """
        WEBVTT

        NOTE 这是注释，不应被当成台词

        00:00:05.000 --> 00:00:07.500
        <i>带标签的台词</i>

        00:01:10.000 --> 00:01:12.000 align:start position:10%
        带设置的第二句
    """.trimIndent()

    @Test
    fun `parses webvtt and skips note blocks`() {
        val result = parseSubtitles(vtt, "fake.vtt")
        assertEquals(2, result.cues.size, "NOTE 块不得被当成台词")
        assertTrue(result.issues.isEmpty(), "${result.issues}")
    }

    @Test
    fun `strips inline tags when normalizing`() {
        val cues = parseSubtitles(vtt).cues
        assertEquals("带标签的台词", cues[0].normalizedText)
    }

    @Test
    fun `ignores cue settings after the end time`() {
        val cues = parseSubtitles(vtt).cues
        assertEquals(70_000L, cues[1].startMillis)
        assertEquals(72_000L, cues[1].endMillis)
    }

    @Test
    fun `webvtt without sequence numbers works`() {
        val noIndex = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n只有一句\n"
        val cues = parseSubtitles(noIndex).cues
        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMillis)
    }

    // ---------- 解析失败要可报告，不能静默 ----------

    @Test
    fun `malformed timeline is reported with a line number`() {
        val broken = "1\n这不是时间行\n某句台词\n"
        val result = parseSubtitles(broken, "broken.srt")
        assertEquals(0, result.cues.size)
        assertEquals(1, result.issues.size)
        assertEquals(2, result.issues[0].lineNumber, "必须报告行号，便于定位坏文件")
        assertEquals("broken.srt", result.issues[0].source)
    }

    @Test
    fun `timeline without text is reported`() {
        val broken = "1\n00:00:01,000 --> 00:00:02,000\n\n2\n00:00:03,000 --> 00:00:04,000\n有文本\n"
        val result = parseSubtitles(broken, "broken.srt")
        assertEquals(1, result.cues.size)
        assertEquals(1, result.issues.size, "有文本的那条仍应被解析出来")
    }

    @Test
    fun `empty input yields empty result without issues`() {
        val result = parseSubtitles("")
        assertTrue(result.isEmpty)
        assertTrue(result.issues.isEmpty())
    }

    // ---------- 归一化 ----------

    @Test
    fun `normalization removes tags and collapses inner whitespace to one space`() {
        assertEquals("你好 世界", normalizeSubtitleText("<i>你好</i>\n  世界  "))
        assertEquals("你好", normalizeSubtitleText("{\\pos(1,2)}你好"))
    }

    @Test
    fun `normalization is case insensitive by default`() {
        assertEquals(normalizeSubtitleText("Hello"), normalizeSubtitleText("HELLO"))
    }

    @Test
    fun `normalization does not rewrite punctuation so quotes stay precise`() {
        // 不做标点归一化：那会让匹配比实际更宽松，从而把错误的时间当成证据
        assertTrue(normalizeSubtitleText("真的吗？") != normalizeSubtitleText("真的吗"))
    }

    // ---------- 引文匹配的两级比较 ----------

    @Test
    fun `quote matching tolerates spacing differences between subtitle sources`() {
        val cue = normalizeSubtitleText("虚构角色甲的真实身份")
        val quote = normalizeSubtitleText("虚构角色甲 的真实身份")
        assertTrue(
            matchesSubtitleQuote(cue, quote),
            "不同来源字幕对空格处理不一致，纯排版差异不应导致“找不到证据”",
        )
    }

    @Test
    fun `quote matching still rejects genuinely different text`() {
        val cue = normalizeSubtitleText("虚构角色甲的真实身份")
        val other = normalizeSubtitleText("虚构角色乙的真实身份")
        assertFalse(matchesSubtitleQuote(cue, other), "放宽的只是空格，不是文本内容")
    }

    @Test
    fun `empty quote never matches`() {
        assertFalse(matchesSubtitleQuote(normalizeSubtitleText("任意台词"), ""))
    }

    // ---------- 边界提取：成功路径 ----------

    private fun cuesOf(vararg pairs: Pair<Long, Long>, text: String) =
        listOf(SubtitleCue(1, pairs[0].first, pairs[0].second, text))

    @Test
    fun `extracts boundary from a single anchor`() {
        val cues = mapOf(
            1.0 to listOf(
                SubtitleCue(1, 5_000L, 7_000L, "无关的一句"),
                SubtitleCue(2, 300_000L, 320_000L, "虚构角色甲的真实身份是虚构角色乙的同伴"),
            ),
        )
        val result = RevealBoundaryExtractor.extract(
            listOf(RevealAnchor(1.0, "虚构角色甲的真实身份是虚构角色乙的同伴")),
            cues,
        )
        val resolved = assertIs<BoundaryExtraction.Resolved>(result)
        assertEquals(1.0, resolved.episodeNumber)
        assertEquals(300_000L, resolved.earliestMillis)
        assertEquals(320_000L, resolved.latestMillis, "上界必须是 cue 结束时间，不是开始时间")
        assertEquals("cue", resolved.precision)
    }

    @Test
    fun `multiple anchors take the earliest start and the latest end`() {
        val cues = mapOf(
            2.0 to listOf(
                SubtitleCue(1, 600_000L, 620_000L, "第二句证据台词"),
                SubtitleCue(2, 100_000L, 110_000L, "第一句证据台词"),
            ),
        )
        val result = RevealBoundaryExtractor.extract(
            listOf(
                RevealAnchor(2.0, "第一句证据台词"),
                RevealAnchor(2.0, "第二句证据台词"),
            ),
            cues,
        )
        val resolved = assertIs<BoundaryExtraction.Resolved>(result)
        assertEquals(100_000L, resolved.earliestMillis)
        assertEquals(620_000L, resolved.latestMillis, "上界取最晚的 cue 结束时间")
        assertTrue(resolved.precision.startsWith("cues"))
    }

    @Test
    fun `anchor matching tolerates tags and whitespace differences in the subtitle`() {
        val cues = mapOf(
            1.0 to listOf(SubtitleCue(1, 1_000L, 2_000L, "<i>虚 构 角 色 甲</i>\n说了话")),
        )
        val result = RevealBoundaryExtractor.extract(
            listOf(RevealAnchor(1.0, "虚 构 角 色 甲 说了话")),
            cues,
        )
        assertIs<BoundaryExtraction.Resolved>(result)
    }

    // ---------- 边界提取：保守方向（本文件最要紧的部分） ----------

    @Test
    fun `too short quote is rejected`() {
        val cues = mapOf(1.0 to listOf(SubtitleCue(1, 1_000L, 2_000L, "是我")))
        val result = RevealBoundaryExtractor.extract(listOf(RevealAnchor(1.0, "是我")), cues)
        val failed = assertIs<BoundaryExtraction.Failed>(result)
        assertEquals(BoundaryExtractionFailure.QUOTE_TOO_SHORT, failed.failures[0].second.reason)
    }

    @Test
    fun `ambiguous quote is rejected instead of picking one`() {
        // 引文出现两次时**不得**挑第一个：挑错方向会把揭晓时间提前
        val cues = mapOf(
            1.0 to listOf(
                SubtitleCue(1, 1_000L, 2_000L, "他其实就是那个人"),
                SubtitleCue(2, 900_000L, 920_000L, "他其实就是那个人"),
            ),
        )
        val result = RevealBoundaryExtractor.extract(listOf(RevealAnchor(1.0, "他其实就是那个人")), cues)
        val failed = assertIs<BoundaryExtraction.Failed>(result)
        assertEquals(BoundaryExtractionFailure.QUOTE_AMBIGUOUS, failed.failures[0].second.reason)
    }

    @Test
    fun `missing quote fails rather than producing a partial time`() {
        val cues = mapOf(
            1.0 to listOf(
                SubtitleCue(1, 1_000L, 2_000L, "存在的证据台词"),
                SubtitleCue(2, 5_000L, 6_000L, "另一句台词"),
            ),
        )
        val result = RevealBoundaryExtractor.extract(
            listOf(
                RevealAnchor(1.0, "存在的证据台词"),
                RevealAnchor(1.0, "不存在的证据台词"),
            ),
            cues,
        )
        val failed = assertIs<BoundaryExtraction.Failed>(result)
        assertEquals(1, failed.failures.size)
        assertEquals(BoundaryExtractionFailure.QUOTE_NOT_FOUND, failed.failures[0].second.reason)
    }

    @Test
    fun `missing subtitle file yields timing unknown not a made up time`() {
        val result = RevealBoundaryExtractor.extract(
            listOf(RevealAnchor(3.0, "某句证据台词")),
            cuesByEpisode = emptyMap(),
        )
        val unknown = assertIs<BoundaryExtraction.TimingUnknown>(result)
        assertEquals(3.0, unknown.episodeNumber)
        assertTrue(unknown.reason.isNotBlank())
    }

    @Test
    fun `no anchors yields timing unknown`() {
        val result = RevealBoundaryExtractor.extract(emptyList(), emptyMap())
        assertIs<BoundaryExtraction.TimingUnknown>(result)
    }

    @Test
    fun `anchors from different episodes are rejected`() {
        val result = RevealBoundaryExtractor.extract(
            listOf(
                RevealAnchor(1.0, "第一句证据台词"),
                RevealAnchor(2.0, "第二句证据台词"),
            ),
            mapOf(1.0 to emptyList(), 2.0 to emptyList()),
        )
        val failed = assertIs<BoundaryExtraction.Failed>(
            result,
            "一条事实的揭晓边界只能落在一集里；跨集必须拆成两条事实",
        )
        assertTrue(failed.failures.size == 2)
    }

    @Test
    fun `boundary upper bound is the end of the cue not its start`() {
        // 方向性：用起点会略微提前解锁。上界必须是结束时间。
        val cues = mapOf(1.0 to listOf(SubtitleCue(1, 100_000L, 130_000L, "证据台词在这里")))
        val resolved = assertIs<BoundaryExtraction.Resolved>(
            RevealBoundaryExtractor.extract(listOf(RevealAnchor(1.0, "证据台词在这里")), cues),
        )
        assertTrue(
            resolved.latestMillis >= 130_000L,
            "上界不得早于 cue 结束时间：${resolved.latestMillis}",
        )
    }
}
