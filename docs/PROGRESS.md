# LogiScope · 进度

> 回答三个问题：**现在到哪了 / 下一步做什么 / 有什么坑**。
> 历轮记录（第一 ~ 十七轮）已归纳进 [`PROGRESS-HISTORY.md`](PROGRESS-HISTORY.md)；
> 目标见 [`GOALS.md`](GOALS.md)，现行原理见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，
> 验收步骤见 [`VERIFY.md`](VERIFY.md)。

## 一句话现状

- **代码 / 离线**：`build` 全过；TestChart **513 条**；`BenchRender`（台阶 99.3%・混合 63.8%）；
  `verify-jar.py` 全过；lang 中英各 **203** 键一致；`ProbeRsApi` = `RS_API_OK`。
- **发布**：**0.1.1**（tag `neoforge-1.21.1-0.1.1`，含第十七轮① 上升 / 下降事件）；**0.1.3 待发布**
  —— 版本号已提至 `0.1.3-1.21.1`（0.1.2 未单独发布，变更并入 0.1.3），
  变更见「第十八轮⑥」+「第十九轮①–③」；0.1.0 同线；发布约定见
  [PROGRESS-HISTORY](PROGRESS-HISTORY.md) 第十七轮①。
- **实机**：第十六轮④ 及更早的轮次全部通过（里程碑 `g0-skeleton` / `g2-waveform`）；
  **十六轮⑤⑥⑦⑧⑨、十七轮①、十八轮①–⑤ 待复测** —— 清单在 [`VERIFY.md`](VERIFY.md)。

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

## 第十八轮⑤：DATA 页右键「按数值读取」（自 1.20.1 线回流；实机两轮反馈后定稿两步式，2026-10-15）

**需求**（用户原话归纳）："有的数值类型在 NBT 中被存储为字符串"；在 probe data 页新增
**右键 = 强制把某个值按数值量读取**；原始值是字符串或无法转换为数值量的值 → 结果报 no data。
功能先在 `1.20.1-neo` 线落地（[1.20.1] 轮次 4），随后回流本分支。提交链：
初版两步式 `f07ebad`（1.20.1 侧 `a6e701b`）→ 死代码重写为一步式 `8198679`（`9d8a02e`）
→ **实机再反馈，定稿两步式（本提交 `3ddb77c`，`edd8f60`）**；表头截断 `d2dc8c5`（`1c67ca1`）。

| 件 | 内容 |
|---|---|
| `analysis/Val` | 新增 `parseNumericText`（纯逻辑）：trim → `Long.parseLong`（整数精确）→ 纯整数超 long **拒绝近似** → `Double.parseDouble`；NaN / ∞ / 任意文本 → null。与 `of(double)` 的两条诚实边界同一口径 |
| `collect/DataProbe` | `readNumber` 按 `DataTarget.forceNumeric` 分流；新增 `asValForced`：数值标签照 `asVal`，字符串再试文本解析；List / Compound → null = 读不到 |
| `bind/DataTarget` | record 新增第 9 个分量 `forceNumeric`（**不参与去重**）；工厂方法 / 普通构造器默认 false |
| `ui/ScopeScreen` | 值列表**右键**从"用作文名称"改为**按「按数值读取」口径填入值格**（`handleDataValueRightClick`：填路径 + 黄标 / 非标量提示；不直接加追踪）；右键派发在 `mouseClicked` 的 `button != 0` 早退**之前**；值格右端点黄标 `COLOR_FORCED`（`drawEditCell` 的 marker 参数：值 = 黄 / 名称跟 NBT = 品红）；手改路径 / 左键取值 → 清黄标；左键语义不变 |
| `collect/ProbeInstance` | 新增 `replaceDataTarget(old, now)`：按记录**精确相等**原子替换（同路径换口径 / 改名；不碰路径字符串偶然相同的 slot 锚点 / AE 目标）；「确认添加」在设置变化时走它，未变化才提示重复 |
| lang | 新增 `flash.data_force` / `flash.data_not_scalar` / `flash.data_updated`；`data.hint.pick` 改口径（中英 176 → **179** 键） |
| `dev-check` | t40 断言 18 条（443 → **461**）；classpath 补 `guava-32.1.2-jre`（t40 的 `ListTag` 构造依赖）；新增 `offline-check.bat` 一键脚本（与 1.20.1 线同款） |

