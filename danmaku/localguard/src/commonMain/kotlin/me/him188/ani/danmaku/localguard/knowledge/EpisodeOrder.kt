/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.knowledge

/**
 * 上游剧集序号的**纯逻辑投影**。
 *
 * 为什么需要它：上游 `EpisodeSort` 是 sealed class，有三种形态且排序规则特殊
 * （见 `datasource/api/src/commonMain/kotlin/EpisodeSort.kt`）：
 * - `Normal(number: Float)` —— 正片，**只有整数与 `.5` 小数**会被解析为 Normal
 * - `Special(type: EpisodeType, number: Float?)` —— OVA/SP/OP/ED 等，`number` 可空
 * - `Unknown(raw: String)` —— 无法解析
 * - 排序：**所有 Normal < 所有 Special < 所有 Unknown**
 *
 * 知识库需要的是"可比较的集序"。直接在 commonMain 引用上游类型会把本模块
 * 与 `datasource:api` 绑死，也让纯逻辑难以独立测试；因此这里定义一个
 * **等价的、无依赖的**投影类型，由适配层负责从上游类型转换过来。
 *
 * 关键约束（对应总任务说明第 7.A / 8.4 条）：
 * - **不得把小数集序强制取整**。
 * - 特别篇必须按**显式顺序**处理，不得参与数值比较。
 * - 无法解析的集序**不得猜测**，必须显式标记为未知。
 */
sealed interface EpisodeOrder {

    /**
     * 正片集序。允许小数（上游实际只保证整数与 `.5`，但本模型不因此限制）。
     */
    data class Numbered(
        val number: Double,
        /**
         * 上游 `EpisodeSort.Normal.isPartial` 的对应语义：形如 `x.5` 的半集。
         * 保留它是因为"半集"在剧本结构上常有特殊含义，不应被抹平。
         */
        val partial: Boolean = false,
    ) : EpisodeOrder

    /**
     * 特别篇。按显式顺序处理，**不参与数值比较**。
     *
     * @param specialType 类别标签，例如上游 `EpisodeType` 的 `SP` / `OVA` / `OP` / `ED`
     * @param number 类别内序号，可为 null
     * @param explicitOrder 在剧集序列中的**显式位置**；null 表示未给出
     */
    data class Special(
        val specialType: String,
        val number: Double? = null,
        val explicitOrder: Int? = null,
    ) : EpisodeOrder

    /** 集序无法解析。**不得猜测**，按未知处理。 */
    data class Unknown(val raw: String) : EpisodeOrder

    companion object {
        /**
         * 从"原始字符串 + 是否为特别篇"构造。
         *
         * 这是适配层能用到的最小信息量：上游 `EpisodeInfo` 的 `ep` / `sort`
         * 最终都能落到"一个数值或一个类别"。
         *
         * @return 解析结果；无法解析返回 [Unknown]
         */
        fun parse(raw: String, specialType: String? = null): EpisodeOrder {
            val trimmed = raw.trim()

            // 特别篇：类别已知时，序号可以缺失（例如只有 "SP" 没有编号）
            if (specialType != null) {
                val n = if (trimmed.isEmpty()) null else trimmed.toDoubleOrNull()
                return Special(specialType = specialType, number = n)
            }

            if (trimmed.isEmpty()) return Unknown(raw)

            val value = trimmed.toDoubleOrNull() ?: return Unknown(raw)
            if (value < 0) return Unknown(raw)
            val isPartial = (value * 2.0) % 2.0 == 1.0
            return Numbered(value, partial = isPartial)
        }
    }
}

/**
 * 比较两个集序在**叙事顺序**上的先后。
 *
 * 与上游 `EpisodeSort.compareTo` 保持一致的语义：
 * **所有正片 < 所有特别篇 < 所有未知**。
 *
 * 之所以不让特别篇参与数值比较：特别篇在时间线上常插在正片之间，
 * 用"第几集"的数值去比较会得出错误结论（总任务说明第 8.4 条要求
 * 跨集先按显式剧集顺序判断）。
 */
