# LogiScope · 回滚与备份

> 目标：**任何时候都能退回到一个已知可用的状态**，并且知道退回到哪。

---

## 一、当前备份状态

| 项 | 状态 |
|---|---|
| 版本控制 | ✅ **已初始化**（**纯本地**，未配置任何远程仓库） |
| 提交历史 | `2a57a81` G0 骨架 → `2871b1b` 文档 → `85dd6ed` 两轮修复 → `0aea394`/`96fe803`/`f2228dd` 文档修正 → `ddc61be` 第三轮两处根因 + 键位换 F9 |
| **里程碑标签** | ✅ `g0-skeleton`（G0 完成，可编译可打包） |
| **临时标签** | ✅ `wip-round2-20260918` → 指向 `85dd6ed`（历史遗留：那一版的部分改动后来在 `ddc61be` 里被修掉了） |
| **工作区** | ⚠️ **有未提交改动**（本轮 G2：波形可视化 + 接缝修复，见下） |
| **最近一次可用提交** | ✅ 以 `git log --oneline -1` 的输出为准（本表只记**相对关系**：哪次是里程碑、哪次还没验证 —— 提交号写死在表里永远会落后一步） |
| 代码状态 | ✅ `BUILD SUCCESSFUL`，jar ≈77 KB，`verify-jar.py` 全部通过，离线断言 47/47 |
| 实机状态 | ✅ G1 / G1.5 / G1.6 / G1.7 / G3 已验（第三、四轮全部通过）；**G2 波形与接缝修复待第五轮验证** |
| 外部备份 | ⬜ 无（**本地磁盘版本管理**；要不要上传到 GitHub 见第七节） |
| 提交身份 | ⚠️ 仓库级占位值 `JesseTech <JesseTech@localhost>`，**建议改成真实信息** |

**已验证 / 未验证** 的界线：

| 提交 | 内容 | 实机验证 |
|---|---|---|
| `2a57a81` `2871b1b` | G0 骨架 + 文档 | ✅ 加载正常 |
| `85dd6ed` / `ddc61be` | G1 / G1.5 / G1.6 / G1.7 / G3 全部功能 | ✅ 第三、四轮全通过 |
| **本轮未提交的改动** | G2 波形可视化（`ChartData` / `ChartRenderer` / 面板重写）+ 关界面接缝修复 | ⬜ **待第五轮验证** |

**第五轮验证完成后要做的事**（把这一轮固化成里程碑）：

```bat
cd /d D:\dev\logiscope
:: 1) 把 VERIFY.md 第二节的勾打上，再提交文档
git add -A
git commit -m "docs: 第五轮实机验证通过（G2 波形）"

:: 2) 打正式里程碑标签
git tag -a g2-waveform -m "G2 完成：按物品分线的波形 + 图例 + 框选 + 面板可缩放"
```


改提交身份（只影响这个仓库，不动全局配置；改完只影响**之后**的提交）：

```bat
cd /d D:\dev\logiscope
git config user.name "你的名字"
git config user.email "你的邮箱"
```

**每次改动前后都要回来更新这张表。** 不知道自己站在哪，是 vibe coding 最大的风险。

---

## 二、初始化版本控制（一次性）

> ✅ **本项目已经做过这一步了**（当初就是这么建的，现状见第一节表）。
> 留着是给你以后建新工程时抄。

```bat
cd /d D:\dev\logiscope
git init
git add .
git commit -m "G0: 工程骨架 + 分层代码骨架 + 设计文档"
git tag -a g0-skeleton -m "G0 完成：可编译、可打包"
```

**这几步分别在干什么**：

| 命令 | 作用 | 有没有网络操作 |
|---|---|---|
| `git init` | 在项目目录里建一个 `.git` 隐藏文件夹，**这就是"仓库"本身** | 无（纯本地） |
| `git add .` | 把文件登记到"暂存区"（挑出这次要记的变化） | 无 |
| `git commit -m "..."` | 把暂存区打包成一个**快照**，永久记进 `.git` | 无 |
| `git tag -a <名字>` | 给某个快照贴一个"书签"，方便以后直接叫出它 | 无 |

### 为什么必须用 git 而不是手动复制文件夹

- 手动复制：不知道"上一个好状态"是哪个，也不知道两次之间改了什么
- git：`git diff` 能看清每一行的改动，`git checkout` 能精确回到任意一点
- 出问题时**第一件事是能对比**，对比能力比备份本身更重要

---

## 三、日常节奏

### 每完成一个可验证的小步就提交一次

```bat
cd /d D:\dev\logiscope
git status          :: 先看改了什么（只读，不改任何东西）
git diff            :: 看具体每一行的改动（只读）
git add -A          :: 把改动登记进暂存区
git commit -m "G1: 打通 Screen 按键拦截 → 选格 → 采样"
```

