/*
 * LogiScope · 标签层断言（DataProbe#asValForced）—— **不在主门禁里**。
 *
 * 为什么单独一个文件：这几条断言要真的构造 MC 的 NBT 标签（IntNBT / StringNBT /
 * ListNBT），而主门禁 dev-check/offline-check.bat 刻意只依赖**纯逻辑层**
 * （analysis / store / archive data 零 MC import），这样它不会被映射版本拖累。
 * 1.12.2 线把这些标签类改名叫 NBTTagInt / NBTTagString / NBTTagList，等 DataProbe
 * 搬过来时连断言一起改，再把本文件并入门禁。
 *
 * 从 1.16.5 线搬过来时保持断言原文（期望值一个字都别改）：
 *   StringNBT("64") → 64（字符串尝试解析）
 *   StringNBT("abc") → null
 *   IntNBT(7) → 7（本来就是数值）
 *   ListNBT(empty) → null（类型不可转换）
 */
import com.jessetech.logiscope.collect.DataProbe;
import net.minecraft.nbt.IntNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;

public class TestNbt {

    private static int failed;

    public static void main(String[] args) {
        eq("StringNBT(\"64\") 强制转换", 64L,
                DataProbe.asValForced(StringNBT.valueOf("64")).longValue());
        eq("StringNBT(\"abc\") → null", null, DataProbe.asValForced(StringNBT.valueOf("abc")));
        eq("IntNBT 照常（本来就是数值）", 7L, DataProbe.asValForced(IntNBT.valueOf(7)).longValue());
        eq("ListNBT → null（类型不可转换）", null, DataProbe.asValForced(new ListNBT()));

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
}
