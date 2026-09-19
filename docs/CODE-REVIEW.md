# LogiScope · 全项目代码审查（2026-09-19，基线 `ecfc7cc`）

> **怎么审的**：三个子系统分头读（采集/绑定/存储、UI、分析与存档）＋ 我自己复核每一条
> 高优先级结论。**代理的话不当事实** —— 每条 P0/P1 都用「读源码行号」或「离线复现」验过，
> 复核中**否掉了 1 条**（见第四节）。
>
> **范围**：`src/main/java` 全部 35 个文件（约 11.5k 行）＋ `build.gradle` / `gradle.properties` /
> `src/main/templates/.../neoforge.mods.toml` / 两个 lang 文件。`dev-check/` 只作为断言依据。

## 一、结论速览

| 级别 | 条数 | 说明 |
|---|---|---|
| P1·数据保真 | **3** | 会让曲线/统计**说谎**（吞真实值、谎报断档、0 冒充"读不到"）—— 按本项目的纪律，这几条优先 |
| P1·功能/性能 | **5** | 键盘被吞、跨世界张冠李戴、竞态写错一格、拖窗口反复全量读盘、数值溢出 |
| P2 | 13 | 隐患与可维护性 |
| P3 | 6 | 风格 / 死代码 |
| 已知残留（等拍板） | 1 | 坐标路径未验证时退回容器下标（第十五轮就记着，NBT 锚点路径已绕过） |

**离线和构建层面全部干净**：`analysis/*` 确实没 import 任何游戏类（纯逻辑契约成立）、
无隐藏静态可变状态、`SampleStore` 环形缓冲逐条核算无误、`WaveColumns`/`RectRuns` 稳态零分配、
两个 lang 文件键集合完全一致、`build.gradle` 的两处 charset 铁律都设了、
`versionRange=[21.1.209,)` 与目标实例一致（不会因区间过高被拒载）。

---

## 二、P1：建议下一轮修（每条都给了证据）

### 1. ★ IO 线统计吞掉每个数据段的第一笔真实流量，并谎报一次断档 ★
- **位置**：`analysis/ChartData.java:250-267`（分流点）＋ `analysis/CurveMath.java:134-150`、`185-196`（统计）
- **机制**：存量线的**首点**按契约带 `breakBefore`（"首点前面没有观测"）→ 这一格本该如此；
  但它顺手把 `b.flowNeedBreak = true` 传给了下一格，而下一格的 Δ 是**两个都观测到的相邻格之差**，
  是真实值。统计却按 `breakBefore` 把它剔掉并计一次缺口。
- **实测（我写的临时探针，连续 3 格 10 → 14 → 12）**：
  ```
  存量线: 上升=4  下降=-2  断档=0        ← 对
  IO  线: 上升=0  下降=-2  断档=1  事件=1  ← 错：吞了 +4，还报了一个不存在的断档
  ```
  面板上会同时出现"存量说上升 4、IO 说上升 0"，而且 `断档 ≥ 1` 恒成立（每段一个）。
- **建议**：`flow` 上的 `breakBefore` **只服务于渲染断线**，统计不应过滤它 ——
  `build` 里 `flow` 点只在 `!gap && lastTick == t-1` 时产生，**每个点的值都成立**。
  IO 线的"缺口数"应改为由存量侧推导（少了几笔 Δ），而不是数 `breakBefore`。
  修的时候把上面那三个数写成断言（t34）。

### 2. ★ NBT 数值被窄化进 `int`，越界/NaN 静默变成错值 ★
- **位置**：`collect/ProbeInstance.java:691` `bufCounts[got] = (int) Math.round(v);` → `store/Sample.java:30`（`int count`）
- **机制**：`data get`/NBT 里的 long 或 double 超过 `Integer.MAX_VALUE` 时 `(int)` 静默回绕成负数；
  `FloatTag` 的 NaN 经 `Math.round` 变 **0**（被当成"真实的 0"记进曲线）。
  触发场景很实在：能量类设备的 FE/J 值本来就常在 2^31 以上。
- **建议**：`Math.round` 前判 `Double.isFinite` 与 int 范围，越界/非有限一律按"读不到"处理（留空），
  不要写一个错的数。**宽化存储（int→long）是更大的改动，先修诚实性。**

### 3. ★ 纯数据探针暂停时不写 `gap` 标记 → 恢复后曲线跨过暂停连起来 ★
- **位置**：`collect/ProbeInstance.java:786-791`（`writeGapMarker` 只看 `lastSampledGameTime`）
- **机制**：`lastSampledGameTime` 只在**物品**路径的 `advanceTick` 里更新；纯数据探针走 `lastDataTick`
  → 恒为 `Long.MIN_VALUE` → `pause()` 时什么都不写 → 恢复后画图把暂停前后连成一条直线，
  正是 `gap` 标记要防的"编造连续数据"。
