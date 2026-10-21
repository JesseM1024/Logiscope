# LogiScope · 1.16.5 Forge 移植记录

分支：`1.16.5-forge`（从 `1.18.2-forge` 拉出）  
基线：Forge **1.16.5-36.2.42**（= 用户 HMCL 测试实例版本）  
映射：MCP snapshot **`20210309-1.16.5`**  
结论：**编译通过（Java 8 目标）、打包通过、离线门禁 461 条断言全过**；实机待验。

---

## 一、前提：这一代没有 NeoForge，也没有第二个平台

NeoForge 的第一个版本是 MC 1.20.1。1.16.5 时代 Forge 是**唯一**平台：

- 不存在"双加载器兼容"问题，也不需要为它做任何设计；
- 1.20.1 那条"一份 jar 通吃 NeoForge / Forge"的结论**不可外推**（它依赖"NeoForge 47 仍用  
  `net.minecraftforge` 包名 + 仍用 `forge` 这个 modId"这两个 1.20.1 独有的历史巧合）；
- 反过来，1.16.5 的 API 是纯 Forge 的，可以放心用 `CapabilityItemHandler`、`GuiScreenEvent` 等平台能力。

---

## 二、加载器适配版本区间（本次重点）

`src/main/templates/META-INF/mods.toml` 经 `gradle.properties` 展开后的实际值：

| 字段                                    | 值           | 理由                                                                                      |
| ------------------------------------- | ----------- | --------------------------------------------------------------------------------------- |
| `loaderVersion`                       | `[36,)`     | 1.16.5 世代 `javafml` 的大版本是 36。写 `[36,)` = "任何 36.x 的 FML 都接受"                            |
| `dependencies.forge.versionRange`     | **`[36,)`** | **下界取 36（1.16.5 线的起点），不取编译用的 36.2.42** —— 否则 36.0.x / 36.1.x 的老整合包会被 FML 直接拒载，"本可用却用不了" |
| `dependencies.minecraft.versionRange` | `[1.16.5]`  | 只认 1.16.5，跨小版本不做承诺                                                                      |
| 依赖 `side`                             | `CLIENT`    | 纯客户端模组；服务端不装也不报错                                                                        |

**为什么不写 `[36.2.42,)`**：编译基线只是为了拿到"最新 API + 最完整签名"，  
运行期能用到的最低版本由**实际调用的 API** 决定。本次用到的都是 1.16.x 世代早已存在的  
长稳 API（`ForgeConfigSpec`、`TickEvent.ClientTickEvent`、`GuiScreenEvent`、  
`CapabilityItemHandler`、`ForgeHooksClient.pushGuiLayer`、`ClientRegistry`），  
所以下界可以安全地压到 36。

**为什么不再往下压（35.x）**：35.x 对应 MC 1.16.4，`minecraft_version_range=[1.16.5]` 本来就会拒掉。

---

## 三、构建层改动

| 文件                                         | 改动                                                                                                                                                                                                       |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `build.gradle`                             | 换成 **ForgeGradle 5.1**（`apply plugin: 'net.minecraftforge.gradle'`）；`mappings channel: 'snapshot', version: '20210309-1.16.5'`；run 目录 `1_16_5_forge/`；`java.toolchain = 8`；新增 `copyMinecraftDeps` 任务（见下） |
| `gradle.properties`                        | `forge_version=1.16.5-36.2.42`（**FG5 要求带 MC 前缀**）；`forge_version_range=[36,)`；`loader_version_range=[36,)`；`org.gradle.java.home` 指向 JDK 17（守护进程用），编译目标另由 toolchain 锁到 8                                 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle **9.2.1 → 7.6.4**（FG5 不支持 Gradle 8+）                                                                                                                                                              |
| `settings.gradle`                          | 去掉 `org.gradle.toolchains.foojay-resolver-convention`（Gradle 7.6 不认这个插件）                                                                                                                                 |
| `.gitignore`                               | 加 `1_16_5_forge/`                                                                                                                                                                                        |
| `dev-check/offline-check.bat`              | classpath 换成 `dev-check\lib\*;build\mcdeps\*`；jar 名 `logiscope-0.1.2-1.16.5-alpha.jar`                                                                                                                   |
| `dev-check/TestChart.java`                 | `IntTag/ListTag/StringTag` → `IntNBT/ListNBT/StringNBT`                                                                                                                                                  |

### 为什么不是 MDG

ModDevGradle 的 legacy 插件官方支持范围是 **Forge 1.17–1.20.1**（见其 `LEGACY.md`），  
**1.16.5 不在内**。这一代只能回到 ForgeGradle 5，配套 Gradle 7.6.4。  
1.18.2 那条"构建栈与 1.20.1 同构、只换版本号"的便利在这里没有，**构建层是重建的**。

