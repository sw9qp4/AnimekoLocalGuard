# A. 事实附录（可核对）

本文件只放**可复现、可核对**的事实。所有数字都注明来源与核对命令。
凡是没有把握的，写"未测量"或"未知"，不写估计值。

---

## A.1 项目身份

| 项目 | 值 |
|---|---|
| 项目名 | AnimekoLocalGuard |
| 上游 | https://github.com/open-ani/animeko |
| 上游基线提交 | `28ec14aca0a2b001f2ce1f6dd1b68acd44a14f67`（2026-09-18 13:04:32 +0800） |
| 许可证 | AGPL-3.0 |
| 本地工作树 | `D:\模型\AnimekoLocalGuard` |
| 公开仓库 | https://github.com/sw9qp4/AnimekoLocalGuard |
| 仓库可见性 | public |
| 发布提交 | `0de136f891ca`（单个 import 提交，非上游 4788 提交历史） |
| 发布文件数 | 3840 |

---

## A.2 任务说明文件

| 项目 | 值 |
|---|---|
| 路径 | `docs/DeepSeek_Animeko_LocalGuard_Master_Prompt.txt` |
| 行数 | 317 |
| 字节数 | 33733 |
| SHA-256 | `4a3aa13713fbbaae6a370d0292c63eaf74e5a03ee2dcc1ab2310f1ae49b2cbae` |

核对命令：`certutil -hashfile docs\DeepSeek_Animeko_LocalGuard_Master_Prompt.txt SHA256`

---

## A.3 测试结果（实测）

### A.3.1 纯逻辑模块 `danmaku/localguard`

| 项目 | 值 |
|---|---|
| 用例数 | **292** |
| 通过 | 292 |
| 失败 | 0 |
| 跳过 | 0 |
| 测试类数 | 19 |
| 运行器 1 | 上游 Gradle：`:danmaku:localguard:testAndroidHostTest` |
| 运行器 2 | 独立 kotlinc 运行器：`tools/build_localguard_standalone.ps1` |
| 两侧一致？ | 是，都是 292/292（互为交叉验证） |

结果 XML 汇总：`files=19 tests=292 failures=0 errors=0 skipped=0`

### A.3.2 界面/持久化层 `:app:shared`

| 项目 | 值 |
|---|---|
| 用例数 | **119** |
| 通过 | 119 |
| 失败 | 0 |
| 测试类数 | 9 |
| 本轮新增类 | `LocalGuardEpisodeOrderTest`（18 例） |

两模块合计：`TOTAL tests=411 failures=0`

### A.3.3 核对命令

```powershell
node tools/test_result_summary.js
```

> 注意：PowerShell 读这些结果 XML 会失败。用例名含非 ASCII 字符，
> PowerShell 5.1 按 GBK 解码后 `[xml]` 转换直接抛错。必须用上面这个 Node 工具。

### A.3.4 测试数据的性质（重要）

**全部 411 个用例使用的都是虚构剧情 fixture**，不含任何真实作品的剧透。
因此这些测试**不能**证明"对真实作品的判定是正确的"，只能证明"逻辑自洽"。

---

## A.4 构建产物（实测）

### A.4.1 debug APK（已有）

| 项目 | 值 |
|---|---|
| 路径 | `artifacts/animekolocalguard-v4.9.0-dev-debug-episode-order.apk` |
| 字节 | 78,296,527 |
| SHA-256 | `11c57a5aacd2de4b6e8bca336be14ffa8a8d84890d92bc5beea886af6c0413a5` |
| package | `me.him188.ani.localguard` |
| versionCode / versionName | 50406 / 4.9.0-dev |
| minSdk / targetSdk | 27 / 37 |
| 签名算法 | APK Signature Scheme v2（v1=false, v3=false，单签名者） |
| 签名证书 DN | `C=US, O=Android, CN=Android Debug` |
| 签名证书 SHA-256 | `966fba3c39027a882bee7949cb3422e8b9328747a9f1a8998f85d9c3595851c0` |
| ZIP 条目 | 1527 |
| DEX 文件 | 28 |
| 原生 ABI | 仅 `arm64-v8a` |
| `assets/story/` 条目数 | **0** |

**⚠️ 这个 APK 不包含最新的集序适配改动。** 它的构建发生在
`LocalGuardEpisodeOrder` 引入之前。因此"最新代码"与"现有 APK"之间存在差异，
需要重新构建才能对齐。

### A.4.2 release APK（本轮构建过，现已不存在）

本轮成功构建过 release 变体，并用自有密钥签名验证通过：

