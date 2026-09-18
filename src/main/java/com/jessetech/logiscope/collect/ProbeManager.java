package com.jessetech.logiscope.collect;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.ProbeTarget;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 采集层：探针实例表 + 每 tick 的唯一驱动点。
 *
 * 第九轮之前这里是 {@code Sampler}（全局唯一一个探头）。多探针之后拆成两层：
 *   · {@link ProbeInstance}：**一个**探针的状态机、缓冲、世界侧读取器
 *   · 本类：实例表、创建 / 删除、活跃指针（界面当前在看哪一个）、每 tick 驱动所有人
 *
 * 【为什么所有实例同时采样，而不是"只看活跃的那一个"】
 *   需求是"正在运行的探针是一个列表，用于选择" —— 语义就是它们**都在跑**，
 *   界面只是切换查看对象。如果只跑活跃的那个，切回来会看到一段空白，
 *   而用户要的恰恰是"我离开的这段时间产线发生了什么"。
 *   代价是每 tick 每个实例各发一次世界侧异步读取（单人集成服务端，成本很低），
 *   且每个实例各占一份 36000 样本的环形缓冲（约 290 KB 引用，上限 16 个实例）。
 *
 * 【活跃指针与"监听中的数组下标"无关】
 *   活跃只影响 UI；不影响任何采样。删掉活跃实例时自动落到列表里的第一个。
 */
public final class ProbeManager {

    /** 实例数上限。每实例一份半小时缓冲，且每 tick 一次异步读取，不做无上限。 */
    public static final int MAX_PROBES = 16;

    private final List<ProbeInstance> probes = new ArrayList<>();
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
        // 先推进全局时钟（客户端 tick + 权威世界 tick），再让各实例按同一刻度采样
        ServerClock.tickClient();
        LogiScopeMod.probes().tick();
    }

    private void tick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        // 时间刻度 = 世界 tick（N12/N13）。主菜单等无世界场合退回客户端节拍。
        long gameTime = mc.level != null
                ? ServerClock.worldGameTime(mc.level.getGameTime())
                : tick;
        // 索引循环：删除只会发生在界面事件里（不在 tick 中途），所以不需要快照
        for (int i = 0; i < probes.size(); i++) {
            probes.get(i).tick(gameTime);
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
