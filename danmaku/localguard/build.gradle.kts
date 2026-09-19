/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    id("ani.kmp-library")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    android {
        namespace = "me.him188.ani.danmaku.localguard"
    }
    sourceSets.commonMain {
        dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
            // 剧情包是磁盘格式：模块自己负责读写，因此需要 JSON。
            // 用 api 而不是 implementation：调用方（app 侧）需要拿到
            // KnowledgePackCodec / 序列化器来写包与测试往返。
            api(libs.kotlinx.serialization.json)
        }
    }
    sourceSets.commonTest {
        dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
