# 接入说明（docs/INTEGRATION.md）

**目标构建**：`open-ani/animeko` @ `main`，锁定提交 **`28ec14aca0a2b001f2ce1f6dd1b68acd44a14f67`**
（2026-09-18 13:04:32 +0800），AGPL-3.0，无本地修改。

> **证据等级说明**
> 本文全部内容为 `STATIC_EVIDENCE`：通过**阅读上游源码**得到，记录了实际文件路径、类名与签名。
> **不含任何运行时验证**（尚未构建、尚未在设备上运行）。不得把本文当作"接入已完成"。

---

## 1. 结论速览

已定位**三个候选接入点**，均位于 Animeko 自身代码中（非混淆、非注入）：

| # | 位置 | 类型 | 作用 |
|---|---|---|---|
| P1 | `danmaku/api/.../DanmakuCollection.kt` | 会话级 | 弹幕按播放进度匹配 → `DanmakuEvent`。唯一的"决定是否发送"的地方 |
| P2 | `app/shared/app-data/.../EpisodeDanmakuLoader.kt` | 加载器级 | 持有播放位置、播放速度、既有正则过滤；产出 `danmakuEventFlow` |
| P3 | `app/shared/.../EpisodeViewModel.kt:662-694` | **显示转换级** | `DanmakuEvent` → `UIDanmakuEvent`。**最后一次能在显示前丢弃条目的地方** |

**推荐主接入点：P3**（理由见第 5 节），**P1 作为会话/进度真值来源**。

---

## 2. 数据模型（STATIC_EVIDENCE）

### 2.1 `DanmakuInfo` —— `danmaku/api/src/commonMain/kotlin/DanmakuInfo.kt`

```kotlin
data class DanmakuInfo(
    val id: String,                    // 条目稳定标识
    val serviceId: DanmakuServiceId,   // 弹幕源
    val senderId: String,              // 发送者标识（不得写入日志）
    val content: DanmakuContent,
) {
    val playTimeMillis get() = content.playTimeMillis
    val color get() = content.color
    val text get() = content.text
    val location get() = content.location
}

@Serializable
data class DanmakuContent(
    val playTimeMillis: Long,          // 出现时间，毫秒（相对视频）
    val color: Int,                    // RGB
    val text: String,                  // 弹幕文本
    val location: DanmakuLocation,
)

enum class DanmakuLocation { TOP, BOTTOM, NORMAL }
```

**要点：**

- `playTimeMillis` 是**弹幕自身在视频时间轴上的位置**（毫秒），这正是时间策略需要的输入。
- `location` 只有三档（顶部／底部／滚动），下游必须原样保留。
- **`serviceId` 是多来源合并的关键**：弹幕可能来自 Animeko／AcFun／Baha／Bilibili／Dandanplay／Tucao，
  AI 过滤必须作用于**全部来源**，不能只覆盖其中一个。
- `senderId` 属个人信息，**不得进入日志**。

### 2.2 `DanmakuServiceId`（同上文件）

`value class DanmakuServiceId(val value: String)`，已知常量：
`Animeko`、`Acfun`、`Baha`、`Bilibili`、`Dandanplay`、`Tucao`。

---

## 3. P1 会话级接入点（STATIC_EVIDENCE）

### 3.1 `DanmakuCollection.at(...)` —— `danmaku/api/.../DanmakuCollection.kt`

```kotlin
interface DanmakuCollection {
    val totalCount: Flow<Int?> get() = emptyFlow()

    fun at(
        progress: Flow<Duration>,                       // 播放进度流
        playbackSpeed: () -> Float,                     // 当前倍速
        danmakuRegexFilterList: Flow<List<String>>,     // 既有用户正则过滤
    ): DanmakuSession
}
```

### 3.2 事件只有两种（**两条都必须过时间策略**）

```kotlin
sealed class DanmakuEvent {
    class Add(val danmaku: DanmakuInfo) : DanmakuEvent()

    data class Repopulate(
        val list: List<DanmakuInfo>,   // 顺序：由距当前时间近到远
        val playTimeMillis: Long,      // 事件产生时的播放器时间
    ) : DanmakuEvent()
}
```

