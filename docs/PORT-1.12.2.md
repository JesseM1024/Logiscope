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

## 三、轮次 1：工具链与骨架（2026-10-21）

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

## 三之二、轮次 2：MC 面全量移植（2026-10-21）

**结果**：`bind/` `collect/` `ui/` `archive/Archives` `LogiScopeConfig` 全部搬完，
`gradlew build` 出包 `logiscope-0.1.0-1.12.2.jar`（243 KB，85 个 class），
离线门禁 `ALL_OK`（TestChart 476 + BenchRender + 版本一致性）。已装入 `1.12.2-Forge` 实例。

### 做法：机械改名 + 用编译器收割

先写脚本做**包路径级**的批量替换（`Screen→GuiScreen`、`ContainerScreen→GuiContainer`、
`CompoundNBT→NBTTagCompound`、`Direction→EnumFacing`、`ForgeRegistries` 换包…），
然后反复 `gradlew compileJava`，按 100 → 40 → 15 → 1 → 0 收敛。
一共 4 轮，每轮的错误都是**具体 API 差异**，不是猜出来的。

### 这一轮新核实的事实（都在映射名 jar 上 `javap` 过）

| 1.16.5 的写法 | 1.12.2 的真实 API |
|---|---|
| `ResourceLocation#getNamespace()/getPath()` | **`getResourceDomain()/getResourcePath()`**（1.13 才改名） |
| `mc.mouseHelper.ungrabMouse()` | `ungrabMouseCursor()` |
| `Util.getOSType().openFile(dir)` | `Util.EnumOS` **没有 openFile** ⇒ 退回 `java.awt.Desktop` |
| `block.getTranslatedName().getString()` | `block.getLocalizedName()`（直接返回 String） |
| `TileEntity#write(CompoundNBT)` / `#getType()` | `writeToNBT(NBTTagCompound)` / **`TileEntity.getKey(be.getClass())`**（静态） |
| `ForgeRegistries.TILE_ENTITIES` | **不存在**（用 `TileEntity.getKey`） |
| `level.isRemote()` | `world.isRemote`（**字段**） |
| `server.getServerConfiguration().getWorldName()` | `server.getWorldName()` |
| `cap.getCapability(...).orElse(null)` | `tile.getCapability(cap, side)` **直接返回或 null** |
| `NBTTagList#size()` | `tagCount()` |
| `IBlockState#onBlockActivated(...)` 返回 `EnumActionResult` | `Block#onBlockActivated(World,Pos,IBlockState,Player,Hand,Facing,f,f,f)` 返回 **boolean** |
| `Item#onItemUseFirst(ItemStack, ItemUseContext)` | 8 参版本（`EntityPlayer, World, BlockPos, EnumFacing, float×3, EnumHand`） |
| `PlayerController#func_217292_a(...)` | `processRightClickBlock(EntityPlayerSP, WorldClient, BlockPos, EnumFacing, Vec3d, EnumHand)` |
| `lang/en_us.json` | **`lang/en_us.lang`**（`key=value` 行；JSON 在这一代根本不读） |
| `GuiContainer#getContainer().inventorySlots` | `GuiContainer.inventorySlots` 是 **Container 本身**，槽位在 `.inventorySlots.inventorySlots` |

### 三处必须"换实现"而不是"换名字"的地方

1. **`Draw` 重写**（66 → 90 行）：没有 `MatrixStack`，全部走立即模式
   （`Gui.drawRect` / `FontRenderer` 老签名 / `RenderItem`）。
   对外方法签名保持不变 ⇒ `ChartRenderer` / `ScopeScreen` 里 200+ 处绘制调用**一行没改**。
   `g.pose()` 那三处 z 层次改成 `Draw#pushZ/popZ`（`GlStateManager.pushMatrix + translate`）。
2. **`ScopeScreen` 的屏幕生命周期**：`openOverlay` 改成直接改 `mc.currentScreen` 字段
   （`displayGuiScreen` 会触发 `onGuiClosed → closePlayerScreen`，把容器关掉），
   并手动补 `setWorldAndResolution` + `initGui` + `ungrabMouseCursor`；
   `drawScreen` 开头先 `parent.drawScreen(...)` 替父界面作画；关闭时把 `currentScreen` 换回父界面。
   `init/tick/isPauseScreen/render/mouse*/charTyped+keyPressed` 全部换成 1.12.2 的对应名字。
