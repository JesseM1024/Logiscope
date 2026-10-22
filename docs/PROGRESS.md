# LogiScope · 进度

> 回答三个问题：**现在到哪了 / 下一步做什么 / 有什么坑**。
> 历轮记录（第一 ~ 十七轮）已归纳进 [`PROGRESS-HISTORY.md`](PROGRESS-HISTORY.md)；
> 目标见 [`GOALS.md`](GOALS.md)，现行原理见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，
> 验收步骤见 [`VERIFY.md`](VERIFY.md)。

## 一句话现状

- **代码 / 离线**：`build` 全过；TestChart **493 条**；`BenchRender`（台阶 99.3%・混合 63.8%）；
  `verify-jar.py` 全过；lang 中英各 **185** 键一致。产物 `logiscope-0.1.3-1.16.5.jar`。
- **发布**：**0.1.1**（tag `neoforge-1.21.1-0.1.1`，含第十七轮① 上升 / 下降事件）；**0.1.2 待发布**
  —— 版本号已提至 `0.1.2-1.21.1`，变更见 `main` 的「第十八轮⑥」；0.1.0 同线；发布约定见
  [PROGRESS-HISTORY](PROGRESS-HISTORY.md) 第十七轮①。
- **实机**：第十六轮④ 及更早的轮次全部通过（里程碑 `g0-skeleton` / `g2-waveform`）；
  **十六轮⑤⑥⑦⑧⑨、十七轮①、十八轮①–⑤ 待复测** —— 清单在 [`VERIFY.md`](VERIFY.md)。

> 以上是 **1.21.1 主线（main）** 的现状。**1.20.1 分支（`1.20.1-neo`）** 的现状与移植记录见
> 紧随其后的「[1.20.1] 线」一节；两条线的 API 差异与限制统一记在
> [`PORT-1.20.1.md`](PORT-1.20.1.md)（本文与 ARCHITECTURE 都只描述 1.21.1 主线）。

---

## [1.16.5] 线：现状与移植记录

**现状（2026-10-21）**：功能已与 main **对齐到第十九轮⑤**
（选格页筛选框 / 忽略空槽 / 回放页打开存档文件夹 / 删除目标按自身删），
并在本线**独自实现了 AE2 8.x 的物品 / 流体监测**（轮次 2 —— 另三条线的 AE 实现照抄不过来，
8.x 是另一套 API）。**实机验收通过，版本 `0.1.3-1.16.5`**（轮次 3 去掉 alpha），产物
`build/libs/logiscope-0.1.3-1.16.5.jar`（146 处 `func_` = 已重混淆，非 dev 包）。
离线 493 条断言 / lang 185 键全过。

> ⚠️ 下面这段「[1.20.1] 线」是从 `1.20.1-neo` 派生时继承的历史，**不是**本分支的记录。

### [1.16.5] 轮次 1：UI 新功能移植（2026-10-21）

| 件 | 1.16.5 侧的做法 |
|---|---|
| `analysis/PickerFilter` / `AeKeyText` | 纯逻辑，原样搬；**`String#isBlank()` 是 Java 11**，改 `trim().isEmpty()` |
| `ui/ScopeScreen` | `Layout` 是**普通类**（不是 record）⇒ 加字段 + 改构造签名 + 加 getter；`switch` 用经典写法（`case X: { ... break; }`）；`font.width(...)` → **`font.getStringWidth(...)`**；`Util.getPlatform()` → **`Util.getOSType()`**；绘制参数是 `Draw` |
| `archive/Archives#dirOf` | 原样（`gameDir` 而非 `gameDirectory`） |
| `collect/ProbeInstance` | `removeDataTarget(DataTarget)`（原来按 valuePath 删会把 AE 目标全删） |
| 语言键 | 新增 6 键（中英各 **185**） |
| dev-check | t41 / t42 搬入；断言 461 → **489** |

### [1.16.5] 轮次 2：AE2 8.x 物品 / 流体监测（2026-10-21）

**两个 bug，一个根因带两个表现**（用户实测反馈：*"打开 AE 终端按 F9 无法呼出菜单"*）：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| ① | AE 终端里按 F9 毫无反应（别的容器界面正常） | AE2 8.x 的终端**一打开就把焦点交给搜索框**：`MEMonitorableScreen#init` 里 `isAutoFocus = terminalSearchMode ∈ {AUTOSEARCH*}`，出厂配置就是 `AUTOSEARCH`（`config/appliedenergistics2-client.toml` 实测值），紧接着 `setListener(searchField)`。`TextInputGuard` 第 1 层把"有文本框聚焦"当成"玩家在打字" ⇒ 快捷键被守卫静默吞掉 | `TextInputGuard#blocksHotkey`：**功能键（F1–F25）不受文本框焦点影响**。判据与模组无关 —— 功能键不产生字符，不可能是打字的一部分 |
| ② | AE 目标恒 `no_data`（即便能进选格页） | 本分支的 `AeNetworkReader` 是照抄 19.x 写的（`GridHelper.getNodeHost` / `MEStorage` / `KeyCounter`），**在 8.4.7 上一条都解析不了** | 按 8.x 重写整条链（见下表） |

**8.x 的读值链**（全部 javap `appliedenergistics2-8.4.7.jar` 核实）：
`TileEntity instanceof IGridHost` → `getGridNode(AEPartLocation)`（INTERNAL 优先 + 扫 6 面；
电缆上的终端部件由 `CableBusTileEntity` 代理，满方块设备认 INTERNAL）→ `IGridNode.isActive()`
→ `IGrid#getCache(IStorageGrid.class)` → `IStorageMonitorable#getInventory(通道)` →
`IMEInventory#getAvailableItems(通道.createList())` → 遍历比对 registry id → `IAEStack#getStackSize()`。
通道来自 `appeng.core.Api.instance().storage().getStorageChannel(Class)`（物品 / 流体两条）。

| 件 | 做法 |
|---|---|
| `collect/AeNetworkReader` | 整类重写；`isAeStorageMenu` 同时认 `appeng.container.me.common.MEMonitorableContainer`（8.x）与 `appeng.menu.me.common.MEStorageMenu`（15/19.x）。**类加载改成 `initialize=false`** —— 单参 `Class.forName("appeng.core.Api")` 会在解析反射链时就把 AE 的 `Api.<clinit>` 跑起来，离线探针当场炸在 `LocatableEventAnnounce.<init>()` |
| `bind/AeKeys`（新） | 8.x 的键层：`RepoSlot#getStoredAmount/getEntry` + `GridInventoryEntry#getStack` → 是流体就编 `ae:{#t:"ae2:f",id:"minecraft:water"}`（**与 15/19.x 同形状**，解在 `AeKeyText#idOf`），是物品就裸 registry id |
| `collect/ContainerAccess` | `SlotView.count` 改 **long**（AE 单键可到 2^31-1）+ 新增 `label`/`displayName()`；`snapshot()/toView()` 在 AE 菜单下走 `viewOfAe` |
| `analysis/AeKeyText` | 新增 `idOf()`（保留命名空间的原文，读值链反查注册表要用）；`label()` 复用它 |
| `ui/ChartRenderer` / `bind/DataTarget` | 图例与目标行按 `AeKeyText.label` 显示 —— 否则流体印成 `{#t:"ae2:f",id:...}` |
| `dev-check/ProbeAeApi`（新） | **验真实实现**（反射调私有 `chain()` / `AeKeys.init()`），不是复制签名表 ⇒ 探针通过等价于"实机那条链能建立"。输出 `AE_API_OK` |
| dev-check | t42 增 4 条（`idOf`）；断言 489 → **493** |

