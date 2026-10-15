# LogiScope · 1.20.1 NeoForge 移植方案

> 状态：**配置层 + API 移植已完成**（compileJava 0 错误、reobf jar 已产出，2026-09-20）；
> **实机验收待做**。本文件在两分支各自维护，结论以 `1.20.1-neo` 分支上的版本为准。
>
> 注意：**两条分支的 git 历史互不相关**（main 曾 rebase 重写，2026-10-14 核查）——
> 两分支之间用 **cherry-pick** 搬运改动，不要用 merge。
>
> 2026-10-15：main 的**第十七轮①、第十八轮①–④**已 cherry-pick 到本分支（离线验证全过，
> 见 `PROGRESS.md` 的「[1.20.1] 轮次」节）；1.20.1 线的**首次实机验收清单**见本分支的
> `VERIFY.md`（已按本分支的命令 / 产物名 / 运行目录重建）。
>
> 原则：**本文只记 1.20.1 的差异与 1.20.1 上重新核实的结论**。
> 1.21.1 的事实记录留在 [`ARCHITECTURE.md`](ARCHITECTURE.md)，不要两边改写。
> 所有"待核实"项落地时用 1.20.1 的 sources jar 逐行查证，不凭记忆。

---

## 一、目标与前提

| 项 | 值 |
|---|---|
| 目标 MC | 1.20.1 |
| 目标 NeoForge | `1.20.1-47.1.106`（1.20.1 线最后一个版本；**待核实**：以 maven 实际产物为准） |
| Java | **17**（`org.gradle.java.home` 与 toolchain 都要改） |
| 模组版本 | `0.1.0-1.20.1`（版本号带 MC 后缀的约定见 `gradle.properties` 注释） |
| 分支 | `1.20.1-neo`（从 master 的"版本号 0.1.0-1.21.1"提交切出） |

**前提（待用户确认）**：需要一个 1.20.1 的测试实例。现有两个实例
（`1.21.1-NeoForge`、`NAST hard d0.9.5`）都是 1.21.1，可能需新装 `1.20.1-NeoForge`。
编译基线 == 目标实例的 NeoForge 版本（老规矩）。

---

## 二、构建层改动（比版本号更大的那块石头）

1. **插件**：`net.neoforged.moddev` → **`net.neoforged.moddev.legacyforge`**（2.0.147）。
   MDG 2 官方支持 Forge 1.17–1.20.1 与 NeoForge 1.20.1，同一家的 legacy 插件，
   `runClient` / `createLaunchScripts` 工作流保留。
2. **配置块**：`neoForge { version = ... }` → `legacyForge { neoForgeVersion = '1.20.1-47.1.106' }`。
3. **reobf**：1.20.1 生产环境是 SRG 映射，**发布 jar 必须 reobfuscated**
   （legacy 插件自带 remapJar；开发环境照常用官方映射）。`verify-jar.py` 的检查口径按 remapJar 产物理解。
4. toolchain `JavaLanguageVersion.of(21)` → `of(17)`。
5. parchment 属性：1.20.1 对应 `2023.09.03`（若启用）。
6. Gradle wrapper 9.2.1 对 legacy 插件是否兼容——**已核实：兼容**（`generateModMetadata`
   在 Gradle 9.2.1 + 插件 2.0.147 下 BUILD SUCCESSFUL）。
7. ⚠️ 现有 `filteringCharset = 'UTF-8'` 的编码铁律原样保留（跨版本通用，参考 build.gradle 注释块）。
8. ★ **DSL 已用字节码核实（javap 插件 jar）**：2.0.147 里 `LegacyForgeExtension` 外层只有
   `version`（Forge 线）和 `mcpVersion`（原版线）；**NeoForge 1.20.1 要写
   `legacyForge { enable { neoForgeVersion = '...' } }`**（`neoForgeVersion` 在
   `LegacyForgeModdingSettings` 上，三选一互斥）。网上资料（DeepWiki）写的外层
   `neoForgeVersion` 会直接报 "unknown property"。
   预防性配置 `obfuscation { createRemappingConfiguration(configurations.localRuntime) }` 照官方 MDK 加了。

## 三、元数据层改动

