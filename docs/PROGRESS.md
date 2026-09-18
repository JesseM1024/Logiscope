# LogiScope · 目前进度

> 这是**滚动更新**的快照。每次做完一段实质工作就更新它。
> 目标定义见 `GOALS.md`，架构与风险见 `ARCHITECTURE.md`，验证步骤见 `VERIFY.md`。

**最后更新**：2026-09-18（第三轮实机后 → 第四轮修复）
**当前版本**：`0.1.0`
**当前里程碑**：**三轮实机后功能行为全部达成**；第四轮修掉 2 个根因 + 换默认键位，
**待第四轮实机确认**

---

## 一、一句话状态

第三轮实测的结果很好：**A–F 六组的功能全部通过，只暴露 2 个根因**
（一个是文本守卫的逻辑 bug，一个是手持物品缺渲染），另外把默认键位换成 `F9`
从根上消除打字冲突。三件事的代码都已写完、编译验收通过，**待第四轮实机确认**。

---

## 二、实机结果汇总

| 轮次 | 结果 | 遗留 |
|---|---|---|
| **第一轮** | A–E 核心行为全部成立，G1/G1.5/G3 主要验收项可打勾 | 1 真 bug（JEI 搜索框）+ 2 条增强 |
| **第二轮** | 修复项 A8/A9/A11 通过；B 组新增行为（鼠标转发、拖动边界）通过 | 守卫第 3 层过度拦截 |
| **第三轮** | **全部通过**，只剩 2 个根因 | 见下 |

逐条结果见 `VERIFY.md` 第一节。

---

## 三、第三轮暴露的 2 个根因（本轮已修）

### 3.1 文本守卫把快捷键永久锁死了　`[x] 待复验`

**现象**（A7/A10/A12 失败）：在 JEI 搜索框里打完字、点外面失焦之后，
**快捷键就再也打不开面板了**。

**根因**：`TextInputGuard` 第 3 层用了**累计计数**：

```java
// ❌ 错：被吃掉的字符会在差值上留下永久的 +1
private static int charPreCount, charPostCount;
private static boolean lastCharWasConsumed() { return charPreCount > charPostCount; }
```

我原来的推理是"不在输入框里按键时计数差自动归零"——**这是错的**。
一个被吃掉的字符给差值留下 **+1 之后，它永远不会消失**：之后每个未被吃掉的字符
同时给两个计数各 +1，差值只是原地保持。于是"打过一次字"就等于"永久打开文本模式"。

**修法**：只记**上一个**字符事件的结果，用一个布尔量：

```java
public static void onCharTypedPre(...)  { lastCharConsumed = true;  }  // 先假设被吃掉
public static void onCharTypedPost(...) { lastCharConsumed = false; }  // Post 来了 = 没人接手
```

因为 Pre/Post 在同一次调用里同步跑完，一个字符事件结束时它恰好等于
"这个字符有没有被吃掉"；而 `KeyPressed` 在 `charTyped` **之前**触发，
所以按键那一刻读到的是"上一个字符的结果"——正是我们要的语义。

> 教训：**"差值"型状态很容易写出"单向累积"的陷阱**。只有"每个事件都成对抵消"
> 的量才能用差值；只要存在"只加不减"的分支，差值就会单调增长。

### 3.2 手持物品不渲染　`[x] 待复验`

**现象**（B7/B8）：点物品能拿到光标上、操作逻辑全对，但**跟着鼠标的物品图标和数字不见了**。

**根因**：背景容器界面是用 `Integer.MAX_VALUE` 当鼠标坐标渲染的，
于是 `AbstractContainerScreen.render` 末尾那段"把 carried item 画在光标旁"的代码
把它画到了屏幕外。**和 hover 高亮是同一个根因**，只是上次只补了高亮、漏了它。

**修法**：在最顶层自己补画，复刻 vanilla 的 `renderFloatingItem`（它是 private）：

```java
ItemStack carried = bg.getMenu().getCarried();
pose.pushPose(); pose.translate(0,0,232);
g.renderItem(carried, mouseX - 8, mouseY - 8);
g.renderItemDecorations(countFont, carried, mouseX - 8, mouseY - 8);
pose.popPose();
```

顺手把**槽位悬浮提示**也补上了（vanilla 的 `renderTooltip` 同样依赖 `hoveredSlot`，
所以之前悬停格子既没高亮也没提示）。

**已知差异**：拖拽逐格分配时，vanilla 会把数字画成"剩余待分配量"
（`quickCraftingRemainder`，private，读不到），这里只能画完整数量。

### 3.3 默认键位换成 F9　`[x] 待复验`

用户的判断是对的：只要快捷键是**能被打出来的字符**，就总有一条路会和搜索框打架。
一路试过 `O` → `]`，两次都被咬到。**功能键根本不产生字符**，冲突通道从根上消失。

- 三层守卫（`TextInputGuard`）**保留**，保的是"用户把快捷键改回字母键"和
  "某些模组的输入框吃掉所有按键"这两种情况
- ⚠️ 旧绑定存在 `run/options.txt` 里并覆盖代码默认值 —— **已删掉那一行**，
  所以直接启动就是 F9

---

## 四、第二轮改动（回顾）

