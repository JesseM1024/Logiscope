# LogiScope · 进度快照

> 这份文件回答三个问题：**现在到哪了 / 下一步做什么 / 有什么坑**。
> 目标定义见 [`GOALS.md`](GOALS.md)，原理见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，
> 逐条验收步骤见 [`VERIFY.md`](VERIFY.md)。

---

## 一句话现状

**G0–G1.8 / G2 / G2.1 / G3 全部实机验证通过**（第九轮 S1–S6 通过，
`g2-waveform` 里程碑已打）。

**第十轮已经跑过四轮实机**：
① A–D 组全通过 + 压力测试通过 → 修 7 项；
② R1–R16 **全部通过** → 修 20t 偶发断线 / 1t 平台 / 读数精度（第三次修复）；
③ X1–X20 **全部通过** → 按"不要跨过去，要每格真值"把**采样挂到世界 tick**（第四次修复）；
④ Y1–Y20 **全部通过**（Y12 多人未测）→ **本轮（第五次修复）加配置上限 + 单 tick IO**。

复测清单见 [`VERIFY.md`](VERIFY.md)（只有本轮这一份）；历轮结论见
[`VERIFY-HISTORY.md`](VERIFY-HISTORY.md)。

| 项 | 状态 |
|---|---|
| 代码 | ✅ `BUILD SUCCESSFUL` |
| 离线断言（`dev-check/TestChart.java`） | ✅ **194 条**全过（聚合 / 断点 / 标记 / 单 tick IO / 种类上限 / 缓冲 resize） |
| 产物验收（`verify-jar.py`） | ✅ 全部通过 |
| 实机验证 | 🟡 前四轮实机全部通过（含第四次修复 Y1–Y20）；**第五次修复待复测**（`VERIFY.md`） |
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

1. **跑第六次修复的复测**（[`VERIFY.md`](VERIFY.md)，第十轮⑥）。重点：
   - **图例弹窗**：点物品图例弹「数量 / IO」两项；`(empty)` 只有「数量」
   - **读数**：仅开 IO 线时十字线报 `名称 Δ±n`；框选统计不再有 in / out
2. 通过后打里程碑标签 `g3-probes`（原定随第五次修复的里程碑顺延到本轮）。
3. **渲染性能优化**（压测基线见上一节：大量线条 20 FPS）—— 单独立项，别夹在其他改动里。
4. 再考虑 G4（标注与差值）或 G5（导出）。两者都可做：
   - G4 的落点已经现成：`ChartRenderer.Selection` 的两个端点就是标记的最佳位置
   - G5 几乎是把 `List<Sample>` 写成文件，连分析层都不用碰
   - 「准星强制探测」（F15）**已经顺带做了**（准星指着无 GUI 容器就能建探针）

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
| 7 | **纵轴刻度数字长到 GUI 外面**（测单格堆叠的大数量容器） | 8 位整数直接把轴挤爆 | 新增 `ChartRenderer#fmtCount`：≥ 10000 走科学计数法（`1.6E7`／`9.99E6`，用整数运算取尾数避免假精度），`AXIS_LEFT` 34 → 46px（**第三次修复更新**：改名 `fmtAxis` 且只给纵轴用，读数改用精确整数 `fmtExact` —— 见「第十轮第三次修复」） |

用户另外确认：**压力测试通过**（T22 多探针挂机不掉帧）。

---

## 第十轮第三次修复（第二轮实机反馈后的修复）

第二轮的结论是"R1–R16 全部通过"，但用户在**正常流速（20t）**下发现曲线上偶发断线，
断线之后还跟着一个 1 tick 的平台 —— 而且明确排除了"手动启停容器"这个原因。
另外提了两条精度要求。逐条：

| # | 反馈 | 根因 | 修法 |
|---|---|---|---|
| 1 | **20t 正常流速下偶发断线**（第九轮之前从没有过） | 第八轮把样本时间戳从"客户端自己的计数器"换成**服务端权威 gameTime**（CP15）。客户端每秒最多观测 20 次、服务端也每秒走 20 tick，两者**不锁相**：一次帧抖动就让某个客户端 tick 看到 +2 的世界 tick（少观测一格）。而 `ChartData` 的断点规则是"间隔 > 1 就断"——它是按"客户端时钟恒 +1"写的，于是在这里凭空剪一刀 | 断线改由**显式标记**决定：`no_data` / `gap` / 该物品在上一个数据 tick 缺席；观察抖动造成的小空档只让折线跨过去。另加一条 `MAX_SILENT_GAP = 20t` 兜底（防"停摆但没写标记"）。顺带解决 `/tick rate 200` 下"每个点都成断点"的隐患（CP10） |
| 2 | **断线后有一个 1t 平台** | 世界侧异步读值被 `latest(maxAge)` **复用**（服务端任务队列按服务端 tick 排空、客户端按客户端 tick 取用），复用时却贴上了新的 tick 标签 → 同一个观测写成两条样本。关/开界面的 `lastViews` / `seed` 垫值同理（那是第五~九轮为了消掉"假 no_data"引入的，副作用就是它） | 读结果带身份（`Read.seq` = 提交读取时的客户端 tick），**同一个观测只写一次**；没拿到新观测的这一 tick **什么都不写**。**删除 `lastViews` / `seed` 整套垫值逻辑**（没观测到就是没观测到，画图层连线即可）；读取器改成三态 `OK` / `UNAVAILABLE` / `NOT_READY`（CP9 / CP9 之一） |
| 3 | 新语义 | 需要区分"读了读不到"和"没在记录" | `Sample` 增加 `gap` 标记：暂停（手动 / 自动）时写一条 → 恢复后曲线**断开**，但**不计入 `no-data`**（那是读失败的专属计数） |
| 4 | **只有纵轴**可以用科学计数法 | 短格式被用到了读数（框选统计 / 十字线 / 面板数字）上 | `fmtCount` 拆成两个：`fmtAxis`（纵轴，科学计数法）与 `fmtExact`（读数，原样整数） |
| 5 | 纵轴一排刻度全是 `1.79E6` | 2 位小数的科学计数法在"大数量 + 窄窗口"下解不出有效数字 | 纵轴精度提到 3 位小数，并把**步长顶到"相邻标签不是同一个字符串"**为止（宁可稀疏不要看不懂） |
| 6 | VERIFY 太长 | 十轮的清单堆在一个文件里 | 历轮结论归档成 [`VERIFY-HISTORY.md`](VERIFY-HISTORY.md)，`VERIFY.md` 只留当前这一轮 |

**代码改动**（`Sampler` 时代之后最大的一次采集层改动）：

| 文件 | 改动 |
|---|---|
| `store/Sample` | 新增 `gap` 标记与 `isMarker()`（标记类样本的统称） |
| `analysis/ChartData` | 断点规则重写：按"上一个数据 tick 是否含这个物品 + 标记 + 静默大空档"判定；删掉 `CONTINUITY_GAP` |
| `analysis/Analyzer` | 标记样本统一跳过（`gap` 不计入 `no-data`） |
| `collect/WorldProbe` | `latest()` → `read()`：返回 `Read(views, seq, status)`；删掉 `seed()` |
| `collect/ProbeInstance` | 删掉 `lastViews` 与两处过渡垫值；新增 `lastUsedReadSeq` 去重；暂停时写 `gap` 标记 |
| `ui/ChartRenderer` | `fmtAxis`（纵轴，3 位小数）/ `fmtExact`（读数）；刻度步长按"标签可区分"顶上去 |
| `ui/ScopeScreen` | `now` / `min-max-avg` 改精确整数 + 按剩余宽度裁断 |
| `dev-check/TestChart` | 140 条断言（新增：观测空档不断 / 稀疏采样仍连续 / 静默大空档兜底 / `gap` 之后必断 / 标记对统计透明 / 多格下缺货必断） |

