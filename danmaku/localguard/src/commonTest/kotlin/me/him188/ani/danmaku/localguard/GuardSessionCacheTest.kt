/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.localguard.cache.AnalyzerVersion
import me.him188.ani.danmaku.localguard.cache.BoundedSemanticsCache
import me.him188.ani.danmaku.localguard.cache.KnowledgeVersion
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardSession
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardRequest
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import me.him188.ani.danmaku.localguard.policy.InMemoryGuardConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 语义缓存接线测试（总任务说明第 11 节）。
 *
 * 缓存本身已有独立测试（`SemanticsCacheTest`）；这里验证的是**它真的被会话使用了**，
 * 以及最要紧的一点：**缓存不得串作品、集数、时间窗口、模型版本**。
 *
 * 之前的实际缺口是：缓存类写好了却从未被任何生产路径调用。
 * 因此本文件的核心断言是"提供者被调用了几次"——只断言判定结果无法发现接线缺失。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardSessionCacheTest {

    private val analyzerV1 = AnalyzerVersion(
        modelId = "fake-encoder",
        modelVersion = "1",
        normalizationVersion = "n1",
        tokenizerVersion = "t1",
    )

    private val analyzerV2 = analyzerV1.copy(modelVersion = "2")
    private val knowledgeV1 = KnowledgeVersion(contentVersion = "content-1", schemaVersion = 1)

    private val spoilerSemantics = DanmakuSemantics(
        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
        factIds = setOf("F_ENDING"),
    )

    private fun request(text: String = "中性弹幕", position: Long = 0L, id: String = "d1") =
        GuardRequest(id = id, text = text, decisionPositionMillis = position)

    /** 记录调用次数的提供者，用于证明缓存真的省掉了计算。 */
    private class CountingProvider(private val semantics: DanmakuSemantics?) {
        var calls = 0
            private set

        fun provider(): (GuardRequest) -> DanmakuSemantics? = {
            calls++
            semantics
        }
    }

    private fun TestScope.session(
        cache: BoundedSemanticsCache?,
        analyzer: AnalyzerVersion? = analyzerV1,
        knowledge: KnowledgeVersion? = knowledgeV1,
        workId: String = Fixtures.WORK_ID,
        episode: Double = 2.0,
        tier: GuardTier = GuardTier.BALANCED,
        provider: (GuardRequest) -> DanmakuSemantics?,
    ): DanmakuGuardSession {
        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true, tier = tier))
        val s = DanmakuGuardSession(
            configSource = src,
            scope = backgroundScope,
            semanticsCache = cache,
            analyzerVersion = analyzer,
            knowledgeVersion = knowledge,
            workId = workId,
        )
        s.startEpisode(episode, Fixtures.pack(), Fixtures.aligned)
        s.semanticsProvider = provider
        return s
    }

    // ---------- 接线本身 ----------

    @Test
    fun `same text is only computed once`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, provider = counting.provider())

        assertFalse(s.shouldDisplay(request(text = "重复的一句话")))
        assertFalse(s.shouldDisplay(request(text = "重复的一句话", id = "d2")))
        assertFalse(s.shouldDisplay(request(text = "重复的一句话", id = "d3")))

        assertEquals(1, counting.calls, "相同原文 + 相同版本必须只计算一次")
        // 判定结果不受缓存影响
        assertEquals(3L, s.counters.value.blockedSpoiler)
    }

    @Test
    fun `different text is computed separately`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(request(text = "第一句"))
        s.shouldDisplay(request(text = "第二句", id = "d2"))
        assertEquals(2, counting.calls, "不同原文必须各自计算")
    }

    @Test
    fun `whitespace only differences share a cache entry`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(request(text = "同一 句"))
        s.shouldDisplay(request(text = "同一   句", id = "d2"))
        assertEquals(1, counting.calls, "仅空白差异应命中同一缓存条目")
    }

    @Test
    fun `negation is not normalized into the same entry`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(request(text = "他就是凶手"))
        s.shouldDisplay(request(text = "他不是凶手", id = "d2"))
        assertEquals(
            2, counting.calls,
            "含不同否定词的文本不得被归一化成同一键（第 11 节明确要求）",
        )
    }

    @Test
    fun `without cache the provider runs every time`() = runTest {
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = null, provider = counting.provider())

        s.shouldDisplay(request(text = "同一句话"))
        s.shouldDisplay(request(text = "同一句话", id = "d2"))
        assertEquals(2, counting.calls, "未提供缓存时必须每次都计算")
    }

    // ---------- 隔离性：不得串模型版本 / 作品 / 集数 / 时间窗口 ----------

    @Test
    fun `changing analyzer version invalidates the cache`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)

        session(cache = cache, analyzer = analyzerV1, provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))
        assertEquals(1, counting.calls)

        session(cache = cache, analyzer = analyzerV2, provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))
        assertEquals(2, counting.calls, "模型/分词器/标准化版本变化后必须重算")
    }

    @Test
    fun `changing knowledge version invalidates the fact relation layer`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)

        session(cache = cache, knowledge = knowledgeV1, provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))
        session(
            cache = cache,
            knowledge = KnowledgeVersion(contentVersion = "content-2", schemaVersion = 1),
            provider = counting.provider(),
        ).shouldDisplay(request(text = "同一句话"))

        assertEquals(2, counting.calls, "剧情包版本变化后事实关系必须重算")
    }

    @Test
    fun `fact relations are not shared across works`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)

        session(cache = cache, workId = "WORK_A", provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))
        session(cache = cache, workId = "WORK_B", provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))

        assertEquals(2, counting.calls, "事实关系缓存不得跨作品复用")
    }

    @Test
    fun `fact relations are not shared across episodes`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)

        session(cache = cache, episode = 2.0, provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))
        session(cache = cache, episode = 3.0, provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))

        assertEquals(2, counting.calls, "事实关系缓存不得跨集复用")
    }

    @Test
    fun `fact relations are not shared across semantic windows`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(
            GuardRequest(
                id = "d1",
                text = "同一句话",
                decisionPositionMillis = 60_000L,
                semanticWindowStartMillis = 60_000L,
                semanticWindowEndMillis = 60_000L,
            ),
        )
        s.shouldDisplay(
            GuardRequest(
                id = "d2",
                text = "同一句话",
                decisionPositionMillis = 120_000L,
                semanticWindowStartMillis = 120_000L,
                semanticWindowEndMillis = 120_000L,
            ),
        )

        assertEquals(2, counting.calls, "语义时间段不同必须各自判断")
    }

    // ---------- 通用层与事实层的分层语义 ----------

    @Test
    fun `without analyzer version nothing is cached`() = runTest {
        // 没有分析器版本就无法构造缓存键：复用"同版本的分析结果"要求知道是哪个版本。
        // 此时应完全不缓存，而不是用猜出来的版本号建键。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(
            DanmakuSemantics(scores = mapOf(GuardCategory.EXPLICIT_OR_HARASSMENT to 0.99)),
        )
        val s = session(
            cache = cache,
            analyzer = null,
            knowledge = null,
            provider = counting.provider(),
        )

        assertFalse(s.shouldDisplay(request(text = "不合适的话")))
        assertFalse(s.shouldDisplay(request(text = "不合适的话", id = "d2")))
        assertEquals(2, counting.calls, "无法构造键时必须每次计算")
        assertEquals(0, cache.size, "不得写入任何缓存条目")
    }

    @Test
    fun `unknown episode still consults the provider and does not cache fact relations`() = runTest {
        // 回归：曾经在"集序未知"时提前 return null，会把无法建键伪装成"提供者没有结果"，
        // 既跳过提供者，又让调用方误判为资料缺失旁路。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(
            DanmakuSemantics(scores = mapOf(GuardCategory.EXPLICIT_OR_HARASSMENT to 0.99)),
        )
        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true))
        val s = DanmakuGuardSession(
            configSource = src,
            scope = backgroundScope,
            semanticsCache = cache,
            analyzerVersion = analyzerV1,
            knowledgeVersion = knowledgeV1,
            workId = Fixtures.WORK_ID,
        )
        // 故意不调用 startEpisode：集序未知
        s.semanticsProvider = counting.provider()

        // 集序未知时整个判定走"资料/集数缺失"旁路，不应消耗提供者
        assertTrue(s.shouldDisplay(request(text = "任何话")))
        assertEquals(0, counting.calls, "集序未知时不应调用提供者")
        assertEquals(1L, s.counters.value.bypassedNoKnowledge)
    }

    @Test
    fun `content layer does not carry spoiler verdicts`() = runTest {
        // 先写入一条含剧透分数的语义（同时进入通用层与事实层）。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        session(cache = cache, provider = counting.provider())
            .shouldDisplay(request(text = "剧透的话"))

        // 换作品后事实层必然未命中；通用层里也**不得**残留剧透结论。
        val s2 = session(cache = cache, workId = "OTHER_WORK", provider = counting.provider())
        s2.shouldDisplay(request(text = "剧透的话"))
        assertEquals(2, counting.calls, "通用层不得携带依赖剧情的剧透结论")
    }

    @Test
    fun `generic layer is shared across works for content only verdicts`() = runTest {
        // 与上一条相对照：纯内容维度的结论**可以**跨作品复用（第 11 节允许）。
        // 注意必须在同一会话/缓存实例内比较：切集或换作品会按设计清空缓存，
        // 那是刻意的保守行为，不能与"跨作品复用"混为一谈。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(
            DanmakuSemantics(scores = mapOf(GuardCategory.EXPLICIT_OR_HARASSMENT to 0.99)),
        )

        // 先直接经由会话写入通用层
        session(cache = cache, workId = "WORK_A", provider = counting.provider())
            .shouldDisplay(request(text = "不合适的话"))
        assertEquals(1, counting.calls)
        assertTrue(cache.size > 0, "前置条件：通用层已写入")

        // 同一缓存实例、另一部作品：直接查通用层（不经过会清缓存的 startEpisode）
        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true, tier = GuardTier.BALANCED))
        val otherWork = DanmakuGuardSession(
            configSource = src,
            scope = backgroundScope,
            semanticsCache = cache,
            analyzerVersion = analyzerV1,
            knowledgeVersion = knowledgeV1,
            workId = "WORK_B",
        )
        otherWork.semanticsProvider = counting.provider()
        // 必须 startEpisode：集序未知时整个判定会走"资料/集数缺失"旁路直接放行，
        // 根本不会查缓存，测的就不是缓存行为了。
        otherWork.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)

        assertFalse(
            otherWork.shouldDisplay(request(text = "不合适的话")),
            "纯内容维度的结论应能跨作品复用并得到相同判定",
        )
        assertEquals(1, counting.calls, "通用分类结论应可跨作品复用，不应重新计算")
    }

    @Test
    fun `provider returning null is not cached`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(null)
        val s = session(cache = cache, provider = counting.provider())

        assertTrue(s.shouldDisplay(request(text = "无法判定")))
        assertTrue(s.shouldDisplay(request(text = "无法判定", id = "d2")))
        assertEquals(
            2, counting.calls,
            "“拿不到语义”是临时状态，不得缓存成永久结论",
        )
        assertEquals(0, cache.size, "无结果不应写入缓存")
    }

    // ---------- 切集与档位 ----------

    @Test
    fun `startEpisode evicts fact relations but keeps generic layer`() = runTest {
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(request(text = "同事实的一句话"))
        assertTrue(cache.size > 0, "前置条件：缓存已有内容")

        s.startEpisode(3.0, Fixtures.pack(), Fixtures.aligned)
        assertEquals(
            0, cache.size,
            "该条结论依赖事实，切集后必须清除，避免把上一集的判断带进新一集",
        )
    }

    @Test
    fun `startEpisode keeps content only entries reusable`() = runTest {
        // 与上一条对照：与剧情无关的通用分类结论跨集仍然可用（第 11 节允许）。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(
            DanmakuSemantics(scores = mapOf(GuardCategory.EXPLICIT_OR_HARASSMENT to 0.99)),
        )
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(request(text = "不合适的话"))
        assertEquals(1, counting.calls)

        s.startEpisode(3.0, Fixtures.pack(), Fixtures.aligned)
        s.shouldDisplay(request(text = "不合适的话", id = "d2"))
        assertEquals(1, counting.calls, "通用分类结论不应因切集而重算")
    }

    @Test
    fun `tier change reuses the same semantics`() = runTest {
        // 档位只影响阈值，不影响“这句话说了什么”，因此语义应可复用
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(spoilerSemantics)
        val s = session(cache = cache, tier = GuardTier.LENIENT, provider = counting.provider())

        s.shouldDisplay(request(text = "同一句话"))
        assertEquals(1, counting.calls)
    }

    // ---------- 分析失败（与"语义不确定"必须分开） ----------

    @Test
    fun `analysis failure blocks unreviewed content in every tier`() = runTest {
        for (tier in GuardTier.entries) {
            val counting = CountingProvider(
                DanmakuSemantics(
                    scores = emptyMap(),
                    analysisFailed = true,
                    failureReason = "model unavailable",
                ),
            )
            val s = session(cache = null, tier = tier, provider = counting.provider())

            assertFalse(
                s.shouldDisplay(request(text = "任意弹幕")),
                "分析失败时未审核内容不得显示（$tier）",
            )
            assertEquals(1L, s.counters.value.failed, "必须计入 failed，不能从分母消失（$tier）")
            assertEquals(0L, s.counters.value.visible, "故障不得被当作放行（$tier）")
            assertEquals(
                s.counters.value.total, s.counters.value.failed,
                "该条目应恰好落在 failed 一个分类里（$tier）",
            )
        }
    }

    @Test
    fun `analysis failure is distinct from unknown semantics`() = runTest {
        // 语义不确定（提供者返回 null）= 能力降级 → 按档位保守处理（原型阶段为旁路）
        val noSemantics = session(cache = null, provider = CountingProvider(null).provider())
        assertTrue(noSemantics.shouldDisplay(request(text = "任意弹幕")))
        assertEquals(0L, noSemantics.counters.value.failed, "“拿不到语义”不是分析失败")

        // 分析失败 = 系统故障 → 不显示
        val failed = session(
            cache = null,
            provider = CountingProvider(
                DanmakuSemantics(scores = emptyMap(), analysisFailed = true),
            ).provider(),
        )
        assertFalse(failed.shouldDisplay(request(text = "任意弹幕")))
        assertEquals(1L, failed.counters.value.failed)
    }

    @Test
    fun `failure verdicts are cached like other fact-free verdicts`() = runTest {
        // 同一原文 + 同一分析器版本的失败是可复现的，重复重试只是浪费。
        // 模型恢复后分析器版本必然改变，键随之失效。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(
            DanmakuSemantics(scores = emptyMap(), analysisFailed = true),
        )
        val s = session(cache = cache, provider = counting.provider())

        s.shouldDisplay(request(text = "同一句话"))
        s.shouldDisplay(request(text = "同一句话", id = "d2"))
        assertEquals(1, counting.calls, "可复现的失败应被缓存，避免无谓重试")
        assertEquals(2L, s.counters.value.failed, "缓存命中仍要逐条计数")
    }

    @Test
    fun `failed verdict is not treated as safe by the generic layer`() = runTest {
        // 回归防线：故障结论绝不能因为"分数为空"而被当成正常内容放行。
        val cache = BoundedSemanticsCache()
        val counting = CountingProvider(
            DanmakuSemantics(scores = emptyMap(), analysisFailed = true),
        )
        // 换作品后通用层仍然命中（失败不依赖剧情），结论必须依旧是"不显示"
        session(cache = cache, workId = "WORK_A", provider = counting.provider())
            .shouldDisplay(request(text = "同一句话"))

        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true))
        val other = DanmakuGuardSession(
            configSource = src,
            scope = backgroundScope,
            semanticsCache = cache,
            analyzerVersion = analyzerV1,
            knowledgeVersion = knowledgeV1,
            workId = "WORK_B",
        )
        other.semanticsProvider = counting.provider()
        other.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)

        assertFalse(
            other.shouldDisplay(request(text = "同一句话")),
            "故障结论跨作品复用后仍必须是不显示",
        )
    }
}
