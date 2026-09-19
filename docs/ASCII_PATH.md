# 本机 ASCII 构建路径（junction）

**状态：** 已实测（`BUILD SUCCESSFUL`，上游测试任务通过 116/116）
**最后更新：** 2026-09-19

---

## 1. 为什么需要它

工作区路径是 `D:\模型\AnimekoLocalGuard`（含非 ASCII 字符"模型"）。这会造成两个**互相独立**的问题：

### 1a. AGP 拒绝非 ASCII 项目路径

AGP 直接报 `Your project path contains non-ASCII characters` 并中止。
`android.overridePathCheck=true` 在本项目的 included build 结构下未生效（已实测多种传参方式）。

### 1b. Gradle 9.3.1 的测试 worker 启动失败（更隐蔽，也更危险）

Gradle 启动测试 worker 时不是把 classpath 直接放在命令行上，而是写进一个 `@argfile`：

```
<GRADLE_USER_HOME>\.tmp\gradle-worker-classpath<random>txt
```

该文件第一行是 `-cp`，第二行是 classpath，其中第一项为

```
<GRADLE_USER_HOME>\caches\9.3.1\workerMain\gradle-worker.jar
```

当这个路径含非 ASCII 字符时，worker JVM 报

```
java.lang.ClassNotFoundException:
    worker.org.gradle.process.internal.worker.GradleWorkerMain
```

并立即以退出码 1 结束。**Gradle 只显示**：

```
Execution failed for task ':danmaku:localguard:testAndroidHostTest'.
> Test process encountered an unexpected problem.
   > Process 'Gradle Test Executor 1' finished with non-zero exit value 1
```

**不产出任何测试结果文件**，看起来像代码缺陷，实际是环境问题。

### 对照实验（确认结论）

同一份 jar 内容，两种加载方式结果不同：

| 方式 | 结果 |
|---|---|
| `java -cp <含非ASCII路径的jar> worker...GradleWorkerMain` | 类加载**成功**（随后抛 `EOFException`，因为它等待 stdin） |
| `java @<argfile，其中 classpath 含非 ASCII 路径> 同一主类` | `ClassNotFoundException` |

argfile 字节经检查为干净的 UTF-8 + CRLF（`-cp\r\nD:\\模型\\...`），并非文件损坏。
因此结论是：**问题出在 argfile 展开时对非 ASCII 路径的处理**，而不是文件本身或类加载。

---

## 2. 解决办法

在盘符根建立两个**目录联接（junction）**，把路径变成纯 ASCII：

```
D:\alrepo   -> D:\模型\AnimekoLocalGuard
D:\al-tools -> D:\模型\AnimekoLocalGuard\.tools
```

创建（**需要管理员权限**；junction 不需要开发者模式也无需格式化）：

```cmd
mklink /J D:\alrepo   D:\模型\AnimekoLocalGuard
mklink /J D:\al-tools D:\模型\AnimekoLocalGuard\.tools
```

特点：

- junction **不复制**任何文件，`D:\alrepo` 与工作区是**同一物理目录**。
- 源码改动立即可见，不需要同步。
- 删除即还原：`rmdir D:\alrepo`、`rmdir D:\al-tools`（`rmdir` 只删链接，不删目标内容）。

`GRADLE_USER_HOME` 也必须走 ASCII：`D:\al-tools\gradle-home`（即 `<repo>\.tools\gradle-home`），
因为 argfile 的第一项就是它下面的 `gradle-worker.jar`。

---

## 3. 现状与后续

- `tools/build_android.ps1` 默认从 `D:\alrepo` 构建，并在 junction 缺失时尝试自动创建。
- `gradle.properties` 里的 `org.gradle.java.installations.paths` 指向 `D:\al-tools\jdk21\...`。
- **长期正解**：把工作区本身迁到纯 ASCII 路径（例如 `D:\animeko\`），
  这样连同 junction 一起都不再需要。迁移前应先确认 DSH 会话工作区仍是 `D:\模型`。

---

## 4. 换机器时的检查清单

1. 建两个 junction（或把仓库放到 ASCII 路径）。
2. 确认 `<GRADLE_USER_HOME>` 路径为 ASCII。
3. 跑 `tools/build_android.ps1 -Task ":danmaku:localguard:testAndroidHostTest"`，
   并**检查 `test-results/*.xml` 里用例数非零**——只看 `BUILD SUCCESSFUL` 不足以说明测试真的跑了。
