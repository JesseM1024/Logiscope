# LogiScope · 1.20.1 NeoForge 移植方案

> 状态（2026-10-15）：配置层 + API 移植完成（2026-09-20）；main 的第十七轮①、第十八轮①–④
> 与文档体系重构已全部搬运；离线验证全过；AE2 已移出 dev、外部实例渠道已打通并完成兼容性审计
> （§八）；版本号提至 `0.1.1-1.20.1-alpha`；**实机验收待做**（清单见本分支 [`VERIFY.md`](VERIFY.md)）。
> 本文件在两分支各自维护，结论以 `1.20.1-neo` 分支上的版本为准。
>
> 纪律：两分支 git 历史**互不相关**（main 曾 rebase 重写，2026-10-14 核查）——
> 搬运一律 `git cherry-pick -x` 并保留来源 sha，**禁用 merge / rebase**。
>
> 口径：本文只记录 1.20.1 的差异与在 1.20.1 上重新核实的结论；1.21.1 的事实记录留在
> [`ARCHITECTURE.md`](ARCHITECTURE.md)，不两边改写。所有结论以 1.20.1 的 sources jar
> 逐行查证，不凭记忆。

---

## 一、目标与前提

| 项 | 值 |
|---|---|
| 目标 MC | 1.20.1 |
| 目标 NeoForge | `1.20.1-47.1.106`（构建基线 == 该版本，产物在 `build/moddev/artifacts/`） |
| Java | **17**（`org.gradle.java.home` 与 toolchain 同时锁定） |
| 模组版本 | `0.1.1-1.20.1-alpha`（版本号带 MC 后缀的约定见 `gradle.properties`） |
| 分支 | `1.20.1-neo`（自 main 线 `c00c545`「版本号带 MC 后缀」提交切出；2026-10-15 经文档 blob 比对核实） |

**测试实例**：dev 实例（`1_20_1_neo/`，NeoForge 47.1.106）用于常规验证；AE / 精妙存储
相关项在 Forge 47.3.7 整合包实例验证（见 §八）。

## 二、构建层改动

1. **插件**：`net.neoforged.moddev` → `net.neoforged.moddev.legacyforge`（2.0.147；官方支
   持 Forge 1.17–1.20.1 与 NeoForge 1.20.1），`runClient` / `createLaunchScripts` 工作流保留。
2. **配置块**：`neoForge { version = ... }` → `legacyForge { enable { neoForgeVersion = '...' } }`。
   DSL 经字节码核实：2.0.147 的 `LegacyForgeExtension` 外层只有 `version`（Forge 线）与
   `mcpVersion`（原版线）；NeoForge 1.20.1 的 `neoForgeVersion` 在 `LegacyForgeModdingSettings`
   上（三选一互斥）。改成外层写法会报 "unknown property"。
3. **reobf**：1.20.1 生产环境为 SRG 映射，发布 jar 必须 reobfuscated（legacy 插件自带
   remapJar）；`verify-jar.py` 按 remapJar 产物核对。
4. toolchain `JavaLanguageVersion.of(21)` → `of(17)`；parchment 属性对应 `2023.09.03`（若启用）。
5. Gradle wrapper 9.2.1 与 legacy 插件兼容（`generateModMetadata` 已核实 BUILD SUCCESSFUL）。
6. **编码铁律保留**：`filteringCharset = 'UTF-8'`（跨版本通用，见 `build.gradle` 注释块）。
7. 预防性配置 `obfuscation { createRemappingConfiguration(configurations.localRuntime) }`
   按官方 MDK 加入。

## 三、元数据层改动

| 项 | 1.21.1（main） | 1.20.1（本分支） |
|---|---|---|
| 元数据文件名 | `META-INF/neoforge.mods.toml` | **`META-INF/mods.toml`**（模板文件同步改名） |
| 依赖字段 | `type="required"` | **`mandatory=true`**（`type` 为 20.2+ 写法） |
| 平台依赖 modId | `neoforge` | **`forge`**（依赖范围 `[47,)`） |
| `loader_version_range` | `[4,)` | `[47,)` |
| `neo_version_range` | `[21.1.209,)` | `[47,)`（`neo_version=1.20.1-47.1.106` 仅供构建插件） |

