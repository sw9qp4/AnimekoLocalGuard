/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.localguard.knowledge.EmptyStoryKnowledgeSource
import me.him188.ani.danmaku.localguard.knowledge.InMemoryStoryKnowledgeSource
import me.him188.ani.danmaku.localguard.knowledge.KnowledgeLoadResult
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackCodec
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.knowledge.loadFromText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 剧情包装载测试。
 *
 * 装载层的职责边界很容易被写错，这里把三条**必须区分**的失败状态钉住：
 *
 * - [KnowledgeLoadResult.Absent]：没有这部作品的包。正常状态，绝大多数作品都是这样。
 * - [KnowledgeLoadResult.Unusable]：有包但用不了（坏包，或版本高于本版本支持）。
 *   这是**资料不可用**，必须按资料缺失降级，不能当成"没有包所以不用管"。
 * - 未来版本（schemaVersion 更高）：**不得**按"能读多少读多少"处理——
 *   新版本可能改变了解锁语义，猜测性读取会给出错误的时间判断。
 *
 * 全部使用虚构 fixture。
 */
class KnowledgePackSourceTest {

    private val workId = Fixtures.WORK_ID

    @Test
    fun `empty source reports every work as absent`() = runTest {
        assertEquals(KnowledgeLoadResult.Absent, EmptyStoryKnowledgeSource.load(workId))
        assertNull(EmptyStoryKnowledgeSource.loadAlignment(workId))
    }

    @Test
    fun `in memory source returns the configured pack`() = runTest {
        val source = InMemoryStoryKnowledgeSource(packs = mapOf(workId to Fixtures.pack()))
        val result = source.load(workId)
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(result)
        assertEquals(Fixtures.pack(), loaded.pack)
    }

    @Test
    fun `in memory source returns absent for an unknown work`() = runTest {
        val source = InMemoryStoryKnowledgeSource(packs = mapOf(workId to Fixtures.pack()))
        assertEquals(KnowledgeLoadResult.Absent, source.load("SOME_OTHER_WORK"))
    }

    @Test
    fun `alignment is absent unless configured and never becomes zero offset`() = runTest {
        val source = InMemoryStoryKnowledgeSource(packs = mapOf(workId to Fixtures.pack()))
        // 未配置对齐 → null，调用方必须按 Unaligned 降级；**不得**当成偏移 0
        assertNull(source.loadAlignment(workId), "未配置对齐时必须返回 null，而不是零偏移")

        val configured = InMemoryStoryKnowledgeSource(
            packs = mapOf(workId to Fixtures.pack()),
            alignments = mapOf(workId to Fixtures.alignedWithIntro),
        )
        assertEquals(Fixtures.alignedWithIntro, configured.loadAlignment(workId))
    }

    // ---------- 从文本装载 ----------

    @Test
    fun `null text means absent not unusable`() {
        assertEquals(KnowledgeLoadResult.Absent, loadFromText(null))
    }

    @Test
    fun `round tripped text loads back to an equal pack`() {
        val result = loadFromText(KnowledgePackCodec.encode(Fixtures.pack()))
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(result)
        assertEquals(Fixtures.pack(), loaded.pack)
    }

    @Test
    fun `corrupted text is unusable rather than absent`() {
        val result = loadFromText("{ this is not a pack")
        val unusable = assertIs<KnowledgeLoadResult.Unusable>(result)
        assertTrue(unusable.reason.isNotBlank())
    }

    @Test
    fun `valid json with the wrong shape is unusable`() {
        val result = loadFromText("""{"hello":"world"}""")
        assertIs<KnowledgeLoadResult.Unusable>(result, "结构不对的 JSON 必须报不可用，而不是当成空包")
    }

    @Test
    fun `newer schema version is unusable not silently partially read`() {
        val newer = Fixtures.pack().copy(schemaVersion = KnowledgePackCodec.CURRENT_SCHEMA_VERSION + 1)
        val result = loadFromText(KnowledgePackCodec.encode(newer))
        val unusable = assertIs<KnowledgeLoadResult.Unusable>(
            result,
            "版本高于本版本支持范围时不得按“能读多少读多少”处理",
        )
        assertTrue(unusable.reason.contains("schemaVersion"), "原因里要能看出是版本问题：${unusable.reason}")
    }