3. **`TextInputGuard` 重写**：没有焦点 API、没有 charTyped 事件 ⇒ 只用两条判据
   （功能键永不挡 / 会打字的键遇界面就让路），并把 `GuiChat` 单独挡掉。

### 还没解决的（写进 §五）

面板外点击/滚轮无法转交给容器 —— `GuiContainer#mouseClicked/mouseReleased/mouseScroll` 在这一代是
**protected**，跨包调不到；反射又要撞 SRG 命名（生产是 `func_73864_a`），违反"只反射 AE"的铁律。

---

## 三之三、轮次 3：首轮实机反馈的四个问题（2026-10-21）

### 实测结果（先入档，再改代码）

实机回来四条问题，其中三条其实是同一个根因。**硬证据**来自模组自己写的归档头
`versions\1.12.2-Forge\logiscope\2026-10-21_23-16-55\recording.json`：

```json
"probe":{"name":"open container (no coords)","description":"slots 0 · runtime identity"}
```

`name` 是 `logiscope.anchor.open_menu`（"界面开着但推不出坐标"），
`description` 里的 `runtime identity` 是 `logiscope.anchor.runtime`（"没有持久身份"）。
⇒ **`ProbeTarget#hasPersistentId()` 恒为 false** —— 锚点从来没拿到过。

| # | 反馈 | 根因 |
|---|---|---|
| 1 | 物品被绘制在面板**之上** | `RenderItem` 固定把物品画在 **z=100**，面板画在 z=0；深度测试开着 ⇒ z=100 必胜，绘制顺序失效 |
| C | 关掉容器界面就 `paused` / `no-data`；点「继续」报 `source unavailable` | 锚点 null ⇒ 没有坐标 ⇒ 关界面后既不能走服务端读，也不能 FOLLOWING |
| D | 「Probe data」按钮**点不了** | 同一件事：置灰条件就是 `target().hasPersistentId()` |
| — | 顺带核实 | 1.12.2 **没有 `/data` 命令**（1.13 才有）；但单人下 `DataCommand` 本来就走服务端直读，不依赖命令 ⇒ 用户猜的这条不是 D 的原因 |

### 四个坑（都在映射名 jar 上 `javap` 核实过）

**坑 1（致命）：`InitGuiEvent.Post` 触发时 `windowId` 还是 0。**
这一代客户端开窗的顺序是：

```
handleOpenWindow
  └─ EntityPlayerSP#displayGUIChest → displayGuiScreen(new GuiChest(...))
       └─ GuiScreen#setWorldAndResolution
            ├─ initGui()          ← GuiContainer#initGui: mc.player.openContainer = this.inventorySlots
            └─ InitGuiEvent.Post  ← ★ 我们在这里记锚点，此时 windowId = 0（int 默认值）
  └─ player.openContainer.windowId = packet.getWindowId()   ★ 真实窗口号在这之后才写进去
```

旧实现把锚点记在 `windowId` 上（`activeContainerId`），于是"打开时记 0、之后 `currentContainerId()`
返回 1" —— **永远对不上**，`activeFor()` 恒为 null。
**修法**：锚点挂在 **`Container` 对象身份**上。windowId 会在原地被改写，对象身份不会。

**坑 2：物品的 z=100 让"先画的面板"失效。**
`RenderItem#setupGuiTransform` 是 `translate(x, y, 100.0F + this.zLevel)`，
而面板的 `Gui#drawRect` / 文字都在 z=0。深度测试开着时 z=100 更靠近相机 ⇒
**物品直接穿透面板**，与我们画在前还是在后无关。
**修法**：整段面板绘制前 `beginPanel()`（关深度 + 关光照 + 归一混合），画完 `endPanel()` 还原；
另外 `Draw#renderItem` 逐次开关 GUI 光照（开着会让后面的文字发暗）。
`drawHoveringText` 收尾会把 depth **重新打开**，所以画完 tooltip 要再断言一次。

