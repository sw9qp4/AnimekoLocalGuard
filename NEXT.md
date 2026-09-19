# 下一动作（NEXT.md）

**当前阶段：** G0 `BUILT` 且上游 Gradle 测试任务可用 ｜ **G1 五项交付全部落地**：
三档策略、揭晓时间线、错误状态、缓存隔离、可关闭 → 纯逻辑 `UNIT_TESTED`（**292/292**，19 个类）
｜两个真实文字显示入口均已接入 ｜剧情包格式、装载链路、**生成流程**均已就绪
｜集序适配层已接线（`LocalGuardEpisodeOrder`，18 例）
｜**未做：真实模型、真实作品资料、真机验证、任何性能测量**
**最后更新：** 2026-09-19 20:40
**恢复方式：** 先读 `PROGRESS.md` + `STATE.json`，再看本文件。不要假设历史叙述仍然成立。

---

## 现状一句话

**链路与工具链都齐了，只差内容与设备。** `assets/story/` 是空的、`semanticsProvider` 返回 null，
所以打开开关**不会屏蔽任何弹幕**。放资料与接模型各自只差一步。

**素材可用性已实测**，结论见 `docs/MATERIAL_SURVEY.md` 与 `docs/SHORT_SERIES_MEASUREMENT.md`：
Bangumi 元数据与集数 ID 可获取；但**带时间字幕拿不到**（这是 G2 真正瓶颈），
且本机可用的弹幕源覆盖率**极低**（短片候选里 4 部抽样四集全为 0 条，最高者也仅 19.5 条/集）。

---

## 步骤 1（需要你操作）—— 连接 Android 设备

**G1 的完成标志目前是 `NOT_MET`，卡在这一步。** 完成标志要求"真实条目显示前受控"。

真机这一步能验证的：

- 开关能开、档位能切、重启后设置仍在；
- 状态行如实显示降级状态，**不会**显示成"已启用"；
- 开启后播放行为不变（不崩溃、不掉帧、弹幕照常显示）。

顺带**只读核对已安装 Animeko 的包名与版本号**（解除 B1、验证假设 A1）。

自编译版包名 `me.him188.ani.localguard`，与官方 Animeko **共存、不覆盖**。

---

## 步骤 2（需要你提供材料）—— G2：两部真实作品的时间线闭环

**代码与工具都已就绪，给素材就能产包。** 已实测的素材可用性见 `docs/MATERIAL_SURVEY.md`，
短片候选与弹幕源实测见 **`docs/SHORT_SERIES_MEASUREMENT.md`**。

### 2.1 片单已按你的要求收敛到"短片"

已从 500 条 Bangumi 候选里筛出 **85 部 ≤14 集、评分 ≥8.0** 的作品，并逐部实测了本机弹幕源。
弹幕实测的结论是：**Animeko 自有源覆盖极低**（多数作品每集个位数到 0 条），
所以"这部作品好不好演示"主要取决于 DanDanPlay（需凭据）。

按"集数短 + 弹幕相对多 + 剧透结构清晰"排序的建议：

| 优先 | 标题 | subjectId | 集数 | 评分 | Animeko 弹幕实测 |
|---|---|---|---|---|---|
| 1 | 魔法少女小圆 | 9717 | 12 | 8.6 | 4/4 集有，**19.5 条/集**（本批最高） |
| 2 | 奇巧计程车 | 325285 | 13 | 8.5 | 4/4 集有，5.0 条/集 |
| 备选 | 进击的巨人 第三季 Part.2 | 263750 | 10 | 8.7 | 2/4 集有，7.3 条/集 |
| 备选 | 吹响吧！上低音号 第二季 | 152091 | 13 | 8.5 | 4/4 集有，2.0 条/集 |

推荐这两部而不是"评分最高的那两部"，理由只有一个：**它们有明确、可指到具体台词的揭晓时刻**，
而"分钟级剧透防护"要证明的正是"在第 N 集某个时间点之前，相关弹幕被拦住"。
魔法少女小圆的第 3 集是公认的叙事转折点（第一集片头就有指向性的弹幕），
奇巧计程车第 13 集才揭晓核心身份，两者都是能真正考验"早于揭晓就拦"的素材。