### `copyMinecraftDeps`：为离线门禁铺一条稳定 classpath

离线断言需要「带映射名的 MC+Forge 类路径」。FG5 把 mapped jar 放在  
`~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.16.5-36.2.42_mapped_snapshot_20210309-1.16.5/`  
下 —— 路径里带映射版本号，`.bat` 里硬编码会随 mappings 变动而失效。

所以加了一个 Copy 任务，把 `configurations.minecraft` 里的 jar 全部复制到 `build/mcdeps/`，  
`.bat` 用通配符 `build\mcdeps\*` 引用：

```gradle
var copyMinecraftDeps = tasks.register('copyMinecraftDeps', Copy) {
    from(configurations.minecraft) { include '**/*.jar' }
    into layout.buildDirectory.dir('mcdeps')
}
tasks.named('build') { dependsOn copyMinecraftDeps }
```

`configurations.minecraft` 里不止一个文件（mapped jar / launcher jar / client-extra /  
accesstransformers / eventbus / coremods / brigadier / authlib …），  
**必须全给**：只拷 mapped jar 时运行期会 `NoClassDefFoundError: com/mojang/brigadier/Message`  
（`DataProbe` 的类链接阶段就会碰到 brigadier）。

### ★ 拿哪个任务出包：`build`，不是 `jar`

这是 ForgeGradle 与 MDG 的一个实质差别，1.18.2 / 1.20 线**没有**这个坑。

| 任务 | 做了什么 | 产物（实测） | 能不能装进实例 |
|---|---|---|---|
| `jar` | 只打包 | 242,419 B，**1 处 SRG 名**（只有源码手写的 `func_217292_a`），其余全是 `getGuiLeft` / `getStack` / `mouseClicked` 这类 MCP 友好名 | ❌ 生产运行时用 SRG，必然 `NoSuchMethodError` |
| `build` | `assemble` → `jar` + **`reobfJar`** | 245,041 B，**84 处 SRG 名** | ✅ |

`reobfJar` 把 MCP 名重混淆成 SRG 名，并**覆盖** `build/libs/` 里的同名 jar。
也就是说两个产物**同名同路径**，后跑的覆盖先跑的 —— 在 IDEA 里点一下 `jar`，
刚 build 好的可用包就退化成 dev 版了（实测：245,041 → 242,419，SRG 84 → 1）。

**判据**（不用开游戏就能分辨）：

```bat
:: 看大小
dir build\libs\
:: 245,041 B = 可用；242,419 B = dev 版，别用
```

装进实例：删掉 `mods/` 里的同名旧 jar → 拷 `build\libs\logiscope-0.1.2-1.16.5-alpha.jar`。
纯客户端模组，服务端不装也不报错。

---

## 四、代码层

### 4.1 `Draw` 重建（`ui/Draw.java`）

1.20 把「矩阵 + 绘制方法」收成了 `GuiGraphics`；1.16.5 拆得更碎：  
矩阵是 `MatrixStack`，静态绘制在 `AbstractGui`，文字在 `FontRenderer`，物品在 `ItemRenderer`。

处理手法与 1.18.2 相同 —— **只做一层与 `GuiGraphics` 同签名的替身**，让  
`ScopeScreen` / `ChartRenderer` 的绘制调用**方法体一行不改**：

```java
public MatrixStack pose()                                                  // 对应 GuiGraphics#pose()
public void fill(int x1, int y1, int x2, int y2, int color)                 // AbstractGui.fill(pose, ...)
public void drawString(FontRenderer, String, int, int, int[, boolean])      // drawStringWithShadow / drawString
public void drawString(FontRenderer, ITextComponent, int, int, int[, boolean])
public void renderItem(ItemStack, int, int)                                 // renderItemAndEffectIntoGUI
public void renderItemDecorations(FontRenderer, ItemStack, int, int)        // renderItemOverlayIntoGUI
```

两点与 1.18.2 不同：

1. **字体是显式参数**。1.16.5 的 `FontRenderer` 没有全局入口，物品数量叠层  
   （`renderItemOverlayIntoGUI`）**必须**传 `FontRenderer`，所以 `renderItemDecorations`  
   比 1.18.2 多一个 `font` 参数 —— 调用处随之传 `this.font`。
2. **tooltip 不进 `Draw`**。1.16.5 的 `Screen#renderTooltip(MatrixStack, ItemStack, int, int)`  
   已经收矩阵，直接 `this.renderTooltip(g.pose(), stack, mouseX, mouseY)`。

`ITextComponent` 这个名字在 1.16.5 **是对的**（1.18.2 叫 `Component`，1.20 又叫回  
`Component`）—— 版本间命名来回改过，只能按 srg/mapped jar 核实，不能靠记忆。

### 4.2 Java 8 降级清单

