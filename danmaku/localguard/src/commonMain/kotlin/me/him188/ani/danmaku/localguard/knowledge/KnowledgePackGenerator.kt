/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.knowledge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.him188.ani.danmaku.localguard.policy.SpoilerSeverity

/**
 * 事实声明：**人手写的**那部分。
 *
 * 这个类型的存在本身是一条设计约束：生成器不会自己判断剧情。
 * "谁是凶手"、"什么时候揭晓"必须由人（或人工核对过的流程）声明，
 * 生成器只负责把声明与字幕证据对齐、算出时间、跑校验。
 *
 * 因此这里的每个字段都是**声明**，不是推断结果。
 *
 * 这是**磁盘格式**（`<workId>.facts.json`）：字段名即 JSON 键，
 * **不要重命名或删除已有字段**。读取时忽略未知字段，因此新版本写的声明文件
 * 仍可被旧版本安全读取。
 */
@Serializable
data class FactDeclaration(
    /** 稳定唯一标识；改名等于换一条事实，旧包里的引用会失效 */
    val factId: String,
    /**
     * 细粒度命题。
     *
     * 约束：身份、动机、关系、结局**分别建一条**。合并成"大事实"会让
     * "知道一部分"被当成"全都知道"，从而过早解锁。
     */
    val proposition: String,
    /** 涉及的实体 id（必须在 [StoryKnowledgePack.entities] 里存在） */
    val entityIds: List<String> = emptyList(),
    val severity: SpoilerSeverity = SpoilerSeverity.MAJOR,
    /** 证据锚；为空表示"知道在哪一集，但没有可定位的台词" */
    val anchors: List<RevealAnchor> = emptyList(),
    /**
     * 锚为空时使用的集号（"这条事实在这一集揭晓，但时间未知"）。
     * 锚非空时忽略。两处都为空则该事实无法定位，生成器会报错。
     */
    val episodeNumberWhenTimingUnknown: Double? = null,
    /** 证据来源标识，例如字幕文件名 */
    val sourceId: String? = null,
    val verification: VerificationState = VerificationState.UNKNOWN,
    /** 有证据证明原作尚未动画化等，覆盖范围内始终受保护 */
    val neverUnlocksInScope: Boolean = false,
    /** 额外的非字幕证据（剧本、访谈等），会与字幕证据合并 */
    val extraEvidence: List<EvidenceRef> = emptyList(),
)

/**
 * 一份声明文件：某部作品的全部事实声明。
 *
 * 与 [StoryKnowledgePack] 分开的原因：包是**工具产物**（含推导出的时间），
 * 声明文件是**人工输入**（只有人能确定的内容）。把两者混在一个文件里，
 * 就会有人直接手改包里的时间——那样时间就失去证据支撑了。
 *
 * 因此工作流是单向的：
 * ```
 * <workId>.facts.json （人写） + 字幕  →  生成器  →  <workId>.json （工具写，只读）
 * ```
 */
@Serializable
data class FactDeclarationFile(
    /**
     * 格式版本。
     *
     * 与包的 `schemaVersion` 独立：声明文件的结构稳定性要求不同
     * （它只被人手写，不随运行时演进）。
     */
    val declarationVersion: Int = CURRENT_DECLARATION_VERSION,
    /** 作品标识（应等于 workId 文件名部分；不一致时生成流程会报错） */
    val workId: String,
    /** 作品标题（写入包，便于人工核对） */
    val title: String,
    val seasonLabel: String? = null,
    /** 本声明文件**明确覆盖**的集序范围（含两端） */
    val coveredEpisodesStart: Double,
    val coveredEpisodesEnd: Double,
    /** 逐集信息；stableId 可为空（未知就留空，不伪造） */
    val episodes: List<StoryEpisode> = emptyList(),
    val entities: List<StoryEntity> = emptyList(),
    val specials: List<SpecialEpisodeMapping> = emptyList(),
    val seasonIndex: Int = 1,
    /** 基准视频版本描述 */
    val baselineVersionLabel: String? = null,
    /** 来源与许可说明 */
    val sourceNote: String? = null,
    /** 内容版本；每次实质变更都应改它（它参与缓存键与知识版本） */
    val contentVersion: String,
    val facts: List<FactDeclaration> = emptyList(),
) {
    /**
     * 覆盖范围。
     *
     * [Transient]：这是从两个标量派生出的区间，落盘时只存 `coveredEpisodesStart/End`，
     * 避免磁盘上出现两份可能不一致的表示。
     */
    @Transient
    val coveredEpisodes: ClosedFloatingPointRange<Double>
        get() = coveredEpisodesStart..coveredEpisodesEnd

    companion object {
        const val CURRENT_DECLARATION_VERSION: Int = 1
    }
}

