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

| 字段 | 值 | 理由 |
|---|---|---|
| `loaderVersion` | `[36,)` | 1.16.5 世代 `javafml` 的大版本是 36。写 `[36,)` = "任何 36.x 的 FML 都接受" |
| `dependencies.forge.versionRange` | **`[36,)`** | **下界取 36（1.16.5 线的起点），不取编译用的 36.2.42** —— 否则 36.0.x / 36.1.x 的老整合包会被 FML 直接拒载，"本可用却用不了" |
| `dependencies.minecraft.versionRange` | `[1.16.5]` | 只认 1.16.5，跨小版本不做承诺 |
| 依赖 `side` | `CLIENT` | 纯客户端模组；服务端不装也不报错 |

**为什么不写 `[36.2.42,)`**：编译基线只是为了拿到"最新 API + 最完整签名"，
运行期能用到的最低版本由**实际调用的 API** 决定。本次用到的都是 1.16.x 世代早已存在的
长稳 API（`ForgeConfigSpec`、`TickEvent.ClientTickEvent`、`GuiScreenEvent`、
`CapabilityItemHandler`、`ForgeHooksClient.pushGuiLayer`、`ClientRegistry`），
所以下界可以安全地压到 36。

**为什么不再往下压（35.x）**：35.x 对应 MC 1.16.4，`minecraft_version_range=[1.16.5]` 本来就会拒掉。

---

## 三、构建层改动

| 文件 | 改动 |
|---|---|
| `build.gradle` | 换成 **ForgeGradle 5.1**（`apply plugin: 'net.minecraftforge.gradle'`）；`mappings channel: 'snapshot', version: '20210309-1.16.5'`；run 目录 `1_16_5_forge/`；`java.toolchain = 8`；新增 `copyMinecraftDeps` 任务（见下） |
| `gradle.properties` | `forge_version=1.16.5-36.2.42`（**FG5 要求带 MC 前缀**）；`forge_version_range=[36,)`；`loader_version_range=[36,)`；`org.gradle.java.home` 指向 JDK 17（守护进程用），编译目标另由 toolchain 锁到 8 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle **9.2.1 → 7.6.4**（FG5 不支持 Gradle 8+） |
| `settings.gradle` | 去掉 `org.gradle.toolchains.foojay-resolver-convention`（Gradle 7.6 不认这个插件） |
| `.gitignore` | 加 `1_16_5_forge/` |
| `dev-check/offline-check.bat` | classpath 换成 `dev-check\lib\*;build\mcdeps\*`；jar 名 `logiscope-0.1.2-1.16.5-alpha.jar` |
| `dev-check/TestChart.java` | `IntTag/ListTag/StringTag` → `IntNBT/ListNBT/StringNBT` |

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

| 1.18.2 写法 | Java 8 处理 | 规模 |
|---|---|---|
| `record X(...)` | 改成 `final class` + 构造器 + getter + 手写 `equals/hashCode/toString` | **39 个**（36 个脚本转换 + 3 个手工：`ArchiveFormat.Header`、`ChartData.SeriesRange`、`ProbeTarget.SlotRef`） |
| 箭头 `switch`（语句式 / 表达式式） | 经典 `case X:` + `break`；表达式式要先声明变量再赋值 | **约 145 个 case 区域**（`ScopeScreen` 115 个） |
| `x instanceof Foo f` | `x instanceof Foo` + 显式强转 | 多处 |
| `var x = ...` | 显式类型 | 多处 |
| `str.isBlank()` | `str.trim().isEmpty()` | 1 处（`DataProbe`） |
| `stream.toList()` | 项目自带 `List#toList()`（**不是** `Stream#toList`，别误改） | 5 处 |
| `List.of / Set.of / Map.of` | `Arrays.asList` / `Collections.emptyXxx` | 多处 |
| `Files.writeString / readString` | `Files.write` / `Files.readAllBytes` + 显式 `StandardCharsets.UTF_8` | `Archives` |
| `Optional.isEmpty()` | `!isPresent()` | 多处 |
| 被静态上下文使用的嵌套类 | 显式 `static` | 若干 |

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

