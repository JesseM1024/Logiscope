# LogiScope

**纯客户端物流探测示波器** —— 给模组机器装一台示波器。

按 `F9` 呼出面板：可以**创建多个探针**同时监测（面板里是一个"正在运行的探针"列表，
每行末尾的 `×` 停掉并删掉那个实例），一个探针可以同时监控**好几个容器格**，
记录结果是把它们**加在一起**的聚合值，再画成带坐标轴的波形。

选格有两条路：点「选择容器格」之后**光标只用来点格子**（不再与物品交互），
左键点/划过的格子被选中、右键取消，也可以直接在列表里勾选；
没有界面的设备（只有物品栏的机器）则退回列表选格。

面板是一个**可以拖动、可以缩放的浮层**：它不会盖住原容器界面，你可以边看机器边看波形。
波形按物品分线、各有各的颜色（物品切换处、读取失败处、暂停处自然断开），图例可以点掉不想看的线，
还能用鼠标框选一段区间看那一段的净变化。关掉容器界面之后记录也能继续
（单人下**每个世界 tick** 直接从方块实体读真值 —— 世界走一格记一格，
`/tick rate` 调到多少就采多少；多人记断档，跨空档的差值依然有效）。

> 全程只读客户端已同步的数据。**不发网络包、不需要服务端安装、不注册任何物品。**

---

## 快速开始

```bat
:: 构建
gradlew.bat build

:: 进游戏自测（会启动一个独立的开发客户端）
gradlew.bat runClient

:: 如果 Gradle 运行任务出问题，可生成脱离 Gradle 的启动脚本
gradlew.bat createLaunchScripts
::   → build\moddev\runClient.cmd
```

> ⚠️ **Windows 上请用 `gradlew.bat`，不要用 Git Bash 的 `./gradlew`**
> （cygpath 路径不匹配，会报 `ClassNotFoundException: GradleWrapperMain`）。
> Git Bash 下的替代写法见下方「构建备忘」。

**环境要求**

| 项 | 值 |
|---|---|
| JDK | 21（`org.gradle.java.home` 已锁定） |
| Gradle | 9.2.1（wrapper 自带） |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.209 |
| 模组版本 | `0.1.1-1.21.1`（master 主线） |

> **版本分支约定**：`master` = 1.21.1 主线；`mc-1.20.1` 分支 = MC 1.20.1 + NeoForge 47.1.x 移植线
> （构建插件换成 `net.neoforged.moddev.legacyforge`，Java 17，产物 jar 名 `logiscope-0.1.0-1.20.1`）。
> 移植方案与两个版本线的 API 差异记录在 [`docs/PORT-1.20.1.md`](docs/PORT-1.20.1.md)。
> 通用 bugfix 默认进 master，再 cherry-pick 到 `mc-1.20.1`。
>
> **发布 tag 约定**：`<加载器>-<MC版本>-<模组版本>`（例：`neoforge-1.21.1-0.1.0`）；
> 一个版本 = 一个 tag = 一个 GitHub Release，不把多个版本合并到同一个 tag。

---

## 文档导航（vibe coding 前请按顺序读）

| 文档 | 内容 | 什么时候读 |
|---|---|---|
| [`docs/VISION.md`](docs/VISION.md) | 项目方向、核心依据、**明确不做什么** | 开始任何新功能前 |
| [`docs/GOALS.md`](docs/GOALS.md) | G0–G7 分目标与**可验证的验收标准** | 想知道"下一步做什么" |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 分层、**17 条关键逻辑路径（CP1–CP17）**、数据契约、**已核实的 API 清单** | **改代码前必读** |
| [`docs/VERIFY.md`](docs/VERIFY.md) | **当前这一轮的实机验证清单**（逐条可打勾） | 每次改完要进游戏试 |
| [`docs/VERIFY-HISTORY.md`](docs/VERIFY-HISTORY.md) | 历轮验证结论、根因 → 修法、**已接受的限制**、完整 A–F 功能清单 | 回归排查时回看 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 目前进度、已知问题、验证记录 | 每次开工前 |
| [`docs/BACKUP.md`](docs/BACKUP.md) | 回滚方案、检查点清单、灾难恢复、**本地 git 用法（含要不要上传 GitHub）** | 做有风险的改动前 |
| [`docs/PORT-1.20.1.md`](docs/PORT-1.20.1.md) | **1.20.1 移植方案**：构建插件差异、必撞 API 清单、待核实项 | 在 `mc-1.20.1` 分支上动手前 |

**最短路径**：`ARCHITECTURE.md` → `PROGRESS.md` → 动手；改完 → `VERIFY.md`；提交前 → `BACKUP.md`。

