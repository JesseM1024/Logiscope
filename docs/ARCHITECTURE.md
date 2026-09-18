# LogiScope · 架构与关键逻辑路径

这份文档是 vibe coding 时最该先读的一份。
它回答三个问题：**代码怎么分层**、**风险在哪**、**哪些 API 已经核实过**。

> ⚠️ **本文档第四节的 API 结论曾被推翻过一次**，见 CP8。
> 结论都是拿本地 `neoforge-21.1.209-sources.jar` 逐行查证的，查到什么就写什么。

---

## 一、分层

```
┌───────────────────────────────────────────────────────────────┐
│ 输入层  bind/KeyBindings       按键 → "想打开/收起面板"          │
│         bind/TextInputGuard    "玩家是不是正在打字"（三层守卫）   │
├───────────────────────────────────────────────────────────────┤
│ 绑定层  bind/ContainerAnchor   右键方块 → 「维度 + 坐标」锚点     │
│         bind/ProbeTarget       探针目标（一组槽位 + 持久身份）    │
│         bind/ProbeContext      ★ 当前可探测的容器（界面 / 准星）  │
├───────────────────────────────────────────────────────────────┤
│ 采集层  collect/ContainerAccess  读菜单槽位（★ 界面开着时的数据源）│
│         collect/WorldProbe       读方块实体（★ 界面关了时的数据源）│
│         collect/ProbeSlots       无 GUI 容器的槽位清单（问服务端） │
│         collect/ServerClock      ★ 两只全局时钟                 │
│         collect/ProbeInstance    ★ 一个探针：状态机 + 每 tick 采样│
│         collect/ProbeManager     ★ 实例表 / 创建 / 删除 / 驱动    │
├───────────────────────────────────────────────────────────────┤
│ 存储层  store/SampleStore      环形缓冲 + version，唯一真相       │
│         store/Sample           最小可序列化单元（含 no_data 哨兵）│
├───────────────────────────────────────────────────────────────┤
│ 分析层  analysis/Analyzer      纯函数：摘要 / 逐 tick 差值        │
│         analysis/ChartData     纯函数：扁平序列 → 按物品分组的数据线│
│         analysis/Aggregator    ★ 纯函数：多格 → 一条样本的内容    │
│         analysis/Series(Pt)    数据线的形状（含断点标记）         │
├───────────────────────────────────────────────────────────────┤
│ 呈现层  ui/ScopeScreen         只读：三页面（根/选格/图表）布局与交互│
│         ui/ChartRenderer       只读：坐标轴 / 波形 / 框选 / 准线   │
├───────────────────────────────────────────────────────────────┤
│ (规划)  持久层  export/*       CSV / JSON 导出                  │
└───────────────────────────────────────────────────────────────┘
```

### 层间契约（改代码时必须守住）

| 契约 | 内容 | 违反的后果 |
|---|---|---|
| C1 | `ProbeInstance` / `ProbeManager` 不认识任何 UI / 渲染类 | 界面崩了会连带采样一起挂 |
| C2 | `SampleStore` 只存 `(tick, itemId, count)`，不持有 `ItemStack` | 无法离线分析、无法导出、容易泄漏世界引用 |
| C3 | `Analyzer` / `ChartData` / `Aggregator` 是纯函数，不持有状态 | 无法单元测试，图表和导出会算出不同结果 |
| C4 | `ui/*` 只读，绝不直接改采样数据 | 界面刷新时机不可控，容易写出竞态 |
| C5 | 只有一个探针管理器：`LogiScopeMod.probes()`；每个实例各持一份 `SampleStore` + `WorldProbe` | 多处 `new ProbeManager()` 会导致数据分裂；共享 `WorldProbe` 会让两个探针互相覆盖读数 |
| C6 | `WorldProbe#readNow` / `ProbeSlots#readNow` **只能在服务端线程跑** | 跨线程读 ServerLevel 是数据竞争 |
| C7 | `no_data` 样本不进统计（`Analyzer` / `ChartData` 负责过滤，但要让它**占时间轴**） | 断档会被算成"数量掉到 0"，净流量全错；反过来丢掉时间轴会让图上的断档消失 |
| C8 | **`analysis/` 与 `store/` 不 import 任何 `net.minecraft` / `net.neoforged`** | 一旦引入游戏类，`dev-check/` 的离线断言就跑不了，只能靠进游戏肉眼看 |
| C9 | 一个探针每 tick 只写**一条**样本（多格先聚合） | 下游（分组 / 波形 / 框选统计）会看到交错的多条序列，时间轴语义崩掉 |

**一句话总结数据流**：

```
按键盘 → KeyBindings →（容器界面开着? ContainerAccess : WorldProbe）
                     → ProbeManager 驱动每个 ProbeInstance 采样
                     → Aggregator 把多格合成一条 → SampleStore(version)
                     → ChartData/Analyzer（version 变了才重算）
                     → ChartRenderer 画到 ScopeScreen 上
```

---

## 二、关键逻辑路径

标注 ⚠️ 的是**风险点**，改这些代码前务必先读本节。

### CP1 · 拿到容器内容　风险：低

`player.containerMenu.slots` → `Slot#getItem()` → `ItemStack`

只列**外部设备**的槽位，判两次：

| 判据 | 排除什么 |
|---|---|
| `player.containerMenu != player.inventoryMenu` | 整个菜单就是玩家背包的情况（关掉容器界面后单独开面板） |
| `Slot#container != player.getInventory()` | **菜单里混着的玩家背包那 36 格** |

> ⚠️ 第二条是第九轮补的。旧文档一直写着"会把玩家背包整体排除掉"，但代码里只有第一条 ——
> 于是打开箱子时列表里其实混着自己背包的格子。单格时代它只是"列表有点长"；
> 多格监控 + 可视化选格之后，它变成"点到自己背包的格子也会被当成设备槽"，语义就错了。
> 顺带也跳过 `Slot#isFake()`（部分模组拿假槽位占位）。

### CP2 · 在 GUI 打开时捕获快捷键　风险：**高** ⚠️⚠️

**这是整个项目最容易写错的一处，已经踩过两次。**

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

由这段源码推出**四条必须遵守的规则**，每一条都对应一个真实踩过的坑：

| # | 规则 | 不这样做的做法会怎样 |
|---|---|---|
| 1 | 用 `ScreenEvent.KeyPressed.Pre` + `setCanceled(true)` | 拿不到按键，或者按键继续传给原界面触发别的快捷键 |
| 2 | **必须去抖**（事件对 `action==2` 也触发，且不带 action 信息） | 按住 `]` 会以 ~30 次/秒的频率把面板开了又关 |
| 3 | **必须判断"玩家是不是正在打字"** | 在搜索框里打 `hopper`，那些字符一样会走到这里（详见下面的三层守卫） |
| 4 | **必须另接一条无界面时的路径** | 关掉容器界面后在跟踪状态，按 `]` 完全没反应（Screen 事件不触发） |

规则 4 的补法：没有界面时按键走标准 KeyMapping，所以在客户端 tick 里
排空 `OPEN_SCOPE.consumeClick()`，排空后**只有 `screen == null` 时才真的响应**。
（一定要排空：否则界面开着时攒下的点击会在面板关掉那一刻集中兑现，把面板立刻弹回来。）

#### 规则 3 展开：三层文本输入守卫（`bind/TextInputGuard`）

只要快捷键是个**能被打出来的字符**，就一定会和搜索框打架。
本项目一路试过 `O` → `]`，两次都被咬到：

| 键位 | 结果 |
|---|---|
| `O` | 在 JEI 搜索框里打 `hopper`，那个 `o` 直接弹面板 |
| `]` | 三层守卫能挡住大部分，但"打完字 → 失焦 → 再按"这条路上仍会多拦一次 |
| **`F9`（最终选择）** | **功能键不产生字符，冲突通道从根上不存在** |

所以最终方案是**换键位 + 保留守卫**两条一起上：键位解决 99% 的情况，
守卫保的是"用户把快捷键改回字母/符号键"和"某些模组的输入框吃掉所有按键"。

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

