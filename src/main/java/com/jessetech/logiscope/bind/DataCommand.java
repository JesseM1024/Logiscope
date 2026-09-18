package com.jessetech.logiscope.bind;

import com.jessetech.logiscope.LogiScopeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 「Probe data」的取数通道（第十一轮③）：把 {@code data get block} 的结果抓回来给界面看。
 *
 * 【为什么用命令而不是直接读】客户端手上没有方块实体的完整 NBT（服务端只同步了
 * 渲染/界面上需要的那部分）。`data get block` 是原版提供的唯一"完整 NBT 出口"，
 * 代价是它需要**命令权限**（单人开作弊 / 管理员）。
 *
 * ★ "尝试无需管理员权限"的落地方式 ★
 *   · 先查 `player.hasPermissions(2)`：没有权限就**不发命令**，界面上如实说
 *     "需要命令权限"，而不是发出去、被服务端拒绝、然后显示一句看不懂的报错；
 *   · 采样本身**完全不依赖命令**（服务端直接读方块实体，见 {@code DataProbe}）——
 *     所以"看不了但能采"是可能的：用户可以用 /data 手抄一条路径，或用别的办法
 *     拿到路径后直接填。权限不足时界面会把这句话说清楚。
 *
 * 【回执怎么认】命令回复是系统消息。发出去之后把 `waiting` 立起来，
 * **下一条系统消息**就当作回执收下（内容含 `{` 视为成功）。
 * 一条消息就清标志，所以不会把后面的无关消息也算进去；
 * 同时**吞掉这条回执**（不让它刷聊天框）——面板上已经显示了。
 */
public final class DataCommand {

    public enum Status {
        /** 还没请求过 */
        IDLE,
        /** 已发出，等回执 */
        WAITING,
        /** 拿到了（文本里有 NBT） */
        OK,
        /** 拿不到：没有命令权限 */
        NO_PERMISSION,
        /** 拿不到：回执不是 NBT（没有方块实体 / 路径写错 / 超时） */
        FAIL
    }

    /** 回执超时（客户端 tick）：1 秒没等到就当失败，不然状态永远卡在 WAITING */
    private static final long TIMEOUT_TICKS = 20;

    private static String text = "";
    private static Status status = Status.IDLE;
    private static boolean waiting;
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

    /** 请求一次 `data get block <pos>`。权限不足时直接置 NO_PERMISSION，不发命令 */
    public static void request(@Nullable BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.player == null || mc.getConnection() == null) {
            status = Status.FAIL;
            return;
        }
        if (!mc.player.hasPermissions(2)) {
            status = Status.NO_PERMISSION;
            text = "";
            return;
        }
        String cmd = "data get block " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        waiting = true;
        requestedAt = clientTicks;
        status = Status.WAITING;
        try {
            mc.getConnection().sendCommand(cmd);
        } catch (Exception e) {
            waiting = false;
            status = Status.FAIL;
            LogiScopeMod.LOGGER.warn("[LogiScope] data get failed: {}", e.toString());
        }
    }

    /** 系统消息到达：如果我们在等回执，就收下它并吞掉这条聊天消息 */
    public static void onChatReceived(ClientChatReceivedEvent.System event) {
        if (!waiting || event.isCanceled()) {
            return;
        }
        waiting = false;
        Component msg = event.getMessage();
        String s = msg.getString();
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
        return switch (status) {
            case IDLE -> "logiscope.data.status.idle";
            case WAITING -> "logiscope.data.status.waiting";
            case OK -> "logiscope.data.status.ok";
            case NO_PERMISSION -> "logiscope.data.status.no_permission";
            case FAIL -> "logiscope.data.status.fail";
        };
    }

    public static int statusColor() {
        return switch (status) {
            case OK -> 0xFF5DCAA5;
            case WAITING -> 0xFFEF9F27;
            case NO_PERMISSION -> 0xFFE8A0C8;
            case FAIL -> 0xFFE86A6A;
            case IDLE -> 0xFF8A8AA0;
        };
    }

    /** 测试/调试用：直接塞一段文本（离线断言与手填路径时用不到命令） */
    public static void injectForTest(String raw) {
        text = raw;
        status = raw != null && raw.indexOf('{') >= 0 ? Status.OK : Status.FAIL;
    }
}
