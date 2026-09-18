# LogiScope

**纯客户端物流探测示波器** —— 给模组机器装一台示波器。

在任意模组设备的容器界面里按 `]`，把某个容器格接到探头上，
逐游戏刻记录里面物品的种类与数量变化，并画成波形。

面板是一个**可以拖动的浮层**：它不会盖住原容器界面，你可以边看机器边看波形。
关掉容器界面之后记录也能继续（单人从方块实体读真值，多人记断档，跨空档的差值依然有效）。

> 全程只读客户端已同步的数据。**不发网络包、不需要服务端安装、不注册任何物品。**

---

## 快速开始

```bat
:: 构建
gradlew.bat build

:: 进游戏自测（会启动一个独立的开发客户端）
gradlew.bat runClient

:: 如果 Gradle 运行任务出问题，可生成脱离 Gradle 的启动脚本
gradlew.bat createLaunchScripts
::   → build\moddev\runClient.cmd
```

> ⚠️ **Windows 上请用 `gradlew.bat`，不要用 Git Bash 的 `./gradlew`**
> （cygpath 路径不匹配，会报 `ClassNotFoundException: GradleWrapperMain`）。
> Git Bash 下的替代写法见下方「构建备忘」。

**环境要求**

| 项 | 值 |
|---|---|
| JDK | 21（`org.gradle.java.home` 已锁定） |
| Gradle | 9.2.1（wrapper 自带） |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.209 |

---

## 文档导航（vibe coding 前请按顺序读）

| 文档 | 内容 | 什么时候读 |
|---|---|---|
| [`docs/VISION.md`](docs/VISION.md) | 项目方向、核心依据、**明确不做什么** | 开始任何新功能前 |
| [`docs/GOALS.md`](docs/GOALS.md) | G0–G6 分目标与**可验证的验收标准** | 想知道"下一步做什么" |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 分层、**9 条关键逻辑路径**、数据契约、**已核实的 API 清单** | **改代码前必读** |
| [`docs/VERIFY.md`](docs/VERIFY.md) | **实机验证清单**（逐条可打勾） | 每次改完要进游戏试 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 目前进度、已知问题、验证记录 | 每次开工前 |
| [`docs/BACKUP.md`](docs/BACKUP.md) | 回滚方案、检查点清单、灾难恢复、**本地 git 用法（含要不要上传 GitHub）** | 做有风险的改动前 |

**最短路径**：`ARCHITECTURE.md` → `PROGRESS.md` → 动手；改完 → `VERIFY.md`；提交前 → `BACKUP.md`。

> 版本控制是**纯本机的**（`D:\dev\logiscope\.git`），不需要联网也不需要账号。
> 详见 `docs/BACKUP.md` 第八节。

---

## 按键

| 键 | 作用 |
|---|---|
| `]` | 打开/收起示波器面板（可在「选项 → 控制」里改） |
| ESC | 收起面板（原容器界面会回来） |
| 鼠标拖动标题栏 | 移动面板 |

> 默认键位特意避开了 `O`/`I`/`E` 这类字母键 —— 它们在搜索框里是正文，
> 会跟"打字"打架。而且 `KeyMapping` 的匹配**不看修饰键**，
> 所以设成 `Ctrl+O` 也没用（单独按 `o` 照样命中）。详见 `ARCHITECTURE.md` CP2。

---

## 项目结构

```
logiscope/
├─ docs/                       设计文档（见上表）
├─ src/main/
│  ├─ java/com/jessetech/logiscope/
│  │  ├─ LogiScopeMod.java     主类：注册 7 个事件监听、持有唯一 Sampler
│  │  ├─ bind/                 输入层（快捷键 + TextInputGuard 三层打字守卫）+ 绑定层（坐标锚点 / 探针目标）
│  │  ├─ collect/              采集层（★ 三条取值路径都在这）
│  │  │     ContainerAccess    读菜单槽位（界面开着）
│  │  │     WorldProbe         读方块实体（界面关了，单人）
│  │  │     Sampler            状态机 + 每 tick 采样
│  │  ├─ store/                存储层（环形缓冲 + no_data 哨兵）
│  │  ├─ analysis/             分析层（纯函数，跳过 no_data）
│  │  └─ ui/                   呈现层（可拖动浮层面板，只读；向外转发鼠标给容器）
│  ├─ templates/               元数据模板（${} 占位符）
│  └─ resources/assets/        语言文件
├─ build.gradle                注意 filteringCharset = 'UTF-8' 那一行
├─ gradle.properties           版本号都在这里
└─ verify-jar.py               构建产物验收工具
```

---

## 构建备忘（踩过的坑）

| 现象 | 原因 | 处理 |
|---|---|---|
| `ClassNotFoundException: GradleWrapperMain` | Git Bash 下用了 `./gradlew` | 改用 `gradlew.bat`；或 `java -classpath "$(pwd -W)/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain <task>` |
| 游戏报 `File ... is not a valid mod file` | 元数据编码被破坏 | 检查 `build.gradle` 的 `filteringCharset = 'UTF-8'` 是否还在 |
| 构建报「非零退出码」但无输出 | Gradle 吞掉了子进程 stderr | 加 `--info`；`build.gradle` 里已配好输出转发 |
| PowerShell 里跑构建"退出码 1"但其实成功了 | PowerShell 把 Gradle 写到 stderr 的 JVM WARNING 当成 `NativeCommandError` | 看日志里的 `BUILD SUCCESSFUL`，别只看退出码；或 `... 2>&1 | Out-File 日志` 再读日志 |
| IDEA 同步后行为异常 | IDEA 会另起一个 Gradle 守护进程重新生成文件 | 同步后用 `python verify-jar.py` 复核一次 |

详细的排错记录见隔壁项目 `..\neoforge-1.21.1-walkthrough\WALKTHROUGH.md`，
那份文档把整个工具链的坑都记全了。

---

## 免责声明

本项目只读取客户端已经收到的数据，不修改游戏状态、不发送网络包。
但它**确实会观测其他模组的容器内容** —— 在多人服务器上使用前，
请自行确认该服务器的规则是否允许。

## 许可证

MIT
