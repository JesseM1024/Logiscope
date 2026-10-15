# LogiScope · 进度

> 回答三个问题：**现在到哪了 / 下一步做什么 / 有什么坑**。
> 历轮记录（第一 ~ 十七轮）已归纳进 [`PROGRESS-HISTORY.md`](PROGRESS-HISTORY.md)；
> 目标见 [`GOALS.md`](GOALS.md)，现行原理见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，
> 验收步骤见 [`VERIFY.md`](VERIFY.md)。

## 一句话现状

- **代码 / 离线**：`build` 全过；TestChart **461 条**；`BenchRender`（台阶 99.3%・混合 63.8%）；
  `verify-jar.py` 全过；lang 中英各 **179** 键一致。
- **发布**：**0.1.1**（tag `neoforge-1.21.1-0.1.1`，含第十七轮① 上升 / 下降事件）；0.1.0 同线；
  发布约定见 [PROGRESS-HISTORY](PROGRESS-HISTORY.md) 第十七轮①。
- **实机**：第十六轮④ 及更早的轮次全部通过（里程碑 `g0-skeleton` / `g2-waveform`）；
  **十六轮⑤⑥⑦⑧⑨、十七轮①、十八轮①–⑤ 待复测** —— 清单在 [`VERIFY.md`](VERIFY.md)。

> 以上是 **1.21.1 主线（main）** 的现状。**1.20.1 分支（`1.20.1-neo`）** 的现状与移植记录见
> 紧随其后的「[1.20.1] 线」一节；两条线的 API 差异与限制统一记在
> [`PORT-1.20.1.md`](PORT-1.20.1.md)（本文与 ARCHITECTURE 都只描述 1.21.1 主线）。

---

## [1.20.1] 线：现状与移植记录

**现状（2026-10-15）**：功能已与 main **对齐到第十八轮⑤** —— 第十七轮① + 第十八轮①–④ 经搬运到位，
**轮次 4 的 DATA 右键「按数值读取」已回流 main（两步式定稿 `3ddb77c` + 表头截断 `d2dc8c5`）**；
F9 判据维持第二代（收紧曾被实机否决、已回滚；机械手误交互保留不修 —— 见「轮次 4」）。dev 启动
问题已定位并处理（AE2 的 SRG-mixin 缺陷 → `mods-disabled/`，外部实例渠道打通并完成兼容性审计，
见「轮次 2」）；版本号提至 `0.1.1-1.20.1-alpha` 并装配 NeoForge 测试实例（见「轮次 3」）；
**首次实机验收进行中**（dev 清单 = 本分支 [`VERIFY.md`](VERIFY.md)，含轮次 4 的 C-4；AE / 精妙
存储项在 `1.20.1-NeoForge` 实例验证）。

| 项 | 值 |
|---|---|
| 构建 | `gradlew.bat build --offline` 通过（代理关闭时；代理在跑可直接联网构建）；产物 `build/libs/logiscope-0.1.1-1.20.1-alpha.jar`（reobf） |
| 离线 | TestChart **461 条**全过、BenchRender（台阶 99.3%・混合 63.8%）、verify-jar 全过、lang 中英 **179** 键 |
| dev 实例 | `1_20_1_neo/`（与主线 `run/` 隔离）；mods = MEK `10.4.16.80`；AE2 `15.3.3` / Create `6.0.8` 在 `mods-disabled/` |
| 外部实例（主） | `1.20.1-NeoForge`（HMCL / NeoForge 47.1.105）：AE2 `15.3.6` + MEK `10.4.16.80` + 精妙存储 `1.5.5.2182` / Core `1.5.10.2402` + Create `6.0.8` + pipez `1.2.5`；已装入 `0.1.1-1.20.1-alpha` |
| 外部实例（备用） | GregTech Leisure（Forge 47.3.7）：AE2 `15.2.13` + 精妙存储 + GTCEu；装 `0.1.0-1.20.1`，常量池审计 0 缺失 |
| 未做 | 实机验收（重点 A 组采样保真 + C-4 右键「按数值读取」填入值格） |

### [1.20.1] 轮次 1：同步 main 第十七轮① + 第十八轮①–④ + 文档重构（2026-10-15）