/** 声明文件的解析/写出。与包的编解码共用同一套 JSON 配置。 */
object FactDeclarationCodec {
    fun encode(file: FactDeclarationFile): String =
        KnowledgePackCodec.json.encodeToString(FactDeclarationFile.serializer(), file)

    /** 解析失败返回 null（**不抛异常**）：坏文件是"资料不可用"这一正常状态。 */
    fun decodeOrNull(text: String): FactDeclarationFile? =
        try {
            KnowledgePackCodec.json.decodeFromString(FactDeclarationFile.serializer(), text)
        } catch (_: Exception) {
            null
        }
}

/** 生成过程的一条提示。 */
data class GenerationNotice(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
    /** 相关事实 id（若适用） */
    val factId: String? = null,
)

/** 生成结果。 */
data class GenerationResult(
    val pack: StoryKnowledgePack,
    /** 结构校验 + 证据校验的报告（与包一起产出，便于调用方决定是否落盘） */
    val validation: ValidationReport,
    /** 生成过程本身的提示：时间未知、锚找不到、条目数偏少等 */
    val notices: List<GenerationNotice>,
) {
    /** 包是否可以安全落盘：结构无误即可用；证据类问题会保留在报告里供人工核对。 */
    val isStructurallyValid: Boolean get() = validation.structuralIssues.none { it.severity == ValidationSeverity.ERROR }

    val errors: List<GenerationNotice> get() = notices.filter { it.severity == ValidationSeverity.ERROR }
    val warnings: List<GenerationNotice> get() = notices.filter { it.severity == ValidationSeverity.WARNING }
}

/**
 * 从"事实声明 + 带时间字幕"生成剧情包。
 *
 * 三条不可违背的规则：
 *
 * 1. **不发明时间**：锚找不到或引文有歧义时，该事实的时间保持未知
 *    （`earliest/latest` 为 null），**不**填一个猜测值。时间未知的事实永远不会自动解锁。
 * 2. **不静默丢事实**：任何提取失败都会产出 [GenerationNotice]，调用方能看见。
 *    出错的只是那一条事实的时间，其余事实照常生成——一次坏字幕不该让整个包作废。
 * 3. **不放过结构错误**：包生成后必须过 [KnowledgePackValidator]，结构错误会被报告出来
 *    （例如锚指向了覆盖范围之外的集）。
 */
object KnowledgePackGenerator {

    /**
     * @param schemaVersion 写入包的格式版本；默认当前版本
     * @param baselineVersionLabel 基准视频版本描述
     * @param sourceNote 来源与许可说明
     * @param cuesByEpisode 集号 → 该集字幕。缺失的集视为"没有字幕"。
     */
    fun generate(
        workId: String,
        title: String,
        seasonLabel: String?,
        coveredEpisodes: ClosedFloatingPointRange<Double>,
        episodes: List<StoryEpisode>,
        entities: List<StoryEntity>,
        declarations: List<FactDeclaration>,
        cuesByEpisode: Map<Double, List<SubtitleCue>>,
        schemaVersion: Int = KnowledgePackCodec.CURRENT_SCHEMA_VERSION,
        contentVersion: String,
        baselineVersionLabel: String? = null,
        sourceNote: String? = null,
        specials: List<SpecialEpisodeMapping> = emptyList(),
        seasonIndex: Int = 1,
    ): GenerationResult {
        val notices = mutableListOf<GenerationNotice>()
        val facts = mutableListOf<StoryFact>()

        val duplicateIds = declarations.groupingBy { it.factId }.eachCount().filterValues { it > 1 }.keys
        for (id in duplicateIds) {
            notices += GenerationNotice(
                ValidationSeverity.ERROR, "DUPLICATE_DECLARATION",
                "事实声明 '$id' 出现多次；factId 必须唯一", id,
            )
        }

        for (declaration in declarations.distinctBy { it.factId }) {
            facts += buildFact(declaration, cuesByEpisode, notices)
        }

        // 少到没有区分度的包不值得发布：它会让使用者以为"有保护"而实际几乎不解锁任何东西的相反面，
        // 即几乎不解锁 → 大量误杀。这里只提示，不阻止。
        if (facts.isNotEmpty() && facts.size < MIN_RECOMMENDED_FACTS) {
            notices += GenerationNotice(
                ValidationSeverity.WARNING, "FEW_FACTS",
                "只有 ${facts.size} 条事实；覆盖不足会让判定退化为“几乎全部屏蔽”，" +
                        "建议至少 $MIN_RECOMMENDED_FACTS 条",
            )
        }
        val unknownTiming = facts.count { !it.reveal.hasTiming && !it.neverUnlocksInScope }
        if (unknownTiming > 0) {
            notices += GenerationNotice(
                ValidationSeverity.WARNING, "TIMING_UNKNOWN",
                "$unknownTiming 条事实的时间未知（没有可定位的字幕证据）；" +
                        "这些事实永远不会自动解锁",
            )
        }

        val pack = StoryKnowledgePack(
            schemaVersion = schemaVersion,
            contentVersion = contentVersion,
            work = StoryWork(
                workId = workId,
                title = title,
                seasonLabel = seasonLabel,
                coveredEpisodes = coveredEpisodes,
                episodes = episodes,
                specials = specials,
                seasonIndex = seasonIndex,
            ),
            facts = facts,
            entities = entities,
            baselineVersionLabel = baselineVersionLabel,
            sourceNote = sourceNote,
        )

        return GenerationResult(
            pack = pack,
            validation = KnowledgePackValidator.validate(pack),
            notices = notices,
        )
    }

