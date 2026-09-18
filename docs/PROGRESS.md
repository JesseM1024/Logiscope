# LogiScope · 进度快照

> 这份文件回答三个问题：**现在到哪了 / 下一步做什么 / 有什么坑**。
> 目标定义见 [`GOALS.md`](GOALS.md)，原理见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，
> 逐条验收步骤见 [`VERIFY.md`](VERIFY.md)。

---

## 一句话现状

**G0–G1.8 / G2 / G2.1 / G3 全部实机验证通过**（第九轮 S1–S6 通过，
`g2-waveform` 里程碑已打）。

**第十轮第一轮实机已跑完**：A–D 组的测试用例**全部通过**（用户原话），
并发现 4 个问题 + 提了 1 条语义修正 + 2 条界面改进 —— **本轮已全部修完**，
复测清单见 `VERIFY.md` **第四节之二**。

| 项 | 状态 |
|---|---|
| 代码 | ✅ `BUILD SUCCESSFUL` |
| 离线断言（`dev-check/TestChart.java`） | ✅ **111 条**全过（原 47 + 聚合/断点/统计 64 条） |
| 产物验收（`verify-jar.py`） | ✅ 全部通过 |
| 实机验证 | 🟡 第十轮第一轮：A–D 组用例全通过；**修正版待复测**（`VERIFY.md` 第四节之二 R1–R16） |
| 版本控制 | ✅ `g2-waveform` 标签之后的所有改动都在本轮提交里（见 `BACKUP.md`） |

---

## 已完成的部分

### G0 工程骨架　`[x]`

纯客户端模组骨架。踩过的坑与解法已固化进 `build.gradle` 与文档：
`filteringCharset = 'UTF-8'`（否则中文资源会把元数据写成非法 UTF-8，
FML 报 `MalformedInputException` —— 报错完全不提编码）。

### G1 最小闭环　`[x]`　第一轮实机通过

按键 → 选槽位 → 采样 → 看到实时数字。三条取值路径打通：

| 状态 | 数据源 | 得到什么 |
|---|---|---|
| `RECORDING` | 菜单槽位（服务端已同步） | 真值 |
| `FOLLOWING` + 单人 | 集成服务端的方块实体（异步） | 真值，最多差 1 tick |
| `FOLLOWING` + 多人 | 读不到 | 记 `no_data`（**不记 0**） |

### G1.5 浮层面板 / G1.6 面板外鼠标交给容器 / G1.7 面板可推出屏幕　`[x]`

三、四轮实机通过。关键实现是 `ClientHooks.pushGuiLayer`（**真正的 Screen 栈**，
见 `ARCHITECTURE.md` CP8 —— 这条曾在本项目文档里被写错，现已订正）。

### G3 持久身份　`[x]`　第一轮实机通过，本轮补了一处

右键方块 → 维度+坐标锚点 → 关界面后仍能读回同一台设备。用快捷键开界面的模组拿不到坐标，
这是已知边界（宁可暂停也不往别的方块上记）。

**本轮补**：关界面那一 tick 不再凭空产生 `no_data`（原先两个毛病叠在一起 ——
时间轴断一格 + 一条误导性的"读不到"）。修法见 CP9 之一。

### G1.8 面板可缩放　`[x]` 代码 / `[ ]` 实机

波形要放坐标轴、图例、框选，固定 272×190 画不下，所以面板改成右下角可缩放。

---

## 本轮（G2 波形可视化）做了什么

| # | 改动 | 文件 |
|---|---|---|
| 1 | **接缝不再造假断档**：过渡 tick 沿用上一 tick 的观测值，并垫进 `WorldProbe` 缓存 | `collect/Sampler`、`collect/WorldProbe` |
| 2 | **按物品分组的数据线**：扁平序列 → 多条数据线，断点规则统一成 `breakBefore` | `analysis/ChartData`（新）、`Series`/`SeriesPoint`（新） |
| 3 | **带坐标轴的正规波形**：自动缩放+刻度吸附、按列取极值、稀疏插值、多色、框选、准线读数 | `ui/ChartRenderer`（新，约 500 行） |
| 4 | **面板重做**：可缩放、布局单一来源、图表工具栏、可点击图例、框选交互 | `ui/ScopeScreen`（重写） |
| 5 | **派生数据按 `version` 缓存**：不再每帧全量遍历 | `store/SampleStore`、`ui/ScopeScreen` |
| 6 | **离线断言**：47 条断言覆盖断点规则 / 分组 / 颜色顺序 / 框选统计 / 退化情形 | `dev-check/TestChart.java`（新） |

