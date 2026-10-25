# LogiScope · 架构与关键逻辑路径

本文档描述四件事：**代码分层与层间契约**、**接口与数据流程**、**关键逻辑路径（含风险等级）**、**已核实的 API 事实**。开发之前应先通读本文；接口或数据流发生变化时，必须同步更新第二节。

> 本文档的 API 结论均来自本地 `neoforge-21.1.209-sources.jar` 与第三方模组 jar 的逐行核实，标注"21.1.209 核实"的事实以该版本为准。

> 本文档只对 1.21.1 主线成立。1.20.1 分支的 API 差异与重新核实的结果统一记在
> [`PORT-1.20.1.md`](PORT-1.20.1.md)，差异处在该文档里指回本文的对应小节。

---

## 一、分层

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 输入层  bind/KeyBindings       按键 → 打开 / 收起面板                    │
│         bind/TextInputGuard    "玩家是否正在打字"（三层守卫）            │
├──────────────────────────────────────────────────────────────────────────┤
│ 绑定层  bind/ContainerAnchor   右键方块 → 「维度 + 坐标」锚点            │
│         bind/ProbeTarget       探针目标（一组槽位引用 + 持久身份）       │
│         bind/ProbeContext      当前可探测的容器（容器界面 / 准星）       │
│         bind/DataTarget        一个 NBT 数据目标（路径 / 名称 / 锚点）   │
│         bind/DataCommand       读方块 NBT 的取数通道（服务端直读 / 命令）│
├──────────────────────────────────────────────────────────────────────────┤
│ 采集层  collect/ContainerAccess 读菜单槽位（服务端读不到时的兜底数据源） │
│         collect/WorldProbe      读方块实体 + 下标映射验证（单人主数据源）│
│         collect/ProbeSlots      无 GUI 容器的槽位清单（服务端读一次）    │
│         collect/DataProbe       按 NBT 路径读值（服务端 tick 采样用）    │
│         collect/AeNetworkReader AE 网络物品数量（反射，零编译依赖）      │
│         collect/ServerClock     全局权威世界 tick                        │
│         collect/ProbeInstance   一个探针：状态机 + 每 tick 采样          │
│         collect/ProbeManager    实例表 / 创建 / 删除 / 两个驱动点        │
├──────────────────────────────────────────────────────────────────────────┤
│ 存储层  store/SampleStore       环形缓冲 + version，唯一真相             │
│         store/Sample            最小可序列化单元（含 no_data / gap 标记）│
│         store/MarkerList        用户标记的时间点集合                    │
├──────────────────────────────────────────────────────────────────────────┤
│ 分析层  analysis/Analyzer       纯函数：摘要 / 逐 tick 差值              │
│         analysis/ChartData      纯函数：扁平序列 → 按物品分组的数据线    │
│         analysis/Aggregator     纯函数：多格 → 按物品分别求和            │
│         analysis/CurveMath      纯函数：区间统计与事件分析（回放计算）   │
│         analysis/Val            自适应数值（长整数精确 / 浮点 double）   │
│         analysis/Snbt / SlotAlign / NbtSlotLayout   NBT 解析与槽位对齐   │
│         analysis/WaveColumns / RectRuns            波形列聚合 / 矩形合并 │
│         analysis/Series(Pt)     数据线的形状（含断点标记）               │
├──────────────────────────────────────────────────────────────────────────┤
│ 呈现层  ui/ScopeScreen          只读：面板（根 / 选格 / 图表 / 回放 / 数据）│
│         ui/ChartRenderer        只读：坐标轴 / 波形 / 框选 / 准线 / 标记  │
├──────────────────────────────────────────────────────────────────────────┤
│ 持久层  archive/Archives        存档导出 / 读取 / 重命名（recording.json + csv）│
└──────────────────────────────────────────────────────────────────────────┘
```

### 层间契约（改代码时必须守住）

| 契约 | 内容 | 违反的后果 |
|---|---|---|
| C1 | `ProbeInstance` / `ProbeManager` 不认识任何 UI / 渲染类 | 界面崩了会连带采样一起挂 |
| C2 | `SampleStore` 只存 `(tick, itemId, Val)`，不持有 `ItemStack` | 无法离线分析、无法导出、容易泄漏世界引用 |
| C3 | `Analyzer` / `ChartData` / `Aggregator` / `CurveMath` 是纯函数，不持有状态 | 无法单元测试，图表和导出会算出不同结果 |
| C4 | `ui/*` 只读，绝不直接改采样数据 | 界面刷新时机不可控，容易写出竞态 |
| C5 | 只有一个探针管理器：`LogiScopeMod.probes()`；每个实例各持一份 `SampleStore` + `WorldProbe` | 多处 `new ProbeManager()` 会导致数据分裂；共享 `WorldProbe` 会让两个探针互相覆盖读数 |
| C6 | `WorldProbe#readNow` / `ProbeSlots#readNow` **只能在服务端线程跑** | 跨线程读 ServerLevel 是数据竞争 |
| C7 | `no_data` 样本不进统计（`Analyzer` / `ChartData` 负责过滤，但要让它**占时间轴**） | 断档会被算成"数量掉到 0"，净流量全错；反过来丢掉时间轴会让图上的断档消失 |
| C8 | **`analysis/` 与 `store/` 不 import 任何 `net.minecraft` / `net.neoforged`** | 一旦引入游戏类，`dev-check/` 的离线断言就跑不了，只能靠进游戏肉眼看 |
| C9 | 一个探针每 tick 写**每个出现的物品一条**样本，但**共用同一个 tick 值**（多格先聚合，见 CP16） | 同一 tick 的样本必须是"同一格时间"；跨 tick 交错会让时间轴语义崩掉 |
| C10 | 采样只允许发生在两个驱动点上：**客户端 tick**（状态机 + 服务端读不到时的菜单回退）与**服务端 tick**（单人主路径），两者用 `ProbeInstance#sampleLock` 互斥 | 客户端线程与服务端线程同时写缓冲/聚合缓冲会算错数（且症状是"偶尔跳一下"，极难查） |

---

## 二、接口与数据流程

> 本节是模组内部接口与数据流的**权威描述**。跨层接口的签名或语义一旦变动，必须同步更新本节（含下面两张表）；新增进程级缓存时，也要回来登记并核对生命周期。

### 2.1 数据流总览

```
[世界]                    [服务端线程]                        [渲染线程]
容器 / 方块实体 / NBT      ServerTickEvent.Post                ClientTickEvent.Post
   │  同步读（无包、无权限）  │  遍历全部实例，每个世界 tick 一条  │  状态机（界面 / 源状态）
   └──────────────────▶ ProbeInstance.sampleOnServerTick ──┐    │  菜单回退采样（服务端读不到时）
                                                            ▼    ▼
                                              SampleStore（环形缓冲；version 计数）
                                                            │  version 变化才重建（纯函数）
                                                            ▼
                                              ChartData / Analyzer / CurveMath
                                                            │  每帧只读
                                                            ▼
                                              ChartRenderer → ScopeScreen（绘制 / 交互）
                                                            │  导出 / 读取
                                                            ▼
                                              archive/Archives（recording.json + csv）
```

### 2.2 接口清单

| 边界 | 接口 | 数据形态 | 线程 |
|---|---|---|---|
| 输入 → 呈现 | `KeyBindings`（`ScreenEvent.KeyPressed.Pre`）+ `TextInputGuard` 三层守卫 | 按键事件 / `boolean` | 渲染线程（事件） |
| 绑定 ← 世界 | `ContainerAnchor`：`RightClickBlock` 记 pending → `ScreenEvent.Init.Post` 提升为 active | `Anchor(dimension, pos, containerId)` | 渲染线程（事件） |
| 绑定 → 采集 | `ProbeTarget`：`List<SlotRef>` + deviceName + dim + pos；`SlotRef(menuSlotIndex, containerSlotIndex, itemId, count)` | 不可变 record | 构造于渲染线程，服务端线程读取 |
| 绑定 → 采集 | `DataTarget`：`valuePath` / `namePath` / `literalName` / 锚点 / 读取口径（`forceNumeric`）；含 slot 锚点与 AE 变体 | 不可变 record；`ProbeInstance#dataTargets` 为 COW 列表（增 / 替换 / 删走 `addDataTarget` / `replaceDataTarget` / `removeDataTarget`） | 渲染线程写（锁内），服务端线程读 |
| 采集（服务端） | `WorldProbe#readNow` / `ProbeSlots#readNow` | `SlotView(itemId, count, 下标)` | 仅服务端线程（C6） |
| 采集（服务端） | `DataProbe`（NBT 路径取数）、`AeNetworkReader`（AE 网络数量） | `Val`；`null` = 读不到 | 服务端 tick |
| 采集 → 存储 | `ProbeInstance` → `SampleStore.add(Sample(tick, itemId, Val))`；标记样本 `no_data` / `gap` | 不可变 record；同一 tick 多物品各一条 | 服务端写、渲染读（`sampleLock` 互斥，C10） |
| 采集 → 呈现（旁路） | `ProbeSlots` / `DataCommand`：`server.execute` 提交、volatile 交接 | 槽位清单 / SNBT 文本 | 服务端写，渲染线程读 |
| 存储 → 分析 | `SampleStore#toList()` + `version()` | `List<Sample>` 快照 | 渲染线程 |
| 分析（内部） | `ChartData#build` → `List<Series>`（`SeriesPoint(tick, Val, breakBefore)`）；`Analyzer#summarize` → `Summary`；`CurveMath` → 不可变 record | 纯函数，零游戏依赖（C8） | 渲染线程 |
| 分析 → 呈现 | `ChartRenderer#render(g, font, chartData, …)` | 只读 | 渲染线程 |
| 呈现 → 持久层 | `Archives#export / load / rename`（`ArchiveData`） | JSON + CSV；先写盘后删探针（C19-2） | 渲染线程 |

### 2.3 两个驱动点（C10）

| 驱动 | 事件 | 线程 | 职责 |
|---|---|---|---|
| 状态机 | `ClientTickEvent.Post` | 渲染线程 | 推进权威世界 tick、跑状态机（界面开没开 / 容器换没换 / 源还在不在）、服务端读不到时的**菜单回退采样** |
| 采样（主路径） | `ServerTickEvent.Post` | 服务端线程 | 单人下遍历全部实例，按"维度 + 坐标"同步读方块实体与 NBT，写"这个世界 tick 的真值" |

样本时间戳一律取服务端权威 `ServerLevel#getGameTime()`（经 `ServerClock` 交接）；客户端 tick 只负责状态机与去抖（动作栏提示、快捷键连发），不产生样本。

### 2.4 采样来源的选择（当前口径）

| 局面 | 数据源 | 得到什么 |
|---|---|---|
| 单人 + 有坐标 + 设备有可读容器（**界面开 / 关都一样**） | 集成服务端同步读（`WorldProbe` / `DataProbe`） | 真值，无同步延迟 —— 菜单读数只是它的同步副本 |
| 服务端读不到 + 界面开着（多人 / 无坐标 / 无容器能力） | 菜单槽位（`ContainerAccess`） | 真值，但带一个网络往返延迟（约 1 tick） |
| 服务端读不到 + 界面关着 | 拿不到 | **记 `no_data`**，不假装是 0 |

"服务端读不到"包括：多人（方块实体不在本进程）、无坐标（vanilla 菜单不存方块坐标）、无容器能力（既非 `Container` 也无 ItemHandler，建探针时验证为 UNAVAILABLE）。

---

## 三、关键逻辑路径

各节标题旁标注**风险等级**；标为「高」的实现点在改动前必须先读对应小节。

### CP1 · 拿到容器内容　风险：低

`player.containerMenu.slots` → `Slot#getItem()` → `ItemStack`

只列**外部设备**的槽位，判两次：

| 判据 | 排除什么 |
|---|---|
| `player.containerMenu != player.inventoryMenu` | 整个菜单就是玩家背包的情况（关掉容器界面后单独开面板） |
| `Slot#container != player.getInventory()` | **菜单里混着的玩家背包那 36 格** |

两条判据缺一不可：只判第一条时，打开容器会把自己背包的 36 格混进列表 ——
在"可视化选格"的语义下，那意味着点到自己背包的格子也会被当成设备槽。
同一原则下另跳过 `Slot#isFake()`（部分模组用假槽位占位）。

### CP2 · 在 GUI 打开时捕获快捷键　风险：**高**

这是整个项目风险最高的实现点之一。

vanilla 的 `KeyMapping` 只在**没有界面打开**时才会被 tick 消费
（`Minecraft#tick`：`if (this.overlay == null && this.screen == null) handleKeybinds();`），
而本模组的核心场景恰恰是"容器界面开着"。所以必须走 Screen 事件。

事件的实际发出点不在 `Screen#keyPressed` 里，在 `KeyboardHandler#keyPress`：

``` java
if (screen != null) {
    if (action == 1 || action == 2) {                 // ← 按下 和 长按连发 都进这里
        handled[0] = ClientHooks.onScreenKeyPressedPre(screen, key, scanCode, modifiers);
        if (!handled[0]) handled[0] = screen.keyPressed(...);
        if (!handled[0]) handled[0] = ClientHooks.onScreenKeyPressedPost(...);
    }
    if (handled[0]) return;      // 已处理 → 不再走下面的全局 KeyMapping
}
// ↓ 这里才是 KeyMapping.set / click（并且 handleKeybinds 仍要 screen == null）
```

由这段源码推出**四条必须遵守的规则**，每条都对应一类已验证的失败模式：

| # | 规则 | 不这样做的做法会怎样 |
|---|---|---|
| 1 | 用 `ScreenEvent.KeyPressed.Pre` + `setCanceled(true)` | 拿不到按键，或者按键继续传给原界面触发别的快捷键 |
| 2 | **必须去抖**（事件对 `action==2` 也触发，且不带 action 信息） | 按住 `]` 会以 ~30 次/秒的频率把面板开了又关 |
| 3 | **必须判断"玩家是不是正在打字"** | 在搜索框里打 `hopper`，那些字符一样会走到这里（详见下面的三层守卫） |
| 4 | **必须另接一条无界面时的路径** | 关掉容器界面后在跟踪状态，按 `]` 完全没反应（Screen 事件不触发） |

规则 4 的补法：没有界面时按键走标准 KeyMapping，所以在客户端 tick 里
排空 `OPEN_SCOPE.consumeClick()`，排空后**只有 `screen == null` 时才真的响应**。
（一定要排空：否则界面开着时攒下的点击会在面板关掉那一刻集中兑现，把面板立刻弹回来。）

#### 三层文本输入守卫（`bind/TextInputGuard`）

快捷键若是**能被打出来的字符**，必然与搜索框冲突。三个候选键位的取舍如下：

| 键位 | 结果 |
|---|---|
| `O` | 在搜索框里输入会直接弹面板 |
| `]` | 三层守卫能挡住大部分，但"打字 → 失焦 → 再按"这条路径上仍会多拦一次 |
| **`F9`（采用）** | **功能键不产生字符，冲突通道从根上不存在** |

最终方案 = 功能键 + 保留守卫：键位消除绝大多数冲突；守卫保的是"用户把快捷键改回字母 /
符号键"和"某些模组的输入框吃掉所有按键"两种情况。

三层守卫从准到糙，任何一层命中就不抢键：

| 层 | 判据 | 覆盖范围 | 为什么需要这一层 |
|---|---|---|---|
| 1 | `screen.getFocused()` / `children()` 里的 `EditBox`（+ 类名兜底） | 原版背包搜索框、大多数模组输入框 | 标准契约，最省事 |
| 2 | **JEI 公开 API** `IIngredientListOverlay#hasKeyboardFocus()` | JEI 搜索框 | JEI 的搜索框 `GuiTextFieldFilter` **本身继承 `EditBox`**，但它**不挂进 `screen.children()`**，自己用 `createInputHandler()` 管输入 → 第 1 层完全看不见它 |
| 3 | **`CharacterTyped` 的 Pre/Post 配对情况** | 任何模组的任何输入框 | 通用兜底，防的是 REI / EMI 这类我们没写探针的搜索框 |

第 2 层**用反射调用，零编译期依赖**（没装 JEI 就静静跳过）：
入口只有 `mezz.jei.common.Internal#getJeiRuntime` 是内部类，
之后全是 `mezz.jei.api.*` 的公开接口。整套包在 try/catch 里，失败就关掉这一层。

第 3 层的原理来自对 `KeyboardHandler#charTyped` 的核实：

``` java
if (ClientHooks.onScreenCharTypedPre(screen, (char)codepoint, mods)) return;  // 被 cancel → 不发 Post
if (screen.charTyped((char)codepoint, mods)) return;                          // 被控件吃掉 → 不发 Post
ClientHooks.onScreenCharTypedPost(screen, (char)codepoint, mods);             // 只有没人接手才发 Post
```

⇒ **Pre 发过、Post 没跟上来 ⇒ 上一个字符被消费了 ⇒ 有输入框在接手**。
这条对任何模组都成立，不依赖任何模组的 API。

##### 第 3 层的实现约定：布尔量，而非计数差

只记**上一个**字符事件的结果（一个布尔量），**不要用累计计数**：

``` java
// ✅ 对：一个字符事件结束时，它恰好等于"这个字符有没有被吃掉"
public static void onCharTypedPre(...)  { lastCharConsumed = true;  }
public static void onCharTypedPost(...) { lastCharConsumed = false; }

// ❌ 错：曾因该实现方式失败
private static int preCount, postCount;
private static boolean consumed() { return preCount > postCount; }
```

计数差为何错误：**被吃掉的字符会在差值上留下一个永久的 +1**。
之后每个未被吃掉的字符同时给两个计数各 +1，差值只是**原地保持、永远不会归零**。
于是"在任意输入框里打过一次字"等于"永久打开文本模式"，快捷键彻底失效。

> 通用原则：**差值型状态只在"每个事件都成对抵消"时才成立**。
> 只要存在"只加不减"的分支，差值就会单调增长 —— 这种 bug 在测试里容易被
> 误判成"偶尔失灵"，必须在实现层排除。

正确实现下它**会自愈**：`KeyPressed` 在 `charTyped` **之前**触发，所以

| 场景 | 过程 |
|---|---|
| 在输入框里 | `]` 的字符被吃掉 → Post 不来 → 布尔量保持 true → 每次都拦得住 |
| 不在输入框里 | `]` 的字符没人吃 → Post 把它置回 false → **下一次按键就正常了** |

代价是"刚打完字立刻想开面板"时要多按一次；再叠一个 60 tick 的时效窗口，
避免"很久以前打过一次字"持续抑制快捷键。

另有一条反直觉事实：`KeyMapping#matches` **不看修饰键**。
源码只有一句比较 `key.getType() == KEYSYM && key.getValue() == keyCode`，
因此 `Ctrl+O` 这类组合键毫无意义 —— 单独按 `o` 一样命中。

**默认键位的持久化**：按键绑定会被写进 `run/options.txt`
（形如 `key_logiscope.key.open_scope:key.keyboard.right.bracket`），**该存档优先于代码默认值**。
修改代码默认值后，需删掉那一行或在「选项 → 控制」里手动重绑，否则表现为"改了但没生效"。

### CP3 · 打开面板但不丢菜单　风险：中

见 **CP8**。一句话：用 `ClientHooks.pushGuiLayer/popGuiLayer`，
**不要**用 `Minecraft#setScreen` 替换式打开（那会把原界面从屏幕上抹掉）。

### CP4 · 采样：两个驱动点　风险：中

采样的驱动点有两个（分工表见 2.3），均在游戏总线上。本实现的核心决定是**采样挂在服务端 tick 上、同步读**，依据如下：

| 维度 | 客户端 tick 驱动 + 异步读（已否决） | 服务端 tick 驱动 + 同步读（采用） |
|---|---|---|
| 采样节拍 | 客户端 20 次/秒，与服务端 tick **不锁相** → 必然偶尔漏观测一格 | **世界 tick 本身** → 一格不缺 |
| 时间戳 | 异步读回来的 world tick（会跳 0 / 跳 2） | 那一刻的 `ServerLevel#getGameTime()` |
| 值 | 缓存可能被复用 → 旧值贴新 tick 标签（假平台） | 同步读，即那一刻的值 |
| 高流速 | 客户端每秒上限 20 次 | `/tick rate 200` → **200 条/秒**（世界走几格记几格） |

**`ServerTickEvent.Post` 的时机**（sources 核实）：它在 `MinecraftServer#tickServer`
**最后一行**发出（`EventHooks.fireServerTickPost`），即 `tickChildren`（所有 level tick 完毕）
之后 —— 读到的是"该世界 tick 结束时的状态"，与 `gameTime` 严格同拍。

由此消除的复杂度：不再需要异步读取管线（`server.execute` + volatile 缓存 + 新鲜度窗口），
`WorldProbe` 只余"下标映射验证 + 一次同步读"。时间戳与取值同源，
"旧值贴新标签"这类问题在结构上不可能发生。

无界面时的快捷键拾取挂在同一个 `ClientTickEvent.Post` 上，为独立监听器（CP2）。

**离线验证的边界**：`ServerTickEvent` 只在游戏内可发，因此这一层的正确性由
`dev-check/` 纯逻辑断言（断点规则 / 聚合 / 标记语义）与实机清单（采样密度、每秒样本数）
共同覆盖。

### CP5 · 数据源失效的检测　风险：中

采样时每 tick 校验三件事，任一失败就不再从菜单取值：

| 检查 | 原因 |
|---|---|
| `hasForeignContainerOpen()` | 玩家关了界面 |
| `containerId == watchedContainerId` | 玩家换了另一台设备（先尝试按坐标重连） |
| `read(slotIndex) != null` | 换了不同类型的菜单，槽位下标越界 |

失败后去哪，取决于状态机：

```
RECORDING ──界面关了，且用户开了"继续检测"且拿得到坐标──► FOLLOWING
          ──其余情况──────────────────────────────────► PAUSED(自动)
```

`PAUSED` 分两种，行为不同，这是刻意设计的：

| manualPause | 来源 | 能不能自动恢复 |
|---|---|---|
| `true` | 用户按了「暂停」 | 不能，必须手动「继续」 |
| `false` | 数据源自己失效 | 能。数据源回来（重开同一台设备）就自动回到 `RECORDING` |

**为什么不直接报错或者自动重连到随便哪个容器**：观测工具不该打断用户操作，
但也不该在认不出来的时候硬记 —— 记录假数据比记录中断更糟。

### CP6 · 性能　风险：低

| 措施 | 说明 |
|---|---|
| 环形缓冲 | 固定容量 36000（半小时），写满覆盖最旧，内存可预测 |
| 只在需要时读槽位 | `IDLE`/`ARMED` 只走一次整数比较 |
| **派生数据按 `version` 缓存** | 数据没变就不重算：全量遍历从 60 次/秒 降到 20 次/秒（CP12） |
| **波形按列聚合** | 采样点远多于像素时不做逐点绘制；每帧复杂度 = O(窗口内采样点数)（CP11） |
| **二分定位窗口起点** | `lowerBound` 找起点，"全览 30 分钟"时不必从第一个点开始扫（CP11） |
| **列聚合数组复用** | 按图表宽度分配一次，不为每帧 new（CP11） |
| 世界侧读值只在服务端线程做 | 现在是**服务端 tick 里的同步读**：既避开数据竞争，又顺带拿到"一个世界 tick 一条"的采样密度（CP4） |
| 不引入图表库 | 只用 `GuiGraphics#fill` + `drawString` |

#### CP6 之一：资源上限与配置

三条"资源上限" + 一条"Probe data 页刷新节拍"，与具体设备无关，所以做成 **NeoForge 客户端配置**
（`LogiScopeConfig`，文件 `config/logiscope-client.toml`，界面在「模组列表 → LogiScope → 配置」）：

| 键 | 默认 | 作用 | 什么时候生效 |
|---|---|---|---|
| `bufferSamples` | **72000** | 每个实例的环形缓冲容量（**样本条数**） | **立刻**：`ProbeManager` 每客户端 tick 检查一次，变了就 `SampleStore#resize`（保留最新样本） |
| `maxProbes` | 16 | 同时运行的实例上限 | 创建新探针时判定（已存在的不动） |
| `maxItemKinds` | 256 | 一个实例最多画多少种物品（每种类含存量线 + IO 线） | 下一次重算派生数据时（即立刻） |
| `dataRefreshTicks` | 20 | Probe data 页自动重发 `data get block` 的间隔（0 = 不刷新） | **立刻**：DATA 页每 tick 读一次；改小时会把过期排期拉回来（不等旧节拍走完） |

> `dataRefreshTicks` 与 DATA 页「status ok」旁的**滑条是同一个值**：滑条改动会 `set + save`
> 写回本文件（本次运行立即生效、下次启动保持）。整个游戏运行实例一份，不随探针隔离 ——
> 它是"我多久看一眼数据"的形态偏好，不是每台设备各自的属性。

**为什么容量按"样本条数"而不是"时长"**：采样节拍 = 世界流速（`/tick rate` 可改），
按时长算容量会随流速漂移，而环形缓冲的容量必须在创建时定死。按条数最诚实：写满就覆盖最旧的。

一个实例里有多种物品时，它们是**共用**这一份缓冲的：
`bufferSamples = 72000`、同时有 3 种物品 → 每种只留约 24000 格历史。
要"每种物品各自 72000 格"就得让容量随种类数浮动，内存会不可预测（256 种 × 72000），刻意不做。

`maxItemKinds` 限的是**画出来的种类数**：超出的种类照样进缓冲、照样进统计，
只是不建线（面板显示 `+N 未显示`）。这样"一个槽位轮换了上千种物品"不会拖垮图例。

**已消除的每帧开销**（防回归起见说明）：`ScopeScreen.render` 不再每帧调 `store.toList()` 与
`Analyzer.summarize`；两者都进 `rebuildIfNeeded`，只在 `SampleStore#version` 变化后执行一次。

**允许存在的小分配**（均已评估为可忽略，不优化）：

| 位置 | 频率 | 量级 |
|---|---|---|
| `SampleStore.toList()` 复制 | 20 次/秒（仅面板开着时） | ≈290 KB/次，年轻代 |
| `layout()` 里的 `wrap`/`place` | 1 次/帧 | 几个小 ArrayList |
| `window()` 返回 `long[2]` | 1 次/帧 | 32 字节 |
| `ChartRenderer` 的读数字符串拼接 | 1 次/帧（鼠标在图上时） | 一个 StringBuilder |

> 判断标准：**每帧的常驻分配为零**，只留下与"数据变化频率"和"用户操作"同阶的分配。
> 帧时间实测的判据：挂机 10 分钟不掉帧。

### CP7 · 纯客户端成立性　风险：低

`@Mod(value = MODID, dist = Dist.CLIENT)` —— 加载器在专用服务端上直接跳过本模组。
配套地，`neoforge.mods.toml` 里的两条依赖都写了 `side="CLIENT"`。

注意：单人模式下集成服务端与客户端在同一个 JVM，所以
`PlayerInteractEvent.RightClickBlock` 会**在两侧各触发一次**，
监听器里必须 `if (!event.getLevel().isClientSide()) return;`。

### CP8 · 不遮挡原界面（GUI 图层栈）　风险：中

**结论：`ClientHooks.pushGuiLayer/popGuiLayer` 操作的是真正的 Screen 栈**（不是 HUD 图层；两者是不同系统，勿混淆 `ClientHooks.guiLayers` 与 `GuiLayerManager`）。

源码证据（`net/neoforged/neoforge/client/ClientHooks.java`）：

``` java
/** Contains the *extra* GUI layers.
 *  The current top layer stays in Minecraft#currentScreen,
 *  and the rest serve as a background for it. */
