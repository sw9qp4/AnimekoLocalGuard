/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.cache

import me.him188.ani.danmaku.localguard.policy.DanmakuGuardSession
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardDecision
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.TierPolicy

/**
 * 缓存分层。
 *
 * 总任务说明第 11 节把三件事明确分开：
 * 1. **统计去重** —— 不重复计数；
 * 2. **语义计算复用** —— 可以跨条目复用"这条弹幕说了什么"；
 * 3. **最终显示决定** —— 依赖当前进度、来源映射、档位与资料版本，**不能**作为永久布尔值缓存。
 *
 * 本文件只实现第 2 件（语义缓存）与它的键设计；第 3 件由 [DanmakuGuardSession] 在显示前重新计算。
 *
 * ### 缓存分层规则
 *
 * | 层 | 内容 | 复用范围 |
 * |---|---|---|
 * | 通用分类 | 低俗/攻击等与剧情无关的类别分数 | 相同原文 + 相同预处理 + 相同模型版本即可复用（跨作品） |
 * | 事实关系 | "果然是他"这类依赖场景的判断 | **必须绑定作品、剧集与必要语义时间段**，不得跨作品复用 |
 *
 * **最终显示决定不入缓存**：用有效区间或显示前重新计算。
 */

/** 分析器版本。模型 / 分词器 / 文本标准化任一变化都必须改变它。 */
data class AnalyzerVersion(
    val modelId: String,
    val modelVersion: String,
    /** 文本预处理 / 标准化版本 */
    val normalizationVersion: String,
    /** 分词器版本 */
    val tokenizerVersion: String,
) {
    val asKeyPart: String get() = "$modelId|$modelVersion|$normalizationVersion|$tokenizerVersion"
}

/** 剧情包版本。 */
data class KnowledgeVersion(
    val contentVersion: String,
    val schemaVersion: Int,
) {
    val asKeyPart: String get() = "$contentVersion|$schemaVersion"
}

/** 片源映射版本。对齐结果一变，依赖时间的判断就要重算。 */
data class AlignmentVersion(
    val workId: String,
    val alignmentId: String,
) {
    val asKeyPart: String get() = "$workId|$alignmentId"
}

/**
 * 通用分类缓存键。
 *
 * 按总任务说明：键中按实际需要包含原文指纹、模型/分词器/标准化版本。
 * **不含**作品/集数/档位/进度 —— 因为通用分类与剧情和时间无关。
 */
data class GenericClassificationKey(
    /** 原文指纹（去标识化；不存原文本身） */
    val textFingerprint: String,
    val analyzer: AnalyzerVersion,
) {
    val asKeyPart: String get() = "$textFingerprint|${analyzer.asKeyPart}"
}

/**
 * 事实关系缓存键。
 *
 * 与通用分类相反，这里**必须**绑定作品、剧集与语义时间段：
 * "果然是他"这类判断离开具体场景就没有意义。
 *
 * @param semanticWindowStartMillis 语义窗口起点（弹幕所属时间段）
 * @param semanticWindowEndMillis 语义窗口终点
 */
data class FactRelationKey(
    val textFingerprint: String,
    val analyzer: AnalyzerVersion,
    val knowledge: KnowledgeVersion,
    /** 作品标识 —— 不得跨作品复用 */
    val workId: String,
    /** 剧集序号 —— 不得跨集复用 */
    val episodeNumber: Double,
    val semanticWindowStartMillis: Long,
    val semanticWindowEndMillis: Long,
) {
    init {
        require(semanticWindowStartMillis <= semanticWindowEndMillis) {
            "semantic window start must be <= end"
        }
    }

    val asKeyPart: String
        get() = "$textFingerprint|${analyzer.asKeyPart}|${knowledge.asKeyPart}|$workId|$episodeNumber|" +
            "$semanticWindowStartMillis-$semanticWindowEndMillis"
}

/**
 * 文本指纹器。
 *
 * 关键约束（总任务说明第 11 节）：**不要把有不同否定词的文本归一化成同一键**。
 * 因此这里不做任何"语义归一化"——只做空白折叠与大小写处理的可选开关，
 * 而否定词会改变字符序列，从而改变指纹。
 */
object TextFingerprint {

