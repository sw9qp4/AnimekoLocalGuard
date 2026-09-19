# 迁移决策记录（docs/DECISIONS.md）

本文件记录 **AnimekoLocalGuard 新方向**取代了旧 BiliLocalGuard 阶段的哪些约束、做了哪些版本与
架构选择，以及哪些仍是未验证假设。历史报告（`../BiliLocalGuard/docs/REVIEW_BRIEF.md` 等）
**保留不改**，不删除、不改写，以保留真实历史记录。

来源总任务说明：`docs/DeepSeek_Animeko_LocalGuard_Master_Prompt.txt`
（SHA-256 `4a3aa13713fbbae6a370d0292c63eaf74e5a03ee2dcc1ab2310f1ae49b2cbae`，317 行，已完整读取）

---

## 1. 被本总任务说明取代的旧约束

旧 BiliLocalGuard 在"只能在 B站 App 内实现 / 只按集判断 / 禁止剧情库与训练"等前提下做了大量
排除性决策。以下旧约束**自本文件生效起失效**，不得再作为本项目的阻塞项或范围限制：

| 旧约束（BiliLocalGuard 阶段） | 新方向（本文件生效） |
|---|---|
| 只能在 B站官方客户端进程内实现（注入/补丁） | 直接基于 **Animeko 源码**改造，不重选播放器、不重做视频源聚合、不强制 Lanerc |
| 假想接入点必须落在 B站混淆类里 | 接入点落在 Animeko 自己的弹幕与播放器代码里（可读、可改、可测） |
| 只按"集"过滤，禁止分钟级 | **尽量做到同一集内分钟级防剧透**；不同播放位置允许讨论的内容不同 |
| 禁止剧情知识库 | **核心路线**就是预先构建带揭晓时间的剧情知识库 |
| 禁止训练模型 | 允许在开发电脑上训练轻量中文编码器（约 10M–50M 参数），端侧推理 |
| Root / B站签名 / 混淆方法 是主要阻塞项 | **不得继续把 Root、B站签名或混淆方法作为本项目阻塞项** |
| 修改官方客户端包（重签名冲突无法解决） | 修改版使用**独立 applicationId + 自有测试签名**，与原版 Animeko 共存，不覆盖原版 |
| 屏蔽决定仅由固定字符串规则给出 | 三档（宽松/均衡/严格）+ 语义分析 + 时间线策略；固定规则仅作原型阶段占位 |

**仍然有效且被新方向继承的约束**（未被取代）：

- 不 Root、不解锁、不刷机、不改系统分区/SELinux/证书库/代理/VPN/防火墙、不关闭安全校验。
- 不清除、卸载或覆盖现有 Animeko／B站；不读取其私有账号数据、Cookie、令牌、支付信息。
- 实体设备默认只读；安装测试包或改设备设置前需有明确且适用的授权，不把旧项目模糊授权扩大解释。
- 不绕过会员、地区、DRM、登录、完整性或服务权限限制。
- 不为训练批量抓取全站弹幕，不上传视频/完整字幕/真实弹幕库/设备日志给第三方。
- 依赖与源码记录版本、许可证、摘要；不关闭 TLS 验证。
- 不改写或删除旧工程的历史报告来制造"从未走过旧路线"的记录。
- 日志默认只记去标识化条目、版本、状态、类别编码、时延与计数。
- 性能只填实测值；未测量写"未测量"。

---

## 2. 版本与架构选择

### 2.1 上游源码锁定

| 项目 | 值 |
|---|---|
| 仓库 | `https://github.com/open-ani/animeko.git` |
| 本地路径 | `D:\模型\AnimekoLocalGuard` |
| 分支 | `main` |
| **锁定提交** | **`28ec14aca0a2b001f2ce1f6dd1b68acd44a14f67`** |
| 提交时间 | 2026-09-18 13:04:32 +0800 |
| 提交说明 | `fix(ci): sqlite_bindings.cpp 优先从 GitHub 镜像下载, googlesource 作为备用来源 (#3434)` |
| 许可证 | GNU **AGPL-3.0**（`LICENSE.txt`，34.4 KB）—— 修改版分发需遵守 AGPLv3，保留归属 |
| 克隆方式 | 全量克隆（含历史），保留 `.git` 以便记录差异；未做任何本地修改 |

