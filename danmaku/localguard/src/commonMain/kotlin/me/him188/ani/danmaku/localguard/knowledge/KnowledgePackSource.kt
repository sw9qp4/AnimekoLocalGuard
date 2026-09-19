/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.knowledge

import kotlinx.serialization.json.Json

/**
 * 剧情包的**磁盘格式**约定与解析。
 *
 * 集中在一处的原因：写入端（将来的生成工具）与读取端（手机上的守卫）必须使用
 * **完全相同**的配置，否则会出现"工具写得出、手机上读不回"这类只在真机上暴露的问题。
 */
object KnowledgePackCodec {

    /**
     * 磁盘格式的解析配置。
     *
     * - `ignoreUnknownKeys = true`：**必须**。新版本写入的包要能被旧版本安全读取，
     *   否则一次格式演进就会让所有旧版用户丢包。
     * - `prettyPrint = false`：包文件在设备上不需要人读，压缩后的体积更重要。
     * - `allowSpecialFloatingPointValues = true`：与上游 Animeko 的 DataStore 配置一致，
     *   避免出现"上游能存、这里读不了"的不一致。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        allowSpecialFloatingPointValues = true
    }

    /** 当前支持的 [StoryKnowledgePack.schemaVersion]。 */
    const val CURRENT_SCHEMA_VERSION: Int = 1

    fun encode(pack: StoryKnowledgePack): String =
        json.encodeToString(StoryKnowledgePack.serializer(), pack)

    /**
     * 解析剧情包。
     *
     * **不抛异常**：解析失败是"资料不可用"这一正常运行状态（包缺失、损坏、格式过新），
     * 必须能被如实上报并按资料缺失降级，而不是让守卫崩溃或静默放行。
     */
    fun decodeOrNull(text: String): StoryKnowledgePack? =
        try {
            json.decodeFromString(StoryKnowledgePack.serializer(), text)
        } catch (_: Exception) {
            null
        }

    /**
     * 解析片源对齐文件。
     *
     * 与剧情包一样，**不抛异常**：对齐文件坏了就等同于"没有配置对齐"，
     * 调用方按 [TimeAlignment.Unaligned] 降级。绝不退化成"偏移 0"。
     */
    fun decodeAlignmentOrNull(text: String): TimeAlignment? =
        try {
            json.decodeFromString(TimeAlignment.serializer(), text)
        } catch (_: Exception) {
            null
        }

    fun encodeAlignment(value: TimeAlignment): String =
        json.encodeToString(TimeAlignment.serializer(), value)
}

/**
 * 一次剧情包装载的结果。
 *
 * 刻意把失败原因显式建模，而不是用一个可空引用：
 * "没有这部作品的包"与"包存在但坏了"需要给用户**不同**的提示，
 * 也需要不同的处置（前者等资料，后者要修包）。
 */
sealed interface KnowledgeLoadResult {
    /** 装载成功。 */
    data class Loaded(val pack: StoryKnowledgePack) : KnowledgeLoadResult

    /**
     * 有这部作品对应的包，但无法使用（损坏、无法解析，或 schemaVersion 高于本版本支持的范围）。
     * 属于**资料不可用**，按资料缺失降级，不得当成"没有包所以不用管"。
     */
    data class Unusable(val reason: String) : KnowledgeLoadResult

    /** 没有这部作品对应的包。这是正常状态（绝大多数作品都不会有包）。 */
    data object Absent : KnowledgeLoadResult
}

/**
 * 装载时对剧情包做**结构校验**。
 *
 * 为什么必须在装载路径上跑（而不是只在生成时跑）：
 * 包可能来自别的机器、被手改过、或在传输中损坏；生成时通过不代表装载时仍然合法。
 * 校验器早就写好了，但此前**没有任何装载路径调用它**——"有校验器但没人跑"
 * 与没有校验器在效果上是同一件事。
 *
 * 处置策略，按"结构"与"证据"分开：
 *
 * - **结构错误 → 拒绝**（[KnowledgeLoadResult.Unusable]）。这类问题会让判定做出错误结论，
 *   例如揭晓集落在覆盖范围之外、ID 重复导致取到错误的事实。
 * - **证据问题 → 接受但记录**。核对状态不足是"资料可信度"问题，
 *   而代码已经保证未核对事实不会自动解锁（`trustedForUnlock`），因此不阻断使用。
 *   拒绝它反而会让一批可用资料被判为不可用。
 */
