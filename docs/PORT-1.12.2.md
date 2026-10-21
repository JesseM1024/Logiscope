# LogiScope · 1.12.2 Forge 移植记录

> 本线 = **MC 1.12.2 + Forge 14.23.5.2859**（这一代只有 Forge，没有 NeoForge）。
> **本轮明确不做 AE 兼容**（用户要求）；AE 相关的类与断言原样留在 1.16.5 线，不往这边搬。
>
> 现状一句话：**工具链跑通、骨架能出包、纯逻辑层全过门禁**；MC 相关模块尚未搬（见 §四）。

| 项 | 值 |
|---|---|
| 分支 | `1.12.2-forge`（从 `1.16.5-forge` 的 `846818d` 分出；Java 8 子集与 `Draw` 适配层都能继承） |
| 版本 | `0.1.0-1.12.2`（代码面才起步，先不跟另外三条线的 0.1.3） |
| 产物 | `build/libs/logiscope-0.1.0-1.12.2.jar` |
| 离线门禁 | `dev-check/offline-check.bat` → `ALL_OK`（TestChart **476** 条 + 版本一致性 2 条） |
| 实机 | **无法验证**：本机没有 1.12.2 实例（见 §五） |

---

## 一、工具链：为什么是 ForgeGradle 3 + Gradle 4.9 + JDK 8

这一代的三条路都实测过，只剩一条能走：

| 候选 | 结论 | 依据（实测） |
|---|---|---|
| ModDevGradle（另外三条线用的） | ❌ 不支持 | MDG 的 legacy 插件官方范围是 Forge **1.17–1.20.1**，1.12.2 不在内 |
| **RetroFuturaGradle**（社区标准） | ❌ 仓库不可达 | `maven.gtnewhorizons.com/releases/...` → **HTTP 525**（TLS 握手失败，直连与代理都试过）；`repo.gtnewhorizons.net` → **000**（DNS/连接失败）；Maven Central、阿里云镜像都没有这个 artifact（均 404）。它也**不在** Gradle Plugin Portal（404） |
| **ForgeGradle 3** + Gradle 4.9 | ✅ 采用 | `net.minecraftforge.gradle:ForgeGradle:3.+` 在 `maven.minecraftforge.net` **可用**（maven-metadata 200）；官方 MDK `forge-1.12.2-14.23.5.2859-mdk.zip` 的 `build.gradle` 原文就是 `ForgeGradle:3.+` + `gradle-4.9-bin.zip`（wrapper 原文可查） |

配套约束（都写进了配置，别随手改）：

| 约束 | 在哪 | 为什么 |
|---|---|---|
| Gradle **4.9** | `gradle/wrapper/gradle-wrapper.properties` | FG3 只认 Gradle 4.x；Gradle 5+ 删了它用的 API |
| 守护进程 **JDK 8** | `gradle.properties` 的 `org.gradle.java.home=C:/Program Files/Java/jdk-1.8` | **Gradle 4.x 不支持 Java 11+**。本机默认 `java` 是 25，不锁会直接起不来 |
| Gradle 4.x 语法 | `build.gradle` | 这一代没有 `tasks.register` / `configureEach` / `java.toolchain` / `var`，只有 `def` + `task xxx` |
| `compileJava.options.encoding='UTF-8'` | `build.gradle` | 中文 Windows 默认 GBK，会静默转坏源码里的中文注释 |
| `processResources.filteringCharset='UTF-8'` | `build.gradle` | `mcmod.info` 的 `${version}` 展开同一个坑 |
| `jar.finalizedBy('reobfJar')` | `build.gradle` | FG3 里 `jar` 只打包（MCP 名），`reobfJar` 才是生产用的 SRG 名。**出包一律 `gradlew build`** |
| 腾讯云镜像发 Gradle | wrapper properties | 与另外三条线一致；官方 `services.gradle.org` 作备选（注释里留着） |

产出与缓存位置（排查时直接用）：

```bat
:: 出包
gradlew.bat build

:: 离线门禁（纯逻辑层，不需要 MC classpath）
dev-check\offline-check.bat
```

- 映射名 jar（`javap` 核实 API 就用它）：
  `~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.12.2-14.23.5.2859_mapped_snapshot_20171003-1.12/forge-1.12.2-14.23.5.2859_mapped_snapshot_20171003-1.12.jar`