**为什么选 main 最新提交作为暂定基线**：设备当前**未连接**，无法读取用户实际安装的 Animeko 版本。
按总任务说明第 4.1 条，先选可复现版本并**标记为暂定**，不冒充与手机完全相同。
设备连上后必须重新核对并记录实际版本，必要时调整基线。

### 2.2 选定工具链（来自上游自身配置，不凭记忆）

| 项目 | 要求值 | 来源 |
|---|---|---|
| Gradle | **9.3.1** | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | **2.4.10** | `gradle/libs.versions.toml` |
| Android Gradle Plugin | **9.1.1** | `gradle/libs.versions.toml` |
| JDK toolchain | **21**，`jvm.toolchain.vendor=jetbrains` | `gradle.properties` |
| compileSdk / targetSdk | **37** | `gradle.properties` `android.compile.sdk` |
| minSdk | **27** | `gradle.properties` `android.min.sdk` |
| versionCode | 50406（固定，CI 不更新） | `gradle.properties` |
| package.version / version.name | `5.0.0` / `4.9.0-dev` | `gradle.properties` |
| Android SDK 组件 | `platform-tools`、`platforms;android-37.0`、`build-tools;36.0.0` | `.github/workflows/build.yml` |
| 默认 ABI | `arm64-v8a`（`ani.android.abis=all` 可构建全集） | `app/android/build.gradle.kts` + `docs/contributing/building.md` |

**JDK 选择说明**：`gradle.properties` 指定 `jvm.toolchain.vendor=jetbrains`（JBR，且需带 JCEF）。
CI 使用 JBR 21（`jbrsdk_jcef-21.0.11-windows-x64`）。本环境先以 **Temurin JDK 21** 作为
`org.gradle.java.installations.paths` 提供的 toolchain 来源做基线尝试；若桌面目标因缺 JCEF 失败，
**Android 目标不受影响**（本次只交付 Android）。该假设记为未验证。

### 2.3 独立应用标识与自有签名

上游已提供正规机制，**无需全局改包名**（总任务说明第 4.5 条明确不要全局替换 Kotlin 包名）：

| 项目 | 机制 | 选定值 |
|---|---|---|
| 命名空间 | `app/android/build.gradle.kts` `namespace` | `me.him188.ani.android`（**不改**） |
| 正式 applicationId | 同上 | `me.him188.ani`（**不改**） |
| 独立应用标识 | debug 变体 `applicationIdSuffix`，可经 `local.properties` 的 `ani.android.debug.applicationIdSuffix` 覆盖 | **`me.him188.ani.localguard`** |
| 签名 | debug 变体默认用 debug 签名；release 需要 `signing_release_*` 属性 | 生成**自有测试密钥库**，路径与口令写入 `local.properties`（已被 `.gitignore` 忽略） |

**结论：调试版（`assembleDebug`）即满足"独立 applicationId + 自有测试签名 + 与原版共存"要求**，
不需要 patch 上游构建脚本。发布版（`assembleRelease`）需要 release 签名属性，本阶段不使用。

### 2.4 磁盘与资源布局（项目级，不动全局）

