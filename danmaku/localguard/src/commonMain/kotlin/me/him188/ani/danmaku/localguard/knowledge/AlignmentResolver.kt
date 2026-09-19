/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.knowledge

/**
 * 片源时间对齐：把"当前实际播放位置"映射到"基准剧情版本位置"。
 *
 * 与"弹幕来源时间 → 实际显示时间"的映射**分开维护**：
 * 后者由上游弹幕源的 shift 机制负责，本类只处理前者。
 *
 * 保守原则：
 * - 未对齐时返回 [BaselineMapping.mapped] = false，**绝不**当作偏移 0。
 * - 落在未映射区段时同样返回未映射，**不做线性插值**。
 * - 误差按**保守方向**计入：映射结果向后（更大）偏移，避免提前解锁。
 */
object AlignmentResolver {

    /**
     * @param actualMillis 当前实际播放位置（毫秒）
     * @return 映射结果；[BaselineMapping.baselineMillis] 为 null 表示无法映射
     */
    fun toBaseline(actualMillis: Long, alignment: TimeAlignment): BaselineMapping {
        require(actualMillis >= 0) { "actualMillis must be >= 0" }

        return when (alignment) {
            is TimeAlignment.Unaligned -> BaselineMapping(
                baselineMillis = null,
                mapped = false,
                note = "alignment-unverified",
            )

            is TimeAlignment.ConstantOffset -> BaselineMapping(
                // 误差按保守方向：取让基准时间更大的那一端
                baselineMillis = actualMillis + alignment.offsetMillis + alignment.errorMillis.coerceAtLeast(0),
                mapped = true,
                note = "constant-offset",
            )

            is TimeAlignment.Segmented -> mapSegmented(actualMillis, alignment)
        }
    }

    private fun mapSegmented(actualMillis: Long, alignment: TimeAlignment.Segmented): BaselineMapping {
        for (seg in alignment.segments) {
            if (actualMillis < seg.actualStartMillis || actualMillis > seg.actualEndMillis) continue

            val baseline = if (seg.actualDuration == 0L) {
                seg.baselineStartMillis
            } else {
                val ratio = (actualMillis - seg.actualStartMillis).toDouble() / seg.actualDuration.toDouble()
                val span = (seg.baselineDuration.toDouble() * ratio).toLong()
                seg.baselineStartMillis + span
            }
            return BaselineMapping(
                baselineMillis = baseline + alignment.errorMillis.coerceAtLeast(0),
                mapped = true,
                note = if (seg.preservesRate) "segment-1:1" else "segment-rescaled",
            )
        }
        // 落在分段之间的空隙：属于未映射区，不插值
        return BaselineMapping(
            baselineMillis = null,
            mapped = false,
            note = "unmapped-region",
        )
    }
}