##### ⚠️ 第 3 层的实现有一个必须避开的陷阱

只记**上一个**字符事件的结果（一个布尔量），**绝对不要用累计计数**：

``` java
// ✅ 对：一个字符事件结束时，它恰好等于"这个字符有没有被吃掉"
public static void onCharTypedPre(...)  { lastCharConsumed = true;  }
public static void onCharTypedPost(...) { lastCharConsumed = false; }

// ❌ 错（本项目真踩过，第三轮 A7/A10/A12 失败就是它）
private static int preCount, postCount;
private static boolean consumed() { return preCount > postCount; }
```

为什么"计数差"是错的：**被吃掉的字符会在差值上留下一个永久的 +1**。
之后每个未被吃掉的字符同时给两个计数各 +1，差值只是**原地保持、永远不会归零**。
于是"在任意输入框里打过一次字"就等于"永久打开文本模式"，快捷键彻底失效。

> 通用教训：**差值型状态只在"每个事件都成对抵消"时才成立**。
> 只要存在"只加不减"的分支，差值就会单调增长 —— 这种 bug 在测试里很容易被
> 误判成"偶尔失灵"，必须从原理上排掉。

它**会自愈**（正确实现下）：`KeyPressed` 在 `charTyped` **之前**触发，所以

| 场景 | 过程 |
|---|---|
| 在输入框里 | `]` 的字符被吃掉 → Post 不来 → 布尔量保持 true → 每次都拦得住 |
| 不在输入框里 | `]` 的字符没人吃 → Post 把它置回 false → **下一次按键就正常了** |

代价是"刚打完字立刻想开面板"时要多按一次；再叠一个 60 tick 的时效窗口，
避免"很久以前打过一次字"抑制快捷键。

**还有一个反直觉事实**：`KeyMapping#matches` **不看修饰键**。
源码只有一句比较 `key.getType() == KEYSYM && key.getValue() == keyCode`，
所以把快捷键设成 `Ctrl+O` 毫无意义 —— 单独按 `o` 一样命中。
这就是"改了 Ctrl+O 还在搜索框里触发"的原因。

**换默认键位的坑**：按键绑定会被写进 `run/options.txt`
（形如 `key_logiscope.key.open_scope:key.keyboard.right.bracket`），**那份存档优先于代码默认值**。
改了默认值之后，要么删掉那一行，要么在「选项 → 控制」里手动重绑 ——
否则你会以为"改了但没生效"。

### CP3 · 打开面板但不丢菜单　风险：中 ⚠️

见 **CP8**。一句话：用 `ClientHooks.pushGuiLayer/popGuiLayer`，
**不要**用 `Minecraft#setScreen` 替换式打开（那会把原界面从屏幕上抹掉）。

### CP4 · 每 tick 采样　风险：低

`NeoForge.EVENT_BUS.addListener(ProbeManager::onClientTick)`，事件类型 `ClientTickEvent.Post`。

- `Post` 在 tick 逻辑之后触发，此时同步数据已更新完毕
- 事件在**游戏总线**上
- 同一次 tick 里兼职做三件事：`ContainerAnchor.tick()`（锚点计时）、
  `ServerClock.tickClient()`（推进两只全局时钟）、
  然后遍历**所有**探针实例各采样一次
- `KeyBindings#onClientTick`（无界面时的快捷键）挂在同一个事件上，是另一个监听器

### CP5 · 数据源失效的检测　风险：中 ⚠️

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
| 世界侧读值异步化 | `server.execute` 提交任务 + volatile 缓存，不在渲染/客户端线程读服务端对象 |
| 不引入图表库 | 只用 `GuiGraphics#fill` + `drawString` |

**已修的旧问题**：上一版 `ScopeScreen.render` **每帧**调 `store.toList()` 并
`Analyzer.summarize(samples)`，样本多时是 60 次/秒的全量遍历 + List 分配。
现在两者都进了 `rebuildIfNeeded`，只在 `SampleStore#version` 变化后跑一次（20 次/秒）。

**仍未消除的小分配**（都已评估为可忽略，不再优化）：

| 位置 | 频率 | 量级 |
|---|---|---|
| `SampleStore.toList()` 复制 | 20 次/秒（仅面板开着时） | ≈290 KB/次，年轻代 |
| `layout()` 里的 `wrap`/`place` | 1 次/帧 | 几个小 ArrayList |
| `window()` 返回 `long[2]` | 1 次/帧 | 32 字节 |
| `ChartRenderer` 的读数字符串拼接 | 1 次/帧（鼠标在图上时） | 一个 StringBuilder |

> 判断标准：**每帧的常驻分配为零**，只留下与"数据变化频率"和"用户操作"同阶的分配。
> 真要做帧时间实测，用 `VERIFY.md` 的 N12 挂机 10 分钟观察。

### CP7 · 纯客户端成立性　风险：低

`@Mod(value = MODID, dist = Dist.CLIENT)` —— 加载器在专用服务端上直接跳过本模组。
配套地，`neoforge.mods.toml` 里的两条依赖都写了 `side="CLIENT"`。

⚠️ 注意：单人模式下集成服务端与客户端在同一个 JVM，所以
`PlayerInteractEvent.RightClickBlock` 会**在两侧各触发一次**，
监听器里必须 `if (!event.getLevel().isClientSide()) return;`。

### CP8 · 不遮挡原界面（GUI 图层栈）　风险：中 ⚠️

**结论：`ClientHooks.pushGuiLayer/popGuiLayer` 是货真价实的 Screen 栈。**

> 📌 这一条推翻过本项目文档里的旧结论（旧文写的是
> "属于 HUD 图层系统，不是 Screen 栈，不要用"）。**旧结论是错的**，
> 那是把 `ClientHooks.guiLayers` 和 `GuiLayerManager`（HUD 图层）搞混了。

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
还让这两样东西一起失效（第三轮 B7/B8 反馈"物品拿起来了但图标和数字不见了"）：

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

### CP9 · 关掉界面之后继续跟踪　风险：中 ⚠️

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

**值从哪来**：三条路，按可信度排序。

| 状态 | 数据源 | 得到什么 |
|---|---|---|
| `RECORDING` | 菜单槽位（服务端同步下来的） | 真值，且零延迟问题 |
| `FOLLOWING` + 单人 | 集成服务端的方块实体 | 真值，最多差 1 tick |
| `FOLLOWING` + 多人 | 拿不到 | **记 no_data**，不假装是 0 |

多人的边界是硬性的：容器内容只通过菜单包同步，界面一关客户端手里就什么都没有了。
`isLocalServer()` 为假时直接不提交读取任务。这时时间轴仍然连续，
重新打开界面的那一刻会拿到真值 —— **两段之间的差值依然有效**，这正是用户要的信息。

读取为什么绕到服务端线程：`ServerLevel` / 方块实体属于服务端 tick 线程，
客户端线程直读是数据竞争。`WorldProbe.request` 用 `server.execute` 提交任务，
结果写进 volatile 缓存，客户端下一 tick 取用。缓存还带"提交时的 tick"，
超过 5 tick 就算过期（服务端卡顿时不要把陈旧值当成新值写进序列）。

#### CP9 之一：接缝处那条**假的** no_data（已修）

曾经有一段"关掉界面必定记 1 tick no_data"的现象。它不是读取失败，
而是**异步管道的启动延迟**：

```
第 N   tick  客户端发现界面关了（菜单已失效，读不到）→ 这一 tick 什么也没记
第 N+1 tick  WorldProbe.request() 把任务丢给服务端线程，
             而**同一 tick 内** latest() 立刻就要取值 —— 服务端线程还没轮到
             → 必然返回 null → 记下一条 no_data
```

两个毛病叠在一起：时间轴上断了一格，外加一条**误导性的"无数据"**
（玩家会以为"这台机器读不到"，其实只是还没排上队）。

修法两件事同时做：

