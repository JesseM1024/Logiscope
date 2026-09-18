package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.analysis.Aggregator;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.store.Sample;
import com.jessetech.logiscope.store.SampleStore;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 采集层：**一个探针实例**——整个模组唯一「主动跑」的东西。
 *
 * ★ 第九轮：从"全局唯一"变成"可以并存多个" ★
 *   需求："正在运行的探针是一个列表，用于选择" —— 也就是多个探针**同时**在监测，
 *   界面只负责挑一个来看。于是原来的单例 {@code Sampler} 被拆成：
 *     · 本类：一个实例自己的状态机 + 自己的环形缓冲 + 自己的世界侧读取器
 *     · {@link ProbeManager}：实例表、创建/销毁、每 tick 驱动所有人
 *   注意每个实例都持有**自己的** {@link WorldProbe}（读值缓存与下标验证必须隔离，
 *   否则两个探针会互相覆盖读数），但时钟是全局的（{@link ServerClock}）。
 *
 * 它刻意不认识任何 UI / 渲染类 —— 界面只是它的一个观察者。
 * 这样分层的好处：采样逻辑可以脱离界面单独验证（例如用日志输出），
 * 界面的崩溃也不会污染数据。
 *
 * 状态机：
 *
 *   ARMED ──configure()──► RECORDING ◄──────────┐
 *     ▲                       │  ▲               │
 *     │                       │  └── FOLLOWING ──┘
 *     │                       │        │
 *     └──clearSlots()─────────┴────────┴──► PAUSED
 *
 * 【三条取值路径】
 *   RECORDING  界面开着 → 直接读菜单（ContainerAccess），这是最准的一条
 *   FOLLOWING  界面关了 → 单人从方块实体读（WorldProbe）；多人读不到，记 no_data
 *   PAUSED     不取值
 *
 * 【多格监控：一台设备上可以同时监控好几格，记录是聚合的】
 *   每 tick 只写**一条**样本：把所有被监控格子的数量**加在一起**，
 *   物品 id 的规则见 {@link Aggregator}（同种物品 → 那种物品；混着多种 → logiscope:mixed）。
 *   于是下游（存储/分组/波形/框选统计）一行都不用改 —— 它看到的仍是一条扁平时间序列。
 *
 * 【PAUSED 的两种来源，行为不同】
 *   manualPause = true   用户按了「暂停」→ 不自动恢复，只能手动继续
 *   manualPause = false  数据源自己失效了 → 一旦数据源回来（重开同一台设备）自动恢复
 *
 * 【时间轴连续性：RECORDING → FOLLOWING 的接缝】
 *   关界面的那一 tick 记的是 lastKnown（上一 tick 的观测值），并把它垫进 WorldProbe 缓存，
 *   于是整个切换过程**一格都不缺、也不会冒出假 no_data**。
 *   真读不到的时候才会记 no_data —— 该断的地方照样断。
 *
 * 【时间轴的刻度：世界 tick，不是客户端 tick（第七轮 N12 → 第八轮 N13）】
 *   见 {@link ServerClock} 的类注释：客户端 {@code getGameTime()} 是错拍阶梯，
 *   所以单人存档改用服务端线程读出的权威值；这里只负责"世界没走 tick 就不记重复样本"。
 */
public final class ProbeInstance {

    public enum State {
        /** 已创建但还没选格（或者用户把格全清了）—— 不采样 */
        ARMED,
        RECORDING,
        FOLLOWING,
        PAUSED
    }

    /** 容量 = 20 tick/秒 × 60 秒 × 30 分 = 半小时（每个实例各一份） */
    private static final int CAPACITY = 20 * 60 * 30;

    /** 世界侧读值的最大可容忍延迟（**客户端** tick，≈250ms 真实时间，与世界流速无关）。 */
    private static final int WORLD_READ_MAX_AGE = 5;

    private final int id;
    private final ProbeManager manager;
    private final SampleStore store = new SampleStore(CAPACITY);
    private final WorldProbe probe = new WorldProbe();

    private ProbeTarget target = ProbeTarget.EMPTY;
    private State state = State.ARMED;
    private int watchedContainerId = -1;

    /** 暂停是不是用户主动按的（决定能否自动恢复） */
    private boolean manualPause;

    /**
     * 最近一次真正读到的**原始槽位快照**（与 target.slots() 同序）。
     *
     * 只为一件事存在：**补住"关掉容器界面的那一 tick"**。
     * 那一 tick 菜单已经不在了（读不到），而世界侧的异步读取又还没回来，
     * 于是时间轴上会凭空出现一个空洞 + 一条假 no_data。
     * 手里这份值最多 1 tick（50 ms）旧，拿它续上比记"无数据"诚实得多。
     *
     * 存"快照"而不是"聚合结果"是为了能原样喂给
     * {@link WorldProbe#seed}（它缓存的就是快照）。
     */
    @Nullable
    private List<ContainerAccess.SlotView> lastViews;

