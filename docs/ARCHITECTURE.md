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
│         bind/ProbeTarget       探针目标（运行时身份 + 持久身份）  │
├───────────────────────────────────────────────────────────────┤
│ 采集层  collect/ContainerAccess  读菜单槽位（★ 界面开着时的数据源）│
│         collect/WorldProbe       读方块实体（★ 界面关了时的数据源）│
│         collect/Sampler          每 tick 产出 Sample，状态机      │
├───────────────────────────────────────────────────────────────┤
│ 存储层  store/SampleStore      环形缓冲 + version，唯一真相       │
│         store/Sample           最小可序列化单元（含 no_data 哨兵）│
├───────────────────────────────────────────────────────────────┤
│ 分析层  analysis/Analyzer      纯函数：摘要 / 逐 tick 差值        │
│         analysis/ChartData     纯函数：扁平序列 → 按物品分组的数据线│
│         analysis/Series(Pt)    数据线的形状（含断点标记）         │
├───────────────────────────────────────────────────────────────┤
│ 呈现层  ui/ScopeScreen         只读：布局 / 交互 / 视图状态       │
│         ui/ChartRenderer       只读：坐标轴 / 波形 / 框选 / 准线   │
├───────────────────────────────────────────────────────────────┤
│ (规划)  持久层  export/*       CSV / JSON 导出                  │
└───────────────────────────────────────────────────────────────┘
```

### 层间契约（改代码时必须守住）

| 契约 | 内容 | 违反的后果 |
|---|---|---|
| C1 | `Sampler` 不认识任何 UI / 渲染类 | 界面崩了会连带采样一起挂 |
| C2 | `SampleStore` 只存 `(tick, itemId, count)`，不持有 `ItemStack` | 无法离线分析、无法导出、容易泄漏世界引用 |
| C3 | `Analyzer` / `ChartData` 是纯函数，不持有状态 | 无法单元测试，图表和导出会算出不同结果 |
| C4 | `ui/*` 只读，绝不直接改采样数据 | 界面刷新时机不可控，容易写出竞态 |
| C5 | 只有一个采样器实例：`LogiScopeMod.sampler()` | 多处 `new Sampler()` 会导致数据分裂 |
| C6 | `WorldProbe#readNow` **只能在服务端线程跑** | 跨线程读 ServerLevel 是数据竞争 |
| C7 | `no_data` 样本不进统计（`Analyzer` / `ChartData` 负责过滤，但要让它**占时间轴**） | 断档会被算成"数量掉到 0"，净流量全错；反过来丢掉时间轴会让图上的断档消失 |
| C8 | **`analysis/` 与 `store/` 不 import 任何 `net.minecraft` / `net.neoforged`** | 一旦引入游戏类，`dev-check/` 的离线断言就跑不了，只能靠进游戏肉眼看 |

**一句话总结数据流**：

```
按键盘 → KeyBindings → (界面开着?) ContainerAccess : WorldProbe
                     → Sampler 状态机 → SampleStore(version)
                     → ChartData/Analyzer（version 变了才重算）
                     → ChartRenderer 画到 ScopeScreen 上
```

---

## 二、关键逻辑路径

标注 ⚠️ 的是**风险点**，改这些代码前务必先读本节。

### CP1 · 拿到容器内容　风险：低

`player.containerMenu.slots` → `Slot#getItem()` → `ItemStack`

只列**外部设备**的槽位：`player.containerMenu != player.inventoryMenu`。
`ContainerAccess#snapshot` 会把玩家自己的背包整体排除掉 ——
面板能在没有容器界面时单独打开，那时 `containerMenu` 就是背包，
不排除的话玩家会在列表里看到自己的 36 个格子并误以为那是机器。

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

`NeoForge.EVENT_BUS.addListener(Sampler::onClientTick)`，事件类型 `ClientTickEvent.Post`。

- `Post` 在 tick 逻辑之后触发，此时同步数据已更新完毕
- 事件在**游戏总线**上
- 同一次 tick 里还兼职做两件事：`ContainerAnchor.tick()`（锚点计时）、
  `KeyBindings#onClientTick`（无界面时的快捷键）

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
| 过渡那一 tick | `Sampler` 持有 `lastKnown`（上一 tick 刚读到的值，最多 50 ms 旧），在检测到界面关闭时**先把它记下去**，补住空洞 |
| 下一 tick 的取值 | `WorldProbe.seed(lastKnown, tick)` 把这个值垫进缓存，`latest()` 的年龄判定（≤ `WORLD_READ_MAX_AGE`）让它算"新鲜"，于是异步结果到达前时间轴是连续的 |

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

### 已知限制（不是 bug，是取舍）

| 限制 | 原因 | 影响 |
|---|---|---|
| 面板打开时，容器的**键盘**操作无效（数字键换物、`Q` 丢弃、中键取方块） | 这些走 `hoveredSlot`，而背景层拿不到鼠标坐标（见 CP8） | 鼠标操作已经完全可用（CP8 之一 ~ 之四）；键盘这几个键要收起面板 |
| 拖拽分配时手持物品的数字是**完整数量**而非剩余量 | `quickCraftingRemainder` 是 `private` 字段，读不到 | 只在按住左键拖过一排格子的瞬间可见（CP8 之四） |
| 面板打开时按 `E`（背包键）没反应 | `Screen#keyPressed` 不处理背包键，只有 `AbstractContainerScreen` 处理 | 同上，收起面板即可 |
| 被压住的容器界面**不 tick** | 只有 `minecraft.screen` 才会被 `Screen#tick` | 少数依赖 tick 的自绘界面可能显示不动 |
| 面板内的点击不会穿透到容器 | 这是刻意的，否则一次点击会同时触发两边 | 无 |
| 多探针不支持 | 现在只有 1 个探针 | 见「待决问题」 |
| 多人下关界面记不到真值 | 纯客户端定位的必然代价 | 见 CP9 |
| 用快捷键开界面的模组拿不到坐标 | vanilla 菜单不存方块坐标，只能靠右键事件推 | 见 CP9 |
| 第三方搜索框需逐个加探针 | 那些模组的搜索框不挂进 `screen.children()` | 目前只接了 JEI；通用兜底能覆盖其余情况（代价：多按一次） |
| 一个像素列里同时发生物品切换时，旧颜色会在该列多画一小截 | 列聚合每列只记 `first` 的断点标记 | 列宽 1px，肉眼不可见；不值得为它复杂化聚合逻辑（CP11） |
| 图例超过 3 行时多出的收成 `+N`（点不到） | 面板高度有限 | 一个槽位同时出现 7 种以上物品很罕见；把面板拖大即可（CP12） |
| 纵轴范围随窗口滚动偶尔会跳一下 | 自动缩放的固有代价 | 已吸附到整数刻度，跳变频率远低于逐帧抖动（CP11） |
| 界面上只画 3 条数据线时，跨空列会线性插值（画成斜坡） | 采样点稀疏时无法知道真实跳变发生在哪一 tick | 台阶数据的斜坡只出现在"两点间隔很大"的情况，属合理近似（CP11） |

### 待决问题（写代码前先决定）

1. **多探针**：现在只有 1 个探针。多探针需要把 `Sampler` 的状态改成
   `Map<ProbeTarget, SampleStore>`，界面也要改成"一屏多条独立波形 / 多页切换"。
   **G2 之后可以开始考虑**，但界面复杂度会明显上升。
   （好消息：`ChartData.build` 已经是纯函数，"多套数据各建一份"是它的自然用法。）
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
| `Dist` | `net.neoforged.api.distmarker.Dist` | 客户端专用声明 |

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
├─ LogiScopeMod.java          主类：注册 7 个事件监听、持有唯一 Sampler
├─ bind/
│  ├─ KeyBindings.java        输入层：快捷键 + Screen 按键拦截 + 无界面时的轮询（CP2）
│  ├─ TextInputGuard.java     输入层：三层"正在打字"守卫（CP2 规则 3）
│  ├─ ContainerAnchor.java    绑定层：右键方块 → 维度+坐标锚点（CP9）
│  └─ ProbeTarget.java        绑定层：探针目标（运行时身份 + 持久身份）
├─ collect/
│  ├─ ContainerAccess.java    采集层：读菜单槽位（★ CP1，项目基石）
│  ├─ WorldProbe.java         采集层：读方块实体，服务端线程执行 + seed 垫值（★ CP9）
│  └─ Sampler.java            采集层：状态机 + 每 tick 采样（CP4/CP5/CP9）
├─ store/
│  ├─ Sample.java             存储层：最小可序列化单元（含 no_data 哨兵）
│  └─ SampleStore.java        存储层：环形缓冲 + version 计数（CP12）
├─ analysis/
│  ├─ Analyzer.java           分析层：纯函数（摘要 / 逐 tick 差值，跳过 no_data）
│  ├─ ChartData.java          分析层：扁平序列 → 按物品分组的数据线（★ CP10）
│  ├─ Series.java             分析层：一条数据线（含断点）
│  └─ SeriesPoint.java        分析层：一个采样点（含 breakBefore）
└─ ui/
   ├─ ScopeScreen.java        呈现层：可拖动可缩放的浮层面板，布局与交互（CP6/CP8/CP12）
   └─ ChartRenderer.java      呈现层：坐标轴 / 自动缩放 / 多线 / 框选 / 准线（★ CP11）

dev-check/
└─ TestChart.java             ★ 离线断言（不启动游戏）：断点规则、分组、颜色顺序、框选统计
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
| 支持多探针 | `Sampler` + `ScopeScreen` + `ChartData` | 见第三节「待决问题 1」。`ChartData.build` 是纯函数，"各建一份"是它的自然用法 |
| **支持别的搜索类模组**（REI / EMI） | `bind/TextInputGuard` 里加一个小探针 | 照 `JeiSearchProbe` 抄；先用 `javap -p -cp run/mods/<mod>.jar` 查它的 API |
| 让容器的键盘操作也能用 | `ui/ScopeScreen` 补 `hoveredSlot` | 见第三节「待决问题 4」 |
| 兼容自绘界面的模组 | `collect/` 下新建 accessor | 需要面向接口重构 `ContainerAccess` |
| **图例可滚动 / 可折叠** | `ui/ScopeScreen` 的 `capRows` / `renderLegend` | 见第三节「待决问题 6」 |
| **给纯逻辑层加更多断言** | `dev-check/` 下新建 `Test*.java` | 前提是那一层不 import 游戏类。跑法见 `VERIFY.md` H 组 |
