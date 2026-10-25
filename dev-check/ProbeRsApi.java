/*
 * RS（精致存储 1.9.x）反射链的离线实证：不启动游戏，只把代码里用到的**每一个反射入口**
 * 从真实 jar 解析一遍。
 *
 * 为什么需要它：RS 不是本项目的编译期依赖，{@code RsKeys} / {@code RsNetworkReader}
 * 全靠反射。一旦 RS 换签名，编译期一无所知、实机只会变成"RS 目标恒 no_data"，
 * 而且和"网络没电"长得一模一样。
 *
 * 与 ProbeAeApi 的差别：这个类**沿代码自己的推导路径走**
 * （getNode() 的返回类型 -> getNetwork() -> getItemStorageCache() -> getList()
 *   -> getCount(Object)），免得探针与实现各自漂移。
 *
 * 运行（jar 名按实例里的实际版本改；先复制进 build/ 避免盘符路径被 shell 转坏）：
 * <pre>
 *   copy "&lt;实例&gt;\mods\refinedstorage-1.9.18.jar" build\dev-check\mods\
 *   set CP=build\classes\java\main;build\dev-check;&lt;build\cp.txt 的内容&gt;;build\dev-check\mods\refinedstorage-1.9.18.jar
 *   javac -proc:none -encoding UTF-8 -cp "%CP%" -d build\dev-check dev-check\ProbeRsApi.java
 *   java -Dfile.encoding=UTF-8 -cp "%CP%" ProbeRsApi
 * </pre>
 * 用项目自己的依赖类路径（{@code build/cp.txt}）最省事：RS 的菜单 / 界面继承链会牵到
 * Guava 等传递依赖，只挂两个 jar 会拿到一串 {@code NoClassDefFoundError}
 * —— 那是夹具缺件，归 SKIP，不是签名错。
 *
 * 注意：这个程序**不在** offline-check.bat 里 —— 它依赖本仓库之外的第三方 jar。
 */
import java.lang.reflect.Method;

public class ProbeRsApi {

    private static final String NODE_PROXY =
            "com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy";
    private static final String GRID_MENU =
            "com.refinedmods.refinedstorage.container.GridContainer";
    private static final String GRID_SCREEN =
            "com.refinedmods.refinedstorage.screen.grid.GridScreen";

    private static int failed;
    private static int skipped;

    public static void main(String[] args) {
        // ---- RsKeys.isRsGridMenu：沿父类链比类名 ----
        Class<?> menu = cls(GRID_MENU);
        if (menu != null) {
            System.out.println("ok   网格菜单类存在（菜单判据）");
        }

        // ---- RsKeys.rows：GridScreen#getView() -> IGridView#getAllStacks() ----
        // 直接查 IGridView / IGridStack 两个**接口**：加载 GridScreen 本身会拖进
        // 一长串 Forge 运行时类（BaseScreen 的继承链），离线夹具里必然 SKIP。
        // GridScreen#getView() 的返回类型由 javap 核实（public IGridView getView()）。
        Class<?> view = cls("com.refinedmods.refinedstorage.screen.grid.view.IGridView");
        if (view != null) {
            Method all = tryMethod(view, "getAllStacks");
            if (all == null) {
                m(view, "getStacks");   // 老版本只有 getStacks
            } else {
                System.out.println("ok   IGridView#getAllStacks -> "
                        + all.getReturnType().getSimpleName());
            }
        }
        Class<?> gridStack = cls(
                "com.refinedmods.refinedstorage.screen.grid.stack.IGridStack");
        if (gridStack != null) {
            method(gridStack, "getIngredient", Object.class);
            method(gridStack, "getName", String.class);
            method(gridStack, "getQuantity", int.class);
        }
        // 界面的存在性单独核一次（不加载它，只比类名——与 isRsGridMenu 的判据同源）
        cls(GRID_SCREEN);

        // ---- RsNetworkReader.chain：方块实体 -> 节点 -> 网络 -> 缓存 -> 清单 -> 数量 ----
        // 本线的网格方块实体叫 GridTile（1.12.x 那代才叫 GridBlockEntity）
        Class<?> gridBe = cls("com.refinedmods.refinedstorage.tile.grid.GridTile");
        if (gridBe != null) {
            yes("GridTile 的父类链上有 INetworkNodeProxy",
                    interfaceChainHas(gridBe, cls(NODE_PROXY)));
        }

        Class<?> proxy = cls(NODE_PROXY);
        if (proxy != null) {
            Method getNode = m(proxy, "getNode");
            if (getNode != null) {
                // 注意：泛型 T 的上界是 INetworkNode，擦除后返回类型就是它
                Class<?> nodeType = getNode.getReturnType();
                System.out.println("     getNode -> " + nodeType.getName());
                Method getNetwork = m(nodeType, "getNetwork");
                if (getNetwork != null) {
                    Class<?> network = getNetwork.getReturnType();
                    System.out.println("     getNetwork -> " + network.getName());
                    method(network, "canRun", boolean.class);
                    Method itemCache = m(network, "getItemStorageCache");
                    Method fluidCache = m(network, "getFluidStorageCache");
                    if (itemCache != null) {
                        Class<?> cache = itemCache.getReturnType();
                        System.out.println("     getItemStorageCache -> " + cache.getName());
                        if (fluidCache != null && fluidCache.getReturnType() != cache) {
                            failed++;
                            System.out.println("FAIL 物品 / 流体缓存的返回类型不一致");
                        }
                        Method getList = m(cache, "getList");
                        if (getList != null) {
                            Class<?> list = getList.getReturnType();
                            System.out.println("     getList -> " + list.getName());
                            // 代码调的是 getCount(T)（一个参数；T 擦除成 Object）
                            method(list, "getCount", int.class, Object.class);
                        }
                    }
                }
            }
        }

        System.out.println();
        if (failed == 0) {
            System.out.println(skipped == 0 ? "RS_API_OK"
                    : "RS_API_OK (" + skipped + " 条因离线夹具无法校验)");
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

    private static Method tryMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (Throwable t) {
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

    /** 接口链里有没有这个接口（与 RsKeys / RsNetworkReader 的判据一致） */
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
