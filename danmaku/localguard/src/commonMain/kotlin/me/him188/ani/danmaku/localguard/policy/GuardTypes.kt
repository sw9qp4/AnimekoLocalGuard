/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.policy

/**
 * 过滤强度档位。
 *
 * 三档**共用同一份模型语义输出**，只在阈值、未知信息策略与时间保守余量上不同
 * （见设计约束：不训练三个独立模型）。
 *
 * 单调性要求（必须由属性测试验证）：
 * ```
 * blocked(LENIENT) ⊆ blocked(BALANCED) ⊆ blocked(STRICT)
 * ```
 * 即：严格档永远不比宽松档放出更多内容。
 *
 * 该枚举名会被写入设置存储：**不要重命名或删除已有常量**，
 * 否则旧配置无法解析（读取时会回退到默认档位）。
 */
enum class GuardTier {
    /** 宽松：屏蔽高可信的明确剧透与明显不合适内容；较多保留证据不足的暗示与推测。 */
    LENIENT,

    /** 均衡：默认档。扩大对可信暗示和较隐晦不合适内容的识别。 */
    BALANCED,

    /** 严格：防剧透优先。对涉及尚未揭晓事件的可疑表述更保守，允许较高正常弹幕误杀。 */
    STRICT;

    companion object {
        /** 默认档位：均衡。 */
        val Default: GuardTier = BALANCED
    }
}

/**
 * 内容类别（语义标签）。
 *
 * 分类至少区分总任务说明要求的这几类。一条弹幕可有多个标签，因此判定结果是
 * "每个标签的最大置信度"，而不是单标签互斥。
 */
enum class GuardCategory {
    /** 露骨低俗 / 性骚扰式表达 */
    EXPLICIT_OR_HARASSMENT,

    /** 恶意攻击 / 引战 */
    HOSTILE_ATTACK,

    /** 明确事实型剧透：直接陈述事实本身 */
    SPOILER_EXPLICIT,

    /** 提前暗示：暗示、引导、提醒式剧透 */
    SPOILER_HINT,

    /** 正常猜测 */
    SPECULATION,

    /** 普通批评 / 吐槽 */
    CRITICISM,

    /** 其他正常内容 */
    NORMAL,
    ;

    /**
     * 是否为"内容维度不合适"类别（与剧情时间无关）。
     * 这两类在所有档位下都应当被屏蔽，不受揭晓时间影响。
     */
    val isContentViolation: Boolean
        get() = this == EXPLICIT_OR_HARASSMENT || this == HOSTILE_ATTACK

    /**
     * 是否为"剧透"类别（需要结合揭晓时间与当前播放位置判定）。
     */
    val isSpoiler: Boolean
        get() = this == SPOILER_EXPLICIT || this == SPOILER_HINT
}

/**
 * 弹幕透露剧情事实的严重程度。用于按档位设置不同的置信度阈值。
 */
enum class SpoilerSeverity {
    /** 重大事实（结局、关键身份、核心动机、主要角色死亡等） */
    MAJOR,

    /** 一般事实 */
    MINOR,
}