- `Add`：正常播放中逐条推送。
- `Repopulate`：**快进/快退/换源/列表更新时重建整个屏幕**。
  → 若只在 `Add` 上做过滤，**拖动进度条就能绕过过滤**。这是必须同时覆盖两条路径的原因。

### 3.3 会话实现

- `TimeBasedDanmakuSession.create(listFlow, coroutineContext)`：
  构造时对每条执行 `DanmakuSanitizer.sanitize(it)` 并按 `playTimeMillis` **排序**。
- `TimeBasedDanmakuSession.filterList(list, danmakuRegexFilterList)`：
  **既有正则过滤**（`RegexOption.IGNORE_CASE`），语义是"任一正则命中则丢弃"。
  → AI **不得重新放出**这里已被丢弃的条目。
- `DanmakuSessionAlgorithm.tick(sendEvent)`：每 50ms（`delay(1000/20)`）检查一次；
  位移超过 `repopulateThreshold` 时重建屏幕（`repopulateDistance = 20.seconds`，
  `repopulateMaxCount = Int.MAX_VALUE`）。
- 状态：`DanmakuSessionFlowState.curTimeShared`（`@Volatile`，当前播放进度）。

### 3.4 进度真值

`danmaku/api/.../DanmakuSession.kt` 定义了 `DanmakuSession`（`events` / `requestRepopulate()`）。
**进度来自 `player.currentPositionMillis`**（媒体位置），不是墙上时钟 —— 符合总任务说明第 8.3 条要求。

---

## 4. P2 加载器级接入点（STATIC_EVIDENCE）

`app/shared/app-data/src/commonMain/kotlin/domain/episode/EpisodeDanmakuLoader.kt`

```kotlin
private val config = MutableStateFlow(persistentMapOf<DanmakuServiceId, DanmakuOriginConfig>())
val configFlow = config.asStateFlow()

private val danmakuSessionFlow: Flow<DanmakuSession> = config.mapLatest { configMap ->
    createDanmakuCollection(danmakuLoader.fetchResultFlow, configMap).at(
        progress = player.currentPositionMillis.map { it.milliseconds },   // ← 媒体位置
        playbackSpeed = { player.features[PlaybackSpeed]?.value ?: 1f },
        danmakuRegexFilterList = getDanmakuRegexFilterListFlowUseCase(),   // ← 既有过滤
    )
}.shareIn(flowScope, started = sharingStarted, replay = 1)

val danmakuEventFlow: Flow<DanmakuEvent> = danmakuSessionFlow.flatMapLatest { it.events }

suspend fun requestRepopulate() { danmakuSessionFlow.first().requestRepopulate() }

fun setEnabled(serviceId: DanmakuServiceId, enabled: Boolean)      // 弹幕源开关
fun setShiftMillis(serviceId: DanmakuServiceId, shiftMillis: Long) // ← 时间轴偏移
```

**要点：**

- `config`（每个弹幕源的 enabled／shiftMillis）变化会**重建整个 `DanmakuSession`**。
  → 用户的 generation 概念在这里有天然触发点。
- `setShiftMillis` 是**弹幕源时间到实际显示时间的映射**，与技术说明第 8.6 条要求分开维护的
  "弹幕来源时间→实际显示时间"直接对应。
- 另有 `DanmakuLoader`（`app/shared/app-data/.../domain/danmaku/DanmakuLoader.kt`）负责
  网络获取与缓存，注释提到"弹幕源开关/时间轴导致 DanmakuSession 重建"时会清空状态。

**多来源合并**发生在这里：`danmakuListFlow.map { it?.flatMap { ... } }`，
每个 `DanmakuFetchResult` 按 `matchInfo.serviceId` 取配置后合并。

---

## 5. P3 显示转换级接入点（**推荐主接入点**）

`app/shared/src/commonMain/kotlin/ui/subject/episode/EpisodeViewModel.kt:662-694`

