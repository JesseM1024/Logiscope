/*
 * AE2 反射链的离线实证（1.16.5 / AE2 8.4.x）：不启动游戏，只把代码里用到的
 * 反射签名解析一遍。
 *
 * 为什么需要它：AE2 不是本项目的编译期依赖，所有调用都是 `Class.forName` + `getMethod`。
 * 一旦 AE2 改名 / 换签名，编译期一无所知、实机只会变成"AE 目标恒 no_data"，
 * 而且和"网络没电"长得一模一样。8.x 与 15.x / 19.x 的 API **整体不同**
 * （没有 AEKey / MEStorage / KeyCounter / GridHelper），照抄高版本的签名必然全错，
 * 所以这一代更需要这个探针。
 *
 * 【与前三条版本线不同的一点：这里验的是**真实实现**，不是复制一份签名表】
 *   AeNetworkReader.chain() 是私有的，用反射把它调起来 —— 解析失败它返回 false，
 *   解析成功返回 true。于是"探针通过"**等价于**"实机那条链能建立"，不会出现
 *   "探针表更新了、实现没更新"的漂移。AeKeys.init() 同理。
 *   剩下几条（RepoSlot / GridInventoryEntry / 菜单类名）是 AeKeys 在**运行时**
 *   才 getMethod 的，探针在这里替它先验一遍。
 *
 * 运行（AE2 8.4.7 jar 取自实例 mods；mcdeps 提供 MC / Forge 的映射类）：
 *   javac -encoding UTF-8 -cp "build/classes/java/main;build/mcdeps/*;<AE2 jar>" \
 *         -d build/dev-check dev-check/ProbeAeApi.java
 *   java -cp "build/dev-check;build/classes/java/main;build/mcdeps/*;<AE2 jar>" ProbeAeApi
 * 期望输出最后一行：AE_API_OK
 *
 * 注意：这个程序**不在** offline-check.bat 里（依赖实例里的第三方 jar）。
 * 另：失败路径会走到 `LogiScopeMod.LOGGER`，log4j 会在**当前目录**建一个 `logs/`
 * —— 那是探针的副作用，删掉即可（别提交）。
 */
import com.jessetech.logiscope.bind.AeKeys;
import com.jessetech.logiscope.collect.AeNetworkReader;

import java.lang.reflect.Method;

public class ProbeAeApi {

    private static int failed;

    public static void main(String[] args) {
        // 0) AE2 到底在不在类路径上 —— 不在的话后面全是假阴性，先分清楚
        if (!present("appeng.api.networking.IGridHost")) {
            System.out.println("SKIP: AE2 不在 classpath 上（把实例里的 appliedenergistics2-*.jar 加进来）");
            System.exit(2);
        }

        // 1) 服务端读值链：调用**真实**的私有 chain()
        check("AeNetworkReader.chain()（IGridHost→AEPartLocation→IStorageGrid→IMEInventory）",
                invokeBoolean(AeNetworkReader.class, "chain"));

        // 2) 键层：AeKeys 的初始化（IAEFluidStack#getFluidStack / IAEItemStack#getDefinition）
        check("AeKeys.init()（IAEFluidStack / IAEItemStack）",
                invokeBoolean(AeKeys.class, "init"));

        // 3) 客户端显示格：AeKeys 在运行时才解析这几个方法，这里先验
        Class<?> repoSlot = cls("appeng.client.gui.me.items.RepoSlot");
        has(repoSlot, "getStoredAmount", "终端格真实存量");
        has(repoSlot, "getEntry", "条目在不在");
        Class<?> entry = cls("appeng.container.me.common.GridInventoryEntry");
        has(entry, "getStack", "条目背后的 AE 栈");

        // 4) 菜单判据：isAeStorageMenu 沿父类链比这个名字，它必须真的是终端的基类
        Class<?> base = cls("appeng.container.me.common.MEMonitorableContainer");
        check("物品终端继承 MEMonitorableContainer",
                extendsChain(cls("appeng.container.me.items.ItemTerminalContainer"), base));
        check("流体终端继承 MEMonitorableContainer",
                extendsChain(cls("appeng.container.me.fluids.FluidTerminalContainer"), base));

        System.out.println();
        if (failed == 0) {
            System.out.println("AE_API_OK");
        } else {
            System.out.println(failed + " 项失败");
            System.exit(1);
        }
    }

    private static boolean invokeBoolean(Class<?> owner, String method) {
        try {
            Method m = owner.getDeclaredMethod(method);
            m.setAccessible(true);
            Object v = m.invoke(null);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            System.out.println("      调用失败: " + t);
            return false;
        }
    }

    private static void has(Class<?> owner, String method, String why) {
        try {
            owner.getMethod(method);
            System.out.println("  ok   " + owner.getSimpleName() + "#" + method + "  (" + why + ")");
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL " + owner.getSimpleName() + "#" + method + "  (" + why + "): " + t);
        }
    }

    private static boolean extendsChain(Class<?> type, Class<?> base) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(base.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean present(String name) {
        try {
            Class.forName(name, false, ProbeAeApi.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, ProbeAeApi.class.getClassLoader());
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL 找不到类 " + name + ": " + t);
            return Object.class;
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }
}