private static final Stack<Screen> guiLayers = new Stack<>();

public static void pushGuiLayer(Minecraft minecraft, Screen screen) {
    if (minecraft.screen != null) guiLayers.push(minecraft.screen);
    minecraft.screen = Objects.requireNonNull(screen);
    screen.init(minecraft, ...);
}

public static void popGuiLayer(Minecraft minecraft) {
    if (guiLayers.isEmpty()) { minecraft.setScreen(null); return; }
    popGuiLayerInternal(minecraft);          // 只调 removed()，不调 onClose()
}
```

渲染侧（`ClientHooks#drawScreen`，由 `GameRenderer` 在 `screen != null` 时调用）：

``` java
guiLayers.forEach(layer -> {
    drawScreenInternal(layer, gui, Integer.MAX_VALUE, Integer.MAX_VALUE, partialTick);
    guiGraphics.pose().translate(0, 0, 10000);      // 每层往后推，保证层级
});
drawScreenInternal(screen, gui, mouseX, mouseY, partialTick);   // 最顶层用真实鼠标坐标
```

**三个必须知道的细节**：

| 细节 | 含义 |
|---|---|
| 栈里每一层都会被完整绘制 | 容器界面**看得见**，这才是"不覆盖"的来源。用 `setScreen` 替换则完全看不见 |
| 背景层用 `Integer.MAX_VALUE` 当鼠标坐标 | 容器界面的 `hoveredSlot` 永远是 null → **不高亮 hover 的槽位**，键盘侧操作也失效（见下） |
| `pushGuiLayer` **不释放鼠标** | `Minecraft#setScreen` 里才做 `mouseHandler.releaseMouse()`。不手动补一次，界面上根本没有光标，面板点不了也拖不动 |

撤销时机也已经被 vanilla 兜住了：`Minecraft#setScreen` 开头就调
`ClientHooks.clearGuiLayers(this)`，所以任何 `setScreen` 都会把栈清干净，
不会留下悬挂的图层。

#### CP8 之一：把面板外的鼠标转交给容器

背景层拿不到鼠标事件（`minecraft.screen` 是我们），所以**必须自己转发**，
否则"面板打开时机器就不能操作了"。`ScopeScreen` 的转发规则：

| 事件 | 面板内 | 面板外 |
|---|---|---|
| `mouseClicked` | 标题栏拖拽 / 按钮 / 槽位列表 | → `parent.mouseClicked` |
| `mouseDragged` | 拖动面板 | → `parent.mouseDragged` |
| `mouseReleased` | 结束拖动 | → `parent.mouseReleased`（**必须转**，否则容器 quick-craft 状态会卡住） |
| `mouseScrolled` | 翻槽位列表 | → `parent.mouseScrolled` |

为什么点击能工作：`AbstractContainerScreen#mouseClicked` 内部用的是
`findSlot(mouseX, mouseY)`（坐标现算），**不依赖 `hoveredSlot`** ——
这正好绕过了"背景层 hoveredSlot 是 null"的问题。

#### CP8 之二：自己补画 hover 高亮

背景层的格子不会亮，因为 `AbstractContainerScreen` 是在 `render` 里用
传进来的鼠标坐标算 `hoveredSlot` 的，而背景层拿到的是 `MAX_VALUE`。
我们在最顶层、手里有真实坐标，所以自己补画一遍。需要的全是公开 API（已核实）：

``` java
AbstractContainerScreen<?> bg = ...;
int left = bg.getGuiLeft(), top = bg.getGuiTop();          // public（Neo 加的访问器）
for (Slot slot : bg.getMenu().slots) {
    if (!slot.isActive() || !slot.isHighlightable()) continue;   // 与 vanilla 判定一致
    int sx = left + slot.x, sy = top + slot.y;             // Slot.x/y 是 public 字段
    if (命中鼠标) {
        AbstractContainerScreen.renderSlotHighlight(g, sx, sy, 0, bg.getSlotColor(slot.index));
        break;
    }
}
```

`renderSlotHighlight(GuiGraphics,int,int,int,int)` 是 **public static**，
内部只做 `fillGradient(guiOverlay, x, y, x+16, y+16, ...)` ——
**坐标是 pose 相对的**。vanilla 是在一个平移了 `(leftPos, topPos)` 的 pose 里调用它的，
所以我们在没有平移的 pose 里传绝对坐标，效果完全等价。

#### CP8 之三：面板可以推出屏幕

GUI scale 调大时逻辑分辨率可能只有 320 宽，而面板固定 272 宽 ——
不允许推出去就永远挡着容器界面。两条边界规则分工明确：

| 时机 | 规则 | 为什么 |
|---|---|---|
| 拖动中 | 左右至少留 `width/8`（最少 8px）；向下至少留标题栏；上方贴顶 | 必须留一段"拖动条"能抓住 |
| 重新打开 | 越界就贴回屏幕边缘（完全可见） | 防止面板正好压在别的按钮底下再也点不到 |

「拖动中宽松、打开时收紧」是关键：宽松是**功能**（大 GUI scale 下有用），
收紧是**救援**（万一进了死角，一次开合就能脱困）。

#### CP8 之四：手持物品与悬浮提示

**同一个根因的另外两个受害者。** `MAX_VALUE` 那个鼠标坐标不只让格子不高亮，
还让这两样东西一起失效：

| 失效的东西 | vanilla 在哪画的 | 为什么失效 |
|---|---|---|
| 光标上的手持物品（carried item） | `AbstractContainerScreen.render` 末尾，位置 `(mouseX-8, mouseY-8)` | 坐标被算成 `MAX_VALUE - 8` → 画到屏幕外 |
| 槽位悬浮提示（tooltip） | `AbstractContainerScreen.renderTooltip`，条件是 `hoveredSlot != null` | `hoveredSlot` 恒为 null |

两样都在最顶层自己补画：

``` java
// 手持物品：复刻 vanilla 的 renderFloatingItem（它是 private，只能照着写）
ItemStack carried = bg.getMenu().getCarried();          // public
if (!carried.isEmpty()) {
    pose.pushPose(); pose.translate(0, 0, 232);         // vanilla 的 z
    g.renderItem(carried, mouseX - 8, mouseY - 8);
    Font f = IClientItemExtensions.of(carried).getFont(carried, FontContext.ITEM_COUNT);
    g.renderItemDecorations(f == null ? font : f, carried, mouseX - 8, mouseY - 8);
    pose.popPose();
}

// 悬浮提示：按 vanilla 的规矩，手持物品时不显示
if (hoveredSlot != null && hoveredSlot.hasItem() && bg.getMenu().getCarried().isEmpty()) {
    g.renderTooltip(font, hoveredSlot.getItem(), mouseX, mouseY);
}
```

**必须画在最上层**（我们的 render 末尾），因为光标上的物品应该压过面板 ——
这也是 vanilla 的顺序（它最后画这两样）。

**已知差异**：vanilla 在"拖拽逐格分配"时会把数字画成**剩余待分配量**
（`quickCraftingRemainder`，private 字段，读不到），我们只能画完整数量。
只影响按住左键拖过一排格子的那一瞬间显示的数字。

### CP9 · 关掉界面之后继续跟踪　风险：中

```
目标：玩家关掉容器界面去干别的，记录不要断。
```

**坐标从哪来**：vanilla 的 `AbstractContainerMenu` 不保存方块坐标，
`Minecraft#hitResult` 在界面打开期间也不更新。唯一可靠时机是右键方块那一瞬间：
`PlayerInteractEvent.RightClickBlock`（客户端侧确实会触发，见
`MultiPlayerGameMode#performUseItemOn` 里的 `CommonHooks.onRightClickBlock`）。
流程：

```
右键方块 → RightClickBlock 记 pending（带 tick 时间戳，100 tick 内有效）
        → 服务端回 OpenScreen → ScreenEvent.Init.Post 把 pending 提升为 active
```

`ScreenEvent.Init.Post` 是**每个 Screen 初始化都会发**的（`Screen#init` 末尾），
所以要判 `instanceof AbstractContainerScreen`。另外 `Screen#rebuildWidgets`
也会发这个事件，处理时做了幂等保护（容器 ID 没变就不覆盖已有锚点）。

**值从哪来**：主路径是服务端同步读（`WorldProbe` / `DataProbe`）；菜单槽位（`ContainerAccess`）只在服务端读不到时兜底。完整矩阵见 2.4。

"服务端读不到"包括：多人（方块实体不在本进程）、无坐标（vanilla 菜单不存方块坐标、modded GUI 推不出来）、无容器能力（建探针时验证为 UNAVAILABLE）。这时界面是唯一还有内容的地方；界面一关，客户端手里就什么都没有了。时间轴仍然连续，重新打开界面的那一刻会拿到菜单真值 —— **两段之间的差值依然有效**，这正是本功能要的信息。

读取在服务端线程进行：`ServerLevel` / 方块实体属于服务端 tick 线程，客户端线程直读是数据竞争；采样直接挂在 `ServerTickEvent.Post` 上同步读（CP4），不存在异步管道。

#### CP9 之一：三种状态的区分（设计纪律）

时间轴的语义必须区分三件事，任一混写都会在某个方向上撒谎：

| 状态 | 写法 | 画面表现 |
|---|---|---|
| 读不到（区块卸载 / 方块被拆 / 多人读不到） | 写 `no_data` 样本 | 断开 |
| 还没观测到（过渡 tick） | **什么都不写** | 空档（折线连过；缺一格不算断线，见 CP10） |
| 没在记录（用户暂停 / 未启动） | 写 `gap` 标记 | 断开（统计透明） |

两条推论：

- **不垫值**。过渡 tick 写上一 tick 的值会制造 1 tick 假平台；而"缺一格"在断线规则
  （CP10）下本来就不可见，垫值没有收益。（历史实现中的 `lastViews` / `seed` 概念即因此删除。）
- `no_data` 不被藏起来 —— 真正的读失败仍然照记（2.4 第 3 行），实机清单持续盯着这一点。

**认不出来的情况**：右键信息过期 / 目标模组用快捷键开界面 → 锚点为 null
→ 退化成纯运行时身份，关掉界面只能 `PAUSED`。这是刻意的：
**宁可明确停住，也不要往别的方块上记。**

#### CP9 之二：模组机器的世界侧读取（capability 回退）

多数模组机器不实现 `net.minecraft.world.Container`（javap 核实的两例）：

| 模组（版本） | 方块实体 | 实现的原版接口 |
|---|---|---|
| Sophisticated Storage 1.5.20 | `net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity` | 只有模组自己的接口（`IControllableStorage`、`ILockable`…），**没有 `Container`** |
| Mekanism 10.7.17 | `mekanism.common.tile.machine.TileEntityEnergizedSmelter` | 走 `TileEntityElectricMachine` 一族，**没有 `Container`** |

这类机器的容器内容只在界面打开期间由菜单包同步到客户端；界面一关客户端拿不到。
只认 `Container` 的读取会返回 null → 全部记成 no_data。

**第二条读取路径：NeoForge 方块能力。**

```java
// 已核实（neoforge-21.1.209-sources.jar）：
//   Capabilities.ItemHandler.BLOCK : BlockCapability<IItemHandler, @Nullable Direction>
//   ILevelExtension#getCapability(BlockCapability<T,C>, BlockPos, C)   ← Level 实现了它
//   IItemHandler#getSlots() / getStackInSlot(int)
IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
if (handler != null && idx < handler.getSlots()) {
    return ContainerAccess.make(-1, idx, handler.getStackInSlot(idx));
}
```

三条设计决定：

| 决定 | 理由 |
|---|---|
| **Container 优先，capability 兜底** | Container 的下标语义由 vanilla 保证（`getContainerSlot()` == 容器内下标），最准；capability 只在 Container 缺席时启用 |
| **先问 `side=null`，问不到再按六个方向试** | null = "内部视角"，通常是完整库存；有些模组只给面向某一面的分视图。查询命中 NeoForge 的能力缓存，每 tick 七次成本可忽略 |
| **下标不能直接信 `getContainerSlot()`** | 对 NeoForge 的 `SlotItemHandler`，它把 handler 下标传给 `super`，`getContainerSlot()` 恰好就是 handler 下标；**但 Mekanism 的 `InventoryContainerSlot` 构造是 `super(emptyInventory, 0, x, y)` —— 下标硬编码 0**（javap -c 核实），且它包装的 `IInventorySlot` 是单槽接口、不带位置信息，客户端**拿不到**真下标。解法见 CP9 之三：建探针瞬间在服务端做内容指纹验证 |