---

## 第十轮第四次修复（"不要跨过去，要真实值"，从根因修）

第三轮修复让画图**容忍**了观测空档（断线只认显式标记）。用户随后把要求提高了：
**曲线不该跨过空档，我要每一世界 tick 的真实值**，并且指出"单人下客户端 tick 与
服务端 tick 理应一致"。于是这一轮不再改画图，而是改**采样本身**。

| # | 反馈 | 根因 | 修法 |
|---|---|---|---|
| 1 | 曲线在空档处"跨过去"（是连线，不是真实值） | 采样驱动是**客户端 tick**（每秒最多 20 次），而世界每秒走 20 格（`/tick rate` 还能更快）——**两者节拍不锁相**，客户端必然偶尔看不到中间那一格 | **把采样挂到世界 tick 上**：`ServerTickEvent.Post`（在 `tickChildren` 之后发）里同步读方块实体，时间戳取那一刻的 `ServerLevel#getGameTime()`。世界走一格记一格，一格不漏 |
| 2 | （顺带）`/tick rate 200` 只能每 ~10 tick 采到一个点 | 同上：客户端只有 20 次/秒 | 现在**每秒 200 条**（服务端 tick 200 次 = gameTime +200）。这是"最准确的世界时间采样方法"的直接结果 |
| 3 | 时间轴"秒"标签在 200t 下会差 10 倍 | 硬编码按 20 tick/s 换算（第七轮就记录为 F16"已接受"） | 按**真实 tick 速率**换算：客户端能从 `Level#tickRateManager().tickrate()` 拿到（服务端经 `ClientboundTickingStatePacket` 同步） |

**结构上被删掉的东西**（复杂度实实在在少了）：

| 删除 | 原因 |
|---|---|
| `WorldProbe` 的异步读取管线（`request` / `read` / volatile 缓存 / `pending` / 新鲜度窗口 / `Read` / `seq` 去重） | 采样点已经在服务端线程上了，不需要"提交任务 + 取回结果" |
| `ServerClock#clientTick()` | 它只为"异步读值新鲜度"存在 |
| `ProbeInstance#lastUsedReadSeq` | 同上；"同一个观测写两遍"在结构上不可能发生了 |

**新增的纪律**：两条采样路径（客户端 tick 的菜单回退 / 服务端 tick）用
`ProbeInstance#sampleLock` 互斥，且**锁内复查状态** —— 否则"暂停"标记可能被
一条在飞的样本压在前面，把断口重新连起来。见 `ARCHITECTURE.md` 契约 C10。

**仍然保留的边界**：多人、以及"界面开着但下标没验证出来"的模组机器走菜单路径
（宁可有观测空档，也不要读错格子）；回退路径的空档由第三轮的断线规则兜着。

---

## 第十轮第五次修复（配置上限 + 单 tick IO）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 缓冲容量可配、默认 72000 | `LogiScopeConfig`（NeoForge 客户端配置）+ `SampleStore#resize`：**改完立刻生效**，保留最新样本 |
| 2 | 探针上限也可配（默认 16） | `maxProbes`，创建时判定 |
| 3 | 每个实例的物品种类上限 256 | `maxItemKinds`：超出的种类**照样记、照样进统计**，只是不画线，面板显示 `+N 未显示` |
| 4 | 监控每个物品格的**单 tick IO 量** | `Series#flow`：`flow(t) = 存量(t) − 存量(t−1)`，**只在相邻两格之间**算（段起点 / 跨格 / 断档后都没有流量，硬写 `n/t` 是假的） |
| 5 | 图例里 IO 是物品的子级、可控制渲染 | 图例每个物品两格：父格（存量线）+ `IO` 子格（默认关）。IO 线用同色系浅一档；有 IO 线时画 0 基线；纵轴自动缩放把可见的 IO 一起算（要看 IO 就把存量线关掉）；十字线 `/` 框选统计 / `now` 行都补上流量读数 |

**必须说清的一条边界**（写进了 `ARCHITECTURE.md` CP18）：
一进一出发生在**同一个世界 tick 内**时，两者互相抵消，"进多少 / 出多少"没有留在任何一次观测里
（我们能观测的就是"每格结束时的存量"）→ **IO 线会显示 0**。
单向流、或进出发生在不同 tick / 成批交替的场合，IO 线就是真实吞吐。
要看同 tick 双向往返的进出量，当前版本做不到（要 mixin 挂钩每个模组容器的变更回调，
且没覆盖到的机器会静默显示 0），替代做法是**同时监控上游与下游容器**。

## 第十轮⑤复测结论 + 性能压测基线（待优化，本轮不修）

**复测结论**：`VERIFY.md` A–E 全部通过（Z1–Z18，Y12 多人场景仍未测）。

**性能压测基线**（2026-09-18 实测，界面显示的 FPS）：
场景 = 同时输入**大量不同种类的物品线 + IO 线**。

| 场景 | FPS |
|---|---|
| 默认（不打开面板） | 1300 |
| 打开面板（根 UI） | 850 |
| 单线图表 | 300 |
| 双线图表（存量 + IO） | 200 |
| 大量图表（多物品多 IO 线） | 20 |

**定性**：打开面板本身开销可接受；掉帧随**可见线条数**近似线性恶化 ——
大头在"每条线每帧全窗口重画"（`ChartRenderer#drawSeries` 每条线一次列聚合 + 逐列 fill）。
记为**待优化项**：候选方向包括按脏区域缓存静态波形位图、多线合并到单次列聚合、
超出可见窗口的线段剔除。**下一轮（图例交互重构）不动渲染路径。**

## 第十轮第六次修复（图例弹窗 + 读数瘦身）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 框选统计删掉 `in` / `out`（区间里已有 Δ） | `ChartRenderer#drawSelection` 只保留 `首值→末值 Δ±n 速率`；`ChartData.SeriesRange` 的 `inflow` / `outflow` / `flowSamples` 三个字段连同计算一并删除（数据仍在 `Series#flow`，要捡随时能重算） |
| 2 | 图例里 `(empty)` 不需要 IO | 渲染层跳过 `(empty)` 的 IO（纵轴扫描 / 画线 / 十字线三处都挡）；弹窗里 `(empty)` 只有「数量」一项 |
| 3 | 仅启用 IO 线时十字线也要有读数 | `drawCrosshair` 改为对"存量关 / IO 开"的物品报 `名称 Δ±n`（不报存量数字）；"+N" 计数改按**可报的线**算，不再被隐藏线污染 |
| 4 | IO 并入物品图例 | 图例条**一个物品一格**；点击弹出「数量 / IO」小菜单（`ScopeScreen#legendPopup` + `popupRect` / `popupRows`，渲染与命中共用一份几何）。点菜单外关闭并吃掉点击；菜单失效（物品消失 / 图例被 +N 收起）自动收起；重开面板 / 切探针 / 重新选格时复位 |

**顺带删掉的东西**：`IO_PREFIX` 与图例 IO 子格的全部渲染 / 命中代码（`renderLegendIo`）、
`t20FlowInRangeStats`（TestChart 194 → 183 条断言）。

---

