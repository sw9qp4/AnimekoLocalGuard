/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/animeko
 */

package me.him188.ani.danmaku.localguard

import me.him188.ani.danmaku.localguard.knowledge.StoryKnowledgePack
import me.him188.ani.danmaku.localguard.knowledge.TimeAlignment
import me.him188.ani.danmaku.localguard.knowledge.VerificationState
import me.him188.ani.danmaku.localguard.policy.DanmakuGuardEngine
import me.him188.ani.danmaku.localguard.policy.DanmakuSemantics
import me.him188.ani.danmaku.localguard.policy.GuardCategory
import me.him188.ani.danmaku.localguard.policy.GuardDecision
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.PlaybackPosition
import me.him188.ani.danmaku.localguard.policy.TierPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIN = 60_000L

/**
 * 三档策略的**属性测试**：验证单调嵌套。
 *
 * 总任务说明第 6 节要求：
 * ```
 * 同一输入与上下文中必须满足：宽松屏蔽集合 ⊆ 均衡屏蔽集合 ⊆ 严格屏蔽集合
 * ```
 *
 * 这里用**有限域穷举**代替随机生成（确定性、可复现、失败时可直接给出反例）。
 * 穷举域刻意覆盖会导致"非单调"的边界组合：
 * - 阈值附近（分数恰好等于某个档位的阈值）
 * - 揭晓边界 + 余量附近（各档余量不同 → 解锁时刻不同）
 * - 对齐失败 / 未映射区段（不同档位降级策略不同）
 */
class TierMonotonicityPropertyTest {

    private val pack: StoryKnowledgePack = Fixtures.pack()

    /** 分数采样：0 到 1，步长 0.01，覆盖各档阈值附近。 */
    private val scoreGrid: List<Double> = (0..100).map { it / 100.0 }

    /**
     * 位置采样：以第 1 集 F_IDENTITY 的揭晓上界（5:20）为中心，
     * 覆盖 -60s ~ +60s，步长 1s —— 足以覆盖 0s/15s/30s 三个余量的解锁时刻。
     */
    private val positionGrid: List<Long> =
        (-60..60).map { 5 * MIN + 20_000L + it * 1_000L }.filter { it >= 0 }

    private val episodeGrid: List<Double> = listOf(1.0, 2.0, 3.0)

    private val alignments: Map<String, TimeAlignment> = mapOf(
        "aligned" to Fixtures.aligned,
        "alignedWithIntro" to Fixtures.alignedWithIntro,
        "unaligned" to Fixtures.unaligned,
        "segmented" to Fixtures.segmented,
    )

    private data class Input(
        val label: String,
        val semantics: DanmakuSemantics,
        val position: PlaybackPosition,
        val alignment: TimeAlignment,
    )

    private fun buildInputs(): List<Input> {
        val inputs = mutableListOf<Input>()

        // 1) 剧透类别 × 分数网格 × 位置网格 × 对齐方式
        for (category in listOf(GuardCategory.SPOILER_EXPLICIT, GuardCategory.SPOILER_HINT)) {
            for (score in scoreGrid) {
                for (position in positionGrid) {
                    for ((alignName, alignment) in alignments) {
                        inputs += Input(
                            label = "$category@$score ep1@${position}ms/$alignName",
                            semantics = DanmakuSemantics(
                                scores = mapOf(category to score),
                                factIds = setOf("F_IDENTITY"),
                            ),
                            position = PlaybackPosition(1.0, position),
                            alignment = alignment,
                        )
                    }
                }
            }
        }

        // 2) 跨集：第 3 集事实 × 三个当前集
        for (episode in episodeGrid) {
            for (score in listOf(0.2, 0.5, 0.6, 0.8, 0.9, 1.0)) {
                inputs += Input(
                    label = "F_ENDING@$score curEp=$episode",
                    semantics = DanmakuSemantics(
                        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to score),
                        factIds = setOf("F_ENDING"),
                    ),
                    position = PlaybackPosition(episode, 0L),
                    alignment = Fixtures.aligned,
                )
            }
        }

        // 3) 多事实（一项已解锁 + 一项未解锁）
        for (score in listOf(0.3, 0.6, 0.9, 1.0)) {
            inputs += Input(
                label = "multi@$score",
                semantics = DanmakuSemantics(
                    scores = mapOf(GuardCategory.SPOILER_EXPLICIT to score),
                    factIds = setOf("F_IDENTITY", "F_ENDING"),
                ),
                position = PlaybackPosition(2.0, 30 * MIN),
                alignment = Fixtures.aligned,
            )
        }