**提交粒度标准**：能一句话说清、且**已实际验证过**的改动。

### 还没验证、但又怕丢怎么办

两条路（本项目走的是第 1 条）：

1. **提交成一次明确标注"未验证"的提交**，并打 `wip-` 开头的临时标签
   （例：`wip-round2-20260918`）。好处是有安全网，代价是这次提交不算"已验证状态"。
2. **先不提交，只做整目录复制**（见第一节方案 B）。

⚠️ **不要指望 `git tag` 能保护未提交的改动** —— 标签只能指向已有的提交，
工作区里没提交的改动它一点都护不住。

### 每完成一个里程碑就打标签

```bat
git tag -a g1-minimal-loop -m "G1 完成：最小闭环在游戏内验证通过"
```

标签是**粗粒度的安全点**。回滚时优先回到标签，而不是某个随手提交。
命名约定：`g0-skeleton`（里程碑）、`wip-xxx-日期`（临时）、`g1-panel-overlay`（里程碑）。

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
git checkout master            :: ★ 本项目的分支叫 master，不是 main
git stash pop
```

> ⚠️ `git checkout <某个标签>` 会进入 **detached HEAD**（"游离头指针"）状态：
> 此时能看能编译，但**新提交不会被任何分支记住**，一转身就可能丢。
> 所以看完之后**必须 `git checkout master` 回到分支上**再继续干活。
> 新版 git 也可以用 `git switch`，语义更清楚：`git switch g0-skeleton` / `git switch master`。

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

1. `git status` 确认工作区干净（有没提交的改动就先 `git add -A && git commit`）
2. 记下当前提交号：`git log --oneline -1`
3. 再开始改

这样即使改崩了，也能一步回到"动手之前"。

> ⚠️ 常见误解：**在脏工作区上打标签没有任何保护作用** —— 标签指向的是提交，
> 不是工作区。想留安全点必须先提交。

---

## 八、本地版本管理 vs 上传到 GitHub

**结论：git 默认就是纯本机的磁盘版本管理，跟 GitHub 没有必然关系。**

| | 本地 git（本项目的现状） | 上传到 GitHub（可选） |
|---|---|---|
| 数据存在哪 | `D:\dev\logiscope\.git\` 这个隐藏文件夹 | 本机 `.git` + GitHub 服务器上的一份 |
| 需要联网吗 | ❌ 完全不需要 | ✅ 需要 |
| 需要账号 / 付费吗 | ❌ 不需要 | 需要 GitHub 账号（公共仓库免费） |
| 能做什么 | 提交、看历史、对比、回滚、打标签 —— 全部功能 | 额外获得：异地备份、多机同步、协作、在线浏览代码 |
| 本项目支持吗 | ✅ 已经在用 | ⬜ 未配置（`git remote -v` 是空的） |

也就是说：`git add` / `git commit` / `git tag` / `git log` / `git checkout` **全都在本机完成**，
一个字节都不会发到网上。只有 `git push` 才会把本机提交推到远程服务器。

### 什么时候该上传

| 需求 | 该不该上传 |
|---|---|
| 只是怕改崩了想回滚 | ❌ 不需要，本地就够了 |
| 硬盘坏了也想留一份 | ✅ 建议（这才叫"真正的外部备份"） |
| 想换台电脑继续开发 | ✅ 建议 |
| 想公开发布这个模组 | ✅ 建议（且要先想清楚用不用公开仓库） |

⚠️ **上传前必须想清楚的两件事**：

1. **公开仓库 = 代码对所有人可见。** 想只自己看就建 **private** 仓库。
   另外，提交里记录着作者名和邮箱（当前是占位的 `JesseTech@localhost`），
   要上传的话建议先按第一节改成本想公开的邮箱。
2. **`build/`、`run/`、`.gradle/`、`.idea/` 已经被 `.gitignore` 排除**，
   所以不会把几百 MB 的构建产物和本地存档传上去。这一点已经配好了，不用额外处理。

### 以后真要上传时的完整步骤（现在别执行）

```bat
:: 1) 去 github.com 新建一个空仓库（不要勾选 Add README / .gitignore）
:: 2) 把本机仓库和它关联起来
cd /d D:\dev\logiscope
git remote add origin https://github.com/<你的用户名>/<仓库名>.git

:: 3) 确认默认分支叫 master（GitHub 现在默认叫 main；两条路选一条）
git push -u origin master
::  或者先改名： git branch -M main  然后 git push -u origin main

:: 4) 之后每次上传就一句
git push
```

第一次 push 会要求登录（GitHub 现在不接受密码，要用 **Personal Access Token**
或装 Git Credential Manager；IDEA 里也能直接点登录）。