> 版本控制是**纯本机的**（`D:\dev\logiscope\.git`），不需要联网也不需要账号。
> 详见 `docs/BACKUP.md` 第八节。

---

## 按键与操作

**按 `F9` 打开面板有四条路，取决于你此刻在哪：**

| 此刻的场景 | 会发生什么 |
|---|---|
| 容器界面已经开着 | 面板压在它上面（**根页**） |
| 在世界里，准星指着一台**有界面**的设备 | 先帮你把设备界面打开，再把面板压上去（根页） |
| 在世界里，准星指着一台**没有界面**的容器（如机械手） | 直接新建一个探针并进入**选格页**（这类设备只能列表选格） |
| 在世界里，准星上是空气或非容器方块 | 打开**根页**，用来挑一个已创建的探针看 |

**三个页面**

| 页面 | 内容 |
|---|---|
| **根页** | 「＋ 创建探针」+「正在运行的探针」列表（点一行去看它，点行尾 `×` 删掉它） |
| **选格页** | 「选择容器格」可视化选格 / 「清空选择」/「完成」/「列表」 |
| **图表页** | 波形本体；「选格」回到**这个探针**的选格页，「列表」回根页 |

**面板操作**

| 操作 | 作用 |
|---|---|
| ESC / `F9` | 收起面板（原容器界面会回来） |
| 拖动**标题栏** | 移动面板（可以拖出屏幕，留 1/8 屏宽能抓住） |
| 拖动**右下角** | 缩放面板（尺寸和位置都会记住） |
| **选择容器格**模式 | 光标不再与物品交互；左键点/拖过的格子**选中**，右键点/拖过的**取消** |
| 在列表里点一行 | 切换那一格的勾选（滚轮翻页） |
| 在**波形**里拖动 | 框选一段区间 → 自动冻结视图 + 显示该区间的净变化 |
| 在波形里**点一下** | 取消选择 |
| 滚轮（实时模式） | 缩放时间窗口 |
| 滚轮（冻结模式） | 左右平移，回头细看某一段 |
| 点**图例色块** | 开关那条数据线（**存量**）的渲染 |
| 点图例里物品后面的 **`IO`** 小格 | 开关这个物品的 **IO 线**（单 tick 净流量，默认关）。
想看 IO 时建议把它的存量线关掉 —— 纵轴会自动缩放到 IO 自己的范围。
⚠️ **一进一出发生在同一个 tick 里**（存量一直不变）时，净流量是 0：从外部只能观测到"每格的存量"，看不到同格内的进出（见 `docs/ARCHITECTURE.md` CP18）。要看这种情形的进出，请监控上游/下游容器的格子 |

**按钮**

- 根页：`＋ 创建探针` / `收起`
- 选格页：`选择容器格`（再点一次结束）/ `清空选择` / `完成` / `列表` / `收起`
- 图表页：`选格` / `列表` / `暂停` / `继续` / `清空` / `关界面后继续检测`
- 图表工具栏：`实时滚动`（冻结）/ `-` / `+` / `全览` / `清选`

**多格聚合的语义**（一个探针监控多格时，到底记什么）

| 被监控的格子 | 记下来的内容 |
|---|---|
| 装的是**同一种**物品 | 那种物品 × **总数**（多格一起算） |
| 装了**多种**物品 | **每一种各自一条**：`铁 × 64`、`铜 × 32` —— 图上就是两条不同颜色的线 |
| 全部为空 | 空（数量 0，那条灰蓝线） |

也就是说：**"多格"只体现在"同种物品的数量相加"上，物品仍然各自一条彩色线。**
（第一版曾把多种物品合成一条「混合物品」，实机反馈是"这不是我要的"，已改掉。）

> **关于键位**：一路试过 `O` → `]` → `F9`，`O` 和 `]` 都被"在搜索框里打字"咬到过。
> 代码默认给的是 `F9`（功能键**不产生字符**，冲突通道从根上不存在）；
> 你要是更喜欢 `]`，也能用 —— 那就是三层文本守卫在兜（见 `ARCHITECTURE.md` CP2），
> 实测在 JEI 搜索框里打 `hopper` 再按 `]` 也不会误触发。
>
> 另外 `KeyMapping` 的匹配**不看修饰键**，所以设成 `Ctrl+O` 没用
> （单独按 `o` 照样命中）—— 这是踩过的坑之一。
>
> ⚠️ 改了默认键位之后要注意：按键绑定会存在 `run/options.txt` 里并**覆盖代码默认值**。
> 这就是为什么"改了代码里的默认值但游戏里没变"。

---

## 项目结构