**做法**：两分支历史**无共同祖先**（`merge-base` 为空），全部经 `git cherry-pick -x` 逐条搬运并
保留来源 sha；保留 1.20.1 侧 API 垫片（`ForgeConfigSpec` / `MinecraftForge.EVENT_BUS` +
`TickEvent.Phase.END` / `ForgeHooksClient` / `META-INF/mods.toml`），不采用 main 的 21.1 写法。

| 来源（main） | 内容 | 冲突与解法 |
|---|---|---|
| `adde1e3` | 第十七轮① 上升 / 下降事件 | 无（`ScopeScreen` 自动合并） |
| `b26b30a` | 第十八轮① 采样统一到服务端 | 仅 PROGRESS 文档 → 取 main 版 |
| `7b8635f` | 第十八轮② 删「界面已关」提示 | 无 |
| `e03cc5c` | 第十八轮③ 存档筛选框 | 无 |
| `9f75544` | 第十八轮④ DATA 页「完成」+ 刷新滑条 | **`LogiScopeConfig`**：字段保持 `ForgeConfigSpec.IntValue`（新增 `DATA_REFRESH_TICKS`）；VERIFY 的 V1 行改 1.20.1 版本号 |
| `c5cca54` `75e164e` `4f31c96` `9e9025b` | 文档体系重构（ARCHITECTURE 重写 / PROGRESS 瘦身 / VERIFY 合并 / 其余文档） | PROGRESS 无分支专有内容 → 取 main；VERIFY 取其重构版后**重建 1.20.1 侧**（另一提交）；README / PORT 保留 1.20.1 内容 |
| `4ec6915` | 全库注释去历史化（35 文件，纯注释） | 2 处：`WorldProbe`（本线是 **Forge** capability）、`ScopeScreen`（`ForgeHooksClient.pushGuiLayer`）—— 保留分支代码，只吸收措辞 |

**跨版本 API 核实（本次新增）**：从 `build/moddev/artifacts/forge-1.20.1-47.1.106-sources.jar` 读方法体确认
`ForgeConfigSpec#save()`（内部走 `FileConfig.save()` 落盘）与 `ConfigValue#set(T)` 在 1.20.1 都存在，
前置条件不满足时抛异常 ⇒ 滑条写回用 try/catch 包裹（与 main 同语义）。

**环境事项（详见 README 构建备忘 / PORT §六）**：
1. `createMinecraftArtifacts` 需联网 HEAD 校验 forge userdev 元数据；`maven.neoforged.net` 直连
   不可达 ⇒ IJ 同步卡住、编辑器 import 全红（非代码问题）。最终解法：Gradle 走代理
   （`~/.gradle/gradle.properties`）；备选：`--offline`。
2. Git Bash → cmd 传多条目 `-cp`（分号列表）会拆坏（报"程序包不存在"）；离线断言脚本改用
   `.bat` 包装（`dev-check/offline-check.bat`）。

### [1.20.1] 轮次 2：AE2 移出 dev + 整合包渠道打通 + 兼容性审计（2026-10-15）

**事件**：dev `runClient` 启动即崩 —— AE2 15.3.3 的 `PickColorMixin` 以 SRG 名（`@Shadow f_91074_`）
声明影子字段，其 refmap 无对应字段条目；dev 为 named 映射 ⇒ `InvalidMixinException`，7 秒内退出
（生产环境无此问题）。证据链：启动日志的重映射行 → `javap` 字段声明 → refmap 零命中 → tsrg
交叉核对（`f_91074_` = `Minecraft.player`）。已排除：换 AE2 版本无效（15.4.11 的 refmap 逐字
相同）；本模组与 MEK 均无 mixin；MDG legacy 插件无「dev 跑 SRG」开关。

**处理（A+B）**：AE2 移入 `mods-disabled/`（dev 不再加载）；AE 相关验证改在整合包实例
GregTech Leisure（Forge 47.3.7，含 AE2 15.2.13 + 精妙存储）进行，jar 已装入其 `mods/`。

