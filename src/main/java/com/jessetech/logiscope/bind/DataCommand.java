package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import com.jessetech.logiscope.compat.Dim;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.World;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 「Probe data」的取数通道。
 *
 * 直读（单人，无需任何权限）*   单人下集成服务端就在同一个进程里：把"读方块实体 NBT"这个活**提交到服务端线程**
   （`server.addScheduledTask(...)`，同 CP15 的"取样点放权威一侧"纪律），把 SNBT 文本带回来。
   不发命令、不碰聊天回执、不看权限 —— 这才是"实时显示 NBT"的正确姿势。
 *
 * 命令路（多人，需要权限）—— 直读的**降级路径**：
 *   多人下客户端进程里没有方块实体，原版又没有"读完整 NBT"的包，只能
 *   `data get block`：先查 `player.hasPermissionLevel(2)`，没有就如实说"没有权限"，
 *   不发出去被服务端打回。回执抓到后**吞掉**，不在聊天框刷屏。
 *
 * 【与采样的关系】两条路都只是"看一眼"；真正逐 tick 的采样走
 * {@code DataProbe}（服务端直读，天然免权限）—— 所以"看不了但能采"永远成立。
 */
public final class DataCommand {

    public enum Status {
        /** 还没请求过 */
        IDLE,
        /** 已发出，等结果 */
        WAITING,
        /** 拿到了（文本里有 NBT） */
        OK,
        /** 多人且没有命令权限（直读在多人下不可用） */
        NO_PERMISSION,
        /** 拿不到：没有方块实体 / 维度不对 */
        FAIL
    }

    /** 结果超时（客户端 tick）：1 秒没等到就当失败，不然状态永远卡在 WAITING */
    private static final long TIMEOUT_TICKS = 20;

    /** 这些字段会被服务端线程（直读）与渲染线程（读显示）同时碰 → volatile */
    private static volatile String text = "";
    private static volatile Status status = Status.IDLE;
    private static volatile boolean waiting;
    private static long requestedAt;
    private static long clientTicks;

    private DataCommand() {
    }

    /** 每客户端 tick 调一次：推进时钟 + 超时判定 */
    public static void tick() {
        clientTicks++;
        if (waiting && clientTicks - requestedAt > TIMEOUT_TICKS) {
            waiting = false;
            status = Status.FAIL;
        }
    }

    /**
     * 请求一次设备的完整 NBT。
     * 单人 → 服务端线程直读（免权限）；多人 → 降级为命令（需权限）。
     */
    public static void request(@Nullable Dim dim, @Nullable BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        if (dim == null || pos == null) {
            status = Status.FAIL;
            return;
        }
        MinecraftServer server = mc.getIntegratedServer();
        if (server != null) {
            // ---- 直读：提交到服务端线程，结果直接写 volatile 字段 ----
            waiting = true;
            requestedAt = clientTicks;
            status = Status.WAITING;
            try {
                server.addScheduledTask(() -> {
                    String nbt = readOnServer(server, dim, pos);
                    text = nbt == null ? "" : nbt;
                    status = nbt == null ? Status.FAIL : Status.OK;
                    waiting = false;
                });
            } catch (Exception e) {
                waiting = false;
                status = Status.FAIL;
                LogiScopeMod.LOGGER.warn("[LogiScope] direct nbt read failed: {}", e.toString());
            }
            return;
        }
        // ---- 多人：这一代没有可用的降级通道 ----
        // ★ 1.12.2 **没有 `/data` 命令**（`/data` 是 1.13 才引入的），
        //   客户端进程里又没有方块实体 ⇒ 多人下**结构性地**读不到设备 NBT。
        //   所以这里如实报"读不到"，**不发**一条注定被服务端打回的命令
        //   （发了也只会在聊天框里留一句红字，还顺带让状态机多等一整个超时）。
        //   注意：逐 tick 的**采样**不受影响 —— 数据探针的采样走 DataProbe 的服务端直读，
        //   而多人下服务端根本不在本进程里，所以那条路本来就只在单人成立（见 PORT-1.12.2 §五）。
        status = Status.NO_PERMISSION;
        text = "";
    }

    /** 在**服务端线程**上读方块实体的 SNBT 文本。没有方块实体 / 维度不对返回 null */
    @Nullable
    private static String readOnServer(MinecraftServer server, Dim dim, BlockPos pos) {
        WorldServer level = server.getWorld(dim.id());
        if (level == null) {
            return null;
        }
        TileEntity be = level.getTileEntity(pos);
        if (be == null) {
            return null;
        }
        NBTTagCompound tag = be.writeToNBT(new NBTTagCompound());
        return tag.toString();
    }

    /**
     * 系统消息到达。
     *
     * ★ 1.12.2 **这条路径已经不会再被走到**：这一代没有 `/data`，多人分支改成如实报"读不到"
     * （见 {@link #request}）。留着是为了"命令路在别的世代还能用"时不必重写解析 ——
     * 但本线只编译 1.12.2，所以它实际上是死代码，别在排查时被它误导。
     */
    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        // 1.20.1 无 System 子类型（20.4+ 才拆分）；事件自带 isSystem()，
        // `data get block` 的回执即系统消息
        if (event.getType() != ChatType.SYSTEM) {
            return;
        }
        if (!waiting || event.isCanceled()) {
            return;
        }
        waiting = false;
        ITextComponent msg = event.getMessage();
        String s = msg.getUnformattedText();
        text = s;
        // `data get block` 成功的回复一定带一个复合标签；不带的都是错误信息
        status = s.indexOf('{') >= 0 ? Status.OK : Status.FAIL;
        event.setCanceled(true);
    }

    @Nullable
    public static String text() {
        return text;
    }

    public static Status status() {
        return status;
    }

    /** 状态对应的一句人话（界面直接用） */
    public static String statusKey() {
        switch (status) {
            case IDLE:
                return "logiscope.data.status.idle";
            case WAITING:
                return "logiscope.data.status.waiting";
            case OK:
                return "logiscope.data.status.ok";
            case NO_PERMISSION:
                return "logiscope.data.status.no_permission";
            case FAIL:
                return "logiscope.data.status.fail";
                        default:
                            return "logiscope.data.status.fail";
        }
    }

    public static int statusColor() {
        switch (status) {
            case OK:
                return 0xFF5DCAA5;
            case WAITING:
                return 0xFFEF9F27;
            case NO_PERMISSION:
                return 0xFFE8A0C8;
            case FAIL:
                return 0xFFE86A6A;
            case IDLE:
                return 0xFF8A8AA0;
                        default:
                            return 0xFF8A8AA0;
        }
    }

    /** 进世界时清残留：上一局的 NBT 文本与状态不应带到下一局（与 clearAll 同一时刻调用） */
    public static void reset() {
        text = "";
        status = Status.IDLE;
        waiting = false;
    }

    /** 测试/调试用：直接塞一段文本（离线断言与手填路径时用不到读取） */
    public static void injectForTest(String raw) {
        text = raw;
        status = raw != null && raw.indexOf('{') >= 0 ? Status.OK : Status.FAIL;
    }
}