1.16.5 是 **Java 8** 世代。`java.toolchain = 8` 让编译器直接拦住所有高版本语法，  
下面每一项都是被编译错误逼出来的：

| 1.18.2 写法                        | Java 8 处理                                                          | 规模                                                                                              |
| -------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------- |
| `record X(...)`                  | 改成 `final class` + 构造器 + getter + 手写 `equals/hashCode/toString`    | **39 个**（36 个脚本转换 + 3 个手工：`ArchiveFormat.Header`、`ChartData.SeriesRange`、`ProbeTarget.SlotRef`） |
| 箭头 `switch`（语句式 / 表达式式）          | 经典 `case X:` + `break`；表达式式要先声明变量再赋值                               | **约 145 个 case 区域**（`ScopeScreen` 115 个）                                                        |
| `x instanceof Foo f`             | `x instanceof Foo` + 显式强转                                          | 多处                                                                                              |
| `var x = ...`                    | 显式类型                                                               | 多处                                                                                              |
| `str.isBlank()`                  | `str.trim().isEmpty()`                                             | 1 处（`DataProbe`）                                                                                |
| `stream.toList()`                | 项目自带 `List#toList()`（**不是** `Stream#toList`，别误改）                   | 5 处                                                                                             |
| `List.of / Set.of / Map.of`      | `Arrays.asList` / `Collections.emptyXxx`                           | 多处                                                                                              |
| `Files.writeString / readString` | `Files.write` / `Files.readAllBytes` + 显式 `StandardCharsets.UTF_8` | `Archives`                                                                                      |
| `Optional.isEmpty()`             | `!isPresent()`                                                     | 多处                                                                                              |
| 被静态上下文使用的嵌套类                     | 显式 `static`                                                        | 若干                                                                                              |

**降级过程中的两个坑**（都真实踩过）：

- **机械替换会毁掉 import**。第一版脚本做了全局简单名替换，  
  结果 `import net.minecraft.inventory.container.Container;` 变成 `...IInventory`。  
  正确做法：**import 行只做整行映射，不参与名字替换**，并从干净 checkout 重跑。
- **`switch` 转换会丢掉终止语义**。语句式 `case` 里本来 `return` 掉的分支，  
  机械转换后又补了个 `break;` → `无法访问的语句`；穷举枚举的表达式 switch 转成经典 switch 后  
  javac 不再认为"穷尽" → `缺少返回语句`。两类都得手工补 `default` / 删死 `break`。  
  转换脚本本身不可信，**必须靠 javac 逐条兜底**。

### 4.3 API 差异（1.18.2 → 1.16.5）

括号里是本次源码里的实际用量，用来判断"改得有多深"：

| 主题        | 1.18.2 / 1.20.1                                                                  | 1.16.5                                                                                         | 用量         |
| --------- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | ---------- |
| 变换矩阵      | `PoseStack`                                                                      | `MatrixStack`                                                                                  | 8          |
| 字体        | `Font`；`font.width(s)`                                                           | `FontRenderer`；`font.getStringWidth(s)`                                                        | 17 / 45    |
| 静态绘制      | `GuiGraphics`                                                                    | `AbstractGui.fill(MatrixStack, …)`                                                             | 5          |
| 容器界面基类    | `AbstractContainerScreen`                                                        | `ContainerScreen`                                                                              | 25         |
| 世界        | `Level` / `ServerLevel`                                                          | `World` / `ServerWorld`                                                                        | 55 / 25    |
| 方块实体      | `BlockEntity`；`level.getBlockEntity`                                             | `TileEntity`；`world.getTileEntity`                                                             | 19 / 10    |
| 容器（物品栏接口） | `net.minecraft.world.Container`                                                  | `IInventory`                                                                                   | 26         |
| 菜单 / 槽位列表 | `AbstractContainerMenu`；`menu.slots`                                             | `Container`；`menu.inventorySlots`                                                              | 2          |
| 槽位下标      | `Slot#getContainerSlot()`                                                        | **`Slot#getSlotIndex()`**                                                                      | 4          |
| NBT 类型    | `CompoundTag` / `ListTag` / `Tag`                                                | `CompoundNBT` / `ListNBT` / `INBT`（另有 `IntNBT` `StringNBT` `FloatNBT` `DoubleNBT` `NumberNBT`） | 14 / 4 / 8 |
| 文本组件      | `Component`；`Component.literal`                                                  | `ITextComponent`；`StringTextComponent`                                                         | 5          |
| 注册表       | `ForgeRegistries.BLOCK_ENTITY_TYPES`                                             | `ForgeRegistries.TILE_ENTITIES`                                                                | 1          |
| 方块名       | `block.getName()`                                                                | **`block.getTranslatedName()`**                                                                | 1          |
| 界面事件      | `ScreenEvent.InitScreenEvent / KeyboardKeyPressedEvent / KeyboardCharTypedEvent` | `GuiScreenEvent.InitGuiEvent / KeyboardKeyPressedEvent / KeyboardCharTypedEvent`               | 16         |
| 键位注册      | `RegisterKeyMappingsEvent`（1.19+）                                                | `FMLClientSetupEvent` + `ClientRegistry.registerKeyBinding`                                    | 3          |
| 物品能力      | `ForgeCapabilities.ITEM_HANDLER`                                                 | `CapabilityItemHandler.ITEM_HANDLER_CAPABILITY`                                                | 6          |
| 右键交互      | `player.getInventory()`                                                          | `player.inventory`                                                                             | 43         |
| 打开的菜单     | `player.containerMenu`                                                           | `player.openContainer`                                                                         | 2          |
| 聊天回执      | `player.sendCommand(...)`                                                        | `player.sendChatMessage(...)`                                                                  | 1          |
| 右键发方块     | `useItemOn(...)`                                                                 | **`playerController.func_217292_a(player, (ClientWorld) world, hand, hit)`**                   | 3          |