```kotlin
val uiDanmakuEventFlow = danmakuRepository.selfId.flatMapLatest { selfId ->
    fun createDanmakuPresentation(data: DanmakuInfo, selfId: String?) =
        DanmakuPresentation(data, isSelf = selfId == data.senderId)

    episodeDanmakuLoader.danmakuEventFlow.mapNotNull { event ->
        when (event) {
            is DanmakuEvent.Add -> {
                val data = event.danmaku
                if (data.text.isBlank()) null                                  // ← 既有丢弃
                else UIDanmakuEvent.Add(createDanmakuPresentation(data, selfId))
            }
            is DanmakuEvent.Repopulate -> {
                UIDanmakuEvent.Repopulate(
                    event.list
                        .filter { it.text.any { c -> !c.isWhitespace() } }     // ← 既有丢弃
                        .map { createDanmakuPresentation(it, selfId) },
                    withContext(Dispatchers.Main) { player.currentPositionMillis.value },
                )
            }
        }
    }
}.shareInBackground(started = SharingStarted.WhileSubscribed(5000), replay = 1)
```

**为什么推荐 P3 作为主拦截点：**

1. **它是显示前的最后一道**：下游 `PlayerDanmakuHost` 直接把事件灌进 `DanmakuHostState`。
   在此之后没有任何可判定的位置。
2. **两条事件路径在同一处收口**（`Add` 与 `Repopulate`），只需实现一次过滤，
   天然覆盖"拖动进度条"的绕过路径。
3. **`Add` 分支已可见 `event.danmaku.playTimeMillis`**；`Repopulate` 分支显式解析出
   `player.currentPositionMillis` 作为基准位置。
4. **可以在同一处读到播放器真实位置**，满足"按当前播放位置决定，而非墙上时钟"。
5. 已有 `mapNotNull` + `filter` 的丢弃语义，**AI 过滤只是再加一层同类语义**，
   不改变原有流程形状，改动面小。

**注意（已识别，需在实现时处理）：**

- `Repopulate` 分支用 `withContext(Dispatchers.Main)` 读位置。AI 判定**不得阻塞主线程**，
  因此过滤要么是纯内存同步判定，要么改为在 `Dispatchers.Default` 上带超时地判定，
  并把位置读取与判定放在同一挂起点内、保持与 `event.playTimeMillis` 的语义一致。
  具体做法在 G1 实现时确定并写测试。
- `episodeDanmakuLoader.danmakuEventFlow` 的 `Repopulate` 事件**自带** `playTimeMillis`
  （产生该事件时的播放器时间）。实现时应**优先使用事件自带的位置**，而不是重新读取，
  以避免"判定时刻"与"事件时刻"不一致导致解锁提前或滞后。

---

## 6. 下游显示端（**只在有明确理由时才改**）

`app/shared/src/commonMain/kotlin/ui/danmaku/PlayerDanmakuHost.kt`

```kotlin
sealed class UIDanmakuEvent {
    data class Add(val presentation: DanmakuPresentation) : UIDanmakuEvent()
    data class Repopulate(
        val list: List<DanmakuPresentation>,
        val currentPositionMillis: Long,
    ) : UIDanmakuEvent()
}

@Composable
fun PlayerDanmakuHost(
    player: MediampPlayer,
    danmakuHostState: DanmakuHostState,
    danmakuEvent: Flow<UIDanmakuEvent>,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(player, danmakuHostState) {
        player.state.collect { danmakuHostState.setPaused(!it.isPlaying) }
    }
    LaunchedEffect(danmakuEvent, danmakuHostState) {
        danmakuEvent.collect { event ->
            when (event) {
                is UIDanmakuEvent.Add -> danmakuHostState.trySend(event.presentation)
                is UIDanmakuEvent.Repopulate -> danmakuHostState.repopulate(event.list, event.currentPositionMillis)
            }
        }
    }
    DanmakuHost(danmakuHostState, modifier)
}
```

调用点：`EpisodePage.kt:1049` — `PlayerDanmakuHost(vm.player, danmakuHostState, vm.uiDanmakuEventFlow)`

**`DanmakuHostState.trySend` / `repopulate` 是"进入显示"的门槛。** 一旦到达这里就已经太晚：
`trySend` 是给正在播放的画面投递一条弹幕，之后无法"在显示前"撤回。
因此**不把 P4 作为拦截点**，仅作为"若 P3 出现遗漏入口，可在此断言/自检"的位置。

---

## 7. 覆盖性检查清单（G1 实现时必须逐一确认）

总任务说明第 5 条要求"覆盖所有真实文字弹幕入口……不能把没有经过审核的显示入口藏在适配层之外"。
基于以上阅读，**弹幕进入显示的路径**目前识别到：

