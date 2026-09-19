/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.danmaku.localguard.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.danmaku.localguard.knowledge.StoryPackTextReader
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger

/**
 * 从安装包资源（assets）读取剧情包文本。
 *
 * 选择 assets 而不是可写目录的原因：
 * - 资料是**只读**输入，放在 assets 里与 APK 一起分发，用户无需手动放置；
 * - 不涉及运行时下载，因此不引入网络与校验问题；
 * - 官方 Animeko 不读这个目录，所以不会与官方起冲突。
 *
 * 目录名固定为 `story`，与 `TextStoryKnowledgeSource` 的默认路径约定一致。
 *
 * 当前 APK 里**没有**放置任何剧情包（见 docs/DECISIONS.md：真实作品资料需要用户提供），
 * 因此实际行为是"所有作品都没有包"，守卫按资料缺失降级。这是如实状态，不是失败。
 */
class AssetStoryPackTextReader(
    /**
     * 读取 assets 中某个文件的文本；文件不存在返回 null。
     *
     * 由平台注入，避免在 common 代码里依赖具体平台的 AssetManager。
     */
    private val readAsset: suspend (String) -> String?,
) : StoryPackTextReader {
    private val logger = logger<AssetStoryPackTextReader>()

    override suspend fun readText(path: String): String? = withContext(Dispatchers.IO_) {
        try {
            readAsset(path)
        } catch (e: Exception) {
            // 读取失败一律按"没有这个文件"处理：调用方会降级为资料缺失。
            // 不得向上抛——一次资源读取错误不该打断播放路径。
            logger.error(e) { "Failed to read local guard asset '$path'" }
            null
        }
    }
}
