/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.policy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import me.him188.ani.danmaku.localguard.cache.AnalyzerVersion
import me.him188.ani.danmaku.localguard.cache.FactRelationKey
import me.him188.ani.danmaku.localguard.cache.GenericClassificationKey
import me.him188.ani.danmaku.localguard.cache.KnowledgeVersion
import me.him188.ani.danmaku.localguard.cache.SemanticsCache
import me.him188.ani.danmaku.localguard.cache.TextFingerprint
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.isVerified

/**
 * 一次播放会话的过滤状态。
 *
 * 集成约定（对应总任务说明第 5、11、12 节）：
 * - 本类位于**上游既有过滤之后、弹幕进入显示之前**。上游已丢弃的条目不会被本类拿回，
 *   因为调用方只提交上游过滤后的事件。
 * - 本类只做"是否允许显示"的决定；**不修改**任何弹幕属性（原文/时间/颜色/类型/顺序）。
 * - 决定是**同步纯计算**，不做文件 IO、不做网络。调用方仍应加超时保护（见 [tryDecide]）。
 * - 生成号（generation）只在 [startEpisode] 时递增。**拖回片头不会改变生成号**，
 *   因为"是否已揭晓"由当前播放位置决定，而不是由历史进度决定。
 */

/**
 * 单条弹幕的判定请求。上游适配层负责从 `DanmakuInfo` 投影，避免本模块依赖上游类型。
 */
data class GuardRequest(
    /** 去重/诊断用的弹幕标识（去标识化） */
    val id: String,
    /** 弹幕文本 */
    val text: String,
    /**
     * **判定所用的播放位置（毫秒）**。语义必须与事件类型一致：
     *
     * - `DanmakuEvent.Add`：使用**判定时刻**的播放器媒体位置。
     * - `DanmakuEvent.Repopulate`：使用**该事件自带的** `playTimeMillis`
     *   （它是事件产生时的播放器时间），不要重新读取，否则"判定时刻"与"事件时刻"不一致，
     *   会导致提前解锁或滞后。
     *
     * 明确**不是**弹幕自身在时间轴上的位置——那是 `DanmakuInfo.playTimeMillis`，
     * 由上游负责，本模块不使用。
     */
    val decisionPositionMillis: Long,
    /**
     * 语义窗口起点（毫秒）。用于把"依赖场景的事实关系"绑定到具体时间段，
     * 避免同一句话在不同时间点被当作同一个判断。
     *
     * 默认等于判定位置：调用方通常只需要判定位置。
     * 已知限制：真正"按弹幕自身的播放时刻"建窗口需要上游的 `playTimeMillis`，
     * 本模块目前拿不到，因此默认值只是当前判定时刻，属于**宽于理想值**的窗口。
     */
    val semanticWindowStartMillis: Long = decisionPositionMillis,
    /** 语义窗口终点（毫秒）。默认等于判定位置，理由同上。 */
    val semanticWindowEndMillis: Long = decisionPositionMillis,
) {
    init {
        require(semanticWindowStartMillis <= semanticWindowEndMillis) {
            "semantic window start must be <= end"
        }
    }
}

/** 判定统计。状态互斥且不隐藏排队/失败/过期（总任务说明第 14 节）。 */
data class GuardCounters(
    val evaluated: Long = 0,
    val visible: Long = 0,
    val blockedContent: Long = 0,
    val blockedSpoiler: Long = 0,
    val bypassedOff: Long = 0,
    val bypassedNoKnowledge: Long = 0,
    val deferredTimeout: Long = 0,
    val failed: Long = 0,
) {
    /** 不变式：所有分类之和等于处理过的条目总数。 */
    val total: Long
        get() = visible + blockedContent + blockedSpoiler + bypassedOff + bypassedNoKnowledge + deferredTimeout + failed
}

/**
 * 播放会话的过滤入口。
 *
 * 线程模型：所有方法都可能在任意线程被调用。内部只用一把锁保护可变字段，
 * 判定过程是纯 CPU 计算，不阻塞调用线程做 IO。
 */
