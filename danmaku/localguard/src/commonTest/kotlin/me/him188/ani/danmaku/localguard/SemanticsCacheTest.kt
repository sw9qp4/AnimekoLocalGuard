/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.cache.AlignmentVersion
import me.him188.ani.danmaku.localguard.cache.AnalyzerVersion
import me.him188.ani.danmaku.localguard.cache.BoundedSemanticsCache
import me.him188.ani.danmaku.localguard.cache.DisplayDecisionCache
import me.him188.ani.danmaku.localguard.cache.FactRelationKey
import me.him188.ani.danmaku.localguard.cache.GenericClassificationKey
import me.him188.ani.danmaku.localguard.cache.KnowledgeVersion
import me.him188.ani.danmaku.localguard.cache.TextFingerprint
import me.him188.ani.danmaku.localguard.cache.ValidInterval
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardDecision
import me.him188.ani.danmaku.localguard.policy.GuardTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 缓存隔离测试。
 *
 * 对应总任务说明第 11 节：统计去重、语义计算复用、最终显示决定是三件事；
 * 缓存不得串作品、集数、时间窗口、档位和模型版本。
 */
class SemanticsCacheTest {

    private val analyzer = AnalyzerVersion(
        modelId = "test-encoder",
        modelVersion = "0.1",
        normalizationVersion = "nfkc-1",
        tokenizerVersion = "tok-1",
    )
    private val knowledge = KnowledgeVersion(contentVersion = "fixture-1", schemaVersion = 1)
    private val alignment = AlignmentVersion(workId = "W1", alignmentId = "const-0")