> 反查方法：`javap -p -cp run/mods/<mod>.jar <BE全类名>` 看它 implements 什么；
> 再在 jar 里按 `capabilit|ItemHandler` 搜类名，确认它走哪条能力注册路径。

#### CP9 之三：下标映射验证

模组机器的槽位下标不可信（见上表第三行），解法是**验证，而不是逐模组适配**：

```
挂探头的瞬间（GUI 开着，菜单内容与服务端同步）：
  服务端线程做一次"内容指纹匹配"：
    1. vanilla Container        → getContainerSlot() 直接可信（vanilla 保证）
    2. 拿菜单槽当前内容去 capability handler 里找：
         物品+数量 唯一命中     → 那个下标就是真下标（VERIFIED）
         仅物品     唯一命中    → 也算（容忍挂探头瞬间 1 tick 的同步差）
         命中 0 个或多个      → UNVERIFIED（宁可明说，也不瞎猜）
    3. 既非 Container 也无 handler → UNAVAILABLE（关界面必然读不到）
  结果存 volatile，readNow 每次读取都用验证过的下标。
```

**为什么匹配只在挂探头这一刻做一次**：下标是结构性信息，内容只是用来"指认"它；
之后内容怎么变都不影响。而"GUI 开着"是唯一能同时看到两侧内容的时机窗口。

**验证不了时的行为**：读取退回该格自己的 `containerSlotIndex` 并如实记录，同时在记录期间
弹一次动作栏警告（`logiscope.msg.index_unverified`，每探针一次）。警告判据是
**同一清单里容器下标重复**（与 `SlotAlign.containerIndexReliable` 同一不变式：重复的下标
不是身份）；不能拿菜单位置（`menuSlotIndex`）当判据 —— 它天然唯一，警告永远不会触发。
典型歧义场景：对着**空格子**建探针、机器里多个格子装着同种等量的物品。

---

### CP10 · 把时间序列变成「多条按物品分组的数据线」　风险：中

```
需求：槽位里的东西会变。铁锭 → 空 → 铜锭 → 铁锭 这样一路变下来，
      不能画成一条连来连去的折线（那些连线毫无意义），
      而且要"每种物品一条自己的线、各有各的颜色，图例可以单独关掉"。
```

#### 先定一个容易搞反的分工：**分组是派生视图，不是存储结构**

直觉上"出现新物品就新建一个数组"很自然，但那样会带来三个麻烦：

| 麻烦 | 具体是什么 |
|---|---|
| 环形覆盖 | 写满时要跨 N 个数组协调"该丢哪一段最旧的"，很容易写错 |
| 清空 / 查询 | "清空"要遍历所有数组；"某一刻这一格里到底是什么"反而要反查 |
| 时间轴 | 多条数组之间没有统一的时间轴，画图前还得先归并一次 |

所以这里选的另一条路：**扁平的环形缓冲（`SampleStore`）仍是唯一真相**，
按物品分组的 `ChartData.series()` 是它的**派生视图**，数据一变就整体重算。

代价是"每次重算都是 O(n)"。用 `SampleStore#version` 把它压到**每 tick 最多一次**
（而不是每帧一次，见 CP12 的缓存），n 最大 36000（半小时），
一次全量遍历是零点几毫秒 —— 换来的是"顺序、断档、清空、环形覆盖全都自动正确"。

#### 断点规则：什么时候必须把线剪掉

统一落到 `SeriesPoint#breakBefore` 一个标记上，渲染层只管照着断开。
规则是**"有没有观测到"**，而不是**"采样间隔是多少"**：

| 情况 | 判据 | 为什么必须断 |
|---|---|---|
| 中间夹了标记 | 自上一个数据 tick 以来见过 `no_data` 或 `gap` | 那段区间我们**没观测**（读了读不到 / 没在记录），连起来等于编造数据 |
| 这条线的上一个点**不在上一个数据 tick 上** | `b.lastTick != prevDataTick` | 那个 tick 里被监控的格子没有这个物品（被抽空了 / 换成别的），再连上就是假的连续 |
| 静默大空档兜底 | 间隔 > `MAX_SILENT_GAP`（20t） | 万一路径漏写标记也不让线横穿空白；正常路径碰不到它 |

契约：**每条线的第一个点一定是"段起点"**（`breakBefore = true`）。
它前面没东西可连，所以这是无害的；渲染层靠 `colStart` 决定"要不要连到左邻列"，
而首列本来就没有左邻列。

> **两条已否决的判据**（保留记录，防止回退）：
>
> **① 「上一个样本换物品就断」——断点判定必须逐线（per-series）**
> 该判据只在"一个 tick 只有一条样本"时成立；多格监控下同一 tick 会有多条不同物品的样本
> （见 CP16），用全局 prev 判会把第二条样本误判为"物品切换"
> → 每条线在每个 tick 都断开 → 波形碎成孤立点。
>
> **② 「采样间隔 > 1 就断」——断线只看显式标记**
> 该判据的前提是"采样恒定每 tick 一条"，仅在时间戳取自客户端计数器时成立；
> 时间戳换成服务端权威 gameTime（CP15）后前提不成立：
>
> | 事实 | 后果 |
> |---|---|
> | 客户端每秒最多观测 20 次，服务端每秒也走 20 tick，**两者不锁相** | 一次帧抖动（GC / 区块生成 / 卡顿）即让某个客户端 tick 看到 +2 的世界 tick |
> | 客户端看到 +0 时按"世界没走 tick 就不记"跳过 | 下一个样本的 tick 跳 2 —— **这一格只是没观测到，两侧数据都是真的** |
> | 上述规则把"间隔 2"判成断档 | 20t 流速下出现偶发假断线 |
> | `/tick rate 200` 时样本天然每 ~10t 一个 | 每个点都被判成断点，整条线成为孤立点串 |
>
> 现行规则下"观测稀疏"只表现为点少，折线保持连接；需要断开的两种情况
> （读取失败 / 没在记录）本来就有显式标记，无需靠数间隔推断 —— 这正是它更准确的原因。

`ChartData` 还给出 `switches`（面板上显示成 `switch N`），口径为：**按 tick 比较"物品集合"**，
不比较相邻样本 —— 多格时后者会算出"每 tick 都切换"，数字虚高且无意义。
（实现 `countItemSetSwitches`，只多一次 O(n) 扫描，仅在 rebuild 时执行。）

#### 配色：按**下标**取色，而不是按名字哈希

`series` 的顺序 = 物品**首次出现**的顺序（`LinkedHashMap` 保证），
所以"第几条线用什么颜色"是稳定的 —— 不会因为中途冒出一个新物品，
旧线就集体换色。这是"按下标取色"而不是"按 itemId 哈希取色"的原因：
后者在调色板轮转时也可能撞色，前者至少保证了"先出现的线颜色固定"。

`minecraft:air`（格子空着）单独给一个低饱和灰蓝：它不是物品，不该和物品线抢注意力，
但它**仍然是一条正经的线**（数量恒 0）—— 丢掉它就看不出"这一格被抽空了"。

---

### CP11 · 波形渲染：坐标轴、自动缩放、尖峰、稀疏数据　风险：中

```
需求：一眼看懂。要有坐标轴和刻度；采样点比像素多时尖峰不能被平均掉；
      纵轴自动缩放；no_data 是空白断档；鼠标框选。
```

#### 1. 纵轴自动缩放要"吸附到整数刻度"

直接用 `[min, max]` 会有两个毛病：刻度落在小数上读不出来；
而且窗口一滚动范围就每帧变，数字不停抖。

做法：按 `(max-min)/4` 求一个 nice step（1/2/5/10/20/50/100… 阶梯），
再把上下界吸附到它的整数倍。这样刻度落在整数上，**范围只会偶尔跳一次**。

> 仍然会有跳变（这是自动缩放的固有代价），但频率从"每帧"降到"过一会儿一次"。

#### 2. 稠密数据：按**列**取极值，不是取平均

一个像素列里可能落着上百个采样点。取平均会把"一瞬冲到 64 又落回 0"抹平 ——
而那恰恰是物流系统最值得看的东西。所以每列记录 `min/max/first/last`，
画的时候画**从 min 到 max 的竖线**，再把上一列的 `last` 连到本列的 `first`
（数据是台阶状的，跳变就发生在这条竖线上）。

#### 3. 稀疏数据：列与列之间**线性插值**

反过来，缩放到很近时一个点占一列，中间会隔着很多空列 ——
不补的话折线就退化成一串孤立的点。所以同一段之内，空列用首尾线性补上。

**这两条不冲突**，因为它们判定的对象不同：

- "中间隔了几列" → 说明采样点稀疏 → 补
- 而断档不会走进来 —— 断档会让下一列的 `colStart = true`，直接跳过连接

#### 4. `no_data` 的列什么都不画

断档就该看得出来是断档。图上留白，比画一条 0 的线诚实。

#### 5. 每帧复杂度 = O(窗口内采样点数)，且零常驻分配

- 列聚合数组（`colMin/colMax/colFirst/colLast/colHas/colStart`）**按图表宽度复用**，
  只在宽度变化时重建 —— 不是每帧 new
- 每个 series 的点是**按 tick 有序**的 → 用二分（`lowerBound`）找窗口起点，
  于是"全览 30 分钟数据"时不必从第一个点开始扫
- 画线只用 `GuiGraphics#fill`（逐列 1px 竖直矩形）。它没有画线 API，
  但"逐列竖直矩形"正好和"按列聚合"是同一个形状 —— 两件事合成一次遍历

> 这是"挂机 10 分钟不掉帧"的依据所在；实测确认见 `VERIFY-HISTORY.md`。

**一个已知的、故意不修的边界**：若在同一个像素列里发生了物品切换（要求缩放到
极远 + 恰好切换），旧颜色会在这一列多画一小截。列宽 1px，肉眼不可见，
不值得为它把聚合逻辑复杂化。

#### 实现注意：`fill` 的颜色必须带 alpha 位

`GuiGraphics#fill` 的颜色写成 6 位十六进制（alpha = 0）时画出来完全透明，且没有兜底 ——
症状是"文字都在、图形全没"，极易被误判为逻辑 bug 而非颜色 bug。

| API | alpha=0 时的行为 |
|---|---|
| `GuiGraphics#fill` / `blit` | **完全透明，什么都看不见**。没有兜底 |
| `drawString`（`Font#drawInBatch`） | `Font.adjustColor`（Font.java:172）会兜底：`alpha 位 == 0 → 补 0xFF`，所以文字照样可见 |

因此本项目所有 fill 颜色统一写满 8 位（如 `0xFF5DCAA5`），并在调色板常量上注释防回归。

---

### CP12 · 视图状态：实时 / 冻结 / 框选 / 图例　风险：低

```
需求：波形实时滚动；能用鼠标框选一段；图例能开关数据线；面板能缩放。
```

#### 规则只有一条，好记

| 动作 | 结果 |
|---|---|
| 拖出一段选区 | **自动冻结视图**（否则波形继续往前滚，刚框住那段几秒后就跑出屏幕） |
| 点「实时滚动」 | 回到实时滚动 + **清掉选区**（两者不并存） |
| 点「清选」 | 只清选区，**不解冻**（要实时就自己点「实时滚动」） |
| 点「全览」 | 冻结 + 窗口 = 整段记录 |
| 滚轮（实时） | 缩放，右端锚定（最新数据必须一直在视野里） |
| 滚轮（冻结） | 左右平移（回头细看某一段） |
| 滚轮在**面板外** | 转交容器（CP8 之一） |

框选期间额外**锁住窗口**（`selectWindowStart/End`）：不锁的话波形会跟着手跑，选不准。
松开手时按"锁住的那个窗口"把像素换算成 tick —— **不能重算窗口**，
否则松手那一帧窗口已经变了，选框会跳一下。

#### 实现注意：先取窗口，再置状态位

`beginSelection` 中顺序必须**先取窗口、后置 `selecting`**：

```java
long[] win = window();   // ✅ 先取窗口 —— window() 会按 selecting 分支返回不同结果
selecting = true;        // 再置位
selectWindowStart = win[0];
```

若反过来先置位再调 `window()`，首次框选时 `selectWindowStart/End` 还是初始值 0，
`window()` 会读到**正要写入的旧值**，窗口被锁成 `[0,0]`，波形瞬间变成"这一区间没有数据"，
且永远进不了冻结态（连带的假象是「实时」按钮"点不动"）。

> 通用原则：**给"读状态的函数"加分支时，先检查所有调用点是否存在"边改状态边调它"。**
> 这类症状（按下即显示无数据）离根因（交互层顺序）很远，排查成本高。

#### 布局只有一份

`ScopeScreen#layout()` 产出这一帧的全部矩形（含每个按钮/图例块的位置），
**渲染与鼠标命中共用同一份结果**。禁止"渲染按一套坐标、点击按另一套坐标" ——
这类错位是最难排查的一类 bug（按钮画在那里却点不到）。

面板尺寸与位置、以及"哪些数据线被图例关掉了"都是**静态字段**（跨开合保持）：
它们是使用偏好，不是数据。但**探针换了目标时会清空隐藏集合**
（换了一套数据线，上一套的隐藏记录没有意义），
且会**丢掉已经不存在的数据线**的隐藏记录（`pruneHidden`），
否则某个物品消失又出现时会莫名保持隐藏，玩家找不到原因。

#### 派生数据的缓存：`version` 而不是"每帧重算"

`SampleStore#version` 在任何 `add` / `clear` 时 +1。界面 `rebuildIfNeeded` 时
对比版本号，只有变了才重算 `ChartData` + `Analyzer.Summary`。

效果：60 fps 下的全量遍历从 **60 次/秒** 降到 **20 次/秒**（采样频率），
而且改动只集中在一处 —— 不会出现"某条路径忘了通知界面刷新"的漏网情况。

> 取舍说明：代价是 `toList()` 会真的复制一份（最多 36000 个引用 ≈ 290 KB），
> 20 次/秒 ≈ 5.8 MB/s 的年轻代垃圾。对 JVM 完全无压力，
> 换来的是 `ChartData` 保持纯函数形态（可离线测试，见 `dev-check/`）——
> 比省这点内存值得。

---

### CP13 · 时间刻度：世界 tick　风险：中

样本时间戳必须使用**世界 tick**。`/tick rate` 只改变服务端 tick 频率，客户端 `ClientTickEvent` 恒为每秒 20 个；以客户端节拍（自增计数）作时间戳会与世界完全脱节（流速调慢时出现大量"世界未动的重复值"）。

实现约定：时间戳取 `Level#getGameTime()`（public，已核实），且**世界没走 tick 就跳过采样**（gameTime 与上次相同 → 不写重复样本）：

| 场景 | 行为 |
|---|---|
| `/tick rate 5` | 世界每 200ms 走 1 tick → 每 4 个客户端 tick 里 3 个跳过，每世界 tick 恰好一条样本 |
| ESC 暂停（单人） | 服务端冻结 → gameTime 不动 → 不采样（不产生重复平线） |
| 主菜单（无世界） | 退回客户端节拍（`mc.level == null`） |

连带统一：世界侧读取的时效判定使用同一刻度（世界没走 tick 时缓存不算陈旧 —— 它本来就是"当前"的）；统计块速率单位为 **n per tick**（`fmtRate`），与横轴单位一致。

**保留客户端节拍的地方**：动作栏去抖、快捷键连发去抖 —— 它们需要真实时间。

---

### CP14 · 缩放交互：光标锚定　风险：低

冻结态的缩放交互约定：

| 规则 | 说明 |
|---|---|
| 滚轮 = **光标锚定缩放** | 光标处的 t 值保持原位、两侧缩放（同视频编辑软件的时间轨）；右端钳到最新值的"平移式"行为已否决 |
| 缩放步长 | 滚轮 1.25×/格、按钮 1.5×/次；`MIN_WINDOW = 20`（避免步长与下限相撞导致"一格就到头"） |
| `userZoomed` 标志 | 用户主动缩放过即忠实反映 `windowTicks`；"起点钳到第一个数据点"只在从未缩放时生效（attach / 清空 / 全览复位） |

锚点约束满足不了时，以数据端点为准：窗口推出数据边界 → 贴起始/结束端点；冻结态窗口上限 = 全览（= 数据跨度）；实时态保持右端锚定（最新数据必须在视野里）。

---

### CP15 · 时钟：权威世界 tick 的来源　风险：中

样本时间戳的权威来源是服务端 `ServerLevel#getGameTime()`（经 `ServerClock` 交接）。客户端 `Level#getGameTime()` **不可用作样本时钟** —— 它是一条错拍的阶梯：

| 事实 | 出处 |
|---|---|
| 客户端每客户端 tick 把本地 gameTime **+1** | `ClientLevel#tickTime()`（受客户端 `tickRateManager` 控制） |
| 服务端每 **20 个服务端** tick 才发一次绝对时间包同步 | `MinecraftServer#tickChildren`：`tickCount % 20 == 0 → synchronizeTime` |

- tick rate 20：本地 +1 恰好等于同步值 → 无缝（问题被掩盖）。
- tick rate 40：世界每客户端 tick 走 2，本地只 +1，且每 10 个客户端 tick 被 +20 纠正 → 阶梯平台期被"世界没走 tick"判定吞掉（成段空档），跳变时一条样本横跨 20 个世界 tick（速率全错）。
- tick rate 200：每 2 个客户端 tick 被 +20 纠正 → 采样 20t 一次 + 重复值。

实现：`ServerClock` 在单人下每客户端 tick 向服务端线程提交一次读取（`ServerLevel#getGameTime()` → volatile）；权威值随 `/tick rate` 正确伸缩、ESC 冻结时同步冻结。它现在只服务**状态机**与**回退路径**（多人 / 无坐标的菜单采样）；主路径的样本时间戳直接取服务端 tick 那一刻的值（CP4）。多人 / 主菜单无集成服务端，退回客户端值。

**物理极限（记录在案）**：客户端每秒最多观测 20 次。回退路径下 tick rate 200 时样本间隔 ~10t 属采集端极限，不是缺陷；累计值与 n/t 换算仍正确。

#### CP15 之一：观测相位与采样点位置

时间戳取自服务端权威值后，客户端看到的 tick 序列不再是"每 tick 恰好 +1"：

```
客户端 tick     N    N+1   N+2   N+3   N+4 ...
看到的世界 tick 100   101   101   103   104 ...     ← +0 与 +2 都会出现
```

- **+0**：世界该 tick 没走（`/tick rate` 调慢、卡顿）→ 按设计跳过采样（CP13）。
- **+2**：两个客户端 tick 之间服务端走了两格 —— **中间那格客户端没有机会观测**。本质是"客户端每秒最多观测 20 次、服务端每秒走 20 格（还能更快）"，两者节拍不锁相。

对观测空档，画图曾以"容忍"处理（断线只认显式标记，见 CP10）；最终解法是**把采样点搬到世界 tick 上**（CP4）：客户端看不到的格，服务端自己知道。

| 维度 | 客户端侧观测 | 世界 tick 上采样 |
|---|---|---|
| 每秒次数 | 20（客户端 tick 上限） | = 世界流速（20 / 40 / 200…） |
| 漏格 | 帧抖动即漏 | 不可能漏（每 tick 一次） |
| 时间戳与值的一致性 | 异步读值与时间戳可能差一格 | 同线程、同时刻读出 |

`MAX_SILENT_GAP`（CP10 的兜底）因此降为纯防御措施：主路径时间轴是密的；回退路径（多人 / 无坐标 / 无容器能力）按客户端节拍观测，仍可能有观测空档，由断线规则兜住。

### CP16 · 探针实例化 + 多格聚合　风险：中

```
需求：面板里要有"正在运行的探针"列表（每行带删除按钮）；一个探针可以同时监控好几格，
      记录结果是把它们加在一起。
```

#### 1. 为什么拆成 ProbeManager + ProbeInstance 两层

多探针**同时**运行需要两层分工：