**兼容性审计**：对 jar 全部常量池引用做解析审计 —— 265 个类引用逐条核对存在性，其中
`net.minecraft` / `net.minecraftforge` 属主的 180 条成员引用按继承链逐条解析；在 Forge 47.3.7
运行时（srg + 补丁 client + universal + FML 组件 + JDK `java.base.jmod`）**0 缺失、0
`net.neoforged` 引用** ⇒ 两条加载器线共用同一枚 jar。（审计脚本为一次性工具，未入库。）

### [1.20.1] 轮次 3：版本号 0.1.1-1.20.1-alpha + NeoForge 测试实例装配（2026-10-15）

**版本**：`gradle.properties` 的 `mod_version` 由 `0.1.0-1.20.1` 提至 **`0.1.1-1.20.1-alpha`**；
产物 `build/libs/logiscope-0.1.1-1.20.1-alpha.jar`（reobf；`--offline` 构建通过，系统代理关闭期间）。

**外部测试实例**（用户新建）：`1.20.1-NeoForge`（HMCL，NeoForge `1.20.1-47.1.105`，版本隔离）。
按「1.20.1 + 带 `neoforge` 标签的最新版」装配：AE2 `15.3.6`、Mekanism `10.4.16.80`、
Sophisticated Storage `1.20.1-1.5.5.2182`（前置 Core `1.20.1-1.5.10.2402`）、Create `mc1.20.1-6.0.8`、
pipez `1.20.1-1.2.5`；本 jar 已装入其 `mods/`。

**AE2 选版说明**：`15.3.6` 是 1.20.1 线最后一个带 `neoforge` 标签的版本（`15.4.0` 起仅标 `forge`）；
与 dev 侧同线（15.3.x），AE 反射代码路径的测试面一致。

**dev 侧遗留（暂停排查）**：MEK `10.4.16.80` 在 dev（NeoForge 47.1.106）启动时抛
`NoSuchMethodError: 'net.minecraft.Util.m_137492_(String, ResourceLocation)'`（该 SRG 名在本版映射
中对应 `Util.makeDescriptionId`）；根因未收敛，排查按用户要求暂停，转外部实例测试。
dev `runClient` 若需可用，可先将 MEK 移入 `mods-disabled/`（与 AE2 / Create 同处）。

---

### [1.20.1] 轮次 4：DATA 页右键「按数值读取」（两步式定稿）+ F9 判据收紧（后回滚）（2026-10-15）

**背景（用户实测）**：① 有的数值类型在 NBT 里被模组存成**字符串**，需要"强制按数值读取"
的口径；② 对准 Create 机械手按 F9 会**先发生一次真实交互**（手取放了物品），光标移开后
面板才出现 —— 1.21.1 线无此问题。

**① DATA 页右键「按数值读取」**（初版 `a6e701b` → 一步式 `9d8a02e` → **实机再反馈，
定稿两步式 `edd8f60`**；表头截断 `1c67ca1`）：
右键值条目 = 把该路径**按「按数值读取」口径填入值格**（不直接加追踪），可先设名称再
「确认添加」；值格右端点**黄色小方格**（`COLOR_FORCED`）标这个口径，手改路径 / 左键取值
即清掉。读取走 `DataProbe.asValForced`（数值标签照常，字符串再试 `Val.parseNumericText`：
整数优先 long、纯整数超 long 拒绝近似）；不可转换 → `no_data`（宁缺毋假）。同路径设置变了
（口径 / 名称）再确认 = **替换**（`ProbeInstance.replaceDataTarget`，精确匹配、不碰锚点 /
AE 目标）；非标量条目右键明确提示，不填。**已回流 main（`3ddb77c` / `d2dc8c5`）。**

**② F9 判据收紧（第三代）**（提交 `6ff713b` → **实机反馈否决，已回滚 `83d62d8`**）：
设计背景：1.20.1 的 `use()` 把方块交互与物品交互合并，"会不会消费右键"无法区分
"有界面的机器"与"会动作的设备"。收紧为"只认标准界面通道（`hasMenuChannel`）"后，
**MEK 工厂界面无法再用 F9 打开**（实测）—— 用户不接受这个代价。整体回滚到
"会消费右键 → 模拟右键"的第二代判据；**机械手误交互保留不修**（用户明确）。

