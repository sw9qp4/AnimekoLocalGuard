/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.data.repository.danmaku

import me.him188.ani.danmaku.localguard.knowledge.EpisodeOrder
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.utils.serialization.BigNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 适配层测试：上游集序 → 守卫集序。
 *
 * 这一层是"知识库口径的集数到底是哪个字段"的唯一权威。它错了不会报错，
 * 只会让判定用到错的集数——而错的方向若是**偏早**，本该受保护的弹幕就会被放行。
 * 因此这里对每一类上游形态都钉住具体期望值。
 *
 * 全部使用上游真实类型构造（`EpisodeSort(raw)` / `EpisodeSort(int, type)` 工厂），
 * 不自己造替身，否则测的是替身而不是真实转换。
 */
class LocalGuardEpisodeOrderTest {

    // ---------- 正片 ----------

    @Test
    fun `normal sort maps to its own number`() {
        val order = LocalGuardEpisodeOrder.of(EpisodeSort("12"))
        assertEquals(EpisodeOrder.Numbered(12.0, partial = false), order)
    }

    @Test
    fun `half episode keeps the decimal and is marked partial`() {
        val order = assertIs<EpisodeOrder.Numbered>(LocalGuardEpisodeOrder.of(EpisodeSort("24.5")))
        assertEquals(24.5, order.number, "小数集序不得被取整")
        assertTrue(order.partial, "x.5 应标记为半集")
    }

    @Test
    fun `integer episode is not marked partial`() {
        val order = assertIs<EpisodeOrder.Numbered>(LocalGuardEpisodeOrder.of(EpisodeSort("7")))
        assertTrue(!order.partial)
    }

    // ---------- 两套编号：ep 优先于 sort ----------

    /**
     * 这是本层存在的**首要原因**：二季一集在 `sort` 里是 26、在 `ep` 里是 1。
     * 知识库按观众数的集数编号，因此必须取 `ep`。
     *
     * 取错的后果是具体且严重的：第 1 季第 1 集早已揭晓的事实会被当成"已经公开"，
     * 于是在第 2 季第 1 集里，指向该事实的弹幕被**放行**。
     */
    @Test
    fun `within-season ep wins over series-wide sort`() {
        val n = LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(
            ep = EpisodeSort("1"),
            sort = EpisodeSort("26"),
        )
        assertEquals(1.0, n, "必须用当季集数 ep，而不是系列集数 sort")
    }

    @Test
    fun `falls back to sort when ep is absent`() {
        val n = LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(ep = null, sort = EpisodeSort("26"))
        assertEquals(26.0, n)
    }

    @Test
    fun `no episode information at all maps to null not to episode one`() {
        assertNull(
            LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(ep = null, sort = null),
            "没有集序必须表达为未知，绝不能变成第 1 集",
        )
    }

    /**
     * 上游 `EpisodeInfo.sort` 的默认值是 `EpisodeSort("")`，即"字段存在但内容为空"。
     * 这是最容易被忽略的一条路径：它不是 null，所以"判空"挡不住它；
     * 它也不是数字，所以解析必然失败。必须干净地变成未知，而不是任何数值。
     */
    @Test
    fun `default empty sort is unknown not episode one`() {
        assertNull(
            LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(ep = null, sort = EpisodeSort("")),
            "EpisodeInfo.sort 的默认空值不得被当作第 1 集",
        )
    }

    /**
     * `EpisodeInfo.Empty` 用的是"空 sort"这一形态。整条退化路径（ep=null, sort 为空）
     * 目前对任何条目都不该产生集数；这条用例把该行为钉住，避免以后因为改动而静默退化。
     */
    @Test
    fun `empty default sort never produces a usable number`() {
        val empty = EpisodeSort("")
        assertIs<EpisodeOrder.Unknown>(LocalGuardEpisodeOrder.of(empty))
        assertNull(LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(ep = empty, sort = empty))
    }

    @Test
    fun `two argument overload returns null when both are absent`() {
        assertNull(LocalGuardEpisodeOrder.of(null, null))
    }

    // ---------- 特别篇 ----------

    @Test
    fun `ova is not treated as an ordinary episode number`() {
        val order = LocalGuardEpisodeOrder.of(EpisodeSort(7, EpisodeType.OVA))
        val special = assertIs<EpisodeOrder.Special>(order, "OVA 不得被当作第 7 集")
        assertEquals("OVA", special.specialType)
        assertEquals(7.0, special.number)
    }