- 离线门禁的 Gson：`build/gatedeps/`（`copyGateDeps` 任务产出；mapped jar 的路径带映射版本号，`.bat` 里不能硬编码）
- dev 运行目录：`1_12_2_forge/`（`build.gradle` 的 `runs.client.workingDirectory`）

---

## 二、API 差异（逐条 `javap` 核实，不凭记忆）

核实命令模板（映射名 jar 路径见上）：

```bat
javap -cp "<mapped jar>" net.minecraft.client.gui.GuiScreen
```

### 2.1 移植时最容易写错的几条

| 用途 | 1.16.5 线（我们现在的代码） | 1.12.2（**实测**） |
|---|---|---|
| 客户端实例 | `Minecraft.getInstance()` | **`Minecraft.getMinecraft()`** |
| 游戏目录 | `Minecraft.gameDirectory` | **`Minecraft.mcDataDir`**（`public final File`） |
| 屏幕基类 | `Screen` | `GuiScreen`；`public Minecraft mc` / `public int width,height` / `protected FontRenderer fontRenderer` 全是**字段** |
| 容器屏 | `ContainerScreen` | `GuiContainer`；**`public Container inventorySlots`**（与父类同名字段）、`protected guiLeft/guiTop/xSize/ySize`、`getSlotUnderMouse()` |
| 菜单 | `AbstractContainerMenu` | `Container`：`public List<Slot> inventorySlots`、`public int windowId`、`getSlot(int)` |
| 槽位 | `Slot` | `Slot`：`public final IInventory inventory`、`public int slotNumber / xPos / yPos`、`getStack() / getHasStack() / getSlotIndex()` |
| 绘制 | `GuiGraphics` + `MatrixStack` | **立即模式**：`Gui.drawRect(int,int,int,int,int)`（静态）、`Gui.drawTexturedModalRect(...)`、`Gui.drawModalRectWithCustomSizedTexture(...)`、`GlStateManager` |
| 字体 | `font.width(s)` | `FontRenderer.getStringWidth(String)`；`drawStringWithShadow(String,float,float,int)`；`drawString(String,int,int,int)` |
| 文本组件 | `new TranslationTextComponent(k)` / `getString()` | `new TextComponentTranslation(k)` / **`getUnformattedText()`**（`getString()` 不存在）；或客户端 `I18n.format(k, args...)` |
| 注册表 | `net.minecraftforge.registries.ForgeRegistries` | **`net.minecraftforge.fml.common.registry.ForgeRegistries`** ✅；`net.minecraftforge.registries.ForgeRegistries` **这个包不存在**（1.13+ 才移过去）。1.12.2 的它**没有 FLUIDS**，流体走 `FluidRegistry` |
| 物品栈 | `isEmpty()/getCount()/setCount()` | 同名同义 ✅（这一条不用改） |
| 能力 | `ForgeCapabilities.ITEM_HANDLER`（`Optional`） | `CapabilityItemHandler.ITEM_HANDLER_CAPABILITY`（`Capability<IItemHandler>`；`tile.getCapability(cap, side)` **直接返回实例或 null**，没有 `LazyOptional`） |
| 事件总线 | `IEventBus` + `MinecraftForge.EVENT_BUS` | **只有** `MinecraftForge.EVENT_BUS`；监听器是 `@SubscribeEvent` 方法 |
| 按键注册 | `RegisterKeyMappingsEvent`（1.19+）/ `ClientRegistry` | `ClientRegistry.registerKeyBinding(KeyBinding)`，在 `clientSetup` 阶段 |
| 键位对象 | `KeyMapping` | `KeyBinding(String, int, String)`；`isKeyDown() / isPressed() / getKeyCode()` |
| 元数据 | `META-INF/mods.toml`（模板 + 占位符展开） | **`src/main/resources/mcmod.info`** + `@Mod(...)` 注解 |
| 配置 | `ForgeConfigSpec` | `Configuration` + `@Config`（`ConfigChangedEvent` 里 `config.save()`） |

### 2.2 三个**架构级**断层（不是改名字能解决的）

#### 断层 1：没有 GUI 图层栈 —— 本项目的核心交互要重做

另外三条线的做法是 `ForgeHooksClient.pushGuiLayer(mc, scopeScreen)`，把面板压在原容器界面之上。
**1.12.2 没有这套机制**（实测：`net.minecraftforge.client.ForgeHooksClient` 里没有
`pushGuiLayer` / `popGuiLayer` / 任何 guiLayer 成员）。