**实机反馈与两次修订（同日）**：
- **第一轮**：1.20.1 侧实测"右键无法把 '100' / '39900' 这样的值作为数据追踪" ——
  根因：`mouseClicked` 在 `button != 0` 时直接 `return true`（右键早退），初版把右键分支
  写在了只接左键的 `handleDataClick` 里 = **死代码**（离线断言测不到 UI 事件流，未暴露）；
  先把右键派发移到早退之前 → 一步式。
- **第二轮**：一步式把"追哪个数"和"叫什么名"绑死了 → **定稿两步式**：右键 = 按数值读取
  口径**填入值格**（黄标标口径），可先设名称再「确认添加」；同路径设置变了（口径 / 名称）
  = **替换**（`replaceDataTarget`），设置没变才提示重复；非标量条目（列表 / 复合）右键
  明确提示，不填。
- **另**：追踪目标表头（"追踪目标 · n · 点下方「完成」…"）在窄窗口溢出面板 → 套
  `cut(…, L.w() - 2 * PAD)` 省略号截断（与同页页脚提示同款）。

**设计取舍**：
- **"强制"是路径的属性，不是全局开关**：黄标跟着值格里的这一次填写走（确认后锁进目标；
  换口径就重新右键填入再确认 = 替换）。
- **不可转换 → no-data，不垫 0**：与"读不到 vs 空格子"的既有口径一致；宁缺毋假。
- **左键语义不变**（数值 = 取值路径 / 文字 = 当名称）：右键是纯增量。
- **右键不直接加、要一步确认**：留出"先设名称"的位置（名称格与值格是两个设置）。

离线：build / TestChart 461 条 / BenchRender（台阶 99.3%・混合 63.8%）/ verify-jar 全过
（lang 179 键一致）。实机复测见 `VERIFY.md` 第十八轮⑤（C-4，P1–P9）。

## 第十八轮⑥：版本号 0.1.2（2026-10-17，待发布）

**版本**：`gradle.properties` 的 `mod_version` `0.1.1-1.21.1` → **`0.1.2-1.21.1`**；产物
`build/libs/logiscope-0.1.2-1.21.1.jar`；脚本 / 文档里的产物名同步（`dev-check/offline-check.bat`、
VERIFY / GOALS / README）。

**0.1.1 → 0.1.2 功能变化（相对远端 tag `neoforge-1.21.1-0.1.1`）**：
- **物品探针采样统一到服务端 tick**（第十八轮①）：读数与时间戳同源，消除漏观测 / 时间戳
  跳格造成的假断线；菜单读取降为兜底。
- **DATA 页「完成」+ 刷新速率滑条**（第十八轮④）：可直接在 DATA 页完成创建（不必回选格页）；
  NBT 刷新 1–40 tick 可调 / 关闭（与 config 同源）。
- **根页存档列表筛选框**（第十八轮③）：按名称过滤存档，含"无匹配"提示。
- **DATA 页右键「按数值读取」**（第十八轮⑤）：字符串存的数字（如 `"1000"` / `"39900"`）也能
  按数值采样 —— 右键把路径填入值格并打黄标，可先设名称再「确认添加」；不可转换 → no-data
  （不伪造 0）；同路径改口径 / 名称再确认 = 替换。
- **细节**：移除"界面已关"提示（第十八轮②）；追踪目标表头窄窗口下省略号截断。
- 其余为源码注释与文档整理（无功能变化）。

## 第十九轮①：选格页筛选框（2026-10-21）

**需求**（用户原话）："在物品选格页，新增一个搜索框，用于多容器格时的物品筛选，
筛选可以支持物品名称或容器序号，按显示的来就行，如 `#1 iron_ingot`。"