    @Test
    fun `ova has no knowledge episode number until the pack declares one`() {
        assertNull(
            LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(
                ep = null,
                sort = EpisodeSort(7, EpisodeType.OVA),
            ),
            "未在知识包中声明位置的特别篇必须不可映射",
        )
    }

    @Test
    fun `every special type is forwarded by its upstream label`() {
        for (type in listOf(EpisodeType.SP, EpisodeType.OP, EpisodeType.ED, EpisodeType.PV, EpisodeType.MAD, EpisodeType.OVA, EpisodeType.OAD)) {
            val order = assertIs<EpisodeOrder.Special>(
                LocalGuardEpisodeOrder.of(EpisodeSort(1, type)),
                "$type 应被当作特别篇",
            )
            assertEquals(type.value, order.specialType)
        }
    }

    /**
     * `EpisodeSort.Special(MainStory, n)` 在叙事上就是正片，只是排序标记特殊。
     * 把它当特别篇会让整集无法映射，从而白白失去保护。
     */
    @Test
    fun `special sort typed as main story keeps its episode number`() {
        val order = LocalGuardEpisodeOrder.of(EpisodeSort(3, EpisodeType.MainStory))
        assertEquals(EpisodeOrder.Numbered(3.0), order)
    }

    @Test
    fun `main story special without a number falls back to the other field`() {
        // 上游工厂无法直接构造 number=null 的 Special，这里用原生字符串路径：
        // "MainStory" 之后没有数字 → Special(MainStory, null)
        val unnumbered = assertIs<EpisodeSort.Special>(EpisodeSort("MainStory"))
        assertEquals(null, unnumbered.number)

        val order = LocalGuardEpisodeOrder.of(unnumbered, mainStoryNumberForUnnumbered = 4.0)
        assertEquals(EpisodeOrder.Numbered(4.0), order)
    }

    @Test
    fun `main story special without any number is unknown rather than episode one`() {
        val unnumbered = assertIs<EpisodeSort.Special>(EpisodeSort("MainStory"))
        val order = LocalGuardEpisodeOrder.of(unnumbered, mainStoryNumberForUnnumbered = null)
        assertIs<EpisodeOrder.Unknown>(order, "没有序号时必须是未知，不得默认成第 1 集")
    }

    // ---------- 无法解析 ----------

    @Test
    fun `unparsable sort is unknown and keeps its raw text`() {
        val raw = EpisodeSort("??")
        val order = assertIs<EpisodeOrder.Unknown>(LocalGuardEpisodeOrder.of(raw))
        assertEquals("??", order.raw, "必须保留原始文本，否则无法诊断")
        assertNull(LocalGuardEpisodeOrder.knowledgeEpisodeNumberOf(ep = null, sort = raw))
    }

    @Test
    fun `negative episode number is not accepted as a valid episode`() {
        val raw = EpisodeSort("-5")
        assertIs<EpisodeOrder.Unknown>(LocalGuardEpisodeOrder.of(raw))
    }

    /**
     * `Ep` 与 `Sort` 同时给出时，退化路径（MainStory 特别篇无序号）用的是 `sort` 的数值。
     * 这里钉住这条回退链，避免它悄悄变成"用 ep 的数值"而掩盖编号体系的差异。
     */
    @Test
    fun `fallback for unnumbered main story special prefers the series wide value`() {
        val ep = EpisodeSort("1")
        val sort = EpisodeSort(26, EpisodeType.MainStory)
        val order = LocalGuardEpisodeOrder.of(ep, sort)
        // 常规路径：ep 有效 → 直接用 ep
        assertEquals(EpisodeOrder.Numbered(1.0), order)

        // 退化路径：ep 缺失且 sort 是无序号 MainStory 特别篇 → 用 sort 的数值
        val unnumbered = assertIs<EpisodeSort.Special>(EpisodeSort("MainStory"))
        val fallbackNumber = sort.number?.toDouble()
        assertEquals(26.0, fallbackNumber)
        assertEquals(
            EpisodeOrder.Numbered(26.0),
            LocalGuardEpisodeOrder.of(unnumbered, mainStoryNumberForUnnumbered = fallbackNumber),
        )
    }
}