| # | 入口 | 是否经过 P3 | 状态 |
|---|---|---|---|
| 1 | 正常播放逐条推送（`DanmakuEvent.Add`） | ✅ 是 | STATIC_EVIDENCE |
| 2 | 快进/快退重建屏幕（`DanmakuEvent.Repopulate`） | ✅ 是 | STATIC_EVIDENCE |
| 3 | 多来源合并（同一 `fetchResultFlow` 内 flatMap） | ✅ 是 | STATIC_EVIDENCE |
| 4 | 缓存恢复 | ✅ 是（经 `DanmakuLoader` → 同一 collection） | **待确认**：需读 `DanmakuLoader` 确认缓存路径也汇入同一 flow |
| 5 | 增量加载 / 重新匹配 | ✅ 是（`danmakuListFlow` 更新会重建列表） | **待确认**：需确认"手动匹配弹幕源"是否走同一路径 |
| 6 | `allDanmakuFlow` → `DanmakuListStateProducer`（**弹幕列表 UI，非播放器叠加层**） | ❌ **否** | 这是"弹幕列表"界面，不是视频上的弹幕。**需确认它是否也需要过滤**（总任务说明要求"播放器显示前"，列表界面性质不同，G1 阶段判定并记录） |
| 7 | 其他可能的显示入口 | UNKNOWN | 需在 G1 阶段穷举确认，不得假设只有以上几条 |

**未覆盖项必须明确说明，不得藏在适配层之外。**

---

## 8. 既有过滤规则（AI 不得重新放出）

| 机制 | 位置 | 语义 |
|---|---|---|
| 用户正则过滤 | `TimeBasedDanmakuSession.filterList(list, regexList)` | 任一正则命中即丢弃（`IGNORE_CASE`） |
| 空白文本丢弃 | `EpisodeViewModel.uiDanmakuEventFlow` | `text.isBlank()` → 丢弃；`Repopulate` 中 `!text.any { !it.isWhitespace() }` → 丢弃 |
| 弹幕源开关 | `EpisodeDanmakuLoader.setEnabled(serviceId, enabled)` | 关闭的源不参与合并 |
| 弹幕清洗 | `DanmakuSanitizer.sanitize` | 构造会话时执行 |

**约束：AI 只能做减法，不能放回上述任何一条已经丢弃的条目。**

---

## 9. 作品 / 剧集标识与会话隔离（已定位，本轮补充）

这一节是 G2（剧情包绑定）的前置证据，全部为 `STATIC_EVIDENCE`。

### 9.1 作品与剧集的稳定标识

`app/shared/app-data/src/commonMain/kotlin/domain/episode/SubjectEpisodeInfoBundle.kt`

```kotlin
data class SubjectEpisodeInfoBundle(
    val subjectId: Int,            // 作品（条目）稳定标识
    val episodeId: Int,            // 剧集稳定标识
    val subjectCollectionInfo: SubjectCollectionInfo,
    val episodeCollectionInfo: EpisodeCollectionInfo,
    val seriesInfo: SubjectSeriesInfo,   // 用于更准确地查询系列信息
    val subjectCompleted: Boolean,
) {
    val subjectInfo: SubjectInfo get() = subjectCollectionInfo.subjectInfo
    val episodeInfo: EpisodeInfo get() = episodeCollectionInfo.episodeInfo
}
```

- 加载器：`SubjectEpisodeInfoBundleLoader(subjectId, episodeIdFlow, koin)`；`infoBundleFlow` 在 `episodeIdFlow` 变化时重新发射。
- 使用点：`EpisodeDanmakuLoader` 的构造函数接收 `bundleFlow: Flow<SubjectEpisodeInfoBundle>`，
  并把 `info.subjectInfo` / `info.episodeInfo` / `info.episodeId` 传给 `DanmakuLoaderImpl`。

**结论：`subjectId` + `episodeId` 是绑定剧情包的稳定键**，且已经流入弹幕加载器，无需额外请求。

### 9.2 集序与显示标签（映射知识库的关键）

`app/shared/app-data/src/commonMain/kotlin/data/models/episode/EpisodeInfo.kt`