    /** 一条事实建议包含的最少数量，低于此值给出提示（不阻止生成）。 */
    const val MIN_RECOMMENDED_FACTS: Int = 5

    /**
     * 从**声明文件**生成包：把"人手写的输入"接到生成流程上。
     *
     * 与 [generate] 的区别只在输入形态——本函数是产包流程的实际入口：
     * ```
     * <workId>.facts.json （人写） + 字幕文件  →  本函数  →  <workId>.json
     * ```
     * 提供它的意义在于消除"给字幕后还要改代码"这一步：声明与字幕都是文件，
     * 出错时也只需改文件重跑。
     *
     * 会额外做两项与文件相关的检查（[generate] 不做，因为那些信息在文件里才有）：
     * - 声明文件记录的 `workId` 必须与调用方给的一致（防止把 A 作品的声明写成 B 作品的文件）；
     * - 覆盖范围必须合法（起点 ≤ 终点）。
     */
    fun generateFromDeclarations(
        file: FactDeclarationFile,
        expectedWorkId: String,
        cuesByEpisode: Map<Double, List<SubtitleCue>>,
        schemaVersion: Int = KnowledgePackCodec.CURRENT_SCHEMA_VERSION,
    ): GenerationResult {
        val result = generate(
            workId = file.workId,
            title = file.title,
            seasonLabel = file.seasonLabel,
            coveredEpisodes = file.coveredEpisodes,
            episodes = file.episodes,
            entities = file.entities,
            declarations = file.facts,
            cuesByEpisode = cuesByEpisode,
            schemaVersion = schemaVersion,
            contentVersion = file.contentVersion,
            baselineVersionLabel = file.baselineVersionLabel,
            sourceNote = file.sourceNote,
            specials = file.specials,
            seasonIndex = file.seasonIndex,
        )

        val extra = mutableListOf<GenerationNotice>()
        if (file.workId != expectedWorkId) {
            extra += GenerationNotice(
                ValidationSeverity.ERROR, "WORK_ID_MISMATCH",
                "声明文件里的 workId='${file.workId}' 与期望的 '$expectedWorkId' 不一致；" +
                        "这会把 A 作品的声明写成 B 作品的包",
            )
        }
        if (file.declarationVersion > FactDeclarationFile.CURRENT_DECLARATION_VERSION) {
            extra += GenerationNotice(
                ValidationSeverity.ERROR, "DECLARATION_VERSION_TOO_NEW",
                "声明文件版本 ${file.declarationVersion} 高于本版本支持的 " +
                        "${FactDeclarationFile.CURRENT_DECLARATION_VERSION}；" +
                        "新版本的字段语义可能已改变，不得按“能读多少读多少”处理",
            )
        }
        if (file.coveredEpisodesStart > file.coveredEpisodesEnd) {
            extra += GenerationNotice(
                ValidationSeverity.ERROR, "COVERED_RANGE_INVERTED",
                "覆盖范围的起点 ${file.coveredEpisodesStart} 大于终点 ${file.coveredEpisodesEnd}",
            )
        }
        if (file.facts.isEmpty()) {
            extra += GenerationNotice(
                ValidationSeverity.ERROR, "NO_FACTS",
                "声明文件里没有任何事实；空包没有意义，也不会保护任何内容",
            )
        }
        // 哪一集没有字幕是产包时最该看见的信息：它直接决定哪些事实会时间未知。
        val missingSubtitles = file.facts
            .flatMap { it.anchors }
            .map { it.episodeNumber }
            .distinct()
            .filter { ep -> cuesByEpisode[ep].isNullOrEmpty() }
        if (missingSubtitles.isNotEmpty()) {
            extra += GenerationNotice(
                ValidationSeverity.ERROR, "SUBTITLE_MISSING",
                "以下集有证据锚但没有字幕：${missingSubtitles.sorted().joinToString(", ")}；" +
                        "对应事实的时间会保持未知",
            )
        }

        return result.copy(notices = result.notices + extra)
    }