| 改动 | 关键点 |
|---|---|
| 三层文本守卫 | 原版 `EditBox` / JEI 公开 API（反射，零依赖）/ 字符被吃掉的通用兜底 |
| 面板外鼠标转发 | `AbstractContainerScreen#mouseClicked` 用 `findSlot(mx,my)` 现算坐标，不依赖 `hoveredSlot` |
| 背景 hover 高亮 | `getGuiLeft/getGuiTop/getSlotColor` + `renderSlotHighlight`（public static） |
| 面板可推出屏幕 | 拖动中留拖动条，重新打开贴回边缘 |
| 创造背包不再弹面板 | 排除 `EffectRenderingInventoryScreen` |

细节见 `VERIFY.md` 与 `ARCHITECTURE.md` CP2 / CP8。

---

## 五、验证记录

| 日期 | 项目 | 结果 |
|---|---|---|
| 2026-09-18 | `gradlew build`（首次） | ❌ 1 个编译错误：方法引用泛型推断失败 |
| 2026-09-18 | 修复：改用显式类型实参 `.<Event>addListener(...)` | ✅ |
| 2026-09-18 | `createMinecraftArtifacts` 复用缓存 | ✅ **1.24 秒**（首个项目同步骤 820 秒） |
| 2026-09-18 | `runClient` 第一轮 | ✅ 1 真 bug + 2 条增强 |
| 2026-09-18 | 第一轮修复的代码实现 | ✅ 构建 + `verify-jar.py` 通过 |
| 2026-09-18 | `runClient` 第二轮 | ✅ A8/A9/A11 通过；守卫第 3 层过度拦截 |
| 2026-09-18 | 第二轮修（`TextInputGuard` 等）的代码实现 | ✅ 构建 + 验收通过 |
| 2026-09-18 | `runClient` 第三轮 | ✅ **功能全部达成**；暴露 2 个根因 |
| 2026-09-18 | 第三轮 2 个根因 + 键位换 F9 | ✅ `BUILD SUCCESSFUL` + `verify-jar.py` 全部通过 |
| — | `runClient` 第四轮（照 `VERIFY.md` 第二节） | ⬜ **未做** |

产物：`build/libs/logiscope-0.1.0.jar`，**49,020 字节**（21 个 class + 2 个 lang + 元数据）。

---

## 六、下一步（按优先级）

1. **照 `docs/VERIFY.md` 第二节走一轮**。重点四条：
   - **K1**：选项里确认键位是 F9
   - **A7**：搜索框打完字 → 失焦 → 按 F9 —— 第三轮失败的那条
   - **B7R**：面板外点物品，**图标和数字要跟着鼠标** —— 第三轮缺渲染的那条
   - **B9T**：悬停格子应有高亮 **+ 悬浮提示**
2. 通过后把 `VERIFY.md` 的勾打上，提交并打正式标签
   `git tag -a g1-panel-overlay`（把 `wip-round2-20260918` 升格）
3. 然后进 **G2：把火花线升级为带坐标轴的正规波形** ——
   波形框选所需的鼠标转发基础（G1.6）已经就位

---

## 七、已知问题 / 技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | `ScopeScreen.render` 每帧 `store.toList()`，`tick()` 每 tick `snapshot()` | 都会新建 List，样本几万条时是每秒 60 次分配 | G2 改成按需读取区间 |
| 2 | 只记 `itemId`，不区分 NBT 不同的同名物品 | 带附魔的书会被视为同一种 | 文档已写明；按需再改 |
| 3 | 面板打开时，容器的**键盘**操作（数字键 / `Q` / 中键）无效 | 走 `hoveredSlot`，背景层拿不到鼠标坐标 | 鼠标侧已完全可用；要修需补 `hoveredSlot` |
| 4 | 拖拽分配时手持物品的数字是完整数量而非剩余量 | `quickCraftingRemainder` 是 private | 已接受，写进 VERIFY F8 |
| 5 | 用快捷键开界面的模组拿不到方块坐标 | vanilla 菜单不存坐标 | CP9 已写明；无通用解法 |
| 6 | 单探针 | 无法同时对比多个格子 | G2 之后 |
| 7 | 无导出功能 | 数据出不了游戏 | G5 |
| 8 | `no_data` 每 tick 一条，密集断档会较快写满环形缓冲 | 长时间跟踪的覆盖时长变短 | 观察后再定，可能需要"断档合并" |
| 9 | 第三方搜索框需逐个加探针 | 那些模组的搜索框不挂进 `screen.children()` | 换 F9 后优先级降低；通用兜底仍有效 |

---

## 八、环境备忘

| 项 | 值 |
|---|---|
| 项目路径 | `D:\dev\logiscope` |
| 默认键位 | **F9**（改默认值不会覆盖 `run/options.txt` 里的旧绑定，注意） |
| 开发实例 | `<项目>\run\`；第三方模组放 `run\mods\`（当前有 JEI 19.21.2.313） |
| 构建命令 | `gradlew.bat build`（Windows）；Git Bash 下 `./gradlew` **不可用** |
| 运行命令 | `gradlew.bat runClient` |
| 跳过 Gradle 启动 | `gradlew.bat createLaunchScripts` → `build/moddev/runClient.cmd` |
| 产物验收 | `python verify-jar.py build/libs/logiscope-0.1.0.jar` |
| 资源缓存 | `~/.gradle/caches/neoformruntime/`（全局共享，无需重复下载） |
| API 核实 | `~/.workbuddy/skills/neoforge-api-probe`（查本地 sources jar） |
| 第三方 mod 反查 | `javap -p -cp run/mods/<mod>.jar <类名>` |
| 排错参考 | 隔壁项目 `neoforge-1.21.1-walkthrough` 的 `WALKTHROUGH.md` |