| 件 | 内容 |
|---|---|
| `analysis/PickerFilter`（新） | 纯逻辑匹配器：空白分词取**与**；`#N` = 序号**数值精确**匹配；其余词按子串（物品 id / 短名 / AE 显示名，大小写不敏感）。无空格的整串子串匹配**故意不做** —— 显示文本是 `#1  iron_ingot x32`（两空格），用户按需求敲单空格会匹配不上 |
| `ui/ScopeScreen#layout` | `Layout` 增加 `filterTop/filterH`：选格页在列表顶上让出一行给筛选框；**没有可筛清单时不占位**（那一行还给"先把设备界面打开"提示）。渲染与命中判定共用这份几何 |
| `ui/ScopeScreen#pickerVisible` | 筛后清单。**渲染 / 点击 / 滚动三处共用**（与存档筛选框同一条纪律：看到的行 = 点的行） |
| `ui/ScopeScreen` 输入 | `charTyped` / `keyPressed` / 粘贴：Enter 保留筛选收起焦点、Esc 清空并收起、Ctrl+V 粘贴；离开选格页自动清焦点（否则键盘会被一直吃掉，与重命名框同一个坑） |
| `ui/ScopeScreen` 页脚 | 筛选生效时多一段 `命中 / 全部 · 已筛选` —— 只给命中数会让人以为容器里就这几格 |
| `ContainerAccess.SlotView#displayName` | 新增"列表上画出来的名字"这一层（当前 = `shortName()`）；筛选框、渲染、AE 解码共用同一个口径 |
| lang | 新增 3 键（中英各 182）：`picker.filter_hint` / `picker.no_match` / `picker.filtered` |

**筛掉的不是数据**：筛选只影响**列表显示**，已勾选的格子、选择计数、`完成` 提交的内容
都不受筛选影响 —— 它是个查看工具，不是选择器。

离线：TestChart **478 条**（新增 t41 共 17 条，覆盖 `#N` 精确 / 多词取与 / 大小写 / 空查询 /
`#007` / 空行的 `（空）`）。实机见 `VERIFY.md` G 组。

## 第十九轮②：AE 非物品键的名称 / 数量 / 读值（2026-10-21）

**实机反馈（用户原话归纳）**：AE 网络里的流体在选格页被读成"通用物品堆栈 x1"，
监测时报 0；物品列表后面也全是 `x1`（但物品**追踪**本身是对的）。

**三个根因，各修一处**：

| 现象 | 根因 | 修法 |
|---|---|---|
| 流体显示成"通用物品堆栈"（`wrapped_generic_stack`） | AE 把**所有非物品键**在客户端包成同一个物品（`ae2:wrapped_generic_stack` + `GenericStack` 数据组件），而 `ContainerAccess` 只读了 `Item` 的 registry id —— 流体 / 化学品全部塌成同一个 id | `bind/AeKeys#unwrap` 用 `GenericStack.isWrapped/unwrapItemStack` 解包，身份改写成 `ae:` + `AEKey#toTagGeneric` 的 SNBT（带键类型 `#t`，任何附属模组都能往返）；显示名用 `AEKey#getDisplayName`（游戏内真名，如"水"） |
| 列表里全是 `x1` | AE 终端格子的 `ItemStack.getCount()` 恒为 1（那只是个图标），真实存量在 `RepoSlot#getStoredAmount()` | `ContainerAccess#viewOfAe` 对 AE 菜单家族单独组装：数量 = `getStoredAmount()`（long） |
| 监测报 0 | 目标身份是上述那个包装物品的 id，网络里当然没有"这种物品" | `AeNetworkReader` 按身份分两路取键：物品走 `AEItemKey.of`（老路不变），`ae:` 串走 `AEKey#fromTagGeneric`；`counter.get(key)` 对流体 / 化学品同样成立 |

**顺带修的三件**：

| 件 | 内容 |
|---|---|
| 空/非空判据 | AE 列表里改成"**条目在不在**"（`RepoSlot#hasItem()`），不再是"数量是不是 0" —— 可合成但没存货的条目存量就是 0，监测它是"现在 0 个"这个真值，不该被当成空格子 |
| 读值性能 | 反射链改为**只解析一次**（以前每次读值都 `Class.forName` + `getMethod` 一遍）；`getAvailableStacks()` 按 **(MEStorage, gameTime)** 同 tick 复用（弱引用，避免静态字段钉住一个世界的对象图） |
| 显示口径 | 图例 / 数据目标列表 / 选格页统一走 `AeKeyText.label`：`ae:` 串显示键自己的 id 短名（`water`），普通物品仍是短名（`iron_ingot`，与 0.1.2 之前一致） |
| 删目标误伤 | `ProbeInstance#removeDataTarget` 从"按 valuePath 删"改成"**按目标自身删**"。所有 AE 目标的 `valuePath` 都是标记 `"ae:network"`，按路径删会在点一行 × 时把**全部 AE 目标**一起删掉（槽位锚点早就踩过同一个坑，注释里写着却只绕开了替换那条路） |