| 位置 | 做法 |
|---|---|
| 过渡那一 tick | `ProbeInstance` 持有 `lastViews`（上一 tick 刚读到的**槽位快照**，最多 50 ms 旧），在检测到界面关闭时**先把它记下去**，补住空洞 |
| 下一 tick 的取值 | `WorldProbe.seed(lastViews)` 把这些值垫进缓存，`latest()` 的年龄判定（≤ `WORLD_READ_MAX_AGE`）让它算"新鲜"，于是异步结果到达前时间轴是连续的 |
| **第六轮补充：过渡 tick 立刻 `request()`** | 只靠 seed 的话，下一 tick 的 `latest()` 拿到的仍是 seed（旧值被续命 1~2 tick，1t IO 测试里脉冲被拉宽，第六轮 M1）。过渡 tick 就把读取任务发给服务端，异步真值最早在下一 tick 接管，seed 退化成"异步失败时的兜底" |
| **第六轮补充：重开界面也垫一格** | FOLLOWING → RECORDING 的切换 tick 原来什么都不记，时间轴断一格。用手头最新的世界侧读值垫上（与关界面方向对称） |

**接缝的物理极限**：过渡 tick 手里只有 lastKnown（GUI 最后一拍），
它最多把真实变化"晚看到"1 tick —— 要消除它只能记空洞或假 no_data，都更糟。
第六轮修完后，接缝两侧各剩**至多 1 tick** 的承载，这是不造假数据的前提下的最小值。

**这不是把 no_data 藏起来**：垫值会在 `WORLD_READ_MAX_AGE`（5 tick）后自然过期。
区块卸载、方块被拆、多人读不到这些情况，该记 no_data 还是照记 ——
验证清单里的 N1c 就是在盯这一点（走远到区块卸载，`no-data` 必须开始增长）。

> 教训：**"读不到"和"还没读到"是两回事**。
> 异步读取的第一次调用天生拿不到结果，把它的第一次返回当成"失败"上报，
> 是在制造假警报。修这类问题的方向是"用最后一次已知值兜住 + 给个过期窗口"，
> 而不是把等待包装成失败。

**认不出来的情况**：右键信息过期 / 目标模组用快捷键开界面 → 锚点为 null
→ 退化成纯运行时身份，关掉界面只能 `PAUSED`。这是刻意的：
**宁可明确停住，也不要往别的方块上记。**

#### CP9 之二：模组机器的世界侧读取（capability 回退）

`readNow` 原来只认原版 `Container`。第五轮实测暴露了它的边界：
**绝大多数模组机器不实现 `net.minecraft.world.Container`**。javap 反查实锤：

| 模组（版本） | 方块实体 | 实现的原版接口 |
|---|---|---|
| Sophisticated Storage 1.5.20 | `net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity` | 只有模组自己的接口（`IControllableStorage`、`ILockable`…），**没有 `Container`** |
| Mekanism 10.7.17 | `mekanism.common.tile.machine.TileEntityEnergizedSmelter` | 走 `TileEntityElectricMachine` 一族，**没有 `Container`** |

这类机器的容器内容只在界面打开期间由菜单包同步到客户端；界面一关，客户端手里什么都没有。
原路径 `readNow` 直接返回 null → 全部记成 no_data。

**修法：加第二条读取路径 —— NeoForge 的方块能力。**

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
| **下标不能直接信 `getContainerSlot()`（第六轮 M4 实锤）** | 对 NeoForge 的 `SlotItemHandler`，它把 handler 下标传给 `super`，`getContainerSlot()` 恰好就是 handler 下标；**但 Mekanism 的 `InventoryContainerSlot` 构造是 `super(emptyInventory, 0, x, y)` —— 下标硬编码 0**（javap -c 实锤），且它包装的 `IInventorySlot` 是单槽接口、不带位置信息，客户端**拿不到**真下标。解法见下：挂探头瞬间在服务端做内容指纹验证 |

> 反查方法（这次用的）：`javap -p -cp run/mods/<mod>.jar <BE全类名>` 看它 implements 什么；
> 再在 jar 里按 `capabilit|ItemHandler` 搜类名，确认它走哪条能力注册路径。

#### CP9 之三：下标映射验证（第六轮 M4 的解法）

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

**验证不了时的行为（用户要求的退路）**：FOLLOWING 状态下弹一次动作栏警告
（`logiscope.msg.index_unverified`，每探头只弹一次），读取退回 `getContainerSlot()`
并如实记录 —— 用户至少知道"跟踪的可能不是那一格"。
典型歧义场景：对着**空格子**挂探头、机器里多个格子装着同种等量的物品。

---

### CP10 · 把时间序列变成「多条按物品分组的数据线」　风险：中 ⚠️

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

三种情况统一落到 `SeriesPoint#breakBefore` 一个标记上，渲染层只管照着断开：

| 情况 | 为什么必须断 |
|---|---|
| 物品换了 | 从"64 个铁锭"变成"1 个铜锭"，差值 −63 是假的 |
| 中间是 `no_data` | 那段时间我们没观测到，**把两点连起来等于编造数据** |
| tick 不连续（间隔 > `CONTINUITY_GAP`） | 采样停了（暂停 / 状态切换），同样不能连 |

契约：**每条线的第一个点一定是"段起点"**（`breakBefore = true`）。
它前面没东西可连，所以这是无害的；渲染层靠 `colStart` 决定"要不要连到左邻列"，
而首列本来就没有左邻列。

`ChartData` 还顺手给出 `switches`（物品切换次数），面板上显示成 `switch N`。

#### 配色：按**下标**取色，而不是按名字哈希

`series` 的顺序 = 物品**首次出现**的顺序（`LinkedHashMap` 保证），
所以"第几条线用什么颜色"是稳定的 —— 不会因为中途冒出一个新物品，
旧线就集体换色。这是"按下标取色"而不是"按 itemId 哈希取色"的原因：
后者在调色板轮转时也可能撞色，前者至少保证了"先出现的线颜色固定"。

`minecraft:air`（格子空着）单独给一个低饱和灰蓝：它不是物品，不该和物品线抢注意力，
但它**仍然是一条正经的线**（数量恒 0）—— 丢掉它就看不出"这一格被抽空了"。

---

### CP11 · 波形渲染：坐标轴、自动缩放、尖峰、稀疏数据　风险：中 ⚠️

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

> 这是"挂机 10 分钟不掉帧"的依据所在；实测确认见 `VERIFY.md` N12。

**一个已知的、故意不修的边界**：若在同一个像素列里发生了物品切换（要求缩放到
极远 + 恰好切换），旧颜色会在这一列多画一小截。列宽 1px，肉眼不可见，
不值得为它把聚合逻辑复杂化。

#### ★ 第五轮实机的教训：`fill` 的颜色必须带 alpha 位 ★

第五轮"图里没有任何线"，数据层 47 条断言全过、渲染逻辑复查无误，最后发现是
**调色板写成了 6 位十六进制（`0x5DCAA5`）—— alpha 位是 0，`fill` 画出来完全透明**。

| API | alpha=0 时的行为 |
|---|---|
| `GuiGraphics#fill` / `blit` | **完全透明，什么都看不见**。没有兜底 |
| `drawString`（`Font#drawInBatch`） | `Font.adjustColor`（Font.java:172）会兜底：`alpha 位 == 0 → 补 0xFF`，所以文字照样可见 |

后果就是"文字都在、图形全没"—— 极具迷惑性，因为它**看起来像逻辑 bug 而不是颜色 bug**。
本项目所有 fill 颜色自此统一写满 8 位（`0xFF5DCAA5`），并在调色板常量上写了注释防回归。

> 顺带解释一个历史悬案：第一轮的火花线用的也是 6 位色（`0x9BE1CB`），
> 所以**火花线从来没被看见过**——只是当时验收项没盯着它看，漏了五轮。

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

#### 第六轮实测抓到的一个隐蔽 bug：先改状态、再调"读状态的函数"

`beginSelection` 原来的顺序是错的：