```kotlin
data class EpisodeInfo(
    val episodeId: Int,
    /** `0` 本篇，`1` SP，`2` OP，`3` ED */
    val type: EpisodeType?,
    val name: String = "",
    val nameCn: String = "",
    val airDate: PackedDate = PackedDate.Invalid,
    val desc: String = "",
    /** 同类条目的排序和集数 */
    val sort: EpisodeSort = EpisodeSort(""),
    /** 条目内的集数, 从 `1` 开始。非本篇剧集的此字段无意义 */
    val ep: EpisodeSort? = null,
    ...
)
val EpisodeInfo.displayName get() = nameCn.ifBlank { name }
```

`datasource/api/src/commonMain/kotlin/EpisodeType.kt`：

```kotlin
enum class EpisodeType(val value: String) {
    MainStory, SP, OP, ED, PV, MAD, OVA, OAD
}
```

`datasource/api/src/commonMain/kotlin/EpisodeSort.kt`（sealed class，三种形态）：

| 形态 | 含义 | `number` |
|---|---|---|
| `Normal(number: Float)` | 正片；**只有整数与 `.5` 小数**会被解析为 Normal（`isPartial` 判断 `% 1 == 0.5`） | Float |
| `Special(type: EpisodeType, number: Float?)` | 其他剧集（OVA/SP/OP/ED…） | 可空 |
| `Unknown(raw: String)` | 无法解析 | null |

排序规则（`compareTo`）：**所有 `Normal` < 所有 `Special` < 所有 `Unknown`**；
`Special` 之间先按 `type` 再按 `number`。

**这两个字段的分工，正好对应知识库设计的硬性要求：**

- `ep`（条目内集数，从 1 开始）→ 对应知识包的 `RevealBoundary.episodeNumber`
  （我方模型用 `Double`，因此**小数集序不会被强制取整**）。
- `sort`（同类条目的排序和集数）→ 对应"系列集序 / 特别篇显式顺序"，
  且 `Special` 形态**不是数字**，必须按显式顺序处理，不能参与数值比较。
- `type`（MainStory / SP / OP / ED / PV / MAD / OVA / OAD）→ 对应"特别篇类别"，
  与数字集序**分开保存**（总任务说明第 7.A 条）。

⚠️ **映射时的注意点（已识别，须在 G2 实现时处理）：**

1. `EpisodeSort.Unknown` 表示数据源给的集序无法解析 → 该集**不得**参与数值比较，
   应按"集序未知"处理（知识库侧对应 `TimingUnknown` 一类降级），不得猜测。
2. `Normal` 只保证支持整数与 `.5`。若知识包给出 `7.25` 这类集序，**不能**假定
   上游 `EpisodeSort` 能表达它；需要以 `ep`/`sort` 的原始形态做映射，或标记为无法映射。
3. `OP`/`ED`/`PV`/`MAD` 通常不是"剧情集"；知识包的集序范围应明确是否覆盖它们。

**实现位置（已接线）：** `app/shared/src/commonMain/kotlin/data/repository/danmaku/LocalGuardEpisodeOrder.kt`
（`object LocalGuardEpisodeOrder`，测试 `LocalGuardEpisodeOrderTest`）。
守卫模块刻意不依赖上游类型，因此"哪个字段是知识库口径的集数"只在适配层决定，
`EpisodeViewModel.localGuardEpisodeNumberOf` 只做转发。

三条实现规则：

| 上游形态 | 适配结果 | 理由 |
|---|---|---|
| `Normal(n)` | `Numbered(n, partial = isPartial)` | 保留小数，`x.5` 标记为半集 |
| `Special(MainStory, n)` | `Numbered(n)` | 类别是正片，只是排序标记特殊；当特别篇会让整集失去保护 |
| `Special(其他, n)` | `Special(type.value, n)` | 需要知识包显式声明位置，否则**不可映射** |
| `Unknown(raw)` | `Unknown(raw)` | 保留原文便于诊断，绝不猜测 |

`ep` 优先于 `sort`：`ep` 是当季集数（第 2 季第 1 集为 `01`），`sort` 是系列集数（同一集为 `26`）。
知识库按观众数的集数编号，因此必须取 `ep`——取错会让第 1 季早已揭晓的事实被当成"已公开"，
从而**放行**第 2 季里指向该事实的弹幕。`ep` 缺失时才退回 `sort`；两者皆无 → 未知（不是第 1 集）。