- **建议**：水位取 `max(lastSampledGameTime, lastDataTick)`（数据半边也维护一个"最后采样 tick"）。

### 4. `configure()` 与"在飞的服务端采样"竞态 → 新会话第一格写的是**旧设备**的值
- **位置**：`collect/ProbeInstance.java:620-652`（快照在锁外取，锁内只复查 `state`）＋ `344-373`（`configure`）
- **机制**：服务端线程在 620 行取到旧 `target`/`dataTargets` 后等锁；渲染线程此时 `configure()`
  改目标、`store.clear()`、`lastSampledGameTime = MIN_VALUE` 后放锁 → 服务端线程进锁后 `state` 仍是
  RECORDING/FOLLOWING（复查通过）→ `advanceTick` 因 MIN_VALUE 必返回 true → **用旧目标读值写进新会话**。
- **建议**：锁内重新读 `target`/`dataTargets` 并重算 `hasItems/hasData`（或加一个 generation 序号）。

### 5. `readSlotAnchor` 把"列表路径不存在"也写成 `0`（应与"这格是空的"区分）
- **位置**：`collect/DataProbe.java:49-61`（`bySlot` 返回 null 的两种含义被合并成 `count = 0`）
- **机制**：`bySlot → entriesAt` 在**路径不存在 / 结构不符**时返回空表，于是返回 `Entry(slot, null, 0)`
  —— "读不到"被当成"真实的 0"记进曲线（同文件 `readNumber` 的语义是"路径不存在 → null → no_data"，自相矛盾）。
- **注意（别改坏）**：Mekanism 的 `items` 键**在所有格子都空时根本不写**（`saveTo` 里 `if (!list.isEmpty())`），
  所以"列表不在"也可能是"全空"的合法表达。要区分得靠"这个方块还是不是原来那台设备"
  （探针里有 `deviceName`；更稳的做法是同时看这个方块实体还有没有别的同类键/类型）。
- **建议**：至少把"路径存在但元素缺"与"路径不存在"分开；后者按"读不到"处理（no_data），
  并把上面这条 MEK 特例写进注释，免得下一轮把 0 全变成 no_data。

### 6. 跨世界的静态残留：`ContainerAnchor.clearActive()` 与 `ProbeSlots.reset()` **都没有调用点**
- **位置**：`bind/ContainerAnchor.java:99`（定义）、`collect/ProbeSlots.java:119`（定义）；
  对照 `LogiScopeMod.java:100-103`（进世界时清了 `probes` 与 `DataCommand`，**漏了这两个**）
- **机制**：容器 id 只在 0~99 循环，新世界第一个容器撞上旧 `activeContainerId` 时
  `ContainerAnchor:77` 会直接复用**旧世界**的锚点 → 选格页/探针上下文把"当前开着的容器"认成别的世界的方块。
  `ProbeSlots` 同理：新世界目标坐标与旧世界相同时直接复用旧槽位清单（或遗留 `pending` 永久早退）。
- **建议**：在 `LoggingIn` 监听里一并调用这两个 reset（与 `probes().clearAll()` 同一时机）。

### 7. 重命名输入框离开回放页后**不释放焦点** → 之后所有按键被吃掉
- **位置**：`ui/ScopeScreen.java:3401-3432`（`renameFocus` 时 `return true` 吞键）
  vs `3518`（`!renameFocus || !L.hasChart()` 才画框）；清焦点只在 Esc(3412)/Enter(3489)
- **机制**：点「重命名」→ 点「返回列表」→ 页面变成 ROOT（不画输入框），但 `renameFocus` 仍为 true
  → 键盘全部失效（只有 Esc 能救）。用户感受就是"键盘坏了"。
- **建议**：切页分支（`replay_back` / `data_back` / `close`）或 render 开头加一句
  "`renameFocus && !L.hasChart()` → 置 false"。

### 8. 存档列表扫描**解析整份 JSON**，而 `init()` 每次窗口变化都扫一遍
- **位置**：`archive/Archives.java:152-166`（`scan` 对每个存档调 `readHeader`）＋
  `archive/ArchiveFormat.java:161`（`readString` + 整份 `parseObject`）＋ `ui/ScopeScreen.java:552`
  （注释写"只读 JSON 头，几 KB 级"—— **与实现不符**；vanilla `Screen.resize` 会重新 `init`）
- **机制**：拖动窗口边缘 / 改 GUI scale 时**每秒多次**把每个存档（含几万条 sample）整体解析成对象树
  → 明显间歇卡顿，且和面板无关。存档越多、样本越长越严重。
- **建议**：`readHeader` 只读文件前若干 KB（或流式读到 header 结束就停）；`init` 里加节流
  （按时间或目录签名决定要不要重扫），并修掉那句注释。

