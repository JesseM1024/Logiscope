# LogiScope · 1.20.1 NeoForge 移植方案

> 状态：**方案已定，尚未动手移植**。本文先于分支建立（master 提交），
> 之后随 `1.20.1-neo` 分支逐步更新，每完成一项就回写结论。
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

| 文件 | 1.21.1 现状 | 1.20.1 情况（预期） |
|---|---|---|
| `LogiScopeMod` / `collect/ProbeManager` | `net.neoforged.neoforge.event.tick.ServerTickEvent`（Pre/Post） | `TickEvent.ServerTickEvent` + `phase`（`event.tick` 包是 20.4+ 拆的）。**CP 采样点挂权威事件的结构不变，挂点换成 phase == END** |
| `LogiScopeMod` / `bind/KeyBindings` | `ClientTickEvent.Pre/Post` | `TickEvent.ClientTickEvent` + `phase == TickEvent.Phase.END` |
| `bind/ProbeContext` / `collect/WorldProbe` | `Capabilities` 注册表 + `IItemHandler` | 20.4 能力系统重做前的旧 API：`ForgeCapabilities.ITEM_HANDLER` + `level.getCapability(cap, side)` |
| `bind/ContainerAnchor` | `useWithoutItem(...).consumesAction()` 判"会不会消费这次交互" | 1.20.5 才拆出 `useWithoutItem`，1.20.1 是 `use(player, hand)`。**判据必须重新核实**（教训：别拿静态判据猜行为） |
| `LogiScopeMod` | `ConfigurationScreen` / `IConfigScreenFactory` | `ConfigurationScreen`（自动生成配置界面）大概率 1.20.1 没有。备选：砍掉配置入口或手写 |
| `ui/ScopeScreen` | `ClientHooks.guiLayers`（真正的 Screen 栈） | 方法名/签名要用 1.20.1 sources jar 重核（历史上栽过"同名不同物"） |
| `ui/ScopeScreen` | `IClientItemExtensions` | 1.19.x 起就有，预期不用动 |
| `LogiScopeConfig` | `ModConfigSpec` | NeoForge 1.20.1 已改名，预期不动；配置注册方式待核 |
| `LogiScopeMod` | `@Mod(dist = Dist.CLIENT)` | `dist` 参数 1.20.1 是否存在**待核实**；备选 `@Mod("logiscope")` + `EventBusSubscriber(value = Dist.CLIENT)` / 构造时判 Dist |
| `collect/AeNetworkReader` | AE2 1.21.1 版 grid/node API | AE2 1.20.1 的对应 API 有差异，逐个核实 |
| `analysis/Snbt` / `archive/*` | 若引用 DataComponent 系 API | data component 是 1.20.5 才有，1.20.1 是 `getTag()` NBT。grep 一遍确认引用面 |

核实手段不变：legacy 插件同步后能拿到 1.20.1 的 sources jar，
`dev-check` 离线断言的 classpath 换成 1.20.1 的 merged jar 路径。

## 五、文档口径（两个版本线并存时的规矩）

- `ARCHITECTURE.md`：**只在 1.21.1 侧维护**，头部已加声明；1.20.1 的差异只写进本文。
- `VERIFY.md`（当轮清单）：**每个分支各自维护**——分支上按 1.20.1 的命令与产物名重建当轮清单。
- `PROGRESS.md`：历史轮次记录不改写；1.20.1 的轮次记录用"### [1.20.1] 轮次 N"小节追加。
- 离线断言命令里 `neoforge-21.1.209-merged.jar` 的路径在 legacy 插件下**必然不同**，
  移植时同步更新 `VERIFY.md` 的 H 组命令。

## 六、IJ 操作步骤（建分支与日常切换）

1. master 上已提交"版本号 0.1.0-1.21.1 + 文档标注"（本文所在提交）。
2. **建分支**：IJ 右下角 Git widget（或菜单 Git → Branches）→ master 右键 →
   New Branch from 'master'，名字 `1.20.1-neo`，勾选 Checkout。等价 `git switch -c 1.20.1-neo`。
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

## 七、待办与待定

- [ ] 测试实例：是否新装 `1.20.1-NeoForge` 实例（用户确认）
- [x] 建分支 `1.20.1-neo` 并切过去（用户在 IJ 中完成）
- [x] 配置层移植（二、三节）—— `generateModMetadata` 在 Gradle 9.2.1 + 插件 2.0.147 下通过，元数据占位符替换正确（2026-09-20）
- [ ] 逐文件 API 移植（第四节表格逐行打勾）
- [ ] `VERIFY.md` 按 1.20.1 重建当轮清单（含 H 组离线断言新 classpath）
- [ ] 实机验收（等测试实例）
