/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.knowledge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.him188.ani.danmaku.localguard.policy.SpoilerSeverity

/**
 * 剧情知识库模型。
 *
 * 设计原则（来自总任务说明第七节）：
 * - 身份、动机、关系和结局**分别建事实**。出现伏笔不代表答案已公开；身份公开不代表动机公开。
 * - 时间表示"观众按叙事首次得到足够信息的位置"，**不是故事内部发生时间**。
 * - 事实核对状态必须区分；结构校验通过 ≠ 剧情真实或时间正确。
 * - 未核对项不得作为高可信依据自动解锁。
 * - 时间单位统一为**毫秒**，区间边界含义必须写清。
 *
 * ## 序列化与版本兼容
 *
 * 这些类型是**剧情包的磁盘格式**，因此全部可序列化，JSON 解析忽略未知字段
 * （便于新版本写入的包被旧版本安全读取）。变更字段时必须同时考虑旧包仍能被读出；
 * **不要重命名或删除已有字段名**，否则旧包会解析失败。格式版本见
 * [StoryKnowledgePack.schemaVersion]。
 */

/** 事实的核对状态。 */
@Serializable
enum class VerificationState {
    /** AI 生成候选，尚无人工或来源核对 */
    AI_CANDIDATE,

    /** 有来源但未核对 */
    SOURCED_UNVERIFIED,

    /** 已核对 */
    VERIFIED,

    /** 存在争议 */
    DISPUTED,

    /** 未知 */
    UNKNOWN,
    ;

    /**
     * 是否可作为"高可信依据"用于自动解锁内容。
     *
     * 只有 [VERIFIED] 才允许。其余状态（含 [DISPUTED]、[UNKNOWN]、
     * [AI_CANDIDATE]、[SOURCED_UNVERIFIED]）都**不得**自动解锁。
     */
    val trustedForUnlock: Boolean get() = this == VERIFIED
}

/** 证据引用：必须可定位。仅有整集简介不足以推导分钟级时间。 */
@Serializable
data class EvidenceRef(
    /** 证据类型，例如 "subtitle" / "video" / "staff-note" / "episode-summary" */
    val kind: String,
    /** 可定位的位置描述，例如文件名 + 时间码区间 */
    val locator: String,
    /** 该证据能支撑到什么精度；用于拒绝"用简介推分钟"这类越界引用 */
    val precision: String? = null,
)

/**
 * 揭晓边界：某条事实"对观众公开"的时间范围。
 *
 * 保守原则：**使用完整揭晓时间的上界**。只能确定"第 12 至 13 分钟"时，
 * 上界取 13 分钟（区间终点），不编造精确秒数。
 *
 * 跨集时**不允许**直接比较不同集的毫秒数，必须先按显式剧集顺序判断。
 */
@Serializable
data class RevealBoundary(
    /** 事实完整揭晓所在集（条目内集序）。允许小数集序（如 7.5 表示特别篇插在 7 与 8 之间）。 */
    val episodeNumber: Double,    /**
     * 该集内"完整揭晓"的最早时间（毫秒，相对该集视频起点）。null 表示未知。
     * 未知时不得凭空设成无限远后宣称已查明。
     */
    val earliestMillis: Long?,
    /**
     * 该集内"完整揭晓"的最晚时间（毫秒）。判定使用此上界。
     * null 表示未知。
     */
    val latestMillis: Long?,
    /** 标注精度，例如 "minute" / "second" / "episode-only" */
    val precision: String,
    /** 证据位置 */
    val evidence: List<EvidenceRef> = emptyList(),
    /** 核对状态 */
    val verification: VerificationState = VerificationState.UNKNOWN,
) {
    // 注意：区间合法性（earliestMillis <= latestMillis）**刻意不在此处 require**。
    // 原因：非法数据应当能被构造出来，由 KnowledgePackValidator 报出 REVEAL_TIME_INVERTED，
    // 从而在"导入/校验"阶段形成可见的错误报告，而不是在某个随机构造点抛出难以定位的异常。
    // 这符合设计约定：结构校验与证据审核分开报告，校验器负责发现问题。
    // 非负性属于数据类型本身的约束，仍在构造时阻止。

    init {
        require(earliestMillis == null || earliestMillis >= 0) { "earliestMillis must be >= 0" }
        require(latestMillis == null || latestMillis >= 0) { "latestMillis must be >= 0" }
    }

    /** 时间是否可用（两个边界都已知）。 */
    val hasTiming: Boolean get() = earliestMillis != null && latestMillis != null

    /**
     * 判定时使用的保守上界。时间未知时返回 null —— 调用方必须按"未知信息策略"处理，
     * **不得**用任何默认值假装已知。
     */
    val conservativeUpperBoundMillis: Long? get() = latestMillis
}