> 截图里满屏散落的图标是**JEI 的物品列表**（放大可辨：Mekanism 各等级管道/线缆）。
> 它被画进来的路径是：我们调 `parent.drawScreen(...)` → Forge 在 `GuiContainer.drawScreen`
> 末尾发 `GuiContainerEvent.DrawForeground` → JEI 的 `ingredientListOverlay.drawOnForeground`。
> 位置错乱的原因是**我们的假屏从不更新 JEI 的 `GuiProperties`**（JEI 的
> `updateScreen` 挂在 `GuiScreenEvent.DrawScreenEvent.Post` 上，而我们从不调 `super.drawScreen`，
> 那个事件一次都不发）⇒ 它拿着上一帧容器的属性画到了别处。
> 而这些图标同样是 `RenderItem` 画的 ⇒ 同样在 z=100 ⇒ 同样被深度测试顶到面板之上。
> **关掉深度之后，面板就把它们盖住了** —— 一条修法同时解决"父界面的物品"和"别的模组的物品"。

**坑 3：双联箱子不能直接读方块实体。**
菜单里的下标是 `InventoryLargeChest` 合起来的 0..53，而锚点只指向**一个** `TileEntityChest`（27 格）
⇒ 右半边下标越界 ⇒ 整体 `no_data`（用户测的就是 Large Chest）。
**修法**：`WorldProbe#containerOf` 先问**方块自己** ——
`BlockChest#getContainer(World, Pos, boolean)` 是 public 的，它返回的包装器顺序与客户端菜单一致。
`ProbeSlots` 与 `verifyNow` 都改走同一个 helper，保证"清单"与"读数"用同一套下标。

**坑 4：`/data` 不存在。** 多人下这一代**结构性**读不到设备 NBT。
`DataCommand` 不再发那条注定失败的命令，直接如实报"读不到"（单人不受影响）。

### 没有证据支持、但值得记下来的两条

- 用户怀疑"图层优先级"，实际是**深度**问题，不是绘制顺序问题 —— 关掉深度就够了
  （而且 1.12.2 的 `EntityRenderer#updateCameraAndRender` 是 **先 HUD 再 `currentScreen`**，
  屏幕级绘制天然最后，所以"顺序"这条本来就不可能错）。
- 截图里那批散落图标**不是我们画的**（全项目只有两处 `renderItem` 调用，各画一个图标），
  是 JEI 的物品列表被画进了父界面帧（路径见坑 2 的引注）。改完 z 层次后它们会被面板盖住；
  若还想彻底不画，就得不再转交 `GuiContainerEvent.DrawForeground`（代价见 §五）。

---

## 三之四、轮次 4：AE 终端与"面板被覆盖"（2026-10-22）

### 实测结果

关闭容器后继续追踪、`Probe data` 都通过了（轮次 3 的三条修复有效）。新报三条：

| # | 反馈 | 根因 |
|---|---|---|
| 1 | AE 终端里选格，**玩家背包没被筛掉** | AE 用 `PlayerInvWrapper` 包玩家背包 ⇒ `slot.inventory` 是 `SlotItemHandler` 的占位空 `InventoryBasic`，`isPlayerSlot` 判不出来 |
| 1 | AE 物品**数量恒 1** | AE 终端格 `Slot#getStack()` 的 count 恒 1，真值在 `SlotME#getAEStack().getStackSize()` |
| 2 | 流体终端**一条都读不到** | `SlotFluidME.getStack()` 返回空（流体不是物品）；rv6 **没有** `wrapped_generic_stack` 那类占位物品（那是 AE2 10 才有的） |
| 3 | 选格页右侧整片物品压在面板上 | 面板画在 `drawScreen` 里，而 JEI 的物品列表挂在 **`DrawScreenEvent.Post`** 上 ⇒ JEI 晚于我们 |

### 三条核实（`javap`，AE2 rv6-stable-7）