```
logiscope/
├─ docs/                       设计文档（见上表）
├─ dev-check/TestChart.java    ★ 离线断言：不启动游戏就能验断点规则/分组/统计/多格聚合
├─ src/main/
│  ├─ java/com/jessetech/logiscope/
│  │  ├─ LogiScopeMod.java     主类：注册事件监听与配置、持有唯一 ProbeManager
│  │  ├─ LogiScopeConfig.java  资源上限的客户端配置（缓冲 72000 / 探针 16 / 物品种类 256）
│  │  ├─ bind/                 输入层（快捷键 + TextInputGuard 三层打字守卫）
│  │  │     ContainerAnchor    右键方块 → 维度+坐标锚点
│  │  │     ProbeTarget        探针目标（一组槽位 + 持久身份）
│  │  │     ProbeContext       ★ 当前可探测的容器（容器界面 / 准星）
│  │  ├─ collect/              采集层（★ 三条取值路径都在这）
│  │  │     ContainerAccess    读菜单槽位（界面开着）
│  │  │     WorldProbe         读方块实体（界面关了，单人；读结果带身份与状态）—— 每实例一份
│  │  │     ProbeSlots         无 GUI 容器的槽位清单（问服务端）
│  │  │     ServerClock        ★ 两只全局时钟（权威世界 tick + 客户端 tick）
│  │  │     ProbeInstance      ★ 一个探针：状态机 + 每 tick 采样 + 聚合
│  │  │     ProbeManager       ★ 探针实例表 / 创建 / 删除 / 活跃指针 / 每 tick 驱动
│  │  ├─ store/                存储层（环形缓冲 + version + no_data / gap 标记）
│  │  ├─ analysis/             分析层（纯函数，不 import 任何游戏类）
│  │  │     Analyzer           摘要 / 逐 tick 差值
│  │  │     ChartData          扁平序列 → 按物品分组的数据线（★ 断点规则在这）
│  │  │     Aggregator         ★ 多格聚合规则（同物品求和 / 混装标 mixed）
│  │  ├─ ui/                   呈现层（只读）
│  │  │     ScopeScreen        浮层面板：三页面（根/选格/图表）+ 布局 / 交互
│  │  │     ChartRenderer      坐标轴 / 波形 / 框选 / 准线
│  │  ├─ templates/            元数据模板（${} 占位符）
│  │  └─ resources/assets/     语言文件
├─ build.gradle                注意 filteringCharset = 'UTF-8' 那一行
├─ gradle.properties           版本号都在这里
└─ verify-jar.py               构建产物验收工具

运行时（不是你手写的）：`config/logiscope-client.toml` —— 资源上限都在那里，
游戏内从「模组列表 → LogiScope → 配置」改；`bufferSamples` 改完**立刻生效**。
```

**`analysis/` 与 `store/` 刻意不 import 任何 `net.minecraft`** ——
这不是洁癖，而是让 `dev-check/TestChart.java` 能脱离游戏跑断言的前提：

```bat
gradlew.bat compileJava
javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/TestChart.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" TestChart
```

波形断点这类逻辑的 bug 在游戏里几乎看不出来，靠断言钉住才是可靠的办法。

---

## 构建备忘（踩过的坑）

| 现象 | 原因 | 处理 |
|---|---|---|
| `ClassNotFoundException: GradleWrapperMain` | Git Bash 下用了 `./gradlew` | 改用 `gradlew.bat`；或 `java -classpath "$(pwd -W)/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain <task>` |
| 游戏报 `File ... is not a valid mod file` | 元数据编码被破坏 | 检查 `build.gradle` 的 `filteringCharset = 'UTF-8'` 是否还在 |
| 构建报「非零退出码」但无输出 | Gradle 吞掉了子进程 stderr | 加 `--info`；`build.gradle` 里已配好输出转发 |
| PowerShell 里跑构建"退出码 1"但其实成功了 | PowerShell 把 Gradle 写到 stderr 的 JVM WARNING 当成 `NativeCommandError` | 看日志里的 `BUILD SUCCESSFUL`，别只看退出码；或 `... 2>&1 | Out-File 日志` 再读日志 |
| IDEA 同步后行为异常 | IDEA 会另起一个 Gradle 守护进程重新生成文件 | 同步后用 `python verify-jar.py` 复核一次 |

详细的排错记录见隔壁项目 `..\neoforge-1.21.1-walkthrough\WALKTHROUGH.md`，
那份文档把整个工具链的坑都记全了。

---

## 免责声明

本项目只读取客户端已经收到的数据，不修改游戏状态、不发送网络包。
但它**确实会观测其他模组的容器内容** —— 在多人服务器上使用前，
请自行确认该服务器的规则是否允许。

## 许可证

MIT