    @Test
    fun `newer schema version is also rejected by the in memory source`() = runTest {
        val newer = Fixtures.pack().copy(schemaVersion = KnowledgePackCodec.CURRENT_SCHEMA_VERSION + 1)
        val source = InMemoryStoryKnowledgeSource(packs = mapOf(workId to newer))
        assertIs<KnowledgeLoadResult.Unusable>(source.load(workId))
    }

    @Test
    fun `current schema version is accepted`() = runTest {
        val current = Fixtures.pack().copy(schemaVersion = KnowledgePackCodec.CURRENT_SCHEMA_VERSION)
        val source = InMemoryStoryKnowledgeSource(packs = mapOf(workId to current))
        assertIs<KnowledgeLoadResult.Loaded>(source.load(workId))
    }

    @Test
    fun `older schema version is still accepted`() = runTest {
        // 旧包必须继续可用：忽略未知字段 + 不因版本偏低而拒绝
        val older = Fixtures.pack().copy(schemaVersion = 1)
        val source = InMemoryStoryKnowledgeSource(packs = mapOf(workId to older))
        assertIs<KnowledgeLoadResult.Loaded>(source.load(workId))
    }

    @Test
    fun `codec keeps unknown fields tolerant`() {
        val text = KnowledgePackCodec.encode(Fixtures.pack())
            .replaceFirst("{", """{"futureField":1,""")
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(loadFromText(text))
        assertEquals(Fixtures.pack(), loaded.pack)
    }

    @Test
    fun `codec never emits fully qualified class names for polymorphic alignment`() {
        // 磁盘格式一旦写进全限定类名，改包名就会让旧数据失效
        val text = KnowledgePackCodec.json.encodeToString(
            TimeAlignment.serializer(),
            Fixtures.segmented,
        )
        assertTrue(text.contains("segmented"), text)
        assertFalse(text.contains("me.him188.ani"), "不得出现全限定类名：$text")
    }

    // ---------- 装载时必须真的跑结构校验 ----------
    //
    // 校验器早就写好了，但此前**没有任何装载路径调用它**——"有校验器但没人跑"
    // 与没有校验器在效果上是同一件事：坏包会被当成好包用。

    /** 造一个结构上不合法的包：揭晓集落在覆盖范围之外。 */
    private fun structurallyInvalidPack(): StoryKnowledgePack {
        val valid = Fixtures.pack()
        val badFact = valid.facts.first().let { fact ->
            fact.copy(
                reveal = fact.reveal.copy(
                    episodeNumber = valid.work.coveredEpisodes.endInclusive + 5.0,
                ),
            )
        }
        return valid.copy(facts = listOf(badFact) + valid.facts.drop(1))
    }

    @Test
    fun `structurally invalid pack is rejected at load time from text`() {
        val text = KnowledgePackCodec.encode(structurallyInvalidPack())
        val result = loadFromText(text)
        val unusable = assertIs<KnowledgeLoadResult.Unusable>(
            result,
            "结构不合法的包必须在装载时被拒绝，而不是被当成可用资料",
        )
        assertTrue(
            unusable.reason.contains("结构校验"),
            "拒绝原因要能看出是结构问题：${unusable.reason}",
        )
    }

    @Test
    fun `structurally invalid pack is rejected at load time by the in memory source`() = runTest {
        val source = InMemoryStoryKnowledgeSource(
            packs = mapOf(workId to structurallyInvalidPack()),
        )
        assertIs<KnowledgeLoadResult.Unusable>(source.load(workId))
    }

    @Test
    fun `evidence problems do not block loading`() = runTest {
        // 与上一条相对照：核对状态不足属于**证据**问题，不是结构问题。
        // 代码已保证未核对事实不会自动解锁，因此不该阻断使用——
        // 否则一批本来可用的资料会被整体判为不可用。
        val unverified = Fixtures.pack(identityVerification = VerificationState.AI_CANDIDATE)
        val result = loadFromText(KnowledgePackCodec.encode(unverified))
        val loaded = assertIs<KnowledgeLoadResult.Loaded>(
            result,
            "证据问题不应阻断装载：${(result as? KnowledgeLoadResult.Unusable)?.reason}",
        )
        assertFalse(
            loaded.pack.factsById.getValue("F_IDENTITY").verification.trustedForUnlock,
            "允许装载不等于允许自动解锁",
        )
    }

    @Test
    fun `valid fixture pack still loads`() {
        assertIs<KnowledgeLoadResult.Loaded>(loadFromText(KnowledgePackCodec.encode(Fixtures.pack())))
    }
}
