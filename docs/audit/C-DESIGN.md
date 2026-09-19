# C. 架构与设计（给外部审阅者）

本文件说明系统**怎么运作**，以及每个设计决定背后的理由与它放弃了什么。
所有文件路径都是真实路径，可在仓库中核对。

---

## C.1 一句话架构

```
弹幕来源(danmaku sources)
      │
      ▼
EpisodeDanmakuLoader ──► DanmakuCollection.at(progress, …) ──► DanmakuSession
      │                        （上游时间窗机制）
      ▼
danmakuEventFlow ──► 【DanmakuGuardSession.shouldDisplay】  ← 本项目插入点
      │                        │
      │                        ├── 用户配置（开关 + 档位）
      │                        ├── 剧情知识包 StoryKnowledgePack
      │                        ├── 片源对齐 TimeAlignment
      │                        └── 语义分析器（当前为 null）
      ▼
EpisodeViewModel.uiDanmakuEventFlow ──► PlayerDanmakuHost（覆盖层）
                                     └─► allDanmakuListFlow（弹幕列表）
```

**两个显示入口都已接入。** 全应用穷举确认只有这两个真实文字显示入口。

---

## C.2 为什么不用关键词过滤

常规敏感词过滤挡不住真实剧透，因为这些内容**不含任何敏感词**。实测样本（魔法少女小圆第 1 集，共 37 条弹幕）里就有：

> `"有老虚，不可能治愈"`

这句话一个字都没提剧情，但它把整部作品的**预期**改掉了。它之所以是剧透，只取决于
"观众此刻应该已经知道多少"——这是**时序判断**，不是词表判断。

因此系统的判定输入是三元组：

```
(这条弹幕说了什么) × (现在播到哪里) × (此时观众理应已经知道什么)
```

第三项就是**剧情知识包**。

---

## C.3 剧情知识包的数据结构

```kotlin
StoryKnowledgePack(
    work: StoryWork,                 // workId = 上游 subjectId
    contentVersion: String,
    entities: List<StoryEntity>,     // 实体与别名（含"歧义别名"）
    facts: List<StoryFact>,          // 人工声明的事实
    specials: List<SpecialEpisodeMapping>,
)

StoryFact(
    factId: String,
    proposition: String,             // 命题，由人写
    entityIds: List<String>,
    severity: SpoilerSeverity,       // MINOR / MAJOR
    reveal: RevealBoundary,          // 揭晓时间区间 + 证据
    neverUnlocksInScope: Boolean,
)

RevealBoundary(
    episodeNumber: Double,
    earliestMillis: Long?,
    latestMillis: Long?,             // 判定解锁用**上界**
    precision: String,               // "cue" / "cues(n)"，明确不是秒级精确
    evidence: List<EvidenceRef>,     // 证据可定位
)
```

### 关键设计：判定用**上界**

解锁用 `latestMillis`（最晚锚的 cue **结束**时间）而不是起点。
理由：早解锁 = 本该拦住的弹幕被放行，这个方向的错误不可接受；
晚解锁只是多拦几条。**所有取舍都朝同一方向：宁可保守，不可提前解锁。**

---

## C.4 揭晓时间怎么来的（为什么不写秒数）

写秒数是"凭感觉"，无法复核。所以流程是：

```
人手写的事实声明 (<workId>.facts.json)  +  带时间字幕 (SRT/WebVTT)
                    │
                    ▼
        RevealBoundaryExtractor
                    │  在字幕里做归一化后的**包含匹配**
                    ▼
        揭晓时间区间（最早锚起点 .. 最晚锚终点）
```

四条规则，每条都为了同一个方向：

1. 引用归一化后不足 6 个字符 → 拒绝（两三个字的引用几乎必然误命中，而误命中会把揭晓时间**提前**）。
2. 引用在整集出现**多于一次** → 拒绝（无法确定哪处是揭晓点，猜错方向会提前）。
3. 所有锚都必须命中；任一找不到 → 整条事实报失败，**不产出"部分时间"**。
4. 找不到就保持**时间未知**，绝不编一个时间。

**工具不推断剧情。** 命题（"谁是凶手""谁死了"）必须由人写。
工具只做"把命题绑定到可定位的台词证据，并推导时间边界"。

---

## C.5 片源对齐（同一部片、不同版本、时间轴不同）

不同片源（TV 放送版 / BD 版 / 不同字幕组）片头长度不同，弹幕时间轴整体偏移。
所以需要一层映射：

```
实际播放位置 ──[TimeAlignment]──► 基准时间轴 ──► 与知识包比较
```

`TimeAlignment` 有三种形态：

| 形态 | 含义 |
|---|---|
| `Unaligned` | 未对齐（默认） |
| `ConstantOffset(offsetMillis)` | 整体恒定偏移 |
| `Segmented(segments)` | 分段映射（片源被剪辑过） |

生成方式：按**台词文本**配对两版字幕 → 得到偏移样本 → 取中位数作常量偏移 →
若 90 分位残差超限，则按**偏移阶跃**切分（不是端点连线）→ 都不行就**明确拒绝**。

