# -*- coding: utf-8 -*-
import io

p = "src/main/java/com/jessetech/logiscope/collect/ProbeInstance.java"
s = io.open(p, encoding="utf-8").read()

# 5) configureData
old = "    /**\n     * 配置里的缓冲容量改了就跟上（**保留最新样本**）。"
new = '''    /**
     * 配置成**数据探针**（第十一轮③）：从方块实体 NBT 的某个路径取数。
     *
     * 状态怎么定：单人（有集成服务端）才可能采到值 —— 多人下客户端进程里没有
     * 方块实体，如实停在 PAUSED，不假装在记录（与物品探针的 CP5 同一个原则）。
     * 目标设备没了 / 路径不存在时逐 tick 写 `no_data`，不写 0。
     */
    public void configureData(DataTarget t) {
        synchronized (sampleLock) {
            this.dataTarget = t;
            this.target = ProbeTarget.EMPTY;
            this.watchedContainerId = -1;
            this.store.clear();
            this.markers.clear();
            this.manualPause = false;
            this.warnedUnverifiedIndex = false;
            this.lastSampledGameTime = Long.MIN_VALUE;
            this.probe.reset();
            this.state = integratedServerUp() ? State.RECORDING : State.PAUSED;
        }
    }

    /** 客户端手上有集成服务端（单人）—— 只有这种局面下才读得到方块实体 */
    private static boolean integratedServerUp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.isLocalServer() && mc.getSingleplayerServer() != null;
    }

    /**
     * 配置里的缓冲容量改了就跟上（**保留最新样本**）。'''
assert old in s
s = s.replace(old, new, 1)

# 6) tick()
old2 = """    /** 由 {@link ProbeManager} 每客户端 tick 调一次（渲染线程） */
    void tick(long gameTime) {
        if (state == State.ARMED || target.slots().isEmpty()) {
            return;
        }"""
new2 = """    /** 由 {@link ProbeManager} 每客户端 tick 调一次（渲染线程） */
    void tick(long gameTime) {
        // 数据探针：采样全在服务端 tick 那条路上（读方块实体），这里只管状态回落
        if (isDataProbe()) {
            if (state == State.RECORDING && !integratedServerUp()) {
                pauseAuto();
            }
            return;
        }
        if (state == State.ARMED || target.slots().isEmpty()) {
            return;
        }"""
assert old2 in s
s = s.replace(old2, new2, 1)

# 7) sampleOnServerTick 数据分支
old3 = """    boolean sampleOnServerTick(MinecraftServer server) {
        State st = state;
        if (st != State.RECORDING && st != State.FOLLOWING) {
            return false;
        }
        ProbeTarget t = target;"""
new3 = """    boolean sampleOnServerTick(MinecraftServer server) {
        State st = state;
        if (st != State.RECORDING && st != State.FOLLOWING) {
            return false;
        }
        // ---- 数据探针：按 NBT 路径取值（第十一轮③）----
        DataTarget dt = dataTarget;
        if (dt != null) {
            ServerLevel dataLevel = server.getLevel(dt.dimension());
            if (dataLevel == null) {
                return false;
            }
            long dataTime = dataLevel.getGameTime();
            synchronized (sampleLock) {
                if (dataTarget == null) {
                    return false;
                }
                State now = state;
                if (now != State.RECORDING && now != State.FOLLOWING) {
                    return false;
                }
                if (!advanceTick(dataTime)) {
                    return false;
                }
                Double v = dataProbe.readNumber(dataLevel, dt);
                if (v == null) {
                    // 方块实体没了 / 路径不存在 / 那个值不是数字 —— 如实记"读不到"
                    store.add(Sample.noData(dataTime));
                    return true;
                }
                String name = resolveDataName(dataLevel, dt);
                store.add(new Sample(dataTime, name, (int) Math.round(v)));
            }
            return true;
        }
        ProbeTarget t = target;"""
assert old3 in s
s = s.replace(old3, new3, 1)

# 8) resolveDataName
old4 = "    private void enterFollowing(boolean notify) {"
new4 = '''    /**
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

    private void enterFollowing(boolean notify) {'''
assert old4 in s
s = s.replace(old4, new4, 1)

# 9) dispose 清 dataTarget
old5 = """    void dispose() {
        synchronized (sampleLock) {
            store.clear();
            markers.clear();
            probe.reset();
            target = ProbeTarget.EMPTY;"""
new5 = """    void dispose() {
        synchronized (sampleLock) {
            store.clear();
            markers.clear();
            probe.reset();
            target = ProbeTarget.EMPTY;
            dataTarget = null;"""
assert old5 in s
s = s.replace(old5, new5, 1)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ProbeInstance ok")
