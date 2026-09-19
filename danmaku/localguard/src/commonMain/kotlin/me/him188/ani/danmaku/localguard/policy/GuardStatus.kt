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
            if (!semanticsReady) add("未接入模型")
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
): GuardStatus {
    val state = when {
        !config.enabled -> GuardFeatureState.OFF
        modelFailed -> GuardFeatureState.MODEL_FAILURE
        !knowledgeLoaded -> GuardFeatureState.KNOWLEDGE_MISSING
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
        counters = counters,
    )
}