关键约束：**拟合不到就拒绝，永不退化成"偏移 0"**。
因为"偏移 0"看起来像成功，实际会让所有时间判断错位。

---

## C.6 三档策略

档位只影响**阈值**与**余量**，不影响判定逻辑本身。

| 档位 | 内容违规阈值 | 明确剧透 | 暗示剧透 | 揭晓后余量 | 未知时间事实门槛 |
|---|---|---|---|---|---|
| 宽松 | 0.80 | 0.85 | 0.95 | 0 s | 0.95 |
| **均衡（默认）** | 0.60 | 0.60 | 0.80 | 15 s | 0.80 |
| 严格 | 0.35 | 0.35 | 0.55 | 30 s | 0.55 |

单调性是被测试钉住的：**宽松 ⊆ 均衡 ⊆ 严格**（屏蔽集合），
且三档**两两可区分**（若均衡与严格行为相同，嵌套照样成立但用户少一档），
且方向正确（更严格档位不得放行较宽松档位已屏蔽的内容）。

> ⚠️ 这些数字**不是概率**，也**不是经过校准的最优值**。没有任何真实标注数据支撑它们。

---

## C.7 语义分析器接口（当前未实现）

判定需要知道"这条弹幕是否在谈论某条事实"。这是语义分析器的工作。

接口是**可取消的挂起实现**，且明确要求：

- **不得跑在 UI/渲染线程**
- 失败必须上报（`analysisFailed` + `failureReason`），不得静默返回"无剧透"
- 分析器版本变化（模型/归一化/分词器任一）**必须**改变 `AnalyzerVersion`，
  否则会复用旧管线的缓存结论

**当前状态：`semanticsProvider` 返回 null。** 因此：

```
analysisFailed == false 且 语义为"无" ⇒ 判定为"允许显示"
```

也就是说，**现在打开开关不会屏蔽任何弹幕**。

---

## C.8 两级缓存（为什么不是一层）

缓存键的完整度不同，可复用范围就不同：

| 层 | 键 | 可复用范围 |
|---|---|---|
| 通用分类层 | 文本指纹 + 分析器版本 | **可跨作品、跨集**（"这句话是否是脏话"与剧情无关） |
| 事实关系层 | 上述 + workId + 集数 + 资料版本 + 语义窗口 | **仅限同一作品同一集同一资料版本** |

规则：**键不完整就不缓存**（不用猜的版本号建键）。

切集时：`evictFactRelations()` **只清事实关系层**，保留通用层。
早期版本清整个缓存，等于白白重算。

---

## C.9 集序映射（第一个真实接线缺陷）

上游有两套编号：

- `ep`：**当季**集数（第 2 季第 1 集 = `01`）
- `sort`：**系列**集数（同一集 = `26`）

知识库按观众数的集数编号，所以必须取 `ep`。
原先的实现直接读 `ep ?: sort` 的数值，混了两件事，两个方向都错：

- 取错编号 → 第 1 季早已揭晓的事实被当成"已公开" → **放行**第 2 季的弹幕
- 特别篇（OVA/SP/OP/ED）的编号被当成正片集数 → OVA 被当作"第 7 集"

修复：新增适配层 `LocalGuardEpisodeOrder`，规则：

| 上游形态 | 结果 |
|---|---|
| `Normal(n)` | `Numbered(n, partial = isPartial)` |
| `Special(MainStory, n)` | `Numbered(n)` —— 类别是正片，只是排序标记特殊 |
| `Special(其他, n)` | `Special(type, n)` → 需知识包显式声明位置，否则**不可映射** |
| `Unknown(raw)` | `Unknown(raw)` —— 保留原文不猜测 |

另有两个容易漏掉的形态：
- `EpisodeInfo.sort` 的**默认值是 `EpisodeSort("")`**（字段存在但内容为空），
  既不是 null（判空挡不住）也不是数字（解析必然失败），必须干净地落到未知。
- 特别篇要真正映射到集序，还需要把**已装载知识包里的 `specials` 列表**传进适配层。
  **这一点目前仍未接**，所以特别篇一律降级为"集数未知"。

---

## C.10 状态与降级（面向用户）

状态行**由客观能力推导，不由用户开关决定**。例如开关打开但资料缺失时，
状态必须是"资料缺失"，而不是"已启用"。

状态机：

```
OFF ──(打开开关)──► MODEL_FAILURE?  ──► KNOWLEDGE_MISSING?
                 └─► ALIGNMENT_UNVERIFIED?
                 └─► RULE_PROTOTYPE（原型：规则+时间线，无模型）
                 └─► TIMELINE_VERIFIED（有模型 + 资料已验证）
```

界面上同时显示降级项列表：`集序未知 / 资料缺失 / 对齐未验证 / 未接入模型`。

诊断明细（可展开）暴露逐条计数：`evaluated / visible / blockedContent /
blockedSpoiler / bypassedOff / bypassedNoKnowledge / deferredTimeout / failed`。

**为什么这些计数必须可见**：若 `evaluated` 一直是 0，说明判定路径根本没被走到。
这是唯一能在没有剧情包、没有模型的情况下独立验证"链路是否真的通了"的手段。

