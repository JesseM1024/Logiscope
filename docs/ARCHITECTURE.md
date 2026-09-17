# LogiScope · 架构与关键逻辑路径

这份文档是 vibe coding 时最该先读的一份。
它回答三个问题：**代码怎么分层**、**风险在哪**、**哪些 API 已经核实过**。

---

## 一、分层

```
┌─────────────────────────────────────────────────────────────┐
│ 输入层  bind/KeyBindings         按键 → "想打开示波器"        │
├─────────────────────────────────────────────────────────────┤
│ 绑定层  bind/ProbeTarget         解析成 "哪个菜单的第几格"     │
├─────────────────────────────────────────────────────────────┤
│ 采集层  collect/ContainerAccess  读槽位（★ 数据来源）          │
│         collect/Sampler          每 tick 产出 Sample          │
├─────────────────────────────────────────────────────────────┤
│ 存储层  store/SampleStore        环形缓冲，时间序列            │
│         store/Sample             最小可序列化单元              │
├─────────────────────────────────────────────────────────────┤
│ 分析层  analysis/Analyzer        纯函数：差值 / 摘要 / 事件    │
├─────────────────────────────────────────────────────────────┤
│ 呈现层  ui/ScopeScreen           只读，不写任何状态            │
├─────────────────────────────────────────────────────────────┤
│ (规划)  持久层  export/*          CSV / JSON 导出             │
└─────────────────────────────────────────────────────────────┘
```

### 层间契约（改代码时必须守住）

| 契约 | 内容 | 违反的后果 |
|---|---|---|
| C1 | `Sampler` 不认识任何 UI / 渲染类 | 界面崩了会连带采样一起挂 |
| C2 | `SampleStore` 只存 `(tick, itemId, count)`，不持有 `ItemStack` | 无法离线分析、无法导出、容易泄漏世界引用 |
| C3 | `Analyzer` 是纯函数，不持有状态 | 无法单元测试，图表和导出会算出不同结果 |
| C4 | `ScopeScreen` 只读，绝不直接改采样数据 | 界面刷新时机不可控，容易写出竞态 |
| C5 | 只有一个采样器实例：`LogiScopeMod.sampler()` | 多处 `new Sampler()` 会导致数据分裂 |

**一句话总结数据流**：
`按键盘 → KeyBindings → Sampler.attach() → 每 tick ContainerAccess.read() → SampleStore.add() → Analyzer 读 → ScopeScreen 画`

---

## 二、关键逻辑路径

标注 ⚠️ 的是**风险点**，改这些代码前务必先读本节。

### CP1 · 拿到容器内容　风险：低

`player.containerMenu.slots` → `Slot#getItem()` → `ItemStack`

**已核实**（见第四节）。唯一要注意的是**必须排除玩家自己的背包**：

```java
mc.player.containerMenu != mc.player.inventoryMenu   // inventoryMenu 是玩家背包，不算外部设备
```

### CP2 · 在 GUI 打开时捕获快捷键　风险：中 ⚠️

**这是最容易写错的一处。**

vanilla 的 `KeyMapping` 只在**没有界面打开**时才会被 tick 消费。
而本模组的核心场景恰恰是"容器界面开着"——此时按键被当前 `Screen` 吃掉，
`KeyMapping.consumeClick()` 永远返回 false。

**正确做法**：监听 `ScreenEvent.KeyPressed.Pre`

```java
NeoForge.EVENT_BUS.addListener(KeyBindings::onScreenKeyPressed);

private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
    if (!OPEN_SCOPE.matches(event.getKeyCode(), event.getScanCode())) return;
    // ... 判断是否有外部容器打开
    event.setCanceled(true);   // 阻止按键继续传给原界面
}
```

- 这个事件在 Screen 处理按键**之前**触发，所以能拿到
- 必须 `setCanceled(true)`，否则按键会继续传给原模组（可能触发它的快捷键）
- 事件在**游戏总线** `NeoForge.EVENT_BUS` 上，不是模组总线

