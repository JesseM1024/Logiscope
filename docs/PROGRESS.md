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
