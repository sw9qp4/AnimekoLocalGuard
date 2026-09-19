/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.policy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 过滤功能的用户配置。
 *
 * 约束（总任务说明第 13 节）：
 * - **总开关默认关闭**；关闭时完全恢复 Animeko 原有行为。
 * - 默认档位为「均衡」。
 * - 原型阶段界面必须标注为「固定规则 / 时间线测试」，真实模型未就绪时**不得**标为"AI 防剧透已启用"。
 *
 * 这是**用户设置**，不含能力状态：是否已接入模型、资料是否缺失等由运行时客观条件推导，
 * 见 [GuardStatus]。持久化由 [GuardConfigSource] 的实现负责。
 */
data class GuardUserConfig(
    val enabled: Boolean = false,
    val tier: GuardTier = GuardTier.Default,
) {
    companion object {
        val Default = GuardUserConfig()
    }
}

/**
 * 能力状态。
 *
 * 总任务说明第 13 节要求至少区分这几种；界面**只展示状态，不展示剧情相关信息**。
 */
enum class GuardFeatureState {
    /** 用户关闭了功能 */
    OFF,

    /** 原型：仅固定规则 / 时间线测试，尚无真实模型 */
    RULE_PROTOTYPE,

    /** 通用 AI 分类已启用，但无分钟级资料 */
    GENERAL_AI,

    /** 分钟级资料已验证 */
    TIMELINE_VERIFIED,

    /** 片源对齐未验证，已降级 */
    ALIGNMENT_UNVERIFIED,

    /** 资料缺失，已降级 */
    KNOWLEDGE_MISSING,

    /** 模型故障 */
    MODEL_FAILURE,
    ;

    /** 供界面显示的中文标签。不含任何剧情内容。 */
    val displayName: String
        get() = when (this) {
            OFF -> "关闭"
            RULE_PROTOTYPE -> "固定规则／时间线测试"
            GENERAL_AI -> "通用 AI 分类"
            TIMELINE_VERIFIED -> "分钟级资料已验证"
            ALIGNMENT_UNVERIFIED -> "对齐未验证（降级）"
            KNOWLEDGE_MISSING -> "资料缺失（降级）"
            MODEL_FAILURE -> "模型故障"
        }
}

/**
 * 配置来源。
 *
 * 用接口而不是直接持有 [GuardUserConfig]，便于接入持久化实现，也便于测试注入。
 */
interface GuardConfigSource {
    /**
     * 配置流。实现应保证它持续反映最新值（持久化实现由存储层驱动）。
     */
    val config: Flow<GuardUserConfig>

    /**
     * 原子地修改配置。**这是唯一允许的写入入口**：使用"读取-修改-写回"而不是
     * 让调用方先取值再整体写入，避免并发修改互相覆盖。
     */
    suspend fun update(transform: (GuardUserConfig) -> GuardUserConfig)

    suspend fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
    }

    suspend fun setTier(tier: GuardTier) {
        update { it.copy(tier = tier) }
    }

    /** 当前值。供无法挂起的判定路径做**快速预检**使用（见 [DanmakuGuardSession]）。 */
    val current: GuardUserConfig

    /**
     * 尽早完成首次加载，使 [current] 立即可用。
     *
     * 默认无操作：内存实现的值本来就可立即读取。
     */
    suspend fun start() {
    }
}

/** 内存实现。默认关闭，用于测试与非持久化场景。 */
class InMemoryGuardConfigSource(
    initial: GuardUserConfig = GuardUserConfig.Default,
) : GuardConfigSource {
    private val _config = MutableStateFlow(initial)
    override val config: Flow<GuardUserConfig> = _config.asStateFlow()

    override suspend fun update(transform: (GuardUserConfig) -> GuardUserConfig) {
        _config.update(transform)
    }

    override val current: GuardUserConfig get() = _config.value
}