## 第十一轮（图例分半图标 + G4 标注 + G5 导出回放）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 图例图标**上下分半**：上半 = 存量线颜色，下半 = IO 线颜色 | `ScopeScreen#renderLegend`：色板劈成两半，关掉的那层显示灰；**两层全关时字体才变暗**（原先只开 IO 时整块是暗的，用户分不清"关了"还是"IO 开着"） |
| 2 | G4 波形标记 | `store/MarkerList`（纯类，升序 / 同 tick 去重）+ `ProbeInstance#markers`（**独立于环形缓冲**，写满滚动不覆盖）。**右键**波形加 / 删（±3px，原计划左击+双击改为右键，按用户建议避免与框选冲突）；悬浮读数：tick / 秒 / 各可见线的物品与数量（`ChartRenderer#drawMarkers`） |
| 3 | G4 差值 | 框选两点 → Δ数量 / Δtick / 平均速率，G2 已覆盖，本轮不改 |
| 4 | G5 导出 | 新 `archive` 包：`ArchiveFormat`（纯格式层：Gson JSON + CSV，`format` 版本号 + 逐字段默认值 = 向前兼容）+ `Archives`（文件系统 / 游戏侧）。目录 `.minecraft/logiscope/<存档名>/`，存档名 = `yyyy-MM-dd_HH-mm-ss`，内含 recording.json + recording.csv；no_data / gap 是标记行 **count 留空** |
| 5 | G5「存档并停止」交互 | 图表页新按钮：导出 → **写成功才**删探针与缓存（失败则探针原地保留）→ 直连进入回放页 |
| 6 | G5 回放页 | 新 `Page.REPLAY`：复用图表页全部机制（数据塞进同一个 `chartData`，呈现层对"活的还是回放的"无感）。根页**上半 = running probes、下半 = 存档列表**（默认高亮当前/最近存档，点击任意行跨存档读取）。滚轮分区滚动 |
| 7 | 回放可扩展性 | 图例弹窗多一项「分析」：选中曲线（品红描边，`replaySelectedItem`），后续数学运算挂这个字段叠上去；存档 JSON 的 `format`/逐字段默认值允许未来加字段不用迁移 |

**离线验证**：TestChart 183 → **211 条**（+`MarkerList` 12 条 + `ArchiveFormat` CSV/JSON 往返 16 条）；
dev-check 的 classpath 需加 `dev-check/lib/gson-2.10.1.jar`（ArchiveFormat 用 Gson，
jar 已随项目提交）。

---

## 第十一轮②（回放计算：CurveMath + 对象开关 + 运算弹窗 + 结果框）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 计算 quantity / IO 的开关（一次算一个） | 工具栏「计算 数量/IO」chip（`analyzeIo`），未选中曲线时置灰 |
| 2 | 数学运算按钮 + 列表 | 「运算」chip 弹 5 项列表（`opPopupOpen` / `OP_VALUES`）：关闭 / 极值 / 简单统计 / 变化总量 / 变更区间 |
| 3 | 极值 | `CurveMath#extremes`：min/max 含所在 tick（取值相等时先出现的） |
| 4 | 简单统计 | `CurveMath#stats`：mean / med / **总体方差**（除以 n） |
| 5 | 变化总量 | `CurveMath#totalChange / flowTotal`：Σ升 + Σ降 = Δ；**断档不计**并单独报缺口数（诚实原则：断档处的变化没留在观测里，不该被算进任何一栏） |
| 6 | 变更区间 | `CurveMath#changeEvents / flowEvents`：变更次数 / 变化量 mean·var / 周期 mean·var（周期 = 相邻变更的 tick 间隔） |
| 7 | 框选自动出结果 | 结果框画在绘图区**右上角**（`ChartRenderer#plotRect` 同源几何），框选统计在左上角，两块并存；结果按「区间×曲线×对象×运算」缓存，拖动中不出、静止零开销 |

**IO 段内口径**：只统计严格在段内的流量（段首采样 tick 的流量是从区间外带进来的，
算进去会与 Δ 对不上）；`CurveMath` 是纯类，TestChart 211 → **236 条**。

---

## 第十一轮③（NBT 取数探针：FE / 流体 / 化学品）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 选格页新增「Probe data」按钮 | 实时抓 `data get block`（`DataCommand`：发命令 + 抓系统回执 + **吞掉回执**不刷聊天框），**每秒重发一次**保持"实时" |
| 2 | 权限态度 | 先查 `player.hasPermissions(2)`：没有就**不发**，界面直说"需要命令权限"（单人开作弊 / 服务器 OP）。而**采样本身不依赖命令**（服务端直读方块实体），所以"看不了但能采"是允许的 |
| 3 | 虚拟容器（一行两格） | DATA 页底部一行两格：**值/路径** + **名称**（可空）。格子可点击聚焦、直接打字，支持 Ctrl+V 粘贴长路径 |
| 4 | 从实时结果里选值与名称 | 把命令原文解析成"路径 = 值"列表（`analysis/Snbt` 纯解析器）：**左键数值** = 采样取了它；**左键文本 / 右键任意值** = 用它当追踪名称（流体 / 化学品这类可变物品：名称跟着 NBT 走，罐里换了东西 → 图上换一条线） |
| 5 | 电力这类"没名字"的量 | 名称手输即可（如 `energy`），也可以留空（自动用值路径当名字） |
| 6 | 逐 server tick 采样 | `ProbeInstance#configureData` + `sampleOnServerTick` 的数据分支：读方块实体 NBT（`BlockEntity#saveWithoutMetadata(registryAccess)`）→ 按路径取数 → 写 `Sample(tick, name, value)`。**与物品探针共用状态机 / 缓冲 / 标记 / 存档 / 回放 / 计算**，只是"值从哪来"不同 |
| 7 | 诚实性 | 方块实体没了 / 路径不存在 / 那个值不是数字 → 写 `no_data`（**不写 0**）；多人下没有集成服务端 → 停在 PAUSED，不假装在记录 |

**待优化项（本轮刻意不做，记录备查）**
- 现在每 tick 都 `saveWithoutMetadata()` 构建**整份** NBT，再按路径取一个值。
  可能省掉的量：读一次只取那个子标签（或缓存 Tag 引用 + 只在路径的父节点变化时重读）、
  以及"路径的**前缀**先对一遍"再决定要不要深挖。
  要动的是 `DataProbe#readNumber` 一处，接口不变。
- `data get block` 那一侧（界面实时显示）本来就是"整份 NBT"的命令语义，
  换成"只取对应值"没有原版命令可用 —— 所以那条路只能靠**采样侧**（上面的优化）绕开。

---

## 第十一轮④（直读 NBT + 修复「开始采样」）

实测反馈：I1–I4 / J1–J5 通过，但**「开始采样」点不动**，且 I5（无权限提示）不是
用户要的——"看一眼"不该被权限卡住。

| # | 问题 | 根因 | 修法 |
|---|---|---|---|
| 1 | 「开始采样」点不动 | `handleDataClick` 的值列表分支 `my >= R.listTop()` 把**列表以下的一切点击**（含底部按钮区）全部吞掉 | 值列表只吃自己的行带（`listTop`~`listTop+rows×ROW_H`），行带以下放行给按钮循环 |
| 2 | 实时 NBT 依赖命令权限 | 上一版"看一眼"走 `data get block` | **单人直读**：读 NBT 的活提交到集成服务端线程（`server.execute`，CP15 同款纪律），SNBT 文本写回 volatile 字段；命令降级为多人 fallback |

**教训（一条通用的 UI 纪律）**：*"吃点击的命中分支必须限定自己的矩形"*——
一个大区域分支顺手写 `y >= top` 而不写下界，就会把排在它后面的控件全部挡死。
这类 bug 渲染层看不出来（按钮画得好好的），只有点击测试能暴露。

---

