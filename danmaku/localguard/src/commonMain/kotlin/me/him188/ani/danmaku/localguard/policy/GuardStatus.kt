/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.localguard.policy

/**
 * 面向界面的状态快照。
 *
 * 硬性约束（总任务说明第 13 节）：
 * - 播放器界面**不显示事实正文、角色死亡类别数量或其他会暗示剧情的统计**。
 *   因此本结构里**没有**任何剧情字段，只有运行/降级状态与计数。
 * - 被屏蔽正文及详细原因默认隐藏。
 * - 原型阶段必须标注为「固定规则／时间线测试」；真实模型未就绪时
 *   **不得**标为"AI 防剧透已启用"。
 */
data class GuardStatus(
    val enabled: Boolean,
    val tier: GuardTier,
    val state: GuardFeatureState,
    /** 集序是否已确定。false 表示处于能力降级（不会凭猜测屏蔽）。 */
    val episodeKnown: Boolean,
    /** 是否已加载剧情知识包。 */
    val knowledgeLoaded: Boolean,
    /** 片源对齐是否已验证。 */
    val alignmentVerified: Boolean,
    /** 是否已接入语义分析（真实模型）。原型阶段为 false。 */
    val semanticsReady: Boolean,
    /**
     * 是否**没有可用的分析能力**（尚未接入，或提供者从未产出过任何结论）。
     *
     * 与 [semanticsReady] 的区别：`semanticsReady == false` 的含义是"当前不具备分析能力"，
     * 而本字段进一步区分它属于"还没接"（能力缺失）还是"接了但出故障"（模型故障）。
     * 两者的界面文案与用户预期完全不同，不能合并。
     */
    val analysisCapabilityMissing: Boolean = false,
    /**
     * 是否**被赋过**语义提供者。
     *
     * 只用于诊断，不参与状态判定：一个恒返回 null 的提供者不构成分析能力，
     * 因此它出现而 [analysisCapabilityMissing] 仍为 true 是**正常**的——
     * 那说明"装了提供者但从未产出结论"，正是需要排查的情形。
     * 这个字段的价值就在于把这种情况与"根本没装"区分开。
     */
    val analysisProviderInstalled: Boolean = false,
    val counters: GuardCounters,
) {
    /**
     * 面向用户的一行状态文案。
     *
     * 只描述**运行状态**，不含任何剧情内容。
     */
    fun displayLine(): String = buildString {
        if (!enabled) {
            append("本地过滤：已关闭")
            return@buildString
        }
        append("本地过滤：")
        append(state.displayName)
        append("（")
        append(
            when (tier) {
                GuardTier.LENIENT -> "宽松"
                GuardTier.BALANCED -> "均衡"
                GuardTier.STRICT -> "严格"
            },
        )
        append("）")
        val degradations = buildList {
            if (!episodeKnown) add("集序未知")
            if (!knowledgeLoaded) add("资料缺失")
            if (!alignmentVerified) add("对齐未验证")
            if (!semanticsReady) {
                // 关键区分：没有分析能力时不能只说"未接入模型"就完事——
                // 此时未审核内容不会被显示，用户必须知道"看不到弹幕是功能在起作用"，
                // 而不是以为播放器坏了。
                if (analysisCapabilityMissing) add("无分析能力，未审核弹幕不显示")
                else add("分析故障，未审核弹幕不显示")
            }
        }
        if (degradations.isNotEmpty()) {
            append(" · 降级：")
            append(degradations.joinToString("、"))
        }
    }

    /**
     * 诊断明细。**不含弹幕正文与剧情事实**，可安全写入日志或显示在次级页面。
     */
    fun diagnosticsLines(): List<String> = listOf(
        "enabled=$enabled",
        "tier=${tier.name}",
        "state=${state.name}",
        "episodeKnown=$episodeKnown",
        "knowledgeLoaded=$knowledgeLoaded",
        "alignmentVerified=$alignmentVerified",
        "semanticsReady=$semanticsReady",
        "analysisCapabilityMissing=$analysisCapabilityMissing",
        "analysisProviderInstalled=$analysisProviderInstalled",
        "evaluated=${counters.evaluated}",
        "visible=${counters.visible}",
        "blockedContent=${counters.blockedContent}",
        "blockedSpoiler=${counters.blockedSpoiler}",
        "bypassedOff=${counters.bypassedOff}",
        "bypassedNoKnowledge=${counters.bypassedNoKnowledge}",
        "deferredTimeout=${counters.deferredTimeout}",
        "failed=${counters.failed}",
        "total=${counters.total}",
    )

    companion object {
        /**
         * 能力状态未知时的快照。
         *
         * 使用场景：界面只拿到了用户配置，却拿不到"资料是否就绪"等客观条件。
         * 此时**必须**如实显示为未知，而不能假定为已启用——原型阶段尤其如此。
         */
        fun unknown(
            enabled: Boolean,
            tier: GuardTier,
            counters: GuardCounters = GuardCounters(),
        ): GuardStatus = GuardStatus(
            enabled = enabled,
            tier = tier,
            state = if (enabled) GuardFeatureState.KNOWLEDGE_MISSING else GuardFeatureState.OFF,
            episodeKnown = false,
            knowledgeLoaded = false,
            alignmentVerified = false,
            semanticsReady = false,
            analysisCapabilityMissing = true,
            analysisProviderInstalled = false,
            counters = counters,
        )
    }
}

/**
 * 从会话与配置推导状态。
 *
 * 这个映射刻意显式化：**能力状态由客观条件决定，不由用户开关决定**。
 * 例如开关打开但资料缺失时，状态必须是 [GuardFeatureState.KNOWLEDGE_MISSING]，
 * 而不是"已启用"。
 */
fun deriveGuardStatus(
    config: GuardUserConfig,
    counters: GuardCounters,
    episodeKnown: Boolean,
    knowledgeLoaded: Boolean,
    alignmentVerified: Boolean,
    semanticsReady: Boolean,
    modelFailed: Boolean = false,
    analysisCapabilityMissing: Boolean = false,
    analysisProviderInstalled: Boolean = false,
): GuardStatus {
    val state = when {
        !config.enabled -> GuardFeatureState.OFF
        // 资料缺失优先于能力缺失：没有剧情包时，即使有分析能力也做不了时间判断，
        // 这才是用户首先需要知道的事。
        !knowledgeLoaded -> GuardFeatureState.KNOWLEDGE_MISSING
        // 有资料但没有可用分析能力：判定路径无法给出结论，未审核内容不会被显示。
        // 若在此谎报成 RULE_PROTOTYPE，用户会以为过滤正在工作。
        analysisCapabilityMissing -> GuardFeatureState.MODEL_FAILURE
        modelFailed -> GuardFeatureState.MODEL_FAILURE
        !alignmentVerified -> GuardFeatureState.ALIGNMENT_UNVERIFIED
        !semanticsReady -> GuardFeatureState.RULE_PROTOTYPE
        else -> GuardFeatureState.TIMELINE_VERIFIED
    }
    return GuardStatus(
        enabled = config.enabled,
        tier = config.tier,
        state = state,
        episodeKnown = episodeKnown,
        knowledgeLoaded = knowledgeLoaded,
        alignmentVerified = alignmentVerified,
        semanticsReady = semanticsReady,
        analysisCapabilityMissing = analysisCapabilityMissing,
        analysisProviderInstalled = analysisProviderInstalled,
        counters = counters,
    )
}