| 项目 | 值 |
|---|---|
| 字节 | 37,011,781（开启 minify） |
| 证书 DN | `CN=AnimekoLocalGuard Local Test Key, OU=local-only, O=AnimekoLocalGuard, L=-, ST=-, C=CN` |
| 证书 SHA-256 | `f92b10785d236e5e28e3d510f13b3ce43285f76201337172e6d4482441c626be` |
| **当次 package** | **`me.him188.ani` ← 缺陷，见 D-1** |

由于 `app/android/build/` 在修复过程中被清除，**该 APK 已不存在，修复后未重新构建验证**。

### A.4.3 自有签名密钥库

| 项目 | 值 |
|---|---|
| 路径 | `keystore/animekolocalguard-release.jks`（**未发布**，已在 .gitignore） |
| 格式 | PKCS12 |
| 别名 | `animekolocalguard` |
| 算法 | RSA 4096 / SHA384withRSA |
| 有效期 | 10950 天 |
| 证书 SHA-256 | `f92b10785d236e5e28e3d510f13b3ce43285f76201337172e6d4482441c626be` |
| 与 debug 密钥不同？ | 是（debug 为 `966fba3c…`） |
| 生成工具 | `tools/make_release_keystore.ps1` |
| 凭据存放 | `local.properties`（未发布） |

---

## A.5 上游改动面（实测）

```
git diff --name-status HEAD
```

恰好 **12 个受跟踪文件被修改**，删除行总计 **5 行**：

| 文件 | 删除行 | 性质 |
|---|---|---|
| `app/shared/build.gradle.kts` | 1 | 纯空行重排 |
| `app/shared/src/commonMain/kotlin/ui/subject/episode/EpisodeViewModel.kt` | 2 | `danmakuList.map {…}` 为加 `if/else` 分支而重写；关闭守卫时走原逻辑 |
| `app/shared/src/commonMain/kotlin/ui/subject/episode/video/components/EpisodeVideoSideSheet.kt` | 2 | `useThinSlider` 重写为带尾逗号的参数列表，默认值不变 |
| 其余 9 个文件 | 0 | 纯新增 |

**结论：没有任何上游既有逻辑被删除或改写语义。**

---

## A.6 设备状态（实测）

| 项目 | 值 |
|---|---|
| 是否连接设备 | **否** |
| 核对时间 | 2026-09-19 20:50 +08:00 |
| 核对方法 | `.tools/android-sdk/platform-tools/adb.exe devices -l` → 空列表 |
| 手机上 Animeko 版本 | **UNKNOWN**（无法核对，假设 A1 未解除） |
| 真机验证 | **未做** |

---

## A.7 外部数据源实测

### A.7.1 Animeko 自有弹幕源

| 项目 | 值 |
|---|---|
| 端点 | `GET https://danmaku-cn.myani.org/v1/danmaku/{episodeId}` |
| 需要凭据 | 否 |
| 可达 | 是 |
| `episodeId` 种类 | **Bangumi 章节 id**（非条目 id） |
| 时间字段 | `danmakuInfo.playTime`，**单位毫秒** |
| 文本字段 | `danmakuInfo.text` |

覆盖率实测（抽样每部前 4 集，单位：条/集）：

| 作品 | 集数 | 有弹幕的抽样集 | 每集均量 |
|---|---|---|---|
| 魔法少女小圆 | 12 | 4/4 | 19.5 |
| 进击的巨人 第三季 Part.2 | 10 | 2/4 | 7.3 |
| 奇巧计程车 | 13 | 4/4 | 5.0 |
| 吹响吧！上低音号 第二季 | 13 | 4/4 | 2.0 |
| 航海王：粉丝来信 | 1 | 1/1 | 2.0 |
| 乒乓 | 11 | 1/4 | 0.5 |
| 四叠半神话大系 | 11 | 2/4 | 0.5 |
| 瑞克和莫蒂 第三季 | 10 | 2/4 | 0.5 |
| 水星领航员 第三季 | 13 | 0/4 | 0 |
| 虫师 续章 | 10 | 0/4 | 0 |
| 虫师 续章 第2部分 | 10 | 0/4 | 0 |
| 蜂蜜与四叶草 II | 12 | 0/4 | 0 |

**关键结论：真实一集通常有数百到数千条弹幕，而该源平均只提供个位数到几十条。**
只靠这个源，"拦截"几乎无事可做，真机上也观察不到差异。

### A.7.2 DanDanPlay 聚合源