## 第十二轮（探针随世界清空 + DATA 页三段式 + 悬浮高亮）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 探针跨存档残留（A 存档建的出现在 B 存档） | **`LoggingIn` → `ProbeManager#clearAll`**：探针绑定"这个世界里的这台设备"，实例表却是进程级的——进世界清空，僵尸探针在结构上不可能出现。`DataCommand` 残留文本同刻清 |
| 2 | DATA 页三段式：NBT 原文 / 值选择 / 追踪目标 | 「开始采样」改为「确认添加」：目标攒进探针的 `dataTargets` 列表（按 valuePath 去重；列表里**值与名称锁定只能删**）。回选格点「完成」后与容器格一起开始——**数据目标与物品槽位共存**（`sampleOnServerTick` 两段式：物品守卫 `lastSampledGameTime`、数据守卫独立的 `lastDataTick`，因为界面开着且下标未验证时物品走菜单路、数据仍走服务端直读，两条路节拍不锁相） |
| 3 | 悬浮高亮联动 | `Snbt` 每个叶子记录**源文本偏移**（`Node#start/end`），原文折行时记录每行起始偏移；悬浮值列表行 → 按偏移换算行列 → 原文对应字段画品红底色 |
| 4 | 修：NBT 频繁变化时值列表/原文**每秒弹回顶部** | `reparseData` 重解析后不再重置滚动位置——NBT 数值每秒都变，"文本变了 = 回顶"对这种页面是灾难；滚动只在进页时归零，选中按路径匹配天然稳定 |
| 5 | 目标列表可滚动 + 行数随窗口增减（默认 3 行，1–8）+ 悬浮高亮改**黄色**（用户反馈淡紫难认） | `dataTgtScroll` + `dataRects` 按 `below/3` 分配目标行数；高亮常量 `COLOR_HILIGHT = 0x80FFD24A`（"分析/选中"仍用品红——那是"钉住"的语汇） |
| 6 | **修：同名目标的两个 NBT 值导致图表震荡**（`Items[0]` 与 `Items[1]` 的 item.id 相同） | 数据采样改为**先攒齐、按名称合并**再写入（新 `Aggregator#combineAll`：同名求和但**保留 0 与负数**——那是真实读数，物品语义的 `combine` 会把 ≤0 当空格子丢掉）。部分目标读不到 → 该目标留空；全部失效才写 no_data |
| 7 | 选区基础信息只显示启用的线 + 英文环境漏翻 | 选区统计在 View 组装处按 readable 规则过滤（与十字线同源）；"数据 x" / 计算结果框全部文案 / "(自动)" 补语言键（共 10 个） |

**顺带的结构调整**：`ProbeInstance` 的单数 `dataTarget` → 复数 `dataTargets`
（COW 列表，服务端遍历 / 渲染增删）；`configureData` 删除（`addDataTarget` 取代）；
`configure` 允许"只有数据目标没有格子"（纯数据探针走 FOLLOWING，服务端直读采样）。

---

## 第十三轮（渲染路径优化：多线掉帧）

**问题**（2026-09-18 实测基线，界面显示的 FPS）：

| 场景 | FPS |
|---|---|
| 默认（不打开面板） | 1300 |
| 打开面板（根 UI） | 850 |
| 单线图表 | 300 |
| 双线图表（存量 + IO） | 200 |
| **大量图表（多物品多 IO 线）** | **20** |

掉帧随可见线条数**近似线性**恶化 —— 大头是"每条线每帧全窗口重画"。

**根因（读码确认，非猜测）**：`ChartRenderer#drawSeries` 每帧对**每条线**做三件贵的事：

1. `Arrays.fill(colHas/colStart, false)` 清两个 plotW 长数组 —— 50 线 × 700 列 × 2 = **每帧 7 万次无用写入**；
2. 第二趟扫描 `for (c = 0; c < plotW; c++)` 全窗口空转（窗口里只有一小段数据时也扫满）；
3. 每列最多 **2 次 `GuiGraphics#fill`**（连接线 + 本列 min/max 竖线）——
   50 线 × 700 列 × 2 = **每帧 ~7 万次 fill**，这才是帧率杀手。

**修法（三条，全部像素等价）**：

| # | 改动 | 说明 |
|---|---|---|
| 1 | 列标记改**代际戳** | `colGen[c] == gen` 代替每帧清零满数组 |
| 2 | 只扫 **cMin..cMax** | 记录有数据的列区间，不再全宽空转 |
| 3 | **游程合并后再 fill**（新纯类 `analysis/RectRuns`） | 两条等价合并规则：① 同 x 区间且 y 相接 → y 包围盒（同一列的连接线 + 竖线）；② y 区间相同且 x 相接 → 并宽（台阶数据连续多列同值）。**不做"相近就并"**——那会抹平 1px 台阶，是改图不是优化 |

**顺带重构**：列聚合整段搬进纯类 `analysis/WaveColumns`（离线可断言、可压测），
渲染器只剩"把矩形丢给 GuiGraphics"。几何与语义（断档不连线 / 稀疏列线性插值 /
同列取 min-max 保尖峰）一句话没改。

**离线压测**（`dev-check/BenchRender.java`，走真实代码；50 线 × 700 列 × 1200 tick 窗口）：

| 数据形状 | 合并前 fill/线 | 合并后 fill/线 | 降幅 | 全帧（50 线） |
|---|---|---|---|---|
| 台阶（物品存量 / 液位） | 1399 | **10** | **99.3%** | 500 |
| 阶梯（罐子逐格进料） | 1399 | 120 | 91.4% | 6 000 |
| 噪声（FE 能量逐 tick） | 1399 | 698 | 50.1% | 34 900 |
| 流量（IO：多为 0，偶尔尖峰） | 1399 | 210 | 85.0% | 10 500 |
| **混合（实机近似）** | 1399 | **507** | **63.8%** | **25 350** |

**几何计算的耗时**（同压测）：约 1.5 ms/帧（50 线），不是瓶颈；fill 次数才是。

**已知边界**：噪声型数据（每 tick 都变）的合并下限是 **1 个矩形/列**，
在不引入纹理缓存的前提下没法再降 —— 那条路（`按脏区域缓存波形位图`）留作
下一轮候选，代价是要自己离屏渲染 + 处理缩放/配色变化时的失效。

---

## 第十四轮（B5 根因 + 界面细节：警告收敛 / 样本行 / 存档重命名 / 悬浮详情）

### ① "卸载后还在写旧值" —— 试过"方块 tick 判据"，**用户判定不需要，已回退**

**当时的报告**：换维度 / 走出 1000 格外，原版容器、模组容器、方块实体 NBT 三路都还在写
"卸载前的数值"。

**当时的分析**：`Level#isLoaded` 只回答"区块在不在表里"，**边界区块**（渲染距离内、
模拟距离外）在表里但不做方块 tick —— 机器是停的，数值冻在最后一次 tick 上，
于是曲线看着像"设备稳定运行"。据此把判据换成 vanilla 同源的
`ServerLevel#shouldTickBlocksAt`（= 模拟距离），抽成 `collect/ChunkTicking`，
三条读值路径（`WorldProbe` / `ProbeSlots` / `DataProbe`）统一走它。

**实测后的结论**：**已全部回退**（`ChunkTicking` 删除，恢复 `Level#isLoaded` 语义）。
理由（用户判定）：
- 那个"严格判据"的副作用是**走出模拟距离就变 no_data**；
- 而**"关掉 GUI 之后继续采"才是要的行为** —— 那条逻辑本来就是对的，**不需要 no_data**。

**保留的知识**（别丢，将来真要处理"冻结值"能直接用）：
`Level#getBlockEntity` 会**强制加载区块**并返回磁盘快照；判"机器到底跑不跑"要看
`ServerLevel#shouldTickBlocksAt(chunkPos)`（= 模拟距离），而不是 `isLoaded`。

### ② 序号警告只在"序号有重复"时提示

