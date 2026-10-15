package com.jessetech.logiscope;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组的可调参数（NeoForge **客户端**配置）。
 *
 * 前三条是"资源上限"，与具体设备无关，所以放配置而不是代码常量：
 * 有人只测一台机器（想留很久的历史），有人同时挂十几个探针（得省内存）。
 * 第四条（`dataRefreshTicks`）是 Probe data 页的刷新节拍 —— 界面上有滑条，
 * 两处写的是**同一个值**（谁改都以它为准，滑条改完会落盘）。
 *
 * | 键 | 默认 | 范围 | 影响 |
 * |---|---|---|---|
 * | `bufferSamples` | **72000** | 36000 ~ 2000000 | 每个探针实例的环形缓冲容量（**样本条数**）。20 t/s 时 72000 ≈ 1 小时；`/tick rate 200` 时只 ≈ 6 分钟 |
 * | `maxProbes` | 16 | 1 ~ 64 | 同时运行的探针实例上限（每个实例一份缓冲 + 每世界 tick 一次方块实体读取） |
 * | `maxItemKinds` | 256 | 1 ~ 4096 | 一个实例最多画多少种物品的数据线；超出的种类仍然记账（进统计），但**不画线**，面板上显示 `+N 未显示` |
 * | `dataRefreshTicks` | 20 | 0 ~ 40 | Probe data 页自动重发 `data get block` 的间隔（客户端 tick）；**0 = 不刷新**（只用页面的「刷新」按钮） |
 *
 * 为什么缓冲按"样本条数"而不是"时长" *   采样节拍 = 世界流速（`/tick rate` 可改），按时长算的话容量会随流速漂移，
 *   而且环形缓冲的容量必须在创建时就定死。按条数最诚实：写满就覆盖最旧的。
 *
 * 改动什么时候生效 *   · `bufferSamples`：**立刻**（`ProbeManager` 每客户端 tick 检查一次，变了就 resize，
 *     保留最新的样本）。不用重启，也不用重建探针。
 *   · `maxProbes`：创建新探针时判定（已存在的实例不会被踢掉）。
 *   · `maxItemKinds`：下一次重算派生数据时生效（即立刻）。
 *   · `dataRefreshTicks`：**立刻**（Probe data 页每 tick 读一次；速率改小/从"不刷新"
 *     切回来时会把已经过期的排期拉回来，不拖到旧节拍走完）。
 *
 * 文件在 `config/logiscope-client.toml`；游戏内从「模组列表 → LogiScope → 配置」进去改。
 */
public final class LogiScopeConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue BUFFER_SAMPLES;
    public static final ModConfigSpec.IntValue MAX_PROBES;
    public static final ModConfigSpec.IntValue MAX_ITEM_KINDS;
    public static final ModConfigSpec.IntValue DATA_REFRESH_TICKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("LogiScope 的资源上限").push("limits");
        BUFFER_SAMPLES = b
                .comment("每个探针实例的环形缓冲容量（样本条数）。",
                        "默认 72000：20 t/s 约 1 小时；/tick rate 200 时约 6 分钟。",
                        "改了立刻生效（保留最新样本）。")
                .defineInRange("bufferSamples", 72000, 36000, 2_000_000);
        MAX_PROBES = b
                .comment("同时运行的探针实例上限。",
                        "每个实例一份缓冲 + 每世界 tick 一次方块实体读取；",
                        "默认 16。只在创建新探针时判定，已存在的不会被踢掉。")
                .defineInRange("maxProbes", 16, 1, 64);
        MAX_ITEM_KINDS = b
                .comment("一个实例最多画多少种物品的数据线（含各自的 IO 线）。",
                        "超出的种类仍然记进缓冲、仍然进统计，只是不画线，",
                        "面板上会显示 +N 未显示。默认 256。")
                .defineInRange("maxItemKinds", 256, 1, 4096);
        b.pop();
        b.comment("Probe data 页（NBT 取数）的实时刷新").push("dataPage");
        DATA_REFRESH_TICKS = b
                .comment("自动重发 data get block 的间隔（客户端 tick）。",
                        "1~40：每 N tick 抓一次；0 = 不刷新（只用页面上的「刷新」按钮手动抓）。",
                        "默认 20（约 1 秒一次）。页面上的滑条与本项是同一个值，滑条改完会存到本文件。")
                .defineInRange("dataRefreshTicks", 20, 0, 40);
        b.pop();
        SPEC = b.build();
    }

    private LogiScopeConfig() {
    }

    /** 每个实例的缓冲容量（样本条数）。读不到就用默认值。 */
    public static int bufferSamples() {
        try {
            return BUFFER_SAMPLES.get();
        } catch (Throwable ignored) {
            return 72000;
        }
    }

    /** 同时运行的探针上限。读不到就用默认值。 */
    public static int maxProbes() {
        try {
            return MAX_PROBES.get();
        } catch (Throwable ignored) {
            return 16;
        }
    }

    /** 一个实例最多画多少种物品。读不到就用默认值。 */
    public static int maxItemKinds() {
        try {
            return MAX_ITEM_KINDS.get();
        } catch (Throwable ignored) {
            return 256;
        }
    }

    /** Probe data 页的自动刷新间隔（客户端 tick）。0 = 不刷新。读不到就用默认值。 */
    public static int dataRefreshTicks() {
        try {
            return DATA_REFRESH_TICKS.get();
        } catch (Throwable ignored) {
            return 20;
        }
    }

    /**
     * 滑条写回：改内存值并**落盘**（下次启动保持同一个档位）。
     * 值没变就什么都不做（拖滑条时同一档位会被反复调到）。
     *
     * @return 值是否真的变了
     */
    public static boolean setDataRefreshTicks(int v) {
        int c = Math.max(0, Math.min(40, v));
        try {
            if (DATA_REFRESH_TICKS.get() == c) {
                return false;
            }
            DATA_REFRESH_TICKS.set(c);
            SPEC.save();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
