package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeConfig;
import com.jessetech.logiscope.analysis.Aggregator;
import com.jessetech.logiscope.analysis.NbtSlotLayout;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.DataTarget;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.store.MarkerList;
import com.jessetech.logiscope.store.Sample;
import com.jessetech.logiscope.store.SampleStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集层：**一个探针实例**——整个模组唯一「主动跑」的东西。
 *
 * 它会并存多个（"正在运行的探针是一个列表"），所以不变量是：
 *   · 每个实例持有**自己的**环形缓冲与下标验证（共享会互相覆盖读数）
 *   · 时钟与用户偏好是全局的（{@link ServerClock} / {@link ProbeManager}）
 *
 * 状态机（**由客户端 tick 驱动**——它管的是"界面开没开、源还在不在"这类客户端事实）：
 *
 *   ARMED ──configure()──► RECORDING ◄──────────┐
 *     ▲                       │  ▲               │
 *     │                       │  └── FOLLOWING ──┘
 *     │                       │        │
 *     └──clearSlots()─────────┴────────┴──► PAUSED
 *
 * ★★ 采样时钟 = 世界 tick 本身（第十轮第四次修复）★★
 *   这一版把采样的驱动**从客户端 tick 搬到了服务端 tick**，根因层面解决
 *   "跨过去"和"1t 假平台"：
 *
 *   | | 旧做法（客户端 tick 驱动 + 异步读） | 现在（服务端 tick 驱动 + 同步读） |
 *   |---|---|---|
 *   | 采样节拍 | 客户端 20 次/秒，与服务端 tick **不锁相** | **服务端 tick 本身** |
 *   | 时间戳 | 异步读回来的 world tick（会跳 0 / 跳 2） | 那一刻 `ServerLevel#getGameTime()`，**一个 tick 一条** |
 *   | 值 | 缓存里的旧值可能被复用（贴上新 tick 标签 → 假平台） | 同步读，读到的就是这个世界 tick 的值 |
 *   | `/tick rate 200` | 客户端只能观测 20 次/秒 → 每 ~10 tick 一个点 | **200 条/秒**（服务端 tick 200 次） |
 *
 *   落点是 NeoForge 的 `ServerTickEvent.Post`（在 `tickChildren` 之后发，
 *   读到的就是这一 tick 结束时的状态），由 {@link ProbeManager} 转给
 *   {@link #sampleOnServerTick}。
 *
 * 【两种采样来源，按"哪条路更准"选】
 *
 * | 场景 | 来源 | 为什么 |
 * |---|---|---|
 * | 单人 + 有坐标 + （下标已验证 或 界面关着） | **服务端 tick**（每 tick 一条真值） | 最准：直接读方块实体 |
 * | 界面开着、但下标映射没验证出来（模组机器歧义） | 客户端菜单（`ContainerAccess`） | 菜单槽**就是用户点的那一格**；服务端那侧只能靠猜（MEK 的下标硬编码 0） |
 * | 多人 | 客户端菜单 / 记 `no_data` | 方块实体不在客户端手里 |
 *
 * 【多格监控：一台设备上可以同时监控好几格，记录是聚合的】
 *   每 tick 把各格内容**按物品求和**（规则见 {@link Aggregator}），
 *   **每个出现的物品各写一条样本**（同一个 tick 值）：
 *
 *   | 两个被监控的格子 | 这个 tick 写几条、内容是什么 |
 *   |---|---|
 *   | 都是 64 铁 | 1 条：铁 × 128 |
 *   | 64 铁 + 32 铜 | 2 条：铁 × 64、铜 × 32（图上两条不同颜色的线） |
 *   | 都是空 | 1 条：air × 0（那条灰蓝的"被抽空了"线） |
 *
 * 【PAUSED 的两种来源，行为不同】
 *   manualPause = true   用户按了「暂停」→ 不自动恢复，只能手动继续
 *   manualPause = false  数据源自己失效了 → 一旦数据源回来（重开同一台设备）自动恢复
 *
 * 【时间轴：只写真的发生过的事】
 *   · **不补值**：某一 tick 没读到就什么都不写（画图层按观测连续性连线）
 *   · **不重复**：同一个世界 tick 只写一遍（`advanceTick`）
 *   · 读不到时写 `no_data`；暂停时写 `gap` 标记（恢复后曲线不会连起来）
 *
 * 【线程】：客户端 tick 在渲染线程，服务端 tick 在服务端线程 —— 两条路都会写
 * `store` 与聚合缓冲，所以用 {@link #sampleLock} 互斥（同一个实例同一时刻只有一条路在写）。
 */
public final class ProbeInstance {

    public enum State {
        /** 已创建但还没选格（或者用户把格全清了）—— 不采样 */
        ARMED,
        RECORDING,
        FOLLOWING,
        PAUSED
    }

    private final int id;
    private final ProbeManager manager;
    /** 容量来自配置（`bufferSamples`，默认 72000 条样本）；**改了会热切换**，见 ensureCapacity */
    private final SampleStore store = new SampleStore(LogiScopeConfig.bufferSamples());
    private final WorldProbe probe = new WorldProbe();
    /**
     * 波形标记（G4）。★ 独立于环形缓冲 ★ —— 缓冲写满滚动时标记不会被覆盖掉
     * （用户标记"这里开始出问题"，十分钟后标记必须还在）。随探针实例走：
     * 重新选格（configure）/ 清空记录（clearData）/ 删除实例（dispose）时一并清空。
     */
    private final MarkerList markers = new MarkerList();

    /**
     * 数据探针的目标（第十一轮③）：**非 null = 这是一个"从 NBT 取数"的探针**。
     *
     * 与物品探针共用同一套状态机、缓冲、标记与导出 —— 区别只在"值从哪来"：
     *   · 物品探针：菜单槽 / 方块实体的 `ItemHandler` 能力（按槽位读）
     *   · 数据探针：服务端直接读方块实体 NBT，按用户点出来的路径取一个数
     *     （FE 能量 / 流体重 / 化学品量，以及任何在 NBT 里的数字）
     * 这样"图表、框选、导出、回放、计算"全都不需要为新数据源改一行。
     */
    private volatile List<DataTarget> dataTargets = List.of();
    private final DataProbe dataProbe = new DataProbe();

    /** 数据采样的独立节拍守卫（与物品的 {@link #lastSampledGameTime} 分开：
     *  界面开着且下标未验证时，物品走菜单路（客户端节拍）、数据走服务端 tick，
     *  两者要在同一个世界里各写各的，不能共用一把锁） */
    private volatile long lastDataTick = Long.MIN_VALUE;

    /** 采样互斥：客户端线程（菜单路径）与服务端线程（tick 路径）都可能写样本 */
    private final Object sampleLock = new Object();

    // 这两个字段会被**服务端线程**读（采样时判断该不该采、采哪里），所以 volatile
    private volatile ProbeTarget target = ProbeTarget.EMPTY;
    private volatile State state = State.ARMED;

    /** 客户端侧的状态：当前盯的是哪个菜单（界面重开后会换 ID） */
    private int watchedContainerId = -1;

    /** 暂停是不是用户主动按的（决定能否自动恢复） */
    private boolean manualPause;

    /** 上一次写样本的世界 tick（两个采样路径共用，保证同一个 tick 不写两遍） */
    private volatile long lastSampledGameTime = Long.MIN_VALUE;

    /** "下标不可信"是否已经警告过。每个探头只警告一次，别跑马灯。 */
    private boolean warnedUnverifiedIndex;

    /** 上一次给玩家发动作栏提示的 tick，用于去抖 */
    private long lastNotifyTick = Long.MIN_VALUE / 2;

    // 聚合用的复用缓冲（在 sampleLock 内使用，避免每 tick 造 List）：
    //   buf* 是**输入**（各格原本的物品与数量）
    //   agg* 是**输出**（按物品分别求和之后的条目：每个出现的物品一条）
    private String[] bufIds = new String[8];
    private int[] bufCounts = new int[8];
    private String[] aggIds = new String[8];
    private int[] aggCounts = new int[8];

    ProbeInstance(int id, ProbeManager manager) {
        this.id = id;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // 只读访问
    // ------------------------------------------------------------------

    public int id() {
        return id;
    }

    public SampleStore store() {
        return store;
    }

    /** 波形标记（G4）：独立于环形缓冲，右键波形加/删，随记录一起导出 */
    public MarkerList markers() {
        return markers;
    }

    /** 有没有配置 NBT 数据追踪（可以与物品槽位**共存**） */
    public boolean isDataProbe() {
        return !dataTargets.isEmpty();
    }

    /** 生效中的数据追踪目标（不可变快照；界面增删走 {@link #addDataTarget} / {@link #removeDataTarget}） */
    public List<DataTarget> dataTargets() {
        return dataTargets;
    }

    /**
     * 添加一个数据追踪目标（DATA 页的「确认添加」）。
     *
     * ★ 与物品槽位共存 ★ 同一个探针可以"既监测容器格里的物品，又追 NBT 里的
     * 能量 / 流体 / 化学品"—— 它们写进同一条扁平时间轴（物品按物品名、数据按
     * 追踪名），图表 / 框选 / 存档 / 计算对它们一视同仁。
     *
     * @return false = 同名 valuePath 已存在（去重，界面提示"已添加"）
     */
    public boolean addDataTarget(DataTarget t) {
        synchronized (sampleLock) {
            for (DataTarget d : dataTargets) {
                if (d.valuePath().equals(t.valuePath())) {
                    return false;
                }
            }
            List<DataTarget> n = new ArrayList<>(dataTargets);
            n.add(t);
            dataTargets = List.copyOf(n);
            // 刚加目标时如果还停在 ARMED（没选格也没目标），顺手进入可采样状态
            if (state == State.ARMED && integratedServerUp()) {
                enterFollowing(false);
            }
            return true;
        }
    }

    /** 删除一个数据追踪目标（DATA 页目标列表行尾的 ×）。@return 是否真的删了 */
    public boolean removeDataTarget(String valuePath) {
        synchronized (sampleLock) {
            List<DataTarget> n = new ArrayList<>(dataTargets);
            boolean gone = n.removeIf(d -> d.valuePath().equals(valuePath));
            if (gone) {
                dataTargets = List.copyOf(n);
                // 最后一个目标也没了、又没有槽位 → 回到待配置，不留一个空转的探针
                if (dataTargets.isEmpty() && !hasSlots()) {
                    state = State.ARMED;
                }
            }
            return gone;
        }
    }

    /** 有东西可看（物品探针看槽位，数据探针看目标） */
    public boolean hasAnything() {
        return hasSlots() || isDataProbe();
    }

    public ProbeTarget target() {
        return target;
    }

    public State state() {
        return state;
    }

    public boolean isManuallyPaused() {
        return manualPause;
    }

    public boolean hasSlots() {
        return !target.slots().isEmpty();
    }

    /**
     * 列表页显示用的名字：**这台设备（方块）的名字**。
     *
     * 用户实测反馈：原来只有"槽 0,1 · overworld 10,64,10"，机器一多就分不清谁是谁。
     * 设备名是在建实例 / 选格完成时从方块状态里取的（{@code ProbeContext#blockName}）。
     * 拿不到（例如异常路径）就退回一个通用名 —— 坐标永远在描述里，不会真的认不出来。
     */
    public String name() {
        String d = target.deviceName();
        if (d != null && !d.isBlank()) {
            return d;
        }
        return Component.translatable("logiscope.label.probe").getString();
    }

    /** 列表页显示用的一行描述（槽位 + 维度坐标；带数据追踪时标注条数） */
    public String describe() {
        String base;
        if (!hasSlots()) {
            DataTarget first = dataTargets.isEmpty() ? null : dataTargets.get(0);
            base = first == null ? target.anchorText()
                    : first.valuePath() + " · " + first.anchorText();
        } else {
            base = target.slotSummary() + " · " + target.anchorText();
        }
        int n = dataTargets.size();
        return n > 0 ? base + " · " + Component.translatable("logiscope.info.data_count", n).getString() : base;
    }

    // ------------------------------------------------------------------
    // 配置 / 生命周期
    // ------------------------------------------------------------------

    /**
     * 用"选格页点完成"的结果配置这个实例并开始记录。
     *
     * @param verifyViews 与 {@code target.slots()} 同序的菜单快照；无菜单路径时传 null。
     *                    只用于在服务端做下标指纹验证（第六轮 M4）。
     */
    /** 客户端手上有集成服务端（单人）—— 只有这种局面下才读得到方块实体 */
    private static boolean integratedServerUp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.isLocalServer() && mc.getSingleplayerServer() != null;
    }

    public void configure(ProbeTarget newTarget, @Nullable List<ContainerAccess.SlotView> verifyViews) {
        // 锁内做：服务端 tick 那侧可能正在用旧目标写样本，不能让它落到新会话里
        synchronized (sampleLock) {
            this.target = newTarget;
            this.watchedContainerId = ContainerAccess.currentContainerId();
            this.store.clear();
            this.markers.clear();
            this.manualPause = false;
            this.warnedUnverifiedIndex = false;
            this.lastSampledGameTime = Long.MIN_VALUE;
            this.probe.reset();

            if (newTarget.slots().isEmpty() && dataTargets.isEmpty()) {
                this.state = State.ARMED;
                return;
            }
            if (newTarget.hasPersistentId()) {
                this.probe.verifyIndex(newTarget, verifyViews);
            }
            if (menuSourceReady()) {
                this.state = State.RECORDING;
            } else if (newTarget.hasPersistentId()) {
                enterFollowing(false);
            } else {
                // 既没有菜单路径、又拿不到坐标：只能明确停住，不假装在记录
                this.state = State.PAUSED;
                manualPause = false;
            }
        }
    }

    /**
     * 配置里的缓冲容量改了就跟上（**保留最新样本**）。
     * 由 {@link ProbeManager} 每客户端 tick 调一次 —— 便宜到可以忽略（一次整数比较）。
     */
    void ensureCapacity() {
        int want = LogiScopeConfig.bufferSamples();
        if (store.capacity() != want) {
            synchronized (sampleLock) {
                store.resize(want);
            }
        }
    }

    /** 清空已记录的数据，但不动探针和状态 */
    public void clearData() {
        synchronized (sampleLock) {
            store.clear();
            markers.clear();
            lastSampledGameTime = Long.MIN_VALUE;
        }
    }

    /** 把格子全清掉（面板上的「清空选择」并确认后走到这里）→ 回到待选格 */
    public void clearSlots() {
        synchronized (sampleLock) {
            this.target = ProbeTarget.EMPTY;
            this.watchedContainerId = -1;
            this.manualPause = false;
            this.warnedUnverifiedIndex = false;
            this.probe.reset();
            this.state = State.ARMED;
        }
    }

    /**
     * 销毁前的收尾：丢掉缓冲与全部缓存（"删除按钮 = 停止监测 + 删除这个实例的缓存"）。
     * 由 {@link ProbeManager#remove} 调用。
     */
    void dispose() {
        synchronized (sampleLock) {
            store.clear();
            markers.clear();
            probe.reset();
            target = ProbeTarget.EMPTY;
            dataTargets = List.of();
            lastDataTick = Long.MIN_VALUE;
            state = State.ARMED;
            manualPause = false;
        }
    }

    /** 主动暂停（用户按的） */
    public void pause() {
        if (state == State.RECORDING || state == State.FOLLOWING) {
            manualPause = true;
            state = State.PAUSED;
            // 标记与"服务端那侧的最后一次采样"必须串行：先让在飞的采样写完，再落标记，
            // 否则标记会落在样本之前，断口又会被连起来
            synchronized (sampleLock) {
                writeGapMarker();
            }
        }
    }

    /** 手动继续。数据源不可用时保持 PAUSED，不假装在记录。 */
    public void resume() {
        manualPause = false;
        if (state == State.PAUSED) {
            resumeFromPause();
        }
    }

    public void togglePause() {
        if (state == State.RECORDING || state == State.FOLLOWING) {
            pause();
        } else {
            resume();
        }
    }

    /**
     * 从 PAUSED 尝试恢复：优先回到"界面还在"的 RECORDING，否则回到 FOLLOWING。
     * 两者都不成立就停在 PAUSED —— 这是刻意的，不要为了"看起来在工作"而记假数据。
     */
    private void resumeFromPause() {
        if (menuSourceReady()) {
            state = State.RECORDING;
            return;
        }
        if (manager.followAfterClose() && target.hasPersistentId()) {
            enterFollowing(true);
        }
    }

    // ------------------------------------------------------------------
    // 采样①：客户端 tick（状态机 + 菜单路径 / 多人的回退）
    // ------------------------------------------------------------------

    /** 由 {@link ProbeManager} 每客户端 tick 调一次（渲染线程） */
    void tick(long gameTime) {
        // 纯数据探针（没选格子）：采样全在服务端 tick 那条路上，这里只管状态回落。
        // 带槽位的探针即使也有数据目标，状态机照常跑（物品那半边是它的主路径）。
        if (isDataProbe() && target.slots().isEmpty()) {
            if (state == State.RECORDING && !integratedServerUp()) {
                pauseAuto();
            }
            return;
        }
        if (state == State.ARMED || target.slots().isEmpty()) {
            return;
        }
        switch (state) {
            case RECORDING -> tickRecording(gameTime);
            case FOLLOWING -> tickFollowing(gameTime);
            case PAUSED -> tickPaused();
            default -> {
            }
        }
    }

    /** 界面开着 */
    private void tickRecording(long gameTime) {
        if (!ContainerAccess.hasForeignContainerOpen()) {
            // 玩家关掉界面了 → 转坐标跟踪（单人下从此由服务端 tick 采样，一个 tick 都不缺）
            if (manager.followAfterClose() && target.hasPersistentId()) {
                enterFollowing(true);
            } else {
                pauseAuto();
            }
            return;
        }

        if (ContainerAccess.currentContainerId() != watchedContainerId && !tryRebind()) {
            // 换了另一台设备，且认不出是我们跟踪的那台
            pauseAuto();
            return;
        }

        if (serverSideSampling()) {
            return;   // 服务端 tick 钩子在采（更准、每 tick 一条），这里不插手
        }

        List<ContainerAccess.SlotView> views = readMenuSlots();
        if (views == null) {
            // 有格子读不到（槽位下标越界 = 换了不同类型的菜单）
            pauseAuto();
            return;
        }
        synchronized (sampleLock) {
            if (!advanceTick(gameTime)) {
                return;
            }
            writeTick(gameTime, views);
        }
    }

    /**
     * 选中的槽位里 menuSlotIndex 是否有重复。
     * 有重复 = 光看下标无法区分是哪一格（MEK 这类"全是 #0"的容器）→ 提示；
     * 无重复 = 下标与格子一一对应，采样结果本来就正确 → 不提示。
     */
    private static boolean hasDuplicateMenuIndex(ProbeTarget t) {
        List<ProbeTarget.SlotRef> slots = t.slots();
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                if (slots.get(i).menuSlotIndex() == slots.get(j).menuSlotIndex()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 界面关着 */
    private void tickFollowing(long gameTime) {
        // ★ 下标映射不可信的机器（MEK 这类自写槽位类的模组），必须明说 ★
        // 验证是挂探头那 1~2 tick 内在服务端做的（内容指纹匹配），
        // 匹配有歧义（例如对着空格子挂探头）就到此为止 —— 不瞎猜，让用户知道。
        // force：警告紧跟"转为坐标跟踪"，普通去抖会把它吞掉（第七轮 N3）。
        // 第十三轮②：只在"序号有重复"时提示 ——
        // MEK 那类容器每格的 menuSlotIndex 都是 0（自写槽位类），映射必然有歧义，
        // 必须让用户知道；普通容器序号互不相同，按下标就能唯一对上、采样本来是对的，
        // 这时再弹"下标不可信"就是误报（用户实测：点不同序号的空格子也会看到它）。
        if (!warnedUnverifiedIndex
                && probe.mappingStatus(target) == WorldProbe.MappingStatus.UNVERIFIED
                && hasDuplicateMenuIndex(target)) {
            warnedUnverifiedIndex = true;
            notifyPlayer("logiscope.msg.index_unverified", true);
        }

        if (ContainerAccess.hasForeignContainerOpen()) {
            // 玩家又把界面打开了 —— 优先回到最准的那条路径
            if (ContainerAccess.currentContainerId() == watchedContainerId || tryRebind()) {
                state = State.RECORDING;
                return;
            }
            // 开的是别的容器：继续按坐标跟踪，不打断
        }

        if (serverSideSampling()) {
            return;   // 服务端 tick 钩子在采
        }

        // 回退路径（多人）：方块实体不在客户端手里，如实记"读不到"
        synchronized (sampleLock) {
            if (!advanceTick(gameTime)) {
                return;
            }
            store.add(Sample.noData(gameTime));
        }
    }

    /** 暂停中：只有"自动暂停"才允许数据源自己回来时恢复 */
    private void tickPaused() {
        if (manualPause) {
            return;
        }
        if (menuSourceReady()) {
            state = State.RECORDING;
            return;
        }
        if (manager.followAfterClose() && target.hasPersistentId()
                && !ContainerAccess.hasForeignContainerOpen()) {
            enterFollowing(false);
        }
    }

    // ------------------------------------------------------------------
    // 采样②：服务端 tick（单人下的主路径 —— 一个世界 tick 一条真实样本）
    // ------------------------------------------------------------------

    /**
     * ★ 由服务端 tick 事件调用（**服务端线程**）★
     *
     * 每世界 tick 调一次。它同时是"时间戳"和"取值时机"的答案：
     * 时间戳取那一刻的 `ServerLevel#getGameTime()`，值取那一刻方块实体里的真值 ——
     * 两者同源，所以既不会漏观测也不会把旧值当新值。
     *
     * @return true = 这一 tick 已经写了样本（数据或 `no_data`）
     */
    boolean sampleOnServerTick(MinecraftServer server) {
        State st = state;
        if (st != State.RECORDING && st != State.FOLLOWING) {
            return false;
        }
        ProbeTarget t = target;
        List<DataTarget> dts = dataTargets;
        boolean hasData = !dts.isEmpty();
        boolean hasItems = !t.slots().isEmpty() && t.hasPersistentId();
        // 物品那半边的"可服务端采样"条件（与旧逻辑一致：界面开着且下标没验证时，
        // 物品交给客户端菜单路 —— 但**数据目标不受此限**，它们只认服务端直读）。
        boolean itemsReady = hasItems
                && (st == State.FOLLOWING || probe.mappingStatus(t) == WorldProbe.MappingStatus.VERIFIED);
        if (!hasData && !itemsReady) {
            return false;
        }
        // 同一台设备：维度取槽位的（没有槽位就取数据目标的）
        ResourceKey<Level> dim = t.hasPersistentId() ? t.dimension() : dts.get(0).dimension();
        ServerLevel level = server.getLevel(dim);
        if (level == null) {
            return false;
        }
        long gameTime = level.getGameTime();
        synchronized (sampleLock) {
            // ★ 锁内复查状态 ★（暂停 / 删除后绝不能再用旧目标写样本）
            State now = state;
            if (now != State.RECORDING && now != State.FOLLOWING) {
                return false;
            }
            // ---- 物品那半边（守卫：lastSampledGameTime，与菜单路互斥，旧语义不变）----
            if (itemsReady && advanceTick(gameTime)) {
                List<ContainerAccess.SlotView> views = probe.readOnServer(level, t);
                if (views == null) {
                    store.add(Sample.noData(gameTime));
                } else {
                    writeTick(gameTime, views);
                }
            }
            // ---- 数据那半边（守卫：lastDataTick，独立于物品——两条路的节拍不锁相）----
            if (hasData && lastDataTick != gameTime) {
                lastDataTick = gameTime;
                // ★ 先攒齐、再按名称合并（第十二轮③修）★
                // 两个目标解析出同一个名字（同一物品装了两罐：Items[0].id == Items[1].id）
                // 时必须**求和**成一条样本 —— 否则同 tick 同名两条样本，图表会在
                // 两个值之间来回画（用户实测的"震荡"）。与物品路径的 writeTick 同规。
                // 读不到的目标这一 tick **留空**（它的线断开，不写 0 连累别的目标）；
                // 一个都没读到才写整 tick 的 no_data。
                int want = dts.size();
                if (bufIds.length < want) {
                    bufIds = new String[want];
                    bufCounts = new int[want];
                    aggIds = new String[want];
                    aggCounts = new int[want];
                }
                int got = 0;
                for (DataTarget dt : dts) {
                    if (dt.isSlotAnchor()) {
                        // ★ NBT 槽位目标（第十六轮）★：只认 NBT 里的 slot 号，
                        // 与菜单/GUI 状态无关 —— 于是不会再有"开界面 2 个、关界面 8 个"
                        NbtSlotLayout.Entry e = dataProbe.readSlotAnchor(level, dt);
                        if (e == null) {
                            continue;   // 设备读不到 —— 留空（不写 0，也不连累别的目标）
                        }
                        String name = e.itemId() != null && !e.itemId().isBlank()
                                ? e.itemId()
                                : dt.fallbackName();
                        bufIds[got] = name;
                        bufCounts[got] = e.count();
                        got++;
                        continue;
                    }
                    Double v = dataProbe.readNumber(level, dt);
                    if (v == null) {
                        continue;   // 方块实体没了 / 路径不存在 / 非数字 —— 这个目标留空
                    }
                    bufIds[got] = resolveDataName(level, dt);
                    bufCounts[got] = (int) Math.round(v);
                    got++;
                }
                if (got == 0) {
                    store.add(Sample.noData(gameTime));
                } else {
                    int k = Aggregator.combineAll(bufIds, bufCounts, got, aggIds, aggCounts);
                    for (int i = 0; i < k; i++) {
                        store.add(new Sample(gameTime, aggIds[i], aggCounts[i]));
                    }
                }
            }
        }
        return true;
    }

    /**
     * 采样该不该走服务端 tick 这条主路径。
     *
     * | 条件 | 说明 |
     * |---|---|
     * | 有坐标 | 服务端才能按"维度+坐标"读到那台设备 |
     * | 是单人 | 多人下方块实体不在客户端进程里（集成服务端才有） |
     * | 界面关着 **或** 下标已验证 | 界面开着但映射没验证时，菜单槽才是用户点的那一格 —— 宁可留观测空档，也不要读错格子 |
     */
    private boolean serverSideSampling() {
        ProbeTarget t = target;
        if (!t.hasPersistentId()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isLocalServer() || mc.getSingleplayerServer() == null) {
            return false;
        }
        return state != State.RECORDING
                || probe.mappingStatus(t) == WorldProbe.MappingStatus.VERIFIED;
    }

    /**
     * 同一个世界 tick 只写一遍样本。
     * （服务端暂停 tick / 客户端与服务端两条路交替时都可能重复调用）
     *
     * 只在 {@link #sampleLock} 内调用。
     */
    private boolean advanceTick(long gameTime) {
        if (gameTime == lastSampledGameTime) {
            return false;
        }
        lastSampledGameTime = gameTime;
        return true;
    }

    /**
     * 这一 tick 这条线叫什么名字。
     *
     * ★ 为什么名称要每 tick 解析 ★
     *   可变物品（流体罐、化学品罐）里装的东西会换：名称来自 NBT 的某个路径时，
     *   换了东西 → 名字变了 → 图上就是**换了一条线**。这正是我们要的语义
     *   （比"同一条线忽然变成另一种物质"诚实得多）。
     */
    private String resolveDataName(ServerLevel level, DataTarget dt) {
        if (dt.hasNamePath()) {
            String n = dataProbe.readString(level, dt.namePath(), dt.pos());
            if (n != null && !n.isBlank()) {
                return n;
            }
        }
        return dt.fallbackName();
    }

    private void enterFollowing(boolean notify) {
        // 采样由服务端 tick 接管（单人）；没有集成服务端时退化成"记 no_data"。
        // 这里不做任何垫值：第一次观测就是第一个世界 tick 的真值。
        state = State.FOLLOWING;
        if (notify) {
            notifyPlayer("logiscope.msg.following");
        }
    }

    private void pauseAuto() {
        manualPause = false;
        state = State.PAUSED;
        synchronized (sampleLock) {
            writeGapMarker();
        }
        notifyPlayer("logiscope.msg.paused_auto");
    }

    /**
     * 写一条"中断"标记：让恢复之后的曲线**不会**跟暂停前连起来。
     *
     * 标记落在"最后一个已采样的 tick 的下一格"—— 也就是"从这一格起就没记录了"。
     * 不一定正好等于暂停发生的那一 tick（暂停是界面上的动作，可能发生在两次采样之间），
     * 但语义是对的，而且一定落在这段空白里，画图时能把线剪在该剪的地方。
     */
    private void writeGapMarker() {
        long last = lastSampledGameTime;
        if (last != Long.MIN_VALUE) {
            store.add(Sample.gap(last + 1));
        }
    }

    /**
     * 状态自己变了，必须让玩家知道 —— 否则"记录到底还在不在跑"只能靠猜，
     * 这对一个观测工具是致命的。用动作栏（第二参数 true）不刷屏。
     *
     * 加 2 秒去抖：状态在边界附近来回跳时（例如玩家反复开关界面），
     * 不该把动作栏刷成跑马灯。**force = 重要警告，绕过去抖**
     * （"下标不可信"紧跟在"转为坐标跟踪"后面，普通去抖会把它吞掉 —— 第七轮 N3）。
     */
    private void notifyPlayer(String key) {
        notifyPlayer(key, false);
    }

    private void notifyPlayer(String key, boolean force) {
        Minecraft mc = Minecraft.getInstance();
        long now = manager.currentTick();
        if (mc.player == null || (!force && now - lastNotifyTick < 40)) {
            return;
        }
        lastNotifyTick = now;
        mc.player.displayClientMessage(Component.translatable(key), true);
    }

    // ------------------------------------------------------------------
    // 取值辅助
    // ------------------------------------------------------------------

    /**
     * 数据源是否就绪（界面开着 + 容器没换 + 选中的格子都还在）。
     * 三个条件缺一不可，见 ARCHITECTURE.md 的 CP5。
     */
    private boolean menuSourceReady() {
        if (!target.hasMenuPath() || !ContainerAccess.hasForeignContainerOpen()) {
            return false;
        }
        if (ContainerAccess.currentContainerId() != watchedContainerId && !tryRebind()) {
            return false;
        }
        return readMenuSlots() != null;
    }

    /**
     * 玩家重新打开了容器界面时，尝试按「维度 + 坐标」把它认成同一台设备。
     * 认出来就把 watchedContainerId 更新到新的容器 ID，实现跨界面重连。
     */
    private boolean tryRebind() {
        if (!target.hasPersistentId()) {
            return false;
        }
        int id = ContainerAccess.currentContainerId();
        ContainerAnchor.Anchor anchor = ContainerAnchor.activeFor(id);
        if (anchor == null) {
            return false;
        }
        if (!anchor.dimension().equals(target.dimension()) || !anchor.pos().equals(target.pos())) {
            return false;
        }
        watchedContainerId = id;
        return true;
    }

    /**
     * 界面开着时的一次读取：**按菜单位置读**（第十五轮的"按身份读"已回退，见下）。
     *
     * ★ 为什么回退 ★
     * 用户实测：MEK 熔炉里输入格确实只有 **2** 个圆石，而按**容器内下标**读到的
     * 是 **8**（另一格）。也就是说 —— **菜单位置是准的，容器下标对这类机器反而错**
     * （模组自写槽位类里 `getContainerSlot()` 的语义与能力(handler)的下标序不一致）。
     * 第十五轮把菜单路径也改成按身份读，等于"把错的统一到了两条路径上"；
     * 现在回退成按位置读，**界面内的读数恢复正确**。
     *
     * 残留问题（已记在案，本轮不动）：关掉界面走坐标路径时，仍按容器下标读那一格。
     * 根治要么等下标映射被内容指纹验证过（`verifyIndex`），要么在**未验证时不猜** ——
     * 记 `no_data`，而不是写一个可能是别格的数。
     */
    private List<ContainerAccess.SlotView> readMenuSlots() {
        List<ProbeTarget.SlotRef> refs = target.slots();
        List<ContainerAccess.SlotView> out = new ArrayList<>(refs.size());
        for (ProbeTarget.SlotRef ref : refs) {
            if (ref.menuSlotIndex() < 0) {
                // 这一格只有坐标路径（无 GUI 容器建的探针）—— 菜单里读不到它
                return null;
            }
            ContainerAccess.SlotView v = ContainerAccess.read(ref.menuSlotIndex());
            if (v == null) {
                return null;
            }
            out.add(v);
        }
        return out;
    }

    /**
     * 把一组槽位快照按物品求和，**写进复用的输出缓冲**（不分配 List）。
     *
     * @return 条目数（全空时为 1 —— 一条 {@code air × 0}）
     */
    private int aggregateInto(List<ContainerAccess.SlotView> views) {
        int n = views.size();
        if (bufIds.length < n) {
            bufIds = new String[n];
            bufCounts = new int[n];
            aggIds = new String[n];
            aggCounts = new int[n];
        }
        for (int i = 0; i < n; i++) {
            ContainerAccess.SlotView v = views.get(i);
            bufIds[i] = v.itemId();
            bufCounts[i] = v.count();
        }
        return Aggregator.combine(bufIds, bufCounts, n, aggIds, aggCounts);
    }

    /**
     * 把一组槽位快照变成**这个 tick 的样本**写进环形缓冲。
     *
     * ★ 一个 tick 可能写多条 ★（多格监控时每个出现的物品一条，见 Aggregator）
     * 它们共用同一个 tick 值 —— 时间轴仍然是"每世界 tick 一段"，
     * 只是这一段里可以有好几条不同物品的样本。
     * 断点判定因此是 per-series（见 {@code ChartData#build}）。
     */
    private void writeTick(long gameTime, List<ContainerAccess.SlotView> views) {
        int m = aggregateInto(views);
        for (int i = 0; i < m; i++) {
            store.add(new Sample(gameTime, aggIds[i], aggCounts[i]));
        }
    }

    /**
     * 面板上那行 "now" 需要的实时值（按物品求和后的条目）。
     *
     * 直接取**时间轴最后一格**的样本：它最多 1 个世界 tick 旧
     * （20 t/s 时 50 ms，200 t/s 时 5 ms），比"再读一次菜单/方块实体"更省事，
     * 而且天然和图表上的数字一致（不会出现"读数变了但点还没写进去"）。
     *
     * @return null = 这一刻没有数据（最后一条是 `no_data` / `gap` 标记）
     */
    @Nullable
    public List<Aggregator.Totals> liveTotals() {
        List<Sample> latest = store.latestTick();
        if (latest == null || latest.isEmpty()) {
            return null;
        }
        List<Aggregator.Totals> out = new ArrayList<>(latest.size());
        for (Sample s : latest) {
            out.add(new Aggregator.Totals(s.itemId(), s.count()));
        }
        return out;
    }
}
