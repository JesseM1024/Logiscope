# -*- coding: utf-8 -*-
import io

# ================= VERIFY.md：重建为第十二轮清单 =================
p = "docs/VERIFY.md"
verify = """# LogiScope · 实机验证清单（第十二轮：探针随世界清空 + DATA 页三段式 + 悬浮高亮）

> **这一份只装"当前这一轮要测什么"。** 历轮结论、根因 → 修法、已接受的限制、
> 完整功能清单都在 [`VERIFY-HISTORY.md`](VERIFY-HISTORY.md)。
> 原理见 [`ARCHITECTURE.md`](ARCHITECTURE.md)（CP20 = NBT 取数探针）。

## 怎么跑

```bat
cd /d D:\\dev\\logiscope
gradlew.bat runClient
```

**准备**：
1. 两个单人存档 A / B（A 里建过探针）；
2. 一台 Mekanism 设备：能选容器格、NBT 里有能量/流体/化学品字段；
3. 存档 A 先用旧版本（或本版本）建一个探针。

---

## 一、本轮改了什么

| # | 需求 | 落地 |
|---|---|---|
| 1 | 探针跨存档残留 | **进世界即清空全部探针**（`ClientPlayerNetworkEvent.LoggingIn` → `ProbeManager#clearAll`）：探针绑定"这个世界里的这台设备"，跨世界留着只会是僵尸。NBT 显示的残留文本同刻清掉 |
| 2 | DATA 页改三段式；支持同时监测多个值 + 容器格 | 页面 = **NBT 原文 / 提取的值选择 / 追踪目标列表**。选择框（值 + 名称）后是「确认添加」：点击把目标攒进探针的追踪列表（可加多个，同路径去重）；目标列表里**值与名称锁定，只能删**（行尾 ×）。**不再"立即开始采样"**——回选格点「完成」后，容器格监测与数据追踪**一起**开始（同一个探针，同一条时间轴）。只加数据目标、不选格子也允许（纯数据探针） |
| 3 | 取值可视化联动 | 鼠标悬浮值列表某行 → 上半 NBT 原文中**对应字段整段高亮**（品红底色）。实现：`Snbt` 解析时给每个叶子记录源文本偏移，原文折行时逐行换算位置 |

---

## 二、复测项（★ = 直接对应本轮改动，优先测）

### M. 探针随世界清空（★）

| # | 操作 | 期望 |
|---|---|---|
| ★M1 | 存档 A 建探针（含数据目标）→ 退出 → 进存档 B → 打开面板 | 探针列表为**空**（A 的探针不见了）；无报错 |
| ★M2 | 退出到主菜单再进回存档 A | 同样为空（进世界就清，不留"上次会话"的残留） |
| ★M3 | 多人服务器退出重进 | 同样清空 |
| M4 | 清空后马上创建新探针、记录 | 一切正常（id 从头编号不影响使用） |

### N. DATA 页三段式（★）

| # | 操作 | 期望 |
|---|---|---|
| ★N1 | DATA 页布局 | 三段：NBT 原文（上）/ 提取的值（中）/ 追踪目标（下，带表头"追踪目标 · N"）；底部一行两格 + 「确认添加」按钮（替代原「开始采样」） |
| ★N2 | 选值+名称 → 点「确认添加」 | 目标出现在下方列表（`路径 → 名称`）；**值/名称格被清空**，方便连加多条 |
| ★N3 | 同一路径再添加一次 | 提示"该路径已在追踪中"，不重复添加 |
| ★N4 | 目标列表里的行 | 值与名称**不可编辑**；行尾 × 可删除；删到 0 条时探针回到待配置 |
| ★N5 | 加 2~3 个目标 + 选 1~2 个容器格 → 回选格点「完成」 | 图表页同时出现：容器格的物品线 **和** 每个 NBT 目标的线；`now` 行都显示 |
| ★N6 | 只加数据目标、不选格子 → 完成 | 纯数据探针照常记录（状态"跟踪中"） |
| N7 | 删除某条目标后 | 该线不再更新（已有的历史样本保留——它是记录，不是配置） |

### O. 悬浮高亮（★）

| # | 操作 | 期望 |
|---|---|---|
| ★O1 | 鼠标悬浮值列表某行 | NBT 原文中**对应字段**（含引号的字符串是整段）品红底色高亮 |
| ★O2 | 悬浮跨行的长值 | 每一段都在原文里高亮 |
| ★O3 | 移开鼠标 | 高亮消失 |
| O4 | 高亮时上下滚动原文 | 高亮跟着滚动位置走（不在视口内的部分自然看不见） |

### P. 回归

| # | 操作 | 期望 |
|---|---|---|
| P1 | 物品探针全流程（选格/图表/框选/标记/存档/回放/计算） | 与第十一轮④一致 |
| P2 | 数据目标与物品线同图时：图例（分半图标）/ 弹窗 / 「分析」计算 | 对数据线照常可用（它们就是普通 series） |
| P3 | 纯数据探针的存档并停止 / 回放 | 与物品探针一致 |

### H. 离线检查（不用开游戏）

```bat
cd /d D:\\dev\\logiscope
gradlew.bat build
javac -encoding UTF-8 -cp "build/classes/java/main;dev-check/lib/gson-2.10.1.jar" -d build/dev-check dev-check/TestChart.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main;dev-check/lib/gson-2.10.1.jar" TestChart
python verify-jar.py build/libs/logiscope-0.1.0.jar
```

| # | 检查 | 期望 |
|---|---|---|
| H1 | `gradlew.bat build` | `BUILD SUCCESSFUL` |
| H2 | `TestChart` | **267 条断言全过**（本轮新增 t27：SNBT 源偏移，7 条） |
| H3 | `verify-jar.py` | 全部通过 |

---

## 三、报问题时的三件套

1. **复现步骤**（哪个页面、几条目标、有没有选格子、A/B 存档还是多人）
2. **`run/logs/latest.log`**
3. **现象**：目标列表内容 / 高亮位置对不对 / 探针列表状态
"""
io.open(p, "w", encoding="utf-8", newline="\n").write(verify)