| 主题 | 1.18.2 / 1.20.1 | 1.16.5 | 用量 |
|---|---|---|---|
| 变换矩阵 | `PoseStack` | `MatrixStack` | 8 |
| 字体 | `Font`；`font.width(s)` | `FontRenderer`；`font.getStringWidth(s)` | 17 / 45 |
| 静态绘制 | `GuiGraphics` | `AbstractGui.fill(MatrixStack, …)` | 5 |
| 容器界面基类 | `AbstractContainerScreen` | `ContainerScreen` | 25 |
| 世界 | `Level` / `ServerLevel` | `World` / `ServerWorld` | 55 / 25 |
| 方块实体 | `BlockEntity`；`level.getBlockEntity` | `TileEntity`；`world.getTileEntity` | 19 / 10 |
| 容器（物品栏接口） | `net.minecraft.world.Container` | `IInventory` | 26 |
| 菜单 / 槽位列表 | `AbstractContainerMenu`；`menu.slots` | `Container`；`menu.inventorySlots` | 2 |
| 槽位下标 | `Slot#getContainerSlot()` | **`Slot#getSlotIndex()`** | 4 |
| NBT 类型 | `CompoundTag` / `ListTag` / `Tag` | `CompoundNBT` / `ListNBT` / `INBT`（另有 `IntNBT` `StringNBT` `FloatNBT` `DoubleNBT` `NumberNBT`） | 14 / 4 / 8 |
| 文本组件 | `Component`；`Component.literal` | `ITextComponent`；`StringTextComponent` | 5 |
| 注册表 | `ForgeRegistries.BLOCK_ENTITY_TYPES` | `ForgeRegistries.TILE_ENTITIES` | 1 |
| 方块名 | `block.getName()` | **`block.getTranslatedName()`** | 1 |
| 界面事件 | `ScreenEvent.InitScreenEvent / KeyboardKeyPressedEvent / KeyboardCharTypedEvent` | `GuiScreenEvent.InitGuiEvent / KeyboardKeyPressedEvent / KeyboardCharTypedEvent` | 16 |
| 键位注册 | `RegisterKeyMappingsEvent`（1.19+） | `FMLClientSetupEvent` + `ClientRegistry.registerKeyBinding` | 3 |
| 物品能力 | `ForgeCapabilities.ITEM_HANDLER` | `CapabilityItemHandler.ITEM_HANDLER_CAPABILITY` | 6 |
| 右键交互 | `player.getInventory()` | `player.inventory` | 43 |
| 打开的菜单 | `player.containerMenu` | `player.openContainer` | 2 |
| 聊天回执 | `player.sendCommand(...)` | `player.sendChatMessage(...)` | 1 |
| 右键发方块 | `useItemOn(...)` | **`playerController.func_217292_a(player, (ClientWorld) world, hand, hit)`** | 3 |

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

| 检查项 | 结果 |
|---|---|
| `compileJava`（javac 8 目标） | 0 错误 |
| 产物 | `build/libs/logiscope-0.1.2-1.16.5-alpha.jar`，244,350 字节，84 个类 |
| 字节码版本 | major **52**（= Java 8），全部一致 |
| reobf 是否生效 | jar 内 **83 处 SRG 名**（`func_*` / `f_*`）→ FG5 的 `reobfJar` 确实跑了 |
| refmap | 无（1.16.5 无 mixin，不需要） |
| 资源 | `assets/logiscope/lang/{en_us,zh_cn}.json`、`pack.mcmeta`、`META-INF/mods.toml` 齐全 |
| 离线断言 | TestChart **461 条，0 失败** |
| 渲染基准 | BenchRender 通过 |
| jar 验收 | `verify-jar.py` 三项全过（三类文件齐全 / 占位符已替换 / 元数据符合预期） |
| SRG 名交叉核对 | jar 内 83 个 `func_*` / `f_*` **全部能在 `forge-1.16.5-36.2.42-srg.jar` 里找到**（0 缺失）→ 没有拼错的重混淆名 |
| 类引用核对 | 引用的 99 个 `net.minecraft*` 类里 96 个在 srg jar 内；缺的 3 个是 `Dist` / `IEventBus` / `FMLEnvironment` —— 它们住在 Forge 启动器的库 jar（forgespi / eventbus / fmlcore）里，运行时由 Forge 提供，**不是缺陷** |

`mods.toml` 展开后的关键字段（实测从 jar 里读回）：

```
loaderVersion="[36,)"
modId="logiscope"  version="0.1.2-1.16.5-alpha"
dependencies: forge [36,) side=CLIENT ; minecraft [1.16.5] side=CLIENT
```

---

## 六、已知限制 / 待实机验证

1. **实机未验证**。`runClient` 需要图形界面，本机只跑到"编译 + 打包 + 离线断言"。
   待验清单见 `docs/VERIFY.md`，重点：
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

## 七、与其它版本线的关系

| 分支 | MC / 加载器 | 构建栈 | Java | jar 名 |
|---|---|---|---|---|
| `main` | 1.21.1 / NeoForge 21.1.x | MDG `net.neoforged.moddev` | 21 | `logiscope-0.1.2-1.21.1.jar` |
| `1.20.1-neo` | 1.20.1 / NeoForge 47.1.x（同一 jar 也跑 Forge 47.x） | MDG legacy | 17 | `logiscope-0.1.2-1.20.1-alpha.jar` |
| `1.18.2-forge` | 1.18.2 / Forge 40.x | MDG legacy | 17 | `logiscope-0.1.2-1.18.2-alpha.jar` |
| **`1.16.5-forge`** | **1.16.5 / Forge 36.x** | **ForgeGradle 5** | **8** | **`logiscope-0.1.2-1.16.5-alpha.jar`** |

三条低版本线（1.18.2 / 1.16.5 / 1.12.2）都**只有 Forge**，不需要加载器兼容设计；
`1.12.2` 那代还有 Cleanroom 这个"第二运行时"，但它与 Forge 同包名同 SRG，属另一类问题
（见 `C:\Users\JesseTech\WorkBuddy\Minecraft mod\docs\port-feasibility-legacy.md`）。
