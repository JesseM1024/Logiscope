# LogiScope · 1.18.2 Forge 移植记录

分支：`1.18.2-forge`（从 `1.20.1-neo` 拉出）
基线：Forge **1.18.2-40.3.12**（= 用户 HMCL 测试实例版本）
结论：**编译通过、打包通过、离线门禁 461 条断言全过**；实机待验。

---

## 一、前提：这一代没有 NeoForge

NeoForge 的第一个版本是 MC 1.20.1，且 1.20.1 的 NeoForge 仍沿用 `net.minecraftforge` 包名
（改包名是 20.2 / MC 1.20.2 才做的）。所以：

- 1.18.2 只有 Forge 一家，**不存在"双加载器"问题**，不需要为兼容性做任何设计；
- 1.20.1 那条"一份 jar 通吃 NeoForge/Forge"的经验在这里**用不上也不需要**；
- 反过来，**1.18.2 的 API 是纯 Forge 的**，可以放心用 `ForgeHooksClient` 等平台能力。

---

## 二、加载器适配版本区间（本次重点）

`src/main/templates/META-INF/mods.toml` 经 `gradle.properties` 展开后的实际值：

| 字段 | 值 | 理由 |
|---|---|---|
| `loaderVersion` | `[40,)` | 1.18.2 世代 `javafml` 大版本是 40。写 `[40,)` 表示"任何 1.18.2 时代的 FML 都接受" |
| `dependencies.forge.versionRange` | **`[40.2,)`** | **下界取 40.2（1.18.2 线的起点），不取编译用的 40.3.12** —— 否则 40.2.x 的老整合包会被 FML 直接拒载，"本可用却用不了" |
| `dependencies.minecraft.versionRange` | `[1.18.2]` | 只认 1.18.2，跨小版本不做承诺 |

**为什么不写 `[40.3.12,)`**：编译基线只是为了拿到"最新 API + 最完整签名"，
而运行期能用到的最低版本由**实际调用的 API** 决定。本次用到的都是 1.18 世代早已存在的
长稳 API（`ForgeConfigSpec`、`ScreenEvent`、`TickEvent`、`CapabilityItemHandler`、
`ForgeHooksClient.pushGuiLayer`、`ClientRegistry`），所以下界可以安全地压到 40.2。

**为什么不再往下压（40.0/40.1）**：那两版对应 MC 1.18 / 1.18.1，`minecraft_version_range`
本来就会拒掉，压下去没有意义。

---

## 三、构建层改动

| 文件 | 改动 |
|---|---|
| `build.gradle` | `legacyForge { enable { forgeVersion = project.forge_version } }`（1.20.1 线用的是 `neoForgeVersion`）；run 目录改为 `1_18_2_forge/`；占位符 `neo_version*` → `forge_version*` |
| `gradle.properties` | `minecraft_version=1.18.2`；`forge_version=1.18.2-40.3.12`；`forge_version_range=[40.2,)`；`loader_version_range=[40,)`；JDK 17；`mod_version=0.1.0-1.18.2` |
| `.gitignore` | 加 `1_18_2_forge/` |
| `dev-check/offline-check.bat` | 合并 jar 指到 `forge-1.18.2-40.3.12-merged.jar`；jar 名指到 `logiscope-0.1.0-1.18.2.jar` |

工具链仍是 MDG `net.neoforged.moddev.legacyforge`（官方支持 Forge 1.17–1.20.1），
**1.18.2 属于它的支持范围**，所以构建栈与 1.20.1 线同构，只换版本号。

---

## 四、代码层：先立一层 `Draw`，再改 6 处 API

### 4.1 `Draw`（新增 `ui/Draw.java`）

1.20 起 MC 把「PoseStack + 一组绘制方法」收成了 `GuiGraphics`，1.18.2 没有这个类。
但本项目只用到 **6 个绘制原语**（`fill` 83 处 / `drawString` 79 处 / `pose` 3 处 /
`renderItem` / `renderItemDecorations` / `renderTooltip` 各 1 处），
于是新增 `Draw` 作为**方法签名与 `GuiGraphics` 一致的替身**：

```java
Draw g = new Draw(pose);   // ScopeScreen#render(PoseStack, int, int, float) 里合成
```

效果：`ChartRenderer` 与 `ScopeScreen` 里 160 多处绘制调用**一行没改**，只换了参数类型。
以后若再移植 1.19/1.20 之外的版本，这一层就是唯一的渲染差异点。

### 4.2 逐项差异（均以 `forge-1.18.2-40.3.12-sources.jar` 核实，不靠记忆）

