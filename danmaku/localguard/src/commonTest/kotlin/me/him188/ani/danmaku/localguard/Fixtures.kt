/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.AlignmentSegment
import me.him188.ani.danmaku.localguard.knowledge.EvidenceRef
import me.him188.ani.danmaku.localguard.knowledge.RevealBoundary
import me.him188.ani.danmaku.localguard.knowledge.StoryEntity
import me.him188.ani.danmaku.localguard.knowledge.StoryEpisode
import me.him188.ani.danmaku.localguard.knowledge.StoryFact
import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.StoryWork
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.policy.SpoilerSeverity

/**
 * 测试用**虚构**剧情资料。
 *
 * 严格约束（总任务说明第 6 节末）：所有开发演示必须使用虚构剧情，
 * **不得**使用真实作品的关键剧透。本文件中的作品名、角色名、事实全部为虚构。
 */
object Fixtures {

    const val WORK_ID = "TESTWORK"
    private const val MIN = 60_000L

    fun evidence(locator: String = "fake-subtitle-ep01.vtt#00:05:00.000-00:05:20.000") =
        listOf(EvidenceRef(kind = "subtitle", locator = locator, precision = "minute"))

    /**
     * 虚构作品：3 集。
     *
     * 事实设计覆盖不同揭晓形态：
     * - F_IDENTITY：第 1 集 5:00–5:20 揭晓（区间，取上界 5:20）
     * - F_MOTIVE  ：第 2 集 10:00–10:30 揭晓
     * - F_ENDING  ：第 3 集 20:00–20:40 揭晓
     * - F_UNKNOWN ：时间未知（只有集号）
     * - F_UNVERIFIED：时间已知但核对状态为 AI 候选（不得自动解锁）
     * - F_NEVER   ：覆盖范围内永不解锁
     */
    fun pack(
        identityVerification: VerificationState = VerificationState.VERIFIED,
        unknownVerification: VerificationState = VerificationState.UNKNOWN,
    ): StoryKnowledgePack {
        val entities = listOf(
            StoryEntity(
                entityId = "E_A",
                canonicalName = "虚构角色甲",
                aliases = listOf("甲", "小甲"),
                ambiguousAliases = listOf("老师", "队长"),
            ),
            StoryEntity(entityId = "E_B", canonicalName = "虚构角色乙", aliases = listOf("乙")),
        )

        val facts = listOf(
            StoryFact(
                factId = "F_IDENTITY",
                proposition = "虚构角色甲的真实身份是虚构角色乙的同伴",
                entityIds = listOf("E_A", "E_B"),
                severity = SpoilerSeverity.MAJOR,
                reveal = RevealBoundary(
                    episodeNumber = 1.0,
                    earliestMillis = 5 * MIN,
                    latestMillis = 5 * MIN + 20_000L,
                    precision = "minute",
                    evidence = evidence(),
                    verification = identityVerification,
                ),
                evidence = evidence(),
                sourceId = "FIXTURE",
                verification = identityVerification,
            ),
            StoryFact(
                factId = "F_MOTIVE",
                proposition = "虚构角色乙的动机是保护甲",
                entityIds = listOf("E_B"),
                severity = SpoilerSeverity.MAJOR,
                reveal = RevealBoundary(
                    episodeNumber = 2.0,
                    earliestMillis = 10 * MIN,
                    latestMillis = 10 * MIN + 30_000L,
                    precision = "minute",
                    evidence = evidence("fake-subtitle-ep02.vtt#00:10:00.000-00:10:30.000"),
                    verification = VerificationState.VERIFIED,
                ),
                evidence = evidence("fake-subtitle-ep02.vtt#00:10:00.000-00:10:30.000"),
                sourceId = "FIXTURE",
                verification = VerificationState.VERIFIED,
            ),
            StoryFact(
                factId = "F_ENDING",
                proposition = "虚构作品结局：甲与乙分开",
                entityIds = listOf("E_A", "E_B"),
                severity = SpoilerSeverity.MAJOR,
                reveal = RevealBoundary(
                    episodeNumber = 3.0,
                    earliestMillis = 20 * MIN,
                    latestMillis = 20 * MIN + 40_000L,
                    precision = "minute",
                    evidence = evidence("fake-subtitle-ep03.vtt#00:20:00.000-00:20:40.000"),
                    verification = VerificationState.VERIFIED,
                ),
                evidence = evidence("fake-subtitle-ep03.vtt#00:20:00.000-00:20:40.000"),
                sourceId = "FIXTURE",
                verification = VerificationState.VERIFIED,
            ),
            StoryFact(
                factId = "F_UNKNOWN_TIME",
                proposition = "虚构未知时间事实",
                entityIds = listOf("E_A"),
                severity = SpoilerSeverity.MINOR,
                reveal = RevealBoundary(
                    episodeNumber = 2.0,
                    earliestMillis = null,
                    latestMillis = null,
                    precision = "episode-only",
                    evidence = evidence(),
                    verification = unknownVerification,
                ),
                evidence = evidence(),
                sourceId = "FIXTURE",
                verification = unknownVerification,
            ),
            StoryFact(
                factId = "F_UNVERIFIED",
                proposition = "虚构未核对事实",
                entityIds = listOf("E_A"),
                severity = SpoilerSeverity.MAJOR,
                reveal = RevealBoundary(
                    episodeNumber = 1.0,
                    earliestMillis = 1 * MIN,
                    latestMillis = 1 * MIN + 10_000L,
                    precision = "minute",
                    evidence = evidence("fake-subtitle-ep01.vtt#00:01:00.000-00:01:10.000"),
                    verification = VerificationState.AI_CANDIDATE,
                ),
                evidence = evidence("fake-subtitle-ep01.vtt#00:01:00.000-00:01:10.000"),
                sourceId = "FIXTURE",
                verification = VerificationState.AI_CANDIDATE,
            ),
            StoryFact(
                factId = "F_NEVER",
                proposition = "虚构未动画化事实",
                entityIds = listOf("E_B"),
                severity = SpoilerSeverity.MAJOR,
                reveal = RevealBoundary(
                    episodeNumber = 1.0,
                    earliestMillis = null,
                    latestMillis = null,
                    precision = "episode-only",
                    evidence = evidence("fake-staff-note.txt#not-animated"),
                    verification = VerificationState.VERIFIED,
                ),
                evidence = evidence("fake-staff-note.txt#not-animated"),
                sourceId = "FIXTURE",
                verification = VerificationState.VERIFIED,
                neverUnlocksInScope = true,
            ),
        )

        return StoryKnowledgePack(
            schemaVersion = 1,
            contentVersion = "fixture-1",
            work = StoryWork(
                workId = WORK_ID,
                title = "虚构测试作品",
                seasonLabel = "S1",
                coveredEpisodes = 1.0..3.0,
                episodes = listOf(
                    StoryEpisode(1.0, stableId = "ep1", displayLabel = "第1集"),
                    StoryEpisode(2.0, stableId = "ep2", displayLabel = "第2集"),
                    StoryEpisode(3.0, stableId = "ep3", displayLabel = "第3集"),
                ),
            ),
            facts = facts,
            entities = entities,
            baselineVersionLabel = "fixture-baseline",
            sourceNote = "仅测试用虚构数据",
        )
    }

    /** 已对齐：无偏移。 */
    val aligned: TimeAlignment = TimeAlignment.ConstantOffset(offsetMillis = 0L)

    /**
     * 已对齐但带 10 秒前置偏移（实际版本比基准多 10 秒片头）。
     * 即：基准时间 = 实际时间 − 10s
     */
    val alignedWithIntro: TimeAlignment = TimeAlignment.ConstantOffset(offsetMillis = -10_000L)

    /** 未对齐。 */
    val unaligned: TimeAlignment = TimeAlignment.Unaligned

    /**
     * 分段映射：实际 0–60s 对应基准 0–50s（删减），60–120s 对应 50–110s，
     * 120s 之后无映射（模拟未知剪辑区）。
     */
    val segmented: TimeAlignment = TimeAlignment.Segmented(
        segments = listOf(
            AlignmentSegment(0L, 60_000L, 0L, 50_000L),
            AlignmentSegment(60_000L, 120_000L, 50_000L, 110_000L),
        ),
    )
}
