# B. 任务清单（对照总任务说明逐条）

状态词汇（严格区分，不得合并）：

| 词汇 | 含义 |
|---|---|
| `DESIGNED` | 只有设计，没有代码 |
| `IMPLEMENTED` | 代码已写，未验证 |
| `STATIC_EVIDENCE` | 通过静态读取代码/文件得出的结论 |
| `UNIT_TESTED` | 单元测试通过 |
| `BUILT` | 构建/打包成功（**只代表编译打包**） |
| `DEVICE_VERIFIED` | 真机验证通过 |
| `MODEL_EVALUATED` | 模型已评估 |
| `BLOCKED` | 阻塞 |
| `UNKNOWN` | 未知 |

> **`BUILT` 不等于"能播放"，也不等于"接入已验证"。** 本项目所有 APK 都只是 `BUILT`。

---

## B.1 G0 —— 迁移归档、环境/设备核查、锁定源码、独立构建基线、接入点证据

| # | 任务 | 状态 | 证据 | 备注 |
|---|---|---|---|---|
| G0-1 | 迁移归档 | ✅ 已完成 | `docs/DECISIONS.md` | 旧工程 `D:\模型\BiliLocalGuard\` 未改动 |
| G0-2 | 保留旧工程与用户改动 | ✅ 已完成 | `git diff` 只见 12 个文件被改，无删除 | 见 A.5 |
| G0-3 | 不删除历史报告 | ✅ 已完成 | `PROGRESS.md` 累计记录，未删行 | |
| G0-4 | 建立迁移决策文档 | ✅ 已完成 | `docs/DECISIONS.md` | |
| G0-5 | 锁定 Animeko 源码 | ✅ 已锁定 | HEAD = `28ec14ac…`，与记录一致 | |
| G0-6 | 环境核查 | ✅ `VERIFIED` | `STATE.json → environment` | 含 7 项本机绕行，见 A.10 |
| G0-7 | **设备只读核查** | ❌ **BLOCKED** | adb 无设备 | 假设 A1 未解除 |
| G0-8 | 独立 applicationId | ⚠️ **DEBUG 已达成 / RELEASE 曾失败已修未验证** | debug = `me.him188.ani.localguard` | 见 D-1 |
| G0-9 | 自有签名 | ✅ 已生成并验证 | 证书 SHA-256 `f92b1078…` | release 构建已用它签出并 `apksigner` 验证通过 |
| G0-10 | **可复现基线构建** | ⚠️ **部分**：构建可重复运行，但**字节级可复现性未验证** | — | 见 D-4 |
| G0-11 | 接入点证据 | ✅ `STATIC_EVIDENCE` | `docs/INTEGRATION.md` | 全应用只有 2 个真实弹幕文字显示入口 |

**G0 结论：10/11 项完成或基本完成，卡在设备（G0-7），且 G0-8/G0-10 有明确未验证项。**

---

## B.2 G1 —— 三档策略 + 揭晓时间线 + 接入真实显示链路 + 错误状态 + 缓存隔离 + 可关闭

### B.2.1 五项交付

| # | 交付 | 状态 | 证据 |
|---|---|---|---|
| G1-1 | 三档策略（宽松/均衡/严格） | ✅ `UNIT_TESTED` | `TierPolicyTest`、`TierMonotonicityPropertyTest`（含两两可区分 + 方向性） |
| G1-2 | 揭晓时间线时间规则 | ✅ `UNIT_TESTED` | `DanmakuGuardEngineTest`、`PipelineEndToEndTest` |
| G1-3 | 错误状态 | ✅ `UNIT_TESTED` | `GuardStatusTest`；`MODEL_FAILURE` 状态可达（此前是死代码，已修） |
| G1-4 | 缓存隔离 | ✅ `UNIT_TESTED` + 已接入生产路径 | `SemanticsCacheTest`、`GuardSessionCacheTest`（两级：通用层可跨作品，事实关系层绑定作品+集数+资料版本） |
| G1-5 | 可关闭 | ✅ `BUILT`（界面开关 + 持久化） | `GuardConfigRepository`（DataStore），默认关闭 |

### B.2.2 接入真实显示链路

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| G1-6 | 覆盖层入口（`PlayerDanmakuHost`） | ✅ 已接入 | `EpisodePage.kt:1049`，全应用唯一调用点 |
| G1-7 | 弹幕列表入口（`allDanmakuListFlow`） | ✅ 已接入 | 穷举确认全应用只有这 2 个真实文字显示入口 |
| G1-8 | 无旁路 | ✅ `STATIC_EVIDENCE` | 缓存恢复路径、手动匹配路径都汇入同一 `fetchResultFlow` |
| G1-9 | 重复回调 | ✅ `UNIT_TESTED` | `DuplicateCallbackTest`（8 例） |
| G1-10 | 集序适配（当季集数 vs 系列集数） | ✅ `UNIT_TESTED` | `LocalGuardEpisodeOrderTest`（18 例），本轮新接线 |
| G1-11 | 界面设置分组 + 状态行 + 诊断明细 | ✅ `BUILT` | 11 条文案 × 7 个语言目录 |

### B.2.3 G1 完成标志（**关键**）

原任务说明的完成标志是两句：

| 完成标志 | 状态 | 证据 |
|---|---|---|
| ① "虚构时间线能在同一集不同位置改变决定" | ✅ **已满足** | `PipelineEndToEndTest`：同一集 5:39 拦、6:00 放行 |
| ② "真实条目显示前受控" | ❌ **NOT_MET** | 需要真机 + 真实语义来源，两者都没有 |

**G1 结论：五项交付与两个显示入口都已落地；完成标志只满足前半。**

---

## B.3 G2 —— 两部真实作品的时间线闭环

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| G2-1 | 剧情包磁盘格式 | ✅ `UNIT_TESTED` | 全部 `@Serializable`，`@SerialName` 钉住多态判别值 |
| G2-2 | 装载链路（含结构校验） | ✅ `BUILT` | 区分 `Loaded`/`Unusable`/`Absent`；装载时真跑校验器 |
| G2-3 | 剧情包生成工具 | ✅ `UNIT_TESTED` | 人手写 `FactDeclaration` + 带时间字幕 → 推导揭晓时间 |
| G2-4 | 片源对齐生成 | ✅ `UNIT_TESTED` | 按台词配对两版字幕 → 常量偏移或分段映射；拟合不到就拒绝 |
| G2-5 | 端到端管线测试 | ✅ `UNIT_TESTED` | 字幕→包→落盘→装载→对齐→判定 |
| G2-6 | 短片候选筛选 | ✅ 已完成 | 85 部 ≤14 集、评分 ≥8.0 |
| G2-7 | 候选弹幕实测 | ✅ 已完成（Animeko 源） | 见 A.7.1 |
| G2-8 | DanDanPlay 覆盖实测 | ❌ **未测量**（缺凭据） | 未测量 ≠ 0 |
| G2-9 | **两部作品的真实剧情包** | ❌ **BLOCKED** | **缺带时间字幕**，必须由用户提供 |
| G2-10 | 字幕样例与其测试 | ✅ `UNIT_TESTED` | 3 份虚构 VTT 已进测试，钉住"引文→具体毫秒" |

**G2 结论：工具侧全部就绪；内容侧 0 部，唯一阻塞是带时间字幕。**

---

## B.4 G3 —— 手机本地小模型（10M–50M 参数编码器）

| # | 任务 | 状态 |
|---|---|---|
| G3-1 | 模型接入设计 | ✅ `DESIGNED`（步骤已写入 `NEXT.md`） |
| G3-2 | 实现 `semanticsProvider` | ❌ `NOT_STARTED` |
| G3-3 | 模型本体 | ❌ `NOT_STARTED`（未训练、未下载） |

**G3 结论：未开始。当前 `semanticsProvider` 返回 null，因此打开开关不会屏蔽任何弹幕。**

---

## B.5 G4 / G5

| # | 任务 | 状态 |
|---|---|---|
| G4 | 扩展到 10 部 + 预审注释/调度/性能预算 | ❌ `NOT_STARTED` |
| G5 | 完整验收 | ❌ `NOT_STARTED` |

---

## B.6 本轮（第 16–17 轮）新增完成项

| # | 任务 | 状态 |
|---|---|---|
| R1 | 修复 `EpisodeOrderMapper` 未接线（特别篇被当正片集数、跨季集数错用） | ✅ `UNIT_TESTED`（18 例）+ `BUILT` |
| R2 | 补齐 3 份缺失的示例字幕文件并接入测试 | ✅ `UNIT_TESTED` |
| R3 | 生成自有签名密钥库并验证 release 签名 | ✅ 已实测 |
| R4 | APK 独立核验工具（包名/版本/签名/资产/ABI） | ✅ 已实测 |
| R5 | 发布前密钥扫描工具 | ✅ 已实测 |
| R6 | 真机核查脚本 + 核查清单 | ✅ `IMPLEMENTED`，已空跑一次（正确报告"无设备"） |
| R7 | 发布到公开仓库 | ✅ 已完成并核对 |
| R8 | **重建已修复的 release APK** | ❌ **未做**（构建环境修复中） |
| R9 | **重新构建包含最新集序改动的 debug APK** | ❌ **未做** |

---

## B.7 总体完成度（诚实评估）

| 阶段 | 完成度 |
|---|---|
| G0 | 约 90%（卡设备 + 2 项未验证） |
| G1 | 约 85%（功能全落地，完成标志后半未达成） |
| G2 | 工具 100%，内容 0% |
| G3 | 0% |
| G4 / G5 | 0% |

**一句话：链路是通的，内容是空的，设备没接上。**