| 项目 | 值 | 理由 |
|---|---|---|
| C: 可用 | **12.1 GB**（紧张） | 不可作为 Gradle/Android SDK 缓存盘 |
| D: 可用 | **154.2 GB** | 全部项目级工具链与缓存放这里 |
| `GRADLE_USER_HOME` | `D:\模型\AnimekoLocalGuard\.tools\gradle-home` | 避免写入 `C:\Users\yuzu\.gradle`（旧项目缓存也在那里，且 C 盘吃紧） |
| Android SDK | `D:\模型\AnimekoLocalGuard\.tools\android-sdk` | 独立于任何系统级 SDK；不覆盖其他项目工具链 |
| JDK 21 | `D:\模型\AnimekoLocalGuard\.tools\jdk21` | 项目级，不改 `JAVA_HOME` 全局变量 |

以上均通过**项目级 `local.properties` / 命令行 `-g` / 环境变量仅对本进程生效**实现，
不修改用户全局环境。

### 2.5 旧工程复用边界

| 旧资产 | 处理方式 |
|---|---|
| `BiliLocalGuard/rules-core` 的通用规则/接口/会话隔离/测试 | **审查后择优复用纯逻辑**，不移植 B站专用标识、混淆类、注入代码 |
| `BiliLocalGuard/docs/REVIEW_BRIEF.*` 等历史报告 | **原样保留**，不删除不改写 |
| `BiliLocalGuard/analysis/evidence/*` | 作为历史证据保留 |
| `BiliLocalGuard/.tools/`（JDK 17、platform-tools、build-tools、baksmali） | 可复用（`dl.js` 已用于本次下载；`adb.exe` 用于只读设备查询） |
| B站 APK 相关分析（dex990/smali/签名核验） | **停止继续研究**；仅作历史证据 |
| 旧工程"重复弹幕不重复判定但原样放行"的设计 | **必须修正**：统计去重不能绕过过滤，重复回调也必须落实当前有效决定 |

---

## 3. 未验证假设（明确列出，不得当作事实）

| # | 假设 | 状态 | 验证方式 |
|---|---|---|---|
| A1 | 用户手机上的 Animeko 版本等于 `main@28ec14ac` | **未验证（暂定）** | 设备连接后 `dumpsys package` 读取 versionName/versionCode 并核对 |
| A2 | Temurin JDK 21 可满足上游全部 Android 构建需求（CI 用的是带 JCEF 的 JBR 21） | **未验证** | 实跑 `assembleDebug` |
| A3 | 仅装 `platforms;android-37.0` + `build-tools;36.0.0` 即可完成 Android 构建 | **未验证** | 实跑构建，缺什么记什么 |
| A4 | 缺失 `ani.dandanplay.app.id/secret` 不影响构建（仅运行期该弹幕源不可用） | **未验证** | 实跑构建；官方文档 `docs/contributing/building.md` 称"不配置打包仍会成功，但运行时无法使用对应功能" |
| A5 | 缺失 `app/android/google-services.json` 不阻塞 Android 构建 | **未验证** | CI 中有 `FILE_PATH: './app/android/google-services.json'` 步骤；`GoogleServicesPlugin.MissingGoogleServicesStrategy` 已被 import，实际行为待构建验证 |
| A6 | 磁盘空间足以完成一次完整 Android 构建 | **未验证** | 构建过程中监控 D: 余量；不足则缩减（单 ABI、跳过桌面目标） |

---

## 4. 与旧阶段的对照：哪些旧结论仍然成立

以下旧结论**经重新审查后仍然成立**，并在新工程中继续适用：

1. **统计去重 ≠ 允许显示**：旧实现"重复条目不重复计数但仍原样返回给客户端"必须修正。
   新系统中"统计去重、语义计算复用、实际显示决定"是三件独立的事，重复回调必须落实屏蔽。
2. **日志默认不含弹幕正文**：旧工程的隐私约束与实现（有测试断言）继续有效。
3. **旁路/降级必须有明确状态**：旧工程的 `BypassReason` 思路可复用，但新系统的状态集更大
   （排队、未判定、过期、失败），不能直接套用旧的统计公式。
4. **性能只填实测值**：继续有效。
5. **旧测试通过只代表当时的测试范围**：MOCK 数据不能作为 Animeko 接入或 AI 效果证据。
