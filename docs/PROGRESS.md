# LogiScope · 目前进度

> 这是**滚动更新**的快照。每次做完一段实质工作就更新它。
> 目标定义见 `GOALS.md`，架构与风险见 `ARCHITECTURE.md`，验证步骤见 `VERIFY.md`。

**最后更新**：2026-09-18（第一轮实机验证后 → 第二轮修复）
**当前版本**：`0.1.0`
**当前里程碑**：**第一轮实机全组通过**（仅 1 个遗留问题 + 2 条增强）→ 第二轮改动已编译验收，
**待实机确认**

---

## 一、一句话状态

第一轮实机跑完了：**A–F 六组里 A/B/C/D/E 的核心行为全部成立**，
G1 / G1.5 / G3 的主要验收项都可以打勾。
暴露的遗留问题只有 1 个真 bug（JEI 搜索框仍会弹面板）和 2 条增强要求
（面板外点击应能操作容器、允许把面板拖出屏幕）。
这三件事的代码都已写完并编译验收，**行为待第二轮实机确认**。

---

## 二、第一轮实机结果（2026-09-18）

| 组 | 结果 |
|---|---|
| A 快捷键不打扰打字 | ⚠️ 原版背包搜索框 OK；**JEI 搜索框仍弹面板** |
| B 面板不遮挡 + 可拖动 | ✅ 通过，另提两条增强 |
| C 面板按钮 | ✅ 全部通过 |
| D 关界面后继续检测 | ✅ 单人 D1–D11 全通过（多人不作为当前目标） |
| E 回归 | ✅ 通过（E5 创造模式、E8 F1 已记录并处理） |
| F 观察项 | ✅ F1–F3 符合预期，F6 复现（属预期行为） |

逐条结果见 `VERIFY.md` 第一节。**结论：本轮的架构假设全部成立** ——
CP2（GUI 内按键）、CP8（图层栈不遮挡）、CP9（坐标跟踪）都按设计工作。

---

## 三、第二轮改动（按第一轮反馈）

### 3.1 JEI 搜索框仍会弹面板　`[x] 待验证`

**根因**：JEI 的搜索框（`mezz.jei.gui.input.GuiTextFieldFilter`）
**本身继承自 `EditBox`**，但它**不挂进 `screen.children()`**，
而是自己用 `createInputHandler()` 管输入。所以原来的
`screen.getFocused() instanceof EditBox` 根本看不到它。

**改法**：新建 `bind/TextInputGuard`，做**三层守卫**，任何一层命中就不抢键：

| 层 | 判据 | 覆盖范围 |
|---|---|---|
| 1 | `screen.getFocused()` / `children()` 里的 `EditBox`（+ 类名兜底） | 原版背包搜索框、大多数模组输入框 |
| 2 | **JEI 公开 API**：`IIngredientListOverlay#hasKeyboardFocus()` | JEI 搜索框（它不走第 1 层） |
| 3 | **`CharacterTyped` 的 Pre/Post 计数差** | 任何模组的任何输入框（通用兜底） |

第 2 层**用反射调用，零编译期依赖**（没装 JEI 就静静跳过），
入口是 `mezz.jei.common.Internal#getJeiRuntime`，之后全是 `mezz.jei.api.*` 的公开接口。
要给 REI / EMI 加支持，就在同一个类里再加一个小探针。

第 3 层是核实出来的通用技巧 —— `KeyboardHandler#charTyped` 的实现是：

```java
if (ClientHooks.onScreenCharTypedPre(...)) return;   // 被 cancel → 不发 Post
if (screen.charTyped(...)) return;                   // 被控件吃掉 → 不发 Post
ClientHooks.onScreenCharTypedPost(...);              // 只有没人接手才发 Post
```

所以 **Pre 比 Post 多一次 ⇒ 上一个字符被消费了 ⇒ 有输入框在接手**。
这条对任何模组都成立。代价是"刚打完字想开面板"时要多按一次 ——
而它**会自愈**：`]` 的字符如果没人吃，计数差立刻归零，下一次按键就正常。
（叠了 60 tick 时效窗口，避免很久以前打过一次字就永久抑制。）

### 3.2 面板外的点击应该能操作容器　`[x] 待验证`

**为什么要做**：用户明确说这是"以后图形化拖动、框选待测格"的基础 ——
面板挡住的只是屏幕一块区域，其余部分本来就该能用。

**改法**：`ScopeScreen` 把鼠标事件转交给被压住的容器界面：

| 事件 | 面板内 | 面板外 |
|---|---|---|
| `mouseClicked` | 标题栏拖拽 / 按钮 / 槽位列表 | 转发给 `parent.mouseClicked` |
| `mouseDragged` | 拖动面板 | 转发（原版用它做快速合成） |
| `mouseReleased` | 结束拖动 | 转发（**必须转**，否则容器的 quick-craft 状态会卡住） |
| `mouseScrolled` | 翻槽位列表 | 转发 |

**顺便修掉了一个一直存在的视觉缺陷**：背景容器界面的格子 hover **不高亮**。
原因是背景图层由 `ClientHooks#drawScreen` 渲染，它给背景层传的鼠标坐标是
`Integer.MAX_VALUE`（防止背景控件误高亮），而 `AbstractContainerScreen`
恰恰是在 `render` 里用这两个坐标算 `hoveredSlot` 的 → 永远是 null。
我们在最顶层，手里有真实坐标，所以**自己补画高亮** ——
需要的信息全部有公开访问器：`getGuiLeft()/getGuiTop()/getSlotColor(int)`、
`Slot#x/y/isActive()/isHighlightable()`、`renderSlotHighlight(...)`（public static）。

