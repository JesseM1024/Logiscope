# LogiScope · 历轮记录（第一 ~ 十七轮）

> 本文档是 [PROGRESS.md](PROGRESS.md) 的历史归档：每轮只留**结论、根因、已接受的限制**。
> 现行设计以 [`ARCHITECTURE.md`](ARCHITECTURE.md) 为准；历轮验收结论见
> [`VERIFY-HISTORY.md`](VERIFY-HISTORY.md)；当前轮与未完成项见 [`PROGRESS.md`](PROGRESS.md)。
>
> 轮次验收结论的总表在 `VERIFY-HISTORY.md` 第一节（第一轮 ~ 第十六轮④）。

---

## 第一 ~ 四轮：G0–G1.8 骨架与面板

- **G0 工程骨架**：ModDevGradle + 项目内 wrapper + 锁 JDK 21。构建基线教训：中文资源必须
  显式 `filteringCharset = 'UTF-8'`（否则元数据写成非法 UTF-8，FML 报错完全不提编码）。
- **G1 最小闭环**：按键 → 选槽 → 采样 → 实时数字；三条读数路径（菜单 / 单人世界侧 / 多人
  `no_data`）。
- **G1.5–G1.7 面板**：`ClientHooks.pushGuiLayer` 浮层（真 Screen 栈）、面板外鼠标转交容器、
  面板可推出屏幕；**G1.8** 右下角可缩放。
- **第三轮（文本守卫）**：两个根因固化进 CP2 —— 守卫状态必须是**单事件布尔量**（Pre/Post
  计数差会留下永久的 +1，令快捷键永久失灵）；`KeyMapping#matches` 不看修饰键。

## 第五轮：G2 波形可视化

- 扁平序列 → 按物品分线（**派生视图**，CP10）；断点统一为 `breakBefore`；`ChartRenderer`
  （自动缩放 + 刻度吸附 / 列 min-max 保尖峰 / 稀疏插值 / 框选 / 准线）；面板重写；
  派生数据按 `version` 缓存；离线断言 47 条。
- 实机暴露三个叠加问题（第六轮修）：**线全不可见**（`fill` alpha=0）、模组机器关 GUI 全
  `no_data`（缺 capability 路径）、无数据窗口里框选无反馈。

## 第六轮：修第五轮暴露的问题

- **`fill` 颜色必须 8 位带 alpha**：`GuiGraphics#fill` 没有 `Font.adjustColor` 那样的兜底，
  6 位色画出来全透明（结论进 CP11）。
- **capability 回退**：`Capabilities.ItemHandler.BLOCK`（先 `side=null`，再六面）——
  多数模组机器不实现 vanilla `Container`（javap 核实，CP9 之二）。
- 其余：无数据窗口也画选区；提示补"点「全览」看整段"。

## 第七轮：修第六轮暴露的问题

- **框选四连败（同根）**：`beginSelection` 先置 `selecting=true` 再调 `window()`，读到
  正要写入的旧值 → 窗口锁成 `[0,0]`；修法"先取窗口后置位"（实现注意保留在 CP12）。
- **切换接缝**：过渡 tick 立刻发异步请求 + 世界侧缓存垫一格（该整套垫值后来在第十轮④
  被删除）。
- **MEK 槽位下标（M4）**：javap 核实 `InventoryContainerSlot` 容器下标硬编码 0 →
  "内容指纹匹配"验证下标（CP9 之三）；歧义 → UNVERIFIED + 警告。

## 第八轮：修第七轮暴露的问题

- **缩放交互重做（P5）**：光标锚定缩放、`userZoomed`、滚轮 1.25×/格（进 CP14）。
- **警告去抖**：`notifyPlayer` 加 `force` 通道，重要警告不被去抖窗口吞掉。
- **时间刻度**：样本时间戳改用 `Level#getGameTime()`；世界没走 tick 就跳过采样；
  速率单位 n/s → n/t（进 CP13）。

## 第九轮：修第八轮暴露的问题

- **采样时钟（N13）**：客户端 `Level#getGameTime()` 是**错拍阶梯**（`ClientLevel#tickTime`
  本地 +1，`MinecraftServer#tickChildren` 每 20 服务端 tick 才同步一次绝对值）→ 权威世界
  tick 改由 `ServerClock` 在服务端线程读出（进 CP15）。
- 物理极限（记录在案）：客户端每秒最多观测 20 次；tick rate 200 时样本间隔 ~10t 不可再细，
  累计值与 n/t 速率恒正确。
- 实机 S1–S6 全过，打里程碑 `g2-waveform`。

## 第十轮：探针实例 + 多格监控（UI 交互大更新）

- **多探针**：全局单例拆成 `ProbeManager`（实例表 / 驱动）+ `ProbeInstance`（状态机 / 缓冲 /
  自己的 `WorldProbe`）；全局时钟抽到 `ServerClock`（归属规则进 CP16）。
