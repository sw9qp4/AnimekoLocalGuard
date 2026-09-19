/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 一部作品的剧情知识包。
 *
 * 说明：
 * - [facts] 与 [entities] 为结构化事实与实体，可校验、可审核。
 * - 结构校验通过**不等于**剧情真实或时间正确，两者分开报告。
 *
 * 这是剧情包的**磁盘格式**：字段名即 JSON 键，**不要重命名或删除已有字段**。
 * 读取时忽略未知字段，因此新版本写入的包可被旧版本安全读取。
 */
@Serializable
data class StoryKnowledgePack(
    val schemaVersion: Int,
    val contentVersion: String,
    val work: StoryWork,
    val facts: List<StoryFact>,
    val entities: List<StoryEntity> = emptyList(),
    /** 本包使用的基准视频版本描述（用于与"实际版本"区分） */
    val baselineVersionLabel: String? = null,
    /** 来源与许可说明 */
    val sourceNote: String? = null,
) {
    /**
     * factId 索引，便于判定时按 id 取事实。
     *
     * [Transient]：这是**派生**数据，不是包内容。写盘时不落它，读盘后由构造器重新建立，
     * 因此磁盘格式里不会出现两份可能不一致的事实列表。
     */
    @Transient
    val factsById: Map<String, StoryFact> = facts.associateBy { it.factId }
}

/** 校验问题严重程度。 */
enum class ValidationSeverity { ERROR, WARNING }

/** 单条校验问题。 */
data class ValidationIssue(
    val severity: ValidationSeverity,
    val code: String,
    val message: String,
    val subject: String? = null,
)

/** 校验报告。结构与证据审核分开报告（总任务说明第 14.C 条）。 */
data class ValidationReport(
    val structuralIssues: List<ValidationIssue>,
    val evidenceIssues: List<ValidationIssue>,
) {
    val hasStructuralError: Boolean get() = structuralIssues.any { it.severity == ValidationSeverity.ERROR }
    val hasEvidenceError: Boolean get() = evidenceIssues.any { it.severity == ValidationSeverity.ERROR }
}

/**
 * 知识包校验器。
 *
 * 只做**可机械判定**的检查：ID 唯一性、区间合法性、证据引用、版本匹配、
 * 集序覆盖、时间边界。剧情真实性需要人工/来源审核，不由本校验器负责。
 */
object KnowledgePackValidator {

    fun validate(pack: StoryKnowledgePack): ValidationReport {
        val structural = mutableListOf<ValidationIssue>()
        val evidence = mutableListOf<ValidationIssue>()

        // --- 结构校验 ---

        if (pack.schemaVersion <= 0) {
            structural += ValidationIssue(
                ValidationSeverity.ERROR, "SCHEMA_VERSION",
                "schemaVersion must be positive", "schemaVersion",
            )
        }
        if (pack.contentVersion.isBlank()) {
            structural += ValidationIssue(
                ValidationSeverity.ERROR, "CONTENT_VERSION_EMPTY",
                "contentVersion must not be blank", "contentVersion",
            )
        }

        val duplicateFactIds = pack.facts.groupBy { it.factId }.filterValues { it.size > 1 }.keys
        for (id in duplicateFactIds) {
            structural += ValidationIssue(
                ValidationSeverity.ERROR, "FACT_ID_DUPLICATE",
                "duplicate factId", id,
            )
        }

        val duplicateEntityIds = pack.entities.groupBy { it.entityId }.filterValues { it.size > 1 }.keys
        for (id in duplicateEntityIds) {
            structural += ValidationIssue(
                ValidationSeverity.ERROR, "ENTITY_ID_DUPLICATE",
                "duplicate entityId", id,
            )
        }

        val duplicateEpisodeNumbers = pack.work.episodes.groupBy { it.episodeNumber }
            .filterValues { it.size > 1 }.keys
        for (n in duplicateEpisodeNumbers) {
            structural += ValidationIssue(
                ValidationSeverity.ERROR, "EPISODE_NUMBER_DUPLICATE",
                "duplicate episodeNumber in work", n.toString(),
            )
        }

        val covered = pack.work.coveredEpisodes
        for (fact in pack.facts) {
            val f = fact.factId

            // 揭晓集是否落在声明覆盖范围内
            if (fact.reveal.episodeNumber !in covered && !fact.neverUnlocksInScope) {
                structural += ValidationIssue(
                    ValidationSeverity.ERROR, "REVEAL_OUTSIDE_COVERAGE",
                    "reveal episode ${fact.reveal.episodeNumber} is outside covered range $covered", f,
                )
            }

            // 事实引用的实体必须存在
            for (entityId in fact.entityIds) {
                if (pack.entities.none { it.entityId == entityId }) {
                    structural += ValidationIssue(
                        ValidationSeverity.ERROR, "FACT_ENTITY_MISSING",
                        "fact references unknown entityId", "$f -> $entityId",
                    )
                }
            }

            // 时间边界与精度
            if (fact.reveal.hasTiming) {
                val lo = fact.reveal.earliestMillis!!
                val hi = fact.reveal.latestMillis!!
                if (lo > hi) {
                    structural += ValidationIssue(
                        ValidationSeverity.ERROR, "REVEAL_TIME_INVERTED",
                        "earliestMillis > latestMillis", f,
                    )
                }
                if (fact.reveal.precision.isBlank()) {
                    structural += ValidationIssue(
                        ValidationSeverity.WARNING, "REVEAL_PRECISION_MISSING",
                        "timing present but precision blank", f,
                    )
                }
            } else {
                // 时间缺失：不算结构错误，但必须显式记录，不能假装已知
                structural += ValidationIssue(
                    ValidationSeverity.WARNING, "REVEAL_TIMING_UNKNOWN",
                    "reveal timing unknown; will not be used for automatic unlock", f,
                )
            }

            // 缺少证据
            if (fact.evidence.isEmpty() && !fact.neverUnlocksInScope) {
                evidence += ValidationIssue(
                    ValidationSeverity.WARNING, "FACT_EVIDENCE_EMPTY",
                    "fact has no evidence reference", f,
                )
            }

            // 永不解锁必须有证据支撑
            if (fact.neverUnlocksInScope && fact.evidence.isEmpty()) {
                evidence += ValidationIssue(
                    ValidationSeverity.ERROR, "NEVER_UNLOCK_WITHOUT_EVIDENCE",
                    "neverUnlocksInScope requires evidence", f,
                )
            }

            // 未核对项标记为高可信用途
            if (fact.reveal.verification.trustedForUnlock && fact.evidence.isEmpty()) {
                evidence += ValidationIssue(
                    ValidationSeverity.ERROR, "VERIFIED_WITHOUT_EVIDENCE",
                    "verification=VERIFIED but no evidence (structure check is not plot verification)", f,
                )
            }

            // DISPUTED 不得标记为可用于解锁
            if (fact.reveal.verification == VerificationState.DISPUTED && fact.reveal.verification.trustedForUnlock) {
                evidence += ValidationIssue(
                    ValidationSeverity.ERROR, "DISPUTED_TRUSTED",
                    "DISPUTED fact must not be trusted for unlock", f,
                )
            }
        }

        // 特别篇位置必须在覆盖范围内，且不与其他集序冲突
        for (s in pack.work.specials) {
            if (s.episodeNumber !in covered) {
                structural += ValidationIssue(
                    ValidationSeverity.ERROR, "SPECIAL_OUTSIDE_COVERAGE",
                    "special episode ${s.episodeNumber} outside covered range", s.specialId,
                )
            }
        }

        return ValidationReport(structural, evidence)
    }
}
