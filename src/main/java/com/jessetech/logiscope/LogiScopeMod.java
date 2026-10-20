package com.jessetech.logiscope;

import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.DataCommand;
import com.jessetech.logiscope.bind.KeyBindings;
import com.jessetech.logiscope.bind.TextInputGuard;
import com.jessetech.logiscope.collect.ProbeManager;
import com.jessetech.logiscope.collect.ProbeSlots;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * LogiScope —— 纯客户端物流探测示波器。
 *
 * 【客户端专用】Forge 1.20.1 的 @Mod 无 dist 参数（20.2+ 才引入），改为构造时检查
 * {@code FMLEnvironment.dist}：非客户端环境仅记一条日志后返回、不注册任何监听 ——
 * 服务端无需安装本模组，也不会因缺少客户端类而崩溃。
 *
 * 事件总线分工：
 *   - modBus（模组总线）：只在初始化阶段触发的事件，如注册快捷键
 *   - MinecraftForge.EVENT_BUS（游戏总线）：运行期反复触发的事件，如按键、客户端 tick
 */
@Mod(LogiScopeMod.MODID)
public final class LogiScopeMod {

    public static final String MODID = "logiscope";
    /**
     * ★ 1.16.5 只能用 log4j2，**不能**用 slf4j。
     *
     * 实测崩溃：`NoClassDefFoundError: org/slf4j/LoggerFactory`（`<clinit>` 就炸，
     * mod 在加载阶段被判 "has class loading errors"）。
     * 原因：1.16.5 的运行时 classpath 里只有 `log4j-slf4j18-impl`（绑定层），
     * **没有 slf4j-api**；而编译期它从别处漏进来了，于是"编得过、跑不了"。
     * 1.18.2 / 1.20.1 线有 slf4j-api，所以同一个 import 在那两条线是好的。
     *
     * 1.16.5 的 MC / Forge 自己都用 log4j2（`Minecraft.LOGGER = LogManager.getLogger()`），
     * 换成它即可；方法签名兼容（`warn(String, Object...)` / `debug(String, Throwable)` 都有）。
     */
    public static final Logger LOGGER = LogManager.getLogger("LogiScope");

    /**
     * 全局唯一的探针管理器。所有层都通过它拿数据。
     *
     * 早期这里是"唯一的 Sampler"（只能有一个探头）。现在管理器里面持有
     * **多个** {@code ProbeInstance}（正在运行的探针是一个列表），并且是每客户端 tick
     * 驱动它们采样的唯一入口。
     */
    private static final ProbeManager PROBES = new ProbeManager();