**实机反馈与两次修订（同日）**：
- **第一轮**：DATA 右键"无法把 '100' / '39900' 这样的值作为数据追踪" —— 根因：`mouseClicked`
  在 `button != 0` 时直接 `return true`（右键早退），而初版把右键分支写在了**只接左键**的
  `handleDataClick` 里 = **死代码**（离线断言测不到 UI 事件流，未暴露）→ 右键派发移到早退
  之前 + 一步式。
- **第二轮**：一步式把"追哪个数"和"叫什么名"绑死 → **定稿两步式**（填入值格 + 黄标 +
  确认替换，见①）；另修追踪目标表头在窄窗口溢出（套 `cut` 省略号截断）。
- `hasMenuChannel`（第三代判据）随 revert 一并移除；`wouldConsumeClick` 恢复主判据。

**离线**：TestChart **461 条**全过（新增 t40 断言 18 条）、BenchRender（台阶 99.3%・
混合 63.8%）、verify-jar 全过、lang 中英 **179** 键；dev-check classpath 补
`guava-31.1-jre`（t40 的 `ListTag` 构造依赖）。

**实机验收项**：VERIFY.md 的 **C-4**（右键填入值格 / 黄标 / 替换 / 表头截断，P1–P9；含用
`data modify` 造字符串键的方法）。

---

## 第十八轮①：物品探针采样统一到服务端（菜单降为兜底，2026-10-15）

**需求**（用户原话归纳）：把"打开 GUI 读菜单 / 关闭 GUI 读服务端"两种采样逻辑统一成
**直接读服务端世界数据**（数据保真）；保留服务端读取的检测逻辑，**读不到时**
（例如多人）才用"打开 GUI 的读取方法"。MEK 等 NBT 路径不动；FOLLOWING 的既有行为不改。

| 件 | 内容 |
|---|---|
| `collect/ProbeInstance#serverSideSampling` | 条件从 `state != RECORDING \|\| VERIFIED` 改为 **有坐标 && 单人 && 映射 != UNAVAILABLE** —— 界面开着也让位服务端；UNAVAILABLE（挂探头时验证出既非 Container 也无 ItemHandler）保留给菜单兜底 |
| `collect/ProbeInstance#tickRecording` | `serverSideSampling()` 检查**提前到"换设备检测"之前**：服务端采样与界面无关，"RECORDING 下切到别的容器就 pauseAuto"是菜单路才需要的判定（单人下不再打断） |
| `collect/ProbeInstance#sampleOnServerTick` | `itemsReady` 去掉 `now == FOLLOWING \|\| VERIFIED`，改为 **有坐标 && != UNAVAILABLE** —— RECORDING 与 FOLLOWING 走同一条路：验证过的用验证下标、没验证的按既有回退 |
| `collect/ProbeInstance`（警告） | `warnIfIndexUnverified` 从 `tickFollowing` 挪到 `tick()`（RECORDING 也要提示），判据从 `menuSlotIndex` 修正为 **`containerSlotIndex` 重复**（复用 `SlotAlign.containerIndexReliable` 的不变式）—— 旧判据查的是菜单位置（天然唯一）⇒ 该警告**从未按设计弹过**；javap 实锤 MEK 的 `getContainerSlot()` 恒 0 |
| lang | `msg.index_unverified` 去掉"关界面后"的过时限定（中英一致）；README 采样描述同步 |

**为什么旧警告判据是错的**（写下来免得下次又选错字段）：第十四轮加"只在序号有重复时提示"
时注释写的是"MEK 那类全是 #0"，但"全是 #0"的是 **`Slot#getContainerSlot()`（容器下标）**；
菜单位置（`menuSlotIndex`）天然唯一。javap 复核 `InventoryContainerSlot` 构造 =
`super(emptyInventory, 0, x, y)` → 容器下标恒 0。修正后各机器自洽：
原版箱子 / `SlotItemHandler` 机器不弹（回退恰好正确）、MEK 弹（回退会把两格读成同一格）。

**行为矩阵（物品探针）**：