    private fun semantics(text: String) = DanmakuSemantics(
        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to 0.9),
        analyzerVersion = text,
    )

    // ---------- 指纹 ----------

    @Test
    fun fingerprintIsStableForSameText() {
        assertEquals(TextFingerprint.of("这是同一条弹幕"), TextFingerprint.of("这是同一条弹幕"))
    }

    @Test
    fun fingerprintCollapsesWhitespaceOnly() {
        assertEquals(
            TextFingerprint.of("a b"),
            TextFingerprint.of("a    b"),
            "连续空白应折叠，避免同一句话因排版不同被当成两条",
        )
    }

    @Test
    fun fingerprintDistinguishesNegation() {
        // 关键约束：不要把有不同否定词的文本归一化成同一键
        assertNotEquals(
            TextFingerprint.of("他是凶手"),
            TextFingerprint.of("他不是凶手"),
            "否定词必须改变指纹",
        )
    }

    @Test
    fun fingerprintDistinguishesSmallDifferences() {
        assertNotEquals(TextFingerprint.of("果然是他"), TextFingerprint.of("果然是他啊"))
        assertNotEquals(TextFingerprint.of("abc"), TextFingerprint.of("abd"))
    }

    @Test
    fun fingerprintIsCaseSensitiveByDefault() {
        assertNotEquals(TextFingerprint.of("Test"), TextFingerprint.of("test"))
        assertEquals(
            TextFingerprint.of("Test", caseSensitive = false),
            TextFingerprint.of("test", caseSensitive = false),
            "显式关闭大小写敏感时两者应相同",
        )
    }

    @Test
    fun fingerprintDoesNotContainOriginalText() {
        val t = "这是一条不应出现在键里的弹幕正文"
        val fp = TextFingerprint.of(t)
        assertTrue(!fp.contains(t), "指纹不得包含原文（用于去标识化存储）")
        assertEquals(16, fp.length, "固定 16 位十六进制")
    }

    // ---------- 通用分类缓存 ----------

    @Test
    fun genericCacheReusesAcrossWorksAndEpisodes() {
        val c = BoundedSemanticsCache()
        val key = GenericClassificationKey(TextFingerprint.of("普通吐槽"), analyzer)
        c.putGeneric(key, semantics("generic"))

        // 通用分类与作品/集数无关，因此同一键在任何作品都能命中
        val hit = c.getGeneric(GenericClassificationKey(TextFingerprint.of("普通吐槽"), analyzer))
        assertEquals("generic", hit?.analyzerVersion)
    }

    @Test
    fun genericCacheMissesWhenAnalyzerVersionChanges() {
        val c = BoundedSemanticsCache()
        c.putGeneric(GenericClassificationKey(TextFingerprint.of("普通吐槽"), analyzer), semantics("v1"))

        val newer = analyzer.copy(modelVersion = "0.2")
        assertNull(
            c.getGeneric(GenericClassificationKey(TextFingerprint.of("普通吐槽"), newer)),
            "模型版本变化必须导致缓存不命中",
        )
    }

    @Test
    fun genericCacheMissesWhenTokenizerOrNormalizationChanges() {
        for (changed in listOf(
            analyzer.copy(tokenizerVersion = "tok-2"),
            analyzer.copy(normalizationVersion = "nfkc-2"),
        )) {
            val c = BoundedSemanticsCache()
            c.putGeneric(GenericClassificationKey(TextFingerprint.of("x"), analyzer), semantics("v1"))
            assertNull(
                c.getGeneric(GenericClassificationKey(TextFingerprint.of("x"), changed)),
                "分词器/标准化版本变化必须导致缓存不命中",
            )
        }
    }

    // ---------- 事实关系缓存 ----------

    @Test
    fun factRelationCacheIsBoundToWorkAndEpisode() {
        val c = BoundedSemanticsCache()
        val fp = TextFingerprint.of("果然是他")
        val key = FactRelationKey(
            textFingerprint = fp, analyzer = analyzer, knowledge = knowledge,
            workId = "W1", episodeNumber = 2.0,
            semanticWindowStartMillis = 60_000, semanticWindowEndMillis = 120_000,
        )
        c.putFactRelation(key, semantics("fact"))

        // 换作品 → 不得命中
        assertNull(
            c.getFactRelation(key.copy(workId = "W2")),
            "事实关系不得跨作品复用",
        )
        // 换集 → 不得命中
        assertNull(
            c.getFactRelation(key.copy(episodeNumber = 3.0)),
            "事实关系不得跨集复用",
        )
        // 换语义窗口 → 不得命中
        assertNull(
            c.getFactRelation(key.copy(semanticWindowStartMillis = 200_000, semanticWindowEndMillis = 260_000)),
            "事实关系不得跨语义时间段复用",
        )
        // 换剧情包版本 → 不得命中
        assertNull(
            c.getFactRelation(key.copy(knowledge = knowledge.copy(contentVersion = "fixture-2"))),
            "剧情包版本变化必须导致缓存不命中",
        )
        // 原键仍命中
        assertEquals("fact", c.getFactRelation(key)?.analyzerVersion)
    }

    @Test
    fun factRelationKeyRejectsInvertedWindow() {
        val failed = try {
            FactRelationKey(
                textFingerprint = "x", analyzer = analyzer, knowledge = knowledge,
                workId = "W1", episodeNumber = 1.0,
                semanticWindowStartMillis = 100, semanticWindowEndMillis = 50,
            )
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(failed, "语义窗口起点晚于终点必须被拒绝")
    }

    // ---------- 有界性 ----------

    @Test
    fun cacheIsBoundedAndDoesNotGrowForever() {
        val c = BoundedSemanticsCache(maxEntries = 10)
        repeat(100) { i ->
            c.putGeneric(
                GenericClassificationKey(TextFingerprint.of("text-$i"), analyzer),
                semantics("s$i"),
            )
        }
        assertTrue(c.size <= 10, "缓存必须有界，长时间运行不能无限增长（实际 ${c.size}）")
    }

    @Test
    fun clearRemovesEverything() {
        val c = BoundedSemanticsCache()
        c.putGeneric(GenericClassificationKey(TextFingerprint.of("a"), analyzer), semantics("a"))
        c.putFactRelation(
            FactRelationKey("b", analyzer, knowledge, "W1", 1.0, 0, 1),
            semantics("b"),
        )
        assertTrue(c.size > 0)
        c.clear()
        assertEquals(0, c.size, "clear 后应为空")
    }

    // ---------- 显示决定缓存：有效区间与版本 ----------

    private val visible = GuardDecision.Visible

    @Test
    fun displayDecisionHitsOnlyInsideValidInterval() {
        val c = DisplayDecisionCache()
        c.put(
            workId = "W1", episodeNumber = 1.0, textFingerprint = "fp",
            decision = visible, interval = ValidInterval(300_000, 600_000),
            tier = GuardTier.BALANCED, knowledge = knowledge, alignment = alignment,
        )
        assertEquals(
            visible,
            c.lookup("W1", 1.0, "fp", 400_000, GuardTier.BALANCED, knowledge, alignment),
            "区间内应命中",
        )
        assertNull(
            c.lookup("W1", 1.0, "fp", 299_999, GuardTier.BALANCED, knowledge, alignment),
            "区间前不得命中（显示决定不能作为永久布尔值）",
        )
        assertNull(
            c.lookup("W1", 1.0, "fp", 600_001, GuardTier.BALANCED, knowledge, alignment),
            "区间后不得命中",
        )
    }

    @Test
    fun displayDecisionInvalidatedByTierChange() {
        val c = DisplayDecisionCache()
        c.put("W1", 1.0, "fp", visible, ValidInterval(0, 600_000), GuardTier.LENIENT, knowledge, alignment)
        assertNull(
            c.lookup("W1", 1.0, "fp", 100, GuardTier.STRICT, knowledge, alignment),
            "档位变化必须使显示决定失效（三档结论不同）",
        )
    }

    @Test
    fun displayDecisionInvalidatedByKnowledgeOrAlignmentChange() {
        val c = DisplayDecisionCache()
        c.put("W1", 1.0, "fp", visible, ValidInterval(0, 600_000), GuardTier.BALANCED, knowledge, alignment)
        assertNull(
            c.lookup("W1", 1.0, "fp", 100, GuardTier.BALANCED, knowledge.copy(contentVersion = "v2"), alignment),
            "剧情包版本变化必须使显示决定失效",
        )
        assertNull(
            c.lookup("W1", 1.0, "fp", 100, GuardTier.BALANCED, knowledge, alignment.copy(alignmentId = "seg-1")),
            "映射版本变化必须使显示决定失效",
        )
        assertNull(
            c.lookup("W1", 1.0, "fp", 100, GuardTier.BALANCED, knowledge, null),
            "映射被移除同样必须使显示决定失效",
        )
    }

    @Test
    fun displayDecisionIsBoundToWorkAndEpisode() {
        val c = DisplayDecisionCache()
        c.put("W1", 1.0, "fp", visible, ValidInterval(0, 600_000), GuardTier.BALANCED, knowledge, alignment)
        assertNull(
            c.lookup("W2", 1.0, "fp", 100, GuardTier.BALANCED, knowledge, alignment),
            "显示决定不得跨作品复用",
        )
        assertNull(
            c.lookup("W1", 2.0, "fp", 100, GuardTier.BALANCED, knowledge, alignment),
            "显示决定不得跨集复用",
        )
    }

    @Test
    fun displayDecisionCacheIsBounded() {
        val c = DisplayDecisionCache(maxEntries = 5)
        repeat(50) { i ->
            c.put("W1", 1.0, "fp-$i", visible, ValidInterval(0, 1_000), GuardTier.BALANCED, knowledge, alignment)
        }
        assertTrue(c.size <= 5, "显示决定缓存也必须有界（实际 ${c.size}）")
    }

    // ---------- 不确定输入不得被缓存成"安全" ----------

    @Test
    fun blockedDecisionCanBeCachedWithoutBecomingPermanent() {
        // 即使缓存了"屏蔽"，超出有效区间后也必须重算 —— 防止把"当前屏蔽"固化为永久规则
        val c = DisplayDecisionCache()
        val blocked = GuardDecision.BlockedSpoiler(
            category = GuardCategory.SPOILER_EXPLICIT,
            factId = "F1",
            score = 0.9,
            tier = GuardTier.BALANCED,
            reason = me.him188.ani.danmaku.localguard.policy.SpoilerBlockReason.FutureEpisode,
        )
        c.put("W1", 1.0, "fp", blocked, ValidInterval(0, 100_000), GuardTier.BALANCED, knowledge, alignment)
        assertEquals(
            blocked,
            c.lookup("W1", 1.0, "fp", 50_000, GuardTier.BALANCED, knowledge, alignment),
            "区间内可复用结论以减少重复计算",
        )
        assertNull(
            c.lookup("W1", 1.0, "fp", 500_000, GuardTier.BALANCED, knowledge, alignment),
            "区间外必须重算，不能把屏蔽固化成永久规则",
        )
    }
}