internal fun validateOnLoad(pack: StoryKnowledgePack): String? {
    val report = KnowledgePackValidator.validate(pack)
    if (!report.hasStructuralError) return null
    val detail = report.structuralIssues
        .filter { it.severity == ValidationSeverity.ERROR }
        .joinToString("; ") { "${it.code}: ${it.message}" }
    return "剧情包结构校验未通过：$detail"
}

/**
 * 作品标识到剧情包的映射与装载入口。
 *
 * 为什么把"映射"与"存储"分开：作品标识来自上游（Bangumi subjectId），
 * 而包文件的命名是我们自己的约定。两者一旦耦合，将来改文件名就会影响判定逻辑。
 * 这里只约定"给定作品标识，返回该作品的包"，具体怎么找由实现决定。
 */
interface StoryKnowledgeSource {
    /**
     * 取某部作品的剧情包。实现**不得**抛异常；做不到时返回
     * [KnowledgeLoadResult.Unusable] 或 [KnowledgeLoadResult.Absent]。
     */
    suspend fun load(workId: String): KnowledgeLoadResult

    /**
     * 取某部作品的实际片源对齐。
     *
     * 返回 null 表示"没有为该作品配置对齐"——此时守卫必须按
     * [TimeAlignment.Unaligned] 降级处理，**不得**当成"偏移为 0"。
     */
    suspend fun loadAlignment(workId: String): TimeAlignment? = null
}

/** 什么都不提供的实现：所有作品都视为"没有包"。用于未接入资料时的默认接线。 */
object EmptyStoryKnowledgeSource : StoryKnowledgeSource {
    override suspend fun load(workId: String): KnowledgeLoadResult = KnowledgeLoadResult.Absent

    override suspend fun loadAlignment(workId: String): TimeAlignment? = null
}

/**
 * 内存实现：直接给出一部或多部作品的包。
 *
 * 用途：单元测试、将来的"内置包"接线，以及在没有文件系统的平台上复用同一套逻辑。
 */
class InMemoryStoryKnowledgeSource(
    private val packs: Map<String, StoryKnowledgePack> = emptyMap(),
    private val alignments: Map<String, TimeAlignment> = emptyMap(),
) : StoryKnowledgeSource {
    override suspend fun load(workId: String): KnowledgeLoadResult {
        val pack = packs[workId] ?: return KnowledgeLoadResult.Absent
        if (pack.schemaVersion > KnowledgePackCodec.CURRENT_SCHEMA_VERSION) {
            // 高于本版本支持的范围：**不得**按"能读多少读多少"处理。
            // 新版本的字段语义可能改变了解锁含义，猜测性地读取会给出错误的时间判断。
            return KnowledgeLoadResult.Unusable(
                "schemaVersion ${pack.schemaVersion} 高于本版本支持的 " +
                        "${KnowledgePackCodec.CURRENT_SCHEMA_VERSION}",
            )
        }
        validateOnLoad(pack)?.let { return KnowledgeLoadResult.Unusable(it) }
        return KnowledgeLoadResult.Loaded(pack)
    }

    override suspend fun loadAlignment(workId: String): TimeAlignment? = alignments[workId]
}

/**
 * 从原始文本装载，便于实现者复用解析与版本检查，不必重复这两段逻辑。
 *
 * @param text 包文件内容；null 表示文件不存在。
 */
fun loadFromText(text: String?): KnowledgeLoadResult {
    if (text == null) return KnowledgeLoadResult.Absent
    val pack = KnowledgePackCodec.decodeOrNull(text)
        ?: return KnowledgeLoadResult.Unusable("无法解析剧情包内容")
    if (pack.schemaVersion > KnowledgePackCodec.CURRENT_SCHEMA_VERSION) {
        return KnowledgeLoadResult.Unusable(
            "schemaVersion ${pack.schemaVersion} 高于本版本支持的 " +
                    "${KnowledgePackCodec.CURRENT_SCHEMA_VERSION}",
        )
    }
    validateOnLoad(pack)?.let { return KnowledgeLoadResult.Unusable(it) }
    return KnowledgeLoadResult.Loaded(pack)
}
