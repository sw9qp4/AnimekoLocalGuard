/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    id("ani.android-application")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.google.gms.google.services)
    idea
}

val archs = getPropertyOrNull("ani.android.abis")
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?.let { abis ->
        if (abis.size == 1 && abis.first() == "all") {
            listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        } else {
            abis
        }
    }
    ?: listOf("arm64-v8a")

android {
    namespace = "me.him188.ani.android"
    compileSdk = getIntProperty("android.compile.sdk")
    defaultConfig {
        applicationId = "me.him188.ani"
        minSdk = getIntProperty("android.min.sdk")
        targetSdk = getIntProperty("android.compile.sdk")
        versionCode = getIntProperty("android.version.code")
        versionName = project.version.toString()
        ndk {
            // Specifies the ABI configurations of your native
            // libraries Gradle should build and package with your app.
            abiFilters.clear()
            //noinspection ChromeOsAbiSupport
            abiFilters += archs
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include(*archs.toTypedArray())
            isUniversalApk = true // 额外构建一个
        }
    }
    signingConfigs {
        kotlin.runCatching { getProperty("signing_release_storeFileFromRoot") }.getOrNull()?.let {
            create("release") {
                storeFile = rootProject.file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
        kotlin.runCatching { getProperty("signing_release_storeFile") }.getOrNull()?.let {
            create("release") {
                storeFile = file(it)
                storePassword = getProperty("signing_release_storePassword")
                keyAlias = getProperty("signing_release_keyAlias")
                keyPassword = getProperty("signing_release_keyPassword")
            }
        }
    }
    packaging {
        jniLibs {
            // FFmpeg is launched as a process, so the native binary must be extracted to a filesystem path.
            useLegacyPackaging = true
        }
        resources {
            merges.add("META-INF/DEPENDENCIES") // log4j
            pickFirsts.add("META-INF/LICENSE.md")
            pickFirsts.add("META-INF/LICENSE-notice.md")

        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            // AnimekoLocalGuard: the release variant needs its own package id too. Without this,
            // a release build would carry the upstream id `me.him188.ani` and therefore collide
            // with the official app on the device — an install could never succeed (the signing
            // keys differ) and, worse, nothing would stop it from being *retried* after the user
            // uninstalls the official app. The debug variant already gets its suffix from the same
            // kind of property; this mirrors that instead of hard-coding a second default.
            getLocalProperty("ani.android.release.applicationIdSuffix")?.let {
                applicationIdSuffix = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                *sharedAndroidProguardRules(),
            )
        }
        debug {
            applicationIdSuffix = getLocalProperty("ani.android.debug.applicationIdSuffix") ?: ".debug2"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("default") {
            dimension = "distribution"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(projects.app.shared)
    implementation(projects.app.shared.application)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

//    implementation(libs.log4j.core)
//    implementation(libs.log4j.slf4j.impl)

    implementation(libs.ktor.client.core)
    implementation(libs.mediamp.ffmpeg)
}

idea {
    module {
        excludeDirs.add(file(".cxx"))
    }
}

googleServices {
    missingGoogleServicesStrategy = (getLocalProperty("ani.enable.firebase") ?: "false").toBooleanStrict()
        .let {
            if (it) MissingGoogleServicesStrategy.ERROR else MissingGoogleServicesStrategy.IGNORE
        }
}
