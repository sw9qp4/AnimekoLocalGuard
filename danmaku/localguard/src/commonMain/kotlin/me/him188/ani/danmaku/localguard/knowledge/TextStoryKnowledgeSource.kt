/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.knowledge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 读取剧情包与对齐文件的原始文本。
 *
 * 只做"给我这个路径的文本"，**不做**解析与版本检查——那两件事由
 * [TextStoryKnowledgeSource] 统一处理，避免每个平台实现各写一遍。
 *
 * 声明为 `fun interface` 以便各平台用 lambda 提供实现（平台侧通常只有一个方法调用）。
 */
fun interface StoryPackTextReader {
    /**
     * 读取文本。文件不存在返回 null（= 没有这部作品的包，正常状态）。
     *
     * 实现**不得**抛异常；读取失败请返回 null 并由上层按"资料不可用"处理，
     * 否则一次磁盘错误会把播放路径打断。
     */
    suspend fun readText(path: String): String?
}

/**
 * 基于文本读取器的剧情包来源。
 *
 * 路径约定（**改名会让已放置的资料失效**）：
 * ```
 * story/<workId>.json           剧情包
 * story/<workId>.alignment.json 片源对齐（可选）
 * ```
 *
 * 用 `workId` 而不是标题做文件名：标题会变（改名、简繁、别名），
 * 作品标识不会。这里的 `workId` 由调用方决定（当前是上游 Bangumi `subjectId`）。
 *
 * 缓存的是**已解析的包**而不是原始文本：解析是这里唯一较贵的一步，
 * 而文本在设备上很小。缓存同时使"每条弹幕都不读盘"成立。
 */
class TextStoryKnowledgeSource(
    private val reader: StoryPackTextReader,
    private val directory: String = "story",
) : StoryKnowledgeSource {

    private val lock = Mutex()

    /** workId -> 结果。含失败结果：坏包不必每次重新解析。 */
    private val packCache = mutableMapOf<String, KnowledgeLoadResult>()
    private val alignmentCache = mutableMapOf<String, TimeAlignment?>()

    override suspend fun load(workId: String): KnowledgeLoadResult {
        lock.withLock { packCache[workId] }?.let { return it }

        val text = reader.readText(packPath(workId))
        val result = loadFromText(text)

        lock.withLock { packCache[workId] = result }
        return result
    }

    override suspend fun loadAlignment(workId: String): TimeAlignment? {
        lock.withLock { alignmentCache[workId] }?.let { return it }

        val text = reader.readText(alignmentPath(workId))
        // 解析不了就等于"没有配置对齐"：调用方会按 Unaligned 降级。
        // **不得**退化成偏移 0——那会让未对齐的片源被当成已对齐。
        val alignment = text?.let { KnowledgePackCodec.decodeAlignmentOrNull(it) }

        lock.withLock { alignmentCache[workId] = alignment }
        return alignment
    }

    /** 清空缓存（资料更新后调用）。 */
    suspend fun invalidate() {
        lock.withLock {
            packCache.clear()
            alignmentCache.clear()
        }
    }

    private fun packPath(workId: String) = "$directory/$workId.json"

    private fun alignmentPath(workId: String) = "$directory/$workId.alignment.json"
}