**已知限制（本线）**：

- **附属模组的键类型**（化学品 / 电力等）暂不支持 —— 身份串解不出来就记 `no_data`，不猜 0。
  用户明确要求本轮只做"物品 + AE 本体流体"。
- 物品身份是**裸 registry id**（不带 NBT，与另外三条线一致）⇒ 同种物品的所有 NBT 变体**汇总**成一行。
- 流体只能从**流体终端**里点选（AE 本体就是这样），物品终端里看不到流体。
- 选格页列出的仍是"终端当前可见的那一屏"（`Repo` 按滚动位置取条目），
  换物品要滚动终端 —— 这是"按显示的来"这条纪律的必然结果。


### [1.16.5] 轮次 3：实机验收通过 → 去掉 alpha（2026-10-21）

**依据**：用户实机复测通过 —— AE 终端里 F9 能呼出面板、选格页能列出网络条目、
物品与流体（真名 / 真值 / 各选各的）均可用（对应 `VERIFY.md` J 组）。

| 件 | 改动 |
|---|---|
| `gradle.properties` | `mod_version=0.1.3-1.16.5`（**保留 MC 后缀**，四条线的 jar 仍不会混装） |
| 产物 | `logiscope-0.1.3-1.16.5.jar`；`offline-check.bat` / `README` / `VERIFY` / `GOALS` / `PORT` 的产物名同步 |
| 实例 | `1.16.5-Forge/mods/` 已换装（旧的 alpha jar 移入 `mods/.old`，避免 FML 重复 modId） |
| 历史 | 轮次 1 / 2 那两句「仍带 alpha：尚未实机验收」的措辞随本轮状态更新；`gradle.properties` 的注释里留了旧名作历史 |

> 四条线现在的版本：`0.1.3-1.21.1` / `0.1.3-1.20.1` / `0.1.3-1.18.2` / `0.1.3-1.16.5` —— 全部实机通过。

---

## [1.12.2] 线：现状与移植记录

**现状（2026-10-21）**：**代码面已全量移植完，出包可用**（244 KB / 85 class），已装入
`1.12.2-Forge` 实例；离线门禁全过。**首轮实机已完成，四条反馈已修（轮次 3），待复核**。
分支 `1.12.2-forge` 从 `1.16.5-forge@846818d` 分出，版本 `0.1.0-1.12.2`，
产物 `build/libs/logiscope-0.1.0-1.12.2.jar`。
**本线明确不做 AE 兼容**（用户要求），AE 的类与断言留在 1.16.5 线。
API 差异、三个架构级断层与分阶段计划都在 [`PORT-1.12.2.md`](PORT-1.12.2.md) —— 动手前必读。

### [1.12.2] 轮次 1：工具链与骨架（2026-10-21）

**为什么这一轮值得单独记**：三条候选工具链有两条是死的，选型本身花了实测才坐实。

| 候选 | 结果 |
|---|---|
| ModDevGradle | ❌ 官方 legacy 插件只覆盖 Forge 1.17–1.20.1 |
| RetroFuturaGradle | ❌ 仓库不可达：`maven.gtnewhorizons.com` → HTTP **525**（直连 + 代理都试），`repo.gtnewhorizons.net` → **000**；Central / 阿里云 / Plugin Portal 全 404 |
| **ForgeGradle 3 + Gradle 4.9** | ✅ 采用（= 官方 1.12.2 MDK 的组合；`maven.minecraftforge.net` 上 `ForgeGradle:3.+` 与 `forge-...-mdk.zip` 都可拉） |

| 件 | 内容 |
|---|---|
| `build.gradle` | FG3 重写；Gradle 4.x 老语法（`task` / `def`，没有 `tasks.register`）；**显式 UTF-8**（源码 + `processResources`）；`jar.finalizedBy('reobfJar')` |
| `gradle.properties` | `org.gradle.java.home` 锁 **jdk-1.8**（Gradle 4.x 不支持 11+，本机默认是 25）；`mod_version=0.1.0-1.12.2` |
| wrapper | 换成 **Gradle 4.9**（jar 取自官方 MDK，老 wrapper 与 4.9 配套）；发行版走腾讯云镜像 |
| 元数据 | 新增 `mcmod.info`（`${version}`/`${mcversion}` 由 `processResources` 展开）；`pack.mcmeta` 的 `pack_format` 15 → **3** |
| 骨架 | `LogiScopeMod` 重写为 1.12.2 形态（`@Mod(clientSideOnly=true)` + `@Mod.EventHandler` + log4j2） |
| 纯逻辑层 | `analysis/*`、`store/*`、`archive/ArchiveData`、`archive/ArchiveFormat` 原样搬入（零 MC import） |
| 门禁 | `offline-check.bat` 改成 4 步：TestChart **476** 条 + BenchRender + `check-version.py`（`@Mod` 的版本是手写常量，必须与 `gradle.properties` 一致）；Gson 由 `copyGateDeps` 落到 `build/gatedeps/` |
| 断言拆分 | `DataProbe` 那 4 条 → `dev-check/TestNbt.java`；AE 目标 13 条（t38）→ `dev-check/TestAe.java`。两者都**不在本线门禁**，断言原文与期望值一字未改 |
| 未搬 | `bind/*`、`collect/*`、`ui/*`、`LogiScopeConfig`、`archive/Archives`（用 `git show 1.16.5-forge:<路径>` 取回） |

**三个架构级断层**（详见 PORT §2.2）：
1. **没有 GUI 图层栈**（`ForgeHooksClient` 里没有 `pushGuiLayer`，实测）⇒ 面板不能"压"在原容器界面上。
   正解：自己改 `mc.currentScreen` 字段（不调 `displayGuiScreen`，否则会关掉容器），
   渲染时先 `background.drawScreen(...)` 再画面板。
2. **没有按键事件**：`GuiScreenEvent` 只有 7 个成员，`KeyboardInputEvent` **连按键号都不带**
   （`javap` 实测只有 `(GuiScreen)` 构造器）⇒ 快捷键改成 `TickEvent` 里轮询 `Keyboard.isKeyDown` + 边沿检测。
3. **没有 charTyped 事件** ⇒ `TextInputGuard` 第 3 层（Pre/Post 计数）做不了；
   好在默认键位 F9 是功能键，本来就不该被文本框挡。

**未完成**：实机验证（本机没有 1.12.2 实例）。

