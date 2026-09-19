# LogiScope · 实机验证清单

> **本轮（第十六轮④：NBT 槽位型取格）已全部通过 ✅**（2026-09-19，用户实测），
> 用例与结论已归档进 [`VERIFY-HISTORY.md`](VERIFY-HISTORY.md) 第四节。
>
> **这份文件只装"当前要测什么"** —— 下一轮需求开始时在这里重建新清单
> （素材见 [`NEXT-ROUND-DRAFT.md`](NEXT-ROUND-DRAFT.md)：A 正式目标 / B 待优化项 /
> C 未测项 / D 已接受的限制 / E 候选）。
> 当前基线 = `952b8fb`（第十六轮④ 修完并实测通过）。

## 一、仍未测 / 仍挂着的（历轮遗留，随时可捡）

| # | 项 | 说明 |
|---|---|---|
| D6 | 走远到区块卸载 → `no-data` 增长 | 第十轮遗留；用"渲染距离 2 + 走 5–10 区块"或 `/tp 2000 64 2000` |
| — | MEK 混合探针（既有菜单/坐标槽位、又有 NBT 锚点） | 第十六轮④ 只保证"纯锚点探针"自动进 NBT 模式；混合探针要靠手点按钮切（切了会把槽位那半边换掉）—— 目前**不建议**这么用 |
| — | 「读不到数据」与「用户想清空」 | 覆写语义下一格没解出来时界面什么都不做 —— 要清空只能去数据页逐个删。若觉得别扭，下轮可加一个显式的「清空目标」按钮 |

## 二、上一轮的结论摘要（第十六轮④）

| # | 场景 | 结果 |
|---|---|---|
| N1–N8 | 点格与覆写：只测勾上的格 / 自动回填 / 加勾减勾 / 一格不勾则不动 | ✅ |
| N9–N14 | 空格子靠位移、抽空→0、重现继续、换物新线、GUI 反复开关一致、装升级/放电池也能定格 | ✅ |
| R1–R2 | 普通容器、机械手等无 slot 列表的设备行为不变 | ✅ |
| R3–R4 | 物品探针全流程回归、英文环境文案补齐 | ✅ |

## 三、离线检查（不用开游戏，改完代码先跑这个）

```bat
cd /d D:\dev\logiscope
gradlew.bat build
javac -encoding UTF-8 -cp "build/classes/java/main;dev-check/lib/gson-2.10.1.jar" -d build/dev-check dev-check/TestChart.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main;dev-check/lib/gson-2.10.1.jar" TestChart
javac -encoding UTF-8 -cp build/classes/java/main -d build/dev-check dev-check/BenchRender.java
java  -Dfile.encoding=UTF-8 -cp "build/dev-check;build/classes/java/main" BenchRender
python verify-jar.py build/libs/logiscope-0.1.0.jar
```

| # | 检查 | 期望 |
|---|---|---|
| H1 | `gradlew.bat build` | `BUILD SUCCESSFUL` |
| H2 | `TestChart` | **336 条断言全过** |
| H3 | `BenchRender` | 台阶 ≥ 99%、混合 ≥ 60%（与第十三轮一致） |
| H4 | `verify-jar.py` | 全部通过 |

## 四、报问题时的三件套

1. **复现步骤**（哪台设备 / 点了几格 / 提示里那行字 / 目标表里有几条）
2. **`run/logs/latest.log`**
3. **面板上的关键数字**：信息行（`NBT 槽位型…`）、提示行、数据页目标列表（`items[slot=n]`）