```java
selecting = true;        // ← 先置位
long[] win = window();   // ← window() 第一分支就是 "if (selecting) 返回锁住的窗口"
selectWindowStart = win[0];
```

首次框选时 `selectWindowStart/End` 还是初始值 0 ——
`window()` 读到的是**自己正要写入的旧值**，窗口被锁成 `[0,0]`，
波形瞬间变"这一区间没有数据"。且永远进不了冻结态，
连带的假象是「实时」按钮"点不动"（它只在冻结态才有可见变化）。
第六轮 M12/M13/M14/M16 四条失败全是这一个根因。

> 教训：**给"读状态的函数"加分支时，先查所有调用点有没有"边改状态边调它"。**
> 修法很简单（先取窗口再置位），难在症状离根因太远 ——
> "按下就显示无数据"看起来像数据问题，实际是交互层的顺序问题。

#### 布局只有一份

`ScopeScreen#layout()` 产出这一帧的全部矩形（含每个按钮/图例块的位置），
**渲染与鼠标命中共用同一份结果**。上一版是"渲染按一套坐标、点击按另一套坐标"，
这类错位是最难查的一类 bug —— 明明按钮画在那里，就是点不到。

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

### CP13 · 时间刻度：世界 tick（第七轮 N12）　风险：中 ⚠️

```
实测：用 /tick rate 把游戏流速调慢后，LogiScope 仍然每 0.05s 记一条，
其中一大半是"世界没动时的重复值" —— 数据全错。
```

**根因**：`/tick rate` 只改**服务端**的 tick 频率；客户端的 `ClientTickEvent`
照旧每秒 20 个。之前样本时间戳用的是客户端节拍（自增计数），
它跟世界 tick 完全脱节。

**修法**：样本时间戳改用 `Level#getGameTime()`（世界 tick 计数，public，已核实），
并且**世界没走 tick 就跳过采样**（gameTime 与上次相同 → 不记重复样本）：

| 场景 | 行为 |
|---|---|
| `/tick rate 5` | 世界每 200ms 走 1 tick → 每 4 个客户端 tick 里 3 个跳过，每世界 tick 恰好一条样本 |
| ESC 暂停（单人） | 服务端冻结 → gameTime 不动 → 不采样（之前会记一串重复的平线） |
| 主菜单（无世界） | 退回客户端节拍（`mc.level == null`），行为与旧版一致 |

**连带统一**：`WorldProbe` 的 seed / request / 年龄判定全部改用同一刻度 ——
世界没走 tick 时缓存不算陈旧（它本来就是"当前"的）。统计块速率单位从 n/s 改为
**n per tick**（`fmtRate`），与横轴单位一致。

**保留客户端节拍的地方**：动作栏去抖、快捷键连发去抖 —— 它们要的是真实时间。

---

### CP14 · 缩放交互：光标锚定（第七轮 P5）　风险：低

```
实测：冻结态滚轮做的"左右平移"被理解成了"缩放到最新处"；
且缩放有时没反馈、有时一格就到头。
```

三个根因、三个修法：

| 症状 | 根因 | 修法 |
|---|---|---|
| 冻结态滚轮行为不符合预期 | 平移的右端被钳到最新数据 → 看起来"跳回最新" | 改成**光标锚定缩放**：光标处的 t 值保持原位、两侧缩放（视频编辑软件时间轨的方式） |
| 缩放多按几次才有反应 | `window()` 在"数据未铺满窗口"时把起点钳到第一个数据点 → 缩放前后窗口完全一样 | 记 `userZoomed` 标志：用户主动缩放过就忠实反映 windowTicks（起点钳制只在"从未缩放"时生效，attach / 清空 / 全览复位） |
| 一格就缩到头 | 滚轮 ×2 太猛 + `MIN_WINDOW=40` 与步长相撞（40÷2=20→钳回 40） | 滚轮 1.25×/格、按钮 1.5×/次；MIN_WINDOW 降到 20 |

**锚点约束满足不了时，以数据端点为准**（用户要求）：窗口推出数据边界 → 贴
起始/结束端点；冻结态窗口最大就是"全览"（= 数据跨度）。实时态仍右端锚定
（最新数据必须在视野里）。

---

### CP15 · 两只时钟：权威世界 tick + 客户端 tick（第八轮 N13）　风险：中 ⚠️

```
实测：tick rate 200 → 采样 20t 一次、连续两三 t 相同数据；
      tick rate 40 → 10t 错误数据（64/t 的 IO 记成 128）+ 10t no_data 虚线。
```

**根因（sources jar 核实）**：客户端的 `Level#getGameTime()` 根本不是世界 tick 的
忠实时钟，而是一条**错拍的阶梯**——

| 事实 | 出处 |
|---|---|
| 客户端每客户端 tick 把本地 gameTime **+1** | `ClientLevel#tickTime()`（且受客户端 `tickRateManager` 控制） |
| 服务端每 **20 个服务端** tick 才发一次绝对时间包砸给客户端 | `MinecraftServer#tickChildren` 里 `tickCount % 20 == 0 → synchronizeTime` |

- tick rate 20：本地 +1 恰好等于纠正值 → 无缝（第七轮之前一切正常，问题被掩盖）。
- tick rate 40：世界每客户端 tick 走 2，本地只 +1，且每 10 个客户端 tick 被 +20 纠正
  → 阶梯平台期被"世界没走 tick"判定吞掉（成段虚线），跳变时一条样本横跨 20 个
  世界 tick（IO 速率全错）。
- tick rate 200：每 2 个客户端 tick 被 +20 纠正 → 采样 20t 一次 + 重复值。

**修法：把"世界 tick"和"读值延迟"拆成两只钟**（都在 `WorldProbe`）：

| 钟 | 用途 | 实现 |
|---|---|---|
| **权威世界 tick** `worldGameTime(fallback)` | 样本时间戳、"世界没走 tick 就跳过采样"判定 | 单人：`Sampler#onClientTick` 每 tick 提交一个 `server.execute` 任务，在**服务端线程**读 `ServerLevel#getGameTime()` 缓存进 volatile（权威、随 `/tick rate` 正确伸缩、freeze/ESC 时冻结）；多人/主菜单退回客户端值 |
| **客户端 tick** `tickClient()` | `WorldProbe` 异步读值的新鲜度（`latest(maxAge)`） | 读值延迟是真实时间量（提交到服务端线程、1~2 个客户端 tick 后回来），与世界流速无关；tick rate 200 时按世界 tick 判新鲜会把刚回来的真值判成超龄 |

**仍然存在、且记录在案的物理极限**：客户端每秒最多观测 20 次 —— tick rate 200 时
世界每客户端 tick 走 ~10t，中间 9 个状态**不可观测**，样本间隔 ~10t 是采集端的
物理极限，不是 bug。数据的累计值仍是对的，速率按 n/t 换算也仍是对的。

**多人限制（不变）**：没有集成服务端就没有权威时钟，退回客户端阶梯值 ——
而多人下 FOLLOWING 本来就不可用，RECORDING（界面开着）受影响有限。

---

### CP16 · 探针实例化 + 多格聚合（第十轮）　风险：中 ⚠️

```
需求：面板里要有"正在运行的探针"列表（每行带删除按钮）；一个探针可以同时监控好几格，
      记录结果是把它们加在一起。
```

#### 1. 为什么拆成 ProbeManager + ProbeInstance 两层

原来的 `Sampler` 是**全局唯一**的（"项目里只有一个探头"是当时的刻意取舍）。
现在要多个探针**同时**在跑，于是：

| 类 | 职责 | 生命周期 |
|---|---|---|
| `ProbeManager` | 实例表、创建 / 删除、活跃指针（界面在看哪一个）、每 tick 驱动所有人、`followAfterClose` 这个全局偏好 | 整个游戏会话一个 |
| `ProbeInstance` | 一个探针的状态机、自己的 `SampleStore`、自己的 `WorldProbe` | 创建时生、删除时灭 |