---

### [1.12.2] 轮次 2：MC 面全量移植，出包可用（2026-10-21）

**结果**：`bind/` `collect/` `ui/` `archive/Archives` `LogiScopeConfig` 全部搬完；
`gradlew build` 出包 **243 KB / 85 个 class**，已装入 `1.12.2-Forge` 实例（Forge 14.23.5.2864）。
离线门禁 `ALL_OK`（TestChart 476 + BenchRender + 版本一致性）。

| 件 | 做法 |
|---|---|
| 批量改名 | 脚本做包路径级替换（`Screen→GuiScreen`、`CompoundNBT→NBTTagCompound`、`Direction→EnumFacing`、`ForgeRegistries` 换到 `fml.common.registry`…），再用编译器收割：100 → 40 → 15 → 1 → 0，四轮收敛 |
| 维度 | 新增 `compat/Dim`（包一层 `int`，语义等价 `RegistryKey<World>`）⇒ 20 多处调用点只换类型名，不用散落魔数 |
| `Draw` | **重写为立即模式**（`Gui.drawRect` / `FontRenderer` / `RenderItem`）；对外签名不变 ⇒ `ChartRenderer`+`ScopeScreen` 200+ 处绘制调用零改动；`pose()` 的 z 层次改成 `pushZ/popZ` |
| `ScopeScreen` | 生命周期整体换名（`init→initGui`、`render→drawScreen`、`tick→updateScreen`、`keyPressed+charTyped→keyTyped`、`isPauseScreen→doesGuiPauseGame`、`onClose→onGuiClosed`）；`openOverlay` 改成手管 `mc.currentScreen` 并替父界面作画 |
| `TextInputGuard` | 重写：没有焦点 API / 没有 charTyped ⇒ 功能键永不挡 + 会打字的键遇界面让路 + 单挡 `GuiChat` |
| 语言文件 | `lang/*.json` → **`lang/*.lang`**（1.12.2 只读 `.lang`，185 键原样转换） |
| AE | 明确不做：`AeNetworkReader` 删除、`ContainerAccess` 的 `viewOfAe` 删除、`ProbeInstance` 的 AE 分支留空记 no_data、选格页 AE 模式保留但不触发 |

**新核实的一批 1.12.2 事实**（`javap`，都写进 PORT §三之二 的表）：`ResourceLocation` 用
`getResourceDomain/getResourcePath`（1.13 才改名）；`TileEntity.getKey(Class)` 静态；
`ForgeRegistries` 没有 `TILE_ENTITIES`；`world.isRemote` 是字段；`NBTTagList.tagCount()`；
`onBlockActivated` 返回 boolean；`Item#onItemUseFirst` 是 8 参；`Util.EnumOS` 没有 `openFile`。

**新增已知限制**：面板外的点击/滚轮不再转交给容器（`GuiContainer#mouseClicked` 是 protected，
跨包调不到，反射又撞 SRG）⇒ 面板开着时先收起再操作容器。编译基线 2859 / 实例 2864（二进制兼容）。

**未完成**：实机验证（jar 已装进实例，等你启动）。

---

### [1.12.2] 轮次 3：首轮实机反馈（四个问题 → 三个根因）（2026-10-21）

**先入档实测结果，再改代码**（便于回滚到"已知可用"）。

实机反馈四条，其中 C / D 是**同一个根因**。硬证据不是猜的，是模组自己写的归档头：

```json
// versions\1.12.2-Forge\logiscope\2026-10-21_23-16-55\recording.json
"probe":{"name":"open container (no coords)","description":"slots 0 · runtime identity"}
```

⇒ 探针的 `ProbeTarget` **没有持久身份**（`hasPersistentId() == false`），锚点从来没拿到过。

| # | 反馈 | 根因（都 `javap` 过） |
|---|---|---|
| 1 | 物品被绘制在面板之上 | `RenderItem#setupGuiTransform` 固定 `translate(x,y,100+zLevel)`，面板在 z=0；深度测试开着 ⇒ **z=100 必胜**，绘制顺序失效 |
| C | 关界⾯即 `paused`、点「继续」报 `source unavailable` | 锚点 null ⇒ 无坐标 ⇒ 不能 FOLLOWING，也不能走服务端读 |
| D | 「Probe data」按钮置灰点不了 | 同一件事（置灰条件就是 `hasPersistentId()`） |
| — | 用户猜"1.12.2 没有 `data get block`" | 属实（`/data` 是 1.13 才加的），但**不是 D 的原因** —— 单人下 `DataCommand` 走服务端直读，不碰命令 |

**致命根因：`InitGuiEvent.Post` 触发时 `windowId` 还是 0。**
`handleOpenWindow → displayGUIChest → displayGuiScreen → setWorldAndResolution`
（`initGui` 里 `player.openContainer = this.inventorySlots` → `InitGuiEvent.Post` ←我们在这里记锚点）
**之后**才 `player.openContainer.windowId = packet.getWindowId()`。
旧代码把锚点记在窗口号上 ⇒ "打开时记 0、之后比 1"，永远对不上。

**改动**

| 件 | 改动 |
|---|---|
| `ContainerAnchor` | 锚点改挂 **`Container` 对象身份**（`activeContainer`），不再用 `windowId`；新增 `anchorFromContainer`（槽位 `inventory` 自己就是 `TileEntity` 时反推坐标，作为"右键事件没抓到"的兜底）；绑定时打一条 INFO 日志（锚点成功/失败都能从 latest.log 看出来） |
| `KeyBindings#onScreenInit` / `ProbeContext` / `ProbeInstance#tryRebind` / `ScopeScreen#matchesOpenContainer` | 跟着改成传/比 `Container` |
| `Draw` | 新增 `beginPanel()` / `endPanel()`：**关深度**（这是"物品盖面板"的正解）+ 关光照 + 归一混合，画完还原成 vanilla 期望的状态；`renderItem` 改成逐次开关 GUI 光照（否则后面的文字发暗） |
| `ScopeScreen#drawScreen` | 整段面板包在 `beginPanel/endPanel` 里；面板底色 `0xF2101017` → **`0xFF101017`**（95% 会让底下物品以 5% 透出来，实机像素采样证实）；`renderTip` 之后**再断言一次**（`drawHoveringText` 收尾会把 depth 打开） |
| `WorldProbe` | 新增 `containerOf`：双联箱子先问**方块**（`BlockChest#getContainer` 返回 54 格包装器，顺序与客户端菜单一致），不再直接用 27 格的 TE（右半边必然越界 → no_data）。`verifyNow` / `readOnServer` / `ProbeSlots` 共用它 |
| `DataCommand` | 多人分支不再发 `/data get block`（这一代没有），直接如实报"读不到"；单人直读路径不变 |

**产物**：`logiscope-0.1.0-1.12.2.jar`（244 KB），门禁 `ALL_OK`，已换装进 `1.12.2-Forge` 实例
（旧包移到 `mods/.old/logiscope-0.1.0-1.12.2-r1.jar`）。

