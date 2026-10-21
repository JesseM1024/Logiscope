package com.jessetech.logiscope;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

/**
 * 模组的可调参数（1.12.2 的 {@code @Config}，对应另外三条线的 ForgeConfigSpec）。
 *
 * | 键 | 默认 | 范围 | 影响 |
 * |---|---|---|---|
 * | `bufferSamples` | **72000** | 36000 ~ 2000000 | 每个探针实例的环形缓冲容量（**样本条数**）。20 t/s 时 72000 ≈ 1 小时 |
 * | `maxProbes` | 16 | 1 ~ 64 | 同时运行的探针实例上限 |
 * | `maxItemKinds` | 256 | 1 ~ 4096 | 一个实例最多画多少种物品的线；超出的照旧进统计但不画线，面板显示 `+N 未显示` |
 * | `dataRefreshTicks` | 20 | 0 ~ 40 | Probe data 页自动重发 `data get block` 的间隔（客户端 tick）；**0 = 不刷新** |
 *
 * 【这一代的配置与 ForgeConfigSpec 的差别（别再照抄另外三条线）】
 *   · 没有 `defineInRange` 那种建造器：**字段本身就是配置项**，注解上写范围
 *     （`@Config.RangeInt`），默认值 = 字段初始值；
 *   · 文件是 `config/logiscope-client.cfg`（不是 `.toml`）；
 *   · 落盘靠 `ConfigManager.sync(MODID, Config.Type.INSTANCE)`，没有 `SPEC.save()`；
 *   · 游戏内也没有配置界面（1.12.2 的 ModList 只给 .cfg 一个"打开文件"入口）。
 *
 * 上层调用的仍是 `bufferSamples()` 这类**方法**（不要直接读 public 字段）：
 * 换线时只改这个文件，调用点一行都不用动。
 */
@Config(modid = LogiScopeMod.MODID, name = "logiscope-client", category = "logiscope")
public final class LogiScopeConfig {

    @Config.Comment({
            "每个探针实例的环形缓冲容量（样本条数）。",
            "默认 72000：20 t/s 约 1 小时；服务端加速时相应变短。",
            "改了立刻生效（保留最新样本）。"
    })
    @Config.RangeInt(min = 36000, max = 2000000)
    @Config.Name("bufferSamples")
    public static int bufferSamples = 72000;

    @Config.Comment({
            "同时运行的探针实例上限。",
            "每个实例一份缓冲 + 每世界 tick 一次方块实体读取；默认 16。",
            "只在创建新探针时判定，已存在的不会被踢掉。"
    })
    @Config.RangeInt(min = 1, max = 64)
    @Config.Name("maxProbes")
    public static int maxProbes = 16;

    @Config.Comment({
            "一个实例最多画多少种物品的数据线（含各自的 IO 线）。",
            "超出的种类仍然记进缓冲、仍然进统计，只是不画线，",
            "面板上会显示 +N 未显示。默认 256。"
    })
    @Config.RangeInt(min = 1, max = 4096)
    @Config.Name("maxItemKinds")
    public static int maxItemKinds = 256;

    @Config.Comment({
            "Probe data 页（NBT 取数）的自动刷新间隔（客户端 tick）。",
            "0 = 不刷新，只用页面的「刷新」按钮。默认 20（1 秒）。"
    })
    @Config.RangeInt(min = 0, max = 40)
    @Config.Name("dataRefreshTicks")
    public static int dataRefreshTicks = 20;

    private LogiScopeConfig() {
    }

    /** 每个实例的缓冲容量（样本条数）。 */
    public static int bufferSamples() {
        return Math.max(36000, Math.min(2000000, bufferSamples));
    }

    /** 同时运行的探针上限。 */
    public static int maxProbes() {
        return Math.max(1, Math.min(64, maxProbes));
    }

    /** 一个实例最多画多少种物品。 */
    public static int maxItemKinds() {
        return Math.max(1, Math.min(4096, maxItemKinds));
    }

    /** Probe data 页的自动刷新间隔（客户端 tick）。0 = 不刷新。 */
    public static int dataRefreshTicks() {
        return Math.max(0, Math.min(40, dataRefreshTicks));
    }

    /**
     * 滑条写回：改内存值并**落盘**（下次启动保持同一个档位）。
     * 值没变就什么都不做（拖滑条时同一档位会被反复调到）。
     *
     * @return 值是否真的变了
     */
    public static boolean setDataRefreshTicks(int v) {
        int c = Math.max(0, Math.min(40, v));
        if (dataRefreshTicks == c) {
            return false;
        }
        dataRefreshTicks = c;
        try {
            ConfigManager.sync(LogiScopeMod.MODID, Config.Type.INSTANCE);
        } catch (Throwable ignored) {
            // 落盘失败不影响本次会话（内存值已经改了），下次启动回到旧档位
        }
        return true;
    }
}