- **多格聚合**：初版"混合物品线"，实机反馈后改为**按物品分别求和**（同种数量相加、不同物品
  各自一条线；`ChartData` 断点判定随之改 per-series）——结论进 CP16。
- **三页面结构** + 可视化选格（cellMode 两条铁律，CP17）+ 无 GUI 容器列表选格（`ProbeSlots`）
  + 准星入口（模拟右键 + `PENDING_OPEN` 40t 兜底）。
- **第一轮修正**：`list` 按钮失效（id 不匹配）、选格回填三入口合一、`getMenuProvider` 判据
  不可靠（改问 `interactionResult.consumesAction()`）、纵轴科学计数法、探针名加方块名。
- **第三次修正**：断线判据改为**只认显式标记**（`no_data` / `gap` / 相邻 tick 缺席）；删除
  `lastViews` / `seed` 垫值；读数改精确整数（`fmtExact`）；建立 VERIFY 归档制
  （`VERIFY-HISTORY.md`）。
- **第四次修正**（"不要跨过去，要真实值"）：**采样挂到世界 tick** —— `ServerTickEvent.Post`
  里同步读，时间戳同源；秒标签按真实 tick 速率换算；异步读取管线整体删除；两条采样路径以
  `sampleLock` 互斥（契约 C10）。
- **第五次修正**：配置上限（`bufferSamples` / `maxProbes` / `maxItemKinds`）+ **单 tick IO**
  （`Series#flow`）；"同 tick 一进一出不可观测"的边界写入 CP18。
- **第六次修正**：图例弹窗（数量 / IO）；读数瘦身（框选统计删 in/out，空格无 IO，仅开 IO 时
  十字线照报）。
- 复测：A–E 全过（Z1–Z18）；Y12 多人场景仍未测；渲染性能（大量线条 20 FPS）记为待优化。

## 第十一轮：G4 标记 + G5 导出回放 + 回放计算 + NBT 取数探针

- **①**：图例上下分半图标；G4 标记（`MarkerList` 独立于环形缓冲、右键加删、悬浮读数）；
  G5 导出（`archive` 包：`format` 版本号 + 逐字段默认值 = 向前兼容；「存档并停止」先写盘
  才删探针）+ 回放页（复用 `chartData`，根页下半 = 存档列表）。
- **②** 回放计算：`CurveMath` 纯类（极值 / 简单统计 / 变化总量 / 变更区间）+ 结果框；
  "断档不计、缺口单报"与"IO 只算严格段内"两条诚实性口径。
- **③** NBT 取数探针：`data get block` 实时抓取（命令路 + 抓回执不刷屏）+ 虚拟容器选值 /
  名称 + `Snbt` 纯解析 + 逐 server tick 采样。
- **④** 直读 NBT（单人免权限：`server.execute`；命令降级为多人 fallback）；修「开始采样」
  点不动（命中分支必须限定自己的矩形——一条通用 UI 纪律）。

## 第十二轮：世界生命周期 + DATA 页三段式 + 悬浮高亮联动

- **探针生命周期 = 世界**：`LoggingIn` → `clearAll`（C20-8）。
- DATA 页：确认添加（多目标 COW 去重；目标列表锁定只能删）、与物品槽位共存（两条采样守卫
  分开）、同名目标合并（`combineAll` 保留 0 与负数）、NBT 频繁变化不回顶、目标行随窗高、
  原文悬浮高亮（偏移映射）、选区只列启用线 + 英文漏翻补齐。

## 第十三轮：渲染路径优化（多线掉帧）

- 基线：多线 20 FPS。根因：每条线每帧全窗口重画（满数组清零 / 全宽空转 / 逐列 fill ×2）。
- 修法（像素等价）：代际戳、只扫有数据的列、**游程合并**（`RectRuns` 两条合并规则）；
  列聚合搬进纯类 `WaveColumns`（可离线压测）。
- 结果：台阶 −99.3%、混合 −63.8%（`BenchRender`）；噪声型数据合并下限 = 1 矩形/列（已接受）。

## 第十四轮：B5 根因（已回退）+ 界面细节

- "卸载后仍写旧值"：曾改用 `ServerLevel#shouldTickBlocksAt` 严格判据 → **用户判定副作用
  不可接受，已回退**（恢复 `isLoaded`；两条保留事实进 CP22）。
- 序号警告收敛（只在"容器下标重复"时提示——但当时判据查了 `menuSlotIndex`，从未触发；
  第十八轮①修正）；图表信息行改样本条数 / 上限；存档重命名（只写 `name.txt`）+ 列表三列
  对齐；悬浮详情通用化（`+N` / `data xN` / 槽列表，长列表框内滚轮）。

## 第十五轮：读数一致性（按身份读已回退）+ 悬浮覆盖 + 漏翻