**所有实例同时采样，而不是只跑"当前在看的那一个"**：需求是"用于选择"，
语义就是它们都在跑。如果只跑活跃的那个，切回来会看到一段空白 ——
而用户要的恰恰是"我离开的这段时间产线发生了什么"。
代价是每个实例各占一份 36000 样本的环形缓冲，上限 16 个实例（`MAX_PROBES`）。

#### 2. 哪些状态必须"每实例一份"，哪些必须"全局唯一"

这是这次重构最容易写错的地方，独立列出来：

| 状态 | 归属 | 理由 |
|---|---|---|
| `SampleStore` | **每实例** | 各有各的记录 |
| `WorldProbe`（读值缓存 / pending / 下标验证） | **每实例** | 共享的话两个探针会互相覆盖读数 —— 这是最隐蔽的一类 bug（数据看起来"偶尔跳一下"） |
| `watchedContainerId`、`manualPause`、`lastViews`、`warnedUnverifiedIndex` | **每实例** | 状态机私有 |
| 客户端 tick 计数、权威世界 tick（`ServerClock`） | **全局唯一** | "现在世界走到第几 tick"全世界只有一个答案；留在实例里会导致删掉一个实例就把时钟清掉 |
| `followAfterClose` | **全局唯一**（在 Manager 上） | 它是用户偏好，不是探针状态 |

#### 3. 多格 → 一条样本：聚合规则

一条 `Sample` 只能表示「一个物品 id + 一个数量」，而多个被监控的格子可能装着不同的东西。
规则（实现见 `analysis/Aggregator`，断言见 `dev-check/TestChart.java`）：

| 被监控的格子 | 聚合结果 |
|---|---|
| 全部为空（总数 0） | `minecraft:air` × 0（画成那条灰蓝线，表示"被抽空了"） |
| 非空的格子里只有**一种**物品 | 那种物品 × **总数** |
| 非空的格子里有**多种**物品 | `logiscope:mixed` × **总数**（图例显示"混合物品"） |

**为什么"混合"不挑"最多的那个物品"**：那样图例上的物品名与实际内容不符，读数直接错。
代价是混合状态下看不出具体是哪些物品 —— 这是诚实的代价，不是缺漏。
**数量在任何情况下都是所有格子之和**，所以"总存量 / 总吞吐"这条线永远有效。

#### 4. 读取失败时：**任何一格读不到，整条记 no_data**

聚合值是"所有格子的和"。少读一格 → 和偏小 → 图上的总存量静默偏小，
而这种偏小在波形上**完全看不出来**（它看起来和正常的下降一模一样）。
相比之下 `no_data` 是**看得出来**的断档。两害相权，宁可断档。

#### 5. 目标（`ProbeTarget`）的形态变化

```
旧：ProbeTarget(slotIndex, containerSlotIndex, itemId, dim, pos)      ← 一格
新：ProbeTarget(List<SlotRef>, dim, pos)                              ← 一组
    SlotRef(menuSlotIndex, containerSlotIndex, itemId, count)
```

`menuSlotIndex` 可以是 **-1**：准星创建的那种"无 GUI 容器"没有菜单，只能走世界侧读取。
`ProbeTarget#hasMenuPath()` 就是给状态机判"能不能走最准的那条路"用的。

#### 6. 无 GUI 容器的槽位清单（`ProbeSlots`）

可视化选格依赖"菜单槽在屏幕上的位置"，而菜单只在界面开着时存在。
无 GUI 的设备连"有几格、每格装着什么"客户端都不知道，只能由服务端线程读出来
（`Container` 或 `ItemHandler` 能力），再异步交回客户端。

`ProbeSlots` 用静态单例是**故意**的：同一时刻只可能有一个"正在配置的探针"
（面板是单例界面）。这与 `WorldProbe` 必须每实例一份正好相反。

---

### CP17 · 可视化选格 + 准星入口（第十轮）　风险：高 ⚠️⚠️

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

#### 3. 准星入口：只有服务端能开菜单

需求第 3 条要"准星指着有 GUI 的容器 → 打开 GUI，并打开探针 GUI"。
这里有个硬约束：**只有服务端能开菜单**（`ServerPlayer#openMenu` +
`ClientboundOpenScreenPacket`）。纯客户端模组没有别的合法通道，只能
**模拟一次真实的右键**（`MultiPlayerGameMode#useItemOn`）—— 这正是 vanilla 右键走的那条路。

于是不能在同一 tick 里压图层，得跨 tick：

```
按 F9 → 准星是"有界面"的容器 → useItemOn() 发出交互
                              → 记 PENDING_OVERLAY（60 tick 有效）
       1~3 tick 后，服务端开界面 → ScreenEvent.Init.Post
                              → 先把 pending 提升为锚点（ContainerAnchor，原有逻辑）
                              → 再 pushGuiLayer 把面板压上去
```

**60 tick 过期**是必须的：服务端如果一直没开界面（太远 / 被拒），
标志挂在那里会导致用户**下一次正常开任何容器时面板自己弹出来**。

**副作用防线**：`performUseItemOn` 的顺序是
`RightClickBlock 事件` → **`itemstack.onItemUseFirst`** → 方块 use → 物品 use。
第二条是"物品抢在方块前面"，任何装了扳手 / 工具类物品的玩家都会被它抢走。
所以发交互之前先用**反射只读**物品类的 `onItemUseFirst(ItemStack, UseOnContext)`
的**声明类**：是原版 / NeoForge 的（即没覆写）才发；否则不发，改用面板提示用户空手再试。
`getMethod` 对"没覆写"的情况返回接口 `IItemExtension` 上的 default 方法，
所以判断的是包名前缀而不是具体类。

**为什么"有 GUI"要用 `BlockState#getMenuProvider(Level, BlockPos)` 判定**：
这就是 vanilla 自己用来决定"右键这个方块要不要开界面"的入口，
语义天然一致，不需要维护一张"哪些方块有界面"的表。
（`BlockBehaviour#getMenuProvider(BlockState, Level, BlockPos)` 是 protected，
但 `BlockState` 继承的 `BlockStateBase` 把它的两参重载包成了 **public**，可以直接调。）

#### 4. 准星判定的三条分支

| 准星 | 判定 | 结果 |
|---|---|---|
| 有 `MenuProvider` | 有 GUI | 模拟右键打开界面 → 界面一开就压面板（**根页**） |
| 无 `MenuProvider`，但 `Container` 或 `ItemHandler` 能力存在 | 有容器无界面 | 直接新建探针 + 进选格页（**列表选格**，可视化选格按钮置灰） |
| 其它 / 没指到方块 | 不可探测 | 打开**根页**（挑一个已创建的探针看） |

判"是不是容器方块实体"的两条路径与 `WorldProbe#readNow` 严格对齐
（`Container` 或 `ItemHandler.BLOCK`，先无方向再六面）——
这样不会出现"界面说能探测、实际读不到"。

> 📌 **一处需要你确认的解读**：需求原文是"若是且有GUI，则打开GUI，并打开探针GUI"，
> 而后半句对无 GUI 的情况明确写了"自动进入…创建流程"。
> 两句的对比读下来，**有 GUI 的那支落在根页**（不是自动进创建流程）。
> 如果你其实想要"有 GUI 时也直接进创建流程"，改
> `KeyBindings#onScreenInit` 里 `openOverlay(...)` 的 `Page` 实参即可（一行）。

---