**用户补充的两条已一并实现**（比原验收标准更强）：
- 出现新物品**自动成为一条新的数据线**，不同物品不同颜色（原标准只要求"断开"）
- **图例可点击**，开关某条数据线的渲染

**一处架构取舍要说明**：按物品分组做成了**派生视图**（扁平环形缓冲仍是唯一真相），
而不是在存储层真的按物品分数组。理由写在 `ARCHITECTURE.md` CP10 ——
核心是"环形覆盖 / 清空 / 断档判定只需写一遍"，且保住了统一时间轴。

---

## 第六轮做了什么（修复第五轮暴露的问题）

| # | 改动 | 文件 |
|---|---|---|
| 1 | **波形线不可见**：调色板 / 空格子线 / 图例色块全部补 `0xFF` alpha 位。根因：`fill` 没有 `Font.adjustColor` 那样的兜底，alpha=0 就是全透明（这个坑从第一轮火花线就存在） | `ui/ChartRenderer` |
| 2 | **模组机器关 GUI 全 no_data**：`WorldProbe` 加 capability 回退路径（`Capabilities.ItemHandler.BLOCK`，先无方向再六面）。javap 实锤精妙存储 / Mekanism 的 BE 都不是原版 `Container` | `collect/WorldProbe` |
| 3 | **无数据窗口里框选无反馈**：渲染器提前返回的分支也画选区 | `ui/ChartRenderer` |
| 4 | "这一区间没有数据"提示补一句"点「全览」看整段" | lang |

**为什么第五轮会测成那样**：用户在模组机器上做 1t IO 测试 → 关 GUI 后全 no_data →
实时窗口（最近 20s）里没有数据点 → 图上只有"这一区间没有数据"，再叠加线不可见，
看起来就像"波形整个没实现"。实际是三个独立问题叠在一起。

---

## 第七轮做了什么（修复第六轮暴露的问题）

| # | 改动 | 文件 |
|---|---|---|
| 1 | **框选四连败（M12/M13/M14/M16 同根）**：`beginSelection` 先置 `selecting=true` 再调 `window()`，而 `window()` 第一分支是"框选中→返回锁住的窗口" → 首次框选锁成 `[0,0]` → 波形瞬间"没有数据"，且永远进不了冻结态（「实时」按钮因此看起来点不动）。修法：先取窗口再置位 | `ui/ScopeScreen` |
| 2 | **切换接缝抄数据（M1）**：seed 把上一 GUI 值续命 1~2 tick；重开界面的切换 tick 什么都不记（断一格）。修法：过渡 tick **立刻发异步请求**（真值最早下一 tick 接管）+ 重开界面用世界侧缓存垫一格。接缝两侧各剩至多 1 tick 承载 = 不造假数据前提下的物理极限 | `collect/Sampler`、`collect/WorldProbe`（新增 `resetReadCache`，避免关界面时误删下标验证） |
| 3 | **MEK 槽位下标全是 0（M4）**：javap -c 实锤 `InventoryContainerSlot` 构造是 `super(emptyInventory, 0, x, y)`，客户端拿不到真下标。修法：挂探头瞬间（GUI 开着、两侧内容同步）在服务端做**内容指纹匹配**验证下标（物品+数量唯一→采纳；仅物品唯一→也采纳；歧义→UNVERIFIED）。验证不了的机器在转 FOLLOWING 时弹一次动作栏警告（用户要求的退路），读取退回原下标并如实记录 | `collect/WorldProbe`（`verifyIndex`/`MappingStatus`）、`collect/Sampler`、`ui/ScopeScreen`、lang |
| 4 | M6（机械手等无 GUI 设备）记入后续路线：「准星强制探测」 | `VERIFY.md` F15、`GOALS.md` G2.1 遗留 |

---

## 下一步（按顺序）