fun compareEpisodeOrder(a: EpisodeOrder, b: EpisodeOrder): Int {
    val rank = { o: EpisodeOrder -> when (o) {
        is EpisodeOrder.Numbered -> 0
        is EpisodeOrder.Special -> 1
        is EpisodeOrder.Unknown -> 2
    } }
    val ra = rank(a)
    val rb = rank(b)
    if (ra != rb) return ra.compareTo(rb)

    return when {
        a is EpisodeOrder.Numbered && b is EpisodeOrder.Numbered -> a.number.compareTo(b.number)
        a is EpisodeOrder.Special && b is EpisodeOrder.Special -> {
            // 先按类别名，再按显式顺序，最后按类别内序号
            val byType = a.specialType.compareTo(b.specialType)
            if (byType != 0) return byType
            val ao = a.explicitOrder
            val bo = b.explicitOrder
            if (ao != null && bo != null && ao != bo) return ao.compareTo(bo)
            when {
                a.number == null && b.number == null -> 0
                a.number == null -> -1
                b.number == null -> 1
                else -> a.number.compareTo(b.number)
            }
        }
        else -> 0 // 两个 Unknown：无法比较，视为相等（调用方必须按"未知"处理）
    }
}

/**
 * 当前播放位置 → 知识库集序的映射结果。
 */
sealed interface EpisodeMappingResult {
    /**
     * 成功映射到知识库的集序。
     *
     * @param episodeNumber 知识库使用的集序（**保留小数，不取整**）
     */
    data class Mapped(val episodeNumber: Double) : EpisodeMappingResult

    /**
     * 无法映射到数值集序。调用方**必须**按降级策略处理，
     * **不得**用默认值假装映射成功。
     *
     * @param reason 原因，用于诊断
     */
    data class Unmappable(val reason: String) : EpisodeMappingResult

    /**
     * 特别篇：知识库以数值集序表达剧集，特别篇需要显式顺序映射。
     */
    data class SpecialNeedsExplicitMapping(
        val specialType: String,
        val number: Double?,
        val explicitOrder: Int?,
    ) : EpisodeMappingResult
}

/**
 * 把上游剧集序投影为知识库集序。
 *
 * 保守规则：
 * - 正片 → 直接使用其数值（保留小数）
 * - 特别篇 → 需要显式顺序映射；若知识包未提供映射，则返回
 *   [EpisodeMappingResult.SpecialNeedsExplicitMapping]，由调用方决定降级方式
 * - 未知 → [EpisodeMappingResult.Unmappable]，**绝不猜测**
 */
object EpisodeOrderMapper {

    fun toKnowledgeEpisodeNumber(order: EpisodeOrder): EpisodeMappingResult = when (order) {
        is EpisodeOrder.Numbered -> EpisodeMappingResult.Mapped(order.number)
        is EpisodeOrder.Special -> EpisodeMappingResult.SpecialNeedsExplicitMapping(
            specialType = order.specialType,
            number = order.number,
            explicitOrder = order.explicitOrder,
        )
        is EpisodeOrder.Unknown -> EpisodeMappingResult.Unmappable("unparsable episode order: ${order.raw}")
    }

    /**
     * 用知识包中声明的特别篇映射表，把特别篇解析为数值集序。
     *
     * @return 映射结果；知识包未覆盖该特别篇时返回 [EpisodeMappingResult.Unmappable]，
     *   **不猜测**位置。
     */
    fun resolveSpecial(
        order: EpisodeOrder.Special,
        specials: List<SpecialEpisodeMapping>,
    ): EpisodeMappingResult {
        val match = specials.firstOrNull { it.specialId == order.specialType }
            ?: return EpisodeMappingResult.Unmappable(
                "special type '${order.specialType}' not declared in knowledge pack",
            )
        return EpisodeMappingResult.Mapped(match.episodeNumber)
    }
}