**为什么不把物品键也换成 SNBT 身份**：0.1.2 之前建的 AE 目标是裸 registry id，
换格式会让它们在选格页上"勾不中"。物品键保持裸 id 是**兼容**，不是省事 ——
代价是"同 id 不同数据组件"的物品仍然合并（写进已知限制）。

**"AE 有没有直接取网络量的方法"**：有。`IGrid#getStorageService()` 之后两种读法 ——
`IStorageService#getCachedInventory()`（每 tick 最多刷新一次，AE 终端自己列表就用它，
最省）与 `getInventory().getAvailableStacks()`（现算，遍历全部存储）。
本项目取后者并**同 tick 缓存**：采样点就在服务端 tick 上，宁可多走一遍也不要
"值比时间戳晚一格"（与 CP4/CP15 同一条纪律）。

**离线实证**：新增 `dev-check/ProbeAeApi.java` —— 把代码里用到的 **20 条反射签名**
（类 / 方法 / 参数 / 返回类型 / 静态性）在 AE2 19.2.17 + NeoForge 21.1.209 上逐个
`getMethod` 一遍，输出 `AE_API_OK`。它**不进** `offline-check.bat`（依赖 run/mods 里的
第三方 jar），跑法写在文件头。往返（`toTagGeneric` → SNBT → `fromTagGeneric`）
需要 AE2 的键类型注册表，离线起不来（NeoForge 引导要求 FML 环境）→ **实机待验**。

离线：TestChart **489 条**（新增 t42 共 11 条：AE 键身份 / 显示名 / 类型 / 坏文本兜底）。
实机见 `VERIFY.md` H 组。

## 第十九轮③：选格页「忽略空槽」+ 版本号 0.1.3（2026-10-21）

**需求**（用户原话）："在这个选格页的搜索框后面加一个按钮，ignore empty，默认不开，
开了可以筛除空的格子，配合筛选框进行筛选，将版本号改为 0.1.3"。

| 件 | 内容 |
|---|---|
| `ui/ScopeScreen#pickerIgnoreEmpty` | 新增开关，**静态字段**（看列表的偏好，跨开关面板保持；进程启动默认 false）；与筛选框**叠加**：先剔空行，再按文字筛 |
| `pickerIgnoreRect` / `pickerFilterRect` | 按钮钉在筛选行右端（"搜索框后面"），搜索框宽度按按钮实际字宽让位；两者仍是同一份几何（渲染 / 命中判定共用） |
| 渲染 | 复用 `renderChip` 的观感；开着时文字转绿（`accentFor` 里 `picker_ignore_empty`，与 `cell_mode` 同一语汇） |
| 空结果提示 | 分两句：文字筛不到 → "没有匹配的格子·按 Esc 清空筛选"；开着忽略空槽且容器全空 → "这个容器里没有非空格子·关掉「忽略空槽」看看" |
| lang | 新增 2 键（中英各 **184**）：`picker.ignore_empty` / `picker.all_empty` |

**版本号**：`mod_version` `0.1.2-1.21.1` → **`0.1.3-1.21.1`**；产物名同步到
`dev-check/offline-check.bat`、GOALS / VERIFY / README。0.1.2 **没有单独发布**过，
它的变更（第十八轮⑥ + 第十九轮①–③）一并进 0.1.3。

**筛选只影响显示**这条纪律不变：忽略空槽同样不动已勾选的格子与「完成」提交的内容。

离线：TestChart 489 条（本轮的开关是纯 UI 状态，没有可离线断言的逻辑）；lang 中英各 184。
实机见 `VERIFY.md` G10–G13。

## 第十九轮④：反射边界统一（只反射 AE，不反射 MC，2026-10-21）

**由来**：1.20.1 线实机报"流体全显示成 `wrapped_generic_stack`、点一行选中全部"。
根因是那边的 `AeKeys.init()` 里反射了原版方法 `TagParser#parseTag`（SRG 下叫 `m_129359_`）⇒
整条链 `NoSuchMethodException`。1.21.1 生产用 Mojang 名，所以这条线一直是对的 —— 但**写法本身是脆的**。