### 2.2 素材瓶颈：需要**带时间字幕**

这是本步骤唯一缺的东西。需要的素材：允许用于本项目目的的**带时间字幕**（SRT 或 WebVTT），先两部。

**如果你只能提供英文字幕**：那么应改选欧美作品，弹幕与字幕语言会不一致——
但本流程**不受影响**，因为工具只做"引文在不在字幕里"的定位，不做跨语言推理；
声明里的引文照抄字幕原文即可。候选池里的欧美短片：
瑞克和莫蒂 S3（146457，10 集）、马男波杰克 6 下（299277，8 集）、
探险活宝 第九季（211269，13 集）、星球大战：克隆人战争 第七季（295039，12 集）。

### 2.3 顺带（一次性，收益最大）：DanDanPlay 凭据

填入 `local.properties`：`ani.dandanplay.app.id` / `ani.dandanplay.app.secret`。
签名算法已固化在 `tools/dandanplay.js`，凭据到手即可直接测量，不用再摸索：

```
X-Signature = base64( sha256( appId + timestamp + encodedPath + appSecret ) )   # encodedPath 不含 query
```

产包流程（我会执行）：

1. 你确认片单与素材可用性；
2. 我按 `FactDeclaration` 写出**事实声明**（命题由人给定，工具不推断剧情）；
3. 用字幕里的**台词引用**作为证据锚，工具推导揭晓时间区间；
4. 工具跑 `KnowledgePackValidator` 并输出提示；任何定位不到的证据都会**明确报出来**，
   而不是编一个时间；
5. 产出 `<workId>.json`（`workId` = Bangumi `subjectId`），放进
   `app/android/src/main/assets/story/`，重新构建 APK。

> 说明：在我给出候选清单前，不会展示任何剧透。清单只列标题/季度/集数/素材可用性。
> **弹幕不能用来推导揭晓时间**——弹幕是观众评论（二手转述），只能作为被过滤对象；
> 揭晓时间必须由带时间字幕的台词推导。
>
> 已验证可跑：`docs/examples/fictional-sample.*`（虚构作品 + 虚构字幕）已进测试，
> 从"引文"到"具体毫秒"的整条转换是被钉住的。**换真实作品只需换素材文件，不需要改代码。**

---

## 步骤 3 —— G3：手机本地小模型（10M–50M 参数编码器）

接入时要做的事已经明确（前三项都在 `EpisodeViewModel`）：

1. 实现 `semanticsProvider`（可取消的挂起实现，**不得跑在 UI/渲染线程**）；
2. 把 `semanticsReady` 置为 true，状态行会自动从"固定规则／时间线测试"变为"分钟级资料已验证"；
3. 修改 `LocalGuardAnalyzerVersion`（模型/归一化/分词器任一变化都必须改，
   否则会复用旧管线的缓存结论）；
4. 真实资料已经在链路里，`workId` 也已经是真 `subjectId`，无需额外改动。

---

## 明确不要做的事

- 不研究 B站注入 / Root / 混淆 / 重签名。
- 不大量下载十部视频；不训练大模型。
- 不修改或覆盖手机上的原版 Animeko 与 B站。
- 不写全局环境变量、不改全局 git 配置、不覆盖其他项目工具链。
- 不删除或改写旧工程 `BiliLocalGuard` 的任何文件。
- 不为了让构建通过而关闭 TLS 校验、伪造凭据或删除测试。
- 不把 `BUILT` 当作"播放可用"或"接入已验证"。
- 不伪造审核者、样本数、证据或执行记录。

---

## 复现命令（本机）

```powershell
# 单元测试（上游 Gradle，权威）
powershell -ExecutionPolicy Bypass -File tools/build_android.ps1 -Task ":danmaku:localguard:testAndroidHostTest"

# 纯逻辑独立测试（不经 Gradle，最快；-Only 可只跑某个类）
powershell -ExecutionPolicy Bypass -File tools/build_localguard_standalone.ps1 -Clean
powershell -ExecutionPolicy Bypass -File tools/build_localguard_standalone.ps1 -Clean -Only Cache
# 构建 APK
powershell -ExecutionPolicy Bypass -File tools/build_android.ps1
```

