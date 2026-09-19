/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.policy.GuardCounters
import me.him188.ani.danmaku.localguard.policy.GuardFeatureState
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import me.him188.ani.danmaku.localguard.policy.deriveGuardStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardStatusTest {
    private val counters = GuardCounters(
        evaluated = 10,
        visible = 7,
        blockedContent = 1,
        blockedSpoiler = 2,
        bypassedOff = 0,
        bypassedNoKnowledge = 1,
        deferredTimeout = 0,
        failed = 1,
    )

    @Test
    fun `disabled is OFF regardless of capabilities`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = false, tier = GuardTier.STRICT),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = true,
        )
        assertEquals(GuardFeatureState.OFF, status.state)
        assertFalse(status.enabled)
        assertEquals("本地过滤：已关闭", status.displayLine())
    }

    @Test
    fun `enabled without knowledge reports missing knowledge not enabled`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.BALANCED),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = false,
            alignmentVerified = false,
            semanticsReady = false,
        )
        assertEquals(GuardFeatureState.KNOWLEDGE_MISSING, status.state)
        // 关键：不得把"开关打开"显示为"防剧透已启用"
        assertFalse(status.displayLine().contains("已启用"))
        assertTrue(status.displayLine().contains("资料缺失"))
    }

    @Test
    fun `knowledge but unverified alignment is reported as alignment unverified`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.BALANCED),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = false,
            semanticsReady = false,
        )
        assertEquals(GuardFeatureState.ALIGNMENT_UNVERIFIED, status.state)
        assertTrue(status.displayLine().contains("对齐未验证"))
    }

    @Test
    fun `no semantics reports rule prototype`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.BALANCED),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = false,
        )
        assertEquals(GuardFeatureState.RULE_PROTOTYPE, status.state)
        assertTrue(status.displayLine().contains("未接入模型"))
    }

    @Test
    fun `all capabilities present reports timeline verified`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.BALANCED),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = true,
        )
        assertEquals(GuardFeatureState.TIMELINE_VERIFIED, status.state)
        val line = status.displayLine()
        assertTrue(line.contains("均衡"))
        assertFalse(line.contains("降级"))
    }

    @Test
    fun `model failure takes priority over other capabilities`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.BALANCED),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = true,
            modelFailed = true,
        )
        assertEquals(GuardFeatureState.MODEL_FAILURE, status.state)
    }

    @Test
    fun `unknown episode is surfaced as degradation without blocking claims`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.STRICT),
            counters = counters,
            episodeKnown = false,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = true,
        )
        // 集序未知本身不改状态（知识可能仍在），但必须显式暴露
        assertFalse(status.episodeKnown)
        assertTrue(status.displayLine().contains("集序未知"))
    }

    @Test
    fun `diagnostics never contain danmaku text or story facts`() {
        val status = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = GuardTier.STRICT),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = true,
        )
        val lines = status.diagnosticsLines()
        // 只允许 "key=value" 形式：值必须是布尔/枚举名/整数，
        // 否则说明有自由文本（弹幕正文、剧情事实）混进了面向界面的结构。
        val kv = Regex("^[A-Za-z][A-Za-z0-9]*=(true|false|[0-9]+|[A-Z_]+)$")
        val malformed = lines.filterNot { kv.matches(it) }
        assertTrue(malformed.isEmpty(), "unexpected free-form diagnostics: $malformed")
        // 计数必须完整暴露，且 total 必须等于各分类之和（total 本身是计算属性）。
        assertTrue(lines.any { it == "blockedSpoiler=2" })
        assertTrue(lines.any { it == "total=${counters.total}" })
        assertEquals(12, counters.total)
        assertTrue(lines.any { it.startsWith("enabled=") })
        assertTrue(lines.any { it.startsWith("tier=") })
        assertTrue(lines.any { it.startsWith("state=") })
    }

    @Test
    fun `display line differs per tier so user can tell which policy is active`() {
        fun line(tier: GuardTier) = deriveGuardStatus(
            config = GuardUserConfig(enabled = true, tier = tier),
            counters = counters,
            episodeKnown = true,
            knowledgeLoaded = true,
            alignmentVerified = true,
            semanticsReady = true,
        ).displayLine()
        assertTrue(line(GuardTier.LENIENT).contains("宽松"))
        assertTrue(line(GuardTier.BALANCED).contains("均衡"))
        assertTrue(line(GuardTier.STRICT).contains("严格"))
    }
}
