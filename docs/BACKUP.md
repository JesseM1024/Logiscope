# LogiScope · 回滚与备份

> 目标：**任何时候都能退回到一个已知可用的状态**，并且知道退回到哪。
> 现状以 `git log` / `git tag` / `git remote -v` 的输出为准；本表只记相对关系。

---

## 一、当前备份状态

| 项 | 状态 |
|---|---|
| 版本控制 | 本地 git（`D:\dev\logiscope\.git`） |
| 远程仓库 | **已配置**：GitHub 公开仓库 `JesseM1024/Logiscope`（发布通道，见第八节） |
| 分支 | `main`（发布主线）；`1.20.1-neo`（1.20.1 移植分支，与 main 历史无关，见 `PORT-1.20.1.md`） |
| 里程碑标签 | `g0-skeleton`、`g1-panel-overlay`、`g2-waveform` |
| 发布标签 | `neoforge-1.21.1-0.1.0`、`neoforge-1.21.1-0.1.1`（一版一 tag 一 release） |
| 历史工作标签 | `wip-round2/7/8-20260918`、`wip-g2-20260918`、`round7-verified-20260918`（书签性质，可不管） |
| 工作区 | 以 `git status` 为准（本表不记具体提交号 —— 写死必然落后） |
| 代码状态 | `BUILD SUCCESSFUL`；`TestChart` **443 条**；`BenchRender` 全过；`verify-jar.py` 全过 |
| 实机状态 | 第十六轮④ 及更早全部通过；十六⑦⑨ / 十七① / 十八①-④ 待复测（清单在 `VERIFY.md`） |
| 提交身份 | 仓库级配置（`git config user.name/email`）；如需公开署名，先改这里 |

**最近完成的轮次**（详见 [`PROGRESS.md`](PROGRESS.md) / [`PROGRESS-HISTORY.md`](PROGRESS-HISTORY.md)）：
第十八轮①②③④（采样统一服务端 / 删旧提示 / 存档筛选框 / DATA 页完成按钮与刷新滑条）。

---

## 二、初始化版本控制（一次性）

> 本项目已完成。保留此节供新工程参考。

```bat
cd /d D:\dev\logiscope
git init
git add .
git commit -m "G0: 工程骨架 + 分层代码骨架 + 设计文档"
git tag -a g0-skeleton -m "G0 完成：可编译、可打包"
```

| 命令 | 作用 | 网络操作 |
|---|---|---|
| `git init` | 在项目目录建 `.git` 隐藏文件夹（即"仓库"本身） | 无 |
| `git add .` | 把文件登记进暂存区 | 无 |
| `git commit -m "..."` | 把暂存区打包成快照，永久记进 `.git` | 无 |
| `git tag -a <名字>` | 给快照贴书签 | 无 |

**为什么用 git 而不是手动复制文件夹**：手动复制无法回答"上一个好状态是哪个、两次之间改了什么"。
`git diff` 能看清每一行改动，`git checkout` 能精确回到任意一点 —— 出问题时，**对比能力比备份本身更重要**。

---

## 三、日常节奏

**每完成一个可验证的小步提交一次**：

```bat
cd /d D:\dev\logiscope
git status          :: 先看改了什么（只读）
git diff            :: 看每一行改动（只读）
git add -A
git commit -m "G1: 打通 Screen 按键拦截 → 选格 → 采样"
```

提交粒度标准：**能一句话说清、且已实际验证过**的改动。

**还没验证、又怕丢**：提交成一次明确标注"未验证"的提交，并打 `wip-` 开头的临时标签
（例 `wip-round2-20260918`）。注意：**`git tag` 不会保护未提交的改动** —— 标签只能指向已有提交，
脏工作区上打标签没有任何保护作用。

**每完成一个里程碑打标签**：

```bat
git tag -a g2-waveform -m "G2 完成：波形可视化验证通过"
```

标签是粗粒度安全点；回滚时优先回到标签。命名约定：`gN-xxx`（里程碑）、`wip-xxx-日期`（临时）、
`neoforge-<MC版本>-<模组版本>`（发布，见第八节）。

---

## 四、回滚操作

### 场景 A：改坏了，想回到上一次提交

```bat
git status
git diff
git checkout -- .                       :: 丢弃所有未提交改动（危险：不可恢复）
git checkout -- src/main/java/.../ScopeScreen.java   :: 或只丢弃某个文件
```

### 场景 B：整体退回上一个里程碑