| 项 | 1.21.1（master） | 1.20.1（本分支） |
|---|---|---|
| 元数据文件名 | `META-INF/neoforge.mods.toml` | **`META-INF/mods.toml`**（已改名，`git mv`） |
| 依赖字段 | `type="required"` | **`mandatory=true`**（`type` 写法是 20.2+ 才有） |
| 平台依赖 modId | `neoforge` | **`forge`**（1.20.1 世代平台 mod id 未改名；依赖范围用 `[47,)`） |
| `loader_version_range` | `[4,)` | `[47,)` |
| `neo_version_range` | `[21.1.209,)` | `[47,)`（对平台 mod 的范围；`neo_version=1.20.1-47.1.106` 只给构建插件用） |

模板文件 `src/main/templates/META-INF/neoforge.mods.toml` **已改名为
`src/main/templates/META-INF/mods.toml`**。
小坑：模板注释里不要写 `${}`（空表达式）——Groovy expand 会把它替换成 `null`。

## 四、代码层必撞的 API（按文件，全部"待核实→改→记结论"）

### ★ 第一撞（2026-09-20 compileJava 实测，200 条错误的根因）：包名家族反了

**1.20.1 世代的 NeoForge 没有改包名 —— 还是 Forge 的 `net.minecraftforge.*`**。
`net.neoforged.*` 是 1.20.4+ 才引入的。开包核实
（`build/moddev/artifacts/forge-1.20.1-47.1.106-merged.jar`，15910 条目）：
jar 内只有 `net/minecraft` + `net/minecraftforge`，**没有任何 `net/neoforged`**。
所以移植方向是 `net.neoforged.*` → `net.minecraftforge.*`（不是"包名不用改"）：

| 1.21.1（master） | 1.20.1（本分支，已开包核实） |
|---|---|
| `net.neoforged.fml.common.Mod` | `net.minecraftforge.fml.common.Mod`（FML 类不在 merged jar 里，走独立 FML 依赖） |
| `net.neoforged.bus.api.IEventBus` | `net.minecraftforge.eventbus.api.IEventBus` |
| `net.neoforged.api.distmarker.Dist` | `net.minecraftforge.api.distmarker.Dist` |
| `net.neoforged.fml.ModContainer` / `config.ModConfig` | `net.minecraftforge.fml.ModContainer` / `net.minecraftforge.fml.config.ModConfig` |
| `net.neoforged.neoforge.common.NeoForge`（事件总线） | `net.minecraftforge.common.MinecraftForge` |
| `net.neoforged.neoforge.common.ModConfigSpec` | `net.minecraftforge.common.ForgeConfigSpec` |
| `net.neoforged.neoforge.client.event.*` | `net.minecraftforge.client.event.*` |
| `net.neoforged.neoforge.event.entity.player.*` | `net.minecraftforge.event.entity.player.*` |
| `net.neoforged.neoforge.items.IItemHandler` | `net.minecraftforge.items.IItemHandler`（jar 内存在 ✓） |
| `net.neoforged.neoforge.capabilities.Capabilities` | `net.minecraftforge.common.capabilities.ForgeCapabilities` |
| `net.neoforged.neoforge.client.ClientHooks` | `net.minecraftforge.client.ForgeHooksClient`（签名待核） |
| `net.neoforged.neoforge.client.gui.ConfigurationScreen` | **不存在**（1.20.1 Forge 无自动配置界面）；`IConfigScreenFactory` 也不在 jar 里，1.20.1 的对应物是 `net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory`（待核） |

jar 内直接核实存在的（低风险）：`TickEvent$ClientTickEvent/ServerTickEvent/Phase`、
`ScreenEvent`、`ClientPlayerNetworkEvent`、`PlayerInteractEvent`、
`capabilities/Capability`、`IClientItemExtensions`。

### 逐文件撞点（compileJava 200 条错误分布）

| 文件 | 错误数 |
|---|---|
| `LogiScopeMod` | 86 |
| `collect/WorldProbe` | 20 |
| `bind/KeyBindings` | 18 |
| `bind/ProbeContext` | 16 |
| `LogiScopeConfig` | 14 |
| `ui/ScopeScreen` | 12 |
| `collect/ProbeManager` | 8 |
| `bind/TextInputGuard` / `bind/DataCommand` | 各 6 |
| `collect/DataProbe` / `bind/ContainerAnchor` | 各 4 |
| `collect/ContainerAccess` / `ui/ChartRenderer` / `collect/AeNetworkReader` | 各 2 |

### 其余 API 差异（import 修完后的第二轮撞点，预期）