```
appeng.container.AEBaseContainer#bindPlayerInventory
  → new PlayerInvWrapper(playerInventory) + SlotPlayerInv / SlotPlayerHotBar
    （都不是 Slot(inventory=player.inventory) ⇒ 老判据必然漏）
appeng.client.me.SlotME#getAEStack()           → IAEItemStack   （public）
appeng.client.me.SlotFluidME#getAEFluidStack() → IAEFluidStack  （public）
IAEStack#getStackSize() → long；IAEItemStack#createItemStack() → ItemStack
IAEFluidStack#getFluidStack() → FluidStack
提交顺序：ForgeHooksClient#drawScreen = Pre → screen.drawScreen → Post
```

### 改动

| 件 | 改动 |
|---|---|
| `collect/AeSlots`（新） | 反射读 AE 槽位真值：物品走 `getAEStack().getStackSize()`，流体走 `getAEFluidStack().getFluidStack()`；流体身份用 **`ae:fluid:<流体注册名>`**，数量单位 mB。没装 AE 时全 null，零成本。取 Class 一律 `Class.forName(name,false,loader)` |
| `collect/ContainerAccess` | `isPlayerSlot` 加第二条判据（`SlotItemHandler#getItemHandler()` 是 `PlayerInvWrapper` 或 `InvWrapper.getInv()==player.inventory`）；`snapshot/read/findByContainerIndex` 统一走新的 `viewOf()` → 先问 AE 再走 vanilla |
| `ui/PanelPainter`（新） | 面板改到 `DrawScreenEvent.Post` 里画；**第一次开屏时才注册** ⇒ 派发顺序排在 JEI 等模组之后 ⇒ 面板永远在最上层。注册失败则退回即时绘制（`isReady()` 兜底），面板不会消失 |
| `ui/ScopeScreen` | `drawScreen` 只负责"替父界面作画 + 准备数据"，`renderPanel` 负责画（由 PanelPainter 回调） |

### 已知限制（新增）

1. **AE 探针关掉终端后记 `no_data`**：ME 网络的内容不在那个方块的物品栏里，
   而本线还没有"服务端读 AE 网络"的通道（那是 `AeNetworkReader` 级别的活，见 §五）。
   ⇒ AE 探针目前是"界面开着才读数"。
2. **流体序列的名字是注册名**（`fluid:water` 这种短名），不是本地化名 —— 身份要稳定。
3. AE 物品的身份用 registry id（丢 meta/NBT 区分），与其它线一致。

### 轮次 4 的追加修复（2026-10-22 第二轮实机）

| 反馈 | 根因 | 修法 |
|---|---|---|
| 选格页 Pick cells 时面板变成一片灰块 / 仍被盖住 | 光靠"我们注册得晚"不足以保证排在最后；而且我们 `endPanel()` 之后若还有人画，会继承我们改过的 GL 状态（实测：JEI 的列表整片渲染成灰块） | `PanelPainter` 的监听器加 **`priority = EventPriority.LOWEST`** —— Forge 按优先级派发，低优先级一定最后执行；面板之后**没人再画**，也就不会把状态传给别人 |
| AE 背包格**仍然能选中** | `SlotPlayerInv`/`SlotPlayerHotBar` 继承的是 **`AppEngSlot`**（不是 `SlotItemHandler`），而 `AppEngSlot` 把 `IItemHandler` 放在 **private 字段、不给访问器** ⇒ 上一版只判 `SlotItemHandler` 全部漏过 | `ContainerAccess#itemHandlerOf`：先问 `SlotItemHandler#getItemHandler()`，否则在类层级里找第一个 `IItemHandler` 类型字段反射读（按类缓存）。`PlayerInvWrapper` / `InvWrapper#getInv()` 两条判据复用 |
| 面板开着时 **AE 终端不刷新**（漏斗往网络里灌，数字不动；关上探针才跳一下） | 我们换掉了 `mc.currentScreen` ⇒ 父界面**再也不 `updateScreen()`**；AE 的 repo 正是在 `GuiMEMonitorable#updateScreen()` 里把网络推送合进来的。原版箱子不受影响是因为容器内容由服务端 `detectAndSendChanges` 主动推 | `ScopeScreen#updateScreen` 里**转发**给 `parent.updateScreen()` |
| 界面上流体行全是 `#0` | 行首 `#N` 用的是**容器内下标**（`getSlotIndex()`），AE 流体/物品槽这个值恒 0 | **未改**（显示问题）：选格仍按菜单下标走，行靠名字区分。要改需要同时动 `PickerFilter` 的 `#N` 语义，留到下一轮 |

