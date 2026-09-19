/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.platform

import me.him188.ani.danmaku.localguard.knowledge.StoryPackTextReader

/**
 * iOS：没有与 Android assets 等价的"随包分发、只读"载体，因此不提供剧情包。
 *
 * 返回"什么也读不到"的实现，效果是所有作品都被视为没有包，守卫按资料缺失降级。
 * 刻意不在缺失平台上退回读可写目录：剧情包是安全相关的输入，
 * 来源必须是显式设计的，不能顺手加个文件路径。
 */
actual fun getStoryPackTextReader(context: Context): StoryPackTextReader =
    StoryPackTextReader { null }