1. **跑第十轮修正版的复测**（`VERIFY.md` **第四节之二**，R1–R16）。重点：
   - `R3/R4`（准星对准精妙存储 / MEK / 原版熔炉要真的把界面打开）—— 第一轮的实测 bug
   - `R7/R8`（多格聚合：同物品相加成一条线；**不同物品是两条不同颜色的线**，不是"混合物品"）
   - `R1/R2`（「列表」按钮能回根页；再进选格页能看到原来的勾选）
   - `R6`（大数量容器的纵轴刻度不再画到 GUI 外面）
2. 通过后提交文档 + 打里程碑标签 `g3-probes`。
3. 再考虑 G4（标注与差值）或 G5（导出）。两者都可做：
   - G4 的落点已经现成：`ChartRenderer.Selection` 的两个端点就是标记的最佳位置
   - G5 几乎是把 `List<Sample>` 写成文件，连分析层都不用碰
   - 「准星强制探测」（F15）本轮**已经顺带做了**（准星指着无 GUI 容器就能建探针）

---

## 第八轮做了什么（修复第七轮暴露的问题）

| # | 改动 | 文件 |
|---|---|---|
| 1 | **缩放交互重做（P5）**：冻结态滚轮改为**光标锚定缩放**（光标处 t 值保持原位、两侧缩放，推到数据边界就贴端点，最大=全览）；`userZoomed` 标志修"缩放无反馈"（数据未铺满窗口时起点钳制吞掉了缩放）；滚轮 1.25×/格、按钮 1.5×/次，MIN_WINDOW 降到 20 | `ui/ScopeScreen`（`zoomBy` 重写、`window()`、滚轮/按钮/复位点） |
| 2 | **下标歧义警告被吞（N3）**：警告紧跟"转为坐标跟踪"提示，落在 40t 去抖窗口内被吞。`notifyPlayer` 加 force 通道，重要警告绕过去抖 | `collect/Sampler` |
| 3 | **采样跟随世界 tick（N12）**：`/tick rate` 只改服务端，客户端事件恒 20/s——旧时间戳与世界脱节、记一堆重复值。改用 `Level#getGameTime()` 作样本时间戳，**世界没走 tick 就跳过采样**；WorldProbe 的 seed/年龄判定换同一刻度；**统计块速率单位 n/s → n/t**；ESC 暂停期间也不再记重复平线 | `collect/Sampler`、`ui/ChartRenderer`（`fmtRate`） |

---

## 第九轮做了什么（修复第八轮暴露的问题）

| # | 改动 | 文件 |
|---|---|---|
| 1 | **采样时钟重做（N13）**：第八轮实测 tick rate 200/40 下采样节奏与数据全错。根因（sources jar 核实）：客户端 `Level#getGameTime()` 是错拍阶梯——`ClientLevel#tickTime()` 每客户端 tick 本地 +1，服务端每 20 个服务端 tick 才纠正一次绝对值；tick rate 20 时恰好重合所以之前测不出。修法 = **两只钟拆开**：① 样本时间戳改用服务端线程读的权威 `ServerLevel#getGameTime()`（`WorldProbe#worldGameTime`，每客户端 tick 一个 `server.execute` 刷新 volatile，多人/主菜单退回客户端值）；② `WorldProbe` 异步读值新鲜度改按**客户端 tick** 判定（`latest(maxAgeClientTicks)`，读值延迟是真实时间量）。`seed/request` 签名随之简化 | `collect/WorldProbe`（`tickClient`/`worldGameTime`/`latest`）、`collect/Sampler`、`ui/ScopeScreen`（`latest` 调用点） |

物理极限（记录在案，不是 bug）：客户端每秒最多观测 20 次，tick rate 200 时样本间隔 ~10t 不可再细，但累计值与 n/t 速率恒正确。详见 `ARCHITECTURE.md` CP15。

---

## 第十轮做了什么（UI 交互逻辑更新）

需求原文三条（转述见 `GOALS.md` G7）。核心是**面板的入口变了**：
从"在这台设备上选一个格子"变成"管理一批正在运行的探针"，
并且一个探针可以**同时监控多格**。

