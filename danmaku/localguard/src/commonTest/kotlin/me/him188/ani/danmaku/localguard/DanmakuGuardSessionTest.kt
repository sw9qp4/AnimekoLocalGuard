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

    @Test
    fun noSemanticsIsTreatedAsUnknownNotSafe() = runTest {
        val (s, _) = session()
        // 不设置 semanticsProvider：原型阶段恒为 null
        assertTrue(s.shouldDisplay(request()), "原型阶段无语义信息时按旁路处理并上报")
        val c = s.counters.value
        assertEquals(1L, c.bypassedNoKnowledge)
        assertEquals(0L, c.evaluated, "未参与判定的条目不得计入 evaluated")
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
        s.semanticsProvider = { null }
        repeat(2) { i -> s.shouldDisplay(request(id = "k$i")) }

        src.setEnabled(false)
        runCurrent()
        repeat(4) { i -> s.shouldDisplay(request(id = "o$i")) }

        val c = s.counters.value
        // 每个被处理的条目必须恰好落在一个分类里
        assertEquals(10L, c.total, "计数分类必须互斥且完备")
        assertEquals(1L, c.blockedSpoiler)
        assertEquals(3L, c.visible)
        assertEquals(2L, c.bypassedNoKnowledge)
        assertEquals(4L, c.bypassedOff)
        assertEquals(c.total, c.evaluated + c.bypassedOff + c.bypassedNoKnowledge, "分类之和必须等于总数")
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

    @Test
    fun statusReportsRulePrototypeWhenKnowledgePresentButNoSemantics() = runTest {
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        val status = s.captureStatus()
        assertEquals(GuardFeatureState.RULE_PROTOTYPE, status.state)
        assertFalse(status.semanticsReady, "默认不得声称已接入模型")
        assertTrue(status.knowledgeLoaded)
        assertTrue(status.alignmentVerified)
        assertTrue(status.episodeKnown)
    }

    @Test
    fun statusReportsTimelineVerifiedOnlyWhenSemanticsReady() = runTest {
        val (s, _) = session(enabled = true, withKnowledge = true, episode = 2.0)
        // 安装真实提供者即视为"已接入模型"——能力状态由客观事实推导，不靠额外的标志位
        s.semanticsProvider = { spoilerSemantics }
        assertTrue(s.semanticsReady)
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
        s.shouldDisplay(request(id = "d2")) // 无语义 → 降级放行
        val status = s.captureStatus()
        assertEquals(1L, status.counters.blockedSpoiler)
        assertEquals(1L, status.counters.bypassedNoKnowledge)
        assertEquals(
            status.counters.total,
            status.counters.blockedSpoiler + status.counters.bypassedNoKnowledge,
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
