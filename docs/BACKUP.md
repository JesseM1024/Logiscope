# LogiScope · 回滚与备份

> 目标：**任何时候都能退回到一个已知可用的状态**，并且知道退回到哪。

---

## 一、当前备份状态

| 项 | 状态 |
|---|---|
| 版本控制 | ⬜ **尚未初始化**（见第二节，第一步就做） |
| 里程碑标签 | ⬜ 无（G0 完成后打第一个） |
| 外部备份 | ⬜ 无 |
| 最近一次可用提交 | ⬜ 无 |

**每次改动前后都要回来更新这张表。** 不知道自己站在哪，是 vibe coding 最大的风险。

---

## 二、初始化版本控制（一次性）

```bat
cd /d D:\dev\logiscope
git init
git add .
git commit -m "G0: 工程骨架 + 分层代码骨架 + 设计文档"
git tag -a g0-skeleton -m "G0 完成：可编译、可打包"
```

### 为什么必须用 git 而不是手动复制文件夹

- 手动复制：不知道"上一个好状态"是哪个，也不知道两次之间改了什么
- git：`git diff` 能看清每一行的改动，`git checkout` 能精确回到任意一点
- 出问题时**第一件事是能对比**，对比能力比备份本身更重要

---

## 三、日常节奏

### 每完成一个可验证的小步就提交一次

```bat
git add -A
git commit -m "G1: 打通 Screen 按键拦截 → 选格 → 采样"
```

**提交粒度标准**：能一句话说清且**已实际验证过**的改动。
不要提交"改了一堆东西但还没试"的状态 —— 那样出问题就不知道该回退到哪。

### 每完成一个里程碑就打标签

```bat
git tag -a g1-minimal-loop -m "G1 完成：最小闭环在游戏内验证通过"
```

标签是**粗粒度的安全点**。回滚时优先回到标签，而不是某个随手提交。

---

## 四、回滚操作

### 场景 A：改坏了，想回到上一次提交

```bat
:: 先看看改了什么
git status
git diff

:: 丢弃所有未提交的改动（危险：不可恢复）
git checkout -- .

:: 只丢弃某个文件
git checkout -- src/main/java/com/jessetech/logiscope/ui/ScopeScreen.java
```

### 场景 B：改了一阵子，想整体退回上一个里程碑

```bat
git tag                        :: 列出所有安全点
git stash                      :: 先把当前改动暂存起来（可恢复）
git checkout g0-skeleton       :: 回到 G0 状态
:: 想回来：
git checkout main
git stash pop
```

### 场景 C：某个提交有问题，想撤销它但保留历史

```bat
git revert <commit-hash>       :: 生成一个"反向提交"，历史完整
```

### 场景 D：彻底不要了，强制回到某个标签

```bat
:: ⚠️ 危险操作，会丢弃之后的所有提交
git reset --hard g0-skeleton
```

**优先用 A / B / C，慎用 D。** D 是唯一会真正丢东西的操作。

---

## 五、什么该提交，什么不该

`.gitignore` 已经配好，这里说明**为什么**：

| 不提交 | 原因 |
|---|---|
| `build/` | 构建产物，可从源码重建；体积大；含机器相关的绝对路径 |
| `.gradle/` | Gradle 的本地缓存与状态 |
| `run/` | 开发实例的存档、日志、配置，几百 MB 且是本地状态 |
| `.idea/` | IDE 配置含本地绝对路径，多人协作时会互相覆盖 |

**要提交的**：`src/`、`*.gradle`、`gradle.properties`、`gradle/wrapper/`、
`docs/`、`*.py`（辅助脚本）、`README.md`

> ⚠️ 例外：`gradle/wrapper/gradle-wrapper.jar` 是二进制，但**必须提交** ——
> 它是 wrapper 的入口，缺了就 ./gradlew 不了。

---

## 六、灾难恢复

### 情况 1：`build/` 被删了 / 想彻底重建

```bat
gradlew.bat clean
gradlew.bat build
```

由于 `~/.gradle/caches/neoformruntime/` 是**全局共享**的，
`createMinecraftArtifacts` 会命中缓存。实测：首个项目 820 秒，**后续项目约 1.2 秒**。

### 情况 2：`~/.gradle` 整个缓存被清了

需要重新下载 Minecraft 与 NeoForge（约 300–340 MB），
并重新生成游戏资源（786 MB）。**资源可以从现有实例免下载复用**：

```bat
python reuse-assets.py
```

（脚本来自隔壁 `neoforge-1.21.1-walkthrough` 项目，可直接拷过来。
原理：Minecraft 资源按 SHA1 内容寻址，哈希相同则内容必然相同。）

### 情况 3：`neoforge.mods.toml` 出现中文乱码 / 游戏报 "not a valid mod file"

编码损坏。检查工具：

```bat
python check-toml-encoding.py
```

根治办法是 `build.gradle` 里的 `filteringCharset = 'UTF-8'`（本项目已设）。
详见隔壁项目的 `WALKTHROUGH.md` 阶段 2.5。

---

## 七、推荐的检查点清单

在动手做可能有风险的改动（重构分层、换渲染方式、引入依赖）之前：

1. `git status` 确认工作区干净
2. 打一个临时标签：`git tag -a wip-<一句话>-<日期> -m "..."` 
3. 再开始改

这样即使改崩了，也能一步回到"动手之前"。