| 局面 | 采样来源 |
|---|---|
| 单人 + 有坐标 + 非 UNAVAILABLE（**界面开/关都一样**） | **服务端 tick**（每世界 tick 一条真值） |
| 服务端不可用 + 界面开着（多人 / 无坐标 / UNAVAILABLE） | 菜单（回退，按菜单位置读） |
| 服务端不可用 + 界面关着 | no_data |

**不改的**：MEK / NBT 锚点采样（本来就是服务端读，CP24）；FOLLOWING 的既有行为；
数据探针与 AE 目标；`verifyIndex` 检测体系本身（它继续决定"用验证下标还是回退"）。

离线：build / TestChart 443 条 / BenchRender（台阶 99.3%・混合 63.8%）/ verify-jar 全过。
实机复测见 `VERIFY.md` 第十八轮①。


## 第十八轮②：删除"界面已关"提示（2026-10-15）

**需求**（用户原话）："现在就不需要关闭原版类容器的提示……了，删除这个逻辑。"
（=`LogiScope：界面已关，转为坐标跟踪` / EN `GUI closed, tracking by position`）

采样统一服务端后（第十八轮①），界面开/关**不对读数路径产生任何切换** ——
这条提示在描述一件没发生的事（它是菜单时代的残余：旧口径里"关界面 = 从菜单读切成坐标读"）。

| 件 | 内容 |
|---|---|
| `collect/ProbeInstance#enterFollowing` | 去掉 `boolean notify` 参数与 `msg.following` 调用（7 个调用点全部转无参）；javadoc 写明删除原因 |
| lang | 删除 `logiscope.msg.following`（中英各 175 → **174** 键，仍一致） |
| 注释 | `warnIfIndexUnverified` / `notifyPlayer` 里对"转为坐标跟踪"去抖场景的引用改为历史表述（force 语义不变） |

**保留的**：`pauseAuto` 的"数据源丢失，已暂停"（`msg.paused_auto`）—— 那是"真的没在采"的
提醒，与界面无关；`warnIfIndexUnverified` 的强制警告（force 通道照旧）。

离线：build / TestChart 443 条 / verify-jar 全过。


## 第十八轮③：根页存档列表带筛选框（2026-10-15）

**需求**（用户原话）："主界面的 Archives 那里改成文本框，默认状态下显示原本的
Archives analysis data，输入文字时可用于筛选已存档数据名称。"

标题行左侧的「存档 · 读取分析数据」改为**输入框**：空 = 显示原标题文字（占位符）；
点入后可输入，存档列表**实时筛选**。

| 件 | 内容 |
|---|---|
| `ui/ScopeScreen#renderArchiveFilter` | 标题位置画输入框（与名称列同宽）：空时以占位色显示 `logiscope.info.archives`（= 原标题），聚焦亮环 + 闪烁光标（风格同重命名框），悬停变亮 |
| `ui/ScopeScreen#filteredArchives` | 筛选函数：大小写不敏感，匹配**名称**（displayName，空则退到文件夹名）**或文件夹名**；空串 = 全量原样返回 |
| `ui/ScopeScreen`（命中/滚动） | 点击命中与滚轮翻页都改用筛选后的列表；"n / m" 计数 = 筛选后数量 |
| `ui/ScopeScreen`（输入通道） | `charTyped` / `keyPressed` 加第三个焦点通道（与重命名框、数据框并列）：Backspace 删字、Enter 确认（**保留筛选**、收起）、**Esc 取消（清空筛选并收起）**、Ctrl+V 粘贴（限长 48）；离开根页自动清焦点；左键点到框外先失焦（"焦点跟着点击走"） |
| 空态 | 区分"真没有存档"（原 `no_archives` 提示）与"筛掉了"（新 `logiscope.hint.arch_no_match`）—— 后者别让用户以为数据丢了 |
| lang | 新增 `arch_no_match`（中英 174 → **175** 键，仍一致） |

**一条值得归档的纪律**：筛选页的"渲染 / 命中 / 滚动"必须**共用同一个列表函数** ——
两套列表（显示的用筛选后、点击的用全量）就是"点 A 行打开 B 存档"的经典错位源；
共用一个函数 = 结构上不可能错位（同 CP9 之三"顺序 + 内容对齐"的思路）。

