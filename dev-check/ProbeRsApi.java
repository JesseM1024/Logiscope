/*
 * RS（精致存储 2）反射链的离线实证：不启动游戏，只把代码里用到的**每一个反射入口**
 * 从真实 jar 解析一遍。
 *
 * 为什么需要它：RS 不是本项目的编译期依赖，{@code RsKeys} / {@code RsNetworkReader}
 * 全靠反射。一旦 RS 换签名（2.0 -> 2.x 把 getItemStorage 挪走之类），编译期一无所知、
 * 实机只会变成"RS 目标恒 no_data"，而且和"网络没电"长得一模一样。
 *
 * 与 ProbeAeApi 的差别：那个类照抄签名表；这个类**尽量沿代码自己的推导路径走**
 * （getRepository() 的返回类型 -> getViewList；从运行时的资源键类往上找 ResourceKey），
 * 免得探针与实现各自漂移。
 *
 * 运行（jar 名按实例里的实际版本改；RS jar 先复制进 build/ 避免盘符路径被 shell 转坏）：
 * <pre>
 *   copy "&lt;实例&gt;\mods\refinedstorage-neoforge-2.0.9.jar" build\dev-check\mods\
 *   set CP=build\classes\java\main;build\dev-check;&lt;build\cp.txt 的内容&gt;;build\dev-check\mods\refinedstorage-neoforge-2.0.9.jar
 *   javac -proc:none -encoding UTF-8 -cp "%CP%" -d build\dev-check dev-check\ProbeRsApi.java
 *   java -Dfile.encoding=UTF-8 -cp "%CP%" ProbeRsApi
 * </pre>
 * 用 project 自己的依赖类路径（{@code build/cp.txt}）最省事：RS 的菜单类继承链会牵到
 * Guava / fastutil（RS 自己不打包它们，游戏里由 NeoForge 提供），只挂两个 jar 会拿到
 * 一堆 {@code NoClassDefFoundError} —— 那是夹具缺件，被归到 SKIP，不是签名错。
 *
 * 注意：这个程序**不在** offline-check.bat 里 —— 它依赖本仓库之外的第三方 jar。
 *
 * ★ 只检查 RS 自己的方法。原版（MC）的方法一律直接调，不在这里检查
 *   （1.20.1 生产是 SRG 命名，反射 MC 方法会让整条链失败）。
 */
import java.lang.reflect.Method;

public class ProbeRsApi {

    private static final String GRID = "com.refinedmods.refinedstorage.common.api.grid.Grid";
    private static final String ITEM_RESOURCE = "com.refinedmods.refinedstorage.common.support.resource.ItemResource";
    private static final String FLUID_RESOURCE = "com.refinedmods.refinedstorage.common.support.resource.FluidResource";
    private static final String RESOURCE_KEY = "com.refinedmods.refinedstorage.api.resource.ResourceKey";
    private static final String GRID_MENU = "com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu";

    private static int failed;
    private static int skipped;

