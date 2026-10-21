/*
 * LogiScope · AE 目标断言（t38）—— **不在 1.12.2 的主门禁里**。
 *
 * 1.12.2 线本轮明确"AE 兼容性先不管"，DataTarget 也还没搬过来，所以把这批断言
 * 单独留在这里：等 DataTarget 落地（连同它的维度表示从 RegistryKey 改成 1.12.2 的
 * dimension int）之后，把本文件编译进门禁即可。断言原文与期望值**一个字都没改**。
 */
import com.jessetech.logiscope.analysis.AeKeyText;
import com.jessetech.logiscope.bind.DataTarget;

import java.util.ArrayList;
import java.util.List;

public class TestAe {

    private static int failed;

    public static void main(String[] args) {

        DataTarget ae = DataTarget.aeNetwork("minecraft:stone", "terminal", null, null);
        eq("AE 判定", true, ae.isAeNetwork());
        eq("AE 值路径是标记", "ae:network", ae.valuePath());
        eq("AE 不是槽位锚点", false, ae.isSlotAnchor());
        eq("AE 显示", "ae:network[minecraft:stone]", ae.displayValue());
        eq("AE fallbackName = 物品 id", "minecraft:stone", ae.fallbackName());

        // 去重：身份 = 物品 registry id（valuePath 全是 ae:network，绝不能拿它去重）
        List<DataTarget> list = new ArrayList<>();
        list.add(DataTarget.aeNetwork("minecraft:stone", "t", null, null));
        eq("AE 同物品重复", true, DataTarget.isDuplicate(list,
                DataTarget.aeNetwork("minecraft:stone", "t", null, null)));
        eq("AE 异物品不重复", false, DataTarget.isDuplicate(list,
                DataTarget.aeNetwork("minecraft:iron_ingot", "t", null, null)));

        // 与另外两类目标互不干扰
        eq("AE 与锚点不互判", false, DataTarget.isDuplicate(list,
                DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 0, null)));
        // 用户手输 "ae:network" 当普通路径（罕见）：不与 AE 目标互判重复，
        // 但它会被读值分流当成 AE 目标 —— 已写进文档的保留路径
        DataTarget manual = new DataTarget("ae:network", null, "x", "t", null, null);
        eq("手输 ae:network 路径与 AE 目标不互判", false, DataTarget.isDuplicate(list, manual));

        // 普通路径仍按 valuePath 去重（回归）
        List<DataTarget> plain = new ArrayList<>();
        plain.add(new DataTarget("energy", null, "e", "t", null, null));
        eq("普通路径按 valuePath 去重", true, DataTarget.isDuplicate(plain,
                new DataTarget("energy", null, "e", "t", null, null)));
        eq("普通路径不同值不重复", false, DataTarget.isDuplicate(plain,
                new DataTarget("energy2", null, "e", "t", null, null)));

        // 与锚点去重（回归：同 slot 重复）
        List<DataTarget> anchors = new ArrayList<>();
        anchors.add(DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 0, null));
        eq("锚点同 slot 重复", true, DataTarget.isDuplicate(anchors,
                DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 0, null)));
        eq("锚点异 slot 不重复", false, DataTarget.isDuplicate(anchors,
                DataTarget.slotAnchor("items", "minecraft:stone", "t", null, null, 1, null)));

        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过");
        } else {
            System.out.println(failed + " 项失败");
            System.exit(1);
        }
    }

    private static void eq(String what, Object expect, Object actual) {
        if (expect == null ? actual == null : expect.equals(actual)) {
            System.out.println("  ok   " + what + " = " + actual);
        } else {
            failed++;
            System.out.println("  FAIL " + what + " 期望 " + expect + " 实际 " + actual);
        }
    }

    private static void yes(String what, boolean b) {
        eq(what, true, b);
    }

    private static void no(String what, boolean b) {
        eq(what, false, b);
    }
}
