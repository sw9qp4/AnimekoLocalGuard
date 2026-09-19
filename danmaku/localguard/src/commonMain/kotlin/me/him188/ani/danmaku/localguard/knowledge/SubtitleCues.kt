/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.knowledge

/**
 * 从带时间字幕推导剧情事实的**揭晓边界**。
 *
 * ## 这个工具做什么、不做什么（重要）
 *
 * **不做**：不发明剧情。工具无法"看"视频，也不会自己判断"谁是凶手"。
 * 一条事实的命题、涉及实体与严重程度必须由人（或人工核对过的流程）写在**事实声明**里。
 *
 * **做**：把声明绑定到可定位的字幕证据上，并据此**推导时间边界**。
 * 这正是总任务说明第七节要求的："时间表示观众按叙事首次得到足够信息的位置"，
 * 而"充足信息"是可以指到具体台词的——比凭感觉写一个秒数可靠得多。
 *
 * ## 为什么解析要自己写
 *
 * 上游的字幕解析（`AssSubtitleParserFactory` 等）依赖 Android / 播放器运行时，
 * 无法在纯逻辑单测里跑，也不能用在离线的包生成流程里。
 * 这里只需要"cue 的起止时间 + 文本"，因此写一个最小解析器，
 * 并显式支持 WebVTT 与 SRT 两种常见格式。
 *
 * 设计原则：
 * - 解析失败**不抛异常**，而是返回可报告的问题——生成工具要能告诉使用者"哪个文件、哪一行坏了"。
 * - 时间单位统一为**毫秒**，与知识库模型一致。
 * - 文本比较前做**归一化**（去标签、折叠空白、可选忽略大小写），
 *   因为字幕文件里的换行与标记不应影响"这句话是否出现过"。
 */

/** 一条字幕。 */
data class SubtitleCue(
    /** 文件内序号，从 1 开始（用于报告定位） */
    val index: Int,
    /** 起止时间（毫秒） */
    val startMillis: Long,
    val endMillis: Long,
    /** 原始文本（已去掉时间行与序号） */
    val text: String,
    /** 时间行在原文件里的 1 起行号；未知时为 null。用于把问题定位回文件。 */
    val lineNumber: Int? = null,
) {
    init {
        require(startMillis >= 0) { "startMillis must be >= 0" }
        require(endMillis >= startMillis) { "endMillis must be >= startMillis" }
    }

    /** 归一化文本，用于匹配。 */
    val normalizedText: String get() = normalizeSubtitleText(text)
}

/** 解析问题。用于报告"哪个文件、第几行、什么问题"，而不是静默丢弃。 */
data class SubtitleParseIssue(
    /** 源文件标识（由调用方给出，例如文件路径） */
    val source: String,
    /** 1 起的行号；未知时为 null */
    val lineNumber: Int?,
    val message: String,
)

/** 解析结果：成功解析出的 cue + 无法解析的位置。 */
data class SubtitleParseResult(
    val cues: List<SubtitleCue>,
    val issues: List<SubtitleParseIssue> = emptyList(),
) {
    val isEmpty: Boolean get() = cues.isEmpty()
}

/**
 * 归一化字幕文本：去 HTML/ASS 标签、折叠内部空白为单个空格、去首尾空白。
 *
 * 为什么去掉 `{...}` 与 `<...>`：ASS 的样式块与 WebVTT 的内联标签都不是台词内容，
 * 留在里面会让"引用是否出现"的判断失败。
 *
 * **折叠**而不是**删除**内部空白：一个空格在自然语言里可能是有意义的，
 * 直接删掉会让"AB"与"A B"变成同一个键。跨空格差异由 [matchesSubtitleQuote] 负责，
 * 两者职责分开，避免在这里做出一个影响面更大的决定。
 *
 * 刻意**不做**的归一化：不改写标点、不做同义词替换、不删除语气词。
 * 那些都会让"引用匹配"变得比实际更宽松，从而把错误的时间当成证据。
 */
fun normalizeSubtitleText(text: String, ignoreCase: Boolean = true): String {
    val stripped = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '<' -> {
                    val close = text.indexOf('>', i + 1)
                    i = if (close >= 0) close + 1 else i + 1
                    if (close < 0) append(c) // 未闭合的 '<' 按普通字符处理
                }

                '{' -> {
                    val close = text.indexOf('}', i + 1)
                    i = if (close >= 0) close + 1 else i + 1
                    if (close < 0) append(c)
                }

                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
    val collapsed = stripped
        .replace('\u00A0', ' ') // 不换行空格
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (ignoreCase) collapsed.lowercase() else collapsed
}

/**
 * 判断一段（已归一化的）字幕文本是否包含某条引文。
 *
 * 两级匹配，顺序很重要：
 *
 * 1. **先按原样**匹配。这是精确的一级，能区分"他 是 谁"与"他是谁"这类差异。
 * 2. 只在第 1 级失败时，才退化为**忽略空格**的比较。
 *    理由：不同来源的字幕对中日文之间的空格处理不一致（有的加空格有的不加），
 *    为这种纯排版差异判"找不到证据"会让生成流程无谓失败。
 *
 * 注意方向性：放宽匹配只会让"引用更容易被找到"，即揭晓时间可能落到**更早**的那个 cue。
 * 因此第 2 级**只忽略空格**，不做标点、大小写以外的任何近似
 * （大小写由 [normalizeSubtitleText] 统一处理）。
 */