    public static void main(String[] args) {
        // ---- RsKeys.isRsGridMenu：沿父类链比类名，所以这两个类都要在 ----
        Class<?> menuRoot = cls(GRID_MENU);
        Class<?> menuImpl = cls("com.refinedmods.refinedstorage.common.grid.GridContainerMenu");
        if (menuRoot != null && menuImpl != null) {
            yes("GridContainerMenu 的父类链上有 AbstractGridContainerMenu",
                    superChainHas(menuImpl, GRID_MENU));
        }

        // ---- RsKeys.menuChain：getRepository() -> getViewList / getAmount(ResourceKey) ----
        if (menuRoot != null) {
            Method getRepository = m(menuRoot, "getRepository");
            if (getRepository != null) {
                Class<?> repo = getRepository.getReturnType();
                System.out.println("     getRepository -> " + repo.getName());
                m(repo, "getViewList");
                Class<?> resourceKey = cls(RESOURCE_KEY);
                if (resourceKey != null) {
                    method(repo, "getAmount", long.class, resourceKey);
                }
            }
        }

        // ---- RsKeys.rows：GridResource#getResourceForRecipeMods / getName ----
        Class<?> gridResource = cls("com.refinedmods.refinedstorage.common.api.grid.view.GridResource");
        Class<?> platformKey = cls(
                "com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey");
        if (gridResource != null) {
            method(gridResource, "getResourceForRecipeMods", platformKey);
            method(gridResource, "getName", String.class);
        }
        // 两种资源键都必须真的实现 GridResource 需要的键接口
        Class<?> itemResource = cls(ITEM_RESOURCE);
        Class<?> fluidResource = cls(FLUID_RESOURCE);
        if (itemResource != null) {
            yes("ItemResource 实现了 PlatformResourceKey", interfaceChainHas(itemResource, platformKey));
            method(itemResource, "item", cls("net.minecraft.world.item.Item"));
            method(itemResource, "components", cls("net.minecraft.core.component.DataComponentPatch"));
        }
        if (fluidResource != null) {
            yes("FluidResource 实现了 PlatformResourceKey", interfaceChainHas(fluidResource, platformKey));
            method(fluidResource, "fluid", cls("net.minecraft.world.level.material.Fluid"));
            method(fluidResource, "components", cls("net.minecraft.core.component.DataComponentPatch"));
        }

        // ---- RsKeys.amountMethod：从**运行时的资源键类**往上找 ResourceKey ----
        // 这一步是代码真实走的路径（不写死 ItemResource 的完整签名）
        Class<?> resourceKey = cls(RESOURCE_KEY);
        for (Class<?> keyClass : new Class<?>[]{itemResource, fluidResource}) {
            if (keyClass == null || resourceKey == null) {
                continue;
            }
            yes(keyClass.getSimpleName() + " 的接口链里有 ResourceKey",
                    interfaceChainHas(keyClass, resourceKey));
        }

        // ---- RsKeys.rows 真正返回的元素类型必须实现 GridResource ----
        for (String impl : new String[]{
                "com.refinedmods.refinedstorage.common.grid.view.ItemGridResource",
                "com.refinedmods.refinedstorage.common.grid.view.FluidGridResource"}) {
            Class<?> c = cls(impl);
            if (c != null && gridResource != null) {
                yes(c.getSimpleName() + " 实现了 GridResource", interfaceChainHas(c, gridResource));
            }
        }

        // ---- RsNetworkReader.chain：BlockEntity -> Grid -> isGridActive / getItemStorage ----
        Class<?> grid = cls(GRID);
        Class<?> gridBe = cls("com.refinedmods.refinedstorage.common.grid.AbstractGridBlockEntity");
        if (grid != null) {
            yes("AbstractGridBlockEntity 实现了 Grid", interfaceChainHas(gridBe, grid));
            m(grid, "isGridActive");
            Method getItemStorage = m(grid, "getItemStorage");
            if (getItemStorage != null) {
                Class<?> storage = getItemStorage.getReturnType();
                System.out.println("     getItemStorage -> " + storage.getName());
                // RsNetworkReader 用 Storage#getAll()（声明在 StorageView 上，沿继承找得到）
                m(storage, "getAll");
            }
        }
        // 网格方块实体确实是 Grid 的实例（RsNetworkReader 用它当"这台是不是 RS 设备"的判据）
        Class<?> gridBlockEntity = cls("com.refinedmods.refinedstorage.common.grid.GridBlockEntity");
        if (gridBlockEntity != null) {
            yes("GridBlockEntity 的父类链上实现了 Grid", interfaceChainHas(gridBlockEntity, grid));
        }

        // ---- RsNetworkReader.amountsFor：ResourceAmount#resource / amount ----
        Class<?> amount = cls("com.refinedmods.refinedstorage.api.resource.ResourceAmount");
        if (amount != null) {
            method(amount, "resource", resourceKey);
            method(amount, "amount", long.class);
        }

        System.out.println();
        if (failed == 0) {
            System.out.println(skipped == 0 ? "RS_API_OK" : "RS_API_OK (" + skipped + " 条因离线夹具无法校验)");
        } else {
            System.out.println(failed + " 条签名对不上");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static Class<?> cls(String name) {
        try {
            // initialize=false：不跑静态初始化（不需要注册表 / 世界）
            return Class.forName(name, false, ProbeRsApi.class.getClassLoader());
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 类不存在: " + name + "  (" + t + ")");
            return null;
        }
    }

    private static Method m(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Method mm = owner.getMethod(name);
            System.out.println("ok   " + owner.getSimpleName() + "#" + name
                    + " -> " + mm.getReturnType().getSimpleName());
            return mm;
        } catch (NoClassDefFoundError | VerifyError | IncompatibleClassChangeError e) {
            // 夹具限制（离线类路径缺传递依赖 / 未打补丁的 MC），不是签名错。签名错抛 NoSuchMethodException。
            skipped++;
            System.out.println("SKIP " + owner.getSimpleName() + "#" + name
                    + "  (离线夹具无法校验: " + e.getMessage() + ")");
            return null;
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 方法不存在: " + owner.getSimpleName() + "#" + name + "  " + t);
            return null;
        }
    }

    private static void method(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
        if (owner == null || returnType == null) {
            return;
        }
        try {
            Method mm = owner.getMethod(name, params);
            if (mm.getReturnType() != returnType) {
                failed++;
                System.out.println("FAIL 返回类型不符: " + owner.getSimpleName() + "#" + name
                        + " 期望 " + returnType.getSimpleName()
                        + " 实际 " + mm.getReturnType().getSimpleName());
                return;
            }
            System.out.println("ok   " + owner.getSimpleName() + "#" + name
                    + " -> " + returnType.getSimpleName());
        } catch (NoClassDefFoundError | VerifyError | IncompatibleClassChangeError e) {
            skipped++;
            System.out.println("SKIP " + owner.getSimpleName() + "#" + name
                    + "  (离线夹具无法校验: " + e.getMessage() + ")");
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 签名不存在: " + owner.getSimpleName() + "#" + name + "  " + t);
        }
    }

    private static void yes(String what, boolean b) {
        if (b) {
            System.out.println("ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    /** 父类链里有没有这个类名（与 RsKeys.isRsGridMenu 的判据一致） */
    private static boolean superChainHas(Class<?> from, String name) {
        for (Class<?> c = from; c != null; c = c.getSuperclass()) {
            if (name.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 接口链里有没有这个接口（与 RsKeys.interfaceNamed 的判据一致） */
    private static boolean interfaceChainHas(Class<?> from, Class<?> target) {
        if (from == null || target == null) {
            return false;
        }
        if (target.equals(from)) {
            return true;
        }
        for (Class<?> itf : from.getInterfaces()) {
            if (interfaceChainHas(itf, target)) {
                return true;
            }
        }
        return interfaceChainHas(from.getSuperclass(), target);
    }
}