一个容易漏掉的形态：`EpisodeInfo.sort` 的**默认值是 `EpisodeSort("")`**（见该文件第 43 行），
即"字段存在但内容为空"。它既不是 null（判空挡不住），也不是数字（解析必然失败），
必须干净地落到 `Unknown`。`EpisodeInfo.Empty` 就是这个形态。

### 9.3 会话隔离与 generation（已有上游机制，可直接复用）

`app/shared/app-data/src/commonMain/kotlin/domain/episode/EpisodeSession.kt`

```kotlin
class EpisodeSession(
    subjectId: Int,
    val episodeId: Int,
    koin: Koin,
    parentCoroutineContext: CoroutineContext,
    sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(),
) {
    internal val sessionScope = parentCoroutineContext.childScope(CoroutineName("SubjectEpisodeFetchSelectSession"))
    val infoBundleFlow: SharedFlow<SubjectEpisodeInfoBundle?> = ...
    ...
}
```

文档注释明确：该类**与特定 `episodeId` 绑定**，充当"针对该 episodeId 启动的后台任务的作用域"；
切换集数时必须关闭当前 `EpisodeSession`（`sessionScope.cancel()`）并新建实例，
切换逻辑在 `EpisodeFetchSelectPlayState.switchEpisode` 中实现
（`app/shared/app-data/.../EpisodeFetchSelectPlayState.kt`）。

**这对总任务说明第 11 节（generation 与旧异步结果隔离）非常重要：**

- 上游**已经有**"每集一个作用域、切集即取消"的机制 →
  我方 AI 判定任务应当挂在这个 per-episode 作用域下，
  这样"旧异步任务不得把结果塞回新会话"由上游机制天然保证，不必自建一套。
- `EpisodeFetchSelectPlayState.episodeIdFlow` 提供当前集；
  文档还给了 `getCurrentEpisodeId()` 之类便捷入口，并**明确警告**：
  从外层 flow 里调用 `getCurrentEpisodeId()` 可能拿到已经变化的新集数，
  正确做法是使用**所观察的 session 自己的 `episodeId`**（见其 KDoc 示例）。
  这一约束与我方"位置与被判定条目必须同属一个会话"的要求一致。

### 9.4 待定位项（本轮已全部确证）

| # | 待办 | 结论 | 证据 |
|---|---|---|---|
| 1 | `DanmakuLoader` 的缓存路径 | ✅ **与远程同一收口**：`fetchFromLocal` 与 `fetchFromAllRemotes` 都在 `originalFetchResultFlow` 内竞争发射，随后经 `fetchResultFlow` 输出。本地缓存可显示时（`hasDisplayableDanmakus()`）先发射；远程仅在 `canReplaceLocalCache()` 为真或本地无内容时覆盖 | `domain/danmaku/DanmakuLoader.kt:86-123`、`:210-218` |
| 2 | 手动匹配弹幕源路径 | ✅ **同一收口**：`EpisodeViewModel.onMatchingDanmakuComplete` → `EpisodeDanmakuLoader.overrideResults` → `overrideResultsFlow` → 在 `fetchResultFlow` 内经 `computeFinalDanmakuResult` 合并（按 providerId 覆盖）。**不经任何旁路** | `EpisodeViewModel.kt:1478-1481`、`DanmakuLoader.kt:131-153`、`:156-170` |
| 3 | 弹幕列表 UI 是否属于过滤范围 | ✅ **属于，且已接入**：`DanmakuListItem.content = presentation.danmaku.text`——列表把**每一条弹幕的原文**按时间排序列出，是独立的第二条文字显示入口。见下方"弹幕列表的位置语义" | `details/DanmakuListStateProducer.kt:87-96` |
| 4 | 是否存在其他显示入口 | ✅ **已穷举，全应用只有 2 个**：①覆盖层 `EpisodePage.kt:1049` 的 `PlayerDanmakuHost`（全应用唯一调用点，内部 `DanmakuHost`）；②弹幕列表（第 3 项）。两处均已接入 | `EpisodePage.kt:1049`；`DanmakuHost` / `PlayerDanmakuHost` 的全部调用点检索结果 |
| 5 | 设置项持久化位置 | ✅ **已实现**：不改上游设置结构，直接在项目已有的 preferences DataStore 里用两个独立键（`animeko_localguard_enabled` / `animeko_localguard_tier`），实现见 `app/shared/src/commonMain/kotlin/data/repository/danmaku/GuardConfigRepository.kt` | `GuardConfigRepository.kt`、`CommonKoinModule.kt` |
| 6 | 媒体位置 → 基准剧情进度的映射入口 | 🟡 **部分**：`setShiftMillis` 是"弹幕源时间→实际显示时间"的源级偏移（`EpisodeDanmakuLoader.kt:170-177`），**不是**"当前视频→基准剧情版本"的映射。后者由本模块自己的 `TimeAlignment` 承担，入口是 `DanmakuGuardSession` 构造参数与 `startEpisode(...)`；**尚未接入真实数据** | `knowledge/StoryKnowledge.kt` 的 `TimeAlignment`；`DanmakuGuardSession` |

