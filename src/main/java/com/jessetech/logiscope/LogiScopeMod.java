package com.jessetech.logiscope;

import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.KeyBindings;
import com.jessetech.logiscope.bind.TextInputGuard;
import com.jessetech.logiscope.collect.Sampler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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

        // 打开/收起示波器面板。GUI 打开时 vanilla 的 KeyMapping 不走全局输入，
        // 必须拦 Screen 的按键事件，见 KeyBindings#onScreenKeyPressed
        //
        // 注意这里用了显式类型实参 <ScreenEvent.KeyPressed.Pre>：
        // EventBus#addListener 有多个重载，直接传方法引用会让 javac 推断失败
        // （报 "不兼容的类型: Event无法转换为Pre"）。写明类型即可。
        NeoForge.EVENT_BUS.<ScreenEvent.KeyPressed.Pre>addListener(KeyBindings::onScreenKeyPressed);

        // 容器界面刚打开时，把"玩家右键的方块"记成持久锚点（维度 + 坐标）
        NeoForge.EVENT_BUS.<ScreenEvent.Init.Post>addListener(KeyBindings::onScreenInit);

        // 「玩家是不是正在打字」的最后一道通用守卫：
        // 靠 CharacterTyped 的 Pre/Post 计数差判断上一个字符有没有被界面吃掉。
        // 原理见 TextInputGuard 的类注释（含对 KeyboardHandler#charTyped 的核实结论）。
        NeoForge.EVENT_BUS.<ScreenEvent.CharacterTyped.Pre>addListener(TextInputGuard::onCharTypedPre);
        NeoForge.EVENT_BUS.<ScreenEvent.CharacterTyped.Post>addListener(TextInputGuard::onCharTypedPost);

        // 记录最近一次右键的方块。单人下集成服务端也会收到同一个事件，
        // ContainerAnchor 内部用 isClientSide 过滤掉了。
        NeoForge.EVENT_BUS.<PlayerInteractEvent.RightClickBlock>addListener(ContainerAnchor::onRightClickBlock);

        // 每客户端 tick：锚点计时 + 采样
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(Sampler::onClientTick);

        // 没有界面打开时，Screen 按键事件不触发；补一条走标准 KeyMapping 的路径，
        // 这样关掉容器界面之后还能按快捷键把面板叫出来
        NeoForge.EVENT_BUS.<ClientTickEvent.Post>addListener(KeyBindings::onClientTick);

        LOGGER.info("[LogiScope] client-only mod constructed");
    }

    public static Sampler sampler() {
        return SAMPLER;
    }
}
