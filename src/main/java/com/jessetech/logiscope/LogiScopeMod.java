package com.jessetech.logiscope;

import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.DataCommand;
import com.jessetech.logiscope.bind.KeyBindings;
import com.jessetech.logiscope.collect.ProbeManager;
import com.jessetech.logiscope.ui.ProbeHighlight;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * LogiScope —— 纯客户端物流探测示波器（MC 1.12.2 线）。
 *
 * 【这一代与另外三条线的注册方式差别（详见 docs/PORT-1.12.2.md）】
 *   · **没有 mod 总线**：只有 {@code MinecraftForge.EVENT_BUS}，没有 {@code IEventBus}；
 *     监听器靠 {@code @SubscribeEvent} 静态方法 + {@code EVENT_BUS.register(类)}。
 *   · **没有构造器式初始化**：生命周期是 {@code @Mod.EventHandler} 的
 *     {@code preInit} / {@code init}。
 *   · **客户端专用靠注解**：{@code clientSideOnly = true}（1.13+ 才改成运行时判 dist）。
 *   · **快捷键注册**在 preInit（{@code ClientRegistry.registerKeyBinding}）。
 *   · **配置**是 {@code @Config} 类（见 {@link LogiScopeConfig}），由 FML 注入，
 *     不需要（也不能）手动 registerConfig。
 */
@Mod(
        modid = LogiScopeMod.MODID,
        name = LogiScopeMod.NAME,
        version = LogiScopeMod.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*"
)
public final class LogiScopeMod {

    public static final String MODID = "logiscope";
    public static final String NAME = "LogiScope";

    /**
     * 版本号。这一代没有 {@code mods.toml} 的占位符展开，注解要求**编译期常量**，
     * 所以只能手写一份 —— 改 {@code gradle.properties} 的 {@code mod_version} 时
     * 必须同步这里（`dev-check/check-version.py` 会核对两者一致）。
     */
    public static final String VERSION = "1.0.0-1.12.2";

    /**
     * 1.12.2 的运行时 classpath 里 log4j2 是标配（MC 自己也用它），
     * 与 1.16.5 线同一个选择；**不要**引入 slf4j（那一条在 1.16.5 踩过
     * {@code NoClassDefFoundError: org/slf4j/LoggerFactory}）。
     */
    public static final Logger LOGGER = LogManager.getLogger("LogiScope");

    /**
     * 全局唯一的探针管理器。所有层都通过它拿数据。
     *
     * 它持有**多个** {@code ProbeInstance}，并且是每客户端 / 每服务端 tick 驱动采样的唯一入口。
     */
    private static final ProbeManager PROBES = new ProbeManager();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("[LogiScope] {} {} preInit (MC 1.12.2 / Forge 14.23.5.2859)", NAME, VERSION);

        // 快捷键：注册键位 + 把 KeyBindings 挂上游戏总线（tick 轮询在里面）
        KeyBindings.register();

        // 探针驱动：客户端 tick（状态机 / 时钟）与服务端 tick（单人下的真值采样）
        MinecraftForge.EVENT_BUS.register(ProbeManager.class);

        // 「Probe data」：抓 `data get block` 的系统回执；以及命令回执的超时计时
        MinecraftForge.EVENT_BUS.register(DataCommand.class);

        // 记录最近一次右键的方块（容器界面的持久锚点）
        MinecraftForge.EVENT_BUS.register(ContainerAnchor.class);

        // 探针追踪目标的世界内高亮框（穿墙、1 Hz 红黄闪）：渲染 + 换世界时清理
        MinecraftForge.EVENT_BUS.register(ProbeHighlight.class);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // 配置由 FML 注入（@Config 类），这里只记一行，方便排查"配置没生效"
        LOGGER.info("[LogiScope] config: bufferSamples={} maxProbes={} maxItemKinds={} dataRefreshTicks={}",
                LogiScopeConfig.bufferSamples(), LogiScopeConfig.maxProbes(),
                LogiScopeConfig.maxItemKinds(), LogiScopeConfig.dataRefreshTicks());
        LOGGER.info("[LogiScope] init done");
    }

    public static ProbeManager probes() {
        return PROBES;
    }
}