| 类 | 职责 | 生命周期 |
|---|---|---|
| `ProbeManager` | 实例表、创建 / 删除、活跃指针（界面在看哪一个）、每 tick 驱动所有实例、`followAfterClose` 全局偏好 | 整个游戏会话一个 |
| `ProbeInstance` | 一个探针的状态机、自己的 `SampleStore`、自己的 `WorldProbe` | 创建时生、删除时灭 |

**所有实例同时采样，而不是只运行"当前在看的那一个"**：语义是"正在运行的探针"，
切回来应当看到离开那段时间的数据。代价是每个实例各占一份环形缓冲，
实例上限由 `maxProbes` 控制（默认 16）。

#### 2. 哪些状态必须"每实例一份"，哪些必须"全局唯一"

这是这次重构最容易写错的地方，独立列出来：

| 状态 | 归属 | 理由 |
|---|---|---|
| `SampleStore` | **每实例** | 各有各的记录 |
| `WorldProbe`（读值缓存 / pending / 下标验证） | **每实例** | 共享的话两个探针会互相覆盖读数 —— 这是最隐蔽的一类 bug（数据看起来"偶尔跳一下"） |
| `watchedContainerId`、`manualPause`、`lastUsedReadSeq`、`warnedUnverifiedIndex` | **每实例** | 状态机私有；`lastUsedReadSeq` 是"上一次写成样本的观测身份"，共享会让两个探针互相吞掉对方的观测 |
| 客户端 tick 计数、权威世界 tick（`ServerClock`） | **全局唯一** | "现在世界走到第几 tick"全世界只有一个答案；留在实例里会导致删掉一个实例就把时钟清掉 |
| `followAfterClose` | **全局唯一**（在 Manager 上） | 它是用户偏好，不是探针状态 |

#### 3. 多格 → 按物品分别求和

一条 `Sample` 只能表示「一个物品 id + 一个数量」，而多个被监控的格子可能装着不同的东西。
现行口径是**按物品分别求和**（实现见 `analysis/Aggregator`，断言见 `dev-check/TestChart.java`）。
曾有的"混合物品"方案（把不同物品合成一条 `logiscope:mixed` 线）已否决 ——
它把一台机器里几种物品的物流信息压成一条线，丢失了分析工具最重要的维度：

| 两个被监控的格子 | 这个 tick 写几条、内容是什么 |
|---|---|
| 都是 64 铁 | 1 条：铁 × 128（"多格一起计算"） |
| 64 铁 + 32 铜 | **2 条**：铁 × 64、铜 × 32（图上两条不同颜色的线） |
| 都是空 | 1 条：`minecraft:air` × 0（那条灰蓝的"被抽空了"线） |

即：**"多格"只体现在"同种物品的数量相加"上**，物品维度完全交给"按物品分线 + 每种一条颜色"的既有机制。

**结构影响：一个 tick 会有多条样本。** 契约从"每 tick 一条"升级为"**每 tick 每条物品一条**"
（共用同一个 tick 值），落点有二：
1. `ChartData#build` 的断点判定必须是 **per-series**（见 CP10）
2. `Analyzer#summarize` 先按 tick 合计再统计（面板 `min/max/avg` 仍是"总存量"）

**代价**：环形缓冲容量按**样本条数**计。监控 3 格、3 种物品时，一个世界 tick 占 3 条
→ 半小时的容量实际覆盖 10 分钟。刻意取舍：按时间计容量需随"当前物品种数"浮动，反而不可预测。

#### 4. 读取失败时：**任何一格读不到，整条记 no_data**

聚合值是"所有格子的和"。少读一格 → 和偏小 → 图上的总存量静默偏小，
而这种偏小在波形上**完全看不出来**（它看起来和正常的下降一模一样）。
相比之下 `no_data` 是**看得出来**的断档。两害相权，宁可断档。

#### 5. 目标的形态（`ProbeTarget`）

```
ProbeTarget(List<SlotRef>, deviceName, dim, pos)
SlotRef(menuSlotIndex, containerSlotIndex, itemId, count)
```

`menuSlotIndex` 可为 **-1**：准星创建的"无 GUI 容器"没有菜单，只能走世界侧读取。
`ProbeTarget#hasMenuPath()` 即用于状态机判定"能否走菜单兜底路径"。

#### 6. 无 GUI 容器的槽位清单（`ProbeSlots`）

可视化选格依赖"菜单槽在屏幕上的位置"，而菜单只在界面开着时存在。
无 GUI 的设备连"有几格、每格装着什么"客户端都不知道，只能由服务端线程读出来
（`Container` 或 `ItemHandler` 能力），再交回客户端
（`server.execute` 提交、结果写进 volatile —— 这是一次性的配置动作，不是每 tick 的采样）。

`ProbeSlots` 用静态单例是**故意**的：同一时刻只可能有一个"正在配置的探针"
（面板是单例界面）。这与 `WorldProbe` 必须每实例一份正好相反。

---

### CP17 · 可视化选格 + 准星入口　风险：高

```
需求：点「选择容器格」后光标禁用物品交互，只用于选格；左键点/划过的选中、右键取消；
      没有容器界面时按快捷键，要看准星指着什么。
```

#### 1. cellMode 的两条铁律

| 铁律 | 不这么做会怎样 |
|---|---|
| 面板外的 `mouseClicked / mouseDragged / mouseReleased / mouseScrolled` **一律吃掉，不转发给被压住的容器界面** | 点一格会顺手把物品拿起来（甚至丢出去），"只用来选格"就成了空话 |
| 拖动必须**按线段采样**（每 4px 取一点），不能只判当前位置 | 鼠标事件按帧来，快速划过会**漏格**，而"划过的都被选中"是需求明写的 |

选格集合维护的是**菜单槽下标**（`Slot#index`），不是容器内下标 ——
因为 MEK 那类模组的 `getContainerSlot()` 恒为 0（CP9 之二），
用容器下标做键会让"选了 1 格"变成"选中了全部"。容器下标那份集合是**派生**的
（菜单开着时每次从菜单重算），供"容器界面中途关掉"之后继续用。

#### 2. 覆盖层必须画在面板**之前**

`render()` 里的顺序是：补画背景 hover → **选格覆盖层** → 面板底 → 标题/内容 → 手持物品。
覆盖层放在面板之前，是因为容器格子可能落在面板底下（面板是可拖的浮层），
反过来的话选中框会画到面板上面去。

#### 3. 准星入口：只有服务端能开菜单 + "有没有界面"怎么判

需求第 3 条要"准星指着有 GUI 的容器 → 打开 GUI，并打开探针 GUI"。
这里有个硬约束：**只有服务端能开菜单**（`ServerPlayer#openMenu` +
`ClientboundOpenScreenPacket`）。纯客户端模组没有别的合法通道，只能
**模拟一次真实的右键**（`MultiPlayerGameMode#useItemOn`）—— 这正是 vanilla 右键走的那条路。

##### 3.1 "有没有界面"不能用静态判据

`BlockState#getMenuProvider` 不可靠 —— javap 反查的覆写情况：

| 方块 | 覆写 `getMenuProvider`？ | 界面从哪开 |
|---|---|---|
| 原版 `ChestBlock` | ✅ 覆写（`SimpleMenuProvider`） | `getMenuProvider` + `openMenu` |
| 原版 `AbstractFurnaceBlock` / `BarrelBlock` | ❌ **不覆写** | `useWithoutItem` 里直接 `player.openMenu(...)` |
| 精妙存储 `ChestBlock` | ❌ 不覆写 | 同上（客户端直接 `return SUCCESS` 做预测） |
| Mekanism `BlockTile` | ❌ 不覆写 | 同上（客户端按 `HasGui` 属性返回 SUCCESS / PASS） |

连原版熔炉也不覆写它，模组机器更不必说。判据改为问"这个方块右键会不会**消费**这次交互"：

```java
// ProbeContext#wouldConsumeClick —— 直接在客户端调一次，不发任何包
level.getBlockState(pos).useWithoutItem(level, player, hit).consumesAction()
```

容器方块为做客户端预测**必须**在客户端回答这个问题，因此比 `getMenuProvider` 可靠得多。
`getMenuProvider` 降级为**快速肯定判据**：非空即直接算"有界面"，省一次预测。

##### 3.2 判定为"无界面"时不发交互

若"先发一次右键再看结果"，`performUseItemOn` 在方块 use 返回 PASS 后会继续走到
`itemstack.useOn(...)` —— 手里拿着扳手对着"没有界面的机器"按 F9 时，那一步会真的
改动机器。直接问一次、不发包，即可完全不触碰物品逻辑。

##### 3.3 跨 tick：等服务端把界面交出来

```
按 F9 → 准星是容器 → 问一次"会不会消费"
                      ├─ 不消费 → 不发任何包，直接进创建流程（列表选格）
                      └─ 消费   → useItemOn() 发出交互，记 PENDING_OPEN（40 tick）
       1~3 tick 后，服务端开界面 → ScreenEvent.Init.Post
                              → 先把 pending 提升为锚点（ContainerAnchor，原有逻辑）
                              → 再 pushGuiLayer 把面板压上去（根页）
       40 tick 内一直没界面     → 兜底当作"没有界面" → 进创建流程
```

**40 tick 过期**是必须的：服务端如果一直没开界面（太远 / 被拒，
或者那台机器"消费右键但不是开界面"），标志挂在那里会导致用户
**下一次正常开任何容器时面板自己弹出来**。

**副作用防线（保留）**：`performUseItemOn` 的顺序是
`RightClickBlock 事件` → **`itemstack.onItemUseFirst`** → 方块 use → 物品 use。
第二条是"物品抢在方块前面"。所以发交互之前先**反射只读**物品类的
`onItemUseFirst(ItemStack, UseOnContext)` 的**声明类**：是原版 / NeoForge 的
（即没覆写）才发；否则不发，改用面板提示用户空手再试。
`getMethod` 对"没覆写"的情况返回接口 `IItemExtension` 上的 default 方法，
所以判断的是包名前缀而不是具体类。

> 注：像 Mekanism 扳手那样**只覆写 `useOn`**（不覆写 `onItemUseFirst`）的工具，
> 对"有界面的方块"是安全的（方块 use 先消费掉，轮不到物品）；
> 只有对着"无界面但消费右键"的机器才可能生效 —— 见下面第 4 节的说明。

#### 4. 准星判定的四条分支

| 准星 | 判定 | 结果 |
|---|---|---|
| 不是容器方块实体（`Container` / `ItemHandler` 都没有） | 不可探测 | 打开**根页**；**一个包都不发**（所以对拉杆、门、按钮绝无副作用） |
| 是容器 + `getMenuProvider` 非空 | 有 GUI（快速肯定） | 模拟右键开界面 → 界面一开就压面板（**根页**） |
| 是容器 + "会消费右键" | 有 GUI | 同上 |
| 是容器 + "不消费右键" | 无 GUI（机械手这类） | **不发交互**，直接新建探针 + 进选格页（**列表选格**，可视化选格按钮置灰） |

> 设计解读（需求原文："若是且有GUI，则打开GUI，并打开探针GUI"；无 GUI 时"自动进入…创建流程"）：
> **有 GUI 的那支落在根页**，不自动进创建流程。若要改为"有 GUI 时也直接进创建流程"，
> 改 `KeyBindings#onScreenInit` 里 `openOverlay(...)` 的 `Page` 实参即可。

#### 5. 列表筛选框（第十九轮①）

多容器格时（AE 终端一页 90 格、大箱子 54 格）列表本身就是个"找东西"的问题，
所以列表顶上有一条输入框（`Layout#filterTop/filterH`，与列表同一份几何）。

**规则在纯逻辑层**（`analysis/PickerFilter`，离线断言 t41）：

| 查询 | 匹配方式 |
|---|---|
| （空 / 纯空白） | 全部显示 |
| `iron` | 子串，大小写不敏感；匹配物品 id / 短名 / AE 显示名 / `#N` 文本 |
| `#1` | **序号数值精确相等**（`#1` 不命中 `#10`；`#007` 算 7） |
| `#1 iron_ingot` | 空白分词，**每个词都要命中**（与） |

**不做整串子串匹配**：显示文本是 `#1  iron_ingot x32`（两空格 + 数量段），
用户按需求原文敲的是单空格 `#1 iron_ingot`，整串比会全都匹配不上。

**契约**：
- 渲染、点击命中、翻页三处共用 `pickerVisible()` —— 与存档筛选框同一条纪律
  （"看到的行 = 点的行"）。
- **「忽略空槽」开关**（第十九轮③，`pickerIgnoreEmpty`）：筛选行右端一个按钮，
  默认关、跨开关面板保持（静态字段，属"看列表的偏好"）。它与文字筛选是**与**关系：
  先剔空行再匹配。两者都只影响显示。
- 筛选**只影响显示**：已勾选的格子、选择计数、「完成」提交的内容都不受筛选影响。
- 没有可筛清单（`source == NONE` / 清单为空）时那一行**不占位**，
  还给"先把设备界面打开"这类提示。
- 焦点语义与另外两个输入框（存档筛选 / 存档重命名）完全一致：离开页面自动清焦点，
  否则键盘会被永久吃掉（那个坑已经踩过）。

---

### CP18 · 单 tick IO（净流量）与它的可测边界　风险：中

```
需求：用 64/t 的管道往里输入、又有一条 64/t 往外输出 —— 存量是不变的，
      但进和出的流量都存在；有时候速率还会变，我要这部分数据。
```

#### 数据怎么来的：**从存量算，不另采一份**

`flow(tick) = 存量(tick) − 存量(tick−1)`，只在**相邻两格**之间算（CP10 的 `Series#flow`）。
不另采一份的四个好处：与存量天然对齐、不占缓冲、不用改采样层、离线可断言。

#### 边界：**同 tick 内一进一出会抵消，从外部看不出来**

这不是偷懒，是观测方式决定的 —— 值的来源是"**每个世界 tick 读一次方块实体的内容**"（CP4）。
我们能看到的只有**每一格结束时的存量**：

```
世界 tick:      T          T+1         T+2
机器实际:  +64 / −64   +64 / −64   +64 / −64     ← 一进一出，都在同一 tick 内发生
我们看到的:   1000       1000        1000         ← 净变化恒为 0
```

进出发生在同一 tick 内、互相抵消之后，**"进多少/出多少"这个信息没有留在任何一次观测里**。
这不是采样频率的问题（把采样提到一 tick 多次也一样：一 tick 内的中间态根本读不到），
而是**可观测量的边界**：从外部只能看见存量，看不见流向。

**能看到什么**（都是真实有效的）：

| 场景 | 存量线 | IO 线 |
|---|---|---|
| 单向流入（机器只往里放 / 只往外抽） | 上升/下降 | **±n/t，就是真实吞吐** |
| 进出都发生、但**不同 tick** 或**成批交替**（脉冲式管道） | 台阶状 | 交替的正负脉冲，能看出节奏与批量 |
| 一进一出**完全同 tick 且抵消** | 平 | 0（**这就是边界**，不要当成"没有流量"） |

**想要"同 tick 一进一出的真实进出量"该怎么办**（都不在当前范围内，列出来备选）：

| 办法 | 说明 |
|---|---|
| **监控两端容器**（零代码） | 上游容器掉 64/t、下游容器涨 64/t —— 各自的 IO 立刻可见。这也是目前最贴近用户目的的做法 |
| 挂钩容器的变更回调（需要 mixin） | 在每次 `insertItem` / `extractItem` / `setChanged` 前后读一次内容，就能拿到**逐次**的进出量。代价：要给每个模组机器的物品实现单独挂钩（原版 `Container`、NeoForge `ItemStackHandler` 能覆盖一批，MEK / 精妙存储这类自写实现的覆盖不到），且没覆盖到的机器会**静默显示 0** —— 与"宁可留空也不给假值"的原则冲突，所以没有直接做 |
| 让被监控的机器自己报数（per-mod 集成） | 最准，但每个模组都要单独适配 |

#### 界面上怎么用

- **图例两级**：图例条上**一个物品一格**；点它弹出
  「数量 / IO」两项的小菜单，分别控制存量线与 IO 线（默认 IO 关）。
  IO 线颜色用同色系**浅一档**（`ChartRenderer#flowColor`），一眼能对上是谁的流量。
  (empty) 只有「数量」一项 —— 空格子没有进出，IO 对它没有意义。
- **纵轴自动缩放会把可见的 IO 一起算进去**：存量 1.79E6、IO ±64 时两者量级差太远，
  把父格（存量线）关掉，轴就会自动缩放到 IO 自己的范围 —— 比在一张图里塞两套刻度清楚。
  有 IO 线时画一条 0 基线（否则看不出是进还是出）。
- **读数与统计**：十字线对**只开 IO 线**（存量线关掉）的物品照样报读数，
  形如 `barrel Δ+64` —— 只报名字 + 这个 tick 的净流量，不报存量。
  框选统计只给 `Δ`（首尾之差）；区间内流入 / 流出的合计不单列 —— `Δ` 已表达同一件事，
  原始数据仍在 `Series#flow` 里，可随时重算。
- 面板 `now` 行在开了 IO 的物品后面跟 `[±n/t]`。

---

### CP19 · 存档与回放（G5）　风险：低

**需求**：记录能离开游戏继续用；能回放；回放页要能选中某条曲线做后续数学运算。

**路径**：

```
图表页「存档并停止」
  → Archives.export(p)：store.toList() + markers + 目标信息 → ArchiveData
  → 写 .minecraft/logiscope/<时间戳>/recording.json + recording.csv
  → （写成功才）ProbeManager.remove(p)：停止记录 + 清缓存
  → ScopeScreen.loadReplay(data)：ChartData.build(0, samples) → Page.REPLAY
根页存档列表 → Archives.load(info) → 同一条 loadReplay 路径（跨存档读取）
```

**关键契约**：

- **C19-1 回放复用呈现层**：回放数据塞进 `ScopeScreen#chartData`（与活数据同一个字段），
  窗口 / 框选 / 十字线 / 图例 / 缩放全部零改动。`rebuildIfNeeded` 在 REPLAY 页直接跳过
  （回放数据是静态的，没有"重算"可言）。回图表页时 `builtVersion` 已被置 MIN，强制重算。
- **C19-2 导出的事务性**：先写文件、写成功才删探针。失败路径探针原地保留 +
  动作栏提示。绝不允许"探针删了、存档没写出来"。
- **C19-3 格式向前兼容**：JSON 带 `format` 版本号与 `kind` 指纹；
  读取方**逐字段取值 + 默认值**（不用 Gson 反射绑定）——旧文件永远能被新代码读，
  新增字段不需要迁移。这是"未来要增加更多功能"的落点。
- **C19-4 CSV 的诚实性**：no_data / gap 是标记行、**count 留空**，绝不写成 0
  （写成 0 = 编造"那一刻是空的"）。
- **C19-5 回放可扩展点**：`replaySelectedItem`（图例弹窗「分析」选中曲线，选中态品红描边）。
  后续数学运算（差值 / 滤波 / 对比 / 导出派生数据）全部挂这个字段与 `ArchiveData` 往前叠，
  不再动采集与存储层。
- **C19-6 回放计算**：计算引擎 `analysis/CurveMath` 是**纯类**
  （输入 `SeriesPoint` 列表、输出不可变 record，离线断言覆盖）。六种运算：
  极值 / 简单统计（mean·med·总体方差）/ 变化总量（Σ升+Σ降，断档不计并报缺口）/
  变更区间（变化量与周期的 mean·var）/ **上升事件 / 下降事件**
  （后二者 = 变更区间按 Δ 符号过滤的单向视图；周期 = 相邻两次**同向**事件的间隔）。两条诚实性规则：
  ① 断档处的变化没留在观测里，**不计入任何一栏**（Σ升+Σ降 可 ≠ Δ，缺口数单独报出）；
  ② IO 只统计**严格在段内**的流量（段首采样 tick 的流量是从区间外带进来的）。
  UI 侧：结果按「区间×曲线×对象×运算」四元组缓存（`refreshAnalyze`），
  结果框画在绘图区右上角（`ChartRenderer#plotRect` 同源几何），与左上角的框选统计并存。