状态结构里**没有**任何剧情字段（不含事实正文、不含角色死亡统计），
且有测试逐行校验其形式——因为状态行本身不能泄露剧情。

---

## C.11 界面

播放页 → 弹幕设置 → 「本地弹幕过滤（非官方）」：

- 总开关（**默认关闭**）
- 三档选择
- 状态行（含降级项）
- 可展开的统计明细
- 原型阶段提示："尚未内置 AI 模型"

文案 11 条 × 7 个语言目录（`values` / `values-zh` / `-zh-rCN` / `-zh-rHK` / `-zh-rMO` / `-zh-rSG` / `-zh-rTW`）。

---

## C.12 已知偏差与设计取舍（审阅重点）

| # | 取舍 | 当前选择 | 放弃了什么 |
|---|---|---|---|
| C12-1 | 集数未知 / 资料缺失时 | **放行**（fail-open） | 与"宁可保守"原则冲突，见 D-2 |
| C12-2 | 语义窗口默认值 | 等于判定时刻 | 理想值应按弹幕**自身**的播放时刻建窗，但本模块拿不到该值，窗口偏宽 |
| C12-3 | 弹幕源自身的偏移 | **未使用** | 上游 `DandanplayEpisode.shift` 字段（表示该源弹幕应整体提前/延后多少秒）**从未被上游使用**，本项目也没接。目前只能靠人工生成的对齐文件 |
| C12-4 | 弹幕 ≠ 剧透证据 | 严格分离 | 弹幕是观众评论（二手转述，可能错或提前），只能作为**被过滤对象**；揭晓时间必须由台词推导 |
| C12-5 | 未知事实的降级门槛 | 按档位单调下降 | 这是可测参数，不是校准值 |
| C12-6 | 特别篇映射 | 降级为未知 | 安全方向，但在接入 `specials` 之前，特别篇得不到保护 |

---

## C.13 模块与文件清单

### 纯逻辑核心 `danmaku/localguard/`

```
policy/
  GuardTypes.kt            GuardTier / GuardCategory / SpoilerSeverity
  TierPolicy.kt            DanmakuSemantics / TierPolicy / GuardDecision / SpoilerBlockReason
  DanmakuGuardEngine.kt    PlaybackPosition / TimelineEvaluator / DanmakuGuardEngine
  DanmakuGuardSession.kt   GuardRequest / GuardCounters / 会话入口
  GuardUserConfig.kt       GuardUserConfig / GuardFeatureState / GuardConfigSource
  GuardStatus.kt           GuardStatus / deriveGuardStatus
cache/
  SemanticsCache.kt        两级键 / AnalyzerVersion / KnowledgeVersion / TextFingerprint
knowledge/
  StoryKnowledge.kt        知识包数据模型 / TimeAlignment
  KnowledgePackValidator.kt
  KnowledgePackSource.kt   装载链路（Loaded/Unusable/Absent）
  TextStoryKnowledgeSource.kt
  KnowledgePackGenerator.kt  声明文件 → 剧情包
  AlignmentGeneration.kt     两版字幕 → 对齐
  AlignmentResolver.kt
  RevealBoundaryExtractor.kt 引文 → 揭晓时间
  SubtitleCues.kt            WebVTT/SRT 解析
  EpisodeOrder.kt            集序投影与映射
```

### 接入点

| 文件 | 改动 |
|---|---|
| `app/shared/src/commonMain/kotlin/ui/subject/episode/EpisodeViewModel.kt` | 守卫接线、会话创建、状态流、两个显示入口过滤 |
| `app/shared/src/commonMain/kotlin/data/repository/danmaku/GuardConfigRepository.kt` | DataStore 持久化 |
| `app/shared/src/commonMain/kotlin/data/repository/danmaku/LocalGuardEpisodeOrder.kt` | 集序适配 |
| `app/shared/src/commonMain/kotlin/data/repository/danmaku/AssetStoryPackTextReader.kt` | 从 assets 读剧情包 |
| `app/shared/src/commonMain/kotlin/ui/subject/episode/video/settings/EpisodeVideoSettings.kt` | 设置界面分组 |
| `app/shared/application/src/commonMain/kotlin/platform/CommonKoinModule.kt` | DI 注册 |
| `app/android/build.gradle.kts` | release 变体包名后缀（本轮新增） |

### 测试（19 类，292 例）

```
Fixtures                      GuardPolicyTest              TierPolicyTest
TierMonotonicityPropertyTest  DanmakuGuardEngineTest       AlignmentTest
EpisodeOrderTest              DanmakuGuardSessionTest      GuardStatusTest
SemanticsCacheTest            GuardSessionCacheTest        DuplicateCallbackTest
KnowledgePackValidatorTest    KnowledgePackSerializationTest
KnowledgePackSourceTest       TextStoryKnowledgeSourceTest
SubtitleAndBoundaryTest       AlignmentGenerationTest      KnowledgePackGeneratorTest
PipelineEndToEndTest          FactDeclarationFileTest
```