**待实机复核**：见 [`VERIFY.md`](VERIFY.md) 的 **G 组**（本轮四条修复的定向复核）。

---

### [1.12.2] 轮次 4：AE 终端读值 + 面板压在所有覆盖层之上（2026-10-22）

轮次 3 的三条在实机通过（关容器继续追踪 / `Probe data` 都好了）。新报三条，全部定位并修掉：

| # | 反馈 | 根因（`javap` 核实） | 修法 |
|---|---|---|---|
| 1 | AE 终端选格页**没筛掉玩家背包** | `AEBaseContainer#bindPlayerInventory` 用 `PlayerInvWrapper` 包玩家背包再建 `SlotPlayerInv` ⇒ `slot.inventory` 是 `SlotItemHandler` 的占位空 `InventoryBasic`，老判据（`slot.inventory == player.inventory`）必然漏 | `isPlayerSlot` 加第二条判据：`SlotItemHandler#getItemHandler()` 是 `PlayerInvWrapper`（或 `InvWrapper.getInv() == player.inventory`） |
| 1 | AE 物品**数量恒 1** | AE 终端格 `getStack()` 的 count 恒 1；真值在 `SlotME#getAEStack().getStackSize()` | 新增 `collect/AeSlots`（反射）：物品读 `IAEStack#getStackSize()`，显示/身份仍是 registry id |
| 2 | 流体终端**一条都读不到** | `SlotFluidME.getStack()` 返回空（流体不是物品）；rv6 没有 `wrapped_generic_stack` 占位物品（AE2 10 才有） | `AeSlots` 读 `getAEFluidStack().getFluidStack()`，身份 `ae:fluid:<流体注册名>`，数量 mB |
| 3 | 选格页右侧整片物品压住面板 | 面板画在 `drawScreen`；JEI 的物品列表挂在 `DrawScreenEvent.Post`（`ForgeHooksClient#drawScreen` = Pre → drawScreen → Post）⇒ JEI 晚于我们 | 新增 `ui/PanelPainter`：面板改在 Post 里画，且**第一次开屏时才注册** ⇒ 派发顺序排在 JEI 之后 ⇒ 面板在最上层；注册失败退回即时绘制 |

**新增已知限制**：AE 探针关掉终端后记 `no_data` —— ME 网络不在那个方块的物品栏里，
本线还没有服务端读 AE 网络的通道（`AeNetworkReader` 级别的活，另开一轮）。
流体序列名用注册名（`fluid:water`），不是本地化名。

**产物**：`logiscope-0.1.0-1.12.2.jar`（249 KB），门禁 `ALL_OK`，已换装（旧包 `.old/…-r2.jar`）。

---

### [1.12.2] 轮次 5：AE 真值生效后的三个尾巴（2026-10-22）

轮次 4 的 AE 真值生效了（终端里数量与网络一致）。新报三条：

| # | 反馈 | 根因 | 修法 |
|---|---|---|---|
| 1 | Pick cells 时面板变成**一片灰块** / 仍被盖住 | 只靠"注册得晚"保证不了派发顺序；且 `endPanel()` 之后若还有人画，会继承我们改过的 GL 状态（JEI 的列表整片渲染成灰块就是这个） | `PanelPainter` 的监听器加 **`priority = EventPriority.LOWEST`**：Forge 按优先级派发，我们一定最后 ⇒ 面板在最上层，且之后没人再画 |
| 2 | AE **背包格仍能选中** | `SlotPlayerInv`/`SlotPlayerHotBar` 继承 **`AppEngSlot`**（不是 `SlotItemHandler`），而 `AppEngSlot` 的 `IItemHandler` 是 **private 字段、无访问器** ⇒ 上一版判据全漏 | `ContainerAccess#itemHandlerOf`：`SlotItemHandler` 走公开 getter，其它类在类层级里找第一个 `IItemHandler` 字段反射读（按类缓存）；`PlayerInvWrapper`/`InvWrapper#getInv()` 判据复用 |
| 3 | 面板开着时 **AE 终端不刷新**（漏斗灌物品数字不动，关掉面板才跳） | 换掉 `mc.currentScreen` ⇒ 父界面不再 `updateScreen()`；AE 的 repo 正是在 `GuiMEMonitorable#updateScreen()` 里合网络推送。原版箱子不受影响（服务端 `detectAndSendChanges` 主动推） | `ScopeScreen#updateScreen` **转发**给 `parent.updateScreen()` |

**已知遗留（显示问题，未改）**：AE 槽位的行首 `#N` 恒为 `#0`（那是容器内下标，AE 恒 0）。
选格仍按菜单下标走、行靠名字区分；要改得同时动 `PickerFilter` 的 `#N` 语义。

**产物**：250 KB，门禁 `ALL_OK`，已换装（旧包 `.old/…-r3.jar`）。

---

### [1.12.2] 轮次 6：面板改成真图层（不换 currentScreen）（2026-10-22）

I2/I3 通过；I1（Pick cells 灰块）与 I4/I5（AE 终端在面板开着时不刷新）仍在。

**I4/I5 的根因（这次是字节码级确证）**：`PacketMEInventoryUpdate#clientPacketData` 是
`if (mc.currentScreen instanceof GuiMEMonitorable) ((GuiMEMonitorable) mc.currentScreen).postUpdate(list);`
—— **AE 的网络更新包只投递给"当前界面"**。我们把 `currentScreen` 换成面板之后，包直接被丢掉；
而 `repo.updateView()` 只挂在 `postUpdate`/`initGui`/点击/按键上，**不在 `updateScreen` 里**，
所以上一版"转发 `parent.updateScreen()`"从原理上就不可能生效。

**改法：`mc.currentScreen` 保持是父界面，面板做成真图层**

| 件 | 做法 |
|---|---|
| `openOverlay` | 不再改 `currentScreen`，只 `setWorldAndResolution` 拿尺寸 + 登记 `ACTIVE` |
| `PanelPainter` | `DrawScreenEvent.Post` + `LOWEST`，挂在**父界面**的 Post 上画（面板永远最上层） |
| `PanelInput`（新） | `MouseInputEvent.Pre`/`KeyboardInputEvent.Pre` 分流：面板内 `setCanceled`，面板外放行 |
| tick | 面板不是当前界面 ⇒ `updateScreen` 改由 `ClientTickEvent` 驱动；父界面没了就收面板 |

**★ 新核实的 1.12.2 语义**：`GuiScreen#handleInput()` 是
`while (Mouse.next()) { if (post(Pre)) continue; handleMouseInput(); }` ——
事件**逐条**发，取消一条只 `continue` 到下一条 ⇒ **处理函数里不能自己调 `next()`**。

**顺带**：面板外的点击/滚轮现在能真的转交给容器（旧限制作废）；JEI 面对正常的容器界面，
I1 的灰块也一并消失。

**产物**：254 KB，门禁 `ALL_OK`，已换装（旧包 `.old/…-r4.jar`）。

---

## [1.20.1] 线：现状与移植记录

