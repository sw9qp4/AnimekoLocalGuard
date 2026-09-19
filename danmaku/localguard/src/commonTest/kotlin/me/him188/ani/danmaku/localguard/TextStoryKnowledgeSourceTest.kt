/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.localguard.knowledge.KnowledgeLoadResult
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackCodec
import me.him188.ani.danmaku.localguard.knowledge.StoryPackTextReader
import me.him188.ani.danmaku.localguard.knowledge.TextStoryKnowledgeSource
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文本目录式剧情包来源的测试。
 *
 * 关注点不是 JSON 本身（那由 `KnowledgePackSerializationTest` 覆盖），而是**装载行为**：
 * 路径约定、缓存（含缓存失败结果）、坏文件不得抛异常、以及最要紧的一条——
 * **对齐读不到时必须降级为 Unaligned，绝不能变成偏移 0**。
 */
class TextStoryKnowledgeSourceTest {

    /** 记录读取次数，用来证明缓存真的生效。 */
    private class FakeReader(private val files: Map<String, String>) : StoryPackTextReader {
        var reads = 0
            private set

        override suspend fun readText(path: String): String? {
            reads++
            return files[path]
        }
    }

    private val workId = Fixtures.WORK_ID

    private fun reader(vararg extra: Pair<String, String>) = FakeReader(
        mapOf("story/$workId.json" to KnowledgePackCodec.encode(Fixtures.pack())) + extra.toMap(),
    )

    @Test
    fun `pack path is derived from work id`() = runTest {
        val r = reader()
        val source = TextStoryKnowledgeSource(r)
        assertIs<KnowledgeLoadResult.Loaded>(source.load(workId))
        assertEquals(1, r.reads, "应恰好读取一次 story/<workId>.json")
    }

    @Test
    fun `absent file is reported as absent not as unusable`() = runTest {
        val source = TextStoryKnowledgeSource(FakeReader(emptyMap()))
        assertEquals(KnowledgeLoadResult.Absent, source.load(workId))
    }

    @Test
    fun `broken file is unusable and does not throw`() = runTest {
        val source = TextStoryKnowledgeSource(FakeReader(mapOf("story/$workId.json" to "{ broken")))
        assertIs<KnowledgeLoadResult.Unusable>(source.load(workId))
    }

    @Test
    fun `parsed pack is cached across calls`() = runTest {
        val r = reader()
        val source = TextStoryKnowledgeSource(r)
        repeat(3) { source.load(workId) }
        assertEquals(1, r.reads, "解析结果应被缓存，判定路径不得反复读盘")
    }

    @Test
    fun `unusable result is cached too`() = runTest {
        // 坏包也不必每次重新解析：它不会自己变好，重复读盘只是浪费
        val r = FakeReader(mapOf("story/$workId.json" to "{ broken"))
        val source = TextStoryKnowledgeSource(r)
        repeat(3) { source.load(workId) }
        assertEquals(1, r.reads, "失败结果同样应缓存")
    }

    @Test
    fun `invalidate forces a re-read`() = runTest {
        val r = reader()
        val source = TextStoryKnowledgeSource(r)
        source.load(workId)
        source.invalidate()
        source.load(workId)
        assertEquals(2, r.reads, "invalidate 之后必须重新读取")
    }

    // ---------- 对齐 ----------

    @Test
    fun `missing alignment file yields null so callers degrade to unaligned`() = runTest {
        val source = TextStoryKnowledgeSource(reader())
        assertNull(
            source.loadAlignment(workId),
            "没有对齐文件时必须返回 null；调用方据此降级为 Unaligned",
        )
    }

    @Test
    fun `broken alignment file yields null and never zero offset`() = runTest {
        val source = TextStoryKnowledgeSource(
            reader("story/$workId.alignment.json" to "{ not json"),
        )
        val alignment = source.loadAlignment(workId)
        assertNull(alignment, "坏掉的对齐文件不得被当成“已对齐”")
    }

    @Test
    fun `unaligned alignment file loads as unaligned not as zero offset`() = runTest {
        val source = TextStoryKnowledgeSource(
            reader(
                "story/$workId.alignment.json" to
                        KnowledgePackCodec.encodeAlignment(TimeAlignment.Unaligned),
            ),
        )
        val alignment = source.loadAlignment(workId)
        assertEquals(TimeAlignment.Unaligned, alignment)
        assertTrue(
            alignment != TimeAlignment.ConstantOffset(0L),
            "未对齐必须与“偏移 0”可区分——把两者混同会让未对齐片源被当成已对齐",
        )
    }

    @Test
    fun `configured alignment is loaded and cached`() = runTest {
        val r = reader(
            "story/$workId.alignment.json" to
                    KnowledgePackCodec.encodeAlignment(Fixtures.alignedWithIntro),
        )
        val source = TextStoryKnowledgeSource(r)
        assertEquals(Fixtures.alignedWithIntro, source.loadAlignment(workId))
        val readsAfterFirst = r.reads
        assertEquals(Fixtures.alignedWithIntro, source.loadAlignment(workId))
        assertEquals(readsAfterFirst, r.reads, "对齐也应缓存")
    }

    @Test
    fun `custom directory changes both paths`() = runTest {
        val r = FakeReader(
            mapOf(
                "packs/$workId.json" to KnowledgePackCodec.encode(Fixtures.pack()),
                "packs/$workId.alignment.json" to
                        KnowledgePackCodec.encodeAlignment(Fixtures.aligned),
            ),
        )
        val source = TextStoryKnowledgeSource(r, directory = "packs")
        assertIs<KnowledgeLoadResult.Loaded>(source.load(workId))
        assertEquals(Fixtures.aligned, source.loadAlignment(workId))
    }

    @Test
    fun `unknown work is absent even when other works have packs`() = runTest {
        val source = TextStoryKnowledgeSource(reader())
        assertEquals(KnowledgeLoadResult.Absent, source.load("999999"))
        assertNull(source.loadAlignment("999999"))
    }
}