fun matchesSubtitleQuote(cueNormalizedText: String, quoteNormalized: String): Boolean {
    if (quoteNormalized.isEmpty()) return false
    if (cueNormalizedText.contains(quoteNormalized)) return true
    val cueNoSpace = cueNormalizedText.filterNot { it == ' ' }
    val quoteNoSpace = quoteNormalized.filterNot { it == ' ' }
    if (quoteNoSpace.isEmpty()) return false
    return cueNoSpace.contains(quoteNoSpace)
}

/**
 * 解析 WebVTT 或 SRT。
 *
 * 两种格式的差别只在时间行与块分隔，因此一套解析器覆盖：
 * - 可选的行内序号（SRT 有、WebVTT 可无）
 * - 时间行 `HH:MM:SS,mmm --> HH:MM:SS,mmm`（SRT 用逗号）或
 *   `HH:MM:SS.mmm --> HH:MM:SS.mmm`（WebVTT 用点），也容忍 `MM:SS.mmm`
 * - WebVTT 的 `WEBVTT` 头、`NOTE` / `STYLE` / `REGION` 块
 * - 句内换行
 */
fun parseSubtitles(text: String, source: String = "<memory>"): SubtitleParseResult {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val cues = mutableListOf<SubtitleCue>()
    val issues = mutableListOf<SubtitleParseIssue>()

    var i = 0
    var nextIndex = 1
    // 问题里报告的行号必须是源码里的**1 起行号**，否则使用者按行号去看会看错一行。
    fun reportIssue(lineIndex: Int, message: String) {
        issues += SubtitleParseIssue(source, lineIndex + 1, message)
    }

    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()

        // 空行：块分隔
        if (line.isEmpty()) {
            i++
            continue
        }
        // WebVTT 头
        if (line.startsWith("WEBVTT", ignoreCase = true)) {
            i++
            continue
        }
        // WebVTT 的 NOTE / STYLE / REGION 块：跳过直到空行
        if (
            line.startsWith("NOTE", ignoreCase = true) ||
            line.startsWith("STYLE", ignoreCase = true) ||
            line.startsWith("REGION", ignoreCase = true)
        ) {
            while (i < lines.size && lines[i].trim().isNotEmpty()) i++
            continue
        }

        // 可选序号行
        val maybeIndex = line.toIntOrNull()
        if (maybeIndex != null) {
            i++
            if (i >= lines.size) {
                reportIssue(i - 1, "序号之后没有时间行")
                break
            }
        }

        val timeLineIndex = i
        val timeLine = lines[i].trim()
        val range = parseTimeRange(timeLine)
        if (range == null) {
            // 时间行坏了：跳过**整个块**直到空行。
            // 只前进一行的话，该块的文本行会被当成新条目的开头，于是同一个坏块报出多个问题，
            // 使用者也看不出它们其实是同一处错误。
            reportIssue(timeLineIndex, "无法解析时间行：'${timeLine.take(60)}'")
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty()) i++
            continue
        }

        i++
        val textLines = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().isNotEmpty()) {
            textLines += lines[i]
            i++
        }
        val cueText = textLines.joinToString("\n").trim()
        if (cueText.isEmpty()) {
            reportIssue(timeLineIndex, "时间行之后没有文本")
            continue
        }

        cues += SubtitleCue(
            index = nextIndex++,
            startMillis = range.first,
            endMillis = range.second,
            text = cueText,
            lineNumber = timeLineIndex + 1,
        )
    }

    return SubtitleParseResult(cues, issues)
}

/** 解析 `start --> end`，返回毫秒区间；格式不认识时返回 null。 */
private fun parseTimeRange(line: String): Pair<Long, Long>? {
    val parts = line.split("-->")
    if (parts.size != 2) return null
    val start = parseTimeCode(parts[0].trim()) ?: return null
    // 结束时间后面可能跟着 WebVTT 的 cue 设置（如 "align:start position:10%"）
    val endToken = parts[1].trim().split(Regex("\\s+")).firstOrNull() ?: return null
    val end = parseTimeCode(endToken) ?: return null
    if (end < start) return null
    return start to end
}

/**
 * 解析 `HH:MM:SS,mmm` / `HH:MM:SS.mmm` / `MM:SS.mmm`，返回毫秒；不认识时返回 null。
 *
 * 公开而非 internal：时间码解析是这个工具链里最容易被写错、
 * 且错了会让揭晓时间整体偏移的一环（例如把 `.5` 当成 5ms 而不是 500ms），
 * 因此它需要被单独测试。测试代码与本模块是分开编译的，internal 对其不可见。
 */
fun parseTimeCode(raw: String): Long? {
    val normalized = raw.trim().replace(',', '.')
    val segments = normalized.split(':')
    if (segments.size !in 2..3) return null

    val last = segments.last()
    val dot = last.indexOf('.')
    val secondsPart = if (dot >= 0) last.substring(0, dot) else last
    val millisPart = if (dot >= 0) last.substring(dot + 1) else "0"
    if (secondsPart.isEmpty() || secondsPart.any { !it.isDigit() }) return null
    if (millisPart.isEmpty() || millisPart.any { !it.isDigit() }) return null

    val seconds = secondsPart.toLongOrNull() ?: return null
    // 毫秒位不足三位时按"毫秒"解释的比例补齐：".5" 视为 500ms，".50" 视为 500ms。
    val millis = when (millisPart.length) {
        1 -> millisPart.toLong() * 100
        2 -> millisPart.toLong() * 10
        3 -> millisPart.toLong()
        else -> millisPart.take(3).toLong()
    }
    if (seconds >= 60) return null

    val minutes = segments[segments.size - 2].let { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toLongOrNull() ?: return null
    }
    if (segments.size == 3 && minutes >= 60) return null
    val hours = if (segments.size == 3) {
        val part = segments[0]
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toLongOrNull() ?: return null
    } else {
        0L
    }

    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
}