**改动**（只动 `bind/AeKeys`）：`TagParser.parseTag(...)` 与 `Component#getString()` 从反射
改为**直接调**（import 原版类）；`init()` 只保留 AE 自己的方法（`toTagGeneric` /
`fromTagGeneric` / `getDisplayName` / `isWrapped` / `unwrapItemStack` / `what` /
`getStoredAmount` / `getEntry`）。行为完全一致（1.21.1 产物调用点仍是 `TagParser.parseTag`），
只是不再依赖"运行时方法名恰好没变"。

**规则**（写进 ARCHITECTURE C27-10）：判据是这个类**是谁的** —— AE 的反射，原版的直接调。
`dev-check/ProbeAeApi` 同步只列 AE 的签名；`VerifyError`（离线夹具缺 Forge 运行时补丁）记 SKIP 不算失败。

## 第十九轮⑤：回放页「打开存档文件夹」（2026-10-21）

**需求**（用户原话）："在查看存档的数据时，也加一个打开文件夹的按钮……打开后全是日期标注的
文件夹名称，不好搜索。在查看存档的数据页面打开文件夹时，就直接打开到 csv、json 那里"。

| 件 | 内容 |
|---|---|
| `archive/Archives#dirOf(dirName)` | 存档目录 = `rootDir()/dirName`。目录布局只在 `Archives` 里定义一次，界面不拼路径 |
| `ui/ScopeScreen` REPLAY 按钮行 | 在「重命名」与「回列表」之间加「打开存档文件夹」；`replayArchive == null` 或 `replayDir` 为空时置灰（没有回放就没有目录可开） |
| `openArchiveFolder()` | `Files.createDirectories(dirOf(replayDir))` 后 `Util.getPlatform().openFile(...)`；失败只 flash，不打断 |
| lang | 新增 1 键（中英各 **185**）：`btn.open_archive_folder` |

根页的「打开文件夹」（落点 = 根目录）**保留不动** —— 那是"我要看所有存档"的入口，
回放页这个是"我要看这一份"。两者不是替代关系。

离线：build 通过；TestChart 489 条全过（本改动是 UI 行为，没有可离线断言的逻辑）。
实机见 `VERIFY.md` I 组。

## 第十九轮⑥：悬浮详情框夹进窗口 + 长文本折行（2026-10-22，自 1.12.2 线同步）

**来源**：1.12.2 线实机发现 —— `Pick cells` 的说明有 150+ 字，悬浮框一路顶到屏幕右边被切掉。
根因是 `renderTip` 只按**面板矩形**夹位置，超宽的行用 `cut()` **截断**。

| 旧 | 新 |
|---|---|
| 只按面板矩形夹 | 按**游戏窗口**（`width/height`）夹：右侧/下方放不下就翻到光标另一侧，再不行贴边 ⇒ 整框必可见 |
| 超宽截断 | 先 `wrapText()` 按像素宽度折行（英文空格优先、中文按字符），再算高度 |
| 行数上限写死 12 | 由**可用高度**决定（窗口矮就少显示几行，配合滚动） |
| 正文最宽 = 自然宽度 | `min(窗口宽 − 22, 420)` —— 长说明折成 3–5 行，短提示不受影响 |

★ 折行必须在**算尺寸之前**做：行数变了，高度、滚动上限、`x / y` 页码全得跟着变；
页脚那行（`3 / 7 scroll`）的宽度也要参与框宽计算，否则会画到框外。

离线：build 通过；TestChart 489 条全过（纯 UI 行为，无可断言逻辑）。

## 第二十轮①：名称显示开关（选格页 / 图表页 / 计算页三处同源，2026-10-24）

**需求**（用户原话归纳）：AE 监测里，选格页对通用包裹堆栈（流体 / 化学品）显示**实际名**
（水、熔岩、氢），物品显示注册名（`iron_ingot`）；而图表图例对同一批键只显示注册名
（`water`）—— 同一份数据在两个页面名字对不上。要一个**可记忆、与 config 同步**的按钮，
在选格页 / 图表页 / 计算页各放一个，统一决定"注册名还是实际名"，
**不影响 probe data 里的名字**。