**现状（2026-10-15）**：功能已与 main **对齐到第十八轮⑤** —— 第十七轮① + 第十八轮①–④ 经搬运到位，
**轮次 4 的 DATA 右键「按数值读取」已回流 main（两步式定稿 `3ddb77c` + 表头截断 `d2dc8c5`）**；
F9 判据维持第二代（收紧曾被实机否决、已回滚；机械手误交互保留不修 —— 见「轮次 4」）。dev 启动
问题已定位并处理（AE2 的 SRG-mixin 缺陷 → `mods-disabled/`，外部实例渠道打通并完成兼容性审计，
见「轮次 2」）；版本号提至 `0.1.2-1.20.1-alpha`（轮次 5），NeoForge 测试实例已装配（「轮次 3」）；
**首次实机验收进行中**（dev 清单 = 本分支 [`VERIFY.md`](VERIFY.md)，含轮次 4 的 C-4；AE / 精妙
存储项在 `1.20.1-NeoForge` 实例验证）。

| 项 | 值 |
|---|---|
| 构建 | `gradlew.bat build --offline` 通过（代理关闭时；代理在跑可直接联网构建）；产物 `build/libs/logiscope-0.1.2-1.20.1-alpha.jar`（reobf） |
| 离线 | TestChart **461 条**全过、BenchRender（台阶 99.3%・混合 63.8%）、verify-jar 全过、lang 中英 **179** 键 |
| dev 实例 | `1_20_1_neo/`（与主线 `run/` 隔离）；mods = MEK `10.4.16.80`；AE2 `15.3.3` / Create `6.0.8` 在 `mods-disabled/` |
| 外部实例（主） | `1.20.1-NeoForge`（HMCL / NeoForge 47.1.105）：AE2 `15.3.6` + MEK `10.4.16.80` + 精妙存储 `1.5.5.2182` / Core `1.5.10.2402` + Create `6.0.8` + pipez `1.2.5`；已装入 `0.1.2-1.20.1-alpha` |
| 外部实例（备用） | GregTech Leisure（Forge 47.3.7）：AE2 `15.2.13` + 精妙存储 + GTCEu；装 `0.1.0-1.20.1`，常量池审计 0 缺失 |
| 未做 | 实机验收（重点 A 组采样保真 + C-4 右键「按数值读取」填入值格） |

### [1.20.1] 轮次 1：同步 main 第十七轮① + 第十八轮①–④ + 文档重构（2026-10-15）

**做法**：两分支历史**无共同祖先**（`merge-base` 为空），全部经 `git cherry-pick -x` 逐条搬运并
保留来源 sha；保留 1.20.1 侧 API 垫片（`ForgeConfigSpec` / `MinecraftForge.EVENT_BUS` +
`TickEvent.Phase.END` / `ForgeHooksClient` / `META-INF/mods.toml`），不采用 main 的 21.1 写法。

| 来源（main） | 内容 | 冲突与解法 |
|---|---|---|
| `adde1e3` | 第十七轮① 上升 / 下降事件 | 无（`ScopeScreen` 自动合并） |
| `b26b30a` | 第十八轮① 采样统一到服务端 | 仅 PROGRESS 文档 → 取 main 版 |
| `7b8635f` | 第十八轮② 删「界面已关」提示 | 无 |
| `e03cc5c` | 第十八轮③ 存档筛选框 | 无 |
| `9f75544` | 第十八轮④ DATA 页「完成」+ 刷新滑条 | **`LogiScopeConfig`**：字段保持 `ForgeConfigSpec.IntValue`（新增 `DATA_REFRESH_TICKS`）；VERIFY 的 V1 行改 1.20.1 版本号 |
| `c5cca54` `75e164e` `4f31c96` `9e9025b` | 文档体系重构（ARCHITECTURE 重写 / PROGRESS 瘦身 / VERIFY 合并 / 其余文档） | PROGRESS 无分支专有内容 → 取 main；VERIFY 取其重构版后**重建 1.20.1 侧**（另一提交）；README / PORT 保留 1.20.1 内容 |
| `4ec6915` | 全库注释去历史化（35 文件，纯注释） | 2 处：`WorldProbe`（本线是 **Forge** capability）、`ScopeScreen`（`ForgeHooksClient.pushGuiLayer`）—— 保留分支代码，只吸收措辞 |

**跨版本 API 核实（本次新增）**：从 `build/moddev/artifacts/forge-1.20.1-47.1.106-sources.jar` 读方法体确认
`ForgeConfigSpec#save()`（内部走 `FileConfig.save()` 落盘）与 `ConfigValue#set(T)` 在 1.20.1 都存在，
前置条件不满足时抛异常 ⇒ 滑条写回用 try/catch 包裹（与 main 同语义）。

**环境事项（详见 README 构建备忘 / PORT §六）**：
1. `createMinecraftArtifacts` 需联网 HEAD 校验 forge userdev 元数据；`maven.neoforged.net` 直连
   不可达 ⇒ IJ 同步卡住、编辑器 import 全红（非代码问题）。最终解法：Gradle 走代理
   （`~/.gradle/gradle.properties`）；备选：`--offline`。
2. Git Bash → cmd 传多条目 `-cp`（分号列表）会拆坏（报"程序包不存在"）；离线断言脚本改用
   `.bat` 包装（`dev-check/offline-check.bat`）。

### [1.20.1] 轮次 2：AE2 移出 dev + 整合包渠道打通 + 兼容性审计（2026-10-15）

**事件**：dev `runClient` 启动即崩 —— AE2 15.3.3 的 `PickColorMixin` 以 SRG 名（`@Shadow f_91074_`）
声明影子字段，其 refmap 无对应字段条目；dev 为 named 映射 ⇒ `InvalidMixinException`，7 秒内退出
（生产环境无此问题）。证据链：启动日志的重映射行 → `javap` 字段声明 → refmap 零命中 → tsrg
交叉核对（`f_91074_` = `Minecraft.player`）。已排除：换 AE2 版本无效（15.4.11 的 refmap 逐字
相同）；本模组与 MEK 均无 mixin；MDG legacy 插件无「dev 跑 SRG」开关。

**处理（A+B）**：AE2 移入 `mods-disabled/`（dev 不再加载）；AE 相关验证改在整合包实例
GregTech Leisure（Forge 47.3.7，含 AE2 15.2.13 + 精妙存储）进行，jar 已装入其 `mods/`。

**兼容性审计**：对 jar 全部常量池引用做解析审计 —— 265 个类引用逐条核对存在性，其中
`net.minecraft` / `net.minecraftforge` 属主的 180 条成员引用按继承链逐条解析；在 Forge 47.3.7
运行时（srg + 补丁 client + universal + FML 组件 + JDK `java.base.jmod`）**0 缺失、0
`net.neoforged` 引用** ⇒ 两条加载器线共用同一枚 jar。（审计脚本为一次性工具，未入库。）

### [1.20.1] 轮次 3：版本号 0.1.1-1.20.1-alpha + NeoForge 测试实例装配（2026-10-15）

