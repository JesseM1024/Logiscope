# -*- coding: utf-8 -*-
import io

p = "dev-check/TestChart.java"
s = io.open(p, encoding="utf-8").read()

assert "import com.jessetech.logiscope.analysis.CurveMath;" in s
s = s.replace("import com.jessetech.logiscope.analysis.CurveMath;",
              "import com.jessetech.logiscope.analysis.CurveMath;\nimport com.jessetech.logiscope.analysis.Snbt;", 1)

assert "        t25CurveMath();" in s
s = s.replace("        t25CurveMath();",
              "        t25CurveMath();\n        t26Snbt();", 1)

t26 = '''
    // 26) SNBT 解析（第十一轮③）：值列表 / 路径 / 采样路径语法
    private static void t26Snbt() {
        // 典型 `data get block` 回复（前缀 + 复合标签）
        String raw = "barrel has the following block data: {Items:[{Count:1b,Slot:0b,"
                + "id:\\"minecraft:iron_ingot\\"}],id:\\"minecraft:barrel\\",energy:1234,"
                + "tank:{FluidName:\\"minecraft:water\\",amount:5000},x:1,y:2,z:3}";

        Snbt.Node root = Snbt.parse(raw);
        yes("解析出复合节点", root != null && root.kind() == Snbt.Kind.COMPOUND);

        List<Snbt.Entry> flat = Snbt.flatten(root);
        List<String> paths = new ArrayList<>();
        for (Snbt.Entry e : flat) {
            paths.add(e.path());
        }
        yes("列出 Items[0].Count", paths.contains("Items[0].Count"));
        yes("列出 Items[0].id", paths.contains("Items[0].id"));
        yes("列出 tank.amount", paths.contains("tank.amount"));
        yes("列出 tank.FluidName", paths.contains("tank.FluidName"));
        yes("列出 energy", paths.contains("energy"));

        Snbt.Entry energy = null;
        Snbt.Entry fluid = null;
        Snbt.Entry amount = null;
        for (Snbt.Entry e : flat) {
            if (e.path().equals("energy")) energy = e;
            if (e.path().equals("tank.FluidName")) fluid = e;
            if (e.path().equals("tank.amount")) amount = e;
        }
        yes("energy 是数值", energy != null && energy.numeric());
        eq("energy = 1234", 1234.0, energy == null ? -1 : energy.node().number(), 1e-9);
        yes("tank.amount 是数值（未知模组键名也能取到）", amount != null && amount.numeric());
        eq("amount = 5000", 5000.0, amount == null ? -1 : amount.node().number(), 1e-9);
        yes("FluidName 是文本", fluid != null && fluid.textual());
        eq("FluidName 内容", "minecraft:water", fluid == null ? "" : fluid.node().text());
        eq("1b 后缀被剥掉", "1", paths.contains("Items[0].Count")
                ? String.valueOf((long) Snbt.flatten(root).get(0).node().number()) : "?");

        // 采样路径语法
        List<Snbt.Token> t = Snbt.parsePath("tank.amount");
        eq("两段路径", 2, t.size());
        eq("第一段是键 tank", "tank", t.get(0).key());
        List<Snbt.Token> t2 = Snbt.parsePath("tanks[0].amount");
        eq("三段路径", 3, t2.size());
        yes("第二段是下标 0", t2.get(1).isIndex() && t2.get(1).index() == 0);
        eq("下标段没有键名", null, t2.get(1).key());
        eq("坏路径返回空表", 0, Snbt.parsePath("a[").size());
        eq("空路径返回空表", 0, Snbt.parsePath("   ").size());
        eq("单键路径", 1, Snbt.parsePath("energy").size());

        // 非复合 / 残缺文本：不抛异常，能解析多少算多少
        eq("没有复合标签返回 null", null, Snbt.parse("no braces here"));
        Snbt.Node truncated = Snbt.parse("{a:1,b:\\"half");
        yes("残缺文本仍能解出前半段", truncated != null
                && truncated.children().containsKey("a"));
        Snbt.Node typed = Snbt.parse("{l:[I;1,2,3]}");
        yes("带类型前缀的列表可解析", typed != null
                && typed.children().get("l").items().size() == 3);
    }

'''

marker = "    // ---- 断言 ----"
assert marker in s
s = s.replace(marker, t26 + marker, 1)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("t26 ok")
