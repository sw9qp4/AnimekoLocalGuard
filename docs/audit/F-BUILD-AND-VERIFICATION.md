# F. 构建、验证与发布状态

本文件记录"到底什么被真正执行过"，用来防止把 `IMPLEMENTED` 误读成 `BUILT`，
或把 `BUILT` 误读成"能用"。

---

## F.1 状态词汇

| 词汇 | 含义 | 本项目当前 |
|---|---|---|
| `DESIGNED` | 只有设计 | G3、G4、G5 |
| `IMPLEMENTED` | 代码已写，未验证 | — |
| `STATIC_EVIDENCE` | 静态读取代码/文件得到的结论 | 接入点、上游改动面 |
| `UNIT_TESTED` | 单元测试通过 | 292 + 119 = 411 例 |
| `BUILT` | 编译打包成功 | 所有 APK（**仅此而已**） |
| `DEVICE_VERIFIED` | 真机验证通过 | **无** |
| `MODEL_EVALUATED` | 模型已评估 | **无**（没有模型） |
| `BLOCKED` | 阻塞 | 设备、字幕、DanDanPlay 凭据 |

---

## F.2 实际执行过的验证（逐条可核对）

| # | 验证 | 结果 | 证据文件 |
|---|---|---|---|
| V1 | 上游 Gradle 测试 `:danmaku:localguard:testAndroidHostTest` | **292/292 通过，19 类，8.7 分钟** | `docs/evidence/gradle_unit_tests_round19.txt` |
| V2 | 独立运行器（kotlinc，不经 Gradle） | **292/292 通过，19 类** | `docs/evidence/standalone_policy_tests.txt` |
| V3 | 上游 Gradle 测试 `:app:shared:testAndroidHostTest` | **119/119 通过，9 类** | `docs/evidence/gradle_unit_tests_round19_appshared.txt` |
| V4 | 构建 debug APK | **BUILD SUCCESSFUL**，sha256 `11c57a5a…` | `docs/evidence/build_success_guard_episode_order.txt` |
| V5 | 构建 release APK（含 minify） | **BUILD SUCCESSFUL**，37,011,781 字节 | `docs/evidence/build_success_guard_episode_order.txt` |
| V6 | `apksigner verify --print-certs`（debug） | v2 方案通过，证书 `966fba3c…` | — |
| V7 | `apksigner verify --print-certs`（release） | 证书 `f92b1078…`（自有密钥） | — |
| V8 | 独立 APK 核验（包名/版本/ABI/资产） | debug = `me.him188.ani.localguard`，`assets/story` 为 0 | `docs/evidence/g0_independent_identity_apk_verify.txt` |
| V9 | 解码 `resources.arsc` 确认新文案进包 | 中/繁文案确实在值池里 | 同上 |
| V10 | 发布前密钥扫描 | 3852 个文件，**无禁用路径、无凭据特征** | `tools/secret_scan.js` 输出 |
| V11 | 发布后逐路径核对 GitHub | 密钥/凭据目录**不可访问**，源码**可访问** | `STATE.json → publication` |
| V12 | 真机核查脚本空跑 | 正确输出"No device detected"，exit=3 | `tools/device_verify.ps1` |
| V13 | 死代码审计 | 96 个声明中 43 个未在生产代码出现（逐个核对为工具/常量/未接线项） | `tools/audit_dead_code.js` |

---

## F.3 明确**没有**做过的事（防止误读）

| # | 未做的事 | 后果 |
|---|---|---|
| N1 | **真机安装/启动/操作** | 界面行为、持久化、崩溃情况全部未知 |
| N2 | **真机上的拦截观察** | 拦截是否真的发生，**未知** |
| N3 | **性能测量** | 判定是否掉帧，未知 |
| N4 | **字节级可复现性比对** | 见 D-4 |
| N5 | **真实作品的剧情包** | 一个都没有 |
| N6 | **模型训练或评估** | 没有模型 |
| N7 | **DanDanPlay 覆盖测量** | 未测量（不是 0） |
| N8 | **修复后的 release 重建与核对** | 见 D-1，修了但没验证 |
| N9 | **包含最新集序改动的 APK 构建** | `artifacts/` 里的版本已过期 |
| N10 | **多设备/多分辨率验证** | 未做 |

---

## F.4 现有产物清单

| 产物 | 状态 | 说明 |
|---|---|---|
| `artifacts/animekolocalguard-v4.9.0-dev-debug-episode-order.apk` | `BUILT` | 78,296,527 B / sha256 `11c57a5a…`。**不含最新集序改动** |
| `artifacts/animekolocalguard-v4.9.0-dev-debug-diagnostics.apk` | `BUILT` | 同字节数（不代表内容相同） |
| 早前 13 个 APK | `BUILT` | 见 `STATE.json → artifacts` |
| release APK | **已删除** | 构建目录被清除，未重建 |
| `keystore/animekolocalguard-release.jks` | 本地，**未发布** | 自有签名密钥 |
| 剧情包 | **无** | — |
| 模型 | **无** | — |

---

## F.5 复现命令

```powershell
# 纯逻辑测试（最快，不经 Gradle）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_localguard_standalone.ps1 -Clean
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_localguard_standalone.ps1 -Only Cache

# 权威测试（上游 Gradle）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_android.ps1 -Task ":danmaku:localguard:testAndroidHostTest"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_android.ps1 -Task ":app:shared:testAndroidHostTest"

# 构建
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_android.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_android.ps1 -Task ":app:android:assembleRelease"

# 结果汇总（不要用 PowerShell 读结果 XML）
node tools/test_result_summary.js

# 产物核验
node tools/apk_inspect.js <apk> --expect-package me.him188.ani.localguard
node tools/apk_find_strings.js <apk> "本地弹幕过滤"

# 发布前必跑
node tools/secret_scan.js --files <listFile>

# 真机核查（默认只读）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\device_verify.ps1
```

构建必须经 ASCII junction `D:\alrepo` 进行，原因见 `docs/ASCII_PATH.md` 与 A.10/E1。

---

## F.6 发布记录

| 项目 | 值 |
|---|---|
| 仓库 | https://github.com/sw9qp4/AnimekoLocalGuard |
| 可见性 | public |
| 提交 | `0de136f891ca`（单个 import 提交） |
| 文件数 | 3840 |
| 发布方式 | 不是推送上游 4788 个提交的历史；出处写在提交信息与 `ANIMEKOLOCALGUARD.md` |
| 密钥扫描 | 通过（3852 个候选文件） |
| 发布后核对 | `local.properties` / `keystore/` / `.tools/` / `artifacts/` / `docs/evidence/` 均**不可访问** |
| 已知不足 | 见 D-15 |

---

## F.7 一个需要外部判断的诚实性问题

本项目所有"已完成"的结论都建立在**虚构测试数据**上。
`PROGRESS.md` 里记录了 5 次"实现了但没接线"的问题，
每一次都是靠**死代码审计或端到端测试**发现的，而不是靠单元测试。

这说明：**单元测试通过不能证明接线正确。**

当前的接线证据是：
- 静态搜索确认只有 2 个显示入口（`STATIC_EVIDENCE`）
- 端到端测试覆盖了判定链路（`UNIT_TESTED`）
- **但没有运行时证据证明真机上确实走了这条路**（`DEVICE_VERIFIED` 缺失）

所以对"接入是否真的生效"这个问题，**目前只能回答"静态看是接上的，运行时未验证"**。