**版本**：`gradle.properties` 的 `mod_version` 由 `0.1.0-1.20.1` 提至 **`0.1.1-1.20.1-alpha`**；
产物 `build/libs/logiscope-0.1.1-1.20.1-alpha.jar`（reobf；`--offline` 构建通过，系统代理关闭期间）。

**外部测试实例**（用户新建）：`1.20.1-NeoForge`（HMCL，NeoForge `1.20.1-47.1.105`，版本隔离）。
按「1.20.1 + 带 `neoforge` 标签的最新版」装配：AE2 `15.3.6`、Mekanism `10.4.16.80`、
Sophisticated Storage `1.20.1-1.5.5.2182`（前置 Core `1.20.1-1.5.10.2402`）、Create `mc1.20.1-6.0.8`、
pipez `1.20.1-1.2.5`；本 jar 已装入其 `mods/`。

**AE2 选版说明**：`15.3.6` 是 1.20.1 线最后一个带 `neoforge` 标签的版本（`15.4.0` 起仅标 `forge`）；
与 dev 侧同线（15.3.x），AE 反射代码路径的测试面一致。

**dev 侧遗留（暂停排查）**：MEK `10.4.16.80` 在 dev（NeoForge 47.1.106）启动时抛
`NoSuchMethodError: 'net.minecraft.Util.m_137492_(String, ResourceLocation)'`（该 SRG 名在本版映射
中对应 `Util.makeDescriptionId`）；根因未收敛，排查按用户要求暂停，转外部实例测试。
dev `runClient` 若需可用，可先将 MEK 移入 `mods-disabled/`（与 AE2 / Create 同处）。

---

### [1.20.1] 轮次 5：版本号 0.1.2-1.20.1-alpha（2026-10-17，待发布）

**版本**：`mod_version` `0.1.1-1.20.1-alpha` → **`0.1.2-1.20.1-alpha`**（与 main 的 `0.1.2-1.21.1`
同步）；产物 `build/libs/logiscope-0.1.2-1.20.1-alpha.jar`；脚本 / 文档产物名同步（VERIFY / GOALS /
PORT / README / `dev-check/offline-check.bat`）。

**相对 0.1.1-1.20.1-alpha 的功能变化**：DATA 页右键「按数值读取」（填入值格 + 黄标 + 确认替换）
与追踪目标表头省略号截断；F9 判据维持第二代（收紧已回滚，机械手误交互保留）。

**加载器兼容性复核（2026-10-17）**：对 0.1.2 jar 的字节码常量池审计 —— `net.neoforged` 引用
**0 条**；Forge 47.3.7 运行时类存在性 35/35 + 58/58、成员引用 180 条 **0 缺失**；实机 Forge
47.3.7（GregTech Leisure）正常 ⇒ **同一 jar 同时适用于 NeoForge / Forge 47.x（MC 1.20.1）**。
结论与边界写入 PORT §十、README 与发布说明。

### [1.20.1] 轮次 4：DATA 页右键「按数值读取」（两步式定稿）+ F9 判据收紧（后回滚）（2026-10-15）

**背景（用户实测）**：① 有的数值类型在 NBT 里被模组存成**字符串**，需要"强制按数值读取"
的口径；② 对准 Create 机械手按 F9 会**先发生一次真实交互**（手取放了物品），光标移开后
面板才出现 —— 1.21.1 线无此问题。

**① DATA 页右键「按数值读取」**（初版 `a6e701b` → 一步式 `9d8a02e` → **实机再反馈，
定稿两步式 `edd8f60`**；表头截断 `1c67ca1`）：
右键值条目 = 把该路径**按「按数值读取」口径填入值格**（不直接加追踪），可先设名称再
「确认添加」；值格右端点**黄色小方格**（`COLOR_FORCED`）标这个口径，手改路径 / 左键取值
即清掉。读取走 `DataProbe.asValForced`（数值标签照常，字符串再试 `Val.parseNumericText`：
整数优先 long、纯整数超 long 拒绝近似）；不可转换 → `no_data`（宁缺毋假）。同路径设置变了
（口径 / 名称）再确认 = **替换**（`ProbeInstance.replaceDataTarget`，精确匹配、不碰锚点 /
AE 目标）；非标量条目右键明确提示，不填。**已回流 main（`3ddb77c` / `d2dc8c5`）。**

**② F9 判据收紧（第三代）**（提交 `6ff713b` → **实机反馈否决，已回滚 `83d62d8`**）：
设计背景：1.20.1 的 `use()` 把方块交互与物品交互合并，"会不会消费右键"无法区分
"有界面的机器"与"会动作的设备"。收紧为"只认标准界面通道（`hasMenuChannel`）"后，
**MEK 工厂界面无法再用 F9 打开**（实测）—— 用户不接受这个代价。整体回滚到
"会消费右键 → 模拟右键"的第二代判据；**机械手误交互保留不修**（用户明确）。

**实机反馈与两次修订（同日）**：
- **第一轮**：DATA 右键"无法把 '100' / '39900' 这样的值作为数据追踪" —— 根因：`mouseClicked`
  在 `button != 0` 时直接 `return true`（右键早退），而初版把右键分支写在了**只接左键**的
  `handleDataClick` 里 = **死代码**（离线断言测不到 UI 事件流，未暴露）→ 右键派发移到早退
  之前 + 一步式。
- **第二轮**：一步式把"追哪个数"和"叫什么名"绑死 → **定稿两步式**（填入值格 + 黄标 +
  确认替换，见①）；另修追踪目标表头在窄窗口溢出（套 `cut` 省略号截断）。
- `hasMenuChannel`（第三代判据）随 revert 一并移除；`wouldConsumeClick` 恢复主判据。

**离线**：TestChart **461 条**全过（新增 t40 断言 18 条）、BenchRender（台阶 99.3%・
混合 63.8%）、verify-jar 全过、lang 中英 **179** 键；dev-check classpath 补
`guava-31.1-jre`（t40 的 `ListTag` 构造依赖）。

**实机验收项**：VERIFY.md 的 **C-4**（右键填入值格 / 黄标 / 替换 / 表头截断，P1–P9；含用
`data modify` 造字符串键的方法）。

---

## 第十八轮①：物品探针采样统一到服务端（菜单降为兜底，2026-10-15）

**需求**（用户原话归纳）：把"打开 GUI 读菜单 / 关闭 GUI 读服务端"两种采样逻辑统一成
**直接读服务端世界数据**（数据保真）；保留服务端读取的检测逻辑，**读不到时**
（例如多人）才用"打开 GUI 的读取方法"。MEK 等 NBT 路径不动；FOLLOWING 的既有行为不改。

