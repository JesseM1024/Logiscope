# -*- coding: utf-8 -*-
import io

# ---- ProbeInstance：补回 integratedServerUp 助手（随 configureData 一起被删了） ----
p = "src/main/java/com/jessetech/logiscope/collect/ProbeInstance.java"
s = io.open(p, encoding="utf-8").read()
old = "    /** 客户端手上有集成服务端（单人）——只有这种局面下才读得到方块实体 */"
if old not in s:
    anchor = "    /** 客户端手上有集成服务端（单人）—— 只有这种局面下才读得到方块实体 */"
    if anchor not in s:
        # 插到 configure 方法前
        a = s.index("    public void configure(ProbeTarget newTarget, @Nullable List<ContainerAccess.SlotView> verifyViews) {")
        helper = """    /** 客户端手上有集成服务端（单人）—— 只有这种局面下才读得到方块实体 */
    private static boolean integratedServerUp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.isLocalServer() && mc.getSingleplayerServer() != null;
    }

"""
        s = s[:a] + helper + s[a:]
        io.open(p, "w", encoding="utf-8", newline="\n").write(s)
        print("helper restored")

# ---- ProbeManager：clearAll ----
p2 = "src/main/java/com/jessetech/logiscope/collect/ProbeManager.java"
s2 = io.open(p2, encoding="utf-8").read()
old2 = """    /** 用选格页的结果配置某个实例 */"""
new2 = """    /**
     * 清空全部探针（进入世界时调用）。
     *
     * ★ 为什么要清 ★ 探针绑定的是"**这个世界**里的这台设备"，而实例表是
     * 客户端进程级的 —— 原先退出存档再进另一个存档，A 存档建的探针还挂在
     * B 存档的列表里（坐标同名但设备完全不是同一台）。LoggingIn 时清空 =
     * 每个世界从零开始，跨世界的"僵尸探针"在结构上不可能出现。
     */
    public void clearAll() {
        for (ProbeInstance p : List.copyOf(probes)) {
            remove(p.id());
        }
        activeId = -1;
    }

    /** 用选格页的结果配置某个实例 */"""
assert old2 in s2
s2 = s2.replace(old2, new2, 1)
io.open(p2, "w", encoding="utf-8", newline="\n").write(s2)
print("ProbeManager ok")

# ---- LogiScopeMod：LoggingIn 监听 ----
p3 = "src/main/java/com/jessetech/logiscope/LogiScopeMod.java"
s3 = io.open(p3, encoding="utf-8").read()
old3 = "import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;"
assert old3 in s3
s3 = s3.replace(old3, "import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;\nimport net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;", 1)
old4 = """        // 命令回执的超时计时（1 秒没回就当失败，不然状态永远卡在"等待"）
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(e -> DataCommand.tick());"""
new4 = """        // 命令回执的超时计时（1 秒没回就当失败，不然状态永远卡在"等待"）
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(e -> DataCommand.tick());

        // ★ 进世界 = 清空全部探针 ★ 探针绑定"这个世界里的这台设备"，
        // 跨世界留着只会变成"坐标同名但设备不是同一台"的僵尸（用户实测 A 存档的
        // 探针出现在 B 存档）。顺手把 NBT 显示的残留文本也清掉。
        NeoForge.EVENT_BUS.<ClientPlayerNetworkEvent.LoggingIn>addListener(e -> {
            LogiScopeMod.probes().clearAll();
            DataCommand.reset();
        });"""
assert old4 in s3
s3 = s3.replace(old4, new4, 1)
io.open(p3, "w", encoding="utf-8", newline="\n").write(s3)
print("LogiScopeMod ok")
