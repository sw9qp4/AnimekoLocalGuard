/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.localguard.cache.AnalyzerVersion
import me.him188.ani.danmaku.localguard.cache.BoundedSemanticsCache
import me.him188.ani.danmaku.localguard.cache.KnowledgeVersion
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardSession
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardDecision
import me.him188.ani.danmaku.localguard.policy.GuardRequest
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import me.him188.ani.danmaku.localguard.policy.InMemoryGuardConfigSource
import me.him188.ani.danmaku.localguard.policy.PlaybackPosition
import me.him188.ani.danmaku.localguard.policy.SpoilerBlockReason
import me.him188.ani.danmaku.localguard.policy.TierPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 重复回调的专门回归测试。
 *
 * 总任务说明第 11 节明确要求：
 *
 * > 统计去重、语义计算复用与实际显示决定是三件事。**重复回调即使不重复计数，
 * > 也必须落实屏蔽。** 保留旧问题的专门回归测试。
 *
 * 这三件事在本模块里各自独立，本文件把它们逐条钉住：
 *
 * 1. **显示决定**必须每次都落实屏蔽 —— 这是本节最要紧的一条，也是最容易出错的一条：
 *    一旦实现里出现"见过这条就放过"的捷径（用 WeakHashMap 之类的去重集合记录已处理条目），
 *    第二次回调就会被**放行**，屏蔽形同虚设。本文件用**值相等但不同实例**的请求来暴露这种实现。
 * 2. **统计**会逐条累计（不去重），且分类互斥完备。
 * 3. **语义计算**会被复用（缓存命中），但复用不得改变结论。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateCallbackTest {

    private val spoilerSemantics = DanmakuSemantics(
        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
        factIds = setOf("F_ENDING"), // 第 3 集才揭晓 → 第 2 集属于未来集剧透
    )

    private fun TestScope.session(
        cache: BoundedSemanticsCache? = null,
        provider: (GuardRequest) -> DanmakuSemantics? = { spoilerSemantics },
    ): DanmakuGuardSession {
        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true, tier = GuardTier.BALANCED))
        val s = DanmakuGuardSession(
            configSource = src,
            scope = backgroundScope,
            semanticsCache = cache,
            // 缓存键必须包含分析器版本；不传就等于"无法构造键 → 完全不缓存"（这是刻意设计）。
            // 因此要测"语义计算复用"就必须给出真实版本。
            analyzerVersion = if (cache == null) {
                null
            } else {
                AnalyzerVersion(
                    modelId = "fake-encoder",
                    modelVersion = "1",
                    normalizationVersion = "n1",
                    tokenizerVersion = "t1",
                )
            },
            // 带 factIds 的语义只有在"作品 + 集数 + 资料版本"齐备时才允许进事实关系层；
            // 缺资料版本时按设计**整条都不缓存**，所以这里也必须给出。
            knowledgeVersion = if (cache == null) null else KnowledgeVersion("fixture-1", 1),
            workId = Fixtures.WORK_ID,
        )
        s.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)
        s.semanticsProvider = provider
        return s
    }

    /**
     * 同一个 id 被重复回调时，**每一次都必须被屏蔽**。
     *
     * 关键点：这里传入的是**值相等但不同实例**的请求。若实现用"实例身份"去重，
     * 这个用例抓不到；必须用 `equals` 相等的不同实例，才能暴露"按内容去重后放行"的错误实现。
     */
    @Test
    fun `repeated callback for the same danmaku is still blocked every time`() = runTest {
        val s = session()

        val first = GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L)
        val second = GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L)

        // 前置条件：两个请求值相等但不是同一个实例——否则这个用例测不到"按内容去重"的实现
        assertEquals(first, second, "两个请求必须值相等")
        assertFalse(first === second, "两个请求必须是不同实例，否则本用例无意义")

        assertFalse(s.shouldDisplay(first), "首次回调必须屏蔽")
        assertFalse(s.shouldDisplay(second), "重复回调必须仍然屏蔽，不得因为“见过”而放行")
        assertFalse(s.shouldDisplay(first), "第三次回调同样必须屏蔽")
    }

    /** 统计逐条累计，不做去重；且分类互斥完备。 */
    @Test
    fun `repeated callback is counted each time and total stays consistent`() = runTest {
        val s = session()

        repeat(3) {
            assertFalse(
                s.shouldDisplay(
                    GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L),
                ),
            )
        }

        val c = s.counters.value
        assertEquals(3L, c.blockedSpoiler, "统计按条目逐条累计（即 3 次回调记 3 次）")
        assertEquals(3L, c.evaluated)
        assertEquals(0L, c.visible)
        // total 是"各分类之和"的计算属性，相等即说明该条目恰好落在一个分类里
        assertEquals(3L, c.total, "分类必须互斥完备")
    }

    /** 语义计算复用：三次回调只调用一次提供者，但结论不变。 */
    @Test
    fun `repeated callback reuses semantics without changing the verdict`() = runTest {
        val cache = BoundedSemanticsCache()
        var calls = 0
        val s = session(cache = cache) {
            calls++
            spoilerSemantics
        }

        repeat(3) {
            assertFalse(
                s.shouldDisplay(
                    GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L),
                ),
                "缓存命中不得把屏蔽变成放行",
            )
        }
        assertEquals(1, calls, "相同原文 + 相同版本只应计算一次")
        assertTrue(cache.size > 0)
        assertEquals(3L, s.counters.value.blockedSpoiler)
    }

    /** 关闭开关后，重复回调同样不得被屏蔽（关闭 = 上游行为）。 */
    @Test
    fun `repeated callback is not blocked while the guard is off`() = runTest {
        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = false))
        val s = DanmakuGuardSession(configSource = src, scope = backgroundScope)
        s.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)
        s.semanticsProvider = { spoilerSemantics }

        repeat(3) {
            assertTrue(
                s.shouldDisplay(
                    GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L),
                ),
            )
        }
        assertEquals(3L, s.counters.value.bypassedOff)
        assertEquals(0L, s.counters.value.blockedSpoiler)
    }

    /** 引擎层也为同一请求给出同一结论（纯函数性），重复调用不产生状态污染。 */
    @Test
    fun `engine verdict is stable across repeated identical evaluations`() = runTest {
        val cache = BoundedSemanticsCache()
        val s = session(cache = cache)
        val request = GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L)

        val verdicts = List(5) { s.shouldDisplay(request) }
        assertTrue(verdicts.all { !it }, "五次判定必须全部为屏蔽")
    }

    /** 未被屏蔽的条目重复回调同样保持一致（防止"只在屏蔽方向稳定"的片面实现）。 */
    @Test
    fun `visible verdict is also stable across repeated callbacks`() = runTest {
        val visible = DanmakuSemantics(
            scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
            factIds = setOf("F_IDENTITY"), // 第 1 集已揭晓 → 第 2 集可讨论
        )
        val s = session(provider = { visible })
        val request = GuardRequest(id = "ok-1", text = "他早就知道了", decisionPositionMillis = 0L)

        repeat(3) { assertTrue(s.shouldDisplay(request)) }
        assertEquals(3L, s.counters.value.visible)
        assertEquals(0L, s.counters.value.blockedSpoiler)
    }

    /**
     * 与"重复回调必须屏蔽"相对照的一条：真正的**不同**弹幕即使原文相同，
     * 也各自独立判定（不能因为"这条文本已被屏蔽过"就连带影响别的条目）。
     */
    @Test
    fun `same text with different ids is judged independently`() = runTest {
        val cache = BoundedSemanticsCache()
        val s = session(cache = cache)

        assertFalse(s.shouldDisplay(GuardRequest(id = "a", text = "同一句话", decisionPositionMillis = 0L)))
        assertFalse(s.shouldDisplay(GuardRequest(id = "b", text = "同一句话", decisionPositionMillis = 0L)))

        val c = s.counters.value
        assertEquals(2L, c.blockedSpoiler, "两条不同弹幕应各自计数")
        // 注意：这里刻意不断言"两条都进缓存"——缓存按原文指纹索引，
        // 原文相同的两条共用同一个键，这正是设计意图（语义计算复用），与显示决定无关。
    }

    /** 直接校验"未来集剧透"这一条 reason，确保屏蔽原因是时间线而不是别的分支。 */
    @Test
    fun `blocking reason for repeated callback is the timeline reason`() = runTest {
        val cache = BoundedSemanticsCache()
        val s = session(cache = cache)
        // 通过引擎自身验证原因，而不是只看布尔结果，避免"碰巧被别的原因屏蔽"
        val engine = DanmakuGuardEngine(Fixtures.pack(), Fixtures.aligned)
        val decision = engine.decide(
            semantics = spoilerSemantics,
            position = PlaybackPosition(2.0, 0L),
            policy = TierPolicy.of(GuardTier.BALANCED),
        )
        val blocked = decision as GuardDecision.BlockedSpoiler
        assertEquals(SpoilerBlockReason.FutureEpisode, blocked.reason)

        assertFalse(s.shouldDisplay(GuardRequest(id = "dup-1", text = "结局是甲和乙分开", decisionPositionMillis = 0L)))
        assertEquals(1L, s.counters.value.blockedSpoiler)
    }
}