> 顺带记一条自己的坑：`HANDLER_FIELD_CACHE` 一开始用了 `ConcurrentHashMap`，
> 而"这个类没有 IItemHandler 字段"的结果是 **null** ⇒ 所有原版格子第一次判就是 NPE。
> 换成 `HashMap`（客户端单线程用，不需要并发容器）。

### 轮次 6：放弃"换屏幕"，改成真图层（2026-10-22）

**为什么前两轮的修法治不了 AE 刷新**
`PacketMEInventoryUpdate#clientPacketData`（javap rv6-stable-7）：

```java
Minecraft mc = Minecraft.getMinecraft();
if (mc.currentScreen instanceof GuiCraftConfirm) ...
else if (mc.currentScreen instanceof GuiCraftingCPU) ...
else if (mc.currentScreen instanceof GuiMEMonitorable) ((GuiMEMonitorable) mc.currentScreen).postUpdate(list);
```

**AE 的网络更新包只投递给"当前界面"**。我们把 `mc.currentScreen` 换成自己的面板之后，
包到客户端就找不到 `GuiMEMonitorable` → `postUpdate` 不执行 → repo 永不更新
（终端画面冻结、探针读旧值）。而且 `repo.updateView()` 只挂在 `postUpdate` /
`initGui` / 点击 / 按键上，**不在 `updateScreen` 里** ⇒ 上一版"转发 `parent.updateScreen()`"
根本救不了。

**改法：`mc.currentScreen` 保持是父界面，面板做成真图层**

| 件 | 做法 |
|---|---|
| `ScopeScreen#openOverlay` | 不再改 `mc.currentScreen`；只 `setWorldAndResolution(mc, parent.width, parent.height)` 拿尺寸，然后把自己登记成 `ACTIVE` |
| `PanelPainter` | `DrawScreenEvent.Post` + `priority = LOWEST`，挂在**父界面**的 Post 上画 |
| `PanelInput`（新） | `MouseInputEvent.Pre` / `KeyboardInputEvent.Pre` 拦输入：落在面板上就 `setCanceled`（= 吃掉这一条），面板外放行给容器 |
| tick | 面板不是当前界面 ⇒ `updateScreen` 由 `ClientTickEvent` 驱动；同时检查"父界面还在不在"，不在就把面板收掉 |

**★ `GuiScreen#handleInput()` 的真实语义（javap，必须记住）**：
```java
while (Mouse.next()) {                                    // 队列由它推进
    if (EVENT_BUS.post(new MouseInputEvent.Pre(this))) continue;   // 取消 = 跳过这一条
    this.handleMouseInput();                              // 只处理当前这一条
}
while (Keyboard.next()) { ... KeyboardInputEvent.Pre ... }
```
事件是**逐条**发的（不是每 tick 一次），取消一条只是 `continue` 到下一 条 ——
**所以处理函数里绝不能自己调 `Mouse.next()`/`Keyboard.next()`**（会白白吞掉下一条）。

**顺带修好的**：面板外的点击/滚轮现在能真的转交给容器（旧写法下
`GuiContainer#mouseClicked` 是 protected，转交不了）；JEI 面对的是一个**正常的容器界面**，
它的物品列表不再被画到奇怪的位置（I1 的灰块随之消失）。

---

## 四、分阶段计划

