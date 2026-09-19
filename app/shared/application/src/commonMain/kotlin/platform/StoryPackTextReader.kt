/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.platform

import me.him188.ani.danmaku.localguard.knowledge.StoryPackTextReader

/**
 * 取当前平台的剧情包文本读取器。
 *
 * 只有 Android 有"随包分发、只读"的 assets 载体；桌面与 iOS 上没有等价物，
 * 因此那些平台返回一个"什么也读不到"的实现——效果是**所有作品都没有包**，
 * 守卫据此按资料缺失降级。
 *
 * 刻意**不**在缺失平台上偷偷去读可写目录：那会让"资料从哪来"变得不可预期
 * （谁写进去的？什么时候更新的？是否可信？），而剧情包是安全相关的输入。
 * 需要非 Android 平台支持时应显式设计一个受控来源，而不是顺手加个文件路径。
 *
 * 写成"接收 context 的顶层函数"而不是扩展函数：与上游既有的
 * `createMeteredNetworkDetector(context)` 等 expect/actual 保持一致。
 */
expect fun getStoryPackTextReader(context: Context): StoryPackTextReader