        // 4) 内容维度（与时间无关）
        for (category in listOf(GuardCategory.EXPLICIT_OR_HARASSMENT, GuardCategory.HOSTILE_ATTACK)) {
            for (score in scoreGrid) {
                inputs += Input(
                    label = "$category@$score",
                    semantics = DanmakuSemantics(scores = mapOf(category to score)),
                    position = PlaybackPosition(1.0, 0L),
                    alignment = Fixtures.aligned,
                )
            }
        }

        // 5) 无事实的剧透信号
        for (score in scoreGrid) {
            inputs += Input(
                label = "noFact@$score",
                semantics = DanmakuSemantics(
                    scores = mapOf(GuardCategory.SPOILER_EXPLICIT to score),
                    factIds = emptySet(),
                ),
                position = PlaybackPosition(1.0, 0L),
                alignment = Fixtures.aligned,
            )
        }

        // 6) 未知时间 / 未核对 / 永不解锁
        for (factId in listOf("F_UNKNOWN_TIME", "F_UNVERIFIED", "F_NEVER")) {
            for (score in listOf(0.3, 0.6, 0.9, 1.0)) {
                inputs += Input(
                    label = "$factId@$score",
                    semantics = DanmakuSemantics(
                        scores = mapOf(GuardCategory.SPOILER_EXPLICIT to score),
                        factIds = setOf(factId),
                    ),
                    position = PlaybackPosition(3.0, 999 * MIN),
                    alignment = Fixtures.aligned,
                )
            }
        }

        // 7) 正常内容 / 猜测：任何档位都不应屏蔽
        inputs += Input(
            label = "normal",
            semantics = DanmakuSemantics(
                scores = mapOf(GuardCategory.NORMAL to 1.0, GuardCategory.CRITICISM to 1.0),
            ),
            position = PlaybackPosition(1.0, 0L),
            alignment = Fixtures.aligned,
        )
        inputs += Input(
            label = "speculation",
            semantics = DanmakuSemantics(scores = mapOf(GuardCategory.SPECULATION to 1.0)),
            position = PlaybackPosition(1.0, 0L),
            alignment = Fixtures.aligned,
        )