| 阶段 | 内容 | 难点 |
|---|---|---|
| ~~0~~ | 工具链 + 骨架 + 纯逻辑层 + 门禁 | ✅ 轮次 1 |
| ~~1~~ | `LogiScopeConfig`（`@Config`）、`LogiScopeMod` 事件注册 | ✅ 轮次 2 |
| ~~2~~ | `bind/` 层：维度用 `compat/Dim`（包一层 `int`，语义等价 `RegistryKey`）、`ProbeTarget`/`DataTarget`/`ContainerAnchor`/`DataCommand` | ✅ 轮次 2（`Dim` 让 20 多处调用点只换类型名） |
| ~~3~~ | `collect/` 层：`ContainerAccess`、`ProbeSlots`、`WorldProbe`、`ServerClock`、`ProbeManager`、`ProbeInstance` | ✅ 轮次 2（去 `LazyOptional`、`isSingleplayer()`、`getTotalWorldTime()`） |
| ~~4~~ | `ui/Draw` 重做 → `ChartRenderer` → `ScopeScreen` | ✅ 轮次 2。`Draw` 对外签名不变，所以 200+ 处绘制调用零改动 |
| ~~5~~ | `archive/Archives`、`KeyBindings`（tick 轮询）、`TextInputGuard`、CP8 的"自己管 currentScreen + 重画背景" | ✅ 轮次 2 |

每阶段的验收：`gradlew build` 绿 + `offline-check.bat` 全过 + 该阶段新增的 API 核实写回本文件。

---

## 五、已知限制

1. **实机环境**：`versions\1.12.2-Forge`（Forge 14.23.5.2864 + AE2 rv6 / MEK 9.8.3 / TE 5.5.7 / CoFH / JEI 4.22）。
   构建基线是 2859，差一个官方小版本，二进制兼容。
   另建议备 **Cleanroom** 的第二运行时（1.12.2 是唯一能复刻"一份 jar 通吃"的版本，
   见 `C:\Users\JesseTech\WorkBuddy\Minecraft mod\docs\port-feasibility-legacy.md` §3）。
2. **AE 不做**（本轮用户明确要求）。`AeKeys` / `AeNetworkReader` 不搬；`TestAe` 不进门禁。
   将来要搬的话，AE2 rv6 的 API 与 8.x 又不一样（`AEApi.instance().grid()` 那一套），别照抄 1.16.5。
3. **`pack.mcmeta` 不是必须**（1.12.2 的资源包元数据可选），留着是为了与其它线一致。
4. **面板外的鼠标事件不再转交给容器**（新）：`GuiContainer#mouseClicked/mouseReleased/mouseDragged`
   在 1.12.2 是 **protected**，跨包调不到；反射又要撞 SRG 命名（`func_73864_a`），
   违反"只反射 AE"的铁律。所以面板开着时点不到容器 —— 按 F9 / Esc 收起后再点。
   另外 `GuiContainer` 没有 `mouseScrolled`，**面板外滚轮也不转交**。
5. **Gradle 编译基线是 Forge 2859，实例装的是 2864**：只差官方小版本，
   `acceptedMinecraftVersions="[1.12.2]"` 不锁 Forge，两者二进制兼容（待网络方便时对齐）。
6. **`verify-jar.py` 未适配**：它读 `mods.toml`，本线用 `mcmod.info`，所以本线门禁暂不含这一步。
   等有实机之后再补一个 `verify-mcmod.py`（校验 mcmod.info 的版本/描述与 jar 内类版本号）。
7. **多人下读不到设备 NBT**（新，结构性）：1.12.2 没有 `/data`，客户端进程里又没有方块实体。
   DATA 页在多人下只会显示"读不到"；逐 tick 采样同理（只在单人成立）。
8. **NBT 槽位模式对双联箱子只覆盖一半**（新）：`DataProbe` 读的是**锚点那个方块实体**的 NBT，
   双联箱子的两半各有一份 27 格的 NBT。要测整只大箱子请用**普通槽位模式**
   （`WorldProbe#containerOf` 已经按 54 格处理），别用 NBT 槽位模式。
9. **AE 只能"界面开着时读数"**（轮次 4）：槽位真值走客户端菜单（`SlotME#getAEStack` /
   `SlotFluidME#getAEFluidStack`）。关掉终端后服务端读不到 —— ME 网络不在那个方块的物品栏里。
   要补的是"服务端读 AE 网络"（rv6：`IGridHost#getGridNode(AEPartLocation)` → `IGrid#getCache` →
   `IMEInventory#getAvailableItems`），另开一轮。
10. **AE 流体的身份是 `ae:fluid:<流体注册名>`、数量单位 mB**；图例显示成 `fluid:water` 这类短名
   （注册名是稳定的，本地化名不稳定 —— 身份优先）。