模板注释中不写 `${}`（空表达式会被 Groovy expand 替换为 `null`）。

## 四、代码层 API 差异（逐项已核实）

### 4.1 包名家族（compileJava 首轮 200 条错误的根因）

**1.20.1 世代 NeoForge 沿用 Forge 的 `net.minecraftforge.*`**（`net.neoforged.*` 为 1.20.4+
引入）。已开包核实（`build/moddev/artifacts/forge-1.20.1-47.1.106-merged.jar`，15910 条目）：
jar 内只有 `net/minecraft` + `net/minecraftforge`，无任何 `net/neoforged`。

| 1.21.1（main） | 1.20.1（本分支） |
|---|---|
| `net.neoforged.fml.common.Mod` | `net.minecraftforge.fml.common.Mod`（FML 类不在 merged jar 内，走独立 FML 依赖） |
| `net.neoforged.bus.api.IEventBus` | `net.minecraftforge.eventbus.api.IEventBus` |
| `net.neoforged.api.distmarker.Dist` | `net.minecraftforge.api.distmarker.Dist` |
| `net.neoforged.fml.ModContainer` / `config.ModConfig` | `net.minecraftforge.fml.ModContainer` / `net.minecraftforge.fml.config.ModConfig` |
| `net.neoforged.neoforge.common.NeoForge` | `net.minecraftforge.common.MinecraftForge` |
| `net.neoforged.neoforge.common.ModConfigSpec` | `net.minecraftforge.common.ForgeConfigSpec` |
| `net.neoforged.neoforge.client.event.*` | `net.minecraftforge.client.event.*` |
| `net.neoforged.neoforge.event.entity.player.*` | `net.minecraftforge.event.entity.player.*` |
| `net.neoforged.neoforge.items.IItemHandler` | `net.minecraftforge.items.IItemHandler` |
| `net.neoforged.neoforge.capabilities.Capabilities` | `net.minecraftforge.common.capabilities.ForgeCapabilities` |
| `net.neoforged.neoforge.client.ClientHooks` | `net.minecraftforge.client.ForgeHooksClient` |
| `net.neoforged.neoforge.client.gui.ConfigurationScreen` | **不存在**（1.20.1 无自动配置界面，见 §七） |

### 4.2 逐文件撞点（compileJava 首发 200 条错误分布）

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

### 4.3 语义差异（import 修完后的第二轮）

| 文件 | 1.21.1（main） | 1.20.1 实际情况（已核实） |
|---|---|---|
| `LogiScopeMod` / `collect/ProbeManager` | `event.tick.ServerTickEvent`（Pre / Post） | `TickEvent.ServerTickEvent` + `phase == Phase.END`；采样点挂权威事件的结构不变 |
| `LogiScopeMod` / `bind/KeyBindings` | `ClientTickEvent.Pre / Post` | `TickEvent.ClientTickEvent` + `phase == END` |
| `bind/ProbeContext` / `collect/WorldProbe` | `Capabilities` 注册表 | `ForgeCapabilities.ITEM_HANDLER` + `BlockEntity#getCapability`（LazyOptional 通道） |
| `bind/ContainerAnchor` | `useWithoutItem(...).consumesAction()` | `use(level, player, MAIN_HAND, hit).consumesAction()`（`useWithoutItem` 为 1.20.5+） |
| `ui/ScopeScreen` | `ClientHooks.guiLayers` | `ForgeHooksClient.pushGuiLayer / popGuiLayer`（签名已核对） |
| `LogiScopeMod` | `@Mod(dist = Dist.CLIENT)` | Forge 1.20.1 的 `@Mod` 无 dist 参数；改为构造时检查 `FMLEnvironment.dist` |
| `bind/DataCommand` | `ClientChatReceivedEvent.System` | 无 System 子类型；用 `event.isSystem()` 判定 |
| `bind/TextInputGuard` | `ScreenEvent.CharacterTyped.Pre / Post` | 同形（该事件在 1.20.1 已存在，已核实） |
| `collect/AeNetworkReader` | AE2 1.21.1 grid / node API | AE2 1.20.1 对应 API（反射，零编译期依赖） |
| `analysis/Snbt` / `archive/*` | DataComponent 系 | `getTag()` NBT（引用面已 grep 核实） |
| 其余 | `mouseScrolled(…, scrollX, scrollY)` / `saveWithoutMetadata(registryAccess)` / `ResourceLocation.parse` | 单滚动参数 / `saveWithoutMetadata()` / `new ResourceLocation(...)` |