    private fun buildFact(
        declaration: FactDeclaration,
        cuesByEpisode: Map<Double, List<SubtitleCue>>,
        notices: MutableList<GenerationNotice>,
    ): StoryFact {
        val extraction = if (declaration.anchors.isEmpty()) {
            null
        } else {
            RevealBoundaryExtractor.extract(declaration.anchors, cuesByEpisode)
        }

        val reveal: RevealBoundary = when (extraction) {
            null -> {
                val episode = declaration.episodeNumberWhenTimingUnknown
                if (episode == null) {
                    notices += GenerationNotice(
                        ValidationSeverity.ERROR, "FACT_NOT_LOCATABLE",
                        "事实 '${declaration.factId}' 既没有证据锚，也没有指定集号，无法定位",
                        declaration.factId,
                    )
                }
                RevealBoundary(
                    episodeNumber = episode ?: Double.NaN,
                    earliestMillis = null,
                    latestMillis = null,
                    precision = "unknown",
                    evidence = declaration.extraEvidence,
                    verification = declaration.verification,
                )
            }

            is BoundaryExtraction.Resolved -> RevealBoundary(
                episodeNumber = extraction.episodeNumber,
                earliestMillis = extraction.earliestMillis,
                latestMillis = extraction.latestMillis,
                precision = extraction.precision,
                // 字幕证据的定位信息由调用方通过 extraEvidence 提供文件级引用；
                // 这里至少记录锚引文本身，保证"结论可复核"。
                evidence = declaration.extraEvidence + declaration.anchors.map {
                    EvidenceRef(
                        kind = "subtitle-quote",
                        locator = "ep${it.episodeNumber}#${it.quote.take(60)}",
                        precision = extraction.precision,
                    )
                },
                verification = declaration.verification,
            )

            is BoundaryExtraction.TimingUnknown -> {
                notices += GenerationNotice(
                    ValidationSeverity.WARNING, "TIMING_UNKNOWN",
                    "事实 '${declaration.factId}' 的时间未知：${extraction.reason}",
                    declaration.factId,
                )
                RevealBoundary(
                    episodeNumber = extraction.episodeNumber
                        .takeIf { !it.isNaN() }
                        ?: declaration.episodeNumberWhenTimingUnknown
                        ?: Double.NaN,
                    earliestMillis = null,
                    latestMillis = null,
                    precision = "unknown",
                    evidence = declaration.extraEvidence,
                    verification = declaration.verification,
                )
            }

            is BoundaryExtraction.Failed -> {
                for ((anchor, failure) in extraction.failures) {
                    notices += GenerationNotice(
                        ValidationSeverity.ERROR, failure.reason.name,
                        "事实 '${declaration.factId}' 的证据锚无法定位：${failure.detail}",
                        declaration.factId,
                    )
                }
                RevealBoundary(
                    episodeNumber = declaration.anchors.first().episodeNumber,
                    earliestMillis = null,
                    latestMillis = null,
                    precision = "unknown",
                    evidence = declaration.extraEvidence,
                    verification = declaration.verification,
                )
            }
        }

        return StoryFact(
            factId = declaration.factId,
            proposition = declaration.proposition,
            entityIds = declaration.entityIds,
            severity = declaration.severity,
            reveal = reveal,
            evidence = reveal.evidence,
            sourceId = declaration.sourceId,
            verification = declaration.verification,
            neverUnlocksInScope = declaration.neverUnlocksInScope,
        )
    }
}