| 项目 | 值 |
|---|---|
| 需要凭据 | **是**（appId + appSecret） |
| 当前状态 | **未配置**，因此**未测量**（不是 0） |
| 无凭据实测 | `GET /api/v2/search/episodes` → `errorCode 3「应用不存在」` |
| 签名算法 | `X-Signature = base64( sha256( appId + timestamp + encodedPath + appSecret ) )`，`encodedPath` 不含 query |
| 算法来源 | `danmaku/dandanplay/src/commonMain/kotlin/DandanplayClient.kt:56-67` |
| 已固化到 | `tools/dandanplay.js` |

`Bilibili` / `AcFun` / `Baha` / `Tucao` 四个 `DanmakuServiceId` **只是标签**，
数据全部由 DanDanPlay 聚合，无独立实现。缺凭据时这四家弹幕一条都拿不到。

### A.7.3 带时间字幕

**不在任何 API 里，本机不可获取。这是 G2 的真正瓶颈，必须由用户提供。**

---

## A.8 阈值与余量（当前取值）

| 档位 | contentViolation | explicitSpoiler | hintSpoiler | revealMargin | unknownFactFloor | 未映射对齐时 |
|---|---|---|---|---|---|---|
| LENIENT（宽松） | 0.80 | 0.85 | 0.95 | 0 ms | 0.95 | 只拦高置信剧透 |
| BALANCED（均衡，默认） | 0.60 | 0.60 | 0.80 | 15000 ms | 0.80 | 只拦高置信剧透 |
| STRICT（严格） | 0.35 | 0.35 | 0.55 | 30000 ms | 0.55 | 拦所有过阈值剧透 |

**这些数字是"可测量的取舍参数"，不是概率，也不是经过校准的最优值。**
它们没有任何真实标注数据集支撑，必须靠真实验证集校准。

---

## A.9 无依赖的辅助工具清单

| 工具 | 用途 |
|---|---|
| `tools/build_android.ps1` | 经 ASCII junction 构建（原因见 `docs/ASCII_PATH.md`） |
| `tools/build_localguard_standalone.ps1` | 不经 Gradle 的纯逻辑测试运行器 |
| `tools/test_result_summary.js` | 汇总 Gradle 结果 XML（PowerShell 读会失败） |
| `tools/secret_scan.js` | 发布前扫描：禁止路径 + 凭据特征 |
| `tools/git_import.js` | 生成单提交发布副本（正确处理非 ASCII 路径） |
| `tools/device_verify.ps1` | 真机核查（默认只读，写入需显式开关） |
| `tools/apk_inspect.js` | 从 APK 直接读包名/版本/签名方案/资产/ABI |
| `tools/apk_find_strings.js` | 解码 `resources.arsc` 值池，确认文案真的进包 |
| `tools/audit_dead_code.js` | 找"实现了但没接线"的声明 |
| `tools/make_release_keystore.ps1` | 生成自有签名密钥库 |
| `tools/dandanplay.js` | DanDanPlay 签名与测量 |
| `tools/short_series_shortlist.js` | 短片候选筛选 + 弹幕实测 |
| `tools/http_util.js` | 本机可用的最小 HTTP 客户端（经代理 CONNECT 隧道） |

---

## A.10 本机环境约束（影响可复现性）

| # | 约束 | 后果 |
|---|---|---|
| E1 | 工作区路径含非 ASCII（`D:\模型\`） | AGP 拒绝；且 Gradle 9.3.1 用 `@argfile` 传测试 worker classpath，非 ASCII 路径会让 worker 启动失败且**不产出任何测试结果**。必须经 ASCII junction 构建 |
| E2 | Windows schannel TLS 损坏（`SEC_E_NO_CREDENTIALS`） | PowerShell/curl 无法 HTTPS；Node 可以 |
| E3 | `api.bgm.tv`、`api.dandanplay.net` 直连超时 | 必须经本机代理 `127.0.0.1:7897` 做 CONNECT 隧道 |
| E4 | GitHub SSH 22 端口不通 | 推送只能用 HTTPS |
| E5 | 构建脚本走 Windows PowerShell 5.1 | BOM-less `.ps1` 含非 ASCII 会被按 GBK 解码并破坏字符串字面量；`&&`/`\|\|` 不是合法语句分隔符 |
| E6 | 沙箱禁止带管道的子进程 | Node 无法 `execFileSync('git', …)`（EPERM）；git 凭据助手无法创建信号管道 |
| E7 | C 盘仅约 12 GB 可用 | Gradle 缓存重定向到 D 盘 |
| E8 | 上游要求 JBR（`vendor=jetbrains`，需 JCEF），本机 DNS 无法解析其下载源 | `local.properties` 中把 vendor 置空，改用 Temurin 21 |

**这些约束意味着：本项目的构建目前依赖多处本机绕行，换机器需要重新评估。**
