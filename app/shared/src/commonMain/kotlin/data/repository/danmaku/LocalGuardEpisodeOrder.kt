/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.data.repository.danmaku

import me.him188.ani.danmaku.localguard.knowledge.EpisodeMappingResult
import me.him188.ani.danmaku.localguard.knowledge.EpisodeOrder
import me.him188.ani.danmaku.localguard.knowledge.EpisodeOrderMapper
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType

/**
 * 上游剧集序号 → 守卫集序的**适配层**。
 *
 * 为什么需要这一层：守卫模块刻意不依赖上游类型（否则纯逻辑无法独立测试），
 * 它只认识 [EpisodeOrder]。而"上游的哪个字段是知识库口径的集数"这件事只有这里知道，
 * 因此转换必须发生在这里，而不是让守卫去猜。
 *
 * ## 这条转换为什么重要
 *
 * 知识库按**观众数的集数**编号。上游有两套编号：
 * - `ep` 是**当季内**的集数（"第二季的第一集为 01"），与知识库口径一致；
 * - `sort` 是**系列内**的集数（"第二季的第一集为 26"），跨季会整体偏移。
 *
 * 因此优先用 `ep`。这不是细节：用错会让第 2 季第 1 集去查知识库的第 1 集，
 * 于是第 1 季早已揭晓的事实被当成"已经公开"，本该拦住的弹幕被放行。
 *
 * 另外两类必须显式降级，不能靠数值掩盖：
 * - **特别篇**（OVA/SP/OP/ED…）不参与正片的数值比较，需要知识包显式声明位置；
 * - **无法解析的集序**绝不猜测，直接报告不可映射。
 */
object LocalGuardEpisodeOrder {

    /**
     * 把上游 [EpisodeSort] 投影为守卫的 [EpisodeOrder]。
     *
     * @param sort 上游集序；调用方应优先传入 `ep`（当季集数），无 `ep` 时传 `sort`
     * @param mainStoryNumberForUnnumbered 仅用于"被标为特别篇、但类别其实是正片、且没有序号"
     *   这一退化情形，提供一个可用的正片集数；调用方没有更好的值时传 null，
     *   此时结果为 [EpisodeOrder.Unknown]，即**不猜测**
     */
    fun of(
        sort: EpisodeSort,
        mainStoryNumberForUnnumbered: Double? = null,
    ): EpisodeOrder = when (sort) {
        is EpisodeSort.Normal -> EpisodeOrder.Numbered(
            number = sort.number.toDouble(),
            partial = sort.isPartial,
        )

        is EpisodeSort.Special -> {
            val type: EpisodeType = sort.type
            // 类别是 MainStory 时它在叙事上就是正片，只是拿了一个"特别"的排序标记。
            // 若连序号都没有，只能退回调用方给的数值；再没有就标为未知。
            if (type == EpisodeType.MainStory) {
                val number = sort.number?.toDouble() ?: mainStoryNumberForUnnumbered
                if (number == null) EpisodeOrder.Unknown(sort.toString()) else EpisodeOrder.Numbered(number)
            } else {
                EpisodeOrder.Special(
                    specialType = type.value,
                    number = sort.number?.toDouble(),
                )
            }
        }

        is EpisodeSort.Unknown -> EpisodeOrder.Unknown(sort.toString())
    }

    /**
     * 直接用 `ep` / `sort` 两个字段做投影，`ep` 缺省时回退到 `sort`。
     *
     * 之所以要一个"两个都可能为空"的入口：`EpisodeInfo.sort` 与 `EpisodeInfo.ep` 都可为 null，
     * 而"两个都没有"必须表达为**没有集序**，不能表达为第 1 集。
     */
    fun of(ep: EpisodeSort?, sort: EpisodeSort?): EpisodeOrder? {
        val available = ep ?: sort ?: return null
        val fallback = if (ep != null) sort?.number?.toDouble() else null
        return of(available, mainStoryNumberForUnnumbered = fallback)
    }

    /**
     * 适配层对外的主要用途：拿到知识库口径的集序。
     *
     * 特别篇与不可解析集序一律返回 null（= "本集不知道对应知识库的哪一集"）。
     * 会话层已把"集数未知"当作能力降级处理，而**不是**沿用上一集，
     * 所以这里返回 null 是安全且正确的降级方向。
     */
    fun knowledgeEpisodeNumberOf(
        ep: EpisodeSort?,
        sort: EpisodeSort?,
    ): Double? {
        val order = of(ep, sort) ?: return null
        return when (val mapped = EpisodeOrderMapper.toKnowledgeEpisodeNumber(order)) {
            is EpisodeMappingResult.Mapped -> mapped.episodeNumber
            is EpisodeMappingResult.SpecialNeedsExplicitMapping -> null
            is EpisodeMappingResult.Unmappable -> null
        }
    }
}