**行为细节（写清免得被当 bug）**：筛选变化（打字 / 删除 / 粘贴 / 清空）→ 回到列表顶部；
持久化 = 无（关界面重开回到全量）；Enter 保留筛选（先筛出来再逐个点开的用法），
Esc 才是清空。

离线：build / TestChart 443 条 / verify-jar 全过（lang 175 键一致）。
实机复测见 `VERIFY.md` 第十八轮③（F1–F8）。


## 第十八轮④：DATA 页「完成」按钮 + 刷新速率滑条（2026-10-15）

**需求**（用户原话）："在选格的 probe data 页面加一个 done 按钮，与选格页的 done 是一样的作用，
这样对于仅追踪 NBT 数据的方块实体探针，不需要 back 到选格页再 done 了，操作更流畅。
将 probe data 页面的数据刷新速率通过一个滑条做在 status ok 后面，目前是默认 20t 一次，
滑条提供 1~40t 和不刷新的档位选择，不需要每个探针隔离，整个游戏运行实例只有一个刷新速率就行，
同样这个速率支持进 config 修改。"

| 件 | 内容 |
|---|---|
| `ui/ScopeScreen`（按钮） | DATA 页底部加「完成」：**复用选格页同一个 chip id `done`** → 同一条 `commitPicker` 路径（覆写语义、置灰条件 `选中格子 or 已攒目标`、黄色配色全部自动一致）；`targets_hint` 文案改为"点下方「完成」直接开始（不必回选格页）" |
| `ui/ScopeScreen`（滑条） | 「status ok」同行右侧：轨道 + 已选段 + 拖钮 + 档位文字（`20t` / `不刷新`）；41 档 = 1..40t + 最右"不刷新"；点击即跳档、按住拖动（`mouseDragged` 跟、`mouseReleased` 收尾，拖出面板也继续跟手），悬停/拖动提亮 |
| `ui/ScopeScreen`（节拍） | `tickDataPage` 读全局档位：`rate > 0` 才自动重发；改小 / 从"不刷新"切回来时 `min(next, now+rate)` 把过期排期拉回来（不等旧节拍走完）。进页面仍立刻抓一次（"不刷新"= 不**自动**重发，手动「刷新」照常） |
| `LogiScopeConfig` | 新增 `dataPage.dataRefreshTicks`（0~40，默认 20）：滑条改动走 `set + save` 写回 toml（本次生效 + 下次启动保持）；`set`/`save` 语义从 NeoForge sources jar 核实（set 直接改内存值；restartType=NONE ⇒ get() 立即反映） |
| lang | 新增 `logiscope.data.rate_off`（不刷新 / off）；`targets_hint` 改口径（中英 175 → 176 键） |

**设计取舍（写下来省得再纠结）**：
- **Done 复用同一个 id 而不是新写一套**：两个页面各写一份提交逻辑必然漂移；
  共用 handler = "与选格页的 done 一样"不是"实现得像"，而是**字面上就是同一个**。
- **"不刷新"放最右档**：档位语义是"间隔"（越大越慢），最右 = 无穷大 = 不刷新；
  往左拖总在加速，不会误触停止。
- **单一真值源**：滑条与 config 不是两份状态 —— 滑条直接读写 config（`set()` 立即生效、
  `save()` 落盘），`tickDataPage` 每 tick 读。没有"UI 值 vs 文件值谁优先"的歧义。
- **全局一份、不随探针隔离**（用户明确要求）：它是"我多久看一眼数据"的形态偏好，
  不是每台设备各自的属性。

离线：build / TestChart 443 条 / BenchRender（台阶 99.3%・混合 63.8%）/ verify-jar 全过
（lang 176 键一致）。实机复测见 `VERIFY.md` 第十八轮④（N1–N9）。

## 未完成 / 待优化项