| 文件 | 1.21.1 现状 | 1.20.1 情况（预期） |
|---|---|---|
| `LogiScopeMod` / `collect/ProbeManager` | `event.tick.ServerTickEvent`（Pre/Post） | `TickEvent.ServerTickEvent` + `phase == Phase.END`。**CP 采样点挂权威事件的结构不变** |
| `LogiScopeMod` / `bind/KeyBindings` | `ClientTickEvent.Pre/Post` | `TickEvent.ClientTickEvent` + `phase == Phase.END` |
| `bind/ProbeContext` / `collect/WorldProbe` | `Capabilities` 注册表 | `ForgeCapabilities.ITEM_HANDLER` + `getCapability(cap, side)` |
| `bind/ContainerAnchor` | `useWithoutItem(...).consumesAction()` | 1.20.5 才拆出 `useWithoutItem`，1.20.1 是 `use(player, hand)`。**判据必须重新核实** |
| `ui/ScopeScreen` | `ClientHooks.guiLayers` | `ForgeHooksClient` 对应方法签名重核（历史教训：同名不同物） |
| `LogiScopeMod` | `@Mod(dist = Dist.CLIENT)` | Forge 1.20.1 的 `@Mod` **没有 dist 参数**；备选 `@Mod("logiscope")` + `EventBusSubscriber(value = Dist.CLIENT)` / 构造时判 Dist |
| `DataCommand` | `ClientChatReceivedEvent.System` | 1.20.1 该事件无 System 子类型（待核），用 `type == ChatType.SYSTEM` 判 |
| `bind/TextInputGuard` | `ScreenEvent.CharacterTyped.Pre` | 1.20.1 是 `ScreenEvent.CharacterTyped`（无 Pre/Post，待核） |
| `collect/AeNetworkReader` | AE2 1.21.1 版 grid/node API | AE2 1.20.1 对应 API 逐个核实 |
| `analysis/Snbt` / `archive/*` | DataComponent 系 | 1.20.1 是 `getTag()` NBT，grep 确认引用面 |

核实手段不变：legacy 插件同步后能拿到 1.20.1 的 sources jar
（`build/moddev/artifacts/forge-1.20.1-47.1.106-sources.jar`），
`dev-check` 离线断言的 classpath 换成这个 merged jar 路径。

## 五、文档口径（两个版本线并存时的规矩）

- `ARCHITECTURE.md`：**只在 1.21.1 侧维护**，头部已加声明；1.20.1 的差异只写进本文。
- `VERIFY.md`（当轮清单）：**每个分支各自维护**——分支上按 1.20.1 的命令与产物名重建当轮清单。
- `PROGRESS.md`：历史轮次记录不改写；1.20.1 的轮次记录用"### [1.20.1] 轮次 N"小节追加。
- 离线断言命令里 `neoforge-21.1.209-merged.jar` 的路径在 legacy 插件下**必然不同**，
  移植时同步更新 `VERIFY.md` 的 H 组命令。

## 六、IJ 操作步骤（建分支与日常切换）

1. `1.20.1-neo` 分支已建立并完成全部配置 / API 移植（勾选状态见末节）。
2. （参考）建分支：IJ 右下角 Git widget → master 右键 → New Branch，勾选 Checkout；
   等价命令 `git switch -c 1.20.1-neo`。
3. 在分支上改配置层（本文二、三节）→ 弹出 "Load Gradle Changes"（Ctrl+Shift+O）→ 同步；
   首次会下载 1.20.1 全套产物，较慢。
4. **JDK**：`gradle.properties` 的 `org.gradle.java.home` 改为 `C:/Program Files/Java/jdk-17`
   （随分支走）；File → Project Structure → Project SDK 选 17（只影响 IJ 编辑器解析）。
5. **切分支串味**（已解决一半）：`build/`、run 目录不受 git 管理——
   - 本分支 build.gradle 的 runs.configureEach 里已设
     `gameDirectory = project.file('1_20_1_neo')`（属性名 javap 核实：
     `RunModel.getGameDirectory()`），首次 `runClient` 会自动创建该目录；
     `saves` / `options.txt` / mods / logs 与 master 的 `run/` 互不覆盖。
   - `build/` 仍共用：**切回 1.21.1 前先 `gradlew.bat clean`，切完重新 sync**。
   - 两条铁律照旧：测试 jar 换版本线时**先删同名旧 jar**；正在开发的模组不进 run 目录的 mods。
   - 测试 jar 目标路径：1.20.1 线 = `1_20_1_neo/mods/`；master 线 = `run/mods/`。