### 9.（**已知残留，等拍板**）坐标路径在映射未验证时退回容器下标
- **位置**：`collect/WorldProbe.java:209-219`（`effectiveIndex` 的 `v >= 0 ? v : containerSlotIndex`）
  ＋ `collect/ProbeInstance.java:626-627`（`FOLLOWING` 一律放行服务端采样）
- **状态**：第十五轮就记进 PROGRESS/VERIFY（"那个 8"），用户当时的判定是"关界面继续采"更重要，
  所以**保留**。本轮 NBT 锚点路径（CP24）完全绕开了这条路 —— MEK 这类机器改用 NBT 追踪后不受影响。
- **建议**：维持现状，但把这条写进"已知限制"并在做 UI 提示时别让用户误以为关界面后的读数一定准。

---

## 三、P2 / P3

| 级别 | 位置 | 问题 | 建议 |
|---|---|---|---|
| P2 | `ui/ScopeScreen.java:3629`、`3644`、`3818` | 悬浮详情只判 `mouseY` 不判 x、也不判"在面板内" → 鼠标停在被压住的容器界面上、y 恰好落在信息行/列表带时，面板里冒出无关的详情框 | 条件前加 `overPanel(mouseX, mouseY, L)` |
| P2 | `ui/ScopeScreen.java:418` + `1714` | `scrollRow` 被根页与选格页共用；`openProbe` 会清零，但**从图表页点「选格」**不清 → 选格页一进来停在列表中部 | 进选格页时归零（或两页各用一份） |
| P2 | `ui/ScopeScreen.java:131`、`2946` | `windowTicks` 是 static 的"用户偏好"，`loadReplay` 把它改成存档全跨度 → 回实时页时窗口突然变成几万 tick | 回放视图宽度单独一个字段 |
| P2 | `ui/ScopeScreen.java:2674` | `commitPicker` 清 `hiddenItems`/`ioShown`，与字段注释"跨开合保持：它是看图的偏好"矛盾 → 补一格就把 5 条线的隐藏状态全丢了 | 只在换数据源时清 |
| P2 | `ui/ScopeScreen.java:751` | `p == null` 分支把展示数据置空却不复位 `builtVersion`（现在靠 id/version 单调递增侥幸不炸） | 顺手复位，保住"缓存键只在成功重建时前进"的不变量 |
| P2 | `ui/ScopeScreen.java:1238`、`1256`、`1279`、`1479`；`ChartRenderer.java:396`、`498` | 每帧分配：`window()` 的 `long[2]`、`visibleSelectionStats()` 的 ArrayList（拖动时白算）、`markers().toList()` / `liveTotals()` 整表拷贝、`String.format` 组读数 —— `drawSeries` 辛苦零分配被外层抵消 | 复用字段 + 手写拼接 |
| P2 | `archive/Archives.java:35`、`56-67` | 导出目录名用**秒级**时间戳，同一秒二次导出**静默覆盖**上一份记录（几万 tick 无声消失，无告警） | 目录已存在就加后缀 |
| P2 | `archive/ArchiveFormat.java:50-51`、`112-115`、`160-164` | 类注释承诺"`kind` 文件指纹 + `format` 版本校验"，两个入口都没读 → 任何叫 `recording.json` 的 JSON 都被当存档；将来 `format:2` 会被静默按 v1 解释 | 读 `kind` 必须相等、`format` 必须 ≤ 支持上限 |
| P2 | `analysis/ChartData.java:250-255` | `build` 不防御"同 tick 同名"重复样本（契约只靠上游自觉）→ 漏合时会切成两段并出现重复点（正是第七轮"震荡"的形状） | 在 tick 分组内自保去重 |
| P2 | `bind/DataCommand.java:61-67` | `tick()` 被 3 处调用（全局 ClientTick + 选格页 + 数据页）→ `clientTicks` 每 tick +2~3，"1 秒超时"实际约 0.3~0.5 秒 | 只让全局监听推进时钟 |
| P2 | `analysis/NbtSlotLayout.java:203-208`、`analysis/Snbt.java:286-296` | `(int) n.number()` 直接窄化（NaN→0、超界→MAX_VALUE）；`parseNumber` 还会接受 `NaN`/`Infinity` 字面量（SNBT 里不存在，只可能来自脏文本） | 转换前判 `isFinite` + 范围 |
| P2 | `analysis/CurveMath.java:82-85` | `stats(空列表)` 会在 `sorted[n/2-1]` 抛 AIOOBE（当前被调用方的 `isEmpty()` 挡住，属侥幸） | 空输入提前返回 |
| P2 | `collect/ProbeInstance.java:631-632` | 数据半边统一用 `target.dimension()`，忽略 `DataTarget` 自带的 dimension（结构上没有约束"两者同维度"） | 按 `dt.dimension()` 取 level，或加一致性校验 |
| P2 | `analysis/SlotAlign.java:167-204` | 对齐依赖"`entries` 按 slot 升序"这一前置条件，代码不校验也不排序 → 上游一疏忽就**静默**给错号（错号不会报错，只会静静测错格） | 入口按 `slot` 排一次序（MEK 本就是升序，零风险） |
| P3 | `ui/ChartRenderer.java:753`、`774`、`781` | `colOf` / `vline` / `vlineF` 已无调用点（逐列绘制早被 `WaveColumns`+`RectRuns` 取代），留着会误导后来者 | 删除 |
| P3 | `ui/ScopeScreen.java:1417`、`1418`、`1429`；`ChartRenderer.java:494`、`649` | 读数文案一半走 lang、一半硬编码英文（`now` / `ticks` / `switch` / `series` / `tick`）→ 中文环境反而中英混杂 | 统一抽 lang 键 |
| P3 | `ui/ScopeScreen.java:3338` | DATA 页滚轮把编辑格与底部按钮区也算进"目标列表"（区互斥是对的，滚按钮时列表会跑） | `my < R.editTop()` 才滚 |
| P3 | `archive/Archives.java:189-200` | `lastName()` / `remember()` 是死代码，且本包唯一的静态可变状态 | 删除 |
| P3 | `analysis/Series.java:54-57` | 注释"段数 = breaks + 1"与契约（首点算 break）不符 | 修注释 |
| P3 | `archive/Archives.java:126-138` | 名称截断按 `length()` 可能切断代理对（emoji）；`rename` 恒返回 true 没有信息量 | 按 code point 截断 |

