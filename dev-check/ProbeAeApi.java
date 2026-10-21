/*
 * AE2 反射链的离线实证：不启动游戏，只把代码里用到的**每一个反射签名**解析一遍。
 *
 * 为什么需要它：AE2 不是本项目的编译期依赖，所有调用都是 `Class.forName` + `getMethod`。
 * 一旦 AE2 改名/换签名（例如 19.2.x -> 20.x 把 getAvailableStacks 挪走），
 * 编译期一无所知、实机只会变成"AE 目标恒 no_data"，而且和"网络没电"长得一模一样。
 * 这个程序把签名逐个 getMethod 一遍 —— 有就打印 OK，没有就打印 FAIL 并退出码非 0。
 *
 * 它**不初始化**任何类（Class.forName 的 initialize=false），所以不需要注册表 / 世界，
 * 也不会触发 AE2 的静态初始化。
 *
 * 运行（jars 顺序无所谓）：
 *   java -cp "build/dev-check;dev-check/lib/*;run/mods/appliedenergistics2-19.2.17.jar;\
 *              build/moddev/artifacts/neoforge-21.1.209-merged.jar" ProbeAeApi
 *
 * 注意：AE2 版本变了要改上面的 jar 名；这个程序**不在** offline-check.bat 里，
 * 因为它依赖 run/mods 下的第三方 jar（不在本仓库）。
 */
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class ProbeAeApi {

    private static int failed;

    public static void main(String[] args) {
        // ---- AeKeys.init() 用到的 ----
        Class<?> aeKey = cls("appeng.api.stacks.AEKey");
        Class<?> provider = cls("net.minecraft.core.HolderLookup$Provider");
        Class<?> compound = cls("net.minecraft.nbt.CompoundTag");
        Class<?> itemStack = cls("net.minecraft.world.item.ItemStack");
        Class<?> generic = cls("appeng.api.stacks.GenericStack");
        Class<?> component = cls("net.minecraft.network.chat.Component");
        Class<?> tagParser = cls("net.minecraft.nbt.TagParser");

        method(aeKey, "toTagGeneric", compound, provider);
        method(aeKey, "fromTagGeneric", aeKey, provider, compound);
        m(aeKey, "getDisplayName").ifPresent(ProbeAeApi::noArg);
        m(component, "getString").ifPresent(ProbeAeApi::noArg);
        method(tagParser, "parseTag", compound, String.class);

        method(generic, "isWrapped", boolean.class, itemStack);
        method(generic, "unwrapItemStack", generic, itemStack);
        m(generic, "what").ifPresent(ProbeAeApi::noArg);

        Class<?> repo = cls("appeng.client.gui.me.common.RepoSlot");
        m(repo, "getStoredAmount").ifPresent(x -> ret(x, long.class));
        m(repo, "hasItem").ifPresent(x -> ret(x, boolean.class));
        m(repo, "getItem").ifPresent(x -> ret(x, itemStack));

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

        // AE 终端显示格确实有"存量"这个概念（本项目的数量就取它）
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
            // initialize=false：不跑静态初始化（不需要注册表 / 世界）
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