后果：`mc.displayGuiScreen(x)` 会先调**旧屏**的 `onGuiClosed()`；`GuiContainer.onGuiClosed()`
会 `mc.player.closeScreen()` → **服务端把容器关了** → 槽位数据立刻消失。

所以 1.12.2 线必须换一套实现（CP8 的另一条路）：

```
打开面板
  ├─ 记住 background = mc.currentScreen（那个 GuiContainer）
  ├─ 直接改字段：mc.currentScreen = 我们的 GuiScreen
  │    （不调 displayGuiScreen —— 它会关掉容器）
  ├─ 手动补它的生命周期：setWorldAndResolution(mc, w, h) 然后 initGui()
  ├─ 手动 mc.mouseHelper.ungrabMouse()（displayGuiScreen 平时替你做的事）
  └─ 渲染时先调 background.drawScreen(mouseX, mouseY, partialTicks)，再画面板
关闭面板
  └─ 把 currentScreen 换回 background（同样绕开 displayGuiScreen）
```

已核实可用的 API：`Minecraft.currentScreen` 是 **public 字段**；
`GuiScreen.setWorldAndResolution(Minecraft,int,int)` 与 `onGuiClosed()` 都是 public；
`GuiContainer.drawScreen(int,int,float)` 是 public（可以整屏重画）。

> ⚠️ 这套做法与 1.16.5 线的语义差别要写进 VERIFY：**面板打开期间原界面是"我们替它画"**，
> 所以原界面的 `updateScreen()` / 计时动画不会自己跑（tick 在 `mc.currentScreen` 上）。
> 需要原界面继续动的话，在面板的 `updateScreen()` 里手动调 `background.updateScreen()` —— 待实机确认必要性。

#### 断层 2：没有"按键按下"事件 —— 快捷键改成轮询

1.12.2 的 `GuiScreenEvent` 家族只有 7 个成员（`unzip -l` 实测）：
`ActionPerformedEvent`、`BackgroundDrawnEvent`、`DrawScreenEvent`、`InitGuiEvent`（含 Pre/Post）、
`KeyboardInputEvent`、`MouseInputEvent`、`PotionShiftEvent`。

**没有** `KeyboardKeyPressedEvent`，而且 `KeyboardInputEvent` **不携带任何按键信息**
（`javap` 实测：只有一个 `(GuiScreen)` 构造器，没有 getter）。

所以 `KeyBindings#onScreenKeyPressed` 那一套在 1.12.2 不存在。正解：

```java
// TickEvent.ClientTickEvent 里轮询（不论有没有界面打开都有效）
boolean down = Keyboard.isKeyDown(OPEN_SCOPE.getKeyCode());
boolean clicked = down && !wasDown;   // 自己做边沿检测
wasDown = down;
```

这也顺带解决了"界面打开时 `KeyBinding.isPressed()` 不生效"的老问题（`isPressed()` 只消费队列，
界面开着时队列没人消费）——**不要**用 `isPressed()`。

#### 断层 3：没有 charTyped 事件 —— 输入守卫只剩前两层

`TextInputGuard` 第 3 层靠 `KeyboardCharTypedEvent` 的 Pre/Post 计数差判断"上一个字符有没有被界面吃掉"。
**1.12.2 没有这个事件**，这一层只能放弃（第 1 层 `getFocused`/`getListener`、第 2 层 JEI 探针保留）。

代价可控：默认键位是 **F9（功能键，不产生字符）**，本来就不该被文本框挡。
1.16.5 线为此加的 "功能键豁免"（`TextInputGuard#blocksHotkey`）在 1.12.2 是**默认行为**，
反而更简单。

---

## 三、已完成的这一轮