| 项 | 说明 | 出处 |
|---|---|---|
| 数据探针的 NBT 读取开销 | 现在每 tick 构建整份 NBT 再取一个值；可优化为"只取子标签 / 路径前缀预判"，接口不变（`DataProbe#readNumber` 一处） | CP20-6 |
| DATA 页路径即时校验 | 路径写错目前只表现为"曲线全 no-data"；拿到 NBT 树时可当场标红 | `NEXT-ROUND-DRAFT.md` E2 |
| 图例可滚动 / 可折叠 | 超过 3 行收成 `+N`；"一个槽位轮换十几种物品"的场景需要翻页 | ARCHITECTURE 待决问题 5 |
| 容器的键盘操作 | 面板打开时数字键 / `Q` / 中键无效（需要补 `hoveredSlot`） | 待决问题 3 |
| 采样降频 / `no_data` 密度 / 相对时间轴 | 三项设计题 | 待决问题 1 / 4 / 6 |
| 物品 NBT 维度 | 只记 itemId，不区分 NBT 不同的同名物品 | 待决问题 2 |
| 十六轮⑤ ~ 十八轮的实机复测 | 清单全部在 VERIFY.md；跑完按约定把通过项归档进 VERIFY-HISTORY | — |

## 已接受的限制（摘要）

完整表在 [`ARCHITECTURE.md`](ARCHITECTURE.md)「已知限制」一节；这里只列值得记住的：

| 项 | 说明 |
|---|---|
| 容器的**键盘**操作在面板打开时无效 | 数字键换物 / `Q` / 中键走 `hoveredSlot`，而背景层拿不到鼠标坐标。鼠标操作完全可用（CP8 之一 ~ 之四） |
| 用快捷键开界面的模组拿不到方块坐标 | vanilla 菜单不存坐标，只能靠右键事件推 → 关界面只能暂停 |
| 第三方搜索框需逐个加探针 | 目前只接了 JEI；第 3 层通用兜底能覆盖其余情况（代价：刚打完字要多按一次） |
| 图例超 3 行会收成 `+N` | 一个槽位轮换 7 种以上物品很罕见；把面板拖大即可 |
| 纵轴范围偶尔跳变 | 自动缩放的固有代价，已吸附整数刻度降低频率 |
| `LogiScopeConfig` 的注释未本地化 | NeoForge 的 `.comment()` 是静态字符串，不走语言文件 |

## 那些"看起来像 bug 但其实是设计"的现象

| 现象 | 为什么是对的 |
|---|---|
| 关界面后走远，`no-data` 开始增长 | 区块卸载了确实读不到。**该断的地方要断**（CP9） |
| 波形在某处空白一段 | 那是 `no_data` 或暂停 —— 留白比画成 0 诚实 |
| 换物品的那一瞬间线是断的 | 从 64 个铁变成 1 个铜，差值 −63 没有物理意义 |
| 同一种物品也会分成好几段 | 中间夹了 `no_data` 或暂停，连线等于编造数据 |
| 面板盖住容器时容器点不动 | 只有**面板之外**才转发给容器（CP8 之一） |
| 同 tick 一进一出时 IO 线显示 0 | 两个方向互相抵消，信息没有留在任何一次观测里（CP18） |

## 怎么继续（接手者看这里）

```bat
cd /d D:\dev\logiscope

:: 1) 编译 + 打包（JDK 21 已由 gradle.properties 的 org.gradle.java.home 锁定）
gradlew.bat build

:: 2) 离线断言（不用开游戏）
javac -encoding UTF-8 -cp "build/classes/java/main;dev-check/lib/gson-2.10.1.jar;build/moddev/artifacts/neoforge-21.1.209-merged.jar" -d build/dev-check dev-check/TestChart.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main;dev-check/lib/gson-2.10.1.jar;build/moddev/artifacts/neoforge-21.1.209-merged.jar" TestChart

:: 2b) 渲染路径离线压测
javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/BenchRender.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" BenchRender

:: 3) 产物验收（元数据 / 编码 / 占位符）
python verify-jar.py build/libs/logiscope-0.1.1-1.21.1.jar

:: 4) 进游戏（或用 build\moddev\runClient.cmd 脱离 Gradle 反复重启）
gradlew.bat runClient
```

改代码前**必读** [`ARCHITECTURE.md`](ARCHITECTURE.md)：先看第二节（接口与数据流程），
再看第三节（关键逻辑路径 CP1–CP27）中标为「高」风险的条目 —— 它们每一个都在实机上出过错。

---