### CP3 · 打开示波器但不丢菜单　风险：中 ⚠️

用 `mc.setScreen(new ScopeScreen(mc.screen))` 替换当前界面。

**关键点**：`setScreen` 会调用旧 Screen 的 `removed()`，但**不会**调用 `onClose()`。
而 `AbstractContainerScreen.onClose()` 才会 `player.closeContainer()`。
所以菜单对象**依然有效**，采样不会中断。关闭时 `setScreen(parent)` 还原即可。

**备选方案（暂未采用）**：NeoForge 的 `ClientHooks.pushGuiLayer/popGuiLayer`。
但核实发现它属于 **HUD 图层系统**（同包的是 `VanillaGuiLayers`，即血量/氧气那些），
不是 Screen 栈，语义不匹配。**不要想当然地用**。

### CP4 · 每 tick 采样　风险：低

`NeoForge.EVENT_BUS.addListener(Sampler::onClientTick)`，事件类型 `ClientTickEvent.Post`。

- `Post` 在 tick 逻辑之后触发，此时同步数据已更新完毕
- 事件在**游戏总线**上

### CP5 · 数据源失效的检测　风险：中 ⚠️

采样时每 tick 校验三件事，任一失败就转 `PAUSED`：

| 检查 | 原因 |
|---|---|
| `hasForeignContainerOpen()` | 玩家关了界面 |
| `containerId == watchedContainerId` | 玩家换了另一台设备 |
| `read(slotIndex) != null` | 换了不同类型的菜单，槽位下标越界 |

**为什么转 PAUSED 而不是报错**：玩家关界面看一眼别的东西再回来，
记录不该断掉或错乱，而应明确地停在暂停态，可以手动继续。

### CP6 · 性能　风险：低

| 措施 | 说明 |
|---|---|
| 环形缓冲 | 固定容量 36000（半小时），写满覆盖最旧，内存可预测 |
| 只在 `RECORDING` 时读槽位 | 其它状态只做一次整数比较 |
| 火花线按列取峰值 | 采样点远多于像素时，不做逐点绘制 |
| 不引入图表库 | 只用 `GuiGraphics#fill` + `drawString` |

**待补**：`ScopeScreen.render` 里每次调用 `store.toList()` 会新建一个 List。
样本多时（几万条）这是每秒 60 次的分配。G2 阶段要改成按需读取区间。

### CP7 · 纯客户端成立性　风险：低

`@Mod(value = MODID, dist = Dist.CLIENT)` —— 加载器在专用服务端上直接跳过本模组。
配套地，`neoforge.mods.toml` 里的两条依赖都写了 `side="CLIENT"`。

---

## 三、设计权衡（已做的决策与理由）

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 探针身份 | 运行时身份（`containerId` + `slotIndex`） | 持久身份（维度 + 坐标 + 下标） | 持久身份需要反推方块坐标，vanilla 不提供，风险高；先做能覆盖核心场景的（见 G3） |
| 界面形式 | `setScreen` 替换式 | `ScreenEvent.Render.Post` 叠加绘制 | 替换式能直接用原版控件；叠加式虽然能"边看机器边看波形"，但控件全要手写 |
| 绘图 | 手写 `fill`/`drawString` | 引入图表库 | 避免依赖地狱，NeoForge 生态的渲染库版本极易打架 |
| 采样时机 | `ClientTickEvent.Post` | `RenderTickEvent` | tick 是 20Hz 恒定，render 帧率会飘，时间轴对不齐 |
| 失效处理 | 转 `PAUSED` | 抛异常 / 自动重连 | 观测工具不该打断用户操作；自动重连需要持久身份（G3） |

### 待决问题（写代码前先决定）

1. **多探针**：现在只有 1 个探针。多探针（同时看 3 个格子对比）需要把
   `Sampler` 的状态改成 `Map<ProbeTarget, SampleStore>`。**建议 G2 之后再做**，
   因为多探针会让界面复杂度翻倍。
