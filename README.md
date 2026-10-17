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
还能用鼠标框选一段区间看那一段的净变化。记录**直接读服务端世界数据**（单人）——
界面开着、关掉都一样，每个世界 tick 一条真值，`/tick rate` 调到多少就采多少；
读不到的位置（例如多人、设备被移走）记断档，跨空档的差值依然有效。

> **不发自定义网络包、不需要在服务端安装、不注册任何物品** —— 单人下在客户端进程内
> 直读集成服务端的方块实体（这就是"数据保真"的来源）；多人退化为只读客户端已同步的菜单数据。

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
| 模组版本 | `0.1.2-1.21.1`（main 主线） |

> **版本分支约定**：`main` = 1.21.1 主线；`1.20.1-neo` 分支 = MC 1.20.1 + NeoForge 47.1.x 移植线
> （构建插件换成 `net.neoforged.moddev.legacyforge`，Java 17，产物 jar 名 `logiscope-0.1.0-1.20.1`）。
> 移植方案与两个版本线的 API 差异记录在 [`docs/PORT-1.20.1.md`](docs/PORT-1.20.1.md)。
> 通用 bugfix 默认进 main，再 cherry-pick 到 `1.20.1-neo`。
>
> **发布 tag 约定**：`<加载器>-<MC版本>-<模组版本>`（例：`neoforge-1.21.1-0.1.1`）；
> 一个版本 = 一个 tag = 一个 GitHub Release，不把多个版本合并到同一个 tag。

---

## 文档导航（vibe coding 前请按顺序读）

| 文档 | 内容 | 什么时候读 |
|---|---|---|
| [`docs/VISION.md`](docs/VISION.md) | 项目方向、核心依据、**明确不做什么** | 开始任何新功能前 |
| [`docs/GOALS.md`](docs/GOALS.md) | G0–G7 分目标与**可验证的验收标准** | 想知道"下一步做什么" |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 分层、**接口与数据流程**、关键逻辑路径（CP1–CP27）、数据契约、**已核实的 API 清单** | **改代码前必读** |
| [`docs/VERIFY.md`](docs/VERIFY.md) | **当前这一版要测什么**（合并清单，逐条可打勾） | 每次改完要进游戏试 |
| [`docs/VERIFY-HISTORY.md`](docs/VERIFY-HISTORY.md) | 历轮验证结论、根因 → 修法、**已接受的限制**、完整 A–F 功能清单 | 回归排查时回看 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 现状速览、当前轮、未完成项 | 每次开工前 |
| [`docs/PROGRESS-HISTORY.md`](docs/PROGRESS-HISTORY.md) | 历轮（一 ~ 十七轮）的结论 / 根因 / 已接受限制 | 查“以前为什么这么做” |
| [`docs/BACKUP.md`](docs/BACKUP.md) | 回滚与灾难恢复、改动前安全点、**GitHub 发布流程** | 做有风险的改动前 |
| [`docs/PORT-1.20.1.md`](docs/PORT-1.20.1.md) | **1.20.1 移植方案**：构建插件差异、必撞 API 清单 | 在 `1.20.1-neo` 分支上动手前 |

**最短路径**：`ARCHITECTURE.md` → `PROGRESS.md` → 动手；改完 → `VERIFY.md`；提交前 → `BACKUP.md`。

> 代码托管：本地 git + GitHub 公开仓库 `JesseM1024/Logiscope`（发布通道）。详见 `docs/BACKUP.md` 第八节。

---

## 按键与操作

**按 `F9` 打开面板有四条路，取决于你此刻在哪：**

| 此刻的场景 | 会发生什么 |
|---|---|
| 容器界面已经开着 | 面板压在它上面（**根页**） |
| 在世界里，准星指着一台**有界面**的设备 | 先帮你把设备界面打开，再把面板压上去（根页） |
| 在世界里，准星指着一台**没有界面**的容器（如机械手） | 直接新建一个探针并进入**选格页**（这类设备只能列表选格） |
| 在世界里，准星上是空气或非容器方块 | 打开**根页**，用来挑一个已创建的探针看 |

**五个页面**

| 页面 | 内容 |
|---|---|
| **根页** | 「＋ 创建探针」+「正在运行的探针」列表（点一行去看它，点行尾 `×` 删掉它）+ 存档列表（标题即**筛选框**，打字过滤） |
| **选格页** | 可视化选格 / NBT 追踪开关 /「Probe data」进数据页 /「完成」/「主界面」 |
| **图表页** | 波形本体；「Back to slot」回本探针选格页、「Main screen」回根页；「Stop & archive」存档并停止 |
| **回放页** | 打开历史存档：波形 + 框选 + 计算 + 重命名（右键图例 = 选中该曲线「分析」） |
| **数据页（Probe data）** | NBT 原文 / 值列表 / 追踪目标 / 手输路径；「确认添加」「完成」；「status ok」旁有**刷新速率滑条**（1~40t，最右 = 不刷新） |

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
注意：**一进一出发生在同一个 tick 里**（存量一直不变）时，净流量是 0 —— 从外部只能观测到"每格的存量"，看不到同格内的进出（见 `docs/ARCHITECTURE.md` CP18）。要看这种情形的进出，请监控上游 / 下游容器的格子 |

**按钮**