**关于 `func_217292_a`**：这不是"硬编码混淆名"。1.16.5 的 MCP 快照 `20210309-1.16.5`  
**没有**给 `PlayerController#useItemOn` 起友好名，它就以 SRG 名发布；  
而生产运行时本来也走 SRG，所以这个写法在 dev 与实机都正确。  
（1.12.2 线会遇到同类问题，届时同样只能按 mapped jar 核实。）

---

## 五、门禁结果

```
gradlew.bat build --offline                  → BUILD SUCCESSFUL
cmd //c dev-check\offline-check.bat          → ALL_OK
```

| 检查项                       | 结果                                                                                                                                                                          |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `compileJava`（javac 8 目标） | 0 错误                                                                                                                                                                        |
| 产物                        | `build/libs/logiscope-0.1.2-1.16.5-alpha.jar`，245,085 字节，84 个类                                                                                                              |
| 字节码版本                     | major **52**（= Java 8），全部一致                                                                                                                                                 |
| reobf 是否生效                | jar 内 **84 处 SRG 名**（`func_*` / `f_*`）→ FG5 的 `reobfJar` 确实跑了                                                                                                               |
| refmap                    | 无（1.16.5 无 mixin，不需要）                                                                                                                                                       |
| 资源                        | `assets/logiscope/lang/{en_us,zh_cn}.json`、`pack.mcmeta`、`META-INF/mods.toml` 齐全                                                                                            |
| 离线断言                      | TestChart **461 条，0 失败**                                                                                                                                                    |
| 渲染基准                      | BenchRender 通过                                                                                                                                                              |
| jar 验收                    | `verify-jar.py` 三项全过（三类文件齐全 / 占位符已替换 / 元数据符合预期）                                                                                                                             |
| SRG 名交叉核对                 | jar 内 84 个 `func_*` / `f_*` **全部能在 `forge-1.16.5-36.2.42-srg.jar` 里找到**（0 缺失）→ 没有拼错的重混淆名                                                                                    |
| 类引用核对                     | 引用的 99 个 `net.minecraft*` 类里 96 个在 srg jar 内；缺的 3 个是 `Dist` / `IEventBus` / `FMLEnvironment` —— 它们住在 Forge 启动器的库 jar（forgespi / eventbus / fmlcore）里，运行时由 Forge 提供，**不是缺陷** |
| **运行时引用检查（第 5 步）**        | `dev-check/check-runtime-refs.py`：101 个外部类引用全部可解析，且**无黑名单命中**（`org/slf4j` 清零）。见 §六之三 —— 这条是实机崩过一次才补上的 |

`mods.toml` 展开后的关键字段（实测从 jar 里读回）：

```
loaderVersion="[36,)"
modId="logiscope"  version="0.1.2-1.16.5-alpha"
dependencies: forge [36,) side=CLIENT ; minecraft [1.16.5] side=CLIENT
```

---

## 六、实机第一轮：F9 关掉容器 + 关闭面板闪退（2026-10-20，已修）

**现象**（用户 runClient 实测）：
1. 打开箱子 → 按 F9 → **箱子界面被关闭**，但面板出来了，"创建探针"能用；
2. 再点"关闭" → **卡死闪退**。

**崩溃栈**（`crash-2026-10-20_17.18.32-client.txt`）：

```
java.lang.StackOverflowError: mouseClicked event handler
  at Minecraft.displayGuiScreen(Minecraft.java:842)
  at ScopeScreen.closeScope(ScopeScreen.java:558)
  at ScopeScreen.onClose(ScopeScreen.java:564)
  at Minecraft.displayGuiScreen(Minecraft.java:842)   ← 循环
```