| 件 | 内容 |
|---|---|
| `bind/NameText`（新） | 所有显示名的唯一出口：`of(id[, hint])`。`REGISTRY` = `AeKeyText.label`（去命名空间的 id，AE 键取 SNBT 里的 `id`）；`DISPLAY` = 物品走 `Item#getDescription()`、AE 键走 `AeKeys.decodeKey` + `AEKey#getDisplayName()`；解析不出来**退回注册名，不编名字** |
| `LogiScopeConfig` | 新增 `display.nameDisplay`（默认 **true = 实际名**）+ `nameDisplay()` / `setNameDisplay()`（写入即 `SPEC.save()` 落盘） |
| `ui/ChartRenderer#label` | 改为 `NameText.of(itemId)` —— 图例、实时读数、计算页标题与列表、悬浮详情**全部跟着切** |
| `ui/ScopeScreen` | 选格页行文字与筛选（`NameText.of(v.itemId(), v.label())`，AE 行复用已拿到的真名）；三个页面各一个 `name_style` 按钮（文案 `名称: 实际名 / 注册名`），点一下写回 config |
| `assets/logiscope/lang/*` | `logiscope.btn.name_style` / `logiscope.name.display` / `logiscope.name.registry` / `logiscope.flash.name_style`（中英各 4 条，190 键） |

**只影响显示**：系列身份、`selectedAeIds`、存档与导出仍用键身份（物品 = registry id，
AE 非物品 = `ae:` + SNBT）—— 换开关不会让旧存档对不上新曲线，也不影响同物品多格求和。

★ 计算页的标题是**拼好缓存的**（键 = 区间 × 曲线 × 对象 × 运算），换风格不在这四个键里；
所以切换时顺手把 `aLo` 置 `Long.MIN_VALUE` 强制重算，否则标题会停在旧写法。

★ `NameText.of` 两种写法都缓存（选格页每帧每行、图例每帧每系列都要算），
切换风格时整表作废；`style()` 里连 `NoClassDefFoundError` 都兜住（离线断言环境没有配置类）。

离线：`build` 通过；TestChart 489 条全过（新逻辑依赖注册表/AE，不适合进纯逻辑断言）。
**本次只在 main（1.21.1）实现，其余四条线未移植**（用户明确要求）。

## 第二十轮②：追踪目标高亮（穿墙红黄闪烁框，2026-10-25）

**需求**：根页的探针列表里，在删除按钮**左边**加一个十字准星按钮；按下时若该探针的追踪方块
在当前维度且与玩家**三轴差值都 < 128 格**（不要求欧氏距离），就在世界里画出**穿墙可见**的
红黄闪烁方块边框（1 Hz）。

| 件 | 内容 |
|---|---|
| `ui/ProbeHighlight`（新） | 高亮状态的唯一持有者 + 世界内线框渲染。注册在**游戏总线**（`AFTER_ENTITIES`） |
| 自定义 `RenderType` | 复制 `RenderType.lines()` 的状态，只把深度测试换成 `NO_DEPTH_TEST`（穿墙）、写掩码 `COLOR_WRITE`（不写深度） |
| `ui/ScopeScreen` | 行尾 `HL_W=11` 的十字准星按钮（`probeHlX = deleteX - HL_W - 2`），图标用 `g.fill` 拼（上下左右四段 + 中心 2×2）；点击校验并 `say` 原因 |
| `LogiScopeMod` | 注册渲染监听；`LoggingIn` 时 `ProbeHighlight.clear()`（探针全清，框不能带进新世界） |

★ 为什么不用 `AFTER_TRANSLUCENT_BLOCKS`：那一档是**跟着地形图层**发的
（`Stage.fromRenderType` → 在 `renderChunkLayer` 里派发），在那一刻往共享
`BufferSource` 塞顶点再自己 `endBatch` 风险更大。`AFTER_ENTITIES` 是 `renderLevel` 里
直接派发的一步，且深度测试已关，"谁先画"不影响可见性。

★ 可见性判定分两处：**点击时**校验一次并说明拒绝原因（没坐标 / 别的维度 / 太远）；
**每帧渲染再看一次**，超出范围只是不画，状态保留（走回去还能看见）。

★ 闪烁用系统时钟而不是 tick：需求是 1 Hz，而 tick 会随 `/tick rate` 漂移。

离线：build 通过；TestChart 489 条全过（渲染依赖 GL 与游戏循环，不适合进纯逻辑断言）。
**本版只在 main（1.21.1）实现，其余四条线未移植。**

## 第二十轮③：精致存储（RS 2）网络采样兼容（2026-10-25）

**需求**：为精致存储增加采样兼容 —— 与 AE2 类似（能存物品和流体），模组开源
（`refinedmods/refinedstorage2`），实例里已装。先在 main（1.21.1）做。