| 件 | 内容 |
|---|---|
| `collect/ProbeInstance#serverSideSampling` | 条件从 `state != RECORDING \|\| VERIFIED` 改为 **有坐标 && 单人 && 映射 != UNAVAILABLE** —— 界面开着也让位服务端；UNAVAILABLE（挂探头时验证出既非 Container 也无 ItemHandler）保留给菜单兜底 |
| `collect/ProbeInstance#tickRecording` | `serverSideSampling()` 检查**提前到"换设备检测"之前**：服务端采样与界面无关，"RECORDING 下切到别的容器就 pauseAuto"是菜单路才需要的判定（单人下不再打断） |
| `collect/ProbeInstance#sampleOnServerTick` | `itemsReady` 去掉 `now == FOLLOWING \|\| VERIFIED`，改为 **有坐标 && != UNAVAILABLE** —— RECORDING 与 FOLLOWING 走同一条路：验证过的用验证下标、没验证的按既有回退 |
| `collect/ProbeInstance`（警告） | `warnIfIndexUnverified` 从 `tickFollowing` 挪到 `tick()`（RECORDING 也要提示），判据从 `menuSlotIndex` 修正为 **`containerSlotIndex` 重复**（复用 `SlotAlign.containerIndexReliable` 的不变式）—— 旧判据查的是菜单位置（天然唯一）⇒ 该警告**从未按设计弹过**；javap 实锤 MEK 的 `getContainerSlot()` 恒 0 |
| lang | `msg.index_unverified` 去掉"关界面后"的过时限定（中英一致）；README 采样描述同步 |

**为什么旧警告判据是错的**（写下来免得下次又选错字段）：第十四轮加"只在序号有重复时提示"
时注释写的是"MEK 那类全是 #0"，但"全是 #0"的是 **`Slot#getContainerSlot()`（容器下标）**；
菜单位置（`menuSlotIndex`）天然唯一。javap 复核 `InventoryContainerSlot` 构造 =
`super(emptyInventory, 0, x, y)` → 容器下标恒 0。修正后各机器自洽：
原版箱子 / `SlotItemHandler` 机器不弹（回退恰好正确）、MEK 弹（回退会把两格读成同一格）。

**行为矩阵（物品探针）**：

| 局面 | 采样来源 |
|---|---|
| 单人 + 有坐标 + 非 UNAVAILABLE（**界面开/关都一样**） | **服务端 tick**（每世界 tick 一条真值） |
| 服务端不可用 + 界面开着（多人 / 无坐标 / UNAVAILABLE） | 菜单（回退，按菜单位置读） |
| 服务端不可用 + 界面关着 | no_data |

**不改的**：MEK / NBT 锚点采样（本来就是服务端读，CP24）；FOLLOWING 的既有行为；
数据探针与 AE 目标；`verifyIndex` 检测体系本身（它继续决定"用验证下标还是回退"）。

离线：build / TestChart 443 条 / BenchRender（台阶 99.3%・混合 63.8%）/ verify-jar 全过。
实机复测见 `VERIFY.md` 第十八轮①。


## 第十八轮②：删除"界面已关"提示（2026-10-15）

**需求**（用户原话）："现在就不需要关闭原版类容器的提示……了，删除这个逻辑。"
（=`LogiScope：界面已关，转为坐标跟踪` / EN `GUI closed, tracking by position`）

采样统一服务端后（第十八轮①），界面开/关**不对读数路径产生任何切换** ——
这条提示在描述一件没发生的事（它是菜单时代的残余：旧口径里"关界面 = 从菜单读切成坐标读"）。

| 件 | 内容 |
|---|---|
| `collect/ProbeInstance#enterFollowing` | 去掉 `boolean notify` 参数与 `msg.following` 调用（7 个调用点全部转无参）；javadoc 写明删除原因 |
| lang | 删除 `logiscope.msg.following`（中英各 175 → **174** 键，仍一致） |
| 注释 | `warnIfIndexUnverified` / `notifyPlayer` 里对"转为坐标跟踪"去抖场景的引用改为历史表述（force 语义不变） |

**保留的**：`pauseAuto` 的"数据源丢失，已暂停"（`msg.paused_auto`）—— 那是"真的没在采"的
提醒，与界面无关；`warnIfIndexUnverified` 的强制警告（force 通道照旧）。

离线：build / TestChart 443 条 / verify-jar 全过。


## 第十八轮③：根页存档列表带筛选框（2026-10-15）

**需求**（用户原话）："主界面的 Archives 那里改成文本框，默认状态下显示原本的
Archives analysis data，输入文字时可用于筛选已存档数据名称。"

标题行左侧的「存档 · 读取分析数据」改为**输入框**：空 = 显示原标题文字（占位符）；
点入后可输入，存档列表**实时筛选**。

| 件 | 内容 |
|---|---|
| `ui/ScopeScreen#renderArchiveFilter` | 标题位置画输入框（与名称列同宽）：空时以占位色显示 `logiscope.info.archives`（= 原标题），聚焦亮环 + 闪烁光标（风格同重命名框），悬停变亮 |
| `ui/ScopeScreen#filteredArchives` | 筛选函数：大小写不敏感，匹配**名称**（displayName，空则退到文件夹名）**或文件夹名**；空串 = 全量原样返回 |
| `ui/ScopeScreen`（命中/滚动） | 点击命中与滚轮翻页都改用筛选后的列表；"n / m" 计数 = 筛选后数量 |
| `ui/ScopeScreen`（输入通道） | `charTyped` / `keyPressed` 加第三个焦点通道（与重命名框、数据框并列）：Backspace 删字、Enter 确认（**保留筛选**、收起）、**Esc 取消（清空筛选并收起）**、Ctrl+V 粘贴（限长 48）；离开根页自动清焦点；左键点到框外先失焦（"焦点跟着点击走"） |
| 空态 | 区分"真没有存档"（原 `no_archives` 提示）与"筛掉了"（新 `logiscope.hint.arch_no_match`）—— 后者别让用户以为数据丢了 |
| lang | 新增 `arch_no_match`（中英 174 → **175** 键，仍一致） |

**一条值得归档的纪律**：筛选页的"渲染 / 命中 / 滚动"必须**共用同一个列表函数** ——
两套列表（显示的用筛选后、点击的用全量）就是"点 A 行打开 B 存档"的经典错位源；
共用一个函数 = 结构上不可能错位（同 CP9 之三"顺序 + 内容对齐"的思路）。

**行为细节（写清免得被当 bug）**：筛选变化（打字 / 删除 / 粘贴 / 清空）→ 回到列表顶部；
持久化 = 无（关界面重开回到全量）；Enter 保留筛选（先筛出来再逐个点开的用法），
Esc 才是清空。

离线：build / TestChart 443 条 / verify-jar 全过（lang 175 键一致）。
实机复测见 `VERIFY.md` 第十八轮③（F1–F8）。


## 第十八轮④：DATA 页「完成」按钮 + 刷新速率滑条（2026-10-15）

**需求**（用户原话）："在选格的 probe data 页面加一个 done 按钮，与选格页的 done 是一样的作用，
这样对于仅追踪 NBT 数据的方块实体探针，不需要 back 到选格页再 done 了，操作更流畅。
将 probe data 页面的数据刷新速率通过一个滑条做在 status ok 后面，目前是默认 20t 一次，
滑条提供 1~40t 和不刷新的档位选择，不需要每个探针隔离，整个游戏运行实例只有一个刷新速率就行，
同样这个速率支持进 config 修改。"