---

## 四、复核时**否掉**的结论（记录在案，免得下一轮重挖）

| 代理结论 | 复核结果 |
|---|---|
| "纯数据探针 `resume()` 永远失败，状态卡在 PAUSED"（列为 P1） | **不成立**。`ScopeScreen.enterDataPage:3710-3715` 要求 `target().hasPersistentId()` 才进数据页 ⇒ 数据探针的 target **带着维度+坐标** ⇒ `resumeFromPause`（`ProbeInstance:459-467`）走 `manager.followAfterClose() && target.hasPersistentId()` 分支能正常恢复。只有"target 没有坐标"的探针才会卡住，而那种探针进不了数据页。 |

其余每条 P0/P1 我都回读了对应行号；第 2 节第 1 条的数值是**跑出来的** ——
复现脚本留在 `dev-check/ProbeFlow.java`（只打印不判定，所以不影响 `TestChart` 的全绿），
下一轮修 P1-1 时把它写成断言即可。

## 五、已知限制与"看着像 bug 其实是对的"（防重挖）

- 物品语义（`Aggregator.combine`：≤0 跳过）与 NBT 语义（`combineAll`：0/负数保留、同名求和）
  是**有意的双规则**，t28 已钉死。
- 断线只认显式标记（`no_data` / `gap` / 逐线连续性）＋ `MAX_SILENT_GAP=20` 兜底，
  不用"间隔 > 1"推算（CP10）。
- `SampleStore` 环形缓冲的覆盖/扩容/最新值语义逐条核算正确（`head` 取模、`resize` 保留最新）。
- `ServerClock` 读主世界 `gameTime` 而目标可能在别的维度：`DerivedLevelData` 委托主世界数据 ⇒ **各维度同值**，无错配。
- 探针 id 单调递增**不复用** ⇒ `builtProbeId` / `seededForProbeId` / `pickerProbeId` 不会被新探针骗过。
- 点击命中的矩形边界：`mouseClicked` 全部 11 个分支逐条核对，**没有**只写下界的（历史那条已修）。
- 所有 `fill` 颜色都带非零 alpha（项目踩过 alpha=0 整块消失）；`ScopeScreen`/`ChartRenderer` 里没有 scissor 调用，不存在配对遗漏。
- 两个 lang 文件各 162 键、集合完全一致；`analysis/*` 无任何 `net.minecraft` import，离线断言可跑。
- `build.gradle` 两处 charset 都显式设了（`filteringCharset='UTF-8'`、`options.encoding='UTF-8'`）。

## 六、建议的下一轮顺序

1. **P1-1 IO 统计**（数据保真，有复现脚本，改完直接变 t34 断言）；
2. **P1-2 / P1-3 / P1-4 / P1-5**（都是"别把错的数写进曲线"，改动都在 `collect`/`analysis` 纯逻辑附近，可离线断言）；
3. **P1-6 / P1-7 / P1-8**（跨世界残留、键盘被吞、存档扫描 —— 都是一处小改，但用户能直接感觉到）；
4. P2 里挑"会丢用户设置"的（隐藏线状态、窗口宽度）与"会覆盖存档"的（同秒导出）先修。