**两个独立缺陷，都源于 1.16.5 与 1.17+ 的语义差异**：

### 缺陷 1：`isOwnInventoryScreen` 判据选错（导致箱子被关）

原写法 `screen instanceof ContainerScreen` —— 本意是"排除玩家自己的背包界面"，
但 1.16.5 的 `ContainerScreen` 是**所有**容器界面的抽象基类：

```
ContainerScreen                 (abstract)   ← 判据命中的是这一层
  ├ ChestScreen（箱子/熔炉/模组设备）
  └ DisplayEffectsScreen        (abstract)
       ├ InventoryScreen（生存背包）
       └ CreativeScreen（创造背包）
```

于是箱子界面也被判成"自己的背包" → 不进 overlay → 走 `openStandalone` →
`displayGuiScreen(ScopeScreen)` → 字节码里 `if (新屏 != 旧屏) 旧屏.onClose()` →
`ChestScreen.onClose()` → `container.onContainerClosed(player)` → **容器关闭**。

> 对照：1.18.2 线用的是 `EffectRenderingInventoryScreen`，那是**背包专有**基类，
> 不会误伤箱子。移植时按"抽象容器界面基类"的语义把它换成了 `ContainerScreen`，
> 层级选高了一层 —— 典型的"近名不同层级"。

**修法**：改成行为判据，跨版本都成立：

```java
if (!(screen instanceof ContainerScreen)) return false;
return ((ContainerScreen<?>) screen).getContainer() == mc.player.container;
```

箱子界面的容器是 `ChestContainer` ≠ `player.container` → 不算背包 → 走 overlay ✓

### 缺陷 2：`onClose()` 里发起关闭（导致闪退）

1.16.5 的两个关闭语义**没有分开**（1.17+ 才分开）：

| 入口 | 角色 | 谁调它 |
|---|---|---|
| `closeScreen()` | **发起关闭** | vanilla `Screen#keyPressed` 的 ESC 分支 |
| `onClose()` | **被通知关闭** | `Minecraft#displayGuiScreen`（切屏时对旧屏）、`ForgeHooksClient#popGuiLayerInternal`（弹层前） |

原来的 `onClose() { closeScope(); }` 就是"关闭 → 被通知 onClose → 再关闭"：
`displayGuiScreen(null)` 发现新屏(null) ≠ 旧屏(this) → 调 `this.onClose()` → 又关闭 → 无限递归。

**修法**：
- `onClose()` 改成空实现（只留注释说明范式）；
- 新增覆写 `closeScreen() → closeScope()` —— 这样 ESC 也能正确走"弹层"而不是
  vanilla 的 `displayGuiScreen(null)`（后者会 `clearGuiLayers`，把面板底下的容器界面
  从栈里丢掉：容器还开着、界面再也回不来）；
- `closeScope()` 加 `closing` 重入保护：将来若再有人在"被通知关闭"里调它，
  代价是"关不掉"，而不是整个游戏崩掉。

### 顺带

- `openOverlay` 在 `mc.currentScreen == null` 时降级走 `openStandalone`（压层没意义）；
- 三处关闭/打开路径各加一行 `LOGGER.debug`，日志里能直接看出走了哪条（`压浮层 parent=ChestScreen` / `独立打开 旧屏=...`）；
- 清掉移植时留下的重复 `import ContainerScreen`。

**回归**：`build` 通过；离线门禁 461 条断言全过。**实机待复测**（见下节）。

### 复测点（按优先级）

1. 箱子界面按 F9 → 面板浮在箱子上、**箱子还在**（背景可见、格子可点）；日志应为 `压浮层 parent=ChestScreen`。
2. 面板上点「关闭」→ 回到箱子界面，不闪退；日志应为 `关闭面板 overlay=true`。
3. 面板上按 ESC → 同上（走 `closeScreen` 覆写）。
4. 背包界面按 F9 → 面板独立打开（`独立打开 旧屏=InventoryScreen`），关闭后回到游戏。
5. 无界面按 F9 → 独立打开；再按 F9 → 收起。

---

## 六之二、实机第二轮：格子高亮盖住物品（2026-10-20，已修）

**现象**（用户截图）：选格提示框渲染正常，但——
- 选格模式下，**已选格里的物品被绿色块洗成一片**（4 个草方块只剩轮廓）；
- 未选格（普通悬停）时，**悬停格的物品被一层白蒙住**。

**根因：绘制顺序**。vanilla 的 `ContainerScreen#render` 顺序是

```
格子底色 → 悬停高亮 → 物品（renderSlot）
```

也就是**高亮本来就该在物品下面**。但我们的浮层是在背景容器界面**整体画完之后**才画的
（Forge `pushGuiLayer` + `ForgeHooksClient.drawScreen` 先遍历 guiLayers 再画当前屏），
所以补画的高亮必然压在物品上。

