/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.platform

import me.him188.ani.danmaku.localguard.knowledge.StoryPackTextReader

/**
 * Android 上的剧情包文本读取器：从 APK 的 assets 目录读取。
 *
 * 只有 Android 有 assets 这种"随包分发、只读"的载体。
 *
 * 目录与 `TextStoryKnowledgeSource` 的默认约定一致（`story/<workId>.json`）。
 * 当前 APK 里没有放置任何剧情包，因此实际行为是"所有作品都没有包"，
 * 守卫按资料缺失降级——这是如实状态，不是失败。放置资料后无需改代码即可生效。
 */
actual fun getStoryPackTextReader(context: Context): StoryPackTextReader =
    StoryPackTextReader { path ->
        // assets.open 抛 FileNotFoundException 表示文件不存在；读取器会把任何异常都按
        // "没有这个文件"处理，因此这里不需要额外的存在性检查。
        context.assets.open(path).bufferedReader().use { it.readText() }
    }