**结论**：与 AE 结构同构、API 没有一处共通 —— 新增两套并行实现（`RsKeys` / `RsNetworkReader`
对 `AeKeys` / `AeNetworkReader`），并把"AE 模式"抽象成**网络模式**（`netMode()`），
选格页 / 完成提交 / 禁用容器格三处只写一次。契约见 ARCHITECTURE **CP28**。

| 件 | 内容 |
|---|---|
| `analysis/RsKeyText`（新） | RS 键的**纯文本**身份（无游戏依赖，t43 断言钉住）：无组件物品 = 裸 registry id；其余 `rs:i:` / `rs:f:` + 可选 `\|组件指纹` |
| `bind/RsKeys`（新） | 反射层：`isRsGridMenu`（沿父类链比类名，零 `forName`）、`rows`（仓库视图 → 行）、`encode`（键 → 身份）、`fingerprint`（排序后的组件指纹） |
| `collect/RsNetworkReader`（新） | 服务端读值：`Grid#isGridActive` → `getItemStorage().getAll()` → 按身份串比对求和；同 tick 同网络只拉一遍 |
| `bind/DataTarget` | 新增 `rs:network` 标记 + `rsNetwork(...)` + `isRsNetwork()` / `isNetwork()`；去重与显示按"家"区分 |
| `collect/ProbeInstance` | 采样分派按 `isRsNetwork()` 选读值器；`replaceAeTargets` → **`replaceNetworkTargets`**（AE 与 RS 一起覆写） |
| `collect/ContainerAccess` | `snapshot()` 增加 RS 分支（行来自 `RsKeys.rows`，`menuSlotIndex = -1`）；`SlotView#shortName` 认 `rs:` |
| `bind/NameText` | `rs:` 身份取名（流体走 `FluidStack#getHoverName`，物品走 `getDescription`；组件指纹不参与取名） |
| `ui/ScopeScreen` | `rsMode` 字段 + `netMode()`；`enterPicker` 两家各判各的；`toggleRow` 的 `RepoSlot` 守卫只有 AE 走；完成提示文案分家 |
| lang | 中英各 +5 键（`picker.rs_mode` / `rs_hint` / `flash.rs_none` / `rs_replaced` / `rs_need_anchor`） |

三个关键决定（细节见 CP28）：
1. **无组件物品不挂前缀** —— 它与"原版的一件铁锭"是同一个东西，共用裸 id 让名称解析
   不必认识 RS，也不影响旧目标。
2. **组件必须进身份**（否则两瓶不同的药水合成一行）—— 指纹用"排序后的
   `组件类型=值`"拼，因为 `DataComponentPatch.toString()` 按插入顺序打印，直接用会抖动。
   已知限制：值的 `toString()` 不保证跨版本稳定，撞车只会合并（不会读出假值）。
3. **行来自仓库视图，不来自槽位** —— RS 2.0 的网格不是"一格一个 Slot"，界面按
   `getViewList()` 逐格画。副产品：AE 只能选当前页，RS 能选整个网络。

离线：`build` 通过；TestChart **513 条**（新增 t43 共 24 条）；lang 中英各 **203** 键；
**新增 `dev-check/ProbeRsApi.java` —— 对真实 RS 2.0.9 jar 把本类用到的每一个反射入口
解析一遍，输出 `RS_API_OK`**（`getRepository` / `getViewList` / `getAmount` /
`getResourceForRecipeMods` / `getName` / `item` / `fluid` / `components` /
`isGridActive` / `getItemStorage` / `getAll` / `resource` / `amount`，
以及三条"接口链里有没有"的判定）。
**实机待测**（`VERIFY.md` 第二十轮③）：选格页列表、勾选 → 完成 → 图表、断电 no_data、
流体与 AE 的并存。**其余四条线未移植。**

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

:: 2) 离线断言 + 渲染压测 + 产物验收（一键；逐条等价命令见 VERIFY.md §E）
dev-check\offline-check.bat

:: 3) 进游戏（或用 build\moddev\runClient.cmd 脱离 Gradle 反复重启）
gradlew.bat runClient
```

改代码前**必读** [`ARCHITECTURE.md`](ARCHITECTURE.md)：先看第二节（接口与数据流程），
再看第三节（关键逻辑路径 CP1–CP27）中标为「高」风险的条目 —— 它们每一个都在实机上出过错。

---

