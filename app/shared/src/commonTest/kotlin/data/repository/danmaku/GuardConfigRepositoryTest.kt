/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 本文件属于新增模块，遵循上游 Animeko 的 AGPL-3.0 许可。
 * 上游: https://github.com/open-ani/ani
 */

package me.him188.ani.app.data.repository.danmaku

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.danmaku.localguard.policy.GuardTier
import me.him188.ani.danmaku.localguard.policy.GuardUserConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 配置持久化测试。
 *
 * 重点验证：
 * 1. 默认（无数据 / 数据无法识别）必须是**关闭**，否则会改变官方 Animeko 的既有行为。
 * 2. "读取-修改-写回"必须以最新值为基础，快速连点不会互相覆盖。
 * 3. 同步的 [GuardConfigRepository.current] 从一开始就是已保存的真实值，
 *    **不得**先报默认值（关闭）再纠正——那个窗口会把用户已开启的开关当成关闭。
 */
class GuardConfigRepositoryTest {

    private fun emptyStore(): MemoryDataStore<Preferences> = MemoryDataStore(emptyPreferences())

    private fun storeWith(enabled: Boolean? = null, tier: String? = null): MemoryDataStore<Preferences> =
        MemoryDataStore(
            mutablePreferencesOf(
                *buildList {
                    if (enabled != null) add(GuardConfigRepository.ENABLED_KEY to enabled)
                    if (tier != null) add(GuardConfigRepository.TIER_KEY to tier)
                }.toTypedArray(),
            ),
        )

    /**
     * 建库并使用独立作用域。
     *
     * 用 `use` 而不是手写 try/finally：作用域一定会被取消，避免收集器泄漏到其他用例。
     */
    private inline fun <T> withRepo(
        prefs: MemoryDataStore<Preferences>,
        block: (GuardConfigRepository) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob())
        try {
            return block(GuardConfigRepository(prefs, scope.coroutineContext))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `missing keys mean disabled`() = runTest {
        withRepo(emptyStore()) { r ->
            assertFalse(r.current.enabled, "缺失配置时必须保持关闭")
            assertEquals(GuardTier.BALANCED, r.current.tier, "默认档位应为均衡")
            assertEquals(GuardUserConfig.Default, r.config.first())
        }
    }

    @Test
    fun `unknown tier falls back to default instead of failing`() = runTest {
        // 模拟旧版本/未来版本写入的、当前无法识别的档位名
        withRepo(storeWith(true, "SUPER_STRICT")) { r ->
            assertTrue(r.current.enabled, "档位无法识别不得连带丢弃开关")
            assertEquals(GuardTier.BALANCED, r.current.tier, "无法识别的档位必须回退默认")
        }
    }

    @Test
    fun `saved value survives a new repository instance`() = runTest {
        val prefs = emptyStore()
        withRepo(prefs) { r ->
            r.setEnabled(true)
            r.setTier(GuardTier.STRICT)
        }
        withRepo(prefs) { reopened ->
            assertTrue(reopened.current.enabled, "开关必须持久化")
            assertEquals(GuardTier.STRICT, reopened.current.tier, "档位必须持久化")
        }
    }

    @Test
    fun `update applies transform to the latest stored value`() = runTest {
        withRepo(emptyStore()) { r ->
            r.setEnabled(true)
            r.setTier(GuardTier.LENIENT)
            // 若实现先取旧值再整体写回，这里会丢失 enabled
            r.setTier(GuardTier.STRICT)
            val saved = r.config.first()
            assertTrue(saved.enabled, "改档位不得把开关改回去")
            assertEquals(GuardTier.STRICT, saved.tier)
        }
    }

    @Test
    fun `current is the saved value immediately and never reports default first`() = runTest {
        val prefs = emptyStore()
        withRepo(prefs) { r -> r.setEnabled(true) }
        // 全新实例：current 必须直接是已保存的值，而不是"先关闭、稍后纠正"
        withRepo(prefs) { fresh ->
            assertTrue(fresh.current.enabled, "已保存为开启时，current 不得报告为关闭")
        }
    }

    @Test
    fun `config flow reflects updates`() = runTest {
        withRepo(emptyStore()) { r ->
            assertFalse(r.config.first().enabled)
            r.setEnabled(true)
            assertTrue(r.config.first().enabled, "config 流必须反映最新写入")
        }
    }

    @Test
    fun `storage keys are stable`() = runTest {
        // 键名改名会让用户已保存的设置丢失，因此这里把它钉死。
        assertEquals("animeko_localguard_enabled", GuardConfigRepository.ENABLED_KEY.name)
        assertEquals("animeko_localguard_tier", GuardConfigRepository.TIER_KEY.name)
        assertEquals(stringPreferencesKey("animeko_localguard_tier"), GuardConfigRepository.TIER_KEY)

        val prefs = emptyStore()
        withRepo(prefs) { r ->
            r.setEnabled(true)
            r.setTier(GuardTier.LENIENT)
            val stored = prefs.data.first()
            assertEquals(true, stored[GuardConfigRepository.ENABLED_KEY])
            assertEquals("LENIENT", stored[GuardConfigRepository.TIER_KEY])
        }
    }
}
