# -*- coding: utf-8 -*-
import io

# ============ ProbeInstance：单数据目标 → 目标列表（物品 + 数据共存） ============
p = "src/main/java/com/jessetech/logiscope/collect/ProbeInstance.java"
s = io.open(p, encoding="utf-8").read()


def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:70]
    s = s.replace(old, new, 1)


# 1) 字段：单数 → 复数（COW，服务端线程遍历 / 渲染线程增删）
rep("""    @Nullable
    private volatile DataTarget dataTarget;
    private final DataProbe dataProbe = new DataProbe();""",
    """    private volatile List<DataTarget> dataTargets = List.of();
    private final DataProbe dataProbe = new DataProbe();

    /** 数据采样的独立节拍守卫（与物品的 {@link #lastSampledGameTime} 分开：
     *  界面开着且下标未验证时，物品走菜单路（客户端节拍）、数据走服务端 tick，
     *  两者要在同一个世界里各写各的，不能共用一把锁） */
    private volatile long lastDataTick = Long.MIN_VALUE;""")

# 2) 访问器
rep("""    /** 是不是"数据探针"（从 NBT 取数，见 {@link #dataTarget}） */
    public boolean isDataProbe() {
        return dataTarget != null;
    }

    @Nullable
    public DataTarget dataTarget() {
        return dataTarget;
    }

    /** 有东西可看（物品探针看槽位，数据探针看目标） */
    public boolean hasAnything() {
        return hasSlots() || isDataProbe();
    }""",
    """    /** 有没有配置 NBT 数据追踪（可以与物品槽位**共存**） */
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
    }""")

# 3) describe：带数据目标数
rep("""    /** 列表页显示用的一行描述（槽位 + 维度坐标；数据探针走路径描述） */
    public String describe() {
        DataTarget dt = dataTarget;
        if (dt != null) {
            return dt.describe();
        }
        if (!hasSlots()) {
            return target.anchorText();
        }
        return target.slotSummary() + " · " + target.anchorText();
    }

    /** 数据探针的名称（设备名 + 取样路径），列表页与图表页都用它 */
    public String dataName() {
        DataTarget dt = dataTarget;
        if (dt == null) {
            return name();
        }
        return dt.blockLabel();
    }""",
    """    /** 列表页显示用的一行描述（槽位 + 维度坐标；带数据追踪时标注条数） */
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
        return n > 0 ? base + " · 数据 x" + n : base;
    }""")

# 4) configureData 删除（被 addDataTarget 取代）；configure 允许"只有数据目标"
rep("""    /**
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

""", "")

# configure：允许"只有数据目标、没有格子"的探针
rep("""            if (newTarget.slots().isEmpty()) {
                this.state = State.ARMED;
                return;
            }""",
    """            if (newTarget.slots().isEmpty() && dataTargets.isEmpty()) {
                this.state = State.ARMED;
                return;
            }""")

# 5) tick：数据分支只对"纯数据探针"早退（带槽位的照常跑状态机）
rep("""    /** 由 {@link ProbeManager} 每客户端 tick 调一次（渲染线程） */
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
        }""",
    """    /** 由 {@link ProbeManager} 每客户端 tick 调一次（渲染线程） */
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
        }""")

# 6) sampleOnServerTick：重构为"物品 + 数据目标"两段
rep("""    boolean sampleOnServerTick(MinecraftServer server) {
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
        ProbeTarget t = target;
        if (t.slots().isEmpty() || !t.hasPersistentId()) {
            return false;
        }
        // 界面开着且下标没验证出来 → 交给客户端菜单路径（菜单槽才是用户点的那一格）。
        // 这个判断在锁外做一次只是"便宜的早退"，真正生效的是锁内那一次复查。
        if (st == State.RECORDING && probe.mappingStatus(t) != WorldProbe.MappingStatus.VERIFIED) {
            return false;
        }
        ServerLevel level = server.getLevel(t.dimension());
        if (level == null) {
            return false;
        }
        long gameTime = level.getGameTime();
        synchronized (sampleLock) {
            // ★ 锁内复查状态 ★
            // 玩家可能刚刚按了暂停 / 删了这个实例：那时绝不能再用旧目标写样本，
            // 否则会在"暂停标记"后面追加一条数据，把断口重新连起来。
            State now = state;
            if (now != State.RECORDING && now != State.FOLLOWING) {
                return false;
            }
            if (!advanceTick(gameTime)) {
                return false;
            }
            List<ContainerAccess.SlotView> views = probe.readOnServer(level, t);
            if (views == null) {
                store.add(Sample.noData(gameTime));
            } else {
                writeTick(gameTime, views);
            }
        }
        return true;
    }""",
    """    boolean sampleOnServerTick(MinecraftServer server) {
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
                for (DataTarget dt : dts) {
                    Double v = dataProbe.readNumber(level, dt);
                    if (v == null) {
                        // 方块实体没了 / 路径不存在 / 那个值不是数字 —— 如实记"读不到"
                        store.add(Sample.noData(gameTime));
                    } else {
                        String name = resolveDataName(level, dt);
                        store.add(new Sample(gameTime, name, (int) Math.round(v)));
                    }
                }
            }
        }
        return true;
    }""")

# 7) imports：ResourceKey / Level
rep("import net.minecraft.server.level.ServerLevel;",
    "import net.minecraft.resources.ResourceKey;\nimport net.minecraft.server.level.ServerLevel;\nimport net.minecraft.world.level.Level;")

# 8) dispose：清目标列表
rep("""            target = ProbeTarget.EMPTY;
            dataTarget = null;""",
    """            target = ProbeTarget.EMPTY;
            dataTargets = List.of();
            lastDataTick = Long.MIN_VALUE;""")

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ProbeInstance ok")