- **C19-8 回放页的「打开存档文件夹」**（第十九轮⑤）：C19-7 的筛选框解决"在长列表里找"，
  这一条解决"找到之后还要在文件管理器里再找一次"。根页的「打开文件夹」落点是根目录
  （几十个 `yyyy-MM-dd_HH-mm-ss`），回放页这个用 `Archives#dirOf(replayDir)` **直接落到
  这一次存档自己的目录** —— `recording.json` / `recording.csv` 就在这一层。
  路径解析归 `Archives`（目录布局的唯一定义处），界面只负责 `openFile`。
- **C19-7 根页存档筛选**：标题行左侧为输入框（空时显示原「存档」标题作占位符），打字即筛
  （匹配名称 / 文件夹名，大小写不敏感）。**渲染、点击命中、滚动三处共用同一个
  `filteredArchives()`** —— "显示的列表"与"点的列表"分家会造成"点 A 行打开 B 存档"。

---

### CP20 · NBT 取数探针（FE / 流体 / 化学品）　风险：中

**需求**：物品之外的量（能量、流体、化学品的量）也要能逐 tick 记录。

**两条路，分工明确**：

| 用途 | 路径 | 为什么 |
|---|---|---|
| **看一眼**（界面上实时显示 NBT）·**单人** | 把"读 NBT"提交到**集成服务端线程**（`server.execute(...)`），SNBT 文本带回来 | 集成服务端在同进程内——取样点放在权威一侧（同 CP15），**零权限**；命令路被权限卡住，聊天回执又是异步又脏的通道 |
| **看一眼** · 多人 | 降级为 `data get block` 命令（需要权限），抓系统回执 | 多人下客户端进程里没有方块实体，原版没有"读完整 NBT"的包 |
| **逐 tick 采样** | 服务端直读方块实体 NBT（`saveWithoutMetadata`）→ 按路径取数 | 不发命令、不依赖权限；值和时间戳同源，没有回执丢包/异步的问题 |

**关键契约**：

- **C20-1 权限与功能解耦**：**单人看与采都不需要任何权限**
  （直读走服务端线程）；权限只在"多人 + 命令 fallback"这一条路上出现。
  没有权限只影响多人下的"看"，界面上如实说明，用户仍可手输路径开始采样。
  绝不用"读不到"假装成"没有流量"。

- **C20-2 名称的两条来源**：`DataTarget` 同时带 `namePath`（NBT 路径，用于流体/化学品
  这类**可变物品**：每 tick 解析一次，换了物质就是换了一条线）与 `literalName`
  （手输，用于"电力消耗"这类 NBT 里没有名字的量）。手输一旦发生就清掉 `namePath`
  （手输优先）。
- **C20-3 路径语法与界面同源**：用户点出来的路径就是采样用的路径
  （`a.b[0].c`，解析在 `Snbt#parsePath`）。**不硬编码任何模组键名** ——
  模组/版本改名不影响功能，用户点哪儿就采哪儿。
- **C20-4 读不到就写 no_data**：方块实体没了 / 路径不在 / 值不是数字 → `no_data`
  （绝不写 0）；多人（无集成服务端）→ PAUSED。
- **C20-5 与物品探针同构**：数据探针也是 `ProbeInstance`，所以图表 / 框选 / 标记 /
  存档 / 回放 / 计算**全部零改动**可用。
- **C20-6 已知的性能取舍**：每 tick 构建整份 NBT 再取一个值（见 PROGRESS.md「未完成 / 待优化项」），
  接口不变、可局部替换。
- **C20-7 多目标与物品共存**：一个探针 = 容器格物品监测 +
  **任意多个** NBT 数据目标（`dataTargets`，COW 列表）。服务端 tick 两段式采样：
  物品守卫 `lastSampledGameTime`（与菜单兜底路互斥）、数据守卫独立的 `lastDataTick` ——
  单人下两条都在服务端 tick 上（节拍相同，但守卫分开：一条读不到时不连累另一条、
  也不重复写同一个 tick）；服务端读不到时物品退到菜单兜底（多人），数据侧直接 PAUSED（C20-4）。
- **C20-8 探针生命周期 = 世界**：`LoggingIn` 时 `clearAll`。探针绑定
  "这个世界里的这台设备"，实例表却是进程级的；不清就会出现"A 存档建的探针
  出现在 B 存档"的僵尸。配置类信息（数据目标）随实例一起消失——它们本来就
  只对那台设备有意义。
- **C20-9 DATA 页的收尾与刷新节拍**：页面底部有与选格页**同一条路径**的
  「完成」按钮（同一个 chip id `done` → 同一个 `commitPicker`）—— 纯数据探针不必
  "回选格页再点完成"；自动刷新间隔 = 全局 `dataRefreshTicks`（「status ok」旁的滑条
  与 config 是同一个值：1~40t，最右档"不刷新"= 只手动「刷新」）。

---

### CP21 · 波形渲染路径（列聚合 + 游程合并）　风险：中

**需求**：多线（多物品 × 存量/IO）时帧率不能塌。实测基线：单线 300 / 双线 200 /
多线 **20** FPS。

**路径**：

```
每帧 ChartRenderer.render
  └─ 每条线 drawSeries(g, pts, color)
       └─ WaveColumns.emit(pts, 窗口, plotW, plotX, yOf, yOfF, RectRuns)   ← 纯类
            ├─ 采样点 → 像素列聚合（同列取 min/max，保尖峰）
            ├─ 断档（breakBefore）不连线；相邻列跳变 → 连接竖线；空列 → 逐列线性插值
            └─ 每个矩形 push 进 RectRuns（就地合并）
       └─ 遍历 RectRuns 调 GuiGraphics#fill
```

**关键契约**：

- **C21-1 合并必须像素等价**：只允许两条规则 —— ①同 x 区间且 y 相接/重叠 → y 包围盒；
  ②y 区间完全相同且 x 相接/重叠 → 并宽。**禁止"相近就并"**（会抹平 1px 台阶，
  属于改图）。对角矩形（x 相接、y 不同）也不能并 —— 并了会多画像素。
- **C21-2 代际戳代替清零**：列缓冲用自增 `gen` 标记"这一轮用过没有"，
  不再每帧 `Arrays.fill` 两个满数组（50 线 × 700 列 × 2 的纯浪费）。
- **C21-3 只扫有数据的列**（cMin..cMax）：缩放到很近或窗口内只有一小段数据时，
  不再全宽空转。
- **C21-4 几何与语义不变**：同列 min/max（尖峰不被平均）、断档不连线、
  稀疏列线性插值 —— 这三条是既定语义要求，重构时不得改动。
- **C21-5 纯类化以便离线验证**：`WaveColumns` / `RectRuns` 不引用任何游戏类，
  所以"聚合成什么样"和"要发多少次 fill"都能离线断言/压测
  （TestChart t29、dev-check/BenchRender），不必靠肉眼在游戏里数帧。

---

### CP22 · "读不到"的判定：`isLoaded`　风险：中

**当前契约**：读值前判 `Level#isLoaded(pos)` —— 区块真卸载才读不到
（`WorldProbe` / `ProbeSlots` 用；`DataProbe` 直接靠 `getBlockEntity` 返回 null）。

**已否决的更严判据**（保留原因，防止回退）：`ServerLevel#shouldTickBlocksAt(chunkPos)`
（= 模拟距离，与 vanilla 决定"这一格要不要 tick 方块"同源）语义更准，但副作用不可接受：
走出模拟距离即变 `no_data`，与"关掉 GUI 之后继续采"的需求冲突 —— 该路径本不需要 `no_data`。
判据因此为 `isLoaded`。

**两条保留事实**（处理"冻结值"问题时直接可用）：
1. `Level#getBlockEntity(pos)` 内部走 `getChunkAt` —— 会**强制加载区块**，
   并返回**磁盘上的快照**（完全卸载后读到的可能是旧值而不是 null）；
2. `ServerLevel#shouldTickBlocksAt(chunkPosLong)` = 距离管理器的 block-ticking 范围
   （模拟距离），是"这台机器目前是否在运行"的唯一准确判据；`isLoaded` 不是。

---

### CP23 · 槽位下标：位置与身份都不可全信 —— 未验证就不猜　风险：高

**背景**：同一个探针的读数有两条通道 —— **主路径是服务端**（`WorldProbe` + 能力(handler)，
单人 + 有坐标 + 设备有可读容器时界面开 / 关都走它），**兜底是界面菜单**
（`ContainerAccess`，仅服务端读不到时：多人 / 无坐标 / 无容器能力）。
两条通道必须指向**同一批物理槽位**，否则同一条曲线会出现"界面内 2 个、界面外 8 个"这类自相矛盾。

**为什么不能靠下标语义统一两条路**：

- 若坐标路径退回 `Slot#getContainerSlot()`（容器内下标）：实测出现"界面内 2、界面外 8"，
  而 2 才是真值 ⇒ 容器内下标对该类模组机器是错的；
- 若让菜单路径也按容器内下标反查（"统一两条路"）：错误被统一 —— 界面内也变成 8（已否决）。

**根因**：模组自写槽位类里，方法的"下标"语义与**能力(handler)的下标序**不一致
（有的返回子库存内的位置，有的返回菜单里的位置）。只有 vanilla `Container` 才保证与能力下标一一对应。

**现行做法**：

- **主路径（服务端）**：`verifyIndex` 验证过映射 → 用验证出的下标；未验证 → 按该格自己的
  `containerSlotIndex` 回退。回退在"下标重复"的机器上可能读错格 —— 由 `warnIfIndexUnverified`
  在记录期间警告（判据 = 同一清单里容器下标重复，与 `SlotAlign.containerIndexReliable`
  同一不变式：**重复的下标不是身份**）。
- **兜底路径（菜单）**：按菜单位置读（界面就是那台机器时，位置唯一确定）。
- **选格（NBT 追踪）**：走 CP24 的"顺序 + 内容"对齐 —— 宁可少选，也不给错的 slot 号。

**结论**："两条路必须读同一批槽位"成立，但"怎么确定为同一批"不能靠猜下标语义 ——
位置与身份在模组世界里都可能失效。更好的出路见 CP24：槽位号若写在数据里
（NBT 的 `slot` 字段），直接读数据，上述困境不存在。

### CP24 · NBT 槽位锚点：槽位号写在数据里时，就**读数据**（不猜下标）　风险：中

**适用对象**：把物品栏存成"列表 + 每个元素自带 slot 号"的机器（Mekanism 等）。
**实测确认的形状**（Mekanism 10.7：`ContainerType.ITEM` 的 containerTag/containerKey
= `items`/`slot`，由 `DataHandlerUtils.writeContents` 写）——

```text
items: [ { id: "minecraft:iron_ingot", count: 64, slot: 9b }, … ]   // 扁平；**空槽根本不写进列表**
```

**契约**：
- **C24-1 识别靠形状，不靠模组名**：`NbtSlotLayout.detect` 只认"复合列表 + 每个元素都带
  数字 slot 字段 +（内部或同级）有 `id`/`count`"，`Item:{…}` 嵌套与扁平两种形状都认。
  空列表、只有 Count、流体列表（`tanks:[{tank:0,…}]`）都不算。模组改名 / 换版本都不影响。
- **C24-2 点到的格子 → slot 号：靠"顺序 + 内容"对齐**（`SlotAlign`，离线断言 t33）。
  两个序列天生同序（都在"机器物品栏的下标序"上）：
  **A** = 菜单里**非空**的机器格（按菜单顺序）；**B** = 数据元素（按 slot 升序）。
  取 A/B 按物品 id 的最长公共子序列，**只有唯一解才采用**（多解 / 对不上 = 拒绝，不猜）。
  · 空格子两侧都不在 → **中间夹着多少空槽都不影响对齐**（Mekanism 的虚拟升级槽、能量槽正是这种）；
  · **不按数量匹配**：运行中的机器每秒都在变数量，两份快照必然错峰（实测确认会漏输出格）。
- **C24-2c `Slot#getContainerSlot()` 只能当备用判据**：
  实测两条字节码事实 —— NeoForge 的 `SlotItemHandler` 是 `super(emptyInventory, index, …)`，
  传的**就是 handler 下标**（正确）；而 **Mekanism 的 `InventoryContainerSlot` 是
  `super(emptyInventory, 0, …)`，容器下标硬编码 0**，全机器槽都报 0。
  把它当首选判据的后果（实测现象）：**点 2 个原料格 + 2 个输出格 → 跟到 1 个原料格 + 全部输出格**
  （撞上 slot 0 的格互相覆盖塌成一格，撞不上的格退回"全纳入"）。
  现在它只在**同时满足**两条时才生效：① 该清单的容器下标**不重复**（重复 ⇒ 它不是身份）；
  ② 那个 slot 的元素**就是这一格的物品**。
- **C24-2b 覆写语义**：再次回掉落格页再点「完成」=
  **界面上勾了几格就只测那几格**（`ProbeInstance.replaceSlotAnchors`）。只换锚点，
  **数据页加的纯路径目标（FE / 流体）原样保留**。一格都没解出来时**什么都不动** ——
  "读不到数据"和"用户想清空"必须分开（要清空去数据页逐个删）。
- **C24-8 进页面必须把现状勾出来**：既然「完成」= 覆写，进页面时界面空着就等于
  "一点完成把目标全清掉"。所以**纯锚点探针**（有锚点、没有菜单/坐标槽位）进选格页即进入
  NBT 追踪并回填勾选；数据还没抓到就每 tick 重试（重试期间**不动用户已勾的格**）。
  混合探针（既有槽位又有锚点）仍必须由人按按钮切 —— 那种一次「完成」会把槽位那半边的目标丢掉。
- **C24-9 统一位移**：所有已确认配对都满足 `ordinal - slot` 相同（且 ≥2 个配对）时，
  这个位移可信 —— **空格子 / 当前已空的锚点**靠它定位（数据里没有元素，只能外推）。
  位移不统一或样本不足 → 不给，空格子宁可不选（靠容器下标 0 的做法是错的）。
- **C24-3 采样只有一条路**：这类目标存成 `DataTarget.slotAnchor`（纯数据目标），
  采样在服务端 tick 读 NBT → 找 `slot == n` 的元素 → 取 `count`/`id`。
  **GUI 开与关走同一条路**，因此不存在"两条路径读数不一致"（对比 CP23 的困境）。
- **C24-4 元素的出现与消失**：元素不存在 = 数量 `0`（这一格空了）；**重新出现就继续记同一条线**；
  出现的若是**另一种物品**，名字变了 → **新开一条线**（旧线停在最后的 0 上）。
  设备整块读不到才是 `no_data`。三者不能混。
- **C24-6b 按格区分只到"目标表"为止**：锚点携带自己的 slot 号，**显示**上写作
  `items[slot=9]`，因此可以**单独删掉某一格**；但**采样名仍是物品 id**（不带后缀）
  → 同物品多格在聚合时照样求和。*"按格区分"只服务于建立与维护 slot 表，
  聚合计算不受后缀影响*（用户明确要求）。
- **C24-7 NBT 追踪是显式开关**：默认关；只有检测到"数据里带 slot 号的物品列表"时按钮可点。
  理由：**自动启用会误判形状相似但没有 slot 语义的设备（"机械手"这类），
  让它们没法按常规（菜单/坐标）路径追踪。开与关都由人决定，离开选格页自动回落到关。
  （唯一例外见 C24-8：**本来就是纯锚点探针**时直接按 NBT 语义进来 —— 那种探针没有
  菜单/坐标槽位，不存在"误判"。）
- **C24-6 选中语义**：*"选中一个物品 = 选中当前所有装着它的格子"*（因为槽位号来自数据、
  照例求和）。这对多格同物品的机器正是要的行为；代价是"只跟其中一格"无法表达 —— 已知。
  **能唯一对齐时"点哪格就是哪格"**，该规则只在"分不出来"时才生效。
- **C24-5 元素消失是常态**：这类机器的空格子不会留在列表里，所以"元素时有时无"
  不是异常，是它表达"空/非空"的方式 —— 不要为它做特殊容错。
- **C24-10 换设备 = 读不到，不是 0**：锚点建档时从 `data get block` 的
  SNBT 根上记下**方块实体类型 id**（`id` 字段），采样时与
  `BlockEntityType.getKey(be.getType())` 比 —— 不是同一种了 ⇒ 这个 slot 号对现在这个方块
  **没有意义**，返回 `null` 走 `no_data`；还是同一种 ⇒ 元素不在就是"这格空了"⇒ `0`。
  若一律写 0，"设备早被拆掉"会表现为一条永远为 0 的假曲线。
  记不到类型（`beType` 为空）时退回写 0 —— 宁可少一种区分，也不误判。

---

### CP25 · 六条硬边界　风险：中

以下六条边界来自全项目代码审查：均非新功能，而是**给已有机制补上"另一种消费者也有意义"的边界**。

- **C25-1 一个标记别给两个消费者用**：`ChartData` 给"段起点之后的第一个流量点"打的
  `breakBefore` 是**渲染**要的断线标记；统计**不得**拿它当"这个值不可信"过滤 ——
  早期实现如此，结果是**每个数据段吞掉一笔真实流量、还谎报一次断档**
  （存量 10→14→12 时 IO 线报 `上升=0 断档=1`，而存量线报 `上升=4 断档=0`）。
  现在：IO 的上升/下降/事件**照单全收**（流量点只在"相邻两格都观测到"时才产生，
  值都成立），缺口数 = **真的少了几笔 Δ**（段起点之后每一条断线算一次）。
  > 通用形式：**渲染的"断开" ≠ 数据的"没有值"**；需要两种语义就写两个字段。
- **C25-2 装不下就是"读不到"，不是"一个怪数"**：能量类设备的 FE/J 常在 2^31 以上，
  窄化截断会把值**回绕成负数**、NaN 会变成 0（"真实的 0"）。现行规则：数值由 `Val`
  承载（long 优先，见 CP26）；越界 / 非有限一律留空（走 no_data 语义），不写错值。
  配套：`Snbt.parseNumber` 不再接受 `NaN`/`Infinity`（SNBT 语法里根本没有这两个字面量），
  `NbtSlotLayout.intField` 不再直接窄化。
- **C25-3 暂停的水位要问两条采样路**：`writeGapMarker` 原来只看
  `lastSampledGameTime`（物品路径才会更新）⇒ **纯数据探针暂停时不写 gap 标记**，
  恢复后曲线跨过暂停连成一条（正是 gap 标记要防的"编造连续数据"）。
  现在取 `max(物品水位, 数据水位)`。
- **C25-4 锁内要重取目标**：`sampleOnServerTick` 原来在锁外取 `target`/`dataTargets`
  快照、锁内只复查 `state`。渲染线程此时可能正在 `configure()`（换设备 / 改选格，
  还会 `store.clear()`）⇒ 服务端线程会**用旧设备的值写进刚清空的新会话**。
  现在锁内重取目标与数据目标、并把守卫（`itemsReady` / `hasData`）一起重算。
  （锁外那份快照只做便宜的早退预筛。）
- **C25-5 换世界要清所有进程级缓存**：`ContainerAnchor.clearActive()` 与
  `ProbeSlots.reset()` 必须在 `LoggingIn` 与 `clearAll` / `DataCommand.reset` 一起调用。
  容器 id 只在 0~99 循环，新世界的第一个容器撞上同一个 id 时，`ContainerAnchor` 会直接
  复用**旧世界**的锚点（"当前开着的容器"被判成别的世界的方块）；`ProbeSlots` 的同坐标
  快照同理。
  > 这就是 C20-8 那句话的完整形态：**生命周期 = 世界的，是"所有进程级缓存"，
  > 不只是探针实例表**。加新的静态缓存时，回来这一条登记。
- **C25-6 输入焦点跟着页面走**：重命名框只活在回放页，但 `renameFocus` 只在
  Enter/Esc 时清 —— 点了「返回列表」之后输入框不画了、焦点还在，
  "输入框激活时吃掉所有按键"于是把**整个键盘**吃掉（只有 Esc 能救）。
  现行实现：`keyPressed` 开头按"页面还在不在"兜底清焦点。
  > 通用形式：**任何"模态"状态（焦点 / 拖拽 / 框选）都要在某处回答"页面走了怎么办"**。