而且这个顺序**改不了**：`ForgeHooksClient.drawScreen` 给 `guiLayers` 里每一层传的鼠标坐标是
硬编码的 `Integer.MAX_VALUE`（javap 已核实，`lambda$drawScreen$4` 里两个 `2147483647`），
背景层永远算不出 `hoveredSlot` —— 这正是我们当初要自己补画高亮的原因。

**第一版修法（失败）：补画一次物品，压在高亮之上**

```java
private void repaintSlotItem(Draw g, Slot slot, int guiLeft, int guiTop) {
    ... g.pose().translate(0, 0, 1.0F);        // 抬 1px，压在高亮之上、低于手持物品(232)
    g.renderItem(stack, sx, sy);               // renderItemAndEffectIntoGUI
    g.renderItemDecorations(this.font, stack, sx, sy);   // 数量/耐久叠层跟着重画
}
```

**实测无效**：重测时白块照旧盖住物品（`debug.log` 已确认跑的是含修复的构建 ——
`压浮层 page=ROOT parent=ChestScreen` / `关闭面板 overlay=true` 都在）。
反直觉的是同一份重画代码在 `renderCellOverlay`（选格页）里看着是好的，
**两处的坐标系、z、渲染状态完全相同，原因未能定位**。

> 这是本项目"别和渲染管线对抗"的一个实例：凡是"先画 A 再补画 B"的写法，
> 一旦底层的深度/矩阵状态不明，就会变成靠运气。

**最终修法：高亮不侵入格子内部**

物品铺满整个 16×16 格子，**任何**格内填充都必然盖住它。所以悬停高亮改成只画格子**外侧**：

```java
ring(g, sx - 2, sy - 2, 20, 20, 0x55FFFFFF);   // 外圈半透明光晕
ring(g, sx - 1, sy - 1, 18, 18, 0xFFFFFFFF);   // 贴身 1px 实线
```

vanilla 的槽位间距是 2px，两层框正好落在间隙里，**格内一个像素都不碰**，物品永远不被遮。
视觉上仍是"这一格被点亮了"。

| 位置 | 处理 |
|---|---|
| `renderBackgroundHover`（普通悬停） | **双层外框，不填充**（本次修的就是这里） |
| `renderCellOverlay` 已选格 | 绿底 `0x4822CC88` + 绿框 + 重画物品（实机认可，保留） |
| `renderCellOverlay` 不可选格 | 压暗 `0x38000000` + 重画物品（底色暗、物品清晰） |

**为什么不用"接管背景层渲染"这种彻底方案**：那要放弃 `pushGuiLayer`、自己保存父屏并手动
`parent.render(pose, 真实鼠标, ...)`，等于重写输入转发 / 缩放 / 关闭语义。
那是唯一能真正复现 vanilla 顺序（高亮在物品下）的路，但代价太大，暂不做。

**已知残留**：
- `hover` 态的 1px 白框（`ring` 画在 `sx-1 .. sx+17`）会压住格子最外 1px 的物品像素；
  vanilla 的选中框同样如此，不处理。
- `selected` / 不可选格仍然靠"补画物品"来压住底色；实机看着正常，但按上面那条实测，
  **它可能只是被 28% / 22% 的低不透明度掩盖了问题**。若将来把这两个填充调深，
  会重新出现"物品被蒙住"，届时同样改成外框。

---

## 六之三、实机第三轮：装进 HMCL 实例启动即崩（2026-10-20，已修）

**现象**：把 `build/libs/logiscope-0.1.2-1.16.5-alpha.jar` 放进 HMCL 的 `1.16.5-Forge` 实例
（mods 里还有 Mekanism / AE2 / Create / Flywheel / JEI），启动直接崩在 mod 加载阶段：

```
Description: Mod loading error has occurred
Caused by 0: java.lang.NoClassDefFoundError: org/slf4j/LoggerFactory
    at com.jessetech.logiscope.LogiScopeMod.<clinit>(LogiScopeMod.java:40)
Failure message: LogiScope has class loading errors
```

**根因：`org.slf4j` 在 1.16.5 生产运行时不可见。**

- 编译期它能过，是因为 FG5 的 `configurations.minecraft` 里确实有 `slf4j-api-1.8.0-beta4.jar`
  （为 `runClient` 准备的）；
- 生产实例的类加载器只暴露 Forge 的 libraries 集合，里面只有 `log4j-slf4j18-impl`（绑定层），
  **没有 slf4j-api**；
- 结果就是"开发客户端跑得好好的、装进实例立刻崩"，而且崩在 `<clinit>`——
  `LOGGER` 是 static final 字段，类初始化时就炸，连一句日志都打不出来。

1.18.2 / 1.20.1 线有 slf4j-api，所以同一个 import 在那两条线是好的 —— 这是移植时
"看起来一样的代码，换一代就换了个可用性"的又一例。