核实手段：legacy 插件同步后产出 `build/moddev/artifacts/forge-1.20.1-47.1.106-sources.jar`；
`dev-check` 离线断言的 classpath 用 `forge-1.20.1-47.1.106-merged.jar`。

## 五、文档口径（两个版本线并存）

- `ARCHITECTURE.md`：只在 1.21.1 侧维护；1.20.1 的差异只写进本文。
- `VERIFY.md`：各分支各自维护（分支上按 1.20.1 的命令 / 产物名重建当轮清单）。
- `PROGRESS.md`：历史轮次不改写；1.20.1 的记录用「[1.20.1] …」小节追加。

## 六、IJ 操作与日常切换

1. 分支已建立并完成全部配置 / API 移植（勾选状态见 §九）；日常仅需 Checkout + 同步。
2. **同步卡住 / import 全红的处理（2026-10-15 实测）**：同步需联网 HEAD 校验
   `forge-1.20.1-47.1.106-userdev.jar` 的元数据；`maven.neoforged.net` 直连不可达时
   `createMinecraftArtifacts` 失败，编辑器内 `net.minecraftforge.*` / `net.minecraft.*` 全红
   （**非代码问题**；元数据与产物本机有缓存，位于 `~/.gradle/caches/neoformruntime`）。
   两条互斥解法：
   1. **走代理（本机当前采用）**：`~/.gradle/gradle.properties` 配
      `systemProp.http(s).proxyHost / Port` 指向系统代理，命令行与 IDE 同步同时生效。
      实测：userdev HEAD 直连超时 → 经代理 HTTP 200 / 0.4s；裸跑 `gradlew.bat build`
      由 223 秒失败变为 20 秒成功。
   2. **离线**：命令行 `--offline`；IJ 用 Gradle 工具窗口的「离线模式」开关；或放一个
      **按项目路径限定**的 init 脚本 `~/.gradle/init.d/logiscope-offline.gradle`
      （`gradle.startParameter.offline = true` + `currentDir` 前缀判断）。
      注：Gradle **没有**项目级离线属性（`org.gradle.offline` 不存在，gradle-9.2.1
      发行包核实），只能走上两法。
3. **JDK**：`org.gradle.java.home` = `C:/Program Files/Java/jdk-17`（随分支走）。`.idea/`
   不受 git 管理，切到本分支后需手动把 Project SDK 设为 17（仅影响编辑器解析，Gradle 侧
   由 `org.gradle.java.home` 保证）。
4. **目录隔离**：本分支 `build.gradle` 设 `gameDirectory = 1_20_1_neo`（`RunModel.getGameDirectory()`
   已 javap 核实），`saves` / `options.txt` / `mods` / `logs` 与 main 的 `run/` 互不覆盖。
   `build/` 仍共用：**切回 1.21.1 前先 `gradlew.bat clean`，切换后重新 sync**。
   测试 jar 换版本线前**先删同名旧 jar**；正在开发的模组不进 dev 的 mods。
5. 分支策略：`main` = 1.21.1 主线；通用 bugfix 进 main 后 `cherry-pick -x` 到本分支；
   仅单侧成立的修复不互挑（提交消息注明）。文档类提交冲突时保留分支侧 1.20.1 专有内容。

## 七、已知限制（1.20.1 线）