class DanmakuGuardSession(
    private val configSource: GuardConfigSource,
    /** 剧情知识包。为 null 表示资料缺失 → 按 [GuardFeatureState.KNOWLEDGE_MISSING] 降级。 */
    knowledge: StoryKnowledgePack? = null,
    alignment: TimeAlignment = TimeAlignment.Unaligned,
    /**
     * 用于持续跟随配置变化的作用域。判定路径无法挂起（它在弹幕显示路径上），
     * 因此配置必须被镜像到内存里以便同步读取；这里不做读取则开关不会生效。
     * 应传入与播放会话同寿命的作用域，并在切集时取消。
     */
    scope: CoroutineScope,
    /**
     * 语义缓存。为 null 表示不缓存（每次判定都调用提供者）。
     *
     * 两层缓存的键与复用范围见 [resolveSemantics]。缺少必要版本信息时**只**使用通用层，
     * 因为事实关系层离开作品/集数/资料版本就没有意义。
     */
    private val semanticsCache: SemanticsCache? = null,
    /**
     * 分析器版本。参与语义缓存键。
     * 为 null 时**完全不缓存**（无法构造键），而不是用猜的版本号去建键。
     */
    private val analyzerVersion: AnalyzerVersion? = null,
    /** 剧情包版本。为 null 时事实关系层不参与，只用通用分类层。 */
    private val knowledgeVersion: KnowledgeVersion? = null,
    /** 作品标识。**不得跨作品复用**事实关系缓存。 */
    private val workId: String = "unknown-work",
) {
    private val lock = Any()

    /**
     * 配置的本地镜像。
     *
     * 为什么不每次判定都去读存储：判定发生在显示路径上，不能为读一个开关而挂起或做 IO。
     * 由 [scope] 上的收集器持续刷新，因此界面一改就立即生效。
     */
    @Volatile
    private var configInternal: GuardUserConfig = configSource.current

    init {
        scope.launch {
            configSource.config.collect { configInternal = it }
        }
    }

    private var knowledgePack: StoryKnowledgePack? = knowledge

    @Volatile
    private var currentAlignment: TimeAlignment = alignment

    /** 生成号：切集时递增，用于隔离旧会话的异步结果。 */
    private var generationInternal: Long = 0

    /** 当前集（条目内集序，允许小数）。null 表示尚未确定。 */
    private var episodeNumberInternal: Double? = null

    private val countersInternal = MutableStateFlow(GuardCounters())

    val counters: StateFlow<GuardCounters> = countersInternal.asStateFlow()

    val config: GuardUserConfig get() = configInternal

    /**
     * 语义层是否已接入。
     *
     * 由**两个客观条件共同决定**，而不是调用方手动置位的单一开关：
     * 1. 是否安装了真实语义提供者（见 [semanticsProvider]）；
     * 2. 是否观测到分析失败（见 [lastSemanticsAnalysisFailed]）。
     *
     * 这样设计的原因：手动置位的开关很容易被忘记，结果就是界面永远显示
     * "未接入模型"（即使模型已在跑）或永远显示正常（即使模型已坏）。
     */
    val semanticsReady: Boolean
        get() = hasSemanticsProvider && !lastSemanticsAnalysisFailed

    /**
     * 调用方报告的**不可恢复**故障（模型文件损坏、加载失败等）。
     *
     * 与 [lastSemanticsAnalysisFailed] 的区别：后者是单次判定观察到的失败，可自愈；
     * 这里是"这个模型整体不可用"，在调用方清除之前一直有效。
     */
    @Volatile
    var modelLoadFailed: Boolean = false

    /** 是否安装过真实语义提供者（区别于默认的"恒返回 null"）。 */
    @Volatile
    private var hasSemanticsProvider: Boolean = false

    /**
     * 最近一次语义解析是否表明"分析失败"。
     *
     * 由 [resolveSemantics] 在**缓存命中与全新计算两条路径上**统一设置：
     * 缓存里存着失败结论时也必须算作失败，否则模型坏了但缓存命中，
     * 界面会显示成正常。
     */
    @Volatile
    private var lastSemanticsAnalysisFailed: Boolean = false

    val generation: Long get() = synchronized(lock) { generationInternal }

    /**
     * 当前状态快照。只包含运行/降级状态与计数，**不含**弹幕正文或剧情事实，
     * 因此可以安全地显示在界面上。
     */
    fun captureStatus(): GuardStatus {
        val pack = synchronized(lock) { knowledgePack }
        return deriveGuardStatus(
            config = config,
            counters = countersInternal.value,
            // 集序未知属于能力降级：此时不会凭猜测屏蔽，必须在状态里如实体现。
            episodeKnown = synchronized(lock) { episodeNumberInternal } != null,
            knowledgeLoaded = pack != null,
            alignmentVerified = currentAlignment.isVerified,
            semanticsReady = semanticsReady,
            modelFailed = modelLoadFailed || lastSemanticsAnalysisFailed,
        )
    }

    /**
     * 开始新一集的过滤会话。切集/换番时必须调用。
     *
     * 递增生成号会使持有旧生成号的结果失效（调用方应通过 [isCurrentGeneration] 校验）。
     */
    fun startEpisode(
        episodeNumber: Double,
        knowledge: StoryKnowledgePack? = knowledgePack,
        alignment: TimeAlignment = TimeAlignment.Unaligned,
    ) {
        synchronized(lock) {
            generationInternal += 1
            episodeNumberInternal = episodeNumber
            knowledgePack = knowledge
            currentAlignment = alignment
        }
        countersInternal.value = GuardCounters()
        // 切集后依赖剧情的结论不再适用，必须重算。
        // 只清事实关系层而不清通用分类层：后者与剧情无关，跨集复用是第 11 节允许的，
        // 一起丢掉只是白白重算。
        semanticsCache?.evictFactRelations()
    }

    /** 校验某个生成号是否仍是当前会话。 */
    fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(lock) { generationInternal == generation }

    /**
     * 判定一条弹幕是否允许显示。
     *
     * @return true = 允许显示；false = 屏蔽
     */
    fun shouldDisplay(request: GuardRequest): Boolean {
        val cfg = config

        // 1) 总开关关闭 → 完全恢复上游行为
        if (!cfg.enabled) {
            bump { it.copy(bypassedOff = it.bypassedOff + 1) }
            return true
        }

        val pack = synchronized(lock) { knowledgePack }
        val episodeNumber = synchronized(lock) { episodeNumberInternal }
        val alignment = currentAlignment

        // 2) 集数未知 → 无法做时间判断。保守：不屏蔽（属于能力降级，必须如实上报状态）
        if (episodeNumber == null || pack == null) {
            bump { it.copy(bypassedNoKnowledge = it.bypassedNoKnowledge + 1) }
            return true
        }

        // 3) 解析语义：先查缓存，未命中才让提供者计算。
        //
        //    缓存分两层（总任务说明第 11 节），**两层互斥**，由结论是否依赖剧情事实决定：
        //    - 不依赖事实（factIds 为空）：与作品/集数/时间无关 → 可跨作品复用；
        //    - 依赖事实：必须绑定作品、集数、语义窗口与资料版本，不得跨作品复用。
        //    最终显示决定不入此缓存：它依赖当前进度、档位与映射，必须每次重新计算。
        val semantics = resolveSemantics(request, pack)
        if (semantics == null) {
            bump { it.copy(bypassedNoKnowledge = it.bypassedNoKnowledge + 1) }
            return true
        }

        val engine = DanmakuGuardEngine(pack, alignment)
        // 判定本身也可能抛异常（例如资料包内部不一致）。按"分析失败"处理：
        // **不显示**，而不是静默放行。放行未审核内容正是第 12 节禁止的。
        val decision = try {
            engine.decide(
                semantics = semantics,
                position = PlaybackPosition(episodeNumber, request.decisionPositionMillis),
                policy = TierPolicy.of(cfg.tier),
            )
        } catch (e: Throwable) {
            GuardDecision.Failed(reason = "engine error: ${e::class.simpleName}")
        }

        return when (decision) {
            is GuardDecision.Visible -> {
                bump { it.copy(evaluated = it.evaluated + 1, visible = it.visible + 1) }
                true
            }

            is GuardDecision.BlockedContent -> {
                bump { it.copy(evaluated = it.evaluated + 1, blockedContent = it.blockedContent + 1) }
                false
            }

            is GuardDecision.BlockedSpoiler -> {
                bump { it.copy(evaluated = it.evaluated + 1, blockedSpoiler = it.blockedSpoiler + 1) }
                false
            }

            GuardDecision.Pending -> {
                // 待判定：**不显示**，并按超时口径计入（不隐藏排队/失败）
                bump { it.copy(deferredTimeout = it.deferredTimeout + 1) }
                false
            }

            GuardDecision.Expired -> {
                bump { it.copy(deferredTimeout = it.deferredTimeout + 1) }
                false
            }

            is GuardDecision.Failed -> {
                bump { it.copy(failed = it.failed + 1) }
                // 故障时未审核内容不显示（总任务说明第 12 节）
                false
            }
        }
    }

    /**
     * 带超时保护的判定。判定超时按"不显示"处理，并计入 `deferredTimeout`。
     *
     * 之所以提供这个包装：调用方位于弹幕显示路径上，**不能因为判定卡住而阻塞显示线程**。
     *
     * 实现要点：`withTimeoutOrNull` 只在**挂起点**生效，无法抢占一个纯同步的阻塞调用。
     * 因此这里先插入一个挂起点（[yield]）让超时机制有机会结算，再执行判定。
     *
     * **已知限制（必须如实记录）**：如果 [semanticsProvider] 本身执行了长时间的阻塞调用
     * （例如阻塞式 IO），它仍然会占用调用线程直到返回，超时只能保证"结果不被采用"，
     * 不能保证"调用线程不被占用"。接入真实模型时应使用可取消的挂起实现，
     * 并确保模型推理不跑在 UI / 渲染线程上。
     */
    suspend fun tryDecide(request: GuardRequest, timeoutMillis: Long = 8L): Boolean {
        val result = withTimeoutOrNull(timeoutMillis) {
            // 让超时机制先结算一次，否则纯同步的判定会让 withTimeoutOrNull 形同虚设。
            yield()
            shouldDisplay(request)
        }
        if (result == null) {
            bump { it.copy(deferredTimeout = it.deferredTimeout + 1) }
            return false
        }
        return result
    }

    private fun bump(transform: (GuardCounters) -> GuardCounters) {
        countersInternal.update(transform)
    }

    /**
     * 解析一条弹幕的语义，优先复用缓存。
     *
     * 顺序刻意是"先查两级缓存 → 全未命中才调用提供者"：
     * 提供者是这里唯一昂贵的一步（将来是模型推理），因此必须先给缓存命中留出机会。
     * 只看通用层就决定是否调用提供者是不行的——那样事实关系层永远不会被命中。
     *
     * **不缓存"无结果"**：提供者返回 null 表示"此刻拿不到语义"（例如模型未接入或暂时失败），
     * 那是临时状态而不是分析结论。缓存它会让一次瞬时故障固化成"这条弹幕永远没有语义"。
     */
    private fun resolveSemantics(request: GuardRequest, pack: StoryKnowledgePack): DanmakuSemantics? {
        val resolution = semanticsCache
        // 没有分析器版本就无法构造任何缓存键：复用"同版本的分析结果"这件事本身要求知道是哪个版本。
        // 这种情况下不缓存，直接计算，而不是用一个猜出来的版本号去建键。
        val analyzer = analyzerVersion
        if (resolution == null || analyzer == null) {
            return semanticsProvider(request).also(::observeSemantics)
        }

        val fingerprint = TextFingerprint.of(request.text)
        val genericKey = GenericClassificationKey(
            textFingerprint = fingerprint,
            analyzer = analyzer,
        )
        val knowledge = knowledgeVersion
        val episodeNumber = synchronized(lock) { episodeNumberInternal }
        // 作品 + 集数 + 语义窗口齐备才允许使用事实关系层。
        // 集序未知时**不**用占位值建键（那会把不同集的判断串在一起），
        // 也不在这里提前返回 null —— 那会把"无法建键"伪装成"提供者没有结果"，
        // 从而让调用方误判为资料缺失旁路，并且完全跳过提供者。
        val factKey = if (knowledge != null && episodeNumber != null) {
            FactRelationKey(
                textFingerprint = fingerprint,
                analyzer = analyzer,
                knowledge = knowledge,
                workId = workId,
                episodeNumber = episodeNumber,
                semanticWindowStartMillis = request.semanticWindowStartMillis,
                semanticWindowEndMillis = request.semanticWindowEndMillis,
            )
        } else {
            null
        }

        if (factKey != null) {
            resolution.getFactRelation(factKey)?.let { return it.also(::observeSemantics) }
        }
        resolution.getGeneric(genericKey)?.let { return it.also(::observeSemantics) }

        // 全未命中：调用提供者。
        val computed = semanticsProvider(request) ?: return null

        // 入哪一层由**这次结论是否依赖剧情事实**决定，两层互斥：
        //
        // - 不依赖任何事实（`factIds` 为空）：结论只由原文与类别分数决定，
        //   与作品、集数、时间窗口无关 → 进通用层，可跨作品复用。
        //   分析失败（`analysisFailed`）也走这一层：同一原文 + 同一分析器版本的失败是可复现的，
        //   重复重试只会反复浪费。
        // - 依赖事实：那么作品/集数/资料版本/语义窗口缺一不可 → 只进事实层。
        //   键不完整时**宁可不缓存**，也不能塞进通用层：那会让一部作品的剧透结论
        //   被另一部作品复用（第 11 节明确禁止跨作品复用事实关系）。
        if (computed.factIds.isEmpty()) {
            resolution.putGeneric(genericKey, computed)
        } else if (factKey != null) {
            resolution.putFactRelation(factKey, computed)
        }
        return computed.also(::observeSemantics)
    }

    /**
     * 记录本次语义解析的结果，供 [semanticsReady] / [captureStatus] 判断能力状态。
     *
     * 在**缓存命中与全新计算两条路径上都会被调用**：缓存里存着失败结论时也必须算作失败，
     * 否则"模型坏了但恰好命中缓存"会被显示成正常，而这恰恰是用户最需要看到降级提示的时候。
     *
     * 一次成功会清除上次的失败标记：单次失败是暂时的，不该让界面永久停在"模型故障"。
     */
    private fun observeSemantics(semantics: DanmakuSemantics?) {
        lastSemanticsAnalysisFailed = semantics?.analysisFailed == true
    }

    private var semanticsProviderInternal: (GuardRequest) -> DanmakuSemantics? = { null }

    /**
     * 语义提供者。原型阶段返回 null（= 无语义信息），
     * 接入真实模型后替换为模型输出。**不得**用关键词匹配冒充模型泛化能力。
     *
     * 安装一个提供者会把 [semanticsReady] 置为 true（只要没观测到失败）——
     * 能力状态由"真的装了提供者"这一客观事实推导，而不是靠调用方记得另外置一个标志位。
     */
    var semanticsProvider: (GuardRequest) -> DanmakuSemantics?
        get() = semanticsProviderInternal
        set(value) {
            semanticsProviderInternal = value
            hasSemanticsProvider = true
        }
}