构建经 ASCII junction `D:\alrepo` 进行（原因见 `docs/ASCII_PATH.md`）。
若 junction 不存在，脚本会尝试自动创建（需要管理员权限）。

---

## 本机环境注意事项（踩过的坑，避免重复）

| 现象 | 真实原因 | 处理 |
|---|---|---|
| 测试任务报 `Test process encountered an unexpected problem`，且**不产出任何测试结果** | 测试 worker 的 classpath 经 `@argfile` 传递，路径含非 ASCII 字符时解析不到 worker jar | 经 ASCII junction 构建，见 `docs/ASCII_PATH.md` |
| 批量编辑 `*.kt` 后中文全部变成乱码 | PowerShell `Set-Content`/`Get-Content -Raw` 按 GBK 读写 UTF-8 源码，并写入 BOM | **禁止用 PowerShell 文本替换改源码**；只用编辑工具 |
| 某个用例永久卡住、运行器只留下一个超时 | `runBlocking` 里把 `this` 传给会话作用域，永不结束的收集器让 `runBlocking` 死等 | 传独立作用域；运行器已有 30s 看门狗并打印线程栈 |
| 证据文件用 `Tee-Object` 写出后读不出来 | PowerShell 5.1 的 `Tee-Object` 默认写 UTF-16LE | 用 `Out-String` 捕获后 `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))` |
| 编译报 `Conflicting declarations` / `Overload resolution ambiguity`，报错行号与源码对不上 | 文件里存在**同名重复声明**（编辑时留下两份），报错行号会乱 | 按声明名**计数校验**：每个声明应恰好出现 1 次 |
| 编译报 `Unresolved reference 'Xxximport'` | 编辑时把两行 `import` 粘成了一行 | 检查报错行附近的 import 是否完整 |
| 编译报 `Script compilation errors: Unresolved reference 'plugins'` | 改动版本目录/新增依赖后，Kotlin DSL 访问器处于重建期 | 清 `.gradle`/`.kotlin`/`<module>/build` 后重试 |
| `expect fun Context.f()` + `actual` 报 `Conflicting overloads` | KMP 对 expect/actual **扩展函数**支持不佳 | 改用上游惯用的"带 context 参数的顶层 expect fun" |
| 测试引用 `internal` 顶层函数却报 "it is internal in file" | 测试与主代码**分开编译**，`internal` 对其不可见 | 该函数改为 public 并说明为何需要被单独测试 |
| `logger.warn(e) { }` 报重载不适用 | 该重载解析有歧义 | 用上游代码已验证的 `logger.error(e) { }` |
| `XxxReader { }` 报 "Interface does not have constructors" | `interface` 不能 SAM 转换 | 声明为 `fun interface` |
| APK 文件大小比上一版大几 MB，怀疑代码暴涨 | 差异可能在 **APK 签名块**、压缩差异或 DEX 正常波动，而非代码 | 取出条目字节比 **SHA-256**；**不要**用文件大小，也**不要**用 `.NET ZipArchiveEntry.Crc32`（对 ZIP64 条目返回不可靠值，实测会让大小不同的条目报出相同 CRC） |
| 用 PowerShell 读 Gradle 测试结果 XML 并转 `[xml]` 时直接抛错 | 用例名含非 ASCII 字符，PowerShell 5.1 按错误编码解码 | 用 `node tools/test_result_summary.js` 扫结果 XML；**别**用 `Get-Content ... -Raw` + `[xml]` |
| 写出的证据文件里中文路径变成乱码 | 同一个文件被不同编码读/写过（`Get-Content -Raw` 有编码猜测，`[System.IO.File]::ReadAllBytes` + `UTF8.GetString` 才是明确的） | 先用 `ReadAllBytes` 取字节，再显式 `UTF8.GetString`，最后 `WriteAllText(..., UTF8Encoding($false))`；写完用 node 复查是否出现乱码特征 |
| 编辑 `import` 行时把两行粘成一行，编译报 `Unresolved reference 'Xxximport'` | 编辑工具替换的字符串边界没含行尾换行 | 改完 import 后立即用 grep 复查该行**附近**是否完整 |