6. 分支策略：`master` = 1.21.1 主线；通用 bugfix 进 master 后 cherry-pick 到 `1.20.1-neo`；
   只对单侧成立的修复不互挑（在提交消息里注明"仅 1.20.1"或"仅 1.21.1"）。

## 七、已知限制（1.20.1 线）

| 限制 | 原因 |
|---|---|
| 配置界面入口不存在 | NeoForge 的 ConfigurationScreen 是 20.4+ 才有；改 TOML（config/logiscope-client.toml） |
| 假槽无法识别 | Forge 1.20.1 的 Slot 没有 isFake()；isSelectable 只排除玩家背包 |
| `/tick rate` 改速时秒数按 20 换算 | 1.20.1 没有 tickRateManager / 速率同步包 |
| **模组必须带 pack.mcmeta**（pack_format 15） | 1.20.1 资源包机制；没有 = lang 不加载 = 界面全是键名（2026-09-20 实测踩中，日志证据 `Missing metadata in pack mod:logiscope`）。1.21 的 NeoForge 用 neoforge.mods.toml 充当打包元数据，master 不需要 |
| **dev 环境下 SRG-mixin 模组必炸**（如 Create 6 内嵌 flywheel 1.0.5） | 开发运行时是 named 映射，SRG 写的 `@Shadow` 定位不到（报 `@Shadow ... not located`）；**生产环境正常**。结论：Create 只放整合包实例测，dev 的 `1_20_1_neo/mods/` 里放 mods-disabled。 |
| 不要尝试"给 mod jar 本地做 SRG→named 重映射" | FART + tsrg2 方案未走通、已废弃（2026-10-14，产物已删）。本地混用映射来源的方案不值得再试；要测就按上一行分流。 |

## 八、测试环境（1_20_1_neo/mods/，2026-09-20 已就位）

| 模组 | 版本 | 来源 |
|---|---|---|
| AE2 | appliedenergistics2-forge-15.4.10 | Modrinth（Forge 版，NeoForge 1.20.1 兼容期可跑） |
| Mekanism | 1.20.1-10.4.16.80 | Modrinth（forge+neoforge 双标） |
| Create | 1.20.1-6.0.8 | Modrinth（Flywheel 已内置，无外部依赖） |

三者均按 Modrinth 元数据校过字节数与 zip 完整性。AE2 的 grid API 走反射（零编译期依赖），
装上即可测 AeNetworkReader。

## 九、待办与待定

- [ ] 测试实例：是否新装 `1.20.1-NeoForge` 实例（用户确认）
- [x] 建分支 `1.20.1-neo` 并切过去（用户在 IJ 中完成）
- [x] 配置层移植（二、三节）—— `generateModMetadata` 在 Gradle 9.2.1 + 插件 2.0.147 下通过，元数据占位符替换正确（2026-09-20）
- [x] 逐文件 API 移植 —— **compileJava 0 错误（2026-09-20）**，14 个文件全部改完：
  包名家族映射（见第四节表）、TickEvent Pre/Post → phase 判定、Capabilities →
  ForgeCapabilities + LazyOptional（走 BE#getCapability）、ModConfigSpec → ForgeConfigSpec、
  ClientChatReceivedEvent.System → isSystem()、useWithoutItem → use(+MAIN_HAND)、
  mouseScrolled 双参数 → 单参数、saveWithoutMetadata 去参、ResourceLocation.parse → new、
  @Mod(dist) → FMLEnvironment.dist 判定、ConfigurationScreen → 不注册（限制）。
  `gradlew.bat build` 通过，产出 reobf 后的 `build/libs/logiscope-0.1.0-1.20.1.jar`，
  元数据/reobf 已开包核验；verify-jar.py 已改为双版本线通用（两个 jar 都过）。
  ★ 注意：这只说明**编译层**通过；逻辑等价性要靠离线断言 + 实机验收。
- [ ] `VERIFY.md` 按 1.20.1 重建当轮清单（含 H 组离线断言新 classpath）
- [ ] 实机验收（等测试实例；Create 相关共存测试须知见第七节两行）
- [ ] 1.20.1 侧的离线断言（`dev-check` classpath 换 forge-1.20.1-47.1.106-merged.jar 后跑通）