    /** 上一次采样的世界 tick。世界没走 tick（流速调慢 / 暂停）就不记重复样本 */
    private long lastSampledGameTime = Long.MIN_VALUE;

    /** "下标不可信"是否已经警告过。每个探头只警告一次，别跑马灯。 */
    private boolean warnedUnverifiedIndex;

    /** 上一次给玩家发动作栏提示的 tick，用于去抖 */
    private long lastNotifyTick = Long.MIN_VALUE / 2;

    // 聚合用的复用缓冲（每 tick 调一次，避免每 tick 造两个 List）
    private String[] bufIds = new String[8];
    private int[] bufCounts = new int[8];
    private int bufN;

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

    /** 列表页显示用的名字 */
    public String name() {
        return "P" + id;
    }

    /** 列表页显示用的一行描述 */
    public String describe() {
        if (!hasSlots()) {
            return target.anchorText();
        }
        return target.slotSummary() + " · " + target.anchorText();
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
    public void configure(ProbeTarget newTarget, @Nullable List<ContainerAccess.SlotView> verifyViews) {
        this.target = newTarget;
        this.watchedContainerId = ContainerAccess.currentContainerId();
        this.store.clear();
        this.manualPause = false;
        this.lastViews = null;
        this.warnedUnverifiedIndex = false;
        this.lastSampledGameTime = Long.MIN_VALUE;
        this.probe.reset();

        if (newTarget.slots().isEmpty()) {
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

    /** 清空已记录的数据，但不动探针和状态 */
    public void clearData() {
        store.clear();
    }

    /** 把格子全清掉（面板上的「清空选择」并确认后走到这里）→ 回到待选格 */
    public void clearSlots() {
        this.target = ProbeTarget.EMPTY;
        this.watchedContainerId = -1;
        this.manualPause = false;
        this.lastViews = null;
        this.warnedUnverifiedIndex = false;
        this.probe.reset();
        this.state = State.ARMED;
    }

    /**
     * 销毁前的收尾：丢掉缓冲与全部缓存（"删除按钮 = 停止监测 + 删除这个实例的缓存"）。
     * 由 {@link ProbeManager#remove} 调用。
     */
    void dispose() {
        store.clear();
        probe.reset();
        target = ProbeTarget.EMPTY;
        lastViews = null;
        state = State.ARMED;
        manualPause = false;
    }

    /** 主动暂停（用户按的） */
    public void pause() {        if (state == State.RECORDING || state == State.FOLLOWING) {
            manualPause = true;
            state = State.PAUSED;
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
    // 采样
    // ------------------------------------------------------------------

    /** 由 {@link ProbeManager} 每客户端 tick 调一次 */
    void tick(long gameTime) {
        if (state == State.ARMED || target.slots().isEmpty()) {
            return;
        }
        // 世界这一 tick 还没走（`/tick rate` 调慢、ESC 暂停）→ 菜单同步也没动，
        // 没有任何新信息。记重复样本只会让 tick rate ≠ 20 时数据全错（第七轮 N12）。
        if (gameTime == lastSampledGameTime) {
            return;
        }
        lastSampledGameTime = gameTime;

        switch (state) {
            case RECORDING -> tickRecording(gameTime);
            case FOLLOWING -> tickFollowing(gameTime);
            case PAUSED -> tickPaused();
            default -> {
            }
        }
    }

    /** 界面开着：读菜单 */
    private void tickRecording(long gameTime) {
        if (!ContainerAccess.hasForeignContainerOpen()) {
            // 玩家关掉界面了
            if (manager.followAfterClose() && target.hasPersistentId()) {
                // ★ 先补住"关界面的这一 tick"★
                // 这一 tick 菜单已经没了，但上一 tick 读到的值还在手上（最多 1 tick 旧）。
                // 不补的话时间轴在这里断一格，而下一 tick 又会因为异步读取还没回来
                // 记一条 no_data —— 合起来就是实机反馈的"关界面时冒出 1t 的 no-data"。
                if (lastViews != null) {
                    Aggregator.Result r = aggregate(lastViews);
                    store.add(new Sample(gameTime, r.itemId(), r.count()));
                }
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

        List<ContainerAccess.SlotView> views = readMenuSlots();
        if (views == null) {
            // 有格子读不到（槽位下标越界 = 换了不同类型的菜单）
            pauseAuto();
            return;
        }
        Aggregator.Result r = aggregate(views);
        lastViews = views;
        store.add(new Sample(gameTime, r.itemId(), r.count()));
    }

    /** 界面关着：按坐标读，读不到就记 no_data */
    private void tickFollowing(long gameTime) {
        // ★ 下标映射不可信的机器（MEK 这类自写槽位类的模组），必须明说 ★
        // 验证是挂探头那 1~2 tick 内在服务端做的（内容指纹匹配），
        // 匹配有歧义（例如对着空格子挂探头）就到此为止 —— 不瞎猜，让用户知道。
        // force：警告紧跟"转为坐标跟踪"，普通去抖会把它吞掉（第七轮 N3）。
        if (!warnedUnverifiedIndex
                && probe.mappingStatus(target) == WorldProbe.MappingStatus.UNVERIFIED) {
            warnedUnverifiedIndex = true;
            notifyPlayer("logiscope.msg.index_unverified", true);
        }

        if (ContainerAccess.hasForeignContainerOpen()) {
            // 玩家又把界面打开了 —— 优先回到最准的那条路径
            if (ContainerAccess.currentContainerId() == watchedContainerId || tryRebind()) {
                // ★ 补住切换 tick ★
                // 世界侧缓存里还留着切换前一刻的真值；不垫的话这一 tick 什么都不记，
                // 时间轴断一格，波形在这里凭空断开（与关界面方向对称）。
                List<ContainerAccess.SlotView> bridge = probe.latest(WORLD_READ_MAX_AGE);
                if (bridge != null) {
                    Aggregator.Result r = aggregate(bridge);
                    store.add(new Sample(gameTime, r.itemId(), r.count()));
                }
                state = State.RECORDING;
                return;
            }
            // 开的是别的容器：继续按坐标跟踪，不打断
        }

        probe.request(target);
        List<ContainerAccess.SlotView> views = probe.latest(WORLD_READ_MAX_AGE);
        if (views == null) {
            store.add(Sample.noData(gameTime));
        } else {
            Aggregator.Result r = aggregate(views);
            lastViews = views;
            store.add(new Sample(gameTime, r.itemId(), r.count()));
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

    private void enterFollowing(boolean notify) {
        probe.resetReadCache();
        // 把最后一次已知值垫进缓存，抹掉异步管道的启动延迟（见 WorldProbe#seed）
        probe.seed(lastViews);
        // ★ 立刻发出第一次世界侧读取 ★
        // 不发的话，下一 tick 的 latest() 只能拿到 seed（上一 GUI 值）——
        // 旧值被续命 1~2 tick，1t IO 测试里表现为切换瞬间脉冲被拉宽（第六轮 M1）。
        probe.request(target);
        state = State.FOLLOWING;
        if (notify) {
            notifyPlayer("logiscope.msg.following");
        }
    }

    private void pauseAuto() {
        manualPause = false;
        state = State.PAUSED;
        notifyPlayer("logiscope.msg.paused_auto");
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
     * 读全部被监控的菜单槽；任一格读不到就返回 null（理由见
     * {@code WorldProbe#readNow} 的注释：聚合值偏小是看不出来的，宁可断档）。
     */
    @Nullable
    private List<ContainerAccess.SlotView> readMenuSlots() {
        List<ProbeTarget.SlotRef> refs = target.slots();
        List<ContainerAccess.SlotView> out = new java.util.ArrayList<>(refs.size());
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

    /** 把一组槽位快照聚合成一条样本内容（缓冲复用，不再分配 List） */
    private Aggregator.Result aggregate(List<ContainerAccess.SlotView> views) {
        int n = views.size();
        if (bufIds.length < n) {
            bufIds = new String[n];
            bufCounts = new int[n];
        }
        for (int i = 0; i < n; i++) {
            ContainerAccess.SlotView v = views.get(i);
            bufIds[i] = v.itemId();
            bufCounts[i] = v.count();
        }
        bufN = n;
        return Aggregator.combine(bufIds, bufCounts, bufN);
    }

    /**
     * 面板上那行 "now" 需要的实时值（聚合后）。
     *
     * @return null = 这一刻没有数据（no_data）；empty() 为真 = 读到了，只是全空
     */
    @Nullable
    public Aggregator.Result liveValue() {
        if (state == State.RECORDING) {
            List<ContainerAccess.SlotView> views = readMenuSlots();
            return views == null ? null : aggregate(views);
        }
        if (state == State.FOLLOWING) {
            List<ContainerAccess.SlotView> views = probe.latest(WORLD_READ_MAX_AGE);
            return views == null ? null : aggregate(views);
        }
        Sample last = store.latest();
        if (last == null || last.isNoData()) {
            return null;
        }
        return new Aggregator.Result(last.itemId(), last.count());
    }
}