原先只要 `mappingStatus == UNVERIFIED` 就弹"下标不可信"。但普通的容器
（每格 menuSlotIndex 互不相同）按下标就能唯一对上、采样本来就是对的 ——
这时弹提示是误报（用户实测：点不同序号的空格子也会看到它）。
现在加一道 `hasDuplicateMenuIndex(target)`：只有 MEK 那类"全是 #0"的容器才提示。

### ③ 图表页信息行：min/max/avg → 样本条数 / 上限

`min max avg` 删掉（要看统计就存档后做区间运算，那里更完整且可复现）；
换成 `样本 N / 上限 M` —— 实时页真正想知道的是"缓冲用了多少"。

### ④ 存档重命名 + 列表三列对齐

- 重命名只写存档目录里的 `name.txt`（**不动 recording.json**）：旧存档天然兼容，
  改名也不用重写几万个样本；空名字 = 清除自定义名。
- 入口在**回放页工具栏**的「重命名」：点一下弹输入框（Enter 确认 / Esc 取消 /
  Ctrl+V 粘贴），浮在绘图区顶部，不占常规布局 —— 不干扰图表交互（用户原话）。
- 根页存档列表改成**三列固定对齐**：名称（左侧留 44% 宽、可重命名）/
  文件夹名 / 样本数（右对齐），列名贴在标题行右侧。

### ⑤ 悬浮详情：+N / data xN / 槽列表，长列表可滚轮

三处"收起来的列表"都能悬浮展开（内容过长时用滚轮上下翻，底部显示 `x / y`）：

| 悬浮目标 | 展开内容 |
|---|---|
| 图例的 `+N` | 被收起的那些线的名字 |
| 探针行的 `数据 xN` | 该探针追踪的全部 NBT 键（`路径 ← 名称路径`） |
| 选格页第一行的 `槽 0,1,2+24` | 每个已选格子：`槽 n · 物品 x数量` |

实现是一个通用悬浮框（`updateHoverTips` + `renderTip`）：每帧重建、跟着鼠标、
夹在面板内，滚轮在框内时优先滚框（不吃掉别处的滚动）。

---

## 第十五轮（菜单/世界两条路径读数不一致 + 悬浮详情覆盖 + 漏翻）

### ① "GUI 里 2 个、关掉 GUI 后 8 个" —— 试了一版"按身份读"，**实测更糟，已回退**

**归因先说清**：与第十四轮②的"警告收敛"**无关**（抑制一条提示不可能改数值）。

**第一次尝试（第十五轮，已回退）**：让菜单路径也按**容器内下标**反查读，
想让两条路径"统一"。结果**用户实测判定 8 是错的、2 才是真值** ——
也就是说 **`Slot#getContainerSlot()` 对这类模组机器不可信**（它的"下标"语义与
能力(handler)的下标序不一致；只有 vanilla `Container` 才保证一致）。
按身份读等于"把错的统一到了两条路径上"。**已回退成按菜单位置读**
（`readMenuSlots` = `ContainerAccess.read(menuSlotIndex)`，与渲染优化版本一致）。

**待办（明确记着，别忘）**：坐标路径在映射**未验证**时仍会退回容器下标读 ——
那就是"8"的来源。正确做法是**不猜**：映射未验证且界面关着时，记 `no_data`
（或停在 PAUSED），而不是写一个可能是别格的数。这条属于"宁可留空也不编数据"，
但会牺牲"关界面也能采"的便利，所以留给用户拍板。

### ② 悬浮详情覆盖不全

上一轮只挂了三处（图例 `+N` / 探针行的 `数据 xN` / 选格页第一行），用户实测
**图表页与根页的"第二行"没反应**。现在抽成共享的 `probeTipLines()`（先列 NBT 键、
再列每个已选格子含当前内容），挂到**信息区第一行（各页）**与**根页探针行**，
标题形如"探针详情 · 数据 2 · 槽 16"。长列表照旧支持框内滚轮。

### ③ 漏翻（`槽` 是硬编码中文）

用脚本扫了一遍所有"会显示给用户"的字符串字面量（`_scan.py` 的思路：
只看字符串字面量、不看注释），补了 7 个语言键：
`槽 %s` / `（未选格）` / `运行时身份` / `已打开的容器（无坐标）` / `[名称]` /
探针详情标题 / `数据` 行前缀。
**仍未本地化的是 `LogiScopeConfig` 的注释**（NeoForge 的 `.comment()` 是静态字符串，
不走语言文件）—— 记为已知限制。

---

## 第十六轮（NBT 槽位型容器：MEK 那类"slot 号写在 NBT 里"的针对性探针）

**需求**（用户）：MEK 的物品格**有些时候空格子会直接从 NBT 里消失**；
NBT 里能看到 `item[0].item.count` / `item[0].item.id` / **`item[0].slot`**。
"MEK 的物品栏是通过 slot 特殊计算的" → 希望**针对这类容器写专门的探针逻辑**，
覆写打开/关闭 GUI 两种情况：点到某格就只监测 NBT 里对应 slot 的值；
**空格子在 NBT 里没有元素、拿不到 slot 号 → 就不选它**。

**做法（"NBT 是唯一权威"）**：

| 环节 | 逻辑 |
|---|---|
| 识别 | 新纯类 `analysis/NbtSlotLayout`：在 NBT 树里找"**每个元素都自带 slot 号**的复合列表"（忽略大小写的 `Slot` + 内部或同级的 `id`/`Count`）；找不到就不是这类容器。空列表、只有 Count 没有 Slot、流体列表都不会误判（离线断言钉死） |
| 拾取 | 选格页每条 tick 抓一次方块 NBT（单人直读，不花权限）并检测。命中这类容器时，点到的格子**按"物品 id + 数量"反查 slot 号**：唯一命中才采用；空格子（没有元素）或同物品同数量命中多个（分不清）→ **都不选**，并提示跳过了几个 |
| 存储 | 选中结果**不生成 SlotRef**，而是生成 `DataTarget.slotAnchor(listPath, itemId, …, slot)`（`valuePath` = 列表路径，`nbtSlot` = slot 号）—— 所以这条探针是"纯数据探针"，采样完全不碰菜单 |
| 读值 | 每 tick：读方块 NBT → 解析 → 在列表里找 `slot == nbtSlot` 的元素 → 取 `Count` / `id`。**GUI 开还是关，走的是同一条路**，于是不可能再出现"开界面 2 个、关界面 8 个" |
| 空格子 | 元素不存在 = 数量 **0**（"这格是空的"的诚实表达）；设备整块读不到才是 `no_data` |

### ① 第十六轮②（实机反馈后的四处修订）

| 反馈 | 修法 |
|---|---|
| **同种物品命中多格要加起来** | 每一格**各建一个锚点**（同名），采样时按名称合并 → **自动相加**（`Aggregator.combineAll`，t28/t31 都有断言） |
| **点了输入输出，输出格却没进来** | 匹配从"物品 id + 数量"改成**只按物品 id**：运行中的机器每秒都在变数量，"菜单快照"与"NBT 快照"读取时刻不同 → 数量匹配会漏格（这就是输出格丢失的原因）。id 稳定得多 |
| **回选格页再选、再 Done 要更新列表** | 明确为**追加/更新**语义：`configure` 只清样本、不碰 `dataTargets`，所以旧锚点不丢；同一 slot 去重（去重键从"路径"改成"(路径, slot 号)"） |
| **消失 → 0 / 重现继续 / 换物品开新线** | 已经是这个语义：元素消失 = 数量 `0`（写回锚点的兜底名）；重现且物品相同 → 同一条线继续；**换了一种物品 → 名字变了 = 新开一条线**（旧线自然停止） |
| **只有创建列表时"无对应 slot 值"的格才取消监测** | 明确：仅拾取那一刻、NBT 里找不到该物品的格子才跳过；此后元素消失只写 0，不取消 |

