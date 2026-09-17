# LogiScope · 目前进度

> 这是**滚动更新**的快照。每次做完一段实质工作就更新它。
> 目标定义见 `GOALS.md`，架构与风险见 `ARCHITECTURE.md`。

**最后更新**：2026-09-18 00:15
**当前版本**：`0.1.0`
**当前里程碑**：G0 完成 → G1 待验收

---

## 一、一句话状态

工程骨架已跑通并可打包；**最小闭环的代码已全部写完**，
但**还没有在游戏里实际验证过**（G1 的所有验收标准都未打勾）。
下一步就是在游戏里跑一遍，确认那条链路真的通。

---

## 二、已完成

### 工程基建 `[x]`
- 版本组合与本地实例严格对齐：MC **1.21.1** + NeoForge **21.1.209** + Gradle **9.2.1** + MDG **2.0.147**
- 编译 JDK 21（`org.gradle.java.home` 锁定，不受系统默认 JDK 25 影响）
- wrapper 复用已跑通的配置（含腾讯镜像）
- **`filteringCharset = 'UTF-8'` 已预先设好** —— 规避中文导致的元数据编码损坏
  （这个坑在隔壁 `neoforge-1.21.1-walkthrough` 项目里踩过一次，见其
  `WALKTHROUGH.md` 阶段 2.5）
- `options.encoding = 'UTF-8'` 已设
- 只声明 `client` 运行配置（纯客户端模组，不提供 server/data）

### 分层代码骨架 `[x]`
| 层 | 文件 | 状态 |
|---|---|---|
| 主类 | `LogiScopeMod` | 完成，`@Mod(dist=Dist.CLIENT)` |
| 输入层 | `bind/KeyBindings` | 完成，含 CP2 的 Screen 按键拦截 |
| 绑定层 | `bind/ProbeTarget` | 完成，运行时身份 |
| 采集层 | `collect/ContainerAccess` | 完成，CP1 基石 |
| 采集层 | `collect/Sampler` | 完成，状态机 + 每 tick 采样 |
| 存储层 | `store/Sample` / `store/SampleStore` | 完成，环形缓冲 |
| 分析层 | `analysis/Analyzer` | 完成，纯函数 |
| 呈现层 | `ui/ScopeScreen` | 完成，槽位选择 + 实时值 + 火花线 |

### 文档 `[x]`
`VISION.md`（方向与边界）/ `GOALS.md`（G0–G6 分目标）/ `ARCHITECTURE.md`
（分层 + 7 条关键路径 + **已核实的 API 清单**）/ `PROGRESS.md`（本文）/ `BACKUP.md`

---

## 三、进行中 `[~]`

- **G2 波形可视化**：目前只有「按列取峰值的火花线」，还没有坐标轴、
  时间刻度、类型切换断点标记。属于**能用但不够看**的状态。

---

## 四、下一步（按优先级）

1. **`gradlew.bat runClient` 进游戏验 G1** —— 最高优先级。
   这一步是**第一次真正验证 CP1/CP2/CP3/CP4/CP5 五条关键路径**，
   可能暴露设计假设的错误。**在这之前不要再往上加功能。**
2. 若 G1 通过，把 `GOALS.md` 的 G1 验收项逐条打勾，并更新本文第三节
3. 然后进入 G2：把火花线升级为带坐标轴的正规波形

---

## 五、已知问题 / 技术债

| # | 问题 | 影响 | 计划 |
|---|---|---|---|
| 1 | `ScopeScreen.render` 每次调用 `store.toList()` 新建 List | 样本几万条时每秒 60 次分配 | G2 改成按需读取区间 |
| 2 | 只记 `itemId`，不区分 NBT 不同的同名物品 | 带附魔的书会被视为同一种 | 在文档写明；按需再改 |
| 3 | 示波器用 `setScreen` 替换，会盖住原设备 GUI | 不能"边看机器边看波形" | G2 后考虑叠加绘制模式 |
| 4 | `Sampler.arm()` 只在打开界面时调用一次 | 状态机够用但不严谨 | G1 验证时一并观察 |
| 5 | 单探针 | 无法同时对比多个格子 | G2 之后 |
| 6 | 无导出功能 | 数据出不了游戏 | G5 |

---

## 六、验证记录

| 日期 | 项目 | 结果 |
|---|---|---|
| 2026-09-18 | `gradlew build`（首次） | ❌ 1 个编译错误：方法引用泛型推断失败 |
| 2026-09-18 | 修复：改用显式类型实参 `.<Event>addListener(...)` | ✅ 见下 |
| 2026-09-18 | `createMinecraftArtifacts` 复用缓存 | ✅ **1.24 秒**（首个项目同步骤 820 秒） |
| — | `runClient` 实机验证 G1 | ⬜ 未做 |

---

## 七、环境备忘

| 项 | 值 |
|---|---|
| 项目路径 | `D:\dev\logiscope` |
| 目标实例 | `...\.minecraft\versions\1.21.1-NeoForge\mods\` |
| 构建命令 | `gradlew.bat build`（Windows）；Git Bash 下 `./gradlew` **不可用** |
| 运行命令 | `gradlew.bat runClient` |
| 跳过 Gradle 启动 | `gradlew.bat createLaunchScripts` → `build/moddev/runClient.cmd` |
| 资源缓存 | `~/.gradle/caches/neoformruntime/`（全局共享，无需重复下载） |
| 排错参考 | 隔壁项目 `neoforge-1.21.1-walkthrough` 的 `WALKTHROUGH.md` |