| 件 | 内容 |
|---|---|
| `ui/ScopeScreen`（按钮） | DATA 页底部加「完成」：**复用选格页同一个 chip id `done`** → 同一条 `commitPicker` 路径（覆写语义、置灰条件 `选中格子 or 已攒目标`、黄色配色全部自动一致）；`targets_hint` 文案改为"点下方「完成」直接开始（不必回选格页）" |
| `ui/ScopeScreen`（滑条） | 「status ok」同行右侧：轨道 + 已选段 + 拖钮 + 档位文字（`20t` / `不刷新`）；41 档 = 1..40t + 最右"不刷新"；点击即跳档、按住拖动（`mouseDragged` 跟、`mouseReleased` 收尾，拖出面板也继续跟手），悬停/拖动提亮 |
| `ui/ScopeScreen`（节拍） | `tickDataPage` 读全局档位：`rate > 0` 才自动重发；改小 / 从"不刷新"切回来时 `min(next, now+rate)` 把过期排期拉回来（不等旧节拍走完）。进页面仍立刻抓一次（"不刷新"= 不**自动**重发，手动「刷新」照常） |
| `LogiScopeConfig` | 新增 `dataPage.dataRefreshTicks`（0~40，默认 20）：滑条改动走 `set + save` 写回 toml（本次生效 + 下次启动保持）；`set`/`save` 语义从 NeoForge sources jar 核实（set 直接改内存值；restartType=NONE ⇒ get() 立即反映） |
| lang | 新增 `logiscope.data.rate_off`（不刷新 / off）；`targets_hint` 改口径（中英 175 → 176 键） |

**设计取舍（写下来省得再纠结）**：
- **Done 复用同一个 id 而不是新写一套**：两个页面各写一份提交逻辑必然漂移；
  共用 handler = "与选格页的 done 一样"不是"实现得像"，而是**字面上就是同一个**。
- **"不刷新"放最右档**：档位语义是"间隔"（越大越慢），最右 = 无穷大 = 不刷新；
  往左拖总在加速，不会误触停止。
- **单一真值源**：滑条与 config 不是两份状态 —— 滑条直接读写 config（`set()` 立即生效、
  `save()` 落盘），`tickDataPage` 每 tick 读。没有"UI 值 vs 文件值谁优先"的歧义。
- **全局一份、不随探针隔离**（用户明确要求）：它是"我多久看一眼数据"的形态偏好，
  不是每台设备各自的属性。

离线：build / TestChart 443 条 / BenchRender（台阶 99.3%・混合 63.8%）/ verify-jar 全过
（lang 176 键一致）。实机复测见 `VERIFY.md` 第十八轮④（N1–N9）。

## 未完成 / 待优化项

| 项 | 说明 | 出处 |
|---|---|---|
| 数据探针的 NBT 读取开销 | 现在每 tick 构建整份 NBT 再取一个值；可优化为"只取子标签 / 路径前缀预判"，接口不变（`DataProbe#readNumber` 一处） | CP20-6 |
| DATA 页路径即时校验 | 路径写错目前只表现为"曲线全 no-data"；拿到 NBT 树时可当场标红 | `NEXT-ROUND-DRAFT.md` E2 |
| 图例可滚动 / 可折叠 | 超过 3 行收成 `+N`；"一个槽位轮换十几种物品"的场景需要翻页 | ARCHITECTURE 待决问题 5 |
| 容器的键盘操作 | 面板打开时数字键 / `Q` / 中键无效（需要补 `hoveredSlot`） | 待决问题 3 |
| 采样降频 / `no_data` 密度 / 相对时间轴 | 三项设计题 | 待决问题 1 / 4 / 6 |
| 物品 NBT 维度 | 只记 itemId，不区分 NBT 不同的同名物品 | 待决问题 2 |
| 十六轮⑤ ~ 十八轮的实机复测 | 清单全部在 VERIFY.md；跑完按约定把通过项归档进 VERIFY-HISTORY | — |

## 已接受的限制（摘要）

完整表在 [`ARCHITECTURE.md`](ARCHITECTURE.md)「已知限制」一节；这里只列值得记住的：

| 项 | 说明 |
|---|---|
| 容器的**键盘**操作在面板打开时无效 | 数字键换物 / `Q` / 中键走 `hoveredSlot`，而背景层拿不到鼠标坐标。鼠标操作完全可用（CP8 之一 ~ 之四） |
| 用快捷键开界面的模组拿不到方块坐标 | vanilla 菜单不存坐标，只能靠右键事件推 → 关界面只能暂停 |
| 第三方搜索框需逐个加探针 | 目前只接了 JEI；第 3 层通用兜底能覆盖其余情况（代价：刚打完字要多按一次） |
| 图例超 3 行会收成 `+N` | 一个槽位轮换 7 种以上物品很罕见；把面板拖大即可 |
| 纵轴范围偶尔跳变 | 自动缩放的固有代价，已吸附整数刻度降低频率 |
| `LogiScopeConfig` 的注释未本地化 | NeoForge 的 `.comment()` 是静态字符串，不走语言文件 |

## 那些"看起来像 bug 但其实是设计"的现象

| 现象 | 为什么是对的 |
|---|---|
| 关界面后走远，`no-data` 开始增长 | 区块卸载了确实读不到。**该断的地方要断**（CP9） |
| 波形在某处空白一段 | 那是 `no_data` 或暂停 —— 留白比画成 0 诚实 |
| 换物品的那一瞬间线是断的 | 从 64 个铁变成 1 个铜，差值 −63 没有物理意义 |
| 同一种物品也会分成好几段 | 中间夹了 `no_data` 或暂停，连线等于编造数据 |
| 面板盖住容器时容器点不动 | 只有**面板之外**才转发给容器（CP8 之一） |
| 同 tick 一进一出时 IO 线显示 0 | 两个方向互相抵消，信息没有留在任何一次观测里（CP18） |

## 怎么继续（接手者看这里）

```bat
cd /d D:\dev\logiscope

:: 1) 编译 + 打包（JDK 17 已由 gradle.properties 的 org.gradle.java.home 锁定；
::    代理关闭时加 --offline，本机缓存完整）
gradlew.bat build

:: 2) 离线断言 + 渲染压测 + 产物验收（一键；逐条等价命令见 VERIFY.md §E）
dev-check\offline-check.bat

:: 3) 进游戏（dev 运行目录 = 1_20_1_neo\，与主线 run/ 隔离）
gradlew.bat runClient
```

改代码前**必读** [`ARCHITECTURE.md`](ARCHITECTURE.md)：先看第二节（接口与数据流程），
再看第三节（关键逻辑路径 CP1–CP27）中标为「高」风险的条目 —— 它们每一个都在实机上出过错。

---