**附注（两条易犯的名实不符）**：
- `ArchiveFormat.readHeader` 必须保持流式读取（`JsonReader` 顺着 token 走、`samples`
  整段 `skipValue()`）—— 不得退回整份解析。
- vanilla `Screen#resize` 只走 `rebuildWidgets()`、**不重跑 `init()`**：拖动窗口不会
  触发重新扫描 / 解析。

---

### CP26 · 自适应数值：整数用 long 精确，其余用 double　风险：中

**背景**：样本值需要覆盖三类情形 —— 2^63 级精确整数（如创造能量立方）、普通整数、
以及浮点量。固定宽度整数不足（截断会产生假线），故值采用**自适应表示**：

- **`analysis/Val`**（纯逻辑，不可变 record）：能精确用 **long** 表示就保存整数形式
  （`integral = true`，读数打印精确整数 —— 用户要求的"读数不用科学计数法"得以保持）；
  否则保存 **double** 形式。两份并存（`i` / `d` 互为影子）。
- **契约**：
  - **C26-1 读取按标签类型分流**：`DataProbe.asVal` 里 NBT 的 byte/short/int/long 走
    `Val.of(long)`（2^63 也精确），float/double 走 `Val.of(double)`；
    **非有限（NaN/∞）→ null = 读不到**（绝不把 NaN 变成 0）。
    **强制数值模式**（DATA 页右键「按数值读取」填入值格时标记 `DataTarget.forceNumeric`）改走 `asValForced`：
    数值标签照 `asVal`；字符串再试 `Val.parseNumericText`（整数优先 long、纯整数超 long
    拒绝近似、小数走 double）；其余类型 / 不可转换 → null 照常记 no-data（不垫 0）。
  - **C26-2 渲染走 `asDouble()`**：`WaveColumns` 的列缓冲保持 `double[]`、逐列聚合零分配
    —— `Val` 是不可变 record，逐列新建对象就是每帧几百次分配（禁止）。
    精度损失只影响"画在哪个像素"，不影响读数。
  - **C26-3 运算保持"能整数就整数"**：`plus`/`minus` 在两个操作数都是整数且**结果不溢出**
    时返回整数形式；**long 求和溢出回退 double 形式**（不回绕成负数）。
    回退**不能走 `of(double)`** —— 它的规范化会把 2^63 折回 `Long.MAX_VALUE`，
    等于把溢出悄悄抹掉；必须直接构造非整数形式（离线断言覆盖）。
  - **C26-4 读数格式**：整数 → 精确整数（`fmtExact` / `signedNum` 的 Val 重载）；
    小数 → 短格式（≥1e6 或 <1e-4 用科学计数法）。纵轴刻度不变（科学计数法）。
  - **C26-5 存档向后兼容**：JSON 里整数写 `"n": <long>`、小数写 `"d": <double>`；
    读侧旧文件只有 `n` 用 `getAsLong`；CSV 里整数原样、小数用 double 字面量。
- **已知边界**：> ±9.2e18 的 double 仍可表示（本就是 double），但**精度**只有 15~16 位
  有效数字；内存上每个样本的值从 4 字节变 16 字节（10 万样本 ≈ +1.2 MB）。

---

### CP27 · AE 网络目标：点格 = 监测网络物品　风险：中

**背景**：AE2 支持 —— 终端面板上点一个"容器格"，监测的是 **AE 网络里对应物品的数量**。
AE 终端的格子不是容器内容（是网络存储列表的显示窗口，客户端从同步包拿到的 repo），
所以这是一条**特例覆盖**，不能套 NBT / 菜单两条路。

**契约**：
- **C27-1 检测靠菜单家族，不靠方块**：`AeNetworkReader.isAeStorageMenu(menu)` 用
  反射判 `menu` 是否 `appeng.menu.me.common.MEStorageMenu` 的实例（终端 / 合成终端 /
  单元仓；wtlib 无线终端也是这个家族 —— 字节码核实过继承链）。
  PatternAccessTerm **不是**这个家族（它显示的是 pattern 供给，语义不同，自动排除）。
  AE2 未安装 → 返回 false，一切照旧。
- **C27-2 目标身份 = 键的字符串身份**（第十九轮② 扩到全类型）：`DataTarget.aeNetwork(keyId, …)`，
  `valuePath` 固定为标记 `"ae:network"`（**不是路径**）。去重按 `literalName` ——
  所有 AE 目标的 valuePath 相同，**绝不能拿 valuePath 去重**（`DataTarget.isDuplicate`
  的 AE 分支 + t38 断言钉住）。
  身份的两种形态（`analysis/AeKeyText`，t42 断言钉住）：

  | 目标 | `literalName` | 例子 |
  |---|---|---|
  | 物品（老格式，保持兼容） | 裸 registry id | `minecraft:iron_ingot` |
  | 流体 / 化学品 / 电力… | `ae:` + `AEKey#toTagGeneric` 的 SNBT | `ae:{#t:"ae2:f",id:"minecraft:water"}` |

  **为什么非物品键不能沿用物品 id**：它们在客户端被包成 `ae2:wrapped_generic_stack`
  这一个物品 —— 所有流体 / 化学品都塌成同一个 id，目标互判重复、读值只能读到
  "这个包装物品有 0 个"（第十九轮② 之前的实机现象：界面显示"通用物品堆栈 x1"、
  监测报 0）。SNBT 里带 `#t`（键类型，`ae2:i` 物品 / `ae2:f` 流体 / 附属自己的类型）
  与 `id`，因此**任何附属模组的键类型都能原样往返**，本模组不需要认识它们。
- **C27-3 选格交互**：AE 模式下只有**网络列表的显示格**（`RepoSlot`，按 slot 类名判定）
  可以选；背包 / 合成格的物品不是"网络中的物品"，点击忽略。cell_mode（可视化选格）
  在 AE 模式禁用（两套语义不能混）。「完成」= **覆写**全部 AE 目标（与槽位锚点同一
  覆写语义）；一个都没选 = 什么都不动。
- **C27-4 读值链（服务端，全反射，零编译依赖）**：
  `GridHelper.getNodeHost(level, pos)` → 遍历 6 个面取第一个非 null 的
  `getGridNode(dir)`（不能只试一个方向：CableBus 的 default 实现按
  `isExposedOnSide` 过滤，节点被部件遮住的面返回 null；也不能用
  `getActionableNode()` —— CableBusBlockEntity 不实现 IActionHost，字节码核实过）→
  `isActive()` → `getGrid().getStorageService().getInventory().getAvailableStacks()`
  （KeyCounter）→ `counter.get(AEKey)`。
  键对象：物品走 `AEItemKey.of(stack)`（老路）；`ae:` 键串走
  `AEKey#fromTagGeneric(registryAccess, TagParser.parseTag(snbt))`。
  反射链**只解析一次**（静态 Method 缓存），KeyCounter **同一 tick 同一张网络只算一次**
  （弱引用缓存，避免静态字段钉住一个世界的对象图）。
- **C27-5 三种语义**（延续"读不到 / 空格子 / 没在记录"的纪律）：
  - 返回 **null** = 设备读不到（不是 AE 节点 / 网络断电 `isActive()==false` /
    AE2 未安装 / 物品已不在注册表 / **键串解不出来**）→ 上层记 **no_data**；
  - 返回 **0** = 网络在线但没有这种东西（craftable 条目也不在 availableStacks）→ 真值；
  - 返回 **n** = 网络中这种东西的可用数量（long → Val 精确承载）。
- **C27-6 失败策略**：AE2 缺失只记一次日志后恒 no_data（不刷屏）；单次反射异常记一次
  日志、本次 no_data、下次采样重试。采样名 = 键身份 → 两个终端监测同一种东西
  会**合并求和**（与槽位锚点同一聚合语义）。
- **C27-7 已知边界**：`"ae:network"` 是保留的 valuePath —— 用户在数据页手输这个路径
  会被当成 AE 目标分流（文档明示，勿在别的模组上测试该路径）。六面全被遮挡的设备
  读不到（no_data）。
- **C27-8 终端列表的"名字与数量"**（第十九轮②）：AE 终端的显示格不是容器内容 ——
  `ItemStack.getCount()` 恒为 1（图标），真实数量在
  `RepoSlot#getStoredAmount()`；非物品键的显示栈是 `ae2:wrapped_generic_stack`，
  真名与身份在它的 `GenericStack` 数据组件里。所以 `ContainerAccess#viewOfAe` 对
  AE 菜单家族走另一条组装路径：数量 = `getStoredAmount()`（long），
  名字 = `AEKey#getDisplayName()`，身份 = `AeKeyText` 格式。
  **空/非空判据是"条目在不在"（`RepoSlot#hasItem()`），不是"数量是不是 0"** ——
  可合成但没存货的条目存量就是 0，那也是一种真实存在的东西（监测它 = 现在 0 个）。
  AE 列表里所有显示格的 `#序号` 都是 0（它们共享同一个空容器）—— 在 AE 终端里
  按 `#N` 筛选没有意义，请按名字筛（选格页筛选框，C17-5）。
- **C27-10 反射的边界：只反射 AE，不反射原版**（2026-10-21 定）：AE2 不是编译期依赖，
  它的类只能反射；但**原版（MC）的方法一律直接调** —— 编译期调用会被正确重混淆，
  按名字反射则要看运行时的命名方案。1.20.1 的生产运行时是 SRG
  （`TagParser#parseTag` 叫 `m_129359_`），`AeKeys.init()` 里那两条 MC 方法反射
  在 dev 全绿、装进实例直接 `NoSuchMethodException`，整条 AE 键链停摆
  （现象：流体显示成 `wrapped_generic_stack`、点一行选中全部）。1.21.1 生产用 Mojang 名，
  这条线碰不到 —— 但两线统一成"直接调"，免得下一版线搬代码时再栽。
- **C27-9 "直接取某张网络里有多少"的 API**：有，而且是 AE 自己用的那条 ——
  `IGridNode#getGrid()` → `IGrid#getStorageService()` → 两种读法：
  `IStorageService#getCachedInventory()`（**每 tick 最多刷新一次的 KeyCounter**，
  代价最低，AE 终端显示列表就用它；代价是最多差 1 tick）与
  `getInventory().getAvailableStacks()`（现算，遍历全部存储，代价高但精确）。
  本项目取**后者 + 同 tick 缓存**：采样点本来就在服务端 tick 上，"值和时间戳同源"
  比省一次遍历重要（CP4/CP15 同一条纪律）。`counter.get(AEKey)` 是 O(1) 取一张键的量。
  （历史上 AE2 还有过 `IMEInventoryHandler` / `IAEStack` 那套，19.x 已换成 `MEStorage` + `AEKey`。）

---

### CP28 · RS（精致存储）网络目标：与 AE 结构同构、API 没有一处相同　风险：中

**背景**：精致存储（Refined Storage 2，下称 RS）与 AE 是同一个故事 ——
网格界面上的格子不是容器内容，是**网络存储的显示窗口**。所以 RS 也走
"网络目标"这条特例路（CP27 的兄弟），但它的类体系、读值链、身份格式**一处都不共通**，
代码是并行的两套（`RsKeys` / `RsNetworkReader` 对应 `AeKeys` / `AeNetworkReader`）。

**契约**：
- **C28-1 检测靠菜单家族**：`RsKeys.isRsGridMenu(menu)` 沿父类链比**类名**
  `common.grid.AbstractGridContainerMenu`（终端 / 合成终端 / 便携网格都在这个家族下，
  字节码核实过）。和 C27-1 同一理由：**不用 `Class.forName`** —— NeoForge 下每个模组
  在自己的模块加载器里，`forName` 用的是**调用方**的加载器，不一定看得见 RS；
  而 `menu.getClass()` 是运行时真实对象，往上走总能拿到那个类。RS 未安装 → false，一切照旧。
- **C28-2 目标身份 = RS 资源的字符串身份**（`DataTarget.rsNetwork(keyId, …)`，
  `valuePath` 固定标记 `"rs:network"`，去重先比标记再比 `literalName`）。三种形态
  （`analysis/RsKeyText`，t43 断言钉住）：

  | 目标 | 身份串 | 例子 |
  |---|---|---|
  | 物品，**无数据组件** | 裸 registry id（**不挂前缀**） | `minecraft:iron_ingot` |
  | 物品，带组件 | `rs:i:<id>\|<组件指纹>` | `rs:i:minecraft:potion\|{minecraft:potion_contents=…}` |
  | 流体 | `rs:f:<id>`（带组件再加 `\|` 尾巴） | `rs:f:minecraft:water` |

  **无组件物品为什么不挂前缀**：RS 的 `ItemResource(item, 空组件)` 与"原版的一件铁锭"
  是同一个东西。共用裸 id 换来三件事 —— ① 名称解析不必认识 RS（C28-6）；
  ② 同一件东西在 AE / RS 两条网络里天然同名（一个探针对准一台方块，不会同探针撞车）；
  ③ 旧目标 / 旧存档不受影响。
  **组件为什么必须进身份**：RS 自己就按 `ItemResource.equals(item + 组件)` 区分资源。
  塌成一个身份 = 两瓶不同的药水合成一行、数量相加，正是 C23「同名不同物」的反面。
- **C28-3 组件指纹（已知限制，已接受）**：`RsKeys#fingerprint` = 把
  `DataComponentPatch.entrySet()` 的每条拼成 `组件类型id=值`、**排序**后 `{}` 包起来。
  - 为什么要排序：`DataComponentPatch.toString()` 按**插入顺序**打印（字节码核实：
    `Reference2ObjectMaps.fastIterable`），两个相等的补丁可能打出不同字符串 ——
    那样的指纹会让同一条曲线每 tick 换一个身份。
  - 为什么要"值.toString()"：反射层没有可靠的组件往返手段，而**采样只需要
    "同一个补丁对象算出来的字符串恒定"**（这一条成立）。代价是跨版本 / 两个模组手写的
    `toString` 可能撞车 —— 撞车的后果是**两行合并成一行（数量相加），不会读出假值**。
  - **长度上限 `FINGERPRINT_MAX = 160`**（`RsKeyText#componentFingerprint`）：身份串是
    **每个采样点都存一份**的（`Sample#itemId`），而 `minecraft:container` /
    `minecraft:bees` / `minecraft:custom_data` 这几类组件能到几 KB —— 20 样本/秒 × 1 小时
    ≈ 360 MB，存档会被撑爆。超限换成 `{…<条目数>:<哈希>}`：仍只由内容决定
    （采样必需的性质不变），代价同上（撞车只会合并）。
- **C28-4 选格页的行来自仓库，不来自槽位**：RS 2.0 的网格不是"一格一个 Slot"的容器，
  界面按仓库视图逐格画（`AbstractGridScreen#renderCell`）。所以
  `RsKeys#rows(menu)` 直接读 `AbstractGridContainerMenu#getRepository()`
  → `getViewList()`（RS 自己筛过 / 排过序的那一份）→ 每项取
  `getResourceForRecipeMods()`（真实键）/ `getName()`（游戏内名）/ `getAmount(key)`。
  副产品：**AE 那条路只能选到当前页的格子，RS 这条路能选到整个网络**。
  行的 `menuSlotIndex = -1`（RS 的行不对应任何菜单槽），选中 / 去重全靠身份串 ——
  所以 `toggleRow` 里 AE 那条"必须落在 `RepoSlot` 上"的守卫 **RS 不走**。
- **C28-5 读值链（服务端，全反射，零编译依赖）**：
  `level.getBlockEntity(pos)` → 沿**该对象自己的类**的接口链找
  `common.api.grid.Grid`（实现类 `AbstractGridBlockEntity`）→ `isGridActive()` →
  `getItemStorage()` → `StorageView#getAll()`（`Collection<ResourceAmount>`）→
  逐条 `encode(resource())` 与目标身份串比对，命中累加 `amount()`。
  - **`getItemStorage()` 的名字会骗人**：字节码核实它返回的是
    `network.getComponent(StorageNetworkComponent.class)` —— **所有资源类型的根存储**
    （RS 自己的 `GridData#of` 也用同一个组件），所以流体**不需要**第二次调用。
  - **为什么是"拉全量再比对"而不是"按 key 精确查"**：`RootStorage#get(ResourceKey)`
    更快，但它要一个 RS 的资源键**对象**，而这个对象只能从"物品/流体 + 组件补丁"重建 ——
    组件补丁在反射层没有可靠往返（C28-3）。用**自己的编码**去比对，就把"读值"和"身份"
    钉在同一条规则上：**编得出来就读得到，编不出来那一行根本没被列出**。
  - 反射链只解析一次；整张网络的清单**同一 tick 只拉一遍**（按 `(storage, gameTime)`
    缓存成"身份串 → 数量"的 Map，弱引用持有 storage，避免静态字段钉住世界的对象图）。
- **C28-6 名称**：`NameText` 只按身份串里的 registry id 取名 —— 流体走
  `new FluidStack(fluid, 1000).getHoverName()`（与 RS 自己算名的方式一致），物品走
  `Item#getDescription()`。**组件指纹不参与取名**（它不是可反解的序列化），所以带组件
  物品在"实际名"档显示的是物品本名（自定义命名的药水显示"药水"）。选格页不受影响：
  那里直接用 RS 算好的 `GridResource#getName()`（`SlotView#label` → `NameText.of` 的 hint）。
- **C28-7 三种语义**（与 C27-5 同一条纪律）：返回 **null** = 读不到（不是 RS 网格设备 /
  网络断电 / RS 未安装 / 反射链断了）→ no_data；返回 **0** = 网络在线但没这东西（真值）；
  返回 **n** = 该身份在网内的数量。
- **C28-8 覆写语义按"全部网络目标"算**：`ProbeInstance#replaceNetworkTargets` 一次换掉
  AE 与 RS 两类目标。理由：一个探针对准的是**一台方块**，它要么是 AE 设备、要么是
  RS 设备 —— 留着另一类只会得到永远 no_data 的死目标。DATA 页的纯路径目标与 NBT
  槽位锚点不受影响。
- **C28-9 已知边界**：① `"rs:network"` 是保留的 valuePath（同 C27-7）；
  ② **附属模组自定义的 RS 资源类型不列出**（`RsKeys#encode` 返回 null）—— 与其给一个
  "看着像身份、其实读不回来"的串让人勾上一行永远 no-data 的东西，不如不列；
  ③ 便携 / 无线网格没有方块锚点 → 「完成」拒绝提交并提示（与 C27-3 的无线终端同一条）。
- **C28-10 离线实证**：`dev-check/ProbeRsApi.java` 把本类用到的**每一个反射入口**
  对真实 jar 解析一遍（`RS_API_OK`）。它尽量沿代码自己的推导路径走
  （`getRepository()` 的返回类型 → `getViewList`；从运行时资源键类往上找 `ResourceKey`），
  免得探针与实现各自漂移。**不在** `offline-check.bat` 里（依赖仓库外的第三方 jar）。

---

