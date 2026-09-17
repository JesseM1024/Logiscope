package com.jessetech.logiscope;

import com.jessetech.logiscope.bind.KeyBindings;
import com.jessetech.logiscope.collect.Sampler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LogiScope —— 纯客户端物流探测示波器。
 *
 * 【客户端专用】靠 @Mod 的 dist 参数声明。加载器在专用服务端上会直接跳过这个模组，
 * 所以服务端不需要安装它，也不会因为缺少客户端类而崩溃。
 *
 * 事件总线分工：
 *   - modBus（模组总线）：只在初始化阶段触发的事件，如注册快捷键
 *   - NeoForge.EVENT_BUS（游戏总线）：运行期反复触发的事件，如按键、客户端 tick
 */
@Mod(value = LogiScopeMod.MODID, dist = Dist.CLIENT)
public final class LogiScopeMod {

    public static final String MODID = "logiscope";
    public static final Logger LOGGER = LoggerFactory.getLogger("LogiScope");

    /** 全局唯一的采样器实例。所有层都通过它读数据。 */
    private static final Sampler SAMPLER = new Sampler();

    public LogiScopeMod(IEventBus modBus, ModContainer container) {
        // 注册快捷键（必须在模组总线上）
        KeyBindings.register(modBus);

        // 打开示波器：GUI 打开时 vanilla 的 KeyMapping 不走全局输入，
        // 必须拦 Screen 的按键事件，见 KeyBindings#onScreenKeyPressed
        //
        // 注意这里用了显式类型实参 <ScreenEvent.KeyPressed.Pre>：
        // EventBus#addListener 有多个重载，直接传方法引用会让 javac 推断失败
        // （报 "不兼容的类型: Event 无法转换为 Pre"）。写明类型即可。
        NeoForge.EVENT_BUS.<ScreenEvent.KeyPressed.Pre>addListener(KeyBindings::onScreenKeyPressed);

        // 每客户端 tick 采样一次
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(Sampler::onClientTick);

        LOGGER.info("[LogiScope] client-only mod constructed");
    }

    public static Sampler sampler() {
        return SAMPLER;
    }
}