**修法**：改用 1.16.5 自带的 log4j2（MC 与 Forge 自己都用它）。

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
public static final Logger LOGGER = LogManager.getLogger("LogiScope");
```

调用处一行没改：`warn(String, Object...)` / `debug(String, Throwable)` 在 log4j2 上签名一致。

### 补了一道离线门禁（`dev-check/check-runtime-refs.py`）

这类问题 javac 抓不到（编译期有）、`verify-jar.py` 也抓不到（只看元数据）。新脚本做两件事：

1. **正向**：解析 jar 里每个 class 常量池的 `CONSTANT_Class`，逐个到 `build/mcdeps` 里找 owner；
2. **反向黑名单**：命中 `FORBIDDEN_PREFIXES` 直接报错。

**为什么要反向**：`build/mcdeps` 是 dev classpath 的副本，**天然比生产多东西** ——
`org/slf4j/LoggerFactory` 在 mcdeps 里就有，正向检查永远抓不到这次这个 bug。
所以黑名单那条才是主力，正向负责兜"拼错类名""引用了不存在的类"。

已接入 `offline-check.bat` 第 5 步，并且**验证过它有效**：把 slf4j 临时改回去重编，
脚本准确报出 2 个 `FORBIDDEN` 且退出码 1。

> 当前黑名单只有 `org/slf4j/`。将来再遇到同类问题（某包 dev 有、实机没有），
> 追加到 `FORBIDDEN_PREFIXES` 并**写明实测依据**，别凭印象加。

---

---

## 六之四、AE2 支持：8.4.7 与 11/15/19 是**三套** API（2026-10-21）

前三条版本线的 AE 实现对这条线**一行都用不上** —— AE2 8.4.7（1.16.5 最后一代）没有
`AEKey` / `GenericStack` / `MEStorage` / `KeyCounter` / `GridHelper`，那是 AE2 10+ 才有的。
本分支最初带过来的 `AeNetworkReader` 是照抄 19.x 的，**一条签名都解析不了** ⇒
AE 目标恒 `no_data`。本轮按 8.x 重写，并顺带修掉一个"F9 在终端里没反应"的键盘问题。

### 现象与根因

| 现象 | 根因 |
|---|---|
| 打开 AE 终端按 F9，面板不出现（其它容器界面正常） | **AE2 8.x 终端一打开就把焦点交给搜索框**：`MEMonitorableScreen#init` 里 `isAutoFocus = terminalSearchMode ∈ {AUTOSEARCH, AUTOSEARCH_KEEP, JEI_AUTOSEARCH*}`，出厂配置就是 `AUTOSEARCH`（实例里 `config/appliedenergistics2-client.toml` 实测值），紧接着 `setListener(searchField)`。而 `TextInputGuard` 第 1 层把"有文本框聚焦"一律当成"玩家在打字" ⇒ 快捷键被吞 |
| AE 目标恒 `no_data`；即便进了选格页也列不出一条网络条目 | ① 菜单判据只认 `appeng.menu.me.common.MEStorageMenu`（19.x 的名字），8.x 的基类是 `appeng.container.me.common.MEMonitorableContainer` ⇒ AE 模式根本不触发；② 读值链整条不存在于 8.x |

### 三代读值链对照（别照抄）

| 代 | 链 |
|---|---|
| 19.x（1.21.1） | `GridHelper.getNodeHost(level,pos)` → 6 面 `getGridNode(Direction)` → `getStorageService().getInventory().getAvailableStacks()`（KeyCounter）→ `AEItemKey.of` |
| 15.x / 11.x | 同上；`getNodeHost` 的历史签名不同（11.x 收 `LevelAccessor`），键序列化不带注册表 |
| **8.x（本线）** | `Ter instanceof IGridHost` → `getGridNode(AEPartLocation)`（INTERNAL 优先 + 6 面）→ `isActive()` → `IGrid.getCache(IStorageGrid.class)` → `IStorageMonitorable.getInventory(通道)` → `IMEInventory.getAvailableItems(通道.createList())` → 遍历比 registry id → `IAEStack#getStackSize()` |

### 8.x 专属的四个坑

1. **`appeng.core.Api` 不能单参 `Class.forName`**：它的 `<clinit>` 会 new 一整套注册表容器并往
   Forge EventBus 挂监听。解析反射链时把它初始化掉，离线探针当场炸在
   `LocatableEventAnnounce.<init>()`（实机因为 AE 早就初始化过，看不出问题）。
   一律用 `Class.forName(name, false, loader)`。
