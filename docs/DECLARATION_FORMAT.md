# 剧情包声明文件格式（`<workId>.facts.json`）

**状态：** 格式已实现并测试（`FactDeclarationFileTest`，19 例）
**最后更新：** 2026-09-19

---

## 1. 为什么分成两个文件

产包流程是**单向**的：

```
<workId>.facts.json   人手写：只写"人能确定的内容"（命题、证据锚）
        +
字幕文件（SRT / WebVTT）
        ↓  KnowledgePackGenerator.generateFromDeclarations
<workId>.json         工具写：包含推导出的揭晓时间，只读
```

分成两个文件的原因很简单：如果声明和时间写在同一个文件里，就会有人直接手改时间——
那样时间就**失去证据支撑**了。而本模块全部的保守性都建立在"时间必须指回某句台词"之上。

因此 `<workId>.json` 是**工具产物，不要手改**。

---

## 2. 顶层字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `declarationVersion` | 否 | 默认 1。**不要手改**，高于工具支持的版本会被拒绝 |
| `workId` | ✅ | 作品标识。必须等于文件名前缀，也等于 Bangumi 的 `subjectId`（如 `"10380"`）。不一致会报 `WORK_ID_MISMATCH` |
| `title` | ✅ | 标题，写入包便于人工核对 |
| `seasonLabel` | 否 | 季度标签，如 `"S1"` |
| `coveredEpisodesStart` / `coveredEpisodesEnd` | ✅ | 本文件**明确覆盖**的集序范围（含两端，允许小数）。起点 > 终点会报 `COVERED_RANGE_INVERTED` |
| `episodes` | 否 | 逐集信息；`stableId` 未知就留空，**不要伪造** |
| `entities` | 否 | 实体（人物/组织）及其别名 |
| `specials` | 否 | 特别篇与显式顺序映射 |
| `seasonIndex` | 否 | 系列内季序，默认 1 |
| `baselineVersionLabel` | 否 | 基准视频版本描述 |
| `sourceNote` | 否 | 来源与许可说明 |
| `contentVersion` | ✅ | 内容版本。**每次实质变更都要改它**（它参与缓存键与知识版本） |
| `facts` | ✅ | 事实声明列表。为空会报 `NO_FACTS` |

---

## 3. 事实声明（`facts[]`）

| 字段 | 必填 | 说明 |
|---|---|---|
| `factId` | ✅ | 稳定唯一标识。**改名等于换一条事实**，旧包里的引用会失效 |
| `proposition` | ✅ | 细粒度命题 |
| `entityIds` | 否 | 涉及实体，必须存在于 `entities` |
| `severity` | 否 | `MAJOR`（默认）/ `MINOR` 等 |
| `anchors` | 否 | 证据锚：`[{"episodeNumber": 1.0, "quote": "某句台词"}]` |
| `episodeNumberWhenTimingUnknown` | 否 | 有锚时忽略；无锚时用它表示"知道是哪一集，但时间未知" |
| `sourceId` | 否 | 证据来源标识（如字幕文件名） |
| `verification` | 否 | `VERIFIED` / `AI_CANDIDATE` / `SOURCED_UNVERIFIED` / `DISPUTED` / `UNKNOWN`。**只有 `VERIFIED` 能自动解锁** |
| `neverUnlocksInScope` | 否 | 有证据证明原作尚未动画化等 → 覆盖范围内始终受保护 |
| `extraEvidence` | 否 | 非字幕证据（剧本、访谈），会与字幕证据合并 |

### 关于 `proposition`：必须细粒度

身份、动机、关系、结局**分别建一条**。合并成一条"大事实"会让
"观众只知道了其中一部分"被当成"全都知道了"，从而**过早解锁**。

### 关于 `anchors.quote`：这是唯一决定时间的输入

- 工具会在该集字幕里做**归一化后的包含匹配**（去标签、折叠空白、忽略大小写）。
- **归一化后不足 6 字符的引文会被拒绝**：两三个字的引用几乎必然误命中，
  而误命中会把揭晓时间**提前**——那是会直接放行剧透的方向。
- 引文在整集出现**多于一次**也会被拒绝（无法确定哪一处是揭晓点）。
- 所以：**引文要足够长、且在该集内唯一**。多写几个字是值得的。

---

## 4. 常见错误与提示代码

产包时工具会报出这些问题（`ERROR` 级别表示必须修正）：

| 代码 | 含义 | 怎么修 |
|---|---|---|
| `WORK_ID_MISMATCH` | 文件名/`workId` 与期望不一致 | 改文件名或 `workId` |
| `DECLARATION_VERSION_TOO_NEW` | 声明版本高于工具支持 | 升级工具，或把版本改回 |
| `COVERED_RANGE_INVERTED` | 覆盖范围起点 > 终点 | 改回来 |
| `NO_FACTS` | 没有任何事实 | 空包没有意义 |
| `SUBTITLE_MISSING` | **有锚的那一集没有字幕** | 补字幕文件；这是最该看见的提示 |
| `QUOTE_TOO_SHORT` | 引文太短 | 引文写长一些 |
| `QUOTE_NOT_FOUND` | 字幕里找不到该引文 | 核对原文（标点/错别字/是否该集） |
| `QUOTE_AMBIGUOUS` | 引文在该集出现多次 | 引文写长一些，使其唯一 |
| `FACT_NOT_LOCATABLE` | 既无锚也无集号 | 补锚或补 `episodeNumberWhenTimingUnknown` |
| `DUPLICATE_DECLARATION` | `factId` 重复 | 改 id |
| `TIMING_UNKNOWN`（警告） | 时间保持未知 | 不影响使用：该事实**永远不会自动解锁** |

---

## 5. 最小示例

见 `docs/examples/fictional-sample.facts.json`（**虚构作品**，仅演示格式）。
字幕配套示例见 `docs/examples/fictional-sample.ep1.vtt`。

跑一遍（拿到字幕与声明后我会实际执行）：

```
# 1) 确认声明文件能读回
# 2) 逐集解析字幕
# 3) generateFromDeclarations
# 4) 检查 errors（ERROR 必须清零）与 warnings
# 5) 写出 <workId>.json 到 app/android/src/main/assets/story/
# 6) 重新构建 APK
```

---

## 6. 关于弹幕的说明（重要）

**弹幕不能用来推导揭晓时间。** 这两类数据是不同的东西：

| | 弹幕 | 带时间字幕 |
|---|---|---|
| 是什么 | 观众的**评论**（"前方高能"之类） | 作品**台词**的逐句时间码 |
| 能否证明"某条剧情事实已对观众公开" | **不能**：评论是二手转述，可能错、也可能提前 | **能**：台词本身就是揭晓时刻 |
| 本模块中的角色 | 被**过滤**的对象 | 推导揭晓时间的**证据** |

因此本模块没有、也不会提供"从弹幕自动生成剧情包"的功能。
