# 短片候选与弹幕源可用性实测

本文件记录"选哪两部作品开工"这一步的**实测**结果，以及测量方法与口径。
所有数字都可复现：`node tools/short_series_shortlist.js 8.0 14 12`。

## 1. 候选池

- 来源：Bangumi `/v0/subjects` 分页抓取，`type=2`（TV 动画），按评分客户端筛选。
- 文件：`bangumi_candidates_6_8-9_9.json`，500 条，评分区间 6.8–9.9。
- 其中**短片**（`eps` ≤ 14，评分 ≥ 8.0）：**85 部**。
  `api.bgm.tv` 会忽略 `rating_min`/`rating_max`，所以筛选必须在本地做，不能依赖服务端。

## 2. 弹幕源可用性（实测）

Animeko 实际只装了两个弹幕源：

| 源 | 端点 | 凭据 | 状态 |
|---|---|---|---|
| Animeko 自有 | `GET https://danmaku-cn.myani.org/v1/danmaku/{episodeId}` | 无 | 已测 |
| DanDanPlay | `https://api.dandanplay.net/api/v2/...` | appId + appSecret | **未测**（凭据缺失） |

`episodeId` 用的是 **Bangumi 章节 id**（实测确认：取 `subject_id=9717` 的章节 id 6xxxx 直接可用）。

### 2.1 Animeko 自有源的覆盖率很低

抽样每部前 4 集，"有弹幕的集数 / 弹幕总数 ÷ 抽样集数"：

| 作品 | 集数 | 有弹幕 | 每集均量 |
|---|---|---|---|
| 魔法少女小圆 | 12 | 4/4 | **19.5** |
| 进击的巨人 第三季 Part.2 | 10 | 2/4 | 7.3 |
| 奇巧计程车 | 13 | 4/4 | 5.0 |
| 吹响吧！上低音号 第二季 | 13 | 4/4 | 2.0 |
| 航海王：粉丝来信 | 1 | 1/1 | 2.0 |
| 乒乓 | 11 | 1/4 | 0.5 |
| 四叠半神话大系 | 11 | 2/4 | 0.5 |
| 瑞克和莫蒂 第三季 | 10 | 2/4 | 0.5 |
| 水星领航员 第三季 | 13 | 0/4 | 0 |
| 虫师 续章 | 10 | 0/4 | 0 |
| 虫师 续章 第 2 部分 | 10 | 0/4 | 0 |
| 蜂蜜与四叶草 II | 12 | 0/4 | 0 |

完整数据：`short_series_shortlist.json`。

**这是本步骤最重要的发现**：Animeko 自有源对一部正常热度的番剧，往往只提供**个位数到几十条**弹幕，而真实一集通常有数百到数千条。
也就是说，只靠这个源，"拦截"几乎无事可做，也无法代表真实使用场景。真实可用性取决于 DanDanPlay 的聚合源（B 站/AcFun/吐槽/巴哈），而它需要凭据才能测。

### 2.2 响应结构与单位（实测）

```
GET /v1/danmaku/62733
→ { "danmakuList": [ { "id":..., "senderId":"...", "danmakuInfo": { "playTime": 192727, "color":..., "text":"顶级传销。", "location":... } } ] }
```

- 文本在 `danmakuInfo.text`，位置在 `danmakuInfo.playTime`。
- `playTime` 单位是**毫秒**（实测 `0`、`32531`、`192727`、`1294158` 对应 0s、32.5s、3m12s、21m34s，与片长一致）。
- 实测抽到的弹幕文本样例（魔法少女小圆 第 1 集 62733，共 37 条）：
  `"顶级传销。"`、`"梦开始的地方"`、`"有老虚，不可能治愈"`、`"1:2"`、`"不喜欢包子脸可以不看"`。
  其中 `"有老虚，不可能治愈"` 正是本项目要处理的那类**含蓄的预期管理型剧透**——它不直接说剧情，但改变观看预期。

## 3. DanDanPlay 凭据到位后要做什么

已固化签名算法，避免凭据到手后还要重新摸索：

```
X-AppId     = appId
X-Timestamp = unix 秒
X-Signature = base64( sha256( appId + timestamp + encodedPath + appSecret ) )
```

`encodedPath` **不含 query string**。工具：`tools/dandanplay.js`（`readCredentials` / `generateSignature` / `getSigned` / `episodesByBgmtvSubjectId` / `commentCount`）。

凭据放在 `local.properties`：

```properties
ani.dandanplay.app.id=<你的 AppId>
ani.dandanplay.app.secret=<你的 AppSecret>
```

拿到后依次运行：

```powershell
node tools/short_series_shortlist.js 8.0 14 12   # 补上 DanDanPlay 列
node tools/danmaku_coverage.js                    # 全量覆盖
```

## 4. 口径纪律

- DanDanPlay 未配置凭据时，该列记为 **UNMEASURED**，不是 0。"测不到"与"测到 0"是不同结论，不得混用。
- 抽样每部仅前 4 集，**不能**外推为全片结论。
- 弹幕数量只说明"有没有东西可拦"，**不**说明"拦得对不对"。
- 弹幕是**被过滤对象**，不是剧透证据来源；剧透时刻只能由**带时间轴的字幕（台词）**推导。两者不可混同。
