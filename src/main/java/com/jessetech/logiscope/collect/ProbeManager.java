package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.ProbeTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 采集层：探针实例表 + 两个驱动点。
 *
 * 第九轮之前这里是 {@code Sampler}（全局唯一一个探头）。多探针之后拆成两层：
 *   · {@link ProbeInstance}：**一个**探针的状态机、缓冲、世界侧读取
 *   · 本类：实例表、创建 / 删除、活跃指针（界面当前在看哪一个）、每 tick 驱动所有人
 *
 * 【两个驱动点，分工明确】（第十轮第四次修复）
 *
 * | 驱动 | 线程 | 干什么 |
 * |---|---|---|
 * | {@link #onClientTick} | 渲染线程 | 推进时钟、跑**状态机**（界面开没开 / 源还在不在）、多人/无坐标时读菜单 |
 * | {@link #onServerTick} | **服务端线程** | 单人下**采样**：一个世界 tick 一条真值（见 `ProbeInstance#sampleOnServerTick`） |
 *
 * 为什么采样要搬到服务端 tick：客户端每秒只能观测 20 次，而服务端每秒要走 20 tick
 * （`/tick rate` 还能更快），两者的节拍**不锁相** —— 从客户端看过去必然偶尔漏一格。
 * 挂到服务端 tick 上就没有这个问题：世界走一格我们记一格，`/tick rate 200` 就是 200 条/秒。
 *
 * 【为什么所有实例同时采样，而不是"只看活跃的那一个"】
 *   需求是"正在运行的探针是一个列表，用于选择" —— 语义就是它们**都在跑**，
 *   界面只是切换查看对象。如果只跑活跃的那个，切回来会看到一段空白，
 *   而用户要的恰恰是"我离开的这段时间产线发生了什么"。
 *
 * 【实例表是 CopyOnWriteArrayList】
 *   服务端线程每 tick 要遍历它，而增删发生在渲染线程（用户点创建 / 删除）——
 *   普通 ArrayList 会在服务端线程上抛 ConcurrentModificationException。
 *   增删是用户动作（很少），遍历是每 tick（很频繁），所以复制写列表正合适。
 */
public final class ProbeManager {

    /** 实例数上限。每实例一份半小时缓冲，且每 tick 一次世界侧读取，不做无上限。 */
    public static final int MAX_PROBES = 16;

    private final List<ProbeInstance> probes = new CopyOnWriteArrayList<>();
    private int nextId = 1;
    private int activeId = -1;

    /** 用户偏好：关掉容器界面之后是否继续检测（对所有实例生效） */
    private boolean followAfterClose = true;

    /** 客户端 tick 计数，只用于提示去抖 */
    private long tick;

    // ------------------------------------------------------------------
    // 驱动
    // ------------------------------------------------------------------

    /** 由主类注册到游戏总线，签名固定为 (event) */
    public static void onClientTick(ClientTickEvent.Post event) {
        ContainerAnchor.tick();
        // 先推进全局时钟（客户端 tick + 权威世界 tick），再让各实例按同一刻度走状态机
        ServerClock.tickClient();
        LogiScopeMod.probes().tick();
    }

    private void tick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        // 状态机用的刻度 = 世界 tick（N12/N13）。主菜单等无世界场合退回客户端节拍。
        long gameTime = mc.level != null
                ? ServerClock.worldGameTime(mc.level.getGameTime())
                : tick;
        // 索引循环：删除只会发生在界面事件里（不在 tick 中途），所以不需要快照
        for (ProbeInstance p : probes) {
            p.tick(gameTime);
        }
    }

    /**
     * ★ 采样驱动（服务端线程）★ 见类注释：单人下"一个世界 tick 一条真值"的来源。
     *
     * 只处理**自己这个集成服务端**（LAN 发布出去的也是它），
     * 专用服务器上这个监听器不会被加载（模组声明为纯客户端）。
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isLocalServer()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server != mc.getSingleplayerServer()) {
            return;
        }
        LogiScopeMod.probes().sampleAll(server);
    }

    private void sampleAll(MinecraftServer server) {
        for (ProbeInstance p : probes) {
            p.sampleOnServerTick(server);
        }
    }

    public long currentTick() {
        return tick;
    }

    // ------------------------------------------------------------------
    // 实例表
    // ------------------------------------------------------------------

    /** 全部实例（按创建顺序）。只读视图，界面可以直接遍历。 */
    public List<ProbeInstance> all() {
        return Collections.unmodifiableList(probes);
    }

    public int count() {
        return probes.size();
    }

    public boolean isFull() {
        return probes.size() >= MAX_PROBES;
    }

    @Nullable
    public ProbeInstance find(int id) {
        for (ProbeInstance p : probes) {
            if (p.id() == id) {
                return p;
            }
        }
        return null;
    }

    @Nullable
    public ProbeInstance active() {
        return find(activeId);
    }

    public int activeId() {
        return activeId;
    }

    public void setActive(int id) {
        if (find(id) != null) {
            activeId = id;
        }
    }

    /**
     * 新建一个探针实例并设为活跃。
     *
     * @param target 初始目标：可以只带锚点（维度+坐标）而槽位为空 ——
     *               选格页会把槽位补上。传 null / 空目标表示"还不知道要探测哪台设备"。
     * @return 新实例；已达上限时返回 null
     */
    @Nullable
    public ProbeInstance create(@Nullable ProbeTarget target) {
        if (isFull()) {
            return null;
        }
        ProbeInstance p = new ProbeInstance(nextId++, this);
        probes.add(p);
        activeId = p.id();
        if (target != null) {
            p.configure(target, null);
        }
        return p;
    }

    /**
     * 停止监测并**删掉这个实例的缓存**（界面列表行末尾的删除按钮）。
     * 删除活跃实例时，活跃指针落到剩下的第一个。
     */
    public boolean remove(int id) {
        for (int i = 0; i < probes.size(); i++) {
            if (probes.get(i).id() != id) {
                continue;
            }
            ProbeInstance gone = probes.remove(i);
            gone.dispose();
            if (activeId == id) {
                activeId = probes.isEmpty() ? -1 : probes.get(0).id();
            }
            return true;
        }
        return false;
    }

    /** 用选格页的结果配置某个实例 */
    public void configure(int id, ProbeTarget target, @Nullable List<ContainerAccess.SlotView> verifyViews) {
        ProbeInstance p = find(id);
        if (p != null) {
            p.configure(target, verifyViews);
        }
    }

    // ------------------------------------------------------------------
    // 用户偏好
    // ------------------------------------------------------------------

    public boolean followAfterClose() {
        return followAfterClose;
    }

    public void setFollowAfterClose(boolean value) {
        this.followAfterClose = value;
    }
}