```bat
git tag                        :: 列出安全点
git stash                      :: 暂存当前改动（可恢复）
git checkout g2-waveform       :: 回到该标签
:: 回来：
git checkout main              :: 本项目主线分支名是 main
git stash pop
```

`git checkout <标签>` 会进入 **detached HEAD** 状态：可看可编译，但新提交不会被分支记住。
看完之后必须 `git checkout main` 回到分支再继续。新版 git 可用 `git switch`（语义更清楚）。

### 场景 C：某个提交有问题，想撤销但保留历史

```bat
git revert <commit-hash>       :: 生成一个反向提交，历史完整
```

### 场景 D：强制回到某个标签

```bat
git reset --hard g2-waveform   :: 危险：会丢弃其后的所有提交
```

**优先 A / B / C，慎用 D** —— D 是唯一真正会丢东西的操作。

---

## 五、什么该提交，什么不该

`.gitignore` 已配好，此处说明理由：

| 不提交 | 原因 |
|---|---|
| `build/` | 构建产物，可从源码重建；体积大；含机器相关绝对路径 |
| `.gradle/` | Gradle 本地缓存与状态 |
| `run/` | 开发实例的存档、日志、配置，数百 MB 且属本地状态 |
| `.idea/` | IDE 配置含本地绝对路径 |

**要提交的**：`src/`、`*.gradle`、`gradle.properties`、`gradle/wrapper/`、`docs/`、
`dev-check/`（含 `lib/gson-*.jar`）、`*.py`（辅助脚本）、`README.md`。
注意：`gradle/wrapper/gradle-wrapper.jar` 是二进制但**必须提交**，缺了就无法用 wrapper 构建。

---

## 六、灾难恢复

### 情况 1：`build/` 被删 / 想彻底重建

```bat
gradlew.bat clean
gradlew.bat build
```

`~/.gradle/caches/neoformruntime/` 全局共享，`createMinecraftArtifacts` 会命中缓存
（首个项目约 820 秒，后续项目约 1.2 秒）。

### 情况 2：`~/.gradle` 整个缓存被清

需要重新下载 Minecraft 与 NeoForge（约 300–340 MB）并重新生成游戏资源（786 MB）。
资源可从现有实例免下载复用：

```bat
python reuse-assets.py
```

（脚本来自 `neoforge-1.21.1-walkthrough` 项目，可直接拷贝；原理：Minecraft 资源按 SHA1 内容寻址，
哈希相同则内容必然相同。）

### 情况 3：`neoforge.mods.toml` 中文乱码 / 游戏报 "not a valid mod file"

编码损坏，检查工具：

```bat
python check-toml-encoding.py
```

根治办法是 `build.gradle` 里的 `filteringCharset = 'UTF-8'`（本项目已设）。
详见 `WALKTHROUGH.md`（工具链穿行项目）阶段 2.5。

---

## 七、改动前的安全点

在可能高风险的改动（重构分层、换渲染方式、引入依赖）之前：

1. `git status` 确认工作区干净（有未提交改动就先提交）；
2. 记下当前提交号：`git log --oneline -1`；
3. 再开始改。

这样即使改崩，也能一步回到"动手之前"。

---

## 八、本地 git 与 GitHub 发布

git 默认是纯本机版本管理；本项目**额外**配置了 GitHub 公开仓库作为发布通道：

| 用途 | 通道 |
|---|---|
| 日常提交 / 回滚 | 本地 git（无需联网） |
| 发布与异地备份 | GitHub `JesseM1024/Logiscope`（`git remote origin`） |

**发布约定**（一版一 tag 一 GitHub Release）：

- tag 格式：`neoforge-<MC版本>-<模组版本>`，例 `neoforge-1.21.1-0.1.1`；
- 每个版本单独一个 tag，不把多个版本合并到同一个 tag；
- Release 附件 = `build/libs/logiscope-<版本>.jar`。

**推送的注意点**：沙箱 / 脚本环境里的 git CLI 对 GitHub 不可用（无凭据、SSL 握手失败）——
`push` 用系统终端或 IntelliJ（由 Git Credential Manager 带凭据）执行。
读取远程状态可用 `curl https://api.github.com/repos/JesseM1024/Logiscope/...`。

**发布前的提交身份检查**：提交里记录作者名 / 邮箱（`git config user.name / user.email`），
公开仓库建议先改成本想公开的信息。

完整发布流程（在用户终端执行）：

```bat
cd /d D:\dev\logiscope
git push origin main                     :: 推主线
git push origin neoforge-1.21.1-0.1.1    :: 推 tag
:: 之后在 GitHub 网页端建 Release 并上传 jar
```