## 三、设计权衡（已做的决策与理由）

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 探针身份 | 运行时身份 + 持久身份**同时持有** | 只用其一 | 界面开着用运行时（零成本），关掉后用持久（单人可真读、多人记断档） |
| 界面形式 | `ClientHooks.pushGuiLayer` 图层栈 | `setScreen` 替换式 / 纯事件叠加绘制 | 替换式看不到原界面；纯事件叠加要自己实现全部命中测试与拖拽。图层栈两者兼得，还能直接用 `Screen` 的事件方法 |
| 关界面后 | 转 `FOLLOWING` | 直接 `PAUSED` | 玩家关界面往往是要腾出手，不是想停 |
| 多人读不到时 | 记 `no_data` | 跳过 / 记 0 | 跳过会让横轴出现"跳变"，记 0 会让净流量彻底算错 |
| 绘图 | 手写 `fill`/`drawString` | 引入图表库 | 避免依赖地狱，NeoForge 生态的渲染库版本极易打架 |
| 采样时机 | `ClientTickEvent.Post` | `RenderTickEvent` | tick 是 20Hz 恒定，render 帧率会飘，时间轴对不齐 |
| 世界侧读值 | `server.execute` 异步 | 客户端线程直读 | 后者是数据竞争，不值得留隐患 |
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
| 关界面接缝 | 用 `lastKnown` 兜住 + 垫进 `WorldProbe` 缓存 | 接受 1 tick 假 no_data | "读不到"和"还没读到"是两回事，把等待包装成失败是制造假警报（CP9 之一） |
| 采样时钟 | 服务端线程读权威 `gameTime`，客户端 tick 只管新鲜度 | 客户端 `Level#getGameTime()` 直接用 | 客户端 gameTime 是错拍阶梯（本地 +1 / 服务端 20t 纠正一次），tick rate ≠ 20 时采样节奏与数值全错（CP15） |
| 多探针 | **所有实例同时采样**，界面只切换查看对象 | 只跑"当前在看的那一个" | 语义是"正在运行的探针"，切回来应该看到离开那段时间的数据（CP16） |
| `WorldProbe` 的归属 | **每实例一份** | 静态单例共享 | 共享读值缓存会让两个探针互相覆盖读数，且症状是"数据偶尔跳一下"，极难查（CP16） |
| 全局时钟 | 抽成 `ServerClock`，不放在实例里 | 跟着 `WorldProbe` 走 | 删掉一个实例不该清掉全世界的时间（CP16） |
| 多格聚合的"混合"档 | 合成 `logiscope:mixed`，数量仍取总数 | 取"最多的那个物品" / 取第一个 | 前者会让图例的物品名与实际内容不符（读数直接错）；数量取总数保证"总吞吐"这条线永远有效（CP16） |
| 多格读值失败 | **任何一格读不到 → 整条记 no_data** | 跳过读不到的那格、按剩下的求和 | 和偏小在波形上和"正常下降"一模一样，**看不出来**；no_data 是看得出来的（CP16） |
| 可视化选格的拖动 | 按线段每 4px 采样逐点命中 | 只判当前鼠标位置 | 鼠标事件按帧来，快速划过会漏格（CP17） |
| 选格集合的键 | **菜单槽下标** | 容器内下标 | MEK 那类模组 `getContainerSlot()` 恒为 0，用它做键会让"选 1 格"变成"全选"（CP17、CP9 之二） |
| 打开容器界面 | 模拟一次真实右键（`useItemOn`），跨 tick 再压图层 | 想在按键那一 tick 就压 | 只有服务端能开菜单，界面必须等服务端回包；且要先挡住"物品抢在方块前面"（CP17） |

### 已知限制（不是 bug，是取舍）

| 限制 | 原因 | 影响 |
|---|---|---|
| 面板打开时，容器的**键盘**操作无效（数字键换物、`Q` 丢弃、中键取方块） | 这些走 `hoveredSlot`，而背景层拿不到鼠标坐标（见 CP8） | 鼠标操作已经完全可用（CP8 之一 ~ 之四）；键盘这几个键要收起面板 |
| 拖拽分配时手持物品的数字是**完整数量**而非剩余量 | `quickCraftingRemainder` 是 `private` 字段，读不到 | 只在按住左键拖过一排格子的瞬间可见（CP8 之四） |
| 面板打开时按 `E`（背包键）没反应 | `Screen#keyPressed` 不处理背包键，只有 `AbstractContainerScreen` 处理 | 同上，收起面板即可 |
| 被压住的容器界面**不 tick** | 只有 `minecraft.screen` 才会被 `Screen#tick` | 少数依赖 tick 的自绘界面可能显示不动 |
| 面板内的点击不会穿透到容器 | 这是刻意的，否则一次点击会同时触发两边 | 无 |
| **多探针**（第十轮已支持，上限 16 个） | 每实例一份半小时缓冲 + 每 tick 一次世界侧异步读取 | 数量很大时（十几个）会有可观的内存与查询开销；`MAX_PROBES` 就是为此设的 |
| 多人下关界面记不到真值 | 纯客户端定位的必然代价 | 见 CP9 |
| 用快捷键开界面的模组拿不到坐标 | vanilla 菜单不存方块坐标，只能靠右键事件推 | 见 CP9 |
| 第三方搜索框需逐个加探针 | 那些模组的搜索框不挂进 `screen.children()` | 目前只接了 JEI；通用兜底能覆盖其余情况（代价：多按一次） |
| 一个像素列里同时发生物品切换时，旧颜色会在该列多画一小截 | 列聚合每列只记 `first` 的断点标记 | 列宽 1px，肉眼不可见；不值得为它复杂化聚合逻辑（CP11） |
| 图例超过 3 行时多出的收成 `+N`（点不到） | 面板高度有限 | 一个槽位同时出现 7 种以上物品很罕见；把面板拖大即可（CP12） |
| 纵轴范围随窗口滚动偶尔会跳一下 | 自动缩放的固有代价 | 已吸附到整数刻度，跳变频率远低于逐帧抖动（CP11） |
| 界面上只画 3 条数据线时，跨空列会线性插值（画成斜坡） | 采样点稀疏时无法知道真实跳变发生在哪一 tick | 台阶数据的斜坡只出现在"两点间隔很大"的情况，属合理近似（CP11） |

### 待决问题（写代码前先决定）

1. ~~**多探针**~~（**第十轮已解决**）：`ProbeManager` + `ProbeInstance`，
   `ChartData.build` 是纯函数，"各建一份"确实是它的自然用法 —— 这条路走对了。
   遗留的小问题：现在没有"多探针同屏对比"，只有"一次看一个"。
   真要做对比，需要相对时间轴（见下面第 7 条）。
2. **采样降频**：长时间记录时是否需要"每 N tick 采样一次"以延长覆盖时长？
   注意：若真的降频，`ChartData.CONTINUITY_GAP` 必须跟着改成 N，
   否则正常采样会被误判成断档、波形碎成一堆短线段。
3. **物品 NBT**：现在只记 itemId，不区分 NBT 不同的同名物品
   （例如带不同附魔的书）。做物流分析时大概率够用，但要在文档里写明。
4. **让容器的键盘操作也能用**：需要补上 `hoveredSlot`（它是 `protected`）。
   可行路径：反射写字段，或改成"纯事件叠加绘制"方案（让容器界面留作
   `minecraft.screen`，用 `ScreenEvent.Render.Post` 画面板）。后者更干净但改动面大。
5. **`no_data` 的采样密度**：现在和真实样本一样每 tick 一条。是否该在断档期间降频？
   （同理注意 `CONTINUITY_GAP`。）
6. **图例的可滚动 / 可折叠**：现在超过 3 行会把多出来的收成 `+N`。
   真出现"一个槽位里轮换了十几种物品"的场景时，需要给图例加翻页。
7. **时间轴对齐参考**：目前所有系列的横轴都是绝对 tick。
   若以后支持"多探针对比"，可能需要"以某个事件为 0 点"的相对时间轴。
8. **~~模组机器的下标映射~~**（**第六轮已解决**）：实测 MEK 的槽位下标硬编码 0
   （构造 `super(emptyInventory, 0, x, y)`），逐模组适配不可行也不必要。
   已改为通用解法：挂探头瞬间在服务端做"内容指纹匹配"验证下标
   （见 CP9 之三）；验证不了（歧义）就明说，不瞎猜。

---

## 四、已核实的 API（重要，避免凭空猜测）

以下全部用本地 `neoforge-21.1.209-sources.jar` **逐行实际查证过**，不是凭记忆写的。

### 4.1 容器与数据

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

