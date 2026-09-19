/*
 * AnimekoLocalGuard - 本地 AI 弹幕过滤（非官方修改版）
 *
 * 这是一个**本地开发用的独立测试运行器**，不是产品代码。
 *
 * 目的：在项目级 Gradle 环境就绪之前，能够立即对 localguard 纯逻辑模块执行真实的
 * kotlin.test 测试方法，而不是"看着像测试但没有运行"。
 *
 * 一旦 :danmaku:localguard 能通过上游 Gradle 构建，应以 Gradle 测试任务为准
 * （见 PROGRESS.md 的状态区分：本运行器的结果记为 UNIT_TESTED_standalone）。
 */

@file:JvmName("LocalGuardTestMain")

package me.him188.ani.danmaku.localguard.devrun

import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * 测试发现方式说明：
 *
 * 上游使用 `kotlin.test`，在 JVM 上 `kotlin.test.Test` 是 JUnit 5
 * `org.junit.jupiter.api.Test` 的 typealias。因此运行器**按注解全名**识别，
 * 而不是直接引用某个测试框架的类 —— 这样无论最终由 JUnit4 还是 JUnit5 提供
 * 注解都能正确发现用例。
 */
private val TEST_ANNOTATION_NAMES = setOf(
    "kotlin.test.Test",
    "org.junit.jupiter.api.Test",
    "org.junit.Test",
)

/**
 * 单个用例的时间上界。
 *
 * 存在的理由：本项目真实发生过"某个用例永久阻塞、运行器只留下一个没有输出的超时"的情况，
 * 排查成本很高。超时后打印该用例并**转储全部线程栈**，可以直接定位到阻塞点。
 * 判定为失败（而不是跳过），因为挂住的用例没有被验证。
 */
private const val TEST_TIMEOUT_MILLIS = 30_000L

private fun isTestAnnotated(m: java.lang.reflect.Method): Boolean =
    m.annotations.any { it.annotationClass.java.name in TEST_ANNOTATION_NAMES }

private object TestRunner {

    data class Result(val name: String, val passed: Boolean, val detail: String? = null)

    private sealed interface Outcome {
        data object Passed : Outcome
        data class Failed(val detail: String) : Outcome
        data class TimedOut(val stackTrace: String) : Outcome
    }

    /** 在独立线程上执行，超时则转储该线程的栈。 */
    private fun runWithTimeout(body: () -> Unit): Outcome {
        var failure: Throwable? = null
        val thread = Thread {
            try {
                body()
            } catch (e: InvocationTargetException) {
                failure = e.targetException
            } catch (e: Throwable) {
                failure = e
            }
        }
        thread.isDaemon = true
        thread.name = "localguard-test"
        thread.start()
        thread.join(TEST_TIMEOUT_MILLIS)
        if (thread.isAlive) {
            val frames = thread.stackTrace.joinToString("\n") { "        at $it" }
            thread.interrupt()
            return Outcome.TimedOut(frames)
        }
        val f = failure
        return if (f == null) {
            Outcome.Passed
        } else {
            Outcome.Failed("${f.javaClass.simpleName}: ${f.message}")
        }
    }

    fun runAll(
        classes: List<Class<*>>,
        onStart: (String) -> Unit = {},
    ): List<Result> {
        val results = mutableListOf<Result>()
        for (c in classes) {
            val tests = c.methods
                .filter { isTestAnnotated(it) }
                .sortedBy { it.name }

            for (m in tests) {
                val label = "${c.simpleName}.${m.name}"
                onStart(label)
                val instance = try {
                    c.getDeclaredConstructor().newInstance()
                } catch (e: Throwable) {
                    results += Result(label, false, "instantiation failed: ${e.message}")
                    continue
                }
                try {
                    // 在独立线程上运行用例，这样超时后能打印线程栈并继续后面的用例，
                    // 而不是让整个运行器永久卡住。
                    val outcome = runWithTimeout {
                        m.invoke(instance)
                    }
                    when (outcome) {
                        is Outcome.Passed -> results += Result(label, true)
                        is Outcome.Failed -> results += Result(label, false, outcome.detail)
                        is Outcome.TimedOut -> results += Result(
                            label, false,
                            "TIMED OUT after ${TEST_TIMEOUT_MILLIS}ms; blocked thread:\n" +
                                    outcome.stackTrace,
                        )
                    }
                } catch (e: InvocationTargetException) {
                    val cause = e.targetException
                    results += Result(label, false, "${cause.javaClass.simpleName}: ${cause.message}")
                } catch (e: Throwable) {
                    results += Result(label, false, "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        return results
    }

    fun report(results: List<Result>, suite: String): Boolean {
        println("=== $suite ===")
        var pass = 0
        for (r in results) {
            if (r.passed) {
                pass++
                println("PASS  ${r.name}")
            } else {
                println("FAIL  ${r.name}")
                println("      -> ${r.detail}")
            }
        }
        val fail = results.size - pass
        println("--- $suite: total=${results.size} pass=$pass fail=$fail")
        return fail == 0
    }
}

/**
 * 入口。必须写成**顶层函数**并配合 @file:JvmName，才能得到可直接运行的静态 main
 * （Kotlin `object` 里的 @JvmStatic main 不会被 java 识别为应用入口）。
 *
 * 用例类通过**扫描已编译的测试输出目录**自动发现，而不是硬编码类名：
 * 硬编码时新增的测试类会被静默漏跑（总数看起来正常，实际少跑），
 * 这是本运行器最初真实发生过的问题。
 */
fun main(args: Array<String>) {
    val testOutputDir = args.firstOrNull()
        ?: System.getProperty("localguard.testClassesDir")
        ?: error("usage: LocalGuardTestMain <compiled-test-classes-dir> [onlyClassSubstring]")

    // 可选：只跑类名包含该子串的用例类，便于在某个类挂住时快速定位到具体类。
    val onlyClassSubstring = args.getOrNull(1)

    val dir = File(testOutputDir)
    require(dir.isDirectory) { "not a directory: $testOutputDir" }

    val basePackage = "me.him188.ani.danmaku.localguard"
    val classes = dir.walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .map { it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
        .filter { it.startsWith("$basePackage.") }
        // 匿名类与 lambda 合成类（Foo$1、Foo$bar$1）不是用例类。
        .filterNot { it.substringAfterLast('.', "").contains('$') }
        .filter { onlyClassSubstring == null || it.contains(onlyClassSubstring) }
        .map { name ->
            try {
                Class.forName(name)
            } catch (e: Throwable) {
                println("SKIP  $name (cannot load: ${e.javaClass.simpleName}: ${e.message})")
                null
            }
        }
        .filterNotNull()
        .filter { c -> c.methods.any { isTestAnnotated(it) } }
        .sortedBy { it.name }
        .toList()

    println("[discovery] ${classes.size} test classes under $testOutputDir")

    // 逐用例打印“开始”行并立即 flush：若某用例挂住或崩溃，日志能直接指出是哪一个，
    // 而不是只留下一个没有输出的超时。
    val results = TestRunner.runAll(classes) { label -> println("RUN   $label"); System.out.flush() }
    val ok = TestRunner.report(results, "LOCALGUARD_POLICY")
    if (!ok) {
        println("RESULT: FAILED")
        System.exit(1)
    }
    println("RESULT: OK")
}