2. **流体是"占位物品"**：流体终端把流体渲染成同一个 `ae2:fluid_dummy`，
   读物品 id 会让水 / 岩浆 / 原油塌成一个身份（与 19.x 的 `wrapped_generic_stack` 同类陷阱）。
   身份必须走 `RepoSlot#getEntry()` → `GridInventoryEntry#getStack()` →
   `IAEFluidStack#getFluidStack()`，显示名用 `FluidStack#getDisplayName()`（"水"）。
3. **终端格不是菜单的"内容"但确实在菜单里**：8.x 的 `MEMonitorableScreen#init` 会往
   `inventorySlots` 里塞 `RepoSlot`，所以选格页仍可读菜单；但 `Repo.get(offset)` 会**加上滚动偏移**，
   列表 = "终端当前这一屏"，这与"按显示的来"那条纪律一致，不是 bug。
4. **`getEntry()` 在 `repo.hasPower()==false` 时恒 null**：网络没电时终端本来就什么都不显示，
   于是选格页全空。这是真值，不要"兜底"去读 `ItemStack`。

### 验证手法（不启动游戏）

`dev-check/ProbeAeApi.java`：用反射把**真实的**私有 `AeNetworkReader.chain()` 与
`AeKeys.init()` 调起来（不是复制一份签名表 ⇒ 探针通过等价于"实机那条链能建立"），
再逐个 `getMethod` 验 `RepoSlot#getStoredAmount/getEntry`、`GridInventoryEntry#getStack`，
最后确认物品 / 流体终端都继承 `MEMonitorableContainer`。输出 `AE_API_OK`。

```bat
:: 一次性跑（classpath 需要实例里的 AE2 jar + build\mcdeps）
build\probe-ae.bat   :: 本地临时脚本，不进仓库
```

---

## 七、已知限制 / 待实机验证

1. **实机已过**：F9 呼出 / 关闭两条路径（§六 三处缺陷修完复测通过）、UI 新功能
   （选格页筛选框 / 忽略空槽 / 回放页打开存档文件夹，见 PROGRESS「[1.16.5] 轮次 1」）。
   **本轮 AE 部分待验**（§六之四，清单见 `docs/VERIFY.md` J 组），其余重点：
   - F9 在容器 GUI 内呼出面板（`GuiScreenEvent` 路由与 1.18.2 不同代，键位事件优先级要实测）；
   - 选格高亮（1.16.5 的 `renderSlotHighlight` 签名与 1.18.2 不同）；
   - 物品数量叠层（`renderItemOverlayIntoGUI` 的 `FontRenderer` 参数）；
   - 右键方块探测（`func_217292_a` 直调路径）。
2. **`IClientItemExtensions` 不存在**（1.18.2 也没有）。物品数量叠层只能用默认字体，  
   与 1.18.2 线同一处理。
3. **`BlockState#getMenuProvider` 不存在**（1.17+ 才有）。容器检测只能走  
   `TileEntity instanceof IInventory` / `ITEM_HANDLER_CAPABILITY` 这两条路，  
   即"结构判据 + 能力判据"，与 1.20.1 线的探测口径一致。
4. **Java 8 语法上限**。后续从 `main`（1.21.1）cherry-pick 的改动，凡用到  
   record / 箭头 switch / 模式匹配 / `var` 的，都要手工降级 —— 编译器会逐条拦住，  
   但工作量不可忽略。
5. **构建需要联网一次**。FG5 首次要下载 MC client、mappings 与 MCP 配置  
   （`downloadMcpConfig` / `extractSrg`），之后整个工具链在 `~/.gradle/caches/forge_gradle/` 落地，  
   日常 `--offline` 可用。

---

## 八、与其它版本线的关系

| 分支 | MC / 加载器 | 构建栈 | Java | jar 名 |
| -- | -------- | --- | ---- | ----- |


| `main` | 1.21.1 / NeoForge 21.1.x | MDG `net.neoforged.moddev` | 21 | `logiscope-0.1.3-1.21.1.jar` |
| `1.20.1-neo` | 1.20.1 / NeoForge 47.1.x（同一 jar 也跑 Forge 47.x） | MDG legacy | 17 | `logiscope-0.1.3-1.20.1.jar` |
| `1.18.2-forge` | 1.18.2 / Forge 40.x | MDG legacy | 17 | `logiscope-0.1.3-1.18.2.jar` |
| **`1.16.5-forge`** | **1.16.5 / Forge 36.x** | **ForgeGradle 5** | **8** | **`logiscope-0.1.3-1.16.5-alpha.jar`** |

三条低版本线（1.18.2 / 1.16.5 / 1.12.2）都**只有 Forge**，不需要加载器兼容设计；
`1.12.2` 那代还有 Cleanroom 这个"第二运行时"，但它与 Forge 同包名同 SRG，属另一类问题
（见 `C:\Users\JesseTech\WorkBuddy\Minecraft mod\docs\port-feasibility-legacy.md`）。