### 4.2 界面与输入

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `ClientHooks.pushGuiLayer(Minecraft, Screen)` | **public static**，`net.neoforged.neoforge.client` | ★ CP8 压图层 |
| `ClientHooks.popGuiLayer(Minecraft)` | 同上；栈空时退化为 `setScreen(null)` | ★ CP8 弹图层 |
| `ClientHooks.clearGuiLayers(Minecraft)` | `Minecraft#setScreen` 开头自动调用 | 兜底清理 |
| `Minecraft.mouseHandler` | `public final MouseHandler` | 手动 `releaseMouse()` |
| `MouseHandler.releaseMouse/grabMouse/isMouseGrabbed` | public | 鼠标抓取状态 |
| `ScreenEvent.KeyPressed.Pre` | 带 `getKeyCode/getScanCode/getModifiers`，可 cancel | CP2 |
| `ScreenEvent.Init.Post` | 在 `Screen#init` 末尾发出 | 容器界面打开时机 |
| `ScreenEvent.Closing` | `getScreen()`，**不可 cancel** | 界面关闭时机 |
| `ScreenEvent.Render.Post` | 带 `getGuiGraphics/getMouseX/...` | 备用（本项目未用） |
| `PlayerInteractEvent.RightClickBlock` | `getPos()` / `getLevel()`，可 cancel | ★ CP9 拿坐标 |
| `RegisterKeyMappingsEvent` | `implements IModBusEvent` | **必须注册在模组总线** |
| `ClientTickEvent.Post` | 游戏总线 | CP4 |
| `KeyboardHandler#keyPress` 里的分支 | `action==1 || action==2` 都走 screen | ★ CP2 去抖的依据 |
| `KeyboardHandler#charTyped` 里的分支 | `Pre` / `charTyped` / `Post` 三连，**前两个 return 掉就不发 Post** | ★ CP2 第 3 层守卫的依据 |
| `Minecraft#tick` | `handleKeybinds()` 只在 `overlay==null && screen==null` 时跑 | ★ CP2 规则 4 的依据 |
| `Screen.getFocused()` | `AbstractContainerEventHandler` 上的 `@Nullable GuiEventListener` | ★ CP2 规则 3 第 1 层 |
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
| `KeyMapping.matches(int,int)` | **只比 keyCode，忽略修饰键** | ★ CP2 的坑 |
| `KeyMapping.releaseAll()` | `public static` | 开面板时清按键状态 |
| `EditBox` / `AbstractWidget.isFocused()` | public | ★ CP2 规则 3 第 1 层 |
| **`AbstractContainerScreen.getGuiLeft()` / `getGuiTop()`** | **public**（Neo 加的访问器） | ★ CP8 之二 画高亮 |
| **`AbstractContainerScreen.getSlotColor(int)`** | **public** | ★ CP8 之二 用原版的每槽高亮色 |
| **`AbstractContainerScreen.renderSlotHighlight(GuiGraphics,int,int,int,int)`** | **public static**，内部 `fillGradient(guiOverlay, x, y, x+16, y+16, ...)`，**坐标系是 pose 相对的** | ★ CP8 之二 |
| **`AbstractContainerMenu.getCarried()`** | public | ★ CP8 之四 手持物品 |
| **`GuiGraphics.renderItem(ItemStack,int,int)`** | public | ★ CP8 之四 |
| **`GuiGraphics.renderItemDecorations(Font,ItemStack,int,int)`** | public | ★ CP8 之四（画数量） |
| **`GuiGraphics.renderTooltip(Font,ItemStack,int,int)`** | public | ★ CP8 之四（悬浮提示） |
| **`IClientItemExtensions.of(ItemStack).getFont(stack, FontContext.ITEM_COUNT)`** | public Neo API | ★ CP8 之四，忠实于 vanilla 的数量字体 |
| **`Slot.x` / `Slot.y` / `isActive()` / `isHighlightable()`** | 全是 public | ★ CP8 之二；命中判定与 vanilla 一致 |
| **`EffectRenderingInventoryScreen`** | `InventoryScreen` 与 `CreativeModeInventoryScreen` 的共同基类 | ★ 排除"玩家自己的背包" |
| `Player.displayClientMessage(Component, boolean)` | public | 动作栏状态提示 |
| **`Capabilities.ItemHandler.BLOCK`** | `BlockCapability<IItemHandler, @Nullable Direction>`（`net.neoforged.neoforge.capabilities`） | ★ CP9 之二，模组机器的世界侧读取 |
| **`Level.getCapability(BlockCapability, BlockPos, @Nullable Direction)`** | `ILevelExtension` 的 default 方法（Level 实现了它）；`side=null` = 内部视角 | ★ CP9 之二。命中 NeoForge 能力缓存，每 tick 多次查询成本可忽略 |
| **`IItemHandler.getSlots()` / `getStackInSlot(int)`** | interface 方法（`net.neoforged.neoforge.items`） | ★ CP9 之二 |
| **`Slot.getContainerSlot()`** | 返回 `this.slot`（构造时传入的下标）；**`SlotItemHandler` 把 handler 下标传给 super** → 对它而言 `getContainerSlot()` 就是 handler 的槽位下标 | ★ CP9 之二的下标依据 |
| **`Font.adjustColor`** | `private static`；alpha 位 == 0 时补 `0xFF` —— **只有文字有这个兜底，`fill` 没有** | ★ CP11 的 fill alpha 坑 |
| `Dist` | `net.neoforged.api.distmarker.Dist` | 客户端专用声明 |
| **`BlockState#getMenuProvider(Level, BlockPos)`** | **public**（`BlockState extends BlockBehaviour.BlockStateBase`，那一层把 protected 的 `BlockBehaviour#getMenuProvider(BlockState, Level, BlockPos)` 包成了 public 的两参重载），返回 `@Nullable MenuProvider` | ★ CP17 判"这个方块右键会不会开界面" |
| **`MultiPlayerGameMode#useItemOn(LocalPlayer, InteractionHand, BlockHitResult)`** | public，返回 `InteractionResult` | ★ CP17 模拟一次真实右键以打开容器界面 |
| **`Minecraft#hitResult`** | `public HitResult hitResult`（**字段**，不是 getter） | ★ CP17 准星结果。`Minecraft#tick` 里 `gameRenderer.pick(1.0F)` 是**无条件**每 tick 调一次的（在 screen 判断之前），所以即使界面开着它也刷新 |
| **`BlockHitResult#getBlockPos()`** | public | ★ CP17 |
| **`Slot#container` / `Slot#index` / `Slot#isFake()`** | 全是 **public**（`container` 是 `public final Container`，`index` 是 `public int`） | ★ CP1 排除玩家背包（`slot.container == player.getInventory()`）、CP17 用 `Slot#index` 当菜单槽下标 |
| **`Player#getInventory()`** | `public Inventory getInventory()` | ★ CP1 |
| **`Level#getBlockEntity(BlockPos)` / `Level#isLoaded(BlockPos)`** | public | CP17 判"是不是方块实体"；CP9 判"区块还在不在" |
| **`Item#onItemUseFirst(ItemStack, UseOnContext)`** | **不是 Item 上的方法**，是 NeoForge `IItemExtension` 的 default 方法（`net.neoforged.neoforge.common.extensions`） | ★ CP17 用它判断"手上的物品会不会抢走这次右键"。反射 `getClass().getMethod(...)` 在**没覆写**时返回接口上的 default 方法，所以看**声明类的包名**（`net.minecraft.*` / `net.neoforged.*` = 安全） |

### 4.3 第三方模组 API（反射调用，零编译依赖）

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `mezz.jei.common.Internal#getJeiRuntime` | `public static IJeiRuntime`（**内部类，所以整段包 try/catch**） | 拿 JEI 运行时的入口 |
| `mezz.jei.api.runtime.IJeiRuntime#getIngredientListOverlay()` | `IIngredientListOverlay` | 公开 API |
| `mezz.jei.api.runtime.IIngredientListOverlay#hasKeyboardFocus()` | `boolean` | ★ 判断 JEI 搜索框是否有焦点 |
| `mezz.jei.gui.input.GuiTextFieldFilter` | **`extends EditBox`**，但**不挂进 `screen.children()`** | 解释了为什么第 1 层守卫看不见它 |

