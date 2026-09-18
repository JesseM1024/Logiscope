# -*- coding: utf-8 -*-
import io

# ---------- PROGRESS.md ----------
p = "docs/PROGRESS.md"
s = io.open(p, encoding="utf-8").read()
anchor = "---\n\n## 已知问题 / 已接受的限制"
assert anchor in s
sec = """---

## 第十一轮③（NBT 取数探针：FE / 流体 / 化学品）

| # | 需求 | 落地 |
|---|---|---|
| 1 | 选格页新增「Probe data」按钮 | 实时抓 `data get block`（`DataCommand`：发命令 + 抓系统回执 + **吞掉回执**不刷聊天框），**每秒重发一次**保持"实时" |
| 2 | 权限态度 | 先查 `player.hasPermissions(2)`：没有就**不发**，界面直说"需要命令权限"（单人开作弊 / 服务器 OP）。而**采样本身不依赖命令**（服务端直读方块实体），所以"看不了但能采"是允许的 |
| 3 | 虚拟容器（一行两格） | DATA 页底部一行两格：**值/路径** + **名称**（可空）。格子可点击聚焦、直接打字，支持 Ctrl+V 粘贴长路径 |
| 4 | 从实时结果里选值与名称 | 把命令原文解析成"路径 = 值"列表（`analysis/Snbt` 纯解析器）：**左键数值** = 采样取了它；**左键文本 / 右键任意值** = 用它当追踪名称（流体 / 化学品这类可变物品：名称跟着 NBT 走，罐里换了东西 → 图上换一条线） |
| 5 | 电力这类"没名字"的量 | 名称手输即可（如 `energy`），也可以留空（自动用值路径当名字） |
| 6 | 逐 server tick 采样 | `ProbeInstance#configureData` + `sampleOnServerTick` 的数据分支：读方块实体 NBT（`BlockEntity#saveWithoutMetadata(registryAccess)`）→ 按路径取数 → 写 `Sample(tick, name, value)`。**与物品探针共用状态机 / 缓冲 / 标记 / 存档 / 回放 / 计算**，只是"值从哪来"不同 |
| 7 | 诚实性 | 方块实体没了 / 路径不存在 / 那个值不是数字 → 写 `no_data`（**不写 0**）；多人下没有集成服务端 → 停在 PAUSED，不假装在记录 |

**待优化项（本轮刻意不做，记录备查）**
- 现在每 tick 都 `saveWithoutMetadata()` 构建**整份** NBT，再按路径取一个值。
  可能省掉的量：读一次只取那个子标签（或缓存 Tag 引用 + 只在路径的父节点变化时重读）、
  以及"路径的**前缀**先对一遍"再决定要不要深挖。
  要动的是 `DataProbe#readNumber` 一处，接口不变。
- `data get block` 那一侧（界面实时显示）本来就是"整份 NBT"的命令语义，
  换成"只取对应值"没有原版命令可用 —— 所以那条路只能靠**采样侧**（上面的优化）绕开。

---
"""
s = s.replace(anchor, sec + "\n## 已知问题 / 已接受的限制", 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)

# ---------- ARCHITECTURE.md：CP20 ----------
p2 = "docs/ARCHITECTURE.md"
s2 = io.open(p2, encoding="utf-8").read()
anchor2 = "---\n\n## 三、设计权衡（已做的决策与理由）"
assert anchor2 in s2
cp20 = """---

### CP20 · NBT 取数探针（FE / 流体 / 化学品）　风险：中 ⚠️

**需求**：物品之外的量（能量、流体、化学品的量）也要能逐 tick 记录。

**两条路，分工明确**：

| 用途 | 路径 | 为什么 |
|---|---|---|
| **看一眼**（界面上实时显示 NBT） | 客户端发 `data get block <pos>`，抓系统回执 | 客户端手上没有完整 NBT（服务端只同步了界面/渲染要用的那部分），命令是原版唯一的"完整 NBT 出口" |
| **逐 tick 采样** | 服务端直读方块实体 NBT（`saveWithoutMetadata`）→ 按路径取数 | 不发命令、不依赖权限；值和时间戳同源（同 CP15 的纪律），没有回执丢包/异步的问题 |

**关键契约**：

- **C20-1 权限与功能解耦**：命令需要权限，**采样不需要**。所以"没有权限"只影响
  "看"，界面上如实说明（"需要命令权限"），而用户仍可手输路径开始采样。绝不用
  "读不到"假装成"没有流量"。
- **C20-2 名称的两条来源**：`DataTarget` 同时带 `namePath`（NBT 路径，用于流体/化学品
  这类**可变物品**：每 tick 解析一次，换了物质就是换了一条线）与 `literalName`
  （手输，用于"电力消耗"这类 NBT 里没有名字的量）。手输一旦发生就清掉 `namePath`
  （手输优先）。
- **C20-3 路径语法与界面同源**：用户点出来的路径就是采样用的路径
  （`a.b[0].c`，解析在 `Snbt#parsePath`）。**不硬编码任何模组键名** ——
  模组/版本改名不影响功能，用户点哪儿就采哪儿。
- **C20-4 读不到就写 no_data**：方块实体没了 / 路径不在 / 值不是数字 → `no_data`
  （绝不写 0）；多人（无集成服务端）→ PAUSED。
- **C20-5 与物品探针同构**：数据探针也是 `ProbeInstance`，所以图表 / 框选 / 标记 /
  存档 / 回放 / 计算**全部零改动**可用。
- **C20-6 已知的性能取舍**：每 tick 构建整份 NBT 再取一个值（见 PROGRESS"待优化项"），
  接口不变、可局部替换。

""" + "---\n\n## 三、设计权衡（已做的决策与理由）"
s2 = s2.replace(anchor2, cp20, 1)
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)

# ---------- VERIFY-HISTORY.md ----------
p3 = "docs/VERIFY-HISTORY.md"
s3 = io.open(p3, encoding="utf-8").read()
old = "| 第十一轮② | 回放计算（数量/IO 开关 + 运算选择：极值 / 简单统计 / 变化总量 / 变更区间，框选自动出结果） | **F1–F14 / G1–G2 / H1–H3 全部通过** | C6/C7（导出失败路径、空探针置灰）仍未测 |"
assert old in s3
s3 = s3.replace(old, old + "\n| 第十一轮③ | NBT 取数探针（Probe data 实时 NBT / 虚拟容器选值与名称 / 逐 server tick 采样 FE·流体·化学品） | **见 VERIFY.md（本轮）** | — |")
io.open(p3, "w", encoding="utf-8", newline="\n").write(s3)

print("docs ok")