- 根页：`＋ 创建探针` / `打开存储文件夹` / `收起`
- 选格页：`选择容器格`（再点一次结束）/ `清空勾选` / `NBT 追踪` / `Probe data` / `完成` / `主界面` / `收起`
- 图表页：`暂停` / `继续` / `清空` / `存档并停止` / `关界面后继续检测` / `Back to slot` / `Main screen`
- 图表工具栏：`实时滚动`（冻结）/ `-` / `+` / `全览` / `清选`；回放页另有 `重命名` 与 `计算（数量/IO）`·`运算`
- 数据页：`刷新` / `确认添加` / `完成` / `Back` / `收起` + 刷新速率滑条

**多格聚合的语义**（一个探针监控多格时，到底记什么）

| 被监控的格子 | 记下来的内容 |
|---|---|
| 装的是**同一种**物品 | 那种物品 × **总数**（多格一起算） |
| 装了**多种**物品 | **每一种各自一条**：`铁 × 64`、`铜 × 32` —— 图上就是两条不同颜色的线 |
| 全部为空 | 空（数量 0，那条灰蓝线） |

也就是说：**"多格"只体现在"同种物品的数量相加"上，物品仍然各自一条彩色线。**

> **关于键位**：代码默认 `F9`（功能键**不产生字符**，与搜索框的冲突通道从根上不存在）。
> 改成 `]` 这类字符键也可以用 —— 三层文本守卫（`ARCHITECTURE.md` CP2）会兜住
> （实测在 JEI 搜索框里打 `hopper` 再按 `]` 也不误触发）。
>
> 两点注意：`KeyMapping` 匹配**不看修饰键**（设 `Ctrl+O` 无效，单独按 `o` 照样命中）；
> 按键绑定存在 `run/options.txt` 里并**覆盖代码默认值** —— 改了代码默认值而未生效时，
> 删掉那一行或在「选项 → 控制」里手动重绑。

---

## 项目结构

```
logiscope/
├─ docs/                       设计文档（见上表）
├─ dev-check/                  离线断言与压测（不启动游戏）
│  ├─ TestChart.java           断点规则 / 分组 / 统计 / 聚合 / 槽位对齐 / 计算 / 存档格式
│  └─ BenchRender.java         渲染路径压测（列聚合与游程合并的 fill 次数）
├─ src/main/
│  ├─ java/com/jessetech/logiscope/
│  │  ├─ LogiScopeMod.java     主类：注册事件监听与配置、持有唯一 ProbeManager
│  │  ├─ LogiScopeConfig.java  客户端配置（缓冲 / 探针上限 / 物品种类上限 / DATA 页刷新节拍）
│  │  ├─ bind/                 输入层与绑定层
│  │  │     KeyBindings        快捷键 + Screen 按键拦截 + 无界面时的准星入口
│  │  │     TextInputGuard     三层“正在打字”守卫
│  │  │     ContainerAnchor    右键方块 → 维度 + 坐标锚点
│  │  │     ProbeTarget / DataTarget   探针目标 / NBT 数据目标
│  │  │     ProbeContext       当前可探测的容器（容器界面 / 准星）
│  │  │     DataCommand        读方块 NBT 的取数通道
│  │  ├─ collect/              采集层
│  │  │     ContainerAccess    读菜单槽位（兜底路径）
│  │  │     WorldProbe         读方块实体 + 下标验证（单人主路径）—— 每实例一份
│  │  │     ProbeSlots         无 GUI 容器的槽位清单
│  │  │     DataProbe / AeNetworkReader    NBT 路径取数 / AE 网络数量
│  │  │     ServerClock        全局权威世界 tick
│  │  │     ProbeInstance      一个探针：状态机 + 每 tick 采样
│  │  │     ProbeManager       探针实例表 / 两个驱动点
│  │  ├─ store/                存储层（环形缓冲 / no_data / gap / 标记）
│  │  ├─ analysis/             分析层（纯函数，不 import 任何游戏类）
│  │  │     ChartData / Aggregator / Analyzer / CurveMath / Val / Snbt / SlotAlign / WaveColumns …
│  │  ├─ archive/              持久层（导出 / 读取 / 重命名）
│  │  ├─ ui/                   呈现层（只读）
│  │  │     ScopeScreen        浮层面板：根 / 选格 / 图表 / 回放 / 数据
│  │  │     ChartRenderer      坐标轴 / 波形 / 框选 / 准线 / 标记
│  │  ├─ templates/            元数据模板（${} 占位符）
│  │  └─ resources/assets/     语言文件
├─ build.gradle                注意 filteringCharset = 'UTF-8' 那一行
├─ gradle.properties           版本号都在这里
└─ verify-jar.py               构建产物验收工具
```

运行时（不是你手写的）：`config/logiscope-client.toml` —— `bufferSamples` / `maxProbes` /
`maxItemKinds` / `dataRefreshTicks` 都在那里，游戏内从「模组列表 → LogiScope → 配置」改；
`bufferSamples` 与 `dataRefreshTicks` 改完立刻生效。

**`analysis/` 与 `store/` 刻意不 import 任何 `net.minecraft`** ——
这是让 `dev-check/TestChart.java` 能脱离游戏跑断言的前提：

```bat
gradlew.bat build
javac -encoding UTF-8 -cp "build/classes/java/main;dev-check/lib/gson-2.10.1.jar;build/moddev/artifacts/neoforge-21.1.209-merged.jar" -d build/dev-check dev-check/TestChart.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main;dev-check/lib/gson-2.10.1.jar;build/moddev/artifacts/neoforge-21.1.209-merged.jar" TestChart
```

波形断点、聚合口径这类逻辑的 bug 在游戏里几乎看不出来，靠断言钉住才是可靠的办法。

---

## 构建备忘

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
