package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.collect.ContainerAccess;
import com.jessetech.logiscope.ui.ScopeScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 绑定层：把「按键」翻译成「打开/收起示波器面板的意图」。
 *
 * ★ 关键逻辑路径 CP2 ★
 * vanilla 的 KeyMapping 只在【没有界面打开】时才会被 tick 消费。
 * 而本模组的核心场景恰恰是「玩家的容器界面开着」——
 * 此时按键被当前 Screen 吃掉，KeyMapping 完全收不到。
 *
 * 解法：监听 ScreenEvent.KeyPressed.Pre。
 * 这个事件在 Screen 处理按键【之前】触发，且可以 cancel，
 * 因此我们既能拿到按键，又能阻止它被原界面继续处理。
 *
 * ★★ 两个必须记住的坑 ★★
 *
 * 坑 1：不要在输入框里抢键。
 *   玩家在 JEI 搜索框里打 "hopper"，那个 'o' 一样会走到这个事件里来。
 *   所以先判断"当前是不是有文本输入被聚焦"，是就完全不响应。
 *
 * 坑 2：KeyMapping#matches **不看修饰键**。
 *   源码只有一句比较：key.getType() == KEYSYM && key.getValue() == keyCode。
 *   所以把快捷键设成 Ctrl+O 毫无意义 —— 单独按 o 一样命中。
 *   真正的出路是换一个**根本不产生字符**的键（本项目最终用 F9）+ 上面的三层守卫。
 *
 * ★ 第九轮：打开面板的入口从 1 条变成 3 条（需求第 3 条）★
 *
 * ```
 * 按下 F9
 *   ├─ 面板已经开着            → 收起
 *   ├─ 容器界面开着            → 浮层压在它上面（根页）
 *   └─ 屏幕是空的（在世界上）  → 看准星：
 *        ├─ 准星是"有 GUI 的容器方块" → 模拟右键把界面打开；界面一开就自动压上面板（根页）
 *        ├─ 准星是"无 GUI 的容器方块" → 直接新建探针并进入选格页（列表选格）
 *        └─ 其它 / 没指到方块        → 打开根页（选一个已创建的探针来看）
 * ```
 *
 * 目的很直白：**指着哪台设备按快捷键，就是在说"我要探测这一台"**，
 * 不用先右键开界面、再按快捷键、再在列表里找。
 */
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.logiscope";

    /** 长按连发的去抖窗口（tick）。20 tick = 1 秒 */
    private static final int REPEAT_GUARD_TICKS = 5;

    /** "模拟右键打开界面"之后，等界面出现的最大 tick 数（超时就放弃，别让它挂着） */
    private static final int PENDING_OVERLAY_TICKS = 60;

    /** 上一次真正响应快捷键的 tick */
    private static long lastHotkeyTick = Long.MIN_VALUE / 2;

    /**
     * 已经模拟过右键、正在等服务端把容器界面打开 —— 界面一开就把探针面板压上去。
     *
     * 为什么必须跨 tick：只有服务端能开菜单（`ServerPlayer#openMenu` + OpenScreen 包），
     * 所以客户端发出交互之后，界面要到 1~3 tick 后才出现。那才是能压图层的时机
     * （见 {@link #onScreenInit}）。
     */
    private static boolean pendingOverlay;
    private static long pendingOverlayUntil;

    /**
     * 默认键位：`F9`。
     *
     * ★ 为什么最后选了功能键，而不是符号键 ★
     * 一路试过 `O` → `]`，两次都被"打字"问题咬到：
     *   · `O`：在 JEI 搜索框里打 `hopper`，那个 `o` 直接弹面板
     *   · `]`：三层守卫能挡住大部分情况，但"在搜索框里打完字 → 点外面失焦 → 再按 `]`"
     *          这条路上仍然会多拦一次（守卫第 3 层的固有代价）
     * **功能键根本不产生字符**，键盘输入这条冲突通道从根上就不存在了。
     * 代价是 `F9` 离左手主键区远、笔记本上可能要按 Fn —— 换来的是"永不会误触发"。
     *
     * 三层守卫（TextInputGuard）保留着，保的是另外两种情况：
     *   1. 用户自己把快捷键改回字母/符号键
     *   2. 某些模组的输入框会把所有按键都吃掉（连 F 键也不放过）
     *
     * ⚠️ 改这个默认值**不会**影响已经存过的老绑定 —— 按键绑定会被写进
     *   `run/options.txt`（形如 `key_logiscope.key.open_scope:key.keyboard.right.bracket`），
     *   那份存档优先。所以换了默认值之后，要么删掉 options.txt 里那一行，
     *   要么在「选项 → 控制」里手动重绑。
     */
    public static final KeyMapping OPEN_SCOPE = new KeyMapping(
            "logiscope.key.open_scope",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F9,
            CATEGORY);

    private KeyBindings() {
    }

    /** 由主类在模组总线上调用 */
    public static void register(IEventBus modBus) {
        modBus.addListener(KeyBindings::onRegisterKeyMappings);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SCOPE);
    }

    /**
     * ★ 关键逻辑路径 CP2 的实现 ★
     *
     * 必须是 public：主类在另一个包，要用方法引用注册到事件总线。
     *
     * ★ 为什么要做去抖 ★
     * 这个事件对「按下」和「长按连发」都会触发（KeyboardHandler 里 action==1 || action==2
     * 都走 screen 这条分支），而且事件本身不带 action 信息。
     * 不去抖的话，按住快捷键会以每秒 ~30 次的频率把面板开了又关。
     */
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!OPEN_SCOPE.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // 连发去抖：同一个按键动作 5 tick（=0.25 秒）内只认一次
        long now = LogiScopeMod.probes().currentTick();
        if (now - lastHotkeyTick < REPEAT_GUARD_TICKS) {
            event.setCanceled(true);
            return;
        }

        Screen screen = event.getScreen();

        // ★ 正在打字就别抢键 ★
        // 判定逻辑单独放在 TextInputGuard（三层守卫，含 JEI 搜索框探针）
        if (TextInputGuard.isTypingNow(screen)) {
            return;
        }

        // 面板已经开着 → 再按一次就是收起（开关语义）
        if (screen instanceof ScopeScreen scope) {
            lastHotkeyTick = now;
            scope.closeScope();
            event.setCanceled(true);
            return;
        }

        // 有外部容器界面 → 压一层浮层，原界面继续画在下面
        if (ContainerAccess.hasForeignContainerOpen() && !isOwnInventoryScreen(screen)) {
            lastHotkeyTick = now;
            ScopeScreen.openOverlay(mc, ScopeScreen.Page.ROOT, -1);
            event.setCanceled(true);
            return;
        }

        // 剩下的是"有界面但不是容器界面"（自己的背包、聊天、模组的自绘界面）：
        // 准星在界面打开时不可信地指向某处，所以这一支不做准星判定，直接开根页。
        // 想准星探测就先把界面关掉 —— 需求第 3 条的前提就是"没有打开容器"。
        lastHotkeyTick = now;
        ScopeScreen.openStandalone(mc, ScopeScreen.Page.ROOT, -1);
        event.setCanceled(true);
    }

    /**
     * 没有界面打开时，上面的 Screen 事件**根本不会触发**，
     * 按键这时才走标准的 KeyMapping 路径（Minecraft#handleKeybinds 只在 screen == null 时跑）。
     * 所以想"关掉容器界面之后还能按快捷键把面板叫出来看波形"，必须在这里补一条。
     *
     * ★ 无论有没有界面打开都要把点击队列排空 ★
     * 否则界面开着时攒下的点击会在面板关掉的那一刻集中兑现，把面板又立刻弹回来。
     * 排空之后只有在"当前没有界面"时才真的响应。
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        expirePendingOverlay();

        boolean clicked = false;
        while (OPEN_SCOPE.consumeClick()) {
            clicked = true;
        }

        if (!clicked || mc.screen != null) {
            return;
        }
        lastHotkeyTick = LogiScopeMod.probes().currentTick();
        openFromCrosshair(mc);
    }

    // ==================================================================
    // 准星判定（需求第 3 条）
    // ==================================================================

    /**
     * 没有容器界面时按快捷键的完整分支。
     *
     * 注意 `Minecraft#tick` 里 `gameRenderer.pick(1.0F)` 是**无条件**每 tick 调一次的
     * （在 screen 判断之前），所以只要世界加载着，`mc.hitResult` 就是这一 tick 的准星结果，
     * 不需要我们自己去射线检测。
     */
    private static void openFromCrosshair(Minecraft mc) {
        if (mc.level == null) {
            ScopeScreen.openStandalone(mc, ScopeScreen.Page.ROOT, -1);
            return;
        }
        BlockHitResult hit = ProbeContext.crosshairBlock(mc);
        if (hit == null) {
            // 准星上没有方块实体 → 打开根目录，选一个已创建的探针来监测
            ScopeScreen.openStandalone(mc, ScopeScreen.Page.ROOT, -1);
            return;
        }

        BlockPos pos = hit.getBlockPos();

        // 情况 A：这个方块右键会开界面 → 先把界面打开（顺带拿到"维度+坐标"锚点），
        //         界面一出现就把探针面板压上去（见 PENDING_OVERLAY / onScreenInit）
        if (ProbeContext.hasMenuProvider(mc.level, pos)) {
            if (ProbeContext.tryOpenContainerGui(mc, hit)) {
                pendingOverlay = true;
                pendingOverlayUntil = LogiScopeMod.probes().currentTick() + PENDING_OVERLAY_TICKS;
            } else {
                // 手上物品会抢走这次右键（扳手/工具类），此时硬发交互可能改动机器。
                // 不冒险，退回根页，并让面板自己提示用户空手再试。
                ScopeScreen.openStandalone(mc, ScopeScreen.Page.ROOT, -1);
                ScopeScreen.flashOnOpen("logiscope.flash.hand_blocks_use");
            }
            return;
        }

        // 情况 B：有容器方块实体但没有界面（机械手这类）→ 直接进入这台设备的创建流程。
        //         没有菜单 → 可视化选格用不了，选格页会自动切成"列表选格"。
        if (ProbeContext.isContainerLike(mc.level, pos)) {
            ScopeScreen.openWorldCreate(mc, mc.level.dimension(), pos.immutable(),
                    ProbeContext.blockName(mc.level, pos));
            return;
        }

        // 情况 C：有方块但既不是容器也没界面 → 等同"没有目标"
        ScopeScreen.openStandalone(mc, ScopeScreen.Page.ROOT, -1);
    }

    private static void expirePendingOverlay() {
        if (pendingOverlay && LogiScopeMod.probes().currentTick() > pendingOverlayUntil) {
            // 服务端一直没开界面（太远 / 被拒）。别让这个标志挂着 ——
            // 否则用户下一次正常开任何容器时，面板会莫名其妙自己弹出来。
            pendingOverlay = false;
        }
    }

    /**
     * 由 ScreenEvent.Init.Post 调用：
     *   1. 容器界面初始化完成时，把它和"玩家右键的那个方块"关联起来，得到持久身份（维度 + 坐标）
     *   2. 如果是我们刚刚模拟右键开出来的界面，顺手把探针面板压上去
     *
     * 这是"关掉界面之后还能找回来"的唯一可靠时机，见 ContainerAnchor 的类注释。
     */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ContainerAnchor.onContainerScreenOpened(containerScreen.getMenu().containerId);

        if (pendingOverlay) {
            pendingOverlay = false;
            // 此刻 Minecraft#setScreen 已经把 mc.screen 赋成这个容器界面了
            // （它先赋值、再调 init，而 Init.Post 在 init 末尾发），所以压图层的父界面正确。
            ScopeScreen.openOverlay(mc, ScopeScreen.Page.ROOT, -1);
        }
    }

    /**
     * 当前界面是不是"玩家自己的背包界面"。
     *
     * 单人的生存背包走 player.inventoryMenu，ContainerAccess 已经排除了；
     * 但**创造模式的背包不走它** —— 它有自己的 ItemPickerMenu，
     * 于是「containerMenu != inventoryMenu」为真，会被误判成"外部设备"。
     * 实测表现：创造模式里在背包界面按快捷键会弹出面板（面板上还会列出一堆
     * 创造物品标签页的内容）。这不是崩溃，但语义是错的。
     *
     * 两种背包界面共同的基类是 EffectRenderingInventoryScreen，
     * 按基类排除即可，也不必分别引用两个具体类。
     */
    private static boolean isOwnInventoryScreen(@Nullable Screen screen) {
        return screen instanceof EffectRenderingInventoryScreen<?>;
    }
}