**由此确定的一条语义公约**：*"选中一个物品 = 选中当前所有装着它的格子"*（因为槽位号
不从位置来、而从数据来，且同物品求和）。这对多格同物品的机器正是想要的效果，
代价是"只想跟其中一格"这类诉求无法表达 —— 已记在案。

### ② 第十六轮③（按格区分 + NBT 追踪改为显式按钮）

| 需求 | 落地 |
|---|---|
| **加一层"按格区分"** | 新纯函数 `NbtSlotLayout.resolvePick(candidates, containerSlotIndex)`：① 容器下标恰好等于某个 NBT slot → **就选那一格**（真正"点哪格监测哪格"）；② 只有一个候选 → 就是它；③ 分不清（多个候选且下标对不上）→ **全纳入**（宁可多纳入也不漏格，并在提示里说明有 N 处歧义） |
| **但聚合计算不要受后缀影响** | 采样用的名字仍是**物品 id**（不带后缀）→ 同物品多格在 `Aggregator.combineAll` 里**自然求和**。slot 号只出现在**目标列表与悬浮详情的显示**上（`DataTarget.displayValue()`），因此可以单独删掉某一格的目标 |
| **NBT 追踪设为按钮** | 默认**关**；检测到"数据里带 slot 号的物品列表"时按钮才可点（`NBT 追踪：关/开`）。用户原话的理由：**错误地自动启用会让"机械手"这类设备没法按常规路径追踪** —— 必须由人显式打开；离开选格页自动回到"关" |

**新增契约**：CP24 的 C24-2 增补"按格定位优先级"、C24-6b"按格区分只到目标表为止"、
**C24-7 NBT 追踪必须显式开启**。

**为什么这比"菜单/世界两条路"更好**：槽位号不再需要从位置或下标**猜**——它就写在数据里。
CP23 那条"位置与身份都可能骗人"的教训，在这类容器上被绕过去了。


## 已知问题 / 已接受的限制

完整列表在 [`VERIFY-HISTORY.md`](VERIFY-HISTORY.md) 第三节。这里只列**值得记住**的几条：

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


### ③ 第十六轮④：实机反馈（先入档，再动代码）

| # | 现象 | 根因 |
|---|---|---|
| 1 | MEK 终极熔炼工厂（9 进 9 出）：点了 2 个原料格 + 2 个输出格 → 「完成」后只跟到 **1 个原料格 + 全部输出格** | **`Slot#getContainerSlot()` 在 Mekanism 上恒为 0**（字节码：`InventoryContainerSlot` 构造时 `super(emptyInventory, 0, x, y)` —— 容器下标**硬编码 0**）。而 `resolvePick` 把"容器下标 == 某个 NBT slot"当**最高优先级**判据 ⇒ ① 点到的格物品恰好撞上 slot 0 → 只认那一格，两格原料**互相覆盖**塌成 1 格；② 撞不上的（输出格 slot 9..17）→ 退到"全纳入"，2 个输出格**炸成全部输出格** |
| 2 | 回选格页再点「完成」应当是**覆写**，不是追加/更新 | 第十六轮②定的"追加"语义被用户推翻（C24-2b 作废） |

**顺带坐实的三条外部事实（免得再挖一遍）**：

- **Mekanism 10.7 的物品栏 NBT 形状 = `items:[{id,count,slot:byte}, …]`**：
  `ContainerType.ITEM` 的 (containerTag, containerKey) = (`items`, `slot`)，由
  `DataHandlerUtils.writeContents` 写入 —— **空槽根本不写进列表**，且 `slot` 就是该元素在
  机器物品栏列表里的下标（列表本身按 slot 升序）。
- `MekanismTileContainer.addSlots` 的动作顺序 = *玩家背包 → 2 个虚拟升级槽 → 按
  `getInventorySlots(null)` 顺序逐个加*。所以**机器格在菜单里的相对顺序 == 数据里的 slot 顺序**，
  中间可能夹着**空着**的虚拟/能量格。
- NeoForge 的 `SlotItemHandler` 恰恰相反：`super(emptyInventory, index, x, y)` —— 它的
  `getContainerSlot()` **就是 handler 下标**（= NBT 的 `Slot`）。所以"容器下标"这条路
  **只对一部分模组成立**，绝不能当通则用（正是第十五轮 CP23 教训的同一形状）。


### ④ 第十六轮④：点格规则改为"顺序 + 内容"对齐 + 「完成」改覆写

两条实测反馈的修法（根因见上一条③）：

| 问题 | 修法 |
|---|---|
| 点 2 原料 + 2 输出 → 跟到 1 原料 + 全部输出 | 新纯类 **`analysis/SlotAlign`**：菜单里**非空**的机器格（按菜单顺序）与数据元素（按 slot 升序）**天然同序**，取按物品 id 的**最长公共子序列**，**唯一解才采用**（多解 / 对不上 = 拒绝）。`resolve` 把四条判据收进一个函数：① 顺序对齐 → ② 容器下标（**要求"该清单下标不重复"+"那个 slot 的元素就是这一格的物品"**）→ ③ 按物品 id 唯一命中 → ④ 空格子靠**统一位移**外推。**四条都不成立就不选**（旧规则的"全纳入"已删除） |
| 回选格页再 Done 应该是**覆写** | `ProbeInstance.replaceSlotAnchors`：只换锚点，**数据页的纯路径目标保留**；一格都没解出来 → **什么都不动**（"读不到数据"≠"用户想清空"）；进页面时把已有锚点**回填成勾选状态**（C24-8），否则覆写会误清 |

**为什么"顺序"是可信的**（这一条值得记）：
- NBT 侧：`DataHandlerUtils.writeContents` 按物品栏列表顺序写 `slot = i`，且**空槽不写** → 列表本身按 slot 升序；
- 菜单侧：`MekanismTileContainer.addSlots` 按 `getInventorySlots(null)` 顺序逐个 `addSlot`（前面多两个**空着**的虚拟升级槽）；
- 于是"非空格序列"两边同序，**中间夹的空槽两侧同时消失**，不影响对齐。

**容器下标判据的降级依据**（同一条教训的第二次）：`Slot#getContainerSlot()` 对
NeoForge 的 `SlotItemHandler` 是 handler 下标（对），对 Mekanism 的 `InventoryContainerSlot`
是硬编码 0（错）。**"一个字段在 A 模组里对、在 B 模组里错"时，正确的做法是给它加一条
可验证的门槛（这里：清单内不重复 + 内容一致），而不是把它当首选。**

**删除**：`NbtSlotLayout.resolvePick`（旧"按格区分"的三种优先级）与其断言 t32 ——
留着它就是留一个已知会误判的判据（"把错的统一"的教训）。TestChart 322 → **336** 条（t33）。
文档：ARCHITECTURE CP24 重写 C24-2/C24-2b，新增 **C24-2c / C24-8 / C24-9**；
VERIFY 重建为 第十六轮④（N1–N14 + 回归 R1–R4）；历轮 Y 组归档进 VERIFY-HISTORY。

**结论**：第十六轮④ 全部用例（N1–N14 + 回归 R1–R4）**实测通过**（2026-09-19）。
当前版本 `952b8fb` 即"已验证可用"的基线；VERIFY.md 随之重建为"未测项 + 上一轮结论摘要"，
本轮用例归档进 VERIFY-HISTORY 第四节。


## 第十六轮⑤：代码审查 ①②③ 三组 8 条 P1 全修（提交见下）

按审查报告（`docs/CODE-REVIEW.md`）的建议顺序修完（每条都补了离线断言，336 → **376** 条）：