# ================= VERIFY-HISTORY：⑦结论 + ⑧行 =================
p4 = "docs/VERIFY-HISTORY.md"
s4 = io.open(p4, encoding="utf-8").read()
old4 = "| 第十一轮④ | 直读 NBT（单人免权限，命令降级为多人 fallback）+ 修复「开始采样」按钮 | **见 VERIFY.md（本轮）** | — |"
assert old4 in s4
s4 = s4.replace(old4, "| 第十一轮④ | 直读 NBT（单人免权限，命令降级为多人 fallback）+ 修复「开始采样」按钮 | **全部通过**（S1–S4 / J6 / K 组 / I1–I4） | 多值 + 容器格共存、跨存档清探针（第十二轮做） |\n| 第十二轮 | 探针随世界清空 + DATA 页三段式（确认添加 / 多目标 / 目标列表锁定）/ 悬浮高亮联动 | **见 VERIFY.md（本轮）** | — |")
io.open(p4, "w", encoding="utf-8", newline="\n").write(s4)

# ================= PROGRESS：第十二轮段落 =================
p3 = "docs/PROGRESS.md"
s3 = io.open(p3, encoding="utf-8").read()
anchor3 = "---\n\n## 已知问题 / 已接受的限制"
assert anchor3 in s3
sec = """---

## 第十二轮（探针随世界清空 + DATA 页三段式 + 悬浮高亮）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 探针跨存档残留（A 存档建的出现在 B 存档） | **`LoggingIn` → `ProbeManager#clearAll`**：探针绑定"这个世界里的这台设备"，实例表却是进程级的——进世界清空，僵尸探针在结构上不可能出现。`DataCommand` 残留文本同刻清 |
| 2 | DATA 页三段式：NBT 原文 / 值选择 / 追踪目标 | 「开始采样」改为「确认添加」：目标攒进探针的 `dataTargets` 列表（按 valuePath 去重；列表里**值与名称锁定只能删**）。回选格点「完成」后与容器格一起开始——**数据目标与物品槽位共存**（`sampleOnServerTick` 两段式：物品守卫 `lastSampledGameTime`、数据守卫独立的 `lastDataTick`，因为界面开着且下标未验证时物品走菜单路、数据仍走服务端直读，两条路节拍不锁相） |
| 3 | 悬浮高亮联动 | `Snbt` 每个叶子记录**源文本偏移**（`Node#start/end`），原文折行时记录每行起始偏移；悬浮值列表行 → 按偏移换算行列 → 原文对应字段画品红底色 |

**顺带的结构调整**：`ProbeInstance` 的单数 `dataTarget` → 复数 `dataTargets`
（COW 列表，服务端遍历 / 渲染增删）；`configureData` 删除（`addDataTarget` 取代）；
`configure` 允许"只有数据目标没有格子"（纯数据探针走 FOLLOWING，服务端直读采样）。

---
"""
s3 = s3.replace(anchor3, sec + "\n## 已知问题 / 已接受的限制", 1)
io.open(p3, "w", encoding="utf-8", newline="\n").write(s3)

# ================= ARCHITECTURE：CP20 增补 =================
p2 = "docs/ARCHITECTURE.md"
s2 = io.open(p2, encoding="utf-8").read()
a = s2.index("- **C20-6")
b = s2.index("\n\n---\n\n## 三、设计权衡", a)
addition = """- **C20-6 已知的性能取舍**：每 tick 构建整份 NBT 再取一个值（见 PROGRESS"待优化项"），
  接口不变、可局部替换。
- **C20-7 多目标与物品共存（第十二轮）**：一个探针 = 容器格物品监测 + **任意多个**
  NBT 数据目标（`dataTargets`，COW 列表）。服务端 tick 两段式采样：
  物品守卫 `lastSampledGameTime`（与菜单路互斥，旧语义不变）、数据守卫独立的
  `lastDataTick` —— 界面开着且下标未验证时物品走菜单路（客户端节拍）、
  数据仍走服务端直读，**两条路节拍不锁相，不能共用一把锁**。
- **C20-8 探针生命周期 = 世界**：`LoggingIn` 时 `clearAll`。探针绑定
  "这个世界里的这台设备"，实例表却是进程级的；不清就会出现"A 存档建的探针
  出现在 B 存档"的僵尸。配置类信息（数据目标）随实例一起消失——它们本来就
  只对那台设备有意义。"""
s2 = s2[:a] + addition + s2[b:]
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)

print("docs ok")