/**
 * 剧情事实。
 *
 * 细粒度原则：身份、动机、关系、结局分别建 factId，不合并成一条"大事实"。
 */
/**
 * 剧情事实。
 *
 * 细粒度原则：身份、动机、关系、结局分别建 factId，不合并成一条"大事实"。
 */
@Serializable
data class StoryFact(
    /** 稳定唯一标识 */
    val factId: String,
    /** 细粒度命题。例如"X 的真实身份是 Y" */
    val proposition: String,
    /** 涉及实体 id 列表（人物、组织等），用于检索与关系判断 */
    val entityIds: List<String> = emptyList(),
    /** 严重程度，用于按档位设置阈值 */
    val severity: SpoilerSeverity = SpoilerSeverity.MAJOR,
    /** 揭晓边界 */
    val reveal: RevealBoundary,
    /** 证据 */
    val evidence: List<EvidenceRef> = emptyList(),
    /** 来源标识（可追溯） */
    val sourceId: String? = null,
    /** 核对状态 */
    val verification: VerificationState = VerificationState.UNKNOWN,
    /**
     * 是否"覆盖范围内永不解锁"（有证据证明原作尚未动画化等）。
     * 标记为 true 时，本事实在覆盖范围内始终受保护。
     */
    val neverUnlocksInScope: Boolean = false,
)

/**
 * 实体（人物、组织等）及其别名。
 *
 * 约束：不能只匹配"老师"就强行认定是某个人 —— 因此 [ambiguousAliases] 单独列出
 * 那些不足以单独确定实体的称呼。
 */
@Serializable
data class StoryEntity(
    val entityId: String,
    val canonicalName: String,
    /** 足以定位该实体的别名 */
    val aliases: List<String> = emptyList(),
    /** 不足以单独确定该实体的称呼（歧义），不得据此成立事实关系 */
    val ambiguousAliases: List<String> = emptyList(),
    /** 与其他实体的关系描述（仅记录，判定不依赖） */
    val relations: List<String> = emptyList(),
)

/** 特别篇类别与显式顺序映射。 */
@Serializable
data class SpecialEpisodeMapping(
    /** 特别篇标识 */
    val specialId: String,
    /** 显示标签，例如 "OVA" / "特别篇" */
    val label: String,
    /** 在条目内的显式位置（小数集序），例如 7.5 表示插在 7 与 8 之间 */
    val episodeNumber: Double,
    /** 是否必须观看（影响剧集顺序判定） */
    val mandatory: Boolean = true,
)

/**
 * 剧集条目。
 *
 * 明确禁止：不得把数据库 ID 当集数；不得把小数集序强制取整。
 */
@Serializable
data class StoryEpisode(
    /** 条目内集序，允许小数（特别篇插值） */
    val episodeNumber: Double,
    /** 稳定剧集标识（可为 null，未知就留空，不伪造） */
    val stableId: String? = null,
    /** 显示标签，例如 "第 3 话" */
    val displayLabel: String? = null,
    /** 系列集序（跨季连续编号），可为 null */
    val seriesEpisodeNumber: Int? = null,
)

/** 作品覆盖范围。 */
@Serializable
data class StoryWork(
    /** 稳定作品标识 */
    val workId: String,
    /** 标题 */
    val title: String,
    /** 季度 / 改编版本标识，例如 "S1" / "S2" */
    val seasonLabel: String? = null,
    /** 本包**明确覆盖**的集序范围（含两端） */
    @Serializable(with = ClosedFloatingPointRangeSerializer::class)
    val coveredEpisodes: ClosedFloatingPointRange<Double>,
    /** 剧集列表（显式顺序以此为准） */
    val episodes: List<StoryEpisode> = emptyList(),
    /** 特别篇映射 */
    val specials: List<SpecialEpisodeMapping> = emptyList(),
    /** 系列内季序（用于跨季判断） */
    val seasonIndex: Int = 1,
)