| # | 改动 | 文件 |
|---|---|---|
| 1 | **多探针并存**：原来的全局单例 `Sampler` 拆成 `ProbeManager`（实例表 / 创建 / 删除 / 每 tick 驱动）+ `ProbeInstance`（一个探针的状态机 + 自己的缓冲 + 自己的世界侧读取器）。`WorldProbe` 从静态单例改成**每实例一份**，两只全局时钟抽到 `ServerClock` | `collect/ProbeManager`（新）、`collect/ProbeInstance`（新）、`collect/ServerClock`（新）、`collect/WorldProbe`、`collect/Sampler`（删） |
| 2 | **多格监控 + 聚合**：`ProbeTarget` 从"一格"变成"一组 `SlotRef`"；每 tick 把所有被监控格**加在一起**写一条样本；同物品取那种物品、混装标 `logiscope:mixed`。规则做成纯函数以便离线断言 | `bind/ProbeTarget`、`analysis/Aggregator`（新）、`ui/ChartRenderer`（mixed 配色与名字） |
| 3 | **三页面结构**：根页（创建探针 + 运行中列表 + 每行删除按钮）/ 选格页 / 图表页；图表页的「选格」回退到**本实例**的选格页 | `ui/ScopeScreen`（重写） |
| 4 | **可视化选格**：点「选择容器格」后 `cellMode` 生效 —— 面板外的鼠标事件一律吃掉不转发（禁用物品交互），左键点/划过的选中、右键取消，**拖动按线段每 4px 采样**逐格命中；覆盖层自己画；一键清空选择 | `ui/ScopeScreen` |
| 5 | **无 GUI 容器的列表选格**：`ProbeSlots` 在服务端线程读方块实体的槽位清单交回客户端（客户端根本没有这些内容） | `collect/ProbeSlots`（新） |
| 6 | **准星入口**：没有容器界面时按 F9 → 判准星。有 GUI → 模拟一次真实右键把界面打开（跨 tick 再压面板图层）；有容器无 GUI → 直接建实例进选格页；都没有 → 开根页 | `bind/ProbeContext`（新）、`bind/KeyBindings` |
| 7 | **顺手修掉一个和文档不符的洞**：`ContainerAccess#snapshot` 之前其实**没有**排除玩家背包那 36 格（文档一直说排除了）。单格时代只是列表长，多格选格之后会变成"点到自己背包也被当成设备槽" | `collect/ContainerAccess` |
| 8 | **离线断言 +20 条**：聚合规则（同物品求和 / 混装 / 全空 / 零长度 / 数组版与 List 版一致） | `dev-check/TestChart.java` |

**为什么"混合"不取"最多的那个物品"**：图例上的物品名会与实际内容不符，读数直接错。
数量在任何情况下都是所有格子之和，所以"总存量 / 总吞吐"这条线永远有效。见 CP16。

**一处需要你确认的解读**：需求原文"若是且有GUI，则打开GUI，并打开探针GUI"，
对比无 GUI 那半句明写的"自动进入…创建流程"，本轮把**有 GUI 的那支落在根页**
（不是自动进创建流程）。想改的话是 `KeyBindings#onScreenInit` 里一个 `Page` 实参。

---

## 第十轮修正（第一轮实机反馈后的修复）

第一轮实机的结论是"**测试用例全部通过**"，但用户提出了 4 个问题、1 条语义修正、2 条界面改进。