## 四、设计权衡（已做的决策与理由）

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 探针身份 | 运行时身份 + 持久身份**同时持有** | 只用其一 | 界面开着用运行时（零成本），关掉后用持久（单人可真读、多人记断档） |
| 界面形式 | `ClientHooks.pushGuiLayer` 图层栈 | `setScreen` 替换式 / 纯事件叠加绘制 | 替换式看不到原界面；纯事件叠加要自己实现全部命中测试与拖拽。图层栈两者兼得，还能直接用 `Screen` 的事件方法 |
| 关界面后 | 转 `FOLLOWING` | 直接 `PAUSED` | 玩家关界面往往是要腾出手，不是想停 |
| 多人读不到时 | 记 `no_data` | 跳过 / 记 0 | 跳过会让横轴出现"跳变"，记 0 会让净流量彻底算错 |
| 绘图 | 手写 `fill`/`drawString` | 引入图表库 | 避免依赖地狱，NeoForge 生态的渲染库版本极易打架 |
| 采样时机 | `ClientTickEvent.Post` | `RenderTickEvent` | tick 是 20Hz 恒定，render 帧率会飘，时间轴对不齐 |
| 单 tick IO 的来源 | **从存量算**（相邻两格相减），只在相邻两格之间算 | 再采一份"流量" / 挂钩容器变更 | 另采一份要改采样层、多占缓冲、还可能与存量错位；跨格（中间漏观测）的变化归不到某一格上，硬写 `n/t` 是假的，所以那种位置宁可不给流量（CP18） |
| IO 的渲染 | 与存量**共用一套纵轴**，各自可开关 | 右侧再开一套刻度（双轴） | 双轴在一张图里塞两套刻度更难读；而"要看 IO 就把存量关掉"这个操作已经把轴让给 IO 了（CP18） |
| 资源上限 | **客户端配置**（`bufferSamples` / `maxProbes` / `maxItemKinds` / `dataRefreshTicks`） | 代码常量 | 有人只测一台机器想留更久历史，有人挂十几个想省内存，需求本来就分叉（CP6 之一） |
| 断线的判据 | **只认显式标记**（`no_data` / `gap` / 相邻 tick 缺席） | 数 tick 间隔（间隔 > 1 就断） | 时间戳换成服务端权威值之后，"间隔 2"经常只是观测抖动，数间隔会把 20t 下的偶发漏观测剪成断线，也会让 `/tick rate 200` 的每个点都变成孤立点（CP10） |
| 采样驱动 | **服务端 tick**（单人主路径）：世界走一格记一格 | 客户端 tick + 异步读 | 客户端每秒最多观测 20 次、服务端每秒走 20 格（还能更快），**节拍不锁相** → 必然偶尔漏观测一格 → 曲线要"跨过去"；异步读值还会把缓存里的旧值贴上新 tick 标签（假平台）。挂到世界 tick 上两者一起解决（CP4） |
| 采样来源的选择 | **服务端优先**：单人 + 有坐标 + 设备有可读容器 → 一律服务端（界面开 / 关都一样，菜单只是同步副本）；读不到 → 菜单兜底（多人 / 无坐标 / 无容器能力） | 界面开着走菜单（已否决）/ 一律走服务端 / 一律走菜单 | 被否决方案中：菜单优先时两条路会因同步相差 ~1 tick 而自相矛盾、且菜单有延迟；一律服务端在多人 / 无容器设备上读不到；一律菜单漏格、关界面没法读。下标不可信时的读错风险由警告明示（CP9 / CP23） |
| 关界面/暂停的过渡 tick | **什么都不写**（没观测就是没观测） | 用上一次的已知值垫一格 | 垫值同样制造 1t 平台；而"缺一格"在新断线规则下看不出来，所以没必要造假（CP9 之一） |
| 暂停在时间轴上的表示 | 写一条 `gap` 标记（统计透明、画图断开） | 什么都不写 / 也记 `no_data` | 什么都不写则恢复后曲线会被连起来；记 `no_data` 会污染"读失败"的计数（`Sample`） |
| 纵轴刻度写法 | 只有纵轴用科学计数法，且**步长顶到相邻标签不重复** | 一律短格式 / 一律精确整数 | 8 位整数画到 GUI 外面；而 2 位小数的短格式会把 `1786000` 与 `1786500` 印成同一个 `1.79E6` |
| 读数（框选 / 十字线 / 面板数字） | **精确整数** | 沿用短格式 | 需求：只有纵轴可用科学计数法 —— 物流读数报"1.79E6"没有意义 |
| 世界侧读值 | 在**服务端 tick 里同步读** | `server.execute` 异步 / 客户端线程直读 | 客户端直读是数据竞争；异步读又带来延迟与缓存复用（假平台、假空档）。既然要"每世界 tick 一条真值"，采样点干脆就放在服务端 tick 上（CP4） |
| 面板位置 | 静态字段记住，默认右上角 | 每次居中 | 容器界面居中，默认放右上角天然"不遮挡"；且拖动结果跨开关保持 |
| 面板外的鼠标 | **转发**给背景容器（CP8 之一） | 不响应 | 面板只占一块，其余区域本来就该能用；也是以后框选待测区间的基础 |
| 格子 hover 高亮 | **自己补画**（CP8 之二） | 接受没有高亮 | 全是公开 API，十几行就能拿回视觉反馈 |
| 面板边界 | 拖动中宽松、打开时收紧（CP8 之三） | 一律限制在屏幕内 | 大 GUI scale 下面板必须能推出去；但又要留退路 |
| 第三方搜索框守卫 | 每支持一个搜索类模组加一个小探针（反射） | 只靠通用兜底 | 探针是**主动**判定，能在"点进搜索框立刻按键"时也拦住；兜底要等一次按键才学会 |
| 按物品分组的数据线 | **派生视图**（扁平环形缓冲是唯一真相） | 存储层就按物品分数组 | 前者让"环形覆盖 / 清空 / 断档判定"只写一遍；后者要跨数组协调、且丢失统一时间轴（CP10） |
| 数据线配色 | 按 series **下标**取色 | 按 itemId 哈希 | 保证"先出现的线颜色固定"，不会因新物品出现而集体换色 |
| 纵轴范围 | 吸附到 nice step | 直接用 min/max | 刻度落在整数上，且把"每帧抖"降成"偶尔跳一次"（CP11） |
| 稠密采样 | 逐列取 min/max | 逐列取平均 | 平均会把尖峰抹平，而尖峰正是物流分析最想看的东西 |
| 画线原语 | 逐列 1px 竖直矩形 | 引入画线库 | `GuiGraphics` 本来就没有画线 API；而"逐列竖直矩形"和"逐列聚合"是同一个形状，合成一次遍历（CP11） |
| 派生数据缓存 | `SampleStore#version` + 每 tick 最多重算一次 | 每帧重算 / 界面里做增量更新 | 前者把开销降到 1/3 且只改一处；增量更新更容易漏（CP12） |
| 建立选区 | **自动冻结**视图 | 继续实时滚动 | 不冻结的话刚框住那段几秒后跑出屏幕，选择就没意义了（CP12） |
| 面板尺寸 | 右下角可缩放 + 静态字段记住 | 固定尺寸 | 波形要放坐标轴/图例/框选，固定 272×190 画不下；尺寸也是使用偏好（CP12） |
| 关界面接缝 | **什么都不写**（那一 tick 确实没观测到） | 用 `lastKnown` / `seed` 垫一格 | 垫值能消掉假 no_data，但会造出 1 tick 假平台（读数上看得出）；而"缺一格"在新断线规则下看不见，所以赊账不如不赊（CP9 之一、CP10） |
| 采样时钟 | **世界 tick 本身**（服务端 tick 事件里的 `ServerLevel#getGameTime()`） | 客户端 `Level#getGameTime()` / 异步读回来的权威值 | 客户端 gameTime 是错拍阶梯（本地 +1 / 服务端 20t 纠正一次），tick rate ≠ 20 时节奏与数值全错；异步读回来的值虽然权威，但它是"上一次观测"，要贴到哪个 tick 上永远差一格（CP15 / CP15 之一） |
| 时间轴的"秒"标签 | 按**真实 tick 速率**换算（`Level#tickRateManager().tickrate()`） | 固定按 20 tick/s 换算 | `/tick rate 200` 下 1 秒会被标成 10 秒。客户端能从 `ClientboundTickingStatePacket` 同步到的 tick 速率拿到真值（21.1.209 核实） |
| 多探针 | **所有实例同时采样**，界面只切换查看对象 | 只跑"当前在看的那一个" | 语义是"正在运行的探针"，切回来应该看到离开那段时间的数据（CP16） |
| `WorldProbe` 的归属 | **每实例一份** | 静态单例共享 | 共享读值缓存会让两个探针互相覆盖读数，且症状是"数据偶尔跳一下"，极难查（CP16） |
| 全局时钟 | 抽成 `ServerClock`，不放在实例里 | 跟着 `WorldProbe` 走 | 删掉一个实例不该清掉全世界的时间（CP16） |
| 多格聚合的粒度 | **按物品分别求和**：不同物品各自一条线，同种物品的数量相加 | 合成一条"混合物品"线（已否决） | 混合写法把一台机器里几种物品的物流信息压成一条线，丢掉分析工具最重要的维度（CP16） |
| 多格读值失败 | **任何一格读不到 → 整条记 no_data** | 跳过读不到的那格、按剩下的求和 | 和偏小在波形上和"正常下降"一模一样，**看不出来**；no_data 是看得出来的（CP16） |
| 可视化选格的拖动 | 按线段每 4px 采样逐点命中 | 只判当前鼠标位置 | 鼠标事件按帧来，快速划过会漏格（CP17） |
| 选格集合的键 | **菜单槽下标** | 容器内下标 | MEK 那类模组 `getContainerSlot()` 恒为 0，用它做键会让"选 1 格"变成"全选"（CP17、CP9 之二） |
| 打开容器界面 | 模拟一次真实右键（`useItemOn`），跨 tick 再压图层 | 想在按键那一 tick 就压 | 只有服务端能开菜单，界面必须等服务端回包；且要先挡住"物品抢在方块前面"（CP17） |
| 判"这个方块有没有界面" | 问一次 `useWithoutItem(...).consumesAction()`（不发包） | `getMenuProvider` 静态判 / 发一次右键再看 | 前者连原版熔炉和几乎所有模组机器都判不出来；后者会把工具/物品的 `useOn` 一起触发（CP17） |
| 探针名 | **方块名 + 槽位 + 坐标** | 只写槽位与坐标 | 机器多时仅凭槽位与坐标无法辨识。方块名只用于显示，不参与身份判定（CP16） |
| 纵轴刻度数字 | ≥ 10000 走**科学计数法**（`1.6E7`），轴宽 34 → 46px | 原样打印整数 | 8 位整数刻度会画出 GUI 外（单格大堆叠场景） |

### 已知限制（不是 bug，是取舍）

| 限制 | 原因 | 影响 |
|---|---|---|
| 面板打开时，容器的**键盘**操作无效（数字键换物、`Q` 丢弃、中键取方块） | 这些走 `hoveredSlot`，而背景层拿不到鼠标坐标（见 CP8） | 鼠标操作已经完全可用（CP8 之一 ~ 之四）；键盘这几个键要收起面板 |
| 拖拽分配时手持物品的数字是**完整数量**而非剩余量 | `quickCraftingRemainder` 是 `private` 字段，读不到 | 只在按住左键拖过一排格子的瞬间可见（CP8 之四） |
| 面板打开时按 `E`（背包键）没反应 | `Screen#keyPressed` 不处理背包键，只有 `AbstractContainerScreen` 处理 | 同上，收起面板即可 |
| 被压住的容器界面**不 tick** | 只有 `minecraft.screen` 才会被 `Screen#tick` | 少数依赖 tick 的自绘界面可能显示不动 |
| 面板内的点击不会穿透到容器 | 这是刻意的，否则一次点击会同时触发两边 | 无 |
| **多探针** | 每实例一份半小时缓冲 + 每世界 tick 一次方块实体读取 | 数量很大时（十几个）会有可观的内存与查询开销；`MAX_PROBES` 就是为此设的 |
| 环形缓冲容量按**样本条数**算 | 多格多物品时一个世界 tick 要占好几条 | 监控 3 格、里面有 3 种物品时，36000 条只覆盖约 10 分钟而不是半小时（CP16） |
| **主路径不再漏格**；回退路径仍可能漏 | 主路径挂在世界 tick 上（CP4）；回退路径（服务端读不到：多人 / 无坐标 / 无容器能力）用菜单读、按客户端 tick 观测 | 回退路径漏掉的那一格在时间轴上留空、折线跨过去（不是断线，见 CP10） |
| 高流速下缓冲覆盖的时间变短 | 容量按**样本条数**算（36000），`/tick rate 200` 时 200 条/秒 | 20 t/s 覆盖 30 分钟；200 t/s 只覆盖 3 分钟。要更久就得加大容量（内存线性增长） |
| `/tick rate > 20` 时样本天然稀疏（200 时约每 10t 一个点） | 观测端的物理极限 | 累计值与 n/t 速率仍正确；曲线是连的（CP10） |
| 纵轴刻度可能变稀疏（相邻标签重复时自动加大步长） | 科学计数法只有 3 位小数，窗口很窄时解不出更多有效数字 | 宁可刻度稀疏也不要一排看不懂的数字；精确值看光标读数（CP11） |
| 精确整数让面板文字变长 | 用户要求读数给准确值 | `now` / `min-max-avg` 行在窄面板里会被裁断（不会压到右边） |
| 对着"会消费右键但不是开界面"的容器机器按 F9，那一次右键会照常发生 | 只能用"消费与否"当作有没有界面的判据，40 tick 没等到界面就兜底 | 与玩家自己右键一次等价；不会误触**非容器**方块（那种情况一个包都不发）（CP17） |
| 多人下关界面记不到真值 | 纯客户端定位的必然代价 | 见 CP9 |
| 用快捷键开界面的模组拿不到坐标 | vanilla 菜单不存方块坐标，只能靠右键事件推 | 见 CP9 |
| 第三方搜索框需逐个加探针 | 那些模组的搜索框不挂进 `screen.children()` | 目前只接了 JEI；通用兜底能覆盖其余情况（代价：多按一次） |
| 一个像素列里同时发生物品切换时，旧颜色会在该列多画一小截 | 列聚合每列只记 `first` 的断点标记 | 列宽 1px，肉眼不可见；不值得为它复杂化聚合逻辑（CP11） |
| 图例超过 3 行时多出的收成 `+N`（点不到） | 面板高度有限 | 一个槽位同时出现 7 种以上物品很罕见；把面板拖大即可（CP12） |
| 纵轴范围随窗口滚动偶尔会跳一下 | 自动缩放的固有代价 | 已吸附到整数刻度，跳变频率远低于逐帧抖动（CP11） |
| 界面上只画 3 条数据线时，跨空列会线性插值（画成斜坡） | 采样点稀疏时无法知道真实跳变发生在哪一 tick | 台阶数据的斜坡只出现在"两点间隔很大"的情况，属合理近似（CP11） |

### 待决问题（写代码前先决定）

1. **采样降频**：长时间记录时是否需要"每 N tick 采样一次"以延长覆盖时长？
   注意：断线规则不看间隔（CP10），降频不会剪碎波形；但降频会让"哪个 tick 有数据"
   更稀，`Analyzer` 的 `ticks N` 口径需同步说明。
2. **物品 NBT**：现在只记 itemId，不区分 NBT 不同的同名物品（例如带不同附魔的书）。
   物流分析大概率够用，属已知边界。
3. **让容器的键盘操作也能用**：需要补上 `hoveredSlot`（`protected`）。
   可行路径：反射写字段，或改为"纯事件叠加绘制"（容器界面留作 `minecraft.screen`，
   用 `ScreenEvent.Render.Post` 画面板）。后者更干净但改动面大。
4. **`no_data` 的采样密度**：现在与真实样本一样每 tick 一条。若要在断档期间降频，
   需先明确"没试过的 tick"与"试过失败的 tick"如何区分。
5. **图例的可滚动 / 可折叠**：现在超过 3 行收成 `+N`；"一个槽位轮换十几种物品"的
   场景需要给图例加翻页。
6. **多探针同屏对比 / 相对时间轴**：目前一次只能查看一个探针，横轴均为绝对 tick。
   同屏对比需要"以某个事件为 0 点"的相对时间轴。

---

## 五、已核实的 API

以下均经本地 `neoforge-21.1.209-sources.jar` 与第三方 jar 逐行核实（版本标注见条目）。

### 5.1 容器与数据

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `Player.containerMenu` | public **字段** | 拿当前菜单 |
| `AbstractContainerMenu.slots` | `public final NonNullList<Slot>` | 槽位列表 |
| `AbstractContainerMenu.getSlot(int)` | `public Slot getSlot(int)` | 按下标取槽 |
| `Slot.getItem()` / `getContainerSlot()` | public | 读内容 / 容器内下标 |
| `Container.getItem(int)` / `getContainerSize()` | interface 方法 | 方块实体侧读值 |
| `Level.getBlockEntity(BlockPos)` | public，非客户端且未加载时返回 null | 找方块实体 |
| `Level.isLoaded(BlockPos)` / `dimension()` | public | 加载检查 / 维度 key |
| `Minecraft.isLocalServer()` | public，字段 `isLocalServer` | 判断单人 |
| `Minecraft.getSingleplayerServer()` | `@Nullable IntegratedServer` | 拿集成服务端 |
| `MinecraftServer.getLevel(ResourceKey<Level>)` | `public ServerLevel getLevel(...)` | 按维度拿服务端世界 |
| `BlockableEventLoop.execute(Runnable)` | public，实现 `Executor` | 提交到服务端线程 |
| `Registries.DIMENSION` | `ResourceKey<Registry<Level>>` | 构造维度 key |

