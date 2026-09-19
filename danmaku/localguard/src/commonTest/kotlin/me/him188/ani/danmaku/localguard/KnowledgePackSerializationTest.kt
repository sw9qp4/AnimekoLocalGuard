/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import kotlinx.serialization.json.Json
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 剧情包磁盘格式测试。
 *
 * 这些类型是**跨版本长期存在**的文件格式，因此这里钉住三件事：
 *
 * 1. **往返一致**：写出再读回必须完全相等（含未揭晓时间、永不解锁标记、核对状态等）。
 * 2. **派生字段不落盘**：`factsById` 是索引而非内容，磁盘上不能出现第二份事实列表，
 *    否则两份可能不一致。
 * 3. **前向兼容**：新版本写入的未知字段必须被忽略而不是解析失败——
 *    否则一次格式演进就会让所有旧版用户丢包。
 *
 * 这些测试**只用虚构 fixture**，不代表任何真实作品。
 */
class KnowledgePackSerializationTest {

    /** 与生产读取端一致的解析配置：忽略未知字段。 */
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        allowSpecialFloatingPointValues = true
    }

    private fun encode(pack: StoryKnowledgePack): String =
        json.encodeToString(StoryKnowledgePack.serializer(), pack)

    private fun decode(text: String): StoryKnowledgePack =
        json.decodeFromString(StoryKnowledgePack.serializer(), text)

    /** 复用同一个解析配置读写对齐映射（同一份磁盘约定）。 */
    private fun encodeAlignment(value: TimeAlignment): String =
        json.encodeToString(TimeAlignment.serializer(), value)

    private fun decodeAlignment(text: String): TimeAlignment =
        json.decodeFromString(TimeAlignment.serializer(), text)

    @Test
    fun `knowledge pack survives a round trip`() {
        val pack = Fixtures.pack()
        val restored = decode(encode(pack))
        assertEquals(pack, restored, "写出再读回必须完全相等")
    }

    @Test
    fun `covered episode range survives a round trip`() {
        val pack = Fixtures.pack()
        val restored = decode(encode(pack))
        assertEquals(1.0, restored.work.coveredEpisodes.start)
        assertEquals(3.0, restored.work.coveredEpisodes.endInclusive)
        assertTrue(
            3.0 in restored.work.coveredEpisodes,
            "闭区间必须包含终点——写成半开区间会让最后一集被判为覆盖范围外",
        )
    }

    @Test
    fun `decimal episode numbers are not rounded by serialization`() {
        val pack = Fixtures.pack()
        val restored = decode(encode(pack))
        assertEquals(
            pack.facts.map { it.reveal.episodeNumber },
            restored.facts.map { it.reveal.episodeNumber },
            "小数集序不得在磁盘往返中被取整",
        )
    }

    @Test
    fun `factsById is derived and not written to disk`() {
        val pack = Fixtures.pack()
        val text = encode(pack)
        assertFalse(
            text.contains("factsById"),
            "派生索引不得落盘：磁盘上出现第二份事实列表就可能与 facts 不一致",
        )
        // 但读回后必须仍然可用
        val restored = decode(text)
        assertEquals(pack.facts.size, restored.factsById.size, "读回后索引必须重建")
        assertEquals(
            pack.factsById.keys,
            restored.factsById.keys,
        )
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val original = encode(Fixtures.pack())
        // 模拟"新版本写入、旧版本读取"：插入一个旧版本不认识的字段
        val withFutureField = original.replaceFirst("{", """{"futureFieldFromNewerVersion":123,""")
        assertTrue(withFutureField.contains("futureFieldFromNewerVersion"))

        val restored = decode(withFutureField)
        assertEquals(Fixtures.pack(), restored, "未知字段必须被忽略，其余内容照常读出")
    }

    @Test
    fun `verification state drives unlock trust and survives round trip`() {
        // 未核对状态不得作为自动解锁依据，这一点必须跨磁盘往返仍成立
        val pack = Fixtures.pack(identityVerification = VerificationState.AI_CANDIDATE)
        val restored = decode(encode(pack))
        val fact = restored.factsById.getValue("F_IDENTITY")
        assertEquals(VerificationState.AI_CANDIDATE, fact.verification)
        assertFalse(fact.verification.trustedForUnlock, "AI 候选不得成为自动解锁依据")
    }

    @Test
    fun `never unlocks flag survives round trip`() {
        val restored = decode(encode(Fixtures.pack()))
        assertTrue(
            restored.factsById.getValue("F_NEVER").neverUnlocksInScope,
            "“覆盖范围内永不解锁”是保护性标记，丢失会导致该事实被提前放行",
        )
    }

    @Test
    fun `unknown timing survives round trip as unknown not as zero`() {
        val restored = decode(encode(Fixtures.pack()))
        val fact = restored.factsById.getValue("F_UNKNOWN_TIME")
        assertEquals(null, fact.reveal.earliestMillis, "时间未知必须保持 null，不得变成 0")
        assertEquals(null, fact.reveal.latestMillis)
        assertFalse(fact.reveal.hasTiming)
    }

    // ---------- 对齐映射 ----------

    @Test
    fun `unaligned survives round trip and stays distinguishable from zero offset`() {
        val restored = decodeAlignment(encodeAlignment(TimeAlignment.Unaligned))
        assertEquals(TimeAlignment.Unaligned, restored)
        // 关键：未对齐不是"偏移 0"。若实现把它序列化成 ConstantOffset(0)，这条会失败。
        assertFalse(
            restored == TimeAlignment.ConstantOffset(0L),
            "未对齐必须与“偏移为 0”可区分",
        )
    }

    @Test
    fun `constant offset survives round trip`() {
        val value = TimeAlignment.ConstantOffset(offsetMillis = -10_000L, errorMillis = 500L)
        val restored = decodeAlignment(encodeAlignment(value))
        assertEquals(value, restored)
    }

    @Test
    fun `segmented alignment survives round trip`() {
        val value = Fixtures.segmented
        val restored = decodeAlignment(encodeAlignment(value))
        assertEquals(value, restored)
        assertEquals(2, (restored as TimeAlignment.Segmented).segments.size)
    }

    @Test
    fun `segmented alignment serializes with a stable type discriminator`() {
        val text = encodeAlignment(Fixtures.segmented)
        // 判别值必须是显式钉住的稳定标识（@SerialName），不能是类的全限定名：
        // 后者一旦类被改名或移动，已经落盘的对齐数据就再也读不回来。
        assertTrue(
            text.contains(""""type":"segmented""""),
            "多态判别值应为稳定的 'segmented'：$text",
        )
        assertFalse(
            text.contains("me.him188.ani"),
            "磁盘格式不得出现类的全限定名：$text",
        )
    }

    @Test
    fun `every alignment variant has a stable short discriminator`() {
        val unaligned = encodeAlignment(TimeAlignment.Unaligned)
        val constant = encodeAlignment(TimeAlignment.ConstantOffset(0L))
        assertTrue(unaligned.contains(""""type":"unaligned""""), unaligned)
        assertTrue(constant.contains(""""type":"constant_offset""""), constant)
        for (text in listOf(unaligned, constant)) {
            assertFalse(text.contains("me.him188.ani"), "不得出现全限定类名：$text")
        }
    }
}
