/*
 * AE2 反射链的离线实证（1.20.1 / AE2 15.x）：不启动游戏，只把代码里用到的
 * **每一个反射签名**解析一遍。
 *
 * 为什么需要它：AE2 不是本项目的编译期依赖，所有调用都是 `Class.forName` + `getMethod`。
 * 一旦 AE2 改名/换签名，编译期一无所知、实机只会变成"AE 目标恒 no_data"，
 * 而且和"网络没电"长得一模一样。这个程序把签名逐个 getMethod 一遍 ——
 * 有就打印 OK，没有就打印 FAIL 并退出码非 0。
 *
 * 它**不初始化**任何类（Class.forName 的 initialize=false），所以不需要注册表 / 世界。
 *
 * 与 1.21.1 线的差别（AE2 19.x -> 15.x）：
 *   toTagGeneric()                 —— 15.x 无参；19.x 要 HolderLookup.Provider
 *   fromTagGeneric(CompoundTag)    —— 15.x 单参；19.x 要 Provider + Tag
 *   其余（GenericStack / RepoSlot / 读值链）两代一致。
 *
 * 运行（先把 AE2 的 jar 路径换成本机那份；本仓库不含第三方 jar）：
 *   java -cp "build/dev-check;<1.20.1 compileClasspath>;<AE2 15.x jar>" ProbeAeApi
 *
 * 注意：这个程序**不在** offline-check.bat 里（依赖 run/mods 或实例里的第三方 jar）。
 */
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class ProbeAeApi {

    private static int failed;

    public static void main(String[] args) {
        // ---- AeKeys.init() 用到的 ----
        Class<?> aeKey = cls("appeng.api.stacks.AEKey");
        Class<?> compound = cls("net.minecraft.nbt.CompoundTag");
        Class<?> itemStack = cls("net.minecraft.world.item.ItemStack");
        Class<?> generic = cls("appeng.api.stacks.GenericStack");
        Class<?> component = cls("net.minecraft.network.chat.Component");
        Class<?> tagParser = cls("net.minecraft.nbt.TagParser");

        method(aeKey, "toTagGeneric", compound);
        method(aeKey, "fromTagGeneric", aeKey, compound);
        m(aeKey, "getDisplayName").ifPresent(ProbeAeApi::noArg);
        m(component, "getString").ifPresent(ProbeAeApi::noArg);
        method(tagParser, "parseTag", compound, String.class);

        method(generic, "isWrapped", boolean.class, itemStack);
        method(generic, "unwrapItemStack", generic, itemStack);
        m(generic, "what").ifPresent(ProbeAeApi::noArg);

        Class<?> repo = cls("appeng.client.gui.me.common.RepoSlot");
        m(repo, "getStoredAmount").ifPresent(x -> ret(x, long.class));
        // 空/非空判据：用 AE 自己的 getEntry（MC 的 Slot#hasItem 在 1.20.1 生产环境是 SRG 名）
        method(repo, "getEntry", cls("appeng.menu.me.common.GridInventoryEntry"));

        // ---- AeNetworkReader.chain() 用到的 ----
        Class<?> gridHelper = cls("appeng.api.networking.GridHelper");
        Class<?> level = cls("net.minecraft.world.level.Level");
        Class<?> pos = cls("net.minecraft.core.BlockPos");
        Class<?> direction = cls("net.minecraft.core.Direction");
        Class<?> host = cls("appeng.api.networking.IInWorldGridNodeHost");
        Class<?> node = cls("appeng.api.networking.IGridNode");
        Class<?> grid = cls("appeng.api.networking.IGrid");
        Class<?> storageService = cls("appeng.api.networking.storage.IStorageService");
        Class<?> meStorage = cls("appeng.api.storage.MEStorage");
        Class<?> aeItemKey = cls("appeng.api.stacks.AEItemKey");
        Class<?> keyCounter = cls("appeng.api.stacks.KeyCounter");

        method(gridHelper, "getNodeHost", host, level, pos);
        method(host, "getGridNode", node, direction);
        m(node, "isActive").ifPresent(x -> ret(x, boolean.class));
        method(node, "getGrid", grid);
        method(grid, "getStorageService", storageService);
        method(storageService, "getInventory", meStorage);
        m(meStorage, "getAvailableStacks").ifPresent(ProbeAeApi::noArg);
        method(aeItemKey, "of", aeItemKey, itemStack);
        method(keyCounter, "get", long.class, aeKey);

        System.out.println();
        if (failed == 0) {
            System.out.println("AE_API_OK");
        } else {
            System.out.println(failed + " 条签名对不上");
            System.exit(1);
        }
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, ProbeAeApi.class.getClassLoader());
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 类不存在: " + name + "  (" + t + ")");
            return null;
        }
    }

    private static java.util.Optional<Method> m(Class<?> owner, String name) {
        if (owner == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(owner.getMethod(name));
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 方法不存在: " + owner.getSimpleName() + "#" + name);
            return java.util.Optional.empty();
        }
    }

    /** 带参数的方法：同时核对返回类型与静态性 */
    private static void method(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
        if (owner == null) {
            return;
        }
        try {
            Method mm = owner.getMethod(name, params);
            if (mm.getReturnType() != returnType) {
                failed++;
                System.out.println("FAIL 返回类型不符: " + owner.getSimpleName() + "#" + name
                        + " 期望 " + returnType.getSimpleName() + " 实际 " + mm.getReturnType().getSimpleName());
                return;
            }
            System.out.println("ok   " + owner.getSimpleName() + "#" + name
                    + " -> " + returnType.getSimpleName()
                    + (Modifier.isStatic(mm.getModifiers()) ? " (static)" : ""));
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 签名不存在: " + owner.getSimpleName() + "#" + name + "(" + params.length + " 参)");
        }
    }

    private static void noArg(Method mm) {
        System.out.println("ok   " + mm.getDeclaringClass().getSimpleName() + "#" + mm.getName()
                + " -> " + mm.getReturnType().getSimpleName());
    }

    private static void ret(Method mm, Class<?> expect) {
        if (mm.getReturnType() != expect) {
            failed++;
            System.out.println("FAIL 返回类型不符: " + mm.getName() + " 期望 " + expect.getSimpleName()
                    + " 实际 " + mm.getReturnType().getSimpleName());
            return;
        }
        noArg(mm);
    }
}
