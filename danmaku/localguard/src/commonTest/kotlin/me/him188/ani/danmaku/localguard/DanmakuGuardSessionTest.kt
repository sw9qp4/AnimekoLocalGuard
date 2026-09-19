/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.localguard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardSession
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardFeatureState
import me.him188.ani.danmaku.localguard.policy.GuardRequest
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import me.him188.ani.danmaku.localguard.policy.InMemoryGuardConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话层测试。
 *
 * 这一层是"规则核心"与"上游弹幕链路"之间的胶水，重点验证：
 * - 总开关关闭时完全旁路（不改上游行为），且**关掉后立即恢复放行**
 * - 资料/集数缺失、语义未接入时如实降级，不猜测
 * - 计数分类互斥且完备，**不隐藏**排队/失败/超时
 * - 切集后生成号隔离，拖回片头会重新受保护
 * - 超时按"不显示 + 计入超时"处理
 * - 状态快照如实反映能力，绝不把"开关打开"说成"已启用"
 *
 * 全部使用虚构剧情 fixture。
 *
 * 关于调度器：会话需要一个作用域持续跟随配置变化（判定路径不能挂起，因此配置被镜像到内存）。
 * 用 [runTest] 的 [TestScope] 建立该作用域，并在改动配置后显式 [runCurrent] 让收集器结算，
 * 使"改开关立即生效"成为被测行为本身，而不是靠等待时间碰运气。
 * 超时类用例例外：它们断言**真实时间**，见各自注释。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuGuardSessionTest {

    private val spoilerSemantics = DanmakuSemantics(
        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
    )

    /**
     * 指向"在本集已经揭晓"的事实，用于构造放行（visible）路径。
     * 第 2 集时 F_IDENTITY（第 1 集揭晓）已可讨论。
     */
    private val visibleSemantics = DanmakuSemantics(
        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
        factIds = setOf("F_IDENTITY"),
    )

    private fun request(text: String = "中性弹幕", position: Long = 0L, id: String = "d1") =
        GuardRequest(id = id, text = text, decisionPositionMillis = position)

    /**
     * 建立会话与配置来源。
     *
     * 收集器先于 [DanmakuGuardSession] 的构造启动，因此调用后配置已经生效，
     * 不需要调用方额外等待。
     */
    private fun TestScope.session(
        enabled: Boolean = true,
        tier: GuardTier = GuardTier.BALANCED,
        withKnowledge: Boolean = true,
        episode: Double? = 2.0,
    ): Pair<DanmakuGuardSession, InMemoryGuardConfigSource> {
        val cfg = GuardUserConfig(enabled = enabled, tier = tier)
        val src = InMemoryGuardConfigSource(cfg)
        val s = DanmakuGuardSession(src, scope = backgroundScope)
        runCurrent()
        if (episode != null) {
            s.startEpisode(
                episodeNumber = episode,
                knowledge = if (withKnowledge) Fixtures.pack() else null,
                alignment = Fixtures.aligned,
            )
        }
        assertEquals(cfg.enabled, s.config.enabled, "会话必须已跟随到当前配置")
        return s to src
    }

    // ---------- 总开关 ----------

    @Test
    fun disabledByDefaultAndFullyBypassed() = runTest {
        val src = InMemoryGuardConfigSource() // 默认 enabled = false
        assertFalse(src.current.enabled, "默认必须关闭")

        val s = DanmakuGuardSession(src, scope = backgroundScope)
        runCurrent()
        s.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)
        s.semanticsProvider = { spoilerSemantics }

        assertTrue(s.shouldDisplay(request()), "关闭时必须放行")
        val c = s.counters.value
        assertEquals(1L, c.bypassedOff, "关闭时计入 bypassedOff")
        assertEquals(0L, c.blockedSpoiler, "关闭时不得屏蔽")
    }

    @Test
    fun enablingRestoresDecisions() = runTest {
        val (s, src) = session()
        // 第 2 集，F_ENDING 在第 3 集才揭晓 → 属于未来集剧透
        s.semanticsProvider = {
            DanmakuSemantics(
                scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                factIds = setOf("F_ENDING"),
            )
        }
        assertFalse(s.shouldDisplay(request()), "开启后应能屏蔽未来集剧透")

        src.setEnabled(false)
        runCurrent()
        assertTrue(s.shouldDisplay(request(id = "d2")), "关闭后立即恢复放行")
        assertEquals(1L, s.counters.value.blockedSpoiler, "关闭后不得再累计屏蔽")
    }

    // ---------- 能力降级 ----------

    @Test
    fun missingKnowledgeDegradesWithoutGuessing() = runTest {
        val (s, _) = session(withKnowledge = false)
        s.semanticsProvider = { spoilerSemantics }
        assertTrue(s.shouldDisplay(request()), "资料缺失时不得凭猜测屏蔽")
        assertEquals(1L, s.counters.value.bypassedNoKnowledge, "应计入资料缺失旁路")
    }

    @Test
    fun unknownEpisodeDegradesWithoutGuessing() = runTest {
        val (s, _) = session(episode = null)
        s.semanticsProvider = { spoilerSemantics }
        assertTrue(s.shouldDisplay(request()), "集数未知时不得凭猜测屏蔽")
        assertEquals(1L, s.counters.value.bypassedNoKnowledge)
    }

    /**
     * 集数变成未知时，**不得沿用上一集**。
     *
     * 这是一个真实的放行缺陷：调用方在集数未知时此前什么都不做，于是会话里保留着上一集的
     * 集数。界面已经知道新内容无法映射，过滤器却仍按上一集判断——而旧进度可能让未来事实
     * 被当成"已经揭晓"，从而放行。这比"没有过滤"更危险，因为它看起来像是在工作。
     */
    @Test
    fun unknownEpisodeClearsThePreviousEpisodeInsteadOfReusingIt() = runTest {
        val (s, _) = session(episode = 3.0) // 第 3 集：F_ENDING 已揭晓
        s.semanticsProvider = { visibleSemantics }
        // 前置条件：第 3 集里指向 F_ENDING 的弹幕确实被放行
        assertTrue(s.shouldDisplay(request(id = "before")), "前置条件：第 3 集应已解锁 F_ENDING")
        assertTrue(s.captureStatus().episodeKnown)

        // 切到无法映射的内容
        s.markEpisodeUnknown()

        assertFalse(s.captureStatus().episodeKnown, "必须清除集数，不得沿用第 3 集")
        // 集序未知 → 能力降级：不凭猜测屏蔽，但也绝不按上一集解锁
        assertTrue(s.shouldDisplay(request(id = "after")), "集序未知时按降级放行，而不是按旧集判断")
        assertEquals(0L, s.counters.value.blockedSpoiler, "不得再以第 3 集的结论作判断")
    }

    /** 重复声明"集序未知"不应反复递增生成号（否则会白白作废在途结果）。 */
    @Test
    fun repeatedUnknownEpisodeIsIdempotent() = runTest {
        val (s, _) = session(episode = 2.0)
        s.markEpisodeUnknown()
        val g = s.generation
        s.markEpisodeUnknown()
        assertEquals(g, s.generation, "已经是未知时不得再次递增生成号")
    }

    /**
     * 剧情包异步加载完成后，**不需要换集**也必须对当前会话生效。
     *
     * 加载剧情包通常发生在集数已知之后。若只在切集时绑定，会话就永远拿着构造时的 null 包，
     * 于是"文件加载成功"不等于"过滤真的用上了它"。
     */
    @Test
    fun knowledgeLoadedLaterIsBoundWithoutChangingEpisode() = runTest {
        // 先以"无资料"启动第 2 集
        val (s, _) = session(episode = 2.0, withKnowledge = false)
        s.semanticsProvider = { visibleSemantics }
        assertFalse(s.captureStatus().knowledgeLoaded, "前置条件：此时尚无资料")
        assertTrue(s.shouldDisplay(request(id = "no-knowledge")), "无资料属于降级，放行")
        assertEquals(1L, s.counters.value.bypassedNoKnowledge)

        // 资料到达，集数不变
        s.updateKnowledge(Fixtures.pack(), Fixtures.aligned)

        assertTrue(s.captureStatus().knowledgeLoaded, "资料必须已绑定到当前会话")
        assertTrue(s.captureStatus().episodeKnown, "集数不变")
        // 计数不因资料到达而重置：本集尚未结束，统计应连续
        assertEquals(1L, s.counters.value.bypassedNoKnowledge, "不得重置计数")
    }

    /** 资料到达后判定必须真的按资料执行，而不是仍然"无资料放行"。 */
    @Test
    fun decisionActuallyUsesKnowledgeThatArrivesLate() = runTest {
        val (s, _) = session(episode = 1.0, withKnowledge = false)
        // 指向第 3 集才揭晓的事实：有资料时应被屏蔽
        s.semanticsProvider = {
            DanmakuSemantics(
                scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.95),
                factIds = setOf("F_ENDING"),
            )
        }
        assertTrue(s.shouldDisplay(request(id = "before-load")), "无资料时放行（降级）")

        s.updateKnowledge(Fixtures.pack(), Fixtures.aligned)

        assertFalse(
            s.shouldDisplay(request(id = "after-load")),
            "资料到达后，指向未来集事实的弹幕必须被屏蔽——否则等于资料没生效",
        )
    }

    /** 资料未变化时重复刷新不得清掉事实关系缓存之外的东西，也不得重置计数。 */
    @Test
    fun repeatedKnowledgeUpdateIsIdempotent() = runTest {
        val (s, _) = session(episode = 2.0)
        s.semanticsProvider = { visibleSemantics }
        s.shouldDisplay(request())
        val before = s.counters.value.total

        s.updateKnowledge(Fixtures.pack(), Fixtures.aligned)
        s.updateKnowledge(Fixtures.pack(), Fixtures.aligned)

        assertEquals(before, s.counters.value.total, "重复刷新不得影响计数")
    }

    /**
     * 拿不到语义结论 = 这条弹幕未经审核。
     *
     * 总任务说明第 12 节：「AI 已开启但系统故障时，保留仍有效的已审核项，
     * **未审核内容不显示**，并明确提示；不得静默恢复原样弹幕还显示保护中。」
     *
     * 因此这里必须**不显示**。早期实现在这一分支放行，理由是"原型阶段无语义信息"，
     * 但那恰好就是第 12 节点名禁止的"静默恢复原样弹幕"：用户打开了开关，
     * 界面却没有如实告诉他未审核内容仍在显示。
     *
     * 与"资料缺失/集序未知"的区别在于：后者是第 13 节的**能力降级**，
     * 没有资料并不等于这条弹幕安全；而这里是**分析能力没有给出结论**。
     */
    @Test
    fun noSemanticsMeansUnreviewedContentIsNotShown() = runTest {
        val (s, _) = session()
        // 不设置 semanticsProvider：原型阶段恒为 null
        assertFalse(s.shouldDisplay(request()), "未审核内容不得显示")
        val c = s.counters.value
        assertEquals(1L, c.failed, "应计入失败，而不是资料缺失旁路")
        assertEquals(0L, c.bypassedNoKnowledge, "这不是资料缺失")
        assertEquals(0L, c.evaluated, "未参与判定的条目不得计入 evaluated")
        assertEquals(0L, c.visible)
    }

    /**
     * 有资料、但完全没有可用分析能力时，状态必须说明"未审核内容不显示"。
     *
     * 这条覆盖的是原型阶段的实际情形：开关能打开，剧情包也在，但没有模型，
     * 于是每条弹幕都拿不到语义结论。用户必须能从状态行知道
     * "看不到弹幕是功能在起作用"，而不是以为播放器坏了。
     */
    @Test
    fun missingAnalysisCapabilityIsReportedInTheStatusLine() = runTest {
        val (s, _) = session() // 有 pack、有集数，但没有 semanticsProvider
        val status = s.captureStatus()
        assertTrue(status.analysisCapabilityMissing, "从未产出过结论 ⇒ 分析能力缺失")
        assertEquals(
            GuardFeatureState.MODEL_FAILURE,
            status.state,
            "不得谎报为 RULE_PROTOTYPE：那样用户会以为过滤在工作",
        )
        val line = status.displayLine()
        assertTrue(
            line.contains("无分析能力"),
            "状态行必须说明原因，实际为：$line",
        )
        assertTrue(
            line.contains("未审核弹幕不显示"),
            "状态行必须让用户知道看不到弹幕是功能在起作用，实际为：$line",
        )
    }

    /**
     * 资料缺失时状态仍报"资料缺失"，而不是"模型故障"。
     *
     * 两者都是"没有结论"，但用户能做的事不同：前者要装剧情包，后者要看分析器。
     * 报错对象搞错会让人往错误方向排查。
     */
    @Test
    fun missingKnowledgeIsReportedAsKnowledgeNotAsModelFailure() = runTest {
        val (s, _) = session(withKnowledge = false)
        val status = s.captureStatus()
        assertEquals(GuardFeatureState.KNOWLEDGE_MISSING, status.state)
        assertTrue(status.displayLine().contains("资料缺失"), status.displayLine())
    }

    /**
     * 分析能力一旦成功产出过结论，就不再算"能力缺失"。
     *
     * 区分"还没接"与"接了但这次失败"是刻意的：总任务说明第 12 节要求把
     * 模型故障与语义不确定分开，而两者在代码里都表现为"拿到 null"。
     */
    @Test
    fun capabilityIsNotMissingOnceASemanticsWasProduced() = runTest {
        val (s, _) = session()
        assertTrue(s.captureStatus().analysisCapabilityMissing, "初始没有能力")

        s.semanticsProvider = { visibleSemantics }
        s.shouldDisplay(request())
        assertFalse(
            s.captureStatus().analysisCapabilityMissing,
            "产出过一次结论后不应再报能力缺失",
        )
    }

    // ---------- 判定与计数 ----------

    @Test
    fun spoilerIsBlockedAndCounted() = runTest {
        val (s, _) = session()
        s.semanticsProvider = {
            DanmakuSemantics(
                scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                factIds = setOf("F_ENDING"),
            )
        }
        assertFalse(s.shouldDisplay(request()))
        val c = s.counters.value
        assertEquals(1L, c.blockedSpoiler)
        assertEquals(1L, c.evaluated)
        assertEquals(0L, c.visible)
    }

    @Test
    fun contentViolationIsBlockedInEveryTier() = runTest {
        for (tier in GuardTier.entries) {
            val (s, _) = session(tier = tier)
            s.semanticsProvider = {
                DanmakuSemantics(
                    scores = mapOf(GuardCategory.EXPLICIT_OR_HARASSMENT to 0.99),
                )
            }
            assertFalse(
                s.shouldDisplay(request()),
                "内容维度在所有档位都必须屏蔽（$tier）",
            )
        }
    }

    @Test
    fun revealedFactIsVisible() = runTest {
        val (s, _) = session(episode = 3.0)
        s.semanticsProvider = {
            DanmakuSemantics(
                scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                factIds = setOf("F_IDENTITY"), // 第 1 集已揭晓
            )
        }
        assertTrue(s.shouldDisplay(request()), "揭晓后应放行")
        assertEquals(1L, s.counters.value.visible)
    }

    @Test
    fun countersAreMutuallyExclusiveAndComplete() = runTest {
        val (s, src) = session()
        s.semanticsProvider = { spoilerSemantics }
        s.shouldDisplay(request(id = "a")) // blocked（达到剧透阈值但缺事实 → 保守屏蔽）
        assertEquals(1L, s.counters.value.total, "前置条件：已有计数")

        // 已揭晓事实 → visible，与上面的 blocked 构成两个不同分类
        s.semanticsProvider = { visibleSemantics }
        repeat(3) { i -> s.shouldDisplay(request(id = "n$i")) }

        // 提供者返回 null → 未经审核 → 不显示，计入 failed
        // （总任务说明第 12 节：未审核内容不显示，且排队/失败不能从分母里消失）
        s.semanticsProvider = { null }
        repeat(2) { i -> s.shouldDisplay(request(id = "k$i")) }

        src.setEnabled(false)
        runCurrent()
        repeat(4) { i -> s.shouldDisplay(request(id = "o$i")) }

        val c = s.counters.value
        // 每个被处理的条目必须恰好落在一个分类里。
        // 关闭之后应先恢复放行——这是总任务说明第 13 节的要求，
        // 也是"用户能关掉它"这条交付的判据。
        assertEquals(10L, c.total, "计数分类必须互斥且完备")
        assertEquals(1L, c.blockedSpoiler)
        assertEquals(3L, c.visible)
        assertEquals(2L, c.failed, "未审核内容计入 failed，而不是资料缺失旁路")
        assertEquals(4L, c.bypassedOff, "关闭后的条目必须计入 bypassedOff")
        assertEquals(0L, c.bypassedNoKnowledge, "资料齐备时不得计入资料缺失")
        assertEquals(
            c.total,
            c.visible + c.blockedSpoiler + c.blockedContent + c.failed + c.bypassedOff +
                c.bypassedNoKnowledge + c.deferredTimeout,
            "所有分类之和必须等于总数",
        )
    }

    // ---------- 切集与生成号 ----------

    @Test
    fun startEpisodeIncrementsGenerationAndResetsCounters() = runTest {
        val (s, _) = session()
        s.semanticsProvider = { spoilerSemantics }
        s.shouldDisplay(request())
        val before = s.generation

        s.startEpisode(episodeNumber = 3.0, knowledge = Fixtures.pack(), alignment = Fixtures.aligned)
        assertTrue(s.generation > before, "切集必须递增生成号")
        assertEquals(0L, s.counters.value.total, "新会话计数应重置")
    }

    @Test
    fun staleGenerationIsDetected() = runTest {
        val (s, _) = session()
        val stale = s.generation
        s.startEpisode(episodeNumber = 3.0, knowledge = Fixtures.pack(), alignment = Fixtures.aligned)
        assertFalse(
            s.isCurrentGeneration(stale),
            "旧生成号必须失效，供调用方丢弃迟到的异步结果",
        )
        assertTrue(s.isCurrentGeneration(s.generation))
    }

    @Test
    fun seekingBackReLocksWithinSameGeneration() = runTest {
        val (s, _) = session(episode = 1.0)
        // F_IDENTITY 在第 1 集 5:00–5:20 揭晓；均衡档余量 15s，因此 5:35 之后才解锁
        s.semanticsProvider = {
            DanmakuSemantics(
                scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                factIds = setOf("F_IDENTITY"),
            )
        }
        val afterReveal = 6 * 60_000L
        assertTrue(s.shouldDisplay(request(position = afterReveal)), "揭晓时间之后应放行")
        // 判定只看**当前**位置，不记录历史最大进度；拖回片头必须重新受保护。
        assertFalse(
            s.shouldDisplay(request(position = 0L, id = "d2")),
            "拖回片头必须重新受保护，不得使用历史最大进度解锁",
        )
        assertEquals(1L, s.counters.value.visible)
        assertEquals(1L, s.counters.value.blockedSpoiler)
    }

    // ---------- 超时 ----------

    /**
     * 注意：超时是对**真实时间**的断言，因此这里用 [runBlocking] 而不是 `runTest`。
     * `runTest` 使用虚拟时间调度器，会把 `withTimeoutOrNull` 的时间推进立即结算，
     * 导致超时分支被立刻触发（测不到真实行为）。
     */
    @Test
    fun timeoutResultsInNotDisplayedAndCounted() = runBlocking {
        // 必须用**独立**作用域：若把 runBlocking 的 this 传进去，会话的配置收集器是永不结束的
        // 子协程，runBlocking 会一直等它结束 → 死等（本项目实际踩过这个坑）。
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true))
            val s = DanmakuGuardSession(src, scope = sessionScope)
            s.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)
            // 让判定在超时窗口内无法完成：语义提供者阻塞超过超时值
            s.semanticsProvider = {
                Thread.sleep(300)
                spoilerSemantics
            }
            val shown = s.tryDecide(request(), timeoutMillis = 10L)
            assertFalse(shown, "判定超时不得显示（本次跳过，不稍后补发）")
            assertEquals(
                1L, s.counters.value.deferredTimeout,
                "超时必须计入 deferredTimeout，不能从分母消失",
            )
            assertEquals(0L, s.counters.value.visible, "超时不得被当作放行")
        } finally {
            sessionScope.cancel()
        }
    }

    /** 同 [timeoutResultsInNotDisplayedAndCounted]：断言真实时间，故用 [runBlocking]。 */
    @Test
    fun fastDecisionIsNotCountedAsTimeout() = runBlocking {
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true))
            val s = DanmakuGuardSession(src, scope = sessionScope)
            s.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)
            s.semanticsProvider = {
                DanmakuSemantics(
                    scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                    factIds = setOf("F_ENDING"),
                )
            }
            val shown = s.tryDecide(request(), timeoutMillis = 5000L)
            assertFalse(shown, "应被判定为剧透而屏蔽")
            assertEquals(0L, s.counters.value.deferredTimeout, "正常判定不得计入超时")
            assertEquals(1L, s.counters.value.blockedSpoiler)
        } finally {
            sessionScope.cancel()
        }
    }

    /** 同 [timeoutResultsInNotDisplayedAndCounted]：断言真实时间，故用 [runBlocking]。 */
    @Test
    fun fastVisibleDecisionIsNotCountedAsTimeout() = runBlocking {
        val sessionScope = CoroutineScope(SupervisorJob())
        try {
            val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true))
            val s = DanmakuGuardSession(src, scope = sessionScope)
            s.startEpisode(2.0, Fixtures.pack(), Fixtures.aligned)
            s.semanticsProvider = {
                DanmakuSemantics(
                    scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.99),
                    factIds = setOf("F_IDENTITY"), // 第 1 集已揭晓
                )
            }
            val shown = s.tryDecide(request(), timeoutMillis = 5000L)
            assertTrue(shown, "已揭晓内容应放行")
            assertEquals(1L, s.counters.value.visible)
            assertEquals(0L, s.counters.value.deferredTimeout)
        } finally {
            sessionScope.cancel()
        }
    }

    // ---------- 状态快照（供界面显示） ----------

    /**
     * 关键诚实性约束：原型阶段没有模型，界面**不得**显示为"已启用"。
     * 状态必须是由客观能力推导出的降级状态。
     */
    @Test
    fun statusReportsKnowledgeMissingRatherThanEnabled() = runTest {
        val (s, _) = session(enabled = true, withKnowledge = false, episode = 2.0)
        val status = s.captureStatus()
        assertEquals(GuardFeatureState.KNOWLEDGE_MISSING, status.state)
        assertFalse(status.knowledgeLoaded)
        assertFalse(status.displayLine().contains("已启用"))
    }

    /**
     * 有资料但没有分析能力时，状态报告的是"能力缺失"，**不是** RULE_PROTOTYPE。
     *
     * 这一条是刻意改过的：早期实现把它报成 RULE_PROTOTYPE（"固定规则／时间线测试"），
     * 而实际上没有任何规则在跑——判定路径拿不到语义，未审核内容一律不显示。
     * 若继续报 RULE_PROTOTYPE，用户会以为自己受到"固定规则"的保护。
     */
    @Test
    fun statusReportsCapabilityMissingWhenKnowledgePresentButNoSemantics() = runTest {
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        val status = s.captureStatus()
        assertEquals(GuardFeatureState.MODEL_FAILURE, status.state)
        assertFalse(status.semanticsReady, "默认不得声称已接入模型")
        assertTrue(status.analysisCapabilityMissing, "从未产出结论 ⇒ 能力缺失")
        assertTrue(status.knowledgeLoaded)
        assertTrue(status.alignmentVerified)
        assertTrue(status.episodeKnown)
    }

    @Test
    fun statusReportsTimelineVerifiedOnlyWhenSemanticsReady() = runTest {
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        s.semanticsProvider = { spoilerSemantics }

        // 只"装了一个提供者"不构成分析能力：还没有任何一条弹幕被真正判过。
        // 此时判定路径拿不到结论（提供者尚未被调用），因此不能声称已接入模型——
        // 否则会出现"界面说已接入、实际全部不显示"的最糟组合。
        assertFalse(s.semanticsReady, "尚未产出任何结论时不得声称已接入模型")
        assertEquals(GuardFeatureState.MODEL_FAILURE, s.captureStatus().state)
        assertTrue(s.captureStatus().analysisProviderInstalled, "诊断应记录提供者已装入")

        // 真正产出一次结论之后，能力才算接入。
        s.shouldDisplay(request())
        assertTrue(s.semanticsReady, "产出结论后应报告已接入")
        assertEquals(GuardFeatureState.TIMELINE_VERIFIED, s.captureStatus().state)
    }

    @Test
    fun statusReportsModelLoadFailure() = runTest {
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        s.semanticsProvider = { spoilerSemantics }
        s.modelLoadFailed = true
        assertEquals(GuardFeatureState.MODEL_FAILURE, s.captureStatus().state)
    }

    @Test
    fun statusReportsModelFailureFromObservedAnalysisFailure() = runTest {
        // 会话自己观察到的分析失败也要能被上报，而不是只靠调用方手动置位。
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        s.semanticsProvider = { DanmakuSemantics(scores = emptyMap(), analysisFailed = true) }
        assertFalse(s.shouldDisplay(request()))
        assertEquals(GuardFeatureState.MODEL_FAILURE, s.captureStatus().state)
        assertFalse(s.semanticsReady, "观测到失败时不得声称已就绪")
    }

    @Test
    fun statusRecoversAfterASuccessfulAnalysis() = runTest {
        // 单次失败是暂时的，不该让界面永久停在"模型故障"
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        s.semanticsProvider = { DanmakuSemantics(scores = emptyMap(), analysisFailed = true) }
        s.shouldDisplay(request(id = "bad"))
        assertEquals(GuardFeatureState.MODEL_FAILURE, s.captureStatus().state)

        s.semanticsProvider = { spoilerSemantics }
        s.shouldDisplay(request(id = "good"))
        assertEquals(
            GuardFeatureState.TIMELINE_VERIFIED, s.captureStatus().state,
            "恢复后状态必须跟着恢复，否则界面会一直报故障",
        )
    }

    @Test
    fun statusReportsOffWhenDisabledRegardlessOfCapabilities() = runTest {
        val (s, _) = session(enabled = false, withKnowledge = true, episode = 2.0)
        s.semanticsProvider = { spoilerSemantics }
        val status = s.captureStatus()
        assertEquals(GuardFeatureState.OFF, status.state)
        assertFalse(status.enabled)
    }

    @Test
    fun statusReportsEpisodeUnknownInsteadOfGuessing() = runTest {
        // 未调用 startEpisode：集序未知，属于能力降级
        val src = InMemoryGuardConfigSource(GuardUserConfig(enabled = true, tier = GuardTier.BALANCED))
        val s = DanmakuGuardSession(src, scope = backgroundScope)
        runCurrent()
        val status = s.captureStatus()
        assertFalse(status.episodeKnown, "集序未知必须如实上报，不得沿用上一集")
        assertTrue(status.displayLine().contains("集序未知"))
    }

    @Test
    fun statusCountersReflectDecisionsIncludingFailures() = runTest {
        val (s, _) = session()
        s.semanticsProvider = { spoilerSemantics }
        s.shouldDisplay(request()) // 被屏蔽
        s.semanticsProvider = { null }
        s.shouldDisplay(request(id = "d2")) // 无语义 → 未经审核 → 不显示
        val status = s.captureStatus()
        assertEquals(1L, status.counters.blockedSpoiler)
        assertEquals(1L, status.counters.failed, "未审核内容必须计入失败而不是消失")
        assertEquals(
            status.counters.total,
            status.counters.blockedSpoiler + status.counters.failed,
        )
    }

    @Test
    fun startEpisodeResetsCountersAndKeepsCapabilities() = runTest {
        val (s, _) = session()
        s.semanticsProvider = { spoilerSemantics }
        s.shouldDisplay(request())
        assertEquals(1L, s.captureStatus().counters.blockedSpoiler)
        s.startEpisode(episodeNumber = 3.0, knowledge = Fixtures.pack(), alignment = Fixtures.aligned)
        val status = s.captureStatus()
        assertEquals(0L, status.counters.blockedSpoiler, "切集必须重置计数")
        assertTrue(status.knowledgeLoaded, "切集后资料仍在")
    }
}
