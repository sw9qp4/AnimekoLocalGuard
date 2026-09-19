/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.data.repository.danmaku

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.danmaku.localguard.policy.GuardConfigSource
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.logger
import kotlin.coroutines.CoroutineContext

/**
 * 本地弹幕过滤配置的持久化来源。
 *
 * 直接使用应用已有的 preferences DataStore：这是本项目的独立设置，放在独立键下
 * 不需要改动任何上游设置结构，因此不影响官方 Animeko 的数据兼容性。
 *
 * 读取失败（键缺失或内容无法识别）时回退到 [GuardUserConfig.Default]，其总开关是**关闭**的：
 * 新安装、旧版本数据或损坏数据都退化为"与官方行为完全一致"，不会擅自开始过滤。
 *
 * 只存两个键而不是整段 JSON：结构简单到不值得引入序列化依赖，
 * 而且单个字段损坏不会连带丢弃开关状态。
 *
 * ## 读取时机
 *
 * 已保存的值在**首次访问时**读取一次，因此 [current] 从一开始就是真实值，
 * 不存在"先默认关闭、稍后再纠正"的窗口——那个窗口会把用户已开启的开关短暂当成关闭。
 * 之后的变更由 [config] 的后台收集器持续同步到 [current]，因此判定路径始终是**无阻塞读取**。
 *
 * 已知限制：首次访问时使用阻塞读取（与上游读取播放器配置的做法一致）。它发生在不渲染视频的
 * 启动阶段，且只读一次；这是为了不在弹幕显示路径上做任何等待而付出的代价。
 */
class GuardConfigRepository(
    private val preferences: DataStore<Preferences>,
    /** 用于持续跟随设置变化的作用域上下文。 */
    parentContext: CoroutineContext,
) : GuardConfigSource {
    private val logger = logger<GuardConfigRepository>()

    /** 串行化"读取-修改-写回"，避免并发修改以旧值为基础互相覆盖。 */
    private val writeLock = Mutex()

    private val scope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))

    /**
     * 首次访问时读取已保存的值。
     *
     * 刻意**不放在构造函数**里：构造可能发生在阻塞上下文（例如测试的 `runBlocking`）中，
     * 在那里做阻塞读取会死锁。改为首次访问时读取——那时是在应用启动、不渲染视频的阶段，
     * 且只读一次。之后由 [config] 的收集器持续同步。
     */
    private val stateInternal: MutableStateFlow<GuardUserConfig> by lazy {
        MutableStateFlow(readInitial()).also { state ->
            scope.launch {
                preferences.data
                    .map { decode(it) }
                    .distinctUntilChanged()
                    .collect { state.value = it }
            }
        }
    }

    override val config: StateFlow<GuardUserConfig> get() = stateInternal.asStateFlow()

    override val current: GuardUserConfig get() = stateInternal.value

    override suspend fun update(transform: (GuardUserConfig) -> GuardUserConfig) {
        writeLock.withLock {
            // 在存储层内部完成"读-改-写"，因此变换函数一定作用在最新值上。
            var written: GuardUserConfig? = null
            preferences.edit { pref ->
                val new = transform(decode(pref))
                pref[ENABLED_KEY] = new.enabled
                pref[TIER_KEY] = new.tier.name
                written = new
            }
            // 立刻把镜像推进到写入后的值。
            //
            // 为什么必须在这里做：后台收集器运行在独立调度器上，可能在写入完成后稍晚才观察到变更。
            // 若只依赖收集器，就会出现"写入已返回、但 current/config 仍是旧值"的窗口——
            // 界面刚打开开关却仍显示关闭，或者判定路径短暂用旧档位。
            written?.let { stateInternal.value = it }
        }
    }

    override suspend fun start() {
        // 首次读取通常已经由 [current] 触发；这里只把它显式提前，避免在判定路径上等待。
        runCatching { stateInternal.value = preferences.data.map { decode(it) }.first() }
            .onFailure { logger.error(it) { "Failed to read local guard settings, keeping current value" } }
    }

    private fun readInitial(): GuardUserConfig = try {
        runBlocking { preferences.data.map { decode(it) }.first() }
    } catch (e: Exception) {
        logger.error(e) { "Failed to read local guard settings, falling back to default (disabled)" }
        GuardUserConfig.Default
    }

    private fun decode(pref: Preferences): GuardUserConfig {
        val enabled = pref[ENABLED_KEY] ?: GuardUserConfig.Default.enabled
        val rawTier = pref[TIER_KEY]
        val tier = if (rawTier == null) {
            GuardUserConfig.Default.tier
        } else {
            GuardTier.entries.firstOrNull { it.name == rawTier } ?: GuardUserConfig.Default.tier.also {
                logger.error { "Unknown local guard tier '$rawTier', falling back to default" }
            }
        }
        return GuardUserConfig(enabled = enabled, tier = tier)
    }

    companion object {
        /**
         * 存储键。**不要改名**，否则用户已保存的设置会丢失。
         */
        internal val ENABLED_KEY = booleanPreferencesKey("animeko_localguard_enabled")
        internal val TIER_KEY = stringPreferencesKey("animeko_localguard_tier")
    }
}