#### 弹幕列表的位置语义（第 3 项的关键决定）

列表条目自带 `playTimeMillis`，但**不能**拿它当判定位置：
一条播放时刻在未来的条目会被当作"那一刻已经发生"，于是列表会解锁观众还没看到的内容——这是泄漏。

因此列表使用**当前播放位置**判定，比覆盖层更保守（覆盖层在 `Repopulate` 时用事件自带的
`playTimeMillis`，那是"事件产生时的播放器时间"，语义不同且是上游既有约定）。
效果：列表显示的内容不会多于覆盖层此刻允许显示的内容。

关闭开关时列表走上游原路径，**不做任何过滤**（关闭 = 恢复官方行为）。

#### 尚存的覆盖空白（如实列出）

| 项 | 状态 |
|---|---|
| `UIDanmakuEvent` 重复回调 | ✅ **已有专门回归测试**：`DuplicateCallbackTest`（8 例）。核心断言用**值相等但不同实例**的请求，专门暴露"按内容去重后放行"的错误实现；同时钉住"统计逐条累计"与"语义计算复用不改变结论"这两件独立的事 |
| 弹幕编辑器自身渲染的文本 | 那是用户自己输入的内容，不属于"外来弹幕显示"，不在过滤范围 |
| 桌面端预览用的 `EpisodeVideo.kt` | 使用 `createTest*` 测试状态，非生产显示路径 |
| 性能 | 列表过滤是按文本指纹查缓存后做纯算术比较，**未测量** |

> ✅ 已完成：作品/剧集标识来源（9.1、9.2）、会话隔离与 generation（9.3）、缓存/手动匹配/显示入口穷举（9.4 第 1–5 项）。
> 🟡 第 6 项（真实映射数据）属 G2 范围。

---

## 10. 与总任务说明条款的对应

| 总任务说明要求 | 本文对应 | 状态 |
|---|---|---|
| 4.7 明确标出真实弹幕获取/合并/过滤/缓存/调度/显示入口，以及作品/剧集/播放位置来源 | 第 3–4 节（获取、合并、既有过滤、调度、进度）；显示入口第 5–6 节；**作品/剧集第 9 节；缓存与手动匹配路径第 9.4 节第 1–2 项** | ✅ 已全部定位（缓存、手动匹配、显示入口穷举均在第 9.4 节） |
| 5.4 覆盖所有真实文字弹幕入口 | 第 7 节清单；**入口穷举与判定位置语义见第 9.4 节第 3–4 项** | ✅ 全应用 2 个入口（覆盖层 + 弹幕列表）均已接入；重复回调有专门回归测试（`DuplicateCallbackTest`） |
| 5.5 保留项原文/时间/颜色/类型/顺序/布局不变 | 第 2.1 节数据模型：过滤只做"是否丢弃"，不改 `DanmakuInfo`/`DanmakuContent` 任何字段 | DESIGNED |
| 4.8 保留原有屏蔽规则，AI 不能重新放出 | 第 8 节 | DESIGNED |
| 11（generation / 旧异步结果隔离） | 第 9.3 节：上游 `EpisodeSession` 已提供 per-episode 作用域与切集取消 | STATIC_EVIDENCE |