> 反查方法：`javap -p -cp run/mods/<mod>.jar <类名>`（直接读 class，不需要源码）。

### 4.4 反面清单（**不存在 / 不要用**）

- `Minecraft.pushGuiLayer()` / `popGuiLayer()` —— **Minecraft 上不存在**，
  只在 `ClientHooks` 上有（而且它是 Screen 栈，见 CP8）
- `GuiLayerManager` / `RegisterGuiLayersEvent` —— 那是 **HUD 图层**（血量/氧气那些），
  和 Screen 栈是两套东西。**别把它们和 `ClientHooks.guiLayers` 搞混**（旧文档就栽在这）
- 不要指望 HUD 图层能"浮在界面之上"：`GameRenderer` 里 HUD 先画（约 1073 行）、
  Screen 后画（约 1092 行），界面自己的 `renderTransparentBackground` 渐变会把 HUD 盖住
- 不要试图给背景图层传真实鼠标坐标 —— `ClientHooks#drawScreen` 写死了 `MAX_VALUE`，
  想拿回 hover 效果只能像 CP8 之二那样自己补画

---

## 五、目录 → 层 的映射

```
src/main/java/com/jessetech/logiscope/
├─ LogiScopeMod.java          主类：注册 7 个事件监听、持有唯一 ProbeManager
├─ bind/
│  ├─ KeyBindings.java        输入层：快捷键 + Screen 按键拦截 + 无界面时的准星入口（CP2/CP17）
│  ├─ TextInputGuard.java     输入层：三层"正在打字"守卫（CP2 规则 3）
│  ├─ ContainerAnchor.java    绑定层：右键方块 → 维度+坐标锚点（CP9）
│  ├─ ProbeTarget.java        绑定层：探针目标（一组槽位 + 持久身份）
│  └─ ProbeContext.java       绑定层：当前可探测的容器（容器界面 / 准星）（CP17）
├─ collect/
│  ├─ ContainerAccess.java    采集层：读菜单槽位（★ CP1，项目基石）
│  ├─ WorldProbe.java         采集层：读方块实体，服务端线程执行 + seed 垫值（★ CP9）——**每实例一份**
│  ├─ ProbeSlots.java         采集层：无 GUI 容器的槽位清单（CP16）
│  ├─ ServerClock.java        采集层：两只**全局**时钟（CP15）
│  ├─ ProbeInstance.java      采集层：一个探针的状态机 + 每 tick 采样 + 多格聚合（CP4/CP5/CP9/CP16）
│  └─ ProbeManager.java       采集层：实例表 / 创建 / 删除 / 每 tick 驱动（CP16）
├─ store/
│  ├─ Sample.java             存储层：最小可序列化单元（含 no_data 哨兵）
│  └─ SampleStore.java        存储层：环形缓冲 + version 计数（CP12）
├─ analysis/
│  ├─ Analyzer.java           分析层：纯函数（摘要 / 逐 tick 差值，跳过 no_data）
│  ├─ ChartData.java          分析层：扁平序列 → 按物品分组的数据线（★ CP10）
│  ├─ Aggregator.java         分析层：多格 → 一条样本的内容（★ CP16）
│  ├─ Series.java             分析层：一条数据线（含断点）
│  └─ SeriesPoint.java        分析层：一个采样点（含 breakBefore）
└─ ui/
   ├─ ScopeScreen.java        呈现层：三页面（根/选格/图表）浮层面板，布局与交互（CP6/CP8/CP12/CP17）
   └─ ChartRenderer.java      呈现层：坐标轴 / 自动缩放 / 多线 / 框选 / 准线（★ CP11）

dev-check/
└─ TestChart.java             ★ 离线断言（不启动游戏）：断点规则、分组、颜色顺序、框选统计、多格聚合
                               跑法见文件头注释；分析层不依赖游戏类才能这么用
```

**为什么 `analysis/` 要拆成四个文件**：`ChartData` 负责"分组与断点判定"，
`Series`/`SeriesPoint` 是它的数据形状，`Analyzer` 保留"与具体图形无关的统计"。
三者的共同点是**都不 import 任何 `net.minecraft`** —— 这不是洁癖，
而是让 `dev-check/TestChart.java` 能脱离游戏跑断言的前提（`VERIFY.md` H 组）。

## 六、扩展点（新功能该往哪加）

| 想加的功能 | 加在哪 | 注意 |
|---|---|---|
| 新的统计量（吞吐、周期） | `analysis/Analyzer` | 保持纯函数，别引入状态；记得跳过 no_data；**顺手在 `dev-check/TestChart.java` 里加几条断言** |
| 新的图表类型 | `ui/` 下新建渲染器 | 只读 `ChartData` / `List<Sample>`，别碰 `Sampler` |
| CSV / JSON 导出 | 新建 `export/` 包 | 从 `SampleStore.toList()` 取数据；no_data 要单独标记。分组信息可以直接复用 `ChartData.series()` |
| 在波形上标点 | `ui/ChartRenderer` + `ScopeScreen` 的标记集合 | 标记是**用户数据**，不该塞进 `SampleStore` 的环形缓冲（会被覆盖掉）。选区的两个端点已经是现成的落点（`ChartRenderer.Selection`） |
| 在波形上**框选一段区间** | ✅ 已完成 | 见 CP12；`Selection` → `ChartData.ranges()` → 统计块 |
| 换配色 / 加调色板条目 | `ui/ChartRenderer.PALETTE` | 按 series **下标**取色，所以往末尾加颜色不会影响已有线的颜色 |
| 图上再叠一层信息（速率曲线等） | `ui/ChartRenderer` 里加一条"派生线" | 派生线的 y 尺度可能和 count 差很多 → 需要第二条纵轴，建议先想清楚 |
| 支持多探针 | ✅ 已完成（第十轮） | `ProbeManager` + `ProbeInstance`；加"同屏对比"要先做相对时间轴（待决问题 1、7） |
| **多格监控一个新容器** | `ui/ScopeScreen` 的选格页 + `analysis/Aggregator` | 聚合规则改动的**第一站是 `Aggregator` + `dev-check` 断言**，别在采集层里改 |
| **换一种"选格"的交互** | `ui/ScopeScreen` 的 `cellMode` / `applyCell` / `slotAt` | 记住两条铁律：面板外的事件不转交、拖动要按线段采样（CP17） |
| **支持一种新的"入口"**（例如对着某类方块自动建探针） | `bind/ProbeContext` + `bind/KeyBindings#openFromCrosshair` | 判"是不是容器"要和 `WorldProbe#readNow` 的两条路径保持一致 |
| **支持别的搜索类模组**（REI / EMI） | `bind/TextInputGuard` 里加一个小探针 | 照 `JeiSearchProbe` 抄；先用 `javap -p -cp run/mods/<mod>.jar` 查它的 API |
| 让容器的键盘操作也能用 | `ui/ScopeScreen` 补 `hoveredSlot` | 见第三节「待决问题 4」 |
| 兼容自绘界面的模组 | `collect/` 下新建 accessor | 需要面向接口重构 `ContainerAccess` |
| **某模组机器的下标对不上**（capability 路径） | `WorldProbe#readNow` 按 `BlockEntity` 类型加分流 | 见待决问题 8；通用路径保持"Container → capability"不动 |
| **支持更多模组的搜索框** | 已有，见 `bind/TextInputGuard` | — |
| **图例可滚动 / 可折叠** | `ui/ScopeScreen` 的 `capRows` / `renderLegend` | 见第三节「待决问题 6」 |
| **给纯逻辑层加更多断言** | `dev-check/` 下新建 `Test*.java` | 前提是那一层不 import 游戏类。跑法见 `VERIFY.md` H 组 |