| 件 | 内容 |
|---|---|
| 分支 | `1.12.2-forge`，从 `1.16.5-forge@846818d` 分出 |
| 工具链 | `build.gradle` 重写为 FG3 + Gradle 4.9 + JDK 8；wrapper 换回 4.9 时代（jar 取自官方 MDK）；`gradle.properties` / `settings.gradle` 改写为 Gradle 4.x 语法 |
| 元数据 | 新增 `src/main/resources/mcmod.info`（`${version}` / `${mcversion}` 由 `processResources` 展开）；`pack.mcmeta` 的 `pack_format` 从 15 改成 **3**（1.12.2 的值） |
| 骨架 | `LogiScopeMod` 重写为 1.12.2 形态（`@Mod(clientSideOnly=true)` + `@Mod.EventHandler` 生命周期 + log4j2），只做"能被加载 + 打日志" |
| 纯逻辑层 | `analysis/*`、`store/*`、`archive/ArchiveData`、`archive/ArchiveFormat` **原样搬入**（零 MC import，Java 8 已满足） |
| 门禁 | `dev-check/offline-check.bat` 重写成 4 步（TestChart 476 条 + BenchRender + 版本一致性）；新增 `dev-check/check-version.py`（`@Mod` 的版本是手写常量，必须与 `gradle.properties` 一致） |
| 断言拆分 | `test`/`DataProbe` 相关的 4 条 → `dev-check/TestNbt.java`；AE 目标那 13 条（t38）→ `dev-check/TestAe.java`。两者都**不在**本线门禁里（前者等 `DataProbe` 移植、后者等 `DataTarget` 移植），断言原文与期望值一字未改 |
| 未搬 | `bind/*`、`collect/*`、`ui/*`、`LogiScopeConfig`、`archive/Archives` —— 全在 `1.16.5-forge` 分支上，用 `git show 1.16.5-forge:<路径>` 取回 |

> 为什么"删掉再搬"而不是"留着红的"：本线的纪律是**任何一次提交都能 `gradlew build` 出包**。
> 留着编译不过的文件，后面每一轮都得先看一堆红字，分不清哪些是"还没搬"哪些是"搬错了"。

---

## 四、分阶段计划（下一轮从这里接）

| 阶段 | 内容 | 难点 |
|---|---|---|
| ~~0~~ | 工具链 + 骨架 + 纯逻辑层 + 门禁 | ✅ 本轮完成 |
| 1 | `LogiScopeConfig`（`Configuration` + `@Config`）、`LogiScopeMod` 补全事件注册 | 1.12.2 的配置类在 `preInit` 里由 FML 注入，写法与 `ForgeConfigSpec` 完全不同 |
| 2 | `bind/` 层：`ProbeTarget`（维度从 `RegistryKey<World>` 改成 **`int` dimension**）、`DataTarget`、`ContainerAnchor`、`DataCommand`（`data get block` 的回执文本格式也不同） | 维度表示是全线性的改动，`DataTarget`/`ProbeTarget` 的持久化格式也要跟着变 |
| 3 | `collect/` 层：`ContainerAccess`、`ProbeSlots`、`WorldProbe`（capability）、`ServerClock`、`ProbeManager`、`ProbeInstance` | 无 `LazyOptional`；`TileEntity` 名字是 `TileEntity`（不是 `BlockEntity`）；集成服务端 API 不同 |
| 4 | `ui/Draw` 重做（`GlStateManager` + `Gui.drawRect` + `Tessellator`）→ `ChartRenderer` → `ScopeScreen` | **最大的一块**（`ScopeScreen` 5655 行）；`Draw` 这一层做对了，`ChartRenderer` 里 160+ 处绘制调用就能方法体零改动 |
| 5 | `archive/Archives`（`mcDataDir`）、`KeyBindings`（tick 轮询）、`TextInputGuard`（砍第 3 层）、CP8 的"自己管 currentScreen + 重画背景" | 见 §2.2 断层 1 |

每阶段的验收：`gradlew build` 绿 + `offline-check.bat` 全过 + 该阶段新增的 API 核实写回本文件。

---

## 五、已知限制

1. **没有实机**。本机没有 1.12.2 实例（只有 1.16.5 / 1.18.2 / 1.20.1 / 1.21.1 等）。
   要实机验证必须先建一个：Forge 14.23.5.2859 + 一个干净 `mods/`。
   建议同时备 **Cleanroom** 的第二运行时（1.12.2 是唯一能复刻"一份 jar 通吃"的版本，
   见 `C:\Users\JesseTech\WorkBuddy\Minecraft mod\docs\port-feasibility-legacy.md` §3）。
2. **AE 不做**（本轮用户明确要求）。`AeKeys` / `AeNetworkReader` 不搬；`TestAe` 不进门禁。
   将来要搬的话，AE2 rv6 的 API 与 8.x 又不一样（`AEApi.instance().grid()` 那一套），别照抄 1.16.5。
3. **`pack.mcmeta` 不是必须**（1.12.2 的资源包元数据可选），留着是为了与其它线一致。
4. **`verify-jar.py` 未适配**：它读 `mods.toml`，本线用 `mcmod.info`，所以本线门禁暂不含这一步。
   等有实机之后再补一个 `verify-mcmod.py`（校验 mcmod.info 的版本/描述与 jar 内类版本号）。
