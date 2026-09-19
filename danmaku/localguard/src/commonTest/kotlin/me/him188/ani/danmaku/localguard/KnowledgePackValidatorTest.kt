/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 知识包校验器测试。
 *
 * 覆盖：ID 唯一性、覆盖范围、证据引用、核对状态约束、
 * "永不解锁必须有证据"、区间合法性、警告类问题。
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.EvidenceRef
import me.him188.ani.danmaku.localguard.knowledge.KnowledgePackValidator
import me.him188.ani.danmaku.localguard.knowledge.RevealBoundary
import me.him188.ani.danmaku.localguard.knowledge.StoryEntity
import me.him188.ani.danmaku.localguard.knowledge.StoryEpisode
import me.him188.ani.danmaku.localguard.knowledge.StoryFact
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.StoryWork
import me.him188.ani.danmaku.localguard.knowledge.ValidationSeverity
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.policy.SpoilerSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgePackValidatorTest {

    private fun fact(
        id: String,
        episodeNumber: Double = 1.0,
        earliest: Long? = 1_000L,
        latest: Long? = 2_000L,
        verification: VerificationState = VerificationState.VERIFIED,
        evidence: List<EvidenceRef> = listOf(EvidenceRef("subtitle", "f.vtt#1")),
        entityIds: List<String> = listOf("E1"),
        neverUnlocks: Boolean = false,
    ) = StoryFact(
        factId = id,
        proposition = "虚构命题 $id",
        entityIds = entityIds,
        severity = SpoilerSeverity.MAJOR,
        reveal = RevealBoundary(
            episodeNumber = episodeNumber,
            earliestMillis = earliest,
            latestMillis = latest,
            precision = "minute",
            evidence = evidence,
            verification = verification,
        ),
        evidence = evidence,
        sourceId = "TEST",
        verification = verification,
        neverUnlocksInScope = neverUnlocks,
    )

    private fun pack(
        facts: List<StoryFact>,
        entities: List<StoryEntity> = listOf(StoryEntity("E1", "虚构实体")),
        episodes: List<StoryEpisode> = listOf(StoryEpisode(1.0), StoryEpisode(2.0)),
        covered: ClosedFloatingPointRange<Double> = 1.0..2.0,
        schemaVersion: Int = 1,
        contentVersion: String = "v1",
    ) = StoryKnowledgePack(
        schemaVersion = schemaVersion,
        contentVersion = contentVersion,
        work = StoryWork("W", "虚构作品", coveredEpisodes = covered, episodes = episodes),
        facts = facts,
        entities = entities,
    )

    @Test
    fun validFixturePackHasNoStructuralErrors() {
        val report = KnowledgePackValidator.validate(Fixtures.pack())
        assertFalse(
            report.hasStructuralError,
            "合法 fixture 不应有结构错误: ${report.structuralIssues}",
        )
    }

    @Test
    fun duplicateFactIdIsError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1"), fact("F1"))),
        )
        assertTrue(
            report.structuralIssues.any { it.code == "FACT_ID_DUPLICATE" && it.severity == ValidationSeverity.ERROR },
            "重复 factId 必须是结构错误",
        )
    }

    @Test
    fun duplicateEntityIdIsError() {
        val report = KnowledgePackValidator.validate(
            pack(
                listOf(fact("F1")),
                entities = listOf(StoryEntity("E1", "甲"), StoryEntity("E1", "乙")),
            ),
        )
        assertTrue(report.structuralIssues.any { it.code == "ENTITY_ID_DUPLICATE" })
    }

    @Test
    fun duplicateEpisodeNumberIsError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1")), episodes = listOf(StoryEpisode(1.0), StoryEpisode(1.0))),
        )
        assertTrue(report.structuralIssues.any { it.code == "EPISODE_NUMBER_DUPLICATE" })
    }

    @Test
    fun revealOutsideCoverageIsError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", episodeNumber = 9.0))),
        )
        assertTrue(
            report.structuralIssues.any { it.code == "REVEAL_OUTSIDE_COVERAGE" },
            "揭晓集超出声明覆盖范围必须报错",
        )
    }

    @Test
    fun unknownEntityReferenceIsError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", entityIds = listOf("E_MISSING")))),
        )
        assertTrue(report.structuralIssues.any { it.code == "FACT_ENTITY_MISSING" })
    }

    @Test
    fun invertedTimeRangeIsError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", earliest = 5_000L, latest = 1_000L))),
        )
        assertTrue(report.structuralIssues.any { it.code == "REVEAL_TIME_INVERTED" })
    }

    @Test
    fun missingTimingIsWarningNotError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", earliest = null, latest = null))),
        )
        assertTrue(
            report.structuralIssues.any { it.code == "REVEAL_TIMING_UNKNOWN" && it.severity == ValidationSeverity.WARNING },
            "时间缺失应记为警告（因为不会被用于自动解锁），而不是结构错误",
        )
        assertFalse(report.hasStructuralError)
    }

    @Test
    fun verifiedWithoutEvidenceIsEvidenceError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", evidence = emptyList()))),
        )
        assertTrue(
            report.evidenceIssues.any { it.code == "VERIFIED_WITHOUT_EVIDENCE" },
            "标记 VERIFIED 却没有证据必须报错",
        )
    }

    @Test
    fun neverUnlockWithoutEvidenceIsError() {
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", evidence = emptyList(), neverUnlocks = true))),
        )
        assertTrue(report.evidenceIssues.any { it.code == "NEVER_UNLOCK_WITHOUT_EVIDENCE" })
    }

    @Test
    fun negativeSchemaVersionIsError() {
        val report = KnowledgePackValidator.validate(pack(listOf(fact("F1")), schemaVersion = 0))
        assertTrue(report.structuralIssues.any { it.code == "SCHEMA_VERSION" })
    }

    @Test
    fun blankContentVersionIsError() {
        val report = KnowledgePackValidator.validate(pack(listOf(fact("F1")), contentVersion = "  "))
        assertTrue(report.structuralIssues.any { it.code == "CONTENT_VERSION_EMPTY" })
    }

    @Test
    fun structuralAndEvidenceReportsAreSeparate() {
        // 结构校验与证据审核必须分开报告（总任务说明第 14.C 条）
        val report = KnowledgePackValidator.validate(
            pack(listOf(fact("F1", episodeNumber = 9.0, evidence = emptyList()))),
        )
        assertTrue(report.structuralIssues.any { it.code == "REVEAL_OUTSIDE_COVERAGE" })
        assertTrue(
            report.evidenceIssues.any { it.code == "VERIFIED_WITHOUT_EVIDENCE" },
            "证据问题不应混入 structuralIssues",
        )
    }
}