| # | 1.20.1 写法 | 1.18.2 实际 | 处理 |
|---|---|---|---|
| 1 | `GuiGraphics` | 不存在 | 新增 `Draw` 适配层 |
| 2 | `net.minecraft.client.gui.components.GuiComponent` | `net.minecraft.client.gui.GuiComponent` | 换包 |
| 3 | `ScreenEvent.KeyPressed.Pre` | `ScreenEvent.KeyboardKeyPressedEvent.Pre` | 换事件名 |
| 4 | `ScreenEvent.Init.Post` | `ScreenEvent.InitScreenEvent.Post` | 换事件名 |
| 5 | `ScreenEvent.CharacterTyped.Pre/Post` | `ScreenEvent.KeyboardCharTypedEvent.Pre/Post` | 换事件名（`getCodePoint()` 同名） |
| 6 | `RegisterKeyMappingsEvent` | **不存在**（1.19 才引入） | 改 `FMLClientSetupEvent` + `ClientRegistry.registerKeyBinding` |
| 7 | `IClientItemExtensions.of(stack).getFont(...)` | **不存在**（1.19 才引入） | 去掉，数量字体统一用 `this.font`（已知限制） |
| 8 | `BuiltInRegistries.ITEM/BLOCK/BLOCK_ENTITY_TYPE` | 1.19+ 才有 | `ForgeRegistries.ITEMS/BLOCKS/BLOCK_ENTITIES` |
| 9 | `ForgeCapabilities.ITEM_HANDLER` | 1.19+ 才有 | `net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY` |
| 10 | `PlayerInteractEvent#getLevel()` | `#getWorld()` | 换方法名 |
| 11 | `ClientPlayerNetworkEvent.LoggingIn` | `ClientPlayerNetworkEvent.LoggedInEvent` | 换内部类名 |
| 12 | `ClientChatReceivedEvent#isSystem()` | 无此方法 | 改判 `event.getType() == ChatType.SYSTEM` |
| 13 | `ClientPacketListener#sendCommand(String)` | **不存在** | 改 `player.chat("/data get block ...")`（1.18.2 聊天包未签名，安全） |
| 14 | `MultiPlayerGameMode.useItemOn(player, hand, hit)` | 多一个 `ClientLevel` 参数 | `useItemOn(player, (ClientLevel) level, hand, hit)` |
| 15 | `Slot#isHighlightable()` | 不存在 | 去掉该判定，只留 `isActive()` |
| 16 | `AbstractContainerScreen.renderSlotHighlight(GuiGraphics,...)` | 收 `PoseStack` | 传 `g.pose()` |
| 17 | `Screen#render(GuiGraphics,...)` | `render(PoseStack, int, int, float)` | 签名 + 首行合成 `Draw` |

> 编译过程：首轮 100 条错误 → 修完上述 17 项后 **0 错误**。

---

## 五、验证

| 项 | 结果 |
|---|---|
| `gradlew.bat compileJava` | ✅ 0 错误 |
| `gradlew.bat build`（含 `reobfJar` SRG 重映射） | ✅ 产出 `build/libs/logiscope-0.1.0-1.18.2.jar`（238 930 B） |
| 元数据 | ✅ `loaderVersion="[40,)"`、`forge [40.2,)`、`minecraft [1.18.2]` |
| `dev-check/offline-check.bat` | ✅ 4 步全过：461 条断言 0 失败 / BenchRender / verify-jar（零残留 + 元数据） |
| 实机 | ⏳ 待用户在 HMCL `1.18.2 + Forge 40.3.12` 实例验证 |

---

## 六、已知限制（1.18.2 线）

1. **物品数量字体**：1.18.2 没有 `IClientItemExtensions`，自定义数量字体的模组（罕见）
   会显示成默认字形。
2. **快捷键拦截走 `KeyboardKeyPressedEvent`**：与 1.20.1 的 `KeyPressed` 语义等价
   （Pre 可取消、Post 可观察），但事件名不同，`TextInputGuard` 的三层守卫逻辑未变。
3. **`/data get block` 回执判定**：1.18.2 只有 `ChatType.SYSTEM` 这一种系统类型
   （1.20.4+ 才拆出更多），判定条件比 1.20.1 线更粗。
4. **`player.chat("/...")`** 在 1.18.2 是未签名聊天包；1.19+ 需要签名链路，届时不能照搬。

---

## 七、本机构建环境备注（一次性，不入库）

本机 `libraries.minecraft.net` TLS 握手失败、`maven.neoforged.net` 网络不可达，
而 1.18.2 的部分构件（`mcp_config`、`mergetool`、`minecraft-dependencies:1.18.2`、
`neoform-runtime`）只在这些域名上。本次通过临时手段完成构建：

- 本地 HTTP 镜像（阿里云 public → BMCLAPI → 本机 `.minecraft/libraries` 兜底，缺 pom 时补最小 pom）；
- 用 Gradle 缓存里已有的 `net.neoforged:*` 构件铺成本地 file 仓库；
- 按 1.20.1 的模块结构 + Mojang 官方 1.18.2 version json 生成了一份
  `minecraft-dependencies:1.18.2` 的 module 元数据（仅构建期 classpath，不影响产物）。

这些都在 `%TEMP%` 下，**没有进仓库**。网络恢复后（或代理打开时）删掉 `--init-script` 直接构建即可。

---

## 八、下一步

1. `HMCL 1.18.2 + Forge 40.3.12` 实机：开容器界面按 F9 → 浮层是否正常压在原界面之上；
2. AE2（1.18.2 = AE2 11.x）的 `MEStorage` 反射链路是否仍通（代码未改，理论上同构）；
3. 精妙存储 / MEK 在 1.18.2 的槽位结构是否仍满足 `SlotAlign` 的对齐前提；
4. 确认 `player.chat("/data get block")` 的多人回执路径（需要 OP）。