### 3.3 允许把面板拖出屏幕　`[x] 待验证`

**为什么**：GUI scale 调大时逻辑分辨率可能只有 320 宽，而面板固定 272 宽 ——
不推出去就永远挡着容器界面。

| 场景 | 规则 |
|---|---|
| 拖动中 | 左右至少留 `width/8`（最少 8px）可抓；向下至少留标题栏那一条"拖动条"；上方贴顶 |
| 重新打开 | 越界就**贴回屏幕边缘**（防止面板正好压在别的按钮底下再也点不到） |

### 3.4 顺手修掉：创造模式背包也会弹面板　`[x] 待验证`

第一轮 E5 记录：生存背包里按 `]` 正常（什么都不发生），
但**创造模式**里会弹面板。根因是创造背包不走 `player.inventoryMenu`，
它有自己的 `ItemPickerMenu`，于是 `containerMenu != inventoryMenu` 为真，
被误判成"外部设备"。

改法：在输入层排除 `EffectRenderingInventoryScreen`（生存背包与创造背包
共同的基类），一行判定，不必分别引用两个具体类。

---

## 四、验证记录

| 日期 | 项目 | 结果 |
|---|---|---|
| 2026-09-18 | `gradlew build`（首次） | ❌ 1 个编译错误：方法引用泛型推断失败 |
| 2026-09-18 | 修复：改用显式类型实参 `.<Event>addListener(...)` | ✅ |
| 2026-09-18 | `createMinecraftArtifacts` 复用缓存 | ✅ **1.24 秒**（首个项目同步骤 820 秒） |
| 2026-09-18 | `runClient` 第一轮实机 | ✅ A–E 核心行为成立；暴露 1 个 bug + 2 条增强 |
| 2026-09-18 | 第一轮四个问题的代码修复 | ✅ 构建 + `verify-jar.py` 通过 |
| 2026-09-18 | `runClient` 第二轮实机（第一轮修复的验证） | ✅ 见 `VERIFY.md` 第一节 |
| 2026-09-18 | 第二轮改动的代码实现（含 `TextInputGuard`） | ✅ `BUILD SUCCESSFUL` + `verify-jar.py` 全部通过 |
| — | `runClient` 第三轮实机（照 `VERIFY.md` 第二节） | ⬜ **未做** |

产物：`build/libs/logiscope-0.1.0.jar`，**48,228 字节**（21 个 class + 2 个 lang + 元数据）。

---

## 五、下一步（按优先级）

1. **照 `docs/VERIFY.md` 第二节走一轮**（★ 开头的是本次新行为）。重点是
   **A8/A9（JEI 搜索框）** 和 **B7–B9（面板外点击容器 + hover 高亮）**。
2. 把 `GOALS.md` 里剩余待验证项逐条打勾
3. 验证通过后提交并打标签：`git tag -a g1-panel-overlay`（见 `BACKUP.md`）
4. 然后进 G2：把火花线升级为带坐标轴的正规波形
   —— **B7 做完之后，"在波形上框选待测区间"这条路就通了**

---

## 六、已知问题 / 技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | `ScopeScreen.render` 每帧 `store.toList()`，`tick()` 每 tick `snapshot()` | 都会新建 List，样本几万条时是每秒 60 次分配 | G2 改成按需读取区间 |
| 2 | 只记 `itemId`，不区分 NBT 不同的同名物品 | 带附魔的书会被视为同一种 | 文档已写明；按需再改 |
| 3 | 面板打开时，容器的**键盘**操作（数字键换物、`Q` 丢弃、中键取方块）无效 | 这些走 `hoveredSlot`，背景层拿不到鼠标坐标 | CP8 已写明；要修得连 `hoveredSlot` 一起补（protected，需反射或换方案） |
| 4 | 用**快捷键**开界面的模组拿不到方块坐标 | 关掉界面后只能暂停 | CP9 已写明；无通用解法 |
| 5 | 单探针 | 无法同时对比多个格子 | G2 之后 |
| 6 | 无导出功能 | 数据出不了游戏 | G5 |
| 7 | `no_data` 每 tick 一条，密集断档会较快写满环形缓冲 | 长时间跟踪的覆盖时长变短 | 观察后再定，可能需要"断档合并" |

---

## 七、环境备忘

| 项 | 值 |
|---|---|
| 项目路径 | `D:\dev\logiscope` |
| 开发实例 | `<项目>\run\`；第三方模组放 `run\mods\`（当前有 JEI 19.21.2.313） |
| 构建命令 | `gradlew.bat build`（Windows）；Git Bash 下 `./gradlew` **不可用** |
| 运行命令 | `gradlew.bat runClient` |
| 跳过 Gradle 启动 | `gradlew.bat createLaunchScripts` → `build/moddev/runClient.cmd` |
| 产物验收 | `python verify-jar.py build/libs/logiscope-0.1.0.jar` |
| 资源缓存 | `~/.gradle/caches/neoformruntime/`（全局共享，无需重复下载） |
| API 核实 | `~/.workbuddy/skills/neoforge-api-probe`（查本地 sources jar） |
| 第三方 mod 反查 | `javap -p -cp run/mods/<mod>.jar <类名>`（本轮就是这么查出 JEI 的搜索框结构） |
| 排错参考 | 隔壁项目 `neoforge-1.21.1-walkthrough` 的 `WALKTHROUGH.md` |