| # | 反馈 | 根因 | 修法 |
|---|---|---|---|
| 1 | **图表页「列表」按钮失效**，无法回退到根页 | `ScopeScreen#onChip` 里只写了 `case "to_root"`，而图表页的按钮 id 是 **`"list"`** → 落到 default，什么都不做 | 补 `case "list"`，并让 `to_root` 只负责选格页的「列表」 |
| 2 | 再次进入选格页显示"**已选 0 格**" | 回填只在 `init()` 与 `openProbe()` 里调用，而"图表页点选格"走的是第三条路；且回填按实例 id 去重，重开面板后没人触发它 | 抽 `enterPicker(probe)` 三个入口共用，回填写在里面（去重仍在，避免冲掉用户正在改的选择） |
| 3 | **多格装不同物品时"不是我要的"**：应该"多格一起计算，但不同物品仍然是不同颜色的线" | 第一版把不同物品合成了一条 `logiscope:mixed`（"混合物品"） | **改成按物品分别求和**：同种物品的数量相加，不同物品各自一条线。连带把 `ChartData` 的断点判定改成 **per-series**（一个 tick 现在有多条样本，用全局 prev 判会每 tick 都断开），`Analyzer` 改成先按 tick 合计再统计 |
| 4 | 手持扳手 T15 失败 / 空手对准**精妙存储、MEK 熔炼工厂打不开界面**，反而进了"无 GUI"逻辑 | 用 `BlockState#getMenuProvider` 判"有没有界面"—— **连原版熔炉都不覆写它**，模组机器更不用说（javap 核实） | 改成问"这个方块右键**会不会消费**交互"（`useWithoutItem(...).consumesAction()`）；有界面才发交互打开它，判为无界面时**一个包都不发**（于是也修掉了"手持工具误触"的隐患）。跨 tick 用 `PENDING_OPEN`（40 tick）等服务端把界面交出来，超时兜底当无界面 |
| 5 | 选格页右上角的绿色 `LMB pick / RMB unpick` 与第二行淡绿色的 source 提示**重叠** | 两处都画在同一行 | 删掉那句绿色提示（信息已在下方的灰色行里） |
| 6 | 探针实例名里"前缀加一个这个方块的名字" | 原来只显示"槽 0,1 · overworld 10,64,10" | `ProbeTarget` 增加 `deviceName`（从准星 / 锚点坐标取方块名），列表行改成 **方块名 → 状态 → 槽位 · 坐标** |
| 7 | **纵轴刻度数字长到 GUI 外面**（测单格堆叠的大数量容器） | 8 位整数直接把轴挤爆 | 新增 `ChartRenderer#fmtCount`：≥ 10000 走科学计数法（`1.6E7`／`9.99E6`，用整数运算取尾数避免假精度），`AXIS_LEFT` 34 → 46px；读数、框选统计、统计行的数字一起用 |

用户另外确认：**压力测试通过**（T22 多探针挂机不掉帧）。

---

## 已知问题 / 已接受的限制

完整列表在 `VERIFY.md` 第四节 F 组。这里只列**值得记住**的几条：

| 项 | 说明 |
|---|---|
| 容器**键盘**操作在面板打开时无效 | 数字键换物 / `Q` / 中键走 `hoveredSlot`，而背景层拿不到鼠标坐标。鼠标操作完全可用（CP8 之一~之四）。要修得连 `hoveredSlot` 一起补 |
| 用快捷键开界面的模组拿不到方块坐标 | vanilla 菜单不存坐标，只能靠右键事件推 → 关界面只能暂停 |
| 第三方搜索框需逐个加探针 | 目前只接了 JEI；第 3 层通用兜底能覆盖其余情况（代价：刚打完字要多按一次） |
| 图例超 3 行会收成 `+N` | 一个槽位轮换 7 种以上物品很罕见；把面板拖大即可 |
| 纵轴范围偶尔跳变 | 自动缩放的固有代价，已吸附整数刻度降低频率 |

## 那些"看起来像 bug 但其实是设计"的现象

| 现象 | 为什么是对的 |
|---|---|
| 关界面后走远，`no-data` 开始增长 | 区块卸载了确实读不到。**该断的地方要断**（见 CP9） |
| 波形在某处空白一段 | 那是 `no_data` 或暂停 —— 留白比画成 0 诚实 |
| 换物品的那一瞬间线是断的 | 从 64 个铁变成 1 个铜，差值 −63 没有物理意义 |
| 同一种物品也会分成好几段 | 中间夹了 `no_data` 或暂停，连线等于编造数据 |
| 面板盖住容器时容器点不动 | 只有**面板之外**才转发给容器（CP8 之一） |

---

## 怎么继续（接手者看这里）

```bat
cd /d D:\dev\logiscope

:: 1) 编译 + 打包（JDK 21 已由 gradle.properties 的 org.gradle.java.home 锁定）
gradlew.bat build

:: 2) 离线断言（不用开游戏，2 秒）
javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/TestChart.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" TestChart

:: 3) 产物验收（元数据 / 编码 / 占位符）
python verify-jar.py build/libs/logiscope-0.1.0.jar

:: 4) 进游戏（或用 build\moddev\runClient.cmd 脱离 Gradle 反复重启）
gradlew.bat runClient
```

改代码前**必读** `ARCHITECTURE.md` 的第二节（关键逻辑路径 CP1–CP12），
尤其是标 ⚠️ 的那几条 —— 它们每一个都是在实机上踩出来的。