    public LogiScopeMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 非客户端环境：跳过全部客户端注册（1.20.1 的 @Mod 无 dist 参数）
        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.info("[LogiScope] not a client dist, skipping all client registrations");
            return;
        }

        // 注册快捷键（必须在模组总线上）
        KeyBindings.register(modBus);

        // 资源上限配置（客户端类型），落在 config/logiscope-client.toml。
        // 1.20.1 无自动配置界面（ConfigurationScreen 为 20.4+ 引入），亦不注册
        // ConfigScreenHandler 扩展点：配置仅经 TOML 文件修改（已知限制，
        // 见 docs/PORT-1.20.1.md）。
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, LogiScopeConfig.SPEC);

        // 打开/收起示波器面板。GUI 打开时 vanilla 的 KeyBinding 不走全局输入，
        // 必须拦 Screen 的按键事件，见 KeyBindings#onScreenKeyPressed
        //
        // 注意这里用了显式类型实参 <GuiScreenEvent.KeyboardKeyPressedEvent.Pre>：
        // EventBus#addListener 有多个重载，直接传方法引用会让 javac 推断失败
        // （报 "不兼容的类型: Event无法转换为Pre"）。写明类型即可。
        MinecraftForge.EVENT_BUS.<GuiScreenEvent.KeyboardKeyPressedEvent.Pre>addListener(KeyBindings::onScreenKeyPressed);

        // 容器界面刚打开时，把"玩家右键的方块"记成持久锚点（维度 + 坐标）
        MinecraftForge.EVENT_BUS.<GuiScreenEvent.InitGuiEvent.Post>addListener(KeyBindings::onScreenInit);

        // 「玩家是不是正在打字」的最后一道通用守卫：
        // 靠 CharacterTyped 的 Pre/Post 计数差判断上一个字符有没有被界面吃掉。
        // 原理见 TextInputGuard 的类注释（含对 KeyboardHandler#charTyped 的核实结论）。
        MinecraftForge.EVENT_BUS.<GuiScreenEvent.KeyboardCharTypedEvent.Pre>addListener(TextInputGuard::onCharTypedPre);
        MinecraftForge.EVENT_BUS.<GuiScreenEvent.KeyboardCharTypedEvent.Post>addListener(TextInputGuard::onCharTypedPost);

        // 记录最近一次右键的方块。单人下集成服务端也会收到同一个事件，
        // ContainerAnchor 内部用 isClientSide 过滤掉了。
        MinecraftForge.EVENT_BUS.<PlayerInteractEvent.RightClickBlock>addListener(ContainerAnchor::onRightClickBlock);

        // 每客户端 tick：锚点计时 + 全局时钟推进 + 所有探针实例跑状态机
        // （单人下的**采样**不在这里，而在下面的服务端 tick —— 见 ProbeManager 类注释）
        MinecraftForge.EVENT_BUS.<TickEvent.ClientTickEvent>addListener(ProbeManager::onClientTick);

        // 每**服务端** tick：单人下"一个世界 tick 一条真值"的采样点。
        // 集成服务端与客户端同进程（纯客户端模组在专用服务器上不会被加载），
        // 事件在 tickChildren 之后发 —— 读到的就是这一 tick 结束时的状态。
        MinecraftForge.EVENT_BUS.<TickEvent.ServerTickEvent>addListener(ProbeManager::onServerTick);

        // 「Probe data」：抓 `data get block` 的系统回执（只认我们刚发出去的那一条），
        // 并顺手吞掉它 —— 界面已经显示了，不必再刷聊天框
        MinecraftForge.EVENT_BUS.<ClientChatReceivedEvent>addListener(DataCommand::onChatReceived);

        // 命令回执的超时计时（1 秒没回就当失败，不然状态永远卡在"等待"）
        MinecraftForge.EVENT_BUS.<TickEvent.ClientTickEvent>addListener(e -> DataCommand.tick());

        // 进世界 = 清空全部探针 探针绑定"这个世界里的这台设备"，
        // 跨世界留着只会变成"坐标同名但设备不是同一台"的僵尸（实测 A 存档的
        // 探针出现在 B 存档）。顺手把 NBT 显示的残留文本也清掉。
        //
        // 还有两处"看着没事、其实只在同一个世界里成立"的静态缓存 ：
        //   · ContainerAnchor.active/activeContainerId —— 容器 id 只在 0~99 循环，
        //     新世界的第一个容器撞上同一个 id 时它会**直接复用旧世界的锚点**
        //     （于是"当前开着的容器"被判成别的世界的方块）；
        //   · ProbeSlots 的 queriedPos/dim —— 新世界坐标相同时会复用旧世界的槽位清单。
        //   两个 reset 方法早就写好了，只是一直没人调。
        MinecraftForge.EVENT_BUS.<ClientPlayerNetworkEvent.LoggedInEvent>addListener(e -> {
            LogiScopeMod.probes().clearAll();
            DataCommand.reset();
            ContainerAnchor.clearActive();
            ProbeSlots.reset();
        });

        // 没有界面打开时，Screen 按键事件不触发；补一条走标准 KeyBinding 的路径，
        // 这样关掉容器界面之后还能按快捷键把面板叫出来
        MinecraftForge.EVENT_BUS.<TickEvent.ClientTickEvent>addListener(KeyBindings::onClientTick);

        LOGGER.info("[LogiScope] client-only mod constructed");
    }

    public static ProbeManager probes() {
        return PROBES;
    }
}