| 限制 | 原因 |
|---|---|
| 配置界面入口不存在 | `ConfigurationScreen` 为 20.4+ 引入；改 `config/logiscope-client.toml` |
| 假槽无法识别 | Forge 1.20.1 的 Slot 无 `isFake()`（20.4+ 才引入）；`isSelectable` 仅排除玩家背包 |
| `/tick rate` 改速时秒数按 20 换算 | 1.20.1 无 tickRateManager、无速率同步包 |
| **模组必须带 `pack.mcmeta`（pack_format 15）** | 1.20.1 资源包机制；缺失则 lang 不加载、界面显示键名。1.21 的 NeoForge 用 `neoforge.mods.toml` 充当打包元数据，main 无此需求 |
| **dev 环境下 SRG-mixin 模组不可用**（实例：Create 6 内嵌 flywheel 1.0.5；AE2 15.3.3） | 开发运行为 named 映射，SRG 写的 `@Shadow` 定位不到（报 `@Shadow ... not located`）；**生产环境正常**。识别方法：扫 jar 内 `*.mixins.json` + refmap —— 若 mixin 声明 `f_*` / `m_*` 影子而 refmap 无对应条目，则该模组不可入 dev。处理：此类模组放 `mods-disabled/` 或只在整合包实例启用 |
| 不尝试「给 mod jar 本地做 SRG→named 重映射」 | FART + tsrg2 方案未走通、已废弃（2026-10-14）；本地混用映射来源不可取，按上一行分流 |

## 八、测试环境与渠道（2026-10-15 更新）

| 渠道 | 位置 / 加载器 | 内容 |
|---|---|---|
| dev 实例 | `1_20_1_neo/`（MDG `runClient`，NeoForge 47.1.106） | `mods/` = MEK `10.4.16.80`；**AE2 `15.3.3` 与 Create `6.0.8` 在 `mods-disabled/`**（SRG-mixin 限制，见 §七） |
| NeoForge 测试实例（主） | `1.20.1-NeoForge`（HMCL，NeoForge 47.1.105，版本隔离） | 2026-10-15 装配：AE2 `15.3.6`、MEK `10.4.16.80`、Sophisticated Storage `1.20.1-1.5.5.2182` + Core `1.20.1-1.5.10.2402`、Create `mc1.20.1-6.0.8`、pipez `1.2.5`；装入 `logiscope-0.1.1-1.20.1-alpha.jar`，承接 AE / 精妙存储项（Q9 / S8） |
| 整合包实例（备用） | `GregTech Leisure`（HMCL，Forge 47.3.7） | 68 模组：AE2 `15.2.13` + AE 附属（ae2wtlib / mae2 / ExtendedAE）、**sophisticatedstorage 全家桶**、GTCEu 等；当前装 `logiscope-0.1.0-1.20.1.jar` |

装入实例前先删同名旧 jar（文件名随版本号变化，旧的不会被顶掉；同 modId 并存会被 FML 拒绝）。
**AE2 选版依据**：`15.3.6` 为 1.20.1 线最后一个带 `neoforge` 标签的版本（`15.4.0` 起仅标
`forge`）——测试实例按此选用 `15.3.6`。**兼容性实证（2026-10-15）**：对该 jar 的全部常量池引用审计 ——
265 个类引用（其中 `net.minecraftforge` 35、`net.minecraft` 58）与 180 条 mc / forge 属主成员
引用，在 Forge 47.3.7 运行时（srg.jar + 打补丁 client.jar + universal + FML 组件 + `java.base.jmod`）
**全部可解析，0 缺失；0 个 `net.neoforged` 引用** ⇒ 两条加载器线共用同一枚 jar。

> 备查：审计用一次性脚本与报告在 `build/_probe/`（未入库，`clean` 会清；结论已记入本表）。

## 九、待办与待定

- [x] 建分支 `1.20.1-neo` 并完成配置 / API 移植（compileJava 0 错误；reobf jar 就位，2026-09-20）
- [x] `VERIFY.md` 按 1.20.1 重建 + 离线断言跑通（443 条全过，2026-10-15）
- [x] 同步 main：第十七轮① + 第十八轮①–④ + 文档重构（2026-10-15，12 条 `cherry-pick -x`）
- [x] AE2 移出 dev（`mods-disabled/`）；整合包渠道打通并完成兼容性审计（2026-10-15）
- [ ] **实机验收**：dev 侧清单 = 本分支 `VERIFY.md`（重点 A 组采样保真）；AE / 精妙存储项
      在 NeoForge 测试实例（`1.20.1-NeoForge`）验证
- [x] 版本号提升为 `0.1.1-1.20.1-alpha`（2026-10-15，供外部 NeoForge 测试实例；正式发布待实机验收后定）