    /**
     * 计算指纹。
     *
     * 刻意保留全部非空白字符（含否定词、标点），只折叠连续空白。
     */
    fun of(text: String, caseSensitive: Boolean = true): String {
        val collapsed = buildString(text.length) {
            var lastWasSpace = false
            for (ch in text) {
                if (ch.isWhitespace()) {
                    if (!lastWasSpace) {
                        append(' ')
                        lastWasSpace = true
                    }
                } else {
                    append(if (caseSensitive) ch else ch.lowercaseChar())
                    lastWasSpace = false
                }
            }
        }.trim()
        return stableHash(collapsed)
    }

    /** 不依赖平台实现的稳定哈希（FNV-1a 64 位）。 */
    private fun stableHash(s: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis 14695981039346656037
        for (b in s.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

/** 语义缓存的条目。 */
data class CachedSemantics(
    val semantics: DanmakuSemantics,
    /** 写入时的分析器版本，命中时需要校验一致 */
    val analyzer: AnalyzerVersion,
)

/**
 * 语义缓存接口。实现需保证线程安全。
 */
interface SemanticsCache {
    /** 通用分类：可跨作品复用。 */
    fun getGeneric(key: GenericClassificationKey): DanmakuSemantics?

    fun putGeneric(key: GenericClassificationKey, value: DanmakuSemantics)

    /** 事实关系：绑定作品/集数/语义窗口。 */
    fun getFactRelation(key: FactRelationKey): DanmakuSemantics?

    fun putFactRelation(key: FactRelationKey, value: DanmakuSemantics)

    /**
     * 只清空事实关系层。
     *
     * 切集/换源/修正对齐时调用：那些结论绑定作品与集数，必须重算；
     * 而通用分类层（与剧情无关）可以跨集复用，不必一起丢弃。
     * 需要全部丢弃时用 [clear]。
     */
    fun evictFactRelations()

    fun clear()

    val size: Int
}

/**
 * 有容量上限的内存实现。
 *
 * 约束（总任务说明第 12 节）：支持取消、长时间运行不能无限增长。
 * 这里用简单的插入顺序淘汰（不需要 LRU 的精确性，但必须**有界**）。
 */
class BoundedSemanticsCache(
    private val maxEntries: Int = 4096,
) : SemanticsCache {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val lock = Any()
    private val generic = LinkedHashMap<String, DanmakuSemantics>()
    private val factRelation = LinkedHashMap<String, DanmakuSemantics>()

    override fun getGeneric(key: GenericClassificationKey): DanmakuSemantics? =
        synchronized(lock) { generic[key.asKeyPart] }

    override fun putGeneric(key: GenericClassificationKey, value: DanmakuSemantics) {
        synchronized(lock) {
            putBounded(generic, key.asKeyPart, value)
        }
    }

    override fun getFactRelation(key: FactRelationKey): DanmakuSemantics? =
        synchronized(lock) { factRelation[key.asKeyPart] }

    override fun putFactRelation(key: FactRelationKey, value: DanmakuSemantics) {
        synchronized(lock) {
            putBounded(factRelation, key.asKeyPart, value)
        }
    }

    private fun putBounded(map: LinkedHashMap<String, DanmakuSemantics>, key: String, value: DanmakuSemantics) {
        map.remove(key)
        map[key] = value
        while (map.size > maxEntries) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }

    override fun evictFactRelations() = synchronized(lock) {
        factRelation.clear()
    }

    override fun clear() = synchronized(lock) {
        generic.clear()
        factRelation.clear()
    }

    override val size: Int get() = synchronized(lock) { generic.size + factRelation.size }
}

/**
 * 显示决定缓存。
 *
 * **本类刻意不提供"永久布尔值"缓存** —— 总任务说明明确要求最终显示决定
 * 必须依赖当前进度、来源映射、档位与资料版本，因此只能：
 * 1. 用有效区间（[ValidInterval]）表达"在一段时间内结论不变"；或
 * 2. 在显示前重新计算。
 *
 * 这里实现"有效区间"形态：条目在 [ValidInterval] 内可直接复用决定，
 * 超出区间必须重新计算。任何影响结论的输入变化（档位、资料、映射、模型）
 * 都会使区间失效。
 *
 * ## 当前是否接线（重要，避免被误读）
 *
 * **当前生产路径不使用本类**：`DanmakuGuardSession.shouldDisplay` 每次都重新计算显示决定，
 * 也就是上面第 2 条。原因有两点，都是刻意的：
 *
 * 1. 重新计算本身已经很便宜——昂贵的那一步（语义分析）已由 [SemanticsCache] 复用，
 *    剩下的只是一次纯算术的时间线比较。
 * 2. `BlockedSpoiler` 类结论依赖播放位置。若有效区间取得过宽，就会在区间内
 *    复用一个已经不成立的结论，这正是"不能作为永久布尔值缓存"要防的事；
 *    而把区间取窄到安全，收益又接近于零。
 *
 * 因此本类目前的作用是：把"允许用有效区间"这一设计固定下来并**有测试覆盖**，
 * 供将来需要时（例如判定成本真的变高、或需要跨会话复用）直接启用。
 * 它**不是**死代码，但也**不是**运行中的加速路径；本项目不把它计入已有性能收益。
 */
data class CachedDisplayDecision(
    val decision: GuardDecision,
    val validFromMillis: Long,
    val validUntilMillis: Long,
    val tier: GuardTier,
    val knowledge: KnowledgeVersion?,
    val alignment: AlignmentVersion?,
)

/** 有效区间。 */
data class ValidInterval(val fromMillis: Long, val untilMillis: Long) {
    init {
        require(fromMillis <= untilMillis) { "interval start must be <= end" }
    }

    fun contains(positionMillis: Long): Boolean = positionMillis in fromMillis..untilMillis
}

/**
 * 显示决定缓存：按 (条目指纹, 作品, 集数) 存有效区间，并在命中时校验区间与版本。
 *
 * 命中条件必须**全部**满足：
 * 1. 当前位置落在 [CachedDisplayDecision.validFromMillis]..[validUntilMillis]；
 * 2. 档位与写入时一致；
 * 3. 剧情包版本与写入时一致；
 * 4. 映射版本与写入时一致。
 *
 * 任一不满足 → 视为未命中，调用方必须重新计算。
 */
class DisplayDecisionCache(
    private val maxEntries: Int = 2048,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, CachedDisplayDecision>()

    private fun key(workId: String, episodeNumber: Double, textFingerprint: String) =
        "$workId|$episodeNumber|$textFingerprint"

    /**
     * 查询可复用的决定。
     *
     * @return 命中则返回决定；未命中返回 null（调用方必须重新计算）
     */
    fun lookup(
        workId: String,
        episodeNumber: Double,
        textFingerprint: String,
        positionMillis: Long,
        tier: GuardTier,
        knowledge: KnowledgeVersion?,
        alignment: AlignmentVersion?,
    ): GuardDecision? = synchronized(lock) {
        val e = entries[key(workId, episodeNumber, textFingerprint)] ?: return null
        if (!ValidInterval(e.validFromMillis, e.validUntilMillis).contains(positionMillis)) return null
        if (e.tier != tier) return null
        if (e.knowledge?.asKeyPart != knowledge?.asKeyPart) return null
        if (e.alignment?.asKeyPart != alignment?.asKeyPart) return null
        e.decision
    }

    /** 写入一个在给定区间内有效的决定。 */
    fun put(
        workId: String,
        episodeNumber: Double,
        textFingerprint: String,
        decision: GuardDecision,
        interval: ValidInterval,
        tier: GuardTier,
        knowledge: KnowledgeVersion?,
        alignment: AlignmentVersion?,
    ) {
        synchronized(lock) {
            val k = key(workId, episodeNumber, textFingerprint)
            entries.remove(k)
            entries[k] = CachedDisplayDecision(
                decision = decision,
                validFromMillis = interval.fromMillis,
                validUntilMillis = interval.untilMillis,
                tier = tier,
                knowledge = knowledge,
                alignment = alignment,
            )
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    /** 版本变化时清空（切剧集、换番、修正对齐、更新模型或剧情包）。 */
    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int get() = synchronized(lock) { entries.size }
}

/**
 * 分层规则（由 `DanmakuGuardSession.resolveSemantics` 执行）：
 *
 * 一条语义结论进哪一层，由**该结论是否依赖剧情事实**决定，两层互斥：
 *
 * - `factIds` 为空 → 结论只由原文与类别分数决定，与作品/集数/时间无关 → [GenericClassificationKey]，可跨作品复用。
 * - `factIds` 非空 → 必须绑定作品、集数、资料版本与语义窗口 → [FactRelationKey]；
 *   此时若键不完整（缺作品/集数/版本），**宁可不缓存**，也不能降级塞进通用层。
 *
 * 这里刻意**不提供**"去掉剧透分数后入通用层"的工具函数：那样做会把一部作品的剧透结论
 * 变成另一部作品可复用的条目，正是第 11 节禁止的跨作品复用。
 */