/**
 * 视频版本对齐。
 *
 * **两个时间映射必须分开维护**（总任务说明第 8.6 条）：
 * 1. 本类描述的是"当前视频时间 → 基准剧情时间"的映射；
 * 2. "弹幕来源时间 → 实际显示时间"由弹幕源自身的 shift 机制负责（上游已有 `setShiftMillis`）。
 *
 * 未映射的区段必须显式列出，**不得**把未知间隙强行线性插值成高可信结果。
 *
 * 可序列化：多态形式在 JSON 中用 `type` 判别字段。
 *
 * 判别值由 [SerialName] **显式钉住**，不依赖类的全限定名——否则一旦类被改名或移动，
 * 已经落盘的对齐数据就再也读不回来。
 */
@Serializable
sealed interface TimeAlignment {
    /**
     * 未对齐 / 无法映射。必须按明确策略降级处理，
     * **不得**当作"偏移为 0"来使用。
     */
    @Serializable
    @SerialName("unaligned")
    data object Unaligned : TimeAlignment

    /** 已对齐：固定偏移。 */
    @Serializable
    @SerialName("constant_offset")
    data class ConstantOffset(
        /** 基准版本时间 = 实际版本时间 + offsetMillis */
        val offsetMillis: Long,
        /** 误差区间（毫秒）。判定时按保守方向计入 */
        val errorMillis: Long = 0L,
    ) : TimeAlignment

    /**
     * 已对齐：分段单调映射。
     *
     * [segments] 必须按 [AlignmentSegment.actualStartMillis] 升序且不重叠；
     * 相邻分段之间的空隙属于**未映射区**，判定时按降级处理，不做插值。
     */
    @Serializable
    @SerialName("segmented")
    data class Segmented(
        val segments: List<AlignmentSegment>,
        val errorMillis: Long = 0L,
    ) : TimeAlignment {
        init {
            require(segments.zipWithNext().all { (a, b) -> a.actualEndMillis <= b.actualStartMillis }) {
                "alignment segments must be sorted and non-overlapping"
            }
        }
    }
}

/**
 * 该对齐是否可用于时间判断。
 *
 * [TimeAlignment.Unaligned] 表示"映射未知"，不是"偏移为 0"，
 * 因此不能用于解锁判断，界面也必须如实显示为降级状态。
 */
val TimeAlignment.isVerified: Boolean
    get() = this !is TimeAlignment.Unaligned

/**
 * 一段单调映射：实际版本 [actualStartMillis, actualEndMillis] 线性对应
 * 基准版本 [baselineStartMillis, baselineEndMillis]。
 */
@Serializable
data class AlignmentSegment(
    val actualStartMillis: Long,
    val actualEndMillis: Long,
    val baselineStartMillis: Long,
    val baselineEndMillis: Long,
) {
    init {
        require(actualStartMillis <= actualEndMillis) { "actual range must be non-decreasing" }
        require(baselineStartMillis <= baselineEndMillis) { "baseline range must be non-decreasing" }
    }

    val actualDuration: Long get() = actualEndMillis - actualStartMillis
    val baselineDuration: Long get() = baselineEndMillis - baselineStartMillis

    /** 该段内是否速率一致（用于识别"剪辑"段）。 */
    val preservesRate: Boolean get() = actualDuration == baselineDuration
}

/**
 * 映射结果。
 *
 * @param baselineMillis 基准版本时间；null 表示**无法映射**（未映射区段或未对齐）
 * @param mapped true 表示落在已映射区段内
 * @param note 说明（用于诊断，不含剧情正文）
 */
@Serializable
data class BaselineMapping(
    val baselineMillis: Long?,
    val mapped: Boolean,
    val note: String,
)

/**
 * [ClosedFloatingPointRange] 的序列化器。
 *
 * 标准库的区间类型没有内置序列化器，但剧情包的"覆盖集序范围"必须落盘，
 * 因此这里显式写成 `{"start": .., "endInclusive": ..}`。
 *
 * 这样写而不是拆成两个顶层字段的原因：区间是领域概念（含两端），
 * 拆开会让每个使用点都要自己记住"是闭区间"。
 */
object ClosedFloatingPointRangeSerializer :
    KSerializer<ClosedFloatingPointRange<Double>> {

    @Serializable
    private data class RangeSurrogate(val start: Double, val endInclusive: Double)

    private val surrogateSerializer = RangeSurrogate.serializer()

    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: ClosedFloatingPointRange<Double>) {
        encoder.encodeSerializableValue(
            surrogateSerializer,
            RangeSurrogate(value.start, value.endInclusive),
        )
    }

    override fun deserialize(decoder: Decoder): ClosedFloatingPointRange<Double> {
        val surrogate = decoder.decodeSerializableValue(surrogateSerializer)
        return surrogate.start..surrogate.endInclusive
    }
}