        return inputs
    }

    private fun blocked(tier: GuardTier, input: Input, pack: StoryKnowledgePack): Boolean {
        val engine = DanmakuGuardEngine(pack, input.alignment)
        val decision = engine.decide(input.semantics, input.position, TierPolicy.of(tier))
        return decision !is GuardDecision.Visible
    }

    /**
     * 核心属性：宽松 ⊆ 均衡 ⊆ 严格。
     *
     * 失败信息包含完整反例（档位、输入标签、各档判定），便于定位。
     */
    private fun assertMonotone(pack: StoryKnowledgePack, caseLabel: String) {
        val failures = mutableListOf<String>()
        var checked = 0

        for (input in buildInputs()) {
            checked++
            val lenient = blocked(GuardTier.LENIENT, input, pack)
            val balanced = blocked(GuardTier.BALANCED, input, pack)
            val strict = blocked(GuardTier.STRICT, input, pack)

            if (lenient && !balanced) {
                failures += "LENIENT⊄BALANCED: ${caseLabel} | ${input.label}"
            }
            if (balanced && !strict) {
                failures += "BALANCED⊄STRICT: ${caseLabel} | ${input.label}"
            }
            if (lenient && !strict) {
                failures += "LENIENT⊄STRICT: ${caseLabel} | ${input.label}"
            }
        }

        assertTrue(checked > 1000, "属性测试输入规模过小（$checked），覆盖不足")
        assertTrue(
            failures.isEmpty(),
            "三档单调嵌套被破坏（$caseLabel），共 ${failures.size} 例，前 10 例:\n" +
                failures.take(10).joinToString("\n"),
        )
    }

    @Test
    fun tierBlockingSetsAreNested() {
        assertMonotone(pack, "default")
    }

    @Test
    fun nestedEvenWhenRevealVerificationIsWeak() {
        // 核对状态不足时所有档位都不得自动解锁 —— 嵌套仍须成立
        val weak = Fixtures.pack(
            identityVerification = VerificationState.AI_CANDIDATE,
            unknownVerification = VerificationState.UNKNOWN,
        )
        assertMonotone(weak, "weak-verification")
    }

    @Test
    fun nestedWhenRevealIsVerified() {
        val verified = Fixtures.pack(
            identityVerification = VerificationState.VERIFIED,
            unknownVerification = VerificationState.VERIFIED,
        )
        assertMonotone(verified, "verified")
    }

    // ---------- 非嵌套相关的补充断言 ----------

    @Test
    fun normalContentIsNeverBlockedInAnyTier() {
        val engine = DanmakuGuardEngine(pack, Fixtures.aligned)
        val normal = DanmakuSemantics(
            scores = mapOf(
                GuardCategory.NORMAL to 1.0,
                GuardCategory.CRITICISM to 1.0,
                GuardCategory.SPECULATION to 1.0,
            ),
        )
        for (tier in GuardTier.entries) {
            assertEquals(
                GuardDecision.Visible,
                engine.decide(normal, PlaybackPosition(1.0, 0L), TierPolicy.of(tier)),
                "正常内容在任何档位都必须可见（$tier）",
            )
        }
    }

    @Test
    fun strictBlocksAtLeastAsMuchAsLenientOnEverySampledInput() {
        val engineByAlignment = alignments.mapValues { (_, a) -> DanmakuGuardEngine(pack, a) }
        var strictExtra = 0
        for (input in buildInputs()) {
            val engine = engineByAlignment.getValue(alignments.keys.first { k -> alignments[k] === input.alignment })
            val lenient = engine.decide(input.semantics, input.position, TierPolicy.LENIENT)
            val strict = engine.decide(input.semantics, input.position, TierPolicy.STRICT)
            if (lenient is GuardDecision.Visible && strict !is GuardDecision.Visible) strictExtra++
        }
        assertTrue(strictExtra > 0, "严格档应当在样本中确实比宽松档多屏蔽一些（否则三档无差别）")
    }

    // ---------- 三档两两可区分 ----------
    //
    // 上面那条只保证"严格比宽松多屏蔽"，**允许均衡与严格完全相同**。
    // 若实现把三档当成两个（甚至一个）在跑，嵌套断言照样成立，用户却少了一档。
    // 因此这里对**每一对**档位都要求存在可区分的输入。

    private fun countDistinguishingCases(a: GuardTier, b: GuardTier): Pair<Int, String?> {
        var differ = 0
        var example: String? = null
        for (input in buildInputs()) {
            val engine = DanmakuGuardEngine(pack, input.alignment)
            val da = engine.decide(input.semantics, input.position, TierPolicy.of(a))
            val db = engine.decide(input.semantics, input.position, TierPolicy.of(b))
            val blockedA = da !is GuardDecision.Visible
            val blockedB = db !is GuardDecision.Visible
            if (blockedA != blockedB) {
                differ++
                if (example == null) example = "${input.label}: $a blocked=$blockedA, $b blocked=$blockedB"
            }
        }
        return differ to example
    }

    @Test
    fun lenientAndBalancedAreDistinguishable() {
        val (differ, example) = countDistinguishingCases(GuardTier.LENIENT, GuardTier.BALANCED)
        assertTrue(
            differ > 0,
            "宽松档与均衡档必须在某些输入上给出不同结论，否则实际只有两档",
        )
        assertTrue(example != null)
    }

    @Test
    fun balancedAndStrictAreDistinguishable() {
        val (differ, example) = countDistinguishingCases(GuardTier.BALANCED, GuardTier.STRICT)
        assertTrue(
            differ > 0,
            "均衡档与严格档必须在某些输入上给出不同结论，否则严格档形同虚设",
        )
        assertTrue(example != null)
    }

    @Test
    fun lenientAndStrictAreDistinguishable() {
        val (differ, _) = countDistinguishingCases(GuardTier.LENIENT, GuardTier.STRICT)
        assertTrue(differ > 0, "宽松档与严格档必须可区分")
    }

    @Test
    fun distinctnessAlwaysRunsInTheMonotoneDirection() {
        // 可区分性还必须**方向正确**：严格档只会多屏蔽，不会反而放行。
        // 否则"可区分"可以靠一个方向相反的缺陷来满足。
        for ((lower, higher) in listOf(
            GuardTier.LENIENT to GuardTier.BALANCED,
            GuardTier.BALANCED to GuardTier.STRICT,
            GuardTier.LENIENT to GuardTier.STRICT,
        )) {
            for (input in buildInputs()) {
                val engine = DanmakuGuardEngine(pack, input.alignment)
                val low = engine.decide(input.semantics, input.position, TierPolicy.of(lower))
                val high = engine.decide(input.semantics, input.position, TierPolicy.of(higher))
                if (low !is GuardDecision.Visible) {
                    assertTrue(
                        high !is GuardDecision.Visible,
                        "更严格的档位不得放行较宽松档位已屏蔽的内容：$lower → $higher @ ${input.label}",
                    )
                }
            }
        }
    }
}