2. **采样降频**：长时间记录时，是否需要"每 N tick 采样一次"以延长覆盖时长？
   涉及数据粒度与存储量的权衡。
3. **物品 NBT**：现在只记 itemId，不区分 NBT 不同的同名物品
   （例如带不同附魔的书）。做物流分析时大概率够用，但要在文档里写明。

---

## 四、已核实的 API（重要，避免凭空猜测）

以下全部用本地 `neoforge-21.1.209-sources.jar` 与 FML loader jar **实际查证过**，
不是凭记忆写的：

| API | 真实签名 / 位置 | 用途 |
|---|---|---|
| `Player.containerMenu` | `public AbstractContainerMenu containerMenu`（public 字段） | 拿当前菜单 |
| `AbstractContainerMenu.slots` | `public final NonNullList<Slot> slots` | 槽位列表 |
| `AbstractContainerMenu.getSlot(int)` | `public Slot getSlot(int)` | 按下标取槽 |
| `Slot.getItem()` | `public ItemStack getItem()` | 读内容 |
| `Slot.getContainerSlot()` | `public int getContainerSlot()` | 容器内下标 |
| `KeyMapping.matches(int,int)` | 存在 | 按键匹配 |
| `RegisterKeyMappingsEvent` | `implements IModBusEvent`；`public void register(KeyMapping)` | **必须注册在模组总线** |
| `ScreenEvent.KeyPressed.Pre` | 继承 `KeyInput`，可 cancel | CP2 |
| `ClientTickEvent.Post` | `public static class Post extends ClientTickEvent` | CP4 |
| `Minecraft.setScreen(Screen)` | `public void setScreen(@Nullable Screen)` | CP3 |
| `GuiGraphics.fill(int,int,int,int,int)` | 存在 | 画矩形 |
| `GuiGraphics.drawString(Font,String,int,int,int)` | 存在 | 画文字 |
| `Dist` | `net.neoforged.api.distmarker.Dist` | 客户端专用声明 |
| `@Mod` 注解 | 含 `value()` 与 `dist()` 两个成员 | CP7 |

**未核实 / 不存在（不要用）**：
- `Minecraft.pushGuiLayer()` / `popGuiLayer()` —— **Minecraft 上不存在**；
  只有 `ClientHooks` 上有，且属于 HUD 图层系统，语义不符
- `@Mod(dist=...)` 的实际枚举常量名（`Dist.CLIENT` 从常量池推断，编译已验证）

---

## 五、目录 → 层 的映射

```
src/main/java/com/jessetech/logiscope/
├─ LogiScopeMod.java          主类：注册事件、持有唯一 Sampler
├─ bind/
│  ├─ KeyBindings.java        输入层：快捷键 + Screen 按键拦截（CP2/CP3）
│  └─ ProbeTarget.java        绑定层：探针目标（运行时身份）
├─ collect/
│  ├─ ContainerAccess.java    采集层：读槽位（★ CP1，项目基石）
│  └─ Sampler.java            采集层：状态机 + 每 tick 采样（CP4/CP5）
├─ store/
│  ├─ Sample.java             存储层：最小可序列化单元
│  └─ SampleStore.java        存储层：环形缓冲
├─ analysis/
│  └─ Analyzer.java           分析层：纯函数
└─ ui/
   └─ ScopeScreen.java        呈现层：只读界面（CP6）
```

## 六、扩展点（新功能该往哪加）

| 想加的功能 | 加在哪 | 注意 |
|---|---|---|
| 新的统计量（吞吐、周期） | `analysis/Analyzer` | 保持纯函数，别引入状态 |
| 新的图表类型 | `ui/` 下新建渲染器 | 只读 `List<Sample>`，别碰 `Sampler` |
| CSV / JSON 导出 | 新建 `export/` 包 | 从 `SampleStore.toList()` 取数据 |
| 支持多探针 | `Sampler` + `ScopeScreen` | 见第三节「待决问题 1」 |
| 兼容自绘界面的模组 | `collect/` 下新建 accessor | 需要面向接口重构 `ContainerAccess` |
