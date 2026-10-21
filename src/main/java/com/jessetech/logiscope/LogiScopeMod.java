package com.jessetech.logiscope;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * LogiScope —— 纯客户端物流探测示波器（MC 1.12.2 线）。
 *
 * <h2>这一代与另外三条线的差别（都写在 docs/PORT-1.12.2.md，动手前先读）</h2>
 * <ul>
 *   <li><b>没有 mods.toml</b>：元数据是 {@code src/main/resources/mcmod.info}，
 *       模组声明靠 {@link Mod} 注解本身。</li>
 *   <li><b>客户端专用靠注解</b>：{@code clientSideOnly = true}（1.13+ 才改成
 *       运行时判 {@code FMLEnvironment.dist}）。专用的服务端会直接跳过本模组。</li>
 *   <li><b>只有游戏总线</b>：这一代没有 {@code IEventBus} / 模组总线，
 *       所有监听一律 {@code MinecraftForge.EVENT_BUS.addListener(...)} /
 *       {@code @SubscribeEvent} 静态方法。</li>
 *   <li><b>生命周期是事件方法</b>：{@code @Mod.EventHandler} 的
 *       {@code preInit} / {@code init} / {@code postInit}，不是构造器。</li>
 *   <li><b>没有 GUI 图层栈</b>（{@code pushGuiLayer} 是 1.13+ 才有的）——
 *       面板只能自己当一层 {@code GuiScreen} 打开，见 CP8 的 1.12.2 妥协方案。</li>
 * </ul>
 *
 * <h2>当前状态：骨架</h2>
 * 本类目前只做"能被 FML 加载 + 打一行日志"。其余模块按 docs/PORT-1.12.2.md 的
 * 顺序逐轮搬过来（纯逻辑层已在 {@code analysis} / {@code store} / {@code archive}）。
 * <p>
 * 待搬清单（对应 1.16.5 线的同名文件，可 {@code git show 1.16.5-forge:<路径>} 取回）：
 * {@code LogiScopeConfig}（→ {@code Configuration} + {@code @Config}）、
 * {@code bind/*}、{@code collect/*}、{@code ui/*}。
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
     * 必须同步这里（offline-check 会核对两者一致）。
     */
    public static final String VERSION = "0.1.0-1.12.2";

    /**
     * 1.12.2 的运行时 classpath 里 log4j2 是标配（MC 自己也用它），
     * 与 1.16.5 线同一个选择；**不要**引入 slf4j（那一条在 1.16.5 踩过
     * {@code NoClassDefFoundError: org/slf4j/LoggerFactory}）。
     */
    public static final Logger LOGGER = LogManager.getLogger("LogiScope");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("[LogiScope] {} {} preInit (MC 1.12.2 / Forge 14.23.5.2859)", NAME, VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // 这里将来要接：快捷键注册（ClientRegistry.registerKeyBinding）、
        // 游戏总线上的 GuiScreenEvent / TickEvent / ClientChatReceivedEvent 监听。
        LOGGER.info("[LogiScope] init done (skeleton — 功能模块尚未搬完，见 docs/PORT-1.12.2.md)");
    }
}
