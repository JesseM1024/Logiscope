package com.jessetech.logiscope.collect;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.inventory.IInventory;
import net.minecraft.world.World;
import net.minecraft.tileentity.TileEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集层：**无 GUI 的容器**的槽位清单。
 *
 * 【为什么需要它】
 * 可视化选格依赖"菜单槽在屏幕上的位置"，而菜单只在界面开着时存在。
 * 但需求里有一类目标根本没有界面（准星对准一台只有物品栏、没有 GUI 的机器），
 * 这时只能退回"按下标列表选格"——而**列表本身也得有内容**：
 * 机器里有几格、每格现在装着什么，客户端一概不知道（内容只在 GUI 打开时同步）。
 * 所以这份清单必须由服务端线程读出来（IInventory 或 ItemHandler 能力），
 * 再异步交回客户端。
 *
 * 【为什么可以用静态单例】
 * 同一时刻只可能有一个"正在配置的探针"（面板是单例界面），
 * 所以这里不需要每实例一份。这一点和 {@link WorldProbe} 正相反 ——
 * 那个必须每实例一份，因为所有运行中的探针会**同时**读值。
 *
 * 与 {@code WorldProbe#readNow} 的分工：那个是"按探针目标定期读值"，
 * 这个是"给选格页临时列一次清单"。
 */
public final class ProbeSlots {

    public enum Status {
        /** 还没查过（或正在查） */
        UNKNOWN,
        /** 查到了：是不是容器看 {@link #slots()} 是否为空 */
        OK,
        /** 这个方块既不是 IInventory 也没有 item handler —— 它根本不含物品栏 */
        NONE,
        /** 区块没加载 / 不在单人存档 */
        UNAVAILABLE
    }

    /** 单次列清单的上限，防御性地挡住某个模组报出几千个槽位 */
    private static final int MAX_SLOTS = 512;

    private static volatile List<ContainerAccess.SlotView> slots = java.util.Collections.emptyList();
    private static volatile Status status = Status.UNKNOWN;
    private static volatile boolean pending;

    private static volatile RegistryKey<World> queriedDim;
    private static volatile BlockPos queriedPos;

    private ProbeSlots() {
    }

    /** 当前清单（无 GUI 容器用；menuSlotIndex 恒为 -1，containerSlotIndex 是机器内部下标） */
    public static List<ContainerAccess.SlotView> slots() {
        return slots;
    }

    public static Status status() {
        return status;
    }

    /** 正在查的目标坐标；界面用它判断"清单是不是还要重查" */
    @Nullable
    public static BlockPos queryPos() {
        return queriedPos;
    }

    /**
     * 请求（或复用）某个坐标的槽位清单。**每客户端 tick 调一次即可**，
     * 目标没变且已经拿到结果时直接返回。
     */
    public static void request(@Nullable RegistryKey<World> dim, @Nullable BlockPos pos) {
        if (dim == null || pos == null) {
            reset();
            return;
        }
        if (pos.equals(queriedPos) && dim.equals(queriedDim) && status != Status.UNKNOWN) {
            return;
        }
        if (pos.equals(queriedPos) && dim.equals(queriedDim) && pending) {
            return;
        }
        queriedDim = dim;
        queriedPos = pos.toImmutable();
        pending = true;
        status = Status.UNKNOWN;
        slots = java.util.Collections.emptyList();

        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            pending = false;
            status = Status.UNAVAILABLE;
            return;
        }
        RegistryKey<World> d = queriedDim;
        BlockPos p = queriedPos;
        try {
            server.execute(() -> {
                List<ContainerAccess.SlotView> out = readNow(server, d, p);
                slots = out == null ? java.util.Collections.emptyList() : out;
                status = out == null ? Status.NONE : Status.OK;
                pending = false;
            });
        } catch (Throwable ignored) {
            pending = false;
            status = Status.UNAVAILABLE;
        }
    }

    public static void reset() {
        slots = java.util.Collections.emptyList();
        status = Status.UNKNOWN;
        pending = false;
        queriedDim = null;
        queriedPos = null;
    }

    /** 必须在服务端线程执行 ；null = 这个方块不含物品栏 */
    @Nullable
    private static List<ContainerAccess.SlotView> readNow(net.minecraft.server.MinecraftServer server,
                                                          RegistryKey<World> dim, BlockPos pos) {
        ServerWorld level = server.getWorld(dim);
        if (level == null || !level.isBlockLoaded(pos)) {
            return null;
        }
        TileEntity be = level.getTileEntity(pos);
        if (be == null) {
            return null;
        }
        List<ContainerAccess.SlotView> out = new ArrayList<>();

        if (be instanceof IInventory) {
            IInventory container = (IInventory) be;
            int n = Math.min(container.getSizeInventory(), MAX_SLOTS);
            for (int i = 0; i < n; i++) {
                out.add(ContainerAccess.make(-1, i, container.getStackInSlot(i)));
            }
            return out;
        }

        net.minecraftforge.items.IItemHandler handler = WorldProbe.itemHandler(level, pos, be);
        if (handler == null) {
            return null;
        }
        int n = Math.min(handler.getSlots(), MAX_SLOTS);
        for (int i = 0; i < n; i++) {
            out.add(ContainerAccess.make(-1, i, handler.getStackInSlot(i)));
        }
        return out;
    }
}