| # | 修法 | 断言 |
|---|---|---|
| 1 | **IO 统计口径**：`flowTotal`/`flowEvents` 不再拿 `breakBefore`（渲染的断线标记）当"值不可信"—— 流量点只在"相邻两格都观测到"时才产生，**每个值都成立**；缺口数改为"真的少了几笔 Δ"（段起点之后每一条断线算一次） | t34 |
| 2 | **装不下 ≠ 怪数**：`(int) Math.round(v)` 前判有限性与 int 范围，越界/NaN 一律留空；`Snbt.parseNumber` 不再接受 `NaN`/`Infinity`；`NbtSlotLayout.intField` 不再直接窄化 | t36 |
| 3 | **暂停水位**：`writeGapMarker` 取 `max(物品水位, 数据水位)`（原先只看物品路 ⇒ 纯数据探针暂停不写 gap） | 需实机（C1） |
| 4 | **锁内重取目标**：`sampleOnServerTick` 的 `target`/`dataTargets` 与守卫全部搬进 `synchronized` 里重算（锁外那份只做早退预筛），避免 `configure()` 之后用旧设备写新会话 | 需实机（D1/R2） |
| 5 | **换设备 = 读不到**：`DataTarget` 增加 `beType`（建档时取 `data get block` 的 `id`），采样时与 `BlockEntityType.getKey(be.getType())` 比 —— 换了 ⇒ `no_data`；还是同一种 ⇒ 元素不在就是 0 | 需实机（D1/D2） |
| 6 | **跨世界清理**：`LoggingIn` 里补调 `ContainerAnchor.clearActive()`（顺带清 pending）与 `ProbeSlots.reset()` | 需实机（E1） |
| 7 | **焦点跟页面走**：`keyPressed` 开头 `renameFocus && page != REPLAY → 清` | 需实机（E2） |
| 8 | **存档头部改流式读**：`readHeader` 用 `JsonReader` 走 token、`samples` 整段 `skipValue()`；容错口径不变（坏文件抛 IOException、坏类型当缺省） | t35 |

**审查自身的两处纠正**（诚实记录，也写进了 CODE-REVIEW 与 ARCHITECTURE）：
- **"拖窗口会反复 full-parse 存档"不成立**：vanilla `Screen#resize` 只走
  `rebuildWidgets()`，**不会**重跑 `init()`（读 sources jar 核实）。真正的浪费只是
  "每次打开面板全量解析一次"，所以只修流式读、**不加**按时间的节流（不想为一条错的推断
  改掉"打开就刷新"的行为）。
- **P1-5 光"拆开两种 null"会改坏刚验过的行为**：MEK 在**所有格子都空**时
  `items` 键根本不写（`saveTo` 里 `if (!list.isEmpty())`），于是"列表不在"也可能是
  "机器还在、全空"。改用**方块实体类型比对**才能既保住"空格子 = 0"（N14）
  又让"设备被换掉"变成 `no_data`。

**另一条值得记的**：修 #1 时`TestChart` 里有 **4 条旧断言变红** —— 它们的期望值正是
**被 bug 吞掉的那笔流量**（`IO 上升 64 / 断档 2 处 / 变更 2 次 / mean 27`）。
断言把 bug 固化了：改对了反而红。已按新语义改正（158 / 1 处 / 4 次 / 37），
并把"为什么"写在断言旁边。

**离线**：build / TestChart **376 条** / BenchRender / verify-jar 全过。
复现脚本 `dev-check/ProbeFlow.java` 已并入 t34 后删除。
文档：ARCHITECTURE 新增 **CP25**（六条硬边界）+ **C24-10**（换设备判定）；
CODE-REVIEW 标注 8 条已修；VERIFY 重建为第十六轮⑤（A–F 组）。


### 补（同日）：B1 复测反馈 —— "2^63 追不出图"的答复与两处文案修正

用户复测 B1：追创造能量立方的 2^63 → 图上一条线都没有、图中央写
`all series hidden by the legend`。结论分两半：

- **"没有线"是第十六轮⑤的预期结果**：`Math.round(9.22e18)` = `Long.MAX_VALUE`，
  旧代码 `(int)` 截断后是 **-1**（2^63 回绕）——修复前画的是一根贴在 -1 的假线；
  现在诚实留空 ⇒ 整 tick 无可写样本 ⇒ `no_data`。info 行的 `no-data` 在涨就是在说"读不到"，
  数据页的 NBT 原文也仍能看到 9.22E18。
- **"文案"确实错了**：`ChartRenderer:311-317` 把三种"没线"并成一句 ——
  有线但被图例关掉 / 有线但窗口没样本 / **一条线都没有**。第三种被说成"被图例隐藏"
  （`logiscope.chart.all_hidden`），等于把"没有数据"栽赃给用户的操作。
  修法：`anySeries`（`data.series()` 是否为空）参与选择，新增
  `logiscope.chart.no_lines`；并给「添加数据目标」加了**当场提醒**
  （`logiscope.flash.data_out_of_range`：值 + 上限 ±21 亿；只提醒不拦 —— 值可能以后落回范围内）。

**根因缺口记入下一轮**：样本存储是 `int`（±2.1e9），能量类读数与浮点量都装不下。
按用户口径（"有些时候需要追浮点值，能否自适应变量类型"），
**自适应数值类型**的设计与落地面已写进 [`NEXT-ROUND-DRAFT.md`](NEXT-ROUND-DRAFT.md) 的 **G1**
（新纯类 `analysis/Val`：整数用 long 保持"读数精确"，小数/超长用 double；渲染走 `asDouble()`
保住热路径零分配；存档向后兼容）。判定：**不是"顺手"能改的**（10 个文件、60~80 处，
且压着热路径与存档格式），按用户"不好一起搞就下一轮"的意见排下一轮第一位。

### ① 提示排版（用户指定，第十六轮⑥）

面板上方的临时提示：内容宽度**定死为面板宽度**、自动换行、**最后一行贴着面板顶**。
- 折行是**逐字符**的（中文没有空格，按词折会失败）；行内若已有空格则优先在最近的空格断。
- 顶上放不下（面板贴屏幕顶）→ 整块挪到面板下方，首行贴面板底（与旧"挪下去"一致，只是从一行变整块）。
- 折行结果按文本缓存：提示每帧都要画，逐字符算宽度不能每帧来一遍。

### ② 自适应数值类型 Val（G1 落地，契约 CP26）

新纯类 `analysis/Val`：**能精确用整数表示就用 long（integral），否则用 double**；
两份并存（`i`/`d` 互为影子），画图/统计一律走 `asDouble()`（零分配），读数走整数形式（精确）。
贯通：`Sample` → `Aggregator` → `SeriesPoint`/`Series`/`ChartData` → `CurveMath` →
`WaveColumns`/`ChartRenderer` → `ArchiveFormat` → `DataProbe`/`ProbeInstance` → `ScopeScreen`。

**四个坑（都已断言钉住）**：
- **读取按 NBT 标签类型分流**：byte/short/int/long 走 `Val.of(long)` —— 2^63 才能精确画；
  float/double 走 `Val.of(double)`；非有限 → null（读不到，不造 0）。
- **渲染只走 `asDouble()`**：`WaveColumns` 列缓冲改 `double[]`、`emit` 的双 yOf 合并成一个
  double 版 —— 热路径零分配不变（Val 逐列新建对象就是每帧几百次分配，禁止）。
- **求和溢出回退不能走 `of(double)`**：它的规范化会把 2^63 折回 `Long.MAX_VALUE`，
  把溢出悄悄抹掉；必须直接构造非整数形式。
- **存档向后兼容**：整数 `n` / 小数 `d` 双字段；旧文件只有 `n`（`getAsLong` 读）。