- "界面内 2、界面外 8"：让菜单路径按容器下标反查的"统一"尝试**已回退** —— 实测 2 才是真值，
  `getContainerSlot()` 对模组机器不可信（进 CP23）。
- 悬浮详情覆盖信息区第一行与根页探针行；7 个漏翻语言键补齐（`LogiScopeConfig` 的注释仍是
  已知限制，不走语言文件）。

## 第十六轮①–④：NBT 槽位锚点探针

- **识别**：`NbtSlotLayout` 认"元素自带 slot 号"的复合列表（形状识别不靠模组名，C24-1）。
- **点格 → slot 号**：`SlotAlign` "顺序 + 内容"对齐，唯一解才采用（C24-2）。
- **② 修订**：匹配只按物品 id（数量会变，按数量匹配会漏输出格）；按格各建锚点（同名采样时
  相加）；（当时的）追加语义在 ④ 被用户推翻为**覆写**。
- **③**：按格区分只到"目标表"为止（显示带 slot，采样名仍是物品 id）；NBT 追踪改为**显式
  开关**（C24-7）。
- **④ 根因坐实**：`InventoryContainerSlot` 容器下标硬编码 0 → "点 2 原料 + 2 输出"塌成
  "1 原料 + 全部输出"；`SlotAlign.resolve` 四条判据（顺序对齐 → 容器下标（带门槛）→ id 唯一
  命中 → 统一位移外推），全不成立就不选；「完成」改覆写 + 进页回填（C24-2b / C24-8）。
  实测 N1–N14 + R1–R4 全过（基线 `952b8fb`）。

## 第十六轮⑤：代码审查 8 条 P1 全修

- 八条：IO 统计口径（`breakBefore` 双消费者）、越界留空（非有限 → `no_data`）、暂停水位
  `max(物品, 数据)`、锁内重取目标、换设备 = `no_data`（`beType`）、跨世界清理
  （`ContainerAnchor.clearActive` / `ProbeSlots.reset`）、焦点跟页面走、存档头部流式读。
- 审查自身的两处纠正：`Screen#resize` 不重跑 `init()`（"反复扫盘"推断不成立）；"列表不在"
  不能当"读不到"（须用方块实体类型比对）。
- 旧断言变红 = 覆盖到 bug 的证据（4 条按新语义改正）。总结进 **CP25 六条硬边界** + C24-10。
- 同日后补（B1 复测）：2^63 追不出图 = 预期行为（截断假线已消灭）；"没线"文案细分
  （有线被关 / 窗口无样本 / 一条线都没有），并给超范围值加当场提醒。

## 第十六轮⑥⑦：提示排版、自适应数值、QoL

- **⑥**：面板提示定宽折行（逐字符断行 + 按文本缓存）；`analysis/Val` 自适应数值
  （long 精确 / double；四个坑进 CP26）。
- **⑦**：QoL 8 项（打开存储文件夹 / NBT 黄 / 按钮排序与配色 / 顶栏光效 / 右键图例 = 分析
  等）+ 三条修订 —— 都是"渲染链顺序"问题（accent 传 0 丢失、光效被后画的标题栏盖住、
  构造时求值滞后一拍）。
- **⑦ 续**：「Done」黄、「Stop & archive」淡紫（语义色，渲染时实时求值）。

## 第十六轮⑧⑨：AE 网络支持

- 检测靠菜单家族（`MEStorageMenu`，反射零编译依赖）；目标身份 = 物品 registry id
  （`valuePath = "ae:network"`，**按 literalName 去重**）；读值链遍历 6 面找节点
  （CableBus 会按面过滤；不实现 `IActionHost`）；null = 读不到 / 0 = 在线没有（CP27）。
- **⑧ 修订**：AE 检测从未执行的根因 = `createProbe` 的 seed 预标记短路 → 检测挪进
  `enterPicker` 每次进入都判；`isAeStorageMenu` 去掉 `Class.forName` 假设（沿父类链比对）。
- **⑨**：AE 说明改悬浮（省一行）；MEK 自动开 NBT 追踪（识别仍靠形状）；无容器 BE 提示；
  **MEK 加压管道 F9 修复**：探测资格（有 BE 即可）与"模拟右键资格"（`isContainerLike`）
  拆开 —— 防"对床按 F9 真的触发睡觉"。

## 第十七轮①：上升 / 下降事件

- `CurveMath` 增 `changeRise/FallEvents` 与 `flowRise/FallEvents`（按 Δ 符号过滤的单向视图；
  断档不计；周期 = 相邻两次**同向**事件的间隔）；不变量：`changeEvents.count == rise + fall`。
- TestChart 411 → **443**；版本 0.1.0 → **0.1.1**。
- **发布线约定**：tag = `<加载器>-<MC版本>-<模组版本>`（如 `neoforge-1.21.1-0.1.1`），
  一版一 tag 一 GitHub Release。