### 5.2 界面与输入

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `ClientHooks.pushGuiLayer(Minecraft, Screen)` | **public static**，`net.neoforged.neoforge.client` | CP8 压图层 |
| `ClientHooks.popGuiLayer(Minecraft)` | 同上；栈空时退化为 `setScreen(null)` | CP8 弹图层 |
| `ClientHooks.clearGuiLayers(Minecraft)` | `Minecraft#setScreen` 开头自动调用 | 兜底清理 |
| `Minecraft.mouseHandler` | `public final MouseHandler` | 手动 `releaseMouse()` |
| `MouseHandler.releaseMouse/grabMouse/isMouseGrabbed` | public | 鼠标抓取状态 |
| `ScreenEvent.KeyPressed.Pre` | 带 `getKeyCode/getScanCode/getModifiers`，可 cancel | CP2 |
| `ScreenEvent.Init.Post` | 在 `Screen#init` 末尾发出 | 容器界面打开时机 |
| `ScreenEvent.Closing` | `getScreen()`，**不可 cancel** | 界面关闭时机 |
| `ScreenEvent.Render.Post` | 带 `getGuiGraphics/getMouseX/...` | 备用（本项目未用） |
| `PlayerInteractEvent.RightClickBlock` | `getPos()` / `getLevel()`，可 cancel | CP9 拿坐标 |
| `RegisterKeyMappingsEvent` | `implements IModBusEvent` | **必须注册在模组总线** |
| `ClientTickEvent.Post` | 游戏总线 | CP4 |
| `KeyboardHandler#keyPress` 里的分支 | `action==1 || action==2` 都走 screen | CP2 去抖的依据 |
| `KeyboardHandler#charTyped` 里的分支 | `Pre` / `charTyped` / `Post` 三连，**前两个 return 掉就不发 Post** | CP2 第 3 层守卫的依据 |
| `Minecraft#tick` | `handleKeybinds()` 只在 `overlay==null && screen==null` 时跑 | CP2 规则 4 的依据 |
| `Screen.getFocused()` | `AbstractContainerEventHandler` 上的 `@Nullable GuiEventListener` | CP2 规则 3 第 1 层 |
| `Screen.mouseClicked/mouseReleased/mouseDragged/mouseScrolled` | `(double,double,…)`，`ContainerEventHandler` 默认方法 | 面板交互 |
| `Screen.tick()` | `Minecraft#tick` 在 `screen != null` 时调用 | 刷新槽位快照 |
| `GuiGraphics.drawString(Font,@Nullable String,int,int,int)` | 存在 | 画文字 |
| **`GuiGraphics.drawString(Font,@Nullable String,int,int,int,boolean dropShadow)`** | 存在（去阴影） | 小号刻度数字用它更清楚 |
| `GuiGraphics.fill(int,int,int,int,int)` | 存在，**支持 ARGB**（带 alpha 好用） | 画矩形 / 竖线 |
| **`GuiGraphics.enableScissor(int,int,int,int)` / `disableScissor()`** | public | 需要裁剪时可用；本项目优先用"坐标钳制"代替（CP11） |
| **`GuiGraphics` 没有画线 API** | 只有 `fill`（axis-aligned 矩形）/ `drawString` / `blit` | 斜线要逐列 1px 竖直矩形拼（CP11） |
| **`Screen.hasShiftDown()/hasControlDown()/hasAltDown()`** | **public static，在 `Screen` 上**（不是 `Minecraft`） | 任何地方都能问修饰键 |
| `InputConstants.KEY_RBRACKET` | `public static final int = 93` | 备用键位 `]` |
| **`InputConstants.KEY_F9`** | `public static final int = 298` | **代码默认键位 `F9`**（功能键不产生字符，见 CP2） |
| `KeyMapping.matches(int,int)` | **只比 keyCode，忽略修饰键** | CP2 的关键注意 |
| `KeyMapping.releaseAll()` | `public static` | 开面板时清按键状态 |
| `EditBox` / `AbstractWidget.isFocused()` | public | CP2 规则 3 第 1 层 |
| **`AbstractContainerScreen.getGuiLeft()` / `getGuiTop()`** | **public**（Neo 加的访问器） | CP8 之二 画高亮 |
| **`AbstractContainerScreen.getSlotColor(int)`** | **public** | CP8 之二 用原版的每槽高亮色 |
| **`AbstractContainerScreen.renderSlotHighlight(GuiGraphics,int,int,int,int)`** | **public static**，内部 `fillGradient(guiOverlay, x, y, x+16, y+16, ...)`，**坐标系是 pose 相对的** | CP8 之二 |
| **`AbstractContainerMenu.getCarried()`** | public | CP8 之四 手持物品 |
| **`GuiGraphics.renderItem(ItemStack,int,int)`** | public | CP8 之四 |
| **`GuiGraphics.renderItemDecorations(Font,ItemStack,int,int)`** | public | CP8 之四（画数量） |
| **`GuiGraphics.renderTooltip(Font,ItemStack,int,int)`** | public | CP8 之四（悬浮提示） |
| **`IClientItemExtensions.of(ItemStack).getFont(stack, FontContext.ITEM_COUNT)`** | public Neo API | CP8 之四，忠实于 vanilla 的数量字体 |
| **`Slot.x` / `Slot.y` / `isActive()` / `isHighlightable()`** | 全是 public | CP8 之二；命中判定与 vanilla 一致 |
| **`EffectRenderingInventoryScreen`** | `InventoryScreen` 与 `CreativeModeInventoryScreen` 的共同基类 | 排除"玩家自己的背包" |
| `Player.displayClientMessage(Component, boolean)` | public | 动作栏状态提示 |
| **`Capabilities.ItemHandler.BLOCK`** | `BlockCapability<IItemHandler, @Nullable Direction>`（`net.neoforged.neoforge.capabilities`） | CP9 之二，模组机器的世界侧读取 |
| **`Level.getCapability(BlockCapability, BlockPos, @Nullable Direction)`** | `ILevelExtension` 的 default 方法（Level 实现了它）；`side=null` = 内部视角 | CP9 之二。命中 NeoForge 能力缓存，每 tick 多次查询成本可忽略 |
| **`IItemHandler.getSlots()` / `getStackInSlot(int)`** | interface 方法（`net.neoforged.neoforge.items`） | CP9 之二 |
| **`Slot.getContainerSlot()`** | 返回 `this.slot`（构造时传入的下标）；**`SlotItemHandler` 把 handler 下标传给 super** → 对它而言 `getContainerSlot()` 就是 handler 的槽位下标 | CP9 之二的下标依据 |
| **`Font.adjustColor`** | `private static`；alpha 位 == 0 时补 `0xFF` —— **只有文字有这个兜底，`fill` 没有** | CP11 的 alpha 位要求 |
| `Dist` | `net.neoforged.api.distmarker.Dist` | 客户端专用声明 |
| **`BlockState#getMenuProvider(Level, BlockPos)`** | **public**（`BlockState extends BlockBehaviour.BlockStateBase`，那一层把 protected 的 `BlockBehaviour#getMenuProvider(BlockState, Level, BlockPos)` 包成了 public 的两参重载），返回 `@Nullable MenuProvider` | CP17 判"这个方块右键会不会开界面" |
| **`MultiPlayerGameMode#useItemOn(LocalPlayer, InteractionHand, BlockHitResult)`** | public，返回 `InteractionResult` | CP17 模拟一次真实右键以打开容器界面 |
| **`Minecraft#hitResult`** | `public HitResult hitResult`（**字段**，不是 getter） | CP17 准星结果。`Minecraft#tick` 里 `gameRenderer.pick(1.0F)` 是**无条件**每 tick 调一次的（在 screen 判断之前），所以即使界面开着它也刷新 |
| **`BlockHitResult#getBlockPos()`** | public | CP17 |
| **`Slot#container` / `Slot#index` / `Slot#isFake()`** | 全是 **public**（`container` 是 `public final Container`，`index` 是 `public int`） | CP1 排除玩家背包（`slot.container == player.getInventory()`）、CP17 用 `Slot#index` 当菜单槽下标 |
| **`Player#getInventory()`** | `public Inventory getInventory()` | CP1 |
| **`Level#getBlockEntity(BlockPos)` / `Level#isLoaded(BlockPos)`** | public | CP17 判"是不是方块实体"；CP9 判"区块还在不在" |
| **`ServerTickEvent.Post`** | `net.neoforged.neoforge.event.tick.ServerTickEvent.Post`；`getServer()` 拿 `MinecraftServer` | CP4 的采样点。sources 核实：它在 `MinecraftServer#tickServer` 的**最后一行**发（`EventHooks.fireServerTickPost`），即 `tickChildren` 之后 —— 读到的是"这一世界 tick 结束时的状态"。集成服务端与客户端同进程，纯客户端模组也能收到 |
| **`ServerLevel#getGameTime()`** | public，转发给 `levelData.getGameTime()` | CP4 的样本时间戳。`ServerLevel#tickTime()` 每个服务端 tick 给它 **+1**（`tickTime` 标志为真时）→ `/tick rate 200` 就是 200/秒 |
| **ESC 暂停 / 单人暂停时** | `IntegratedServer#tickServer` 里 `if (paused) tickPaused()` 且**不调** `super.tickServer()` | 暂停期间**不发** `ServerTickEvent`、`gameTime` 也不走 → 采样自然停住，且时间轴不留假空档（CP4） |
| **客户端能否知道 tick 速率** | `Level#tickRateManager()` → `TickRateManager#tickrate()`；服务端通过 `ClientboundTickingStatePacket(tickRate, isFrozen)` 同步 | CP11 时间轴"秒"标签按真实速率换算的依据（21.1.209 核实） |
| **`BlockState#useWithoutItem(Level, Player, BlockHitResult)`** | **public**（`BlockStateBase` 把 `BlockBehaviour` 的 protected 五参重载包成了这个三参版本），返回 `InteractionResult` | CP17 判"这个方块右键会不会开界面"的**主判据**。容器方块为了做客户端预测必须在客户端返回 `SUCCESS`，所以在客户端直接调它是纯查询（不发包） |
| **`InteractionResult#consumesAction()`** | public；`SUCCESS` / `CONSUME` 为真 | CP17：`hasGui = result.consumesAction()` |
| **容器方块的客户端预测返回值** | 原版箱子/熔炉/木桶：`if (level.isClientSide()) return SUCCESS`；精妙存储 `ChestBlock`：javap 核实同样返回 `SUCCESS`；Mekanism `BlockTile`：按 `HasGui` 属性返回 `SUCCESS` / `PASS` | CP17 的实测依据（都是 sources / javap 真读出来的） |
| **`Item#onItemUseFirst(ItemStack, UseOnContext)`** | **不是 Item 上的方法**，是 NeoForge `IItemExtension` 的 default 方法（`net.neoforged.neoforge.common.extensions`） | CP17 用它判断"手上的物品会不会抢走这次右键"。反射 `getClass().getMethod(...)` 在**没覆写**时返回接口上的 default 方法，所以看**声明类的包名**（`net.minecraft.*` / `net.neoforged.*` = 安全） |

### 5.3 第三方模组 API（反射调用，零编译依赖）

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `mezz.jei.common.Internal#getJeiRuntime` | `public static IJeiRuntime`（**内部类，所以整段包 try/catch**） | 拿 JEI 运行时的入口 |
| `mezz.jei.api.runtime.IJeiRuntime#getIngredientListOverlay()` | `IIngredientListOverlay` | 公开 API |
| `mezz.jei.api.runtime.IIngredientListOverlay#hasKeyboardFocus()` | `boolean` | 判断 JEI 搜索框是否有焦点 |
| `mezz.jei.gui.input.GuiTextFieldFilter` | **`extends EditBox`**，但**不挂进 `screen.children()`** | 解释了为什么第 1 层守卫看不见它 |

**精致存储 2（refinedstorage-neoforge-2.0.9）** —— CP28 用到的那几个（全部 `javap` 核实过）：

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `common.grid.AbstractGridContainerMenu` | `public abstract`，`getRepository()` 是 **public** | 判"这是不是 RS 网格菜单"+ 拿仓库 |
| `api.resource.repository.ResourceRepository` | `getViewList() → List<T>`、`getAmount(ResourceKey) → long` | 界面行 + 客户端存量 |
| `common.api.grid.view.GridResource` | `getResourceForRecipeMods() → PlatformResourceKey`、`getName() → String` | 行的键 / 名字 |
| `common.support.resource.ItemResource` | `item() → Item`、`components() → DataComponentPatch` | 物品身份 |
| `common.support.resource.FluidResource` | `fluid() → Fluid`、`components()` | 流体身份 |
| `common.api.grid.Grid` | `isGridActive() → boolean`、`getItemStorage() → Storage` | 服务端读值入口 |
| `api.storage.Storage`（`extends StorageView`） | `getAll() → Collection<ResourceAmount>` | 全量清单 |
| `api.resource.ResourceAmount` | `resource() → ResourceKey`、`amount() → long`（record） | 一条资源 |
| `common.grid.AbstractGridBlockEntity` | `implements Grid`（`GridBlockEntity` 继承它） | "这台方块是 RS 网格" |

两条值得单独记的坑：
- **`Grid#getItemStorage()` 的名字会骗人**：它返回的是
  `network.getComponent(StorageNetworkComponent.class)` = **所有资源类型的根存储**
  （`StorageNetworkComponent extends RootStorage`），流体也在这份清单里。
- **`DataComponentPatch.toString()` 按插入顺序打印**（`Reference2ObjectMaps.fastIterable`），
  不是规范化的序列化 —— 拿它当身份指纹必须自己排序（C28-3）。
- **离线夹具要带上 Guava / fastutil**：RS 的菜单继承链会牵到它们（游戏里由 NeoForge 提供），
  只挂两个 jar 会得到一串 `NoClassDefFoundError`（那是夹具缺件，不是签名错）。

> 反查方法：`javap -p -cp run/mods/<mod>.jar <类名>`（直接读 class，不需要源码）。

### 5.4 反面清单（不存在 / 不要用）

- `Minecraft.pushGuiLayer()` / `popGuiLayer()` —— **Minecraft 上不存在**，
  只在 `ClientHooks` 上有（而且它是 Screen 栈，见 CP8）
- `GuiLayerManager` / `RegisterGuiLayersEvent` —— 那是 **HUD 图层**（血量/氧气那些），
  和 Screen 栈是两套东西。**别把它们和 `ClientHooks.guiLayers` 搞混**
- 不要指望 HUD 图层能"浮在界面之上"：`GameRenderer` 里 HUD 先画（约 1073 行）、
  Screen 后画（约 1092 行），界面自己的 `renderTransparentBackground` 渐变会把 HUD 盖住
- 不要试图给背景图层传真实鼠标坐标 —— `ClientHooks#drawScreen` 写死了 `MAX_VALUE`，
  想拿回 hover 效果只能像 CP8 之二那样自己补画

---

## 六、目录 → 层 的映射

```
src/main/java/com/jessetech/logiscope/
├─ LogiScopeMod.java            主类：注册事件监听与配置、持有唯一 ProbeManager
├─ LogiScopeConfig.java         客户端配置（CP6 之一）
├─ bind/
│  ├─ KeyBindings.java          输入层：快捷键 + Screen 按键拦截 + 无界面时的准星入口（CP2 / CP17）
│  ├─ TextInputGuard.java       输入层：三层"正在打字"守卫（CP2）
│  ├─ ContainerAnchor.java      绑定层：右键方块 → 维度 + 坐标锚点（CP9）
│  ├─ ProbeTarget.java          绑定层：探针目标（一组槽位引用 + 持久身份）（CP16）
│  ├─ ProbeContext.java         绑定层：当前可探测的容器（容器界面 / 准星）（CP17）
│  ├─ DataTarget.java           绑定层：NBT 数据目标（路径 / 名称 / 锚点；含 slot 锚点与 AE / RS 网络变体）（CP20 / CP24 / CP27 / CP28）
│  ├─ AeKeys.java               绑定层：AE 键的反射层（编码 / 解码 / 显示名 / 终端显示格）（CP27）
│  ├─ RsKeys.java               绑定层：RS 键与网格菜单的反射层（编码 / 组件指纹 / 仓库行）（CP28）
│  └─ DataCommand.java          绑定层：读方块 NBT 的取数通道（服务端直读 / 多人命令回退）（CP20）
├─ collect/
│  ├─ ContainerAccess.java      采集层：读菜单槽位（菜单兜底路径）（CP1）
│  ├─ WorldProbe.java           采集层：同步读方块实体 + 下标映射验证 —— 每实例一份（CP9）
│  ├─ ProbeSlots.java           采集层：无 GUI 容器的槽位清单（CP16）
│  ├─ DataProbe.java            采集层：按 NBT 路径读值（服务端 tick 采样）（CP20 / CP24）
│  ├─ AeNetworkReader.java      采集层：AE 网络物品数量（全反射，零编译依赖）（CP27）
│  ├─ RsNetworkReader.java      采集层：RS 网络资源数量（全反射，零编译依赖）（CP28）
│  ├─ ServerClock.java          采集层：全局权威世界 tick（状态机 / 回退路径）（CP15）
│  ├─ ProbeInstance.java        采集层：一个探针的状态机 + 每 tick 采样（CP4 / CP5 / CP9 / CP16 / CP20）
│  └─ ProbeManager.java         采集层：实例表 / 创建 / 删除 / 两个驱动点（CP4 / CP16）
├─ store/
│  ├─ Sample.java               存储层：最小可序列化单元（含 no_data / gap 标记）
│  ├─ SampleStore.java          存储层：环形缓冲 + version 计数（CP12）
│  └─ MarkerList.java           存储层：用户标记的时间点集合
├─ analysis/
│  ├─ Analyzer.java             分析层：摘要 / 逐 tick 差值（纯函数）
│  ├─ ChartData.java            分析层：扁平序列 → 按物品分组的数据线（CP10）
│  ├─ Aggregator.java           分析层：多格 → 按物品分别求和（CP16）
│  ├─ CurveMath.java            分析层：区间统计与事件分析（回放计算）（CP19）
│  ├─ Val.java                  分析层：自适应数值（long 精确 / double）（CP26）
│  ├─ Snbt.java                 分析层：SNBT 文本解析与路径取值（CP20）
│  ├─ AeKeyText.java            分析层：AE 键的字符串身份（纯文本）（CP27）
│  ├─ RsKeyText.java            分析层：RS 键的字符串身份（纯文本）（CP28）
│  ├─ SlotAlign.java            分析层："顺序 + 内容"对齐（CP24）
│  ├─ NbtSlotLayout.java        分析层：NBT 槽位列表的形状识别（CP24）
│  ├─ WaveColumns.java          分析层：波形列聚合（CP21）
│  ├─ RectRuns.java             分析层：矩形游程合并（CP21）
│  ├─ Series.java               分析层：一条数据线（含断点）
│  └─ SeriesPoint.java          分析层：一个采样点（含 breakBefore）
├─ archive/
│  ├─ ArchiveData.java          持久层：一份存档的内存形态
│  ├─ ArchiveFormat.java        持久层：JSON / CSV 读写（流式读头，C19-3）
│  └─ Archives.java             持久层：扫描 / 导出 / 读取 / 重命名（CP19）
└─ ui/
   ├─ ScopeScreen.java          呈现层：面板（根 / 选格 / 图表 / 回放 / 数据）布局与交互（CP6 / CP8 / CP12 / CP17）
   └─ ChartRenderer.java        呈现层：坐标轴 / 自动缩放 / 多线 / 框选 / 准线 / 标记（CP11 / CP18 / CP21）

dev-check/
├─ TestChart.java               离线断言（不启动游戏）：断点规则 / 分组 / 配色 / 统计 / 聚合 / 对齐 / 计算 / 数值解析 / 两种键身份
├─ BenchRender.java             渲染路径离线压测：列聚合与游程合并的 fill 次数（CP21）
├─ ProbeAeApi.java              反射链实证：AE2 的每一个反射签名（需第三方 jar，不在 offline-check.bat 里）
└─ ProbeRsApi.java              反射链实证：RS 的每一个反射入口（同上）（CP28）
```

**为什么分析层要保持"零游戏依赖"**：`analysis/`、`store/` 不 import 任何 `net.minecraft` ——
这是 `dev-check/` 离线断言能脱离游戏运行的前提（C8）。新增纯逻辑一律放这里，并补断言。

## 七、扩展点（新功能该往哪加）

| 想加的功能 | 加在哪 | 注意 |
|---|---|---|
| 新的统计量（吞吐、周期） | `analysis/Analyzer` | 保持纯函数，不引入状态；跳过标记样本（`Sample#isMarker`）；同时在 `dev-check/TestChart.java` 补断言 |
| 新的图表类型 | `ui/` 下新建渲染器 | 只读 `ChartData` / `List<Sample>`，不碰采集层 |
| CSV / JSON 导出 | `archive/` 包 | 从 `SampleStore.toList()` 取数据；标记样本（`no_data` / `gap`）单独列出，不写成 0。分组信息复用 `ChartData.series()` |
| 在波形上标点 | `ui/ChartRenderer` + `ScopeScreen` 的标记集合 | 标记是**用户数据**，不放 `SampleStore` 的环形缓冲（会被覆盖）。选区的两个端点是现成落点（`ChartRenderer.Selection`） |
| 换配色 / 加调色板条目 | `ui/ChartRenderer.PALETTE` | 按 series **下标**取色；往末尾加颜色不影响已有线的颜色 |
| 图上再叠一层信息（速率曲线等） | `ui/ChartRenderer` 里加一条"派生线" | 派生线的 y 尺度可能和 count 差很多 → 需要第二条纵轴，先想清楚 |
| 多格监控一个新容器 | `ui/ScopeScreen` 的选格页 + `analysis/Aggregator` | 聚合规则改动的第一站是 `Aggregator` + `dev-check` 断言，不在采集层里改 |
| 换一种"选格"的交互 | `ui/ScopeScreen` 的 `cellMode` / `applyCell` / `slotAt` | 两条铁律：面板外的事件不转交、拖动按线段采样（CP17） |
| 支持一种新的"入口"（例如对着某类方块自动建探针） | `bind/ProbeContext` + `bind/KeyBindings#openFromCrosshair` | 判"是不是容器"要和 `WorldProbe#readNow` 的两条路径一致 |
| 支持别的搜索类模组（REI / EMI） | `bind/TextInputGuard` 里加一个小探针 | 照既有 JEI 探针的结构写；先用 `javap -p -cp run/mods/<mod>.jar` 查它的 API |
| 让容器的键盘操作也能用 | `ui/ScopeScreen` 补 `hoveredSlot` | 见第四节「待决问题 3」 |
| 兼容自绘界面的模组 | `collect/` 下新建 accessor | 需要面向接口重构 `ContainerAccess` |
| 某模组机器的下标对不上（capability 路径） | `WorldProbe#readNow` 按 `BlockEntity` 类型加分流 | 通用路径保持"Container → capability"不动；验证机制见 CP9 之三 |
| 图例可滚动 / 可折叠 | `ui/ScopeScreen` 的 `capRows` / `renderLegend` | 见第四节「待决问题 5」 |
| 给纯逻辑层加更多断言 | `dev-check/` 下新建 `Test*.java` | 前提：那一层不 import 游戏类 |