**顺带的行为改进**：平均值不再截断（旧 `(int)(sum/n)` 把 15.67 砍成 15）；
CSV 里整数原样（64 而不是 64.0）、小数用 double 字面量；
顺带删掉 ChartRenderer 的三段死代码（`colOf`/`vline`/`vlineF`，审查 P3 已记录）。

**离线**：build / TestChart 376 → **398 条**（t37：形式与规范化、NaN/∞ 拒绝、溢出回退、
2^63 全链路、小数系列、存档往返）/ BenchRender（降幅不变）/ verify-jar 全过。


## 第十六轮⑦：QoL 打磨批（实测基线 `83584f0` 之后，用户指定 8 项）

| # | 项 | 落地 |
|---|---|---|
| 1 | 根页「打开存储文件夹」 | 存档列表下方一个 chip（列表空/太矮时不显示，宁缺毋错位）；`Util.getPlatform().openFile` 打开 `Archives.rootDir()`（= `.minecraft/logiscope/`），不存在先 `Files.createDirectories`；失败提示（`flash.open_folder_fail`）。命中矩形渲染时记进 `openFolderRect`（渲染与点击同一份几何） |
| 2 | 删"NBT slot-list, track by slot"句 | 选格页信息行只在 `nbtSlotMode` 时显示 `NBT tracking: ON (N)`；lang 键 `picker.nbt_slot` 删除（中英） |
| 3 | NBT tracking ON 黄色 | `accentFor("nbt_mode")`：开 = `0xFFFFD24A` |
| 4 | Probe data 移到 Done 左侧 | PICKER 按钮序列交换 |
| 5 | list → Main screen 淡绿 | `to_list` 文案改"主界面 / Main screen"；`accentFor` 对 `to_root`/`list`/`replay_back` 返回 `COLOR_MAINSCREEN`（`0xFF90EE90`） |
| 6 | 图表页按钮排序 | `pick/list` 移到末尾，顺序 = pause → resume → clear → archive → follow → **Back to slot（淡蓝 `COLOR_BACKSLOT`）→ Main screen（淡绿）** |
| 7 | 计算页右键图例 = 分析 | `mouseClicked` 在"非左键一律吃掉"之前插入分支：REPLAY 页右键命中图例 → 切换 `replaySelectedItem`（与弹窗里「分析」行同一动作），左键弹菜单行为不变 |
| 8 | 顶栏光效 | `renderTopGlow`：2px 底条（低 alpha）+ 移动亮斑（15 段梯形 alpha，半宽 45px，周期 2.8s）；页色 = 根页淡绿 / 选格+数据页淡蓝 / 图表淡黄 / 计算淡紫。每帧 ~16 次 fill，与背景同量级 —— **不需要降级呼吸光效** |

**离线**：build / TestChart 398 条 / verify-jar 全过；lang 中英各 165 键一致。


### 第十六轮⑦修订：实测反馈三条（open_folder 位置 / 按钮颜色 / 光效不显示）

| 现象 | 根因 | 修法 |
|---|---|---|
| open_folder 被 new probe 挡住 | 浮在存档列表下方，几何上与探针列表重叠 | **改进按钮行**（ROOT buttonItems：create → open_folder → close，与 new probe / hide 同级同行）；删除 renderOpenFolderChip / openFolderRect 整套方案，动作走 `onChip("open_folder")` |
| 按钮文本颜色替换不成功 | `renderButtons` 把构造时存好的 accent **传成了 0**，renderChip 拿不到语义色 | 渲染时实时求值 `accentFor(c.id())` —— 顺带修掉另一个隐患：accent 依赖运行状态（如 NBT ON 黄），而按钮行只在 rebuild 时重建，构造时求值会**滞后一拍** |
| 顶栏光效不显示 | `renderTitle` 会整条 fill 标题栏背景，光效画在它**之前**被完全盖住 | 调用顺序改为 renderTitle **之后** renderTopGlow |

**教训（一句话）**：三个都是"画/算的顺序"问题 —— 颜色在渲染链上被中间层丢掉、
光效被后画的背景盖住、状态色在构造时被冻结。渲染链上的每一段都要问：
"我传下去的东西，后面会不会被覆盖/丢弃？"

离线：build / TestChart 398 条 / verify-jar 全过。


### 第十六轮⑦续：两个按钮语义色（2026-09-20）

- 选格页「Done」→ 黄色（`0xFFFFD24A`，与 NBT ON 同一黄）；
- 图表页「Stop & archive」→ 淡紫（复用 `COLOR_CALC_GLOW`，与计算页光效同色系）。
  accentFor 加两个静态分支即可（渲染时实时求值的机制上一轮已修好）。build / verify-jar 全过。


### 第十六轮⑦续：两个按钮语义色（2026-09-20）

- 选格页「Done」→ 黄色（`0xFFFFD24A`，与 NBT ON 同一黄）；
- 图表页「Stop & archive」→ 淡紫（复用 `COLOR_CALC_GLOW`，与计算页光效同色系）。
  accentFor 加两个静态分支即可（渲染时实时求值的机制上一轮已修好）。build / verify-jar 全过。


## 第十六轮⑧：AE 网络支持（特例覆盖，用户点名 2026-09-20）

**需求**：选格页为 AE 终端等面板时，点击一个容器格 = 监测该 AE 网络内对应物品数量；
AE 不能通过容器格判断，写特例覆盖逻辑。

**API 核实（javap，AE2 19.2.17）**：AE 终端格子 = `RepoSlot`（网络列表显示窗口，非容器内容）；
菜单家族 `MEStorageMenu`（wtlib 无线终端同族；PatternAccessTerm 不在此族）；19.x 的
存储读值 = `MEStorage.getAvailableStacks()` → `KeyCounter`（没有旧版 IItemList）；
`getGridNode(dir)` 的 default 实现按 `isExposedOnSide` 过滤 + CableBus 不实现 IActionHost
⇒ 必须遍历 6 面取节点。

| 件 | 内容 |
|---|---|
| `collect/AeNetworkReader`（新） | 全反射零编译依赖。`read(level,pos,itemId)`：host → 6 面找节点 → isActive → storageService.getInventory().getAvailableStacks() → AEItemKey.of → KeyCounter.get。语义：null = 读不到（非 AE/断电/缺 AE2）→ no_data；0 = 网络在线没这物品（真值）。`isAeStorageMenu(menu)` 供 UI 检测。失败只记一次日志 |
| `bind/DataTarget` | `AE_NETWORK_PATH = "ae:network"` 标记 + `aeNetwork` 工厂 + `isAeNetwork`；去重规则抽成静态 `isDuplicate`（AE 按 literalName —— valuePath 全相同，绝不能拿它去重；t38 钉住） |
| `collect/ProbeInstance` | isDuplicate 委托；`replaceAeTargets`（覆写 AE 目标，保留纯路径/锚点）；采样循环加 AE 分支（Val.of(long) 精确承载） |
| `ui/ScopeScreen` | seedSelectionOnce 检测 + 回填已选；toggleRow 只认 RepoSlot；selectionCount/isSelected AE 分支；cell_mode 禁用 + applyCell 双保险；commitPicker → commitAeTargets（覆写，空选不动）；信息行 AE 文案；leavePicker 清标志 |
| lang | picker.ae_mode / picker.ae_hint / flash.ae_none / flash.ae_replaced（中英 169 键一致） |
| 测试 | t38（13 条）：AE 工厂/判定/displayValue + 去重三分支 + 与锚点/普通路径互不干扰。TestChart 398 → **411 条全过**。t38 起测试 classpath 加 MDG merged jar（record 签名解析需要，VERIFY 命令已更新） |
