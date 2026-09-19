# -*- coding: utf-8 -*-
import io

p = "src/main/java/com/jessetech/logiscope/ui/ScopeScreen.java"
s = io.open(p, encoding="utf-8").read()


def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:80]
    s = s.replace(old, new, 1)


# ---- 1) 常量：高亮改黄色 ----
rep("""    /** 「分析」选中曲线 / 波形标记的品红系（同一语汇：都是"用户钉在这里的东西"） */
    private static final int COLOR_ANALYZE = 0xFFE070D8;""",
    """    /** 「分析」选中曲线 / 波形标记的品红系（同一语汇：都是"用户钉在这里的东西"） */
    private static final int COLOR_ANALYZE = 0xFFE070D8;
    /** NBT 原文悬浮高亮：黄（用户反馈淡紫在深底上难认；黄与深底对比最强） */
    private static final int COLOR_HILIGHT = 0x80FFD24A;""")

# ---- 2) 字段：目标列表滚动 ----
rep("""    private int dataScroll;
    private int dataTextScroll;""",
    """    private int dataScroll;
    private int dataTextScroll;
    /** 追踪目标列表的滚动位置（目标可以攒很多，固定两三行放不下） */
    private int dataTgtScroll;""")

# ---- 3) dataRects：目标行数随窗口大小增减（默认面板 3 行）----
rep("""        // 目标区：1 行表头 + 2 行目标（固定），剩下的给值列表（再扣 1 行 footer）
        int tgtRows = 2;
        int below = Math.max(ROW_H * 4, editTop - listTop - 2);
        int listRows = Math.max(1, (below - (1 + tgtRows) * ROW_H) / ROW_H - 1);""",
    """        // 目标区：1 行表头 + N 行目标。N 随窗口高度增减（默认面板正好 3 行），
        // 超出的行靠滚动（目标可以攒很多，固定两行不够用）
        int below = Math.max(ROW_H * 4, editTop - listTop - 2);
        int tgtRows = Math.max(1, Math.min(8, below / 3 / ROW_H));
        int listRows = Math.max(1, (below - (1 + tgtRows) * ROW_H) / ROW_H - 1);""")

# ---- 4) 渲染：目标列表带滚动 + 行号 ----
old_tgt = """        // ---- 3) 追踪目标列表（确认添加后出现在这里；只能删，不能改）----
        ProbeInstance src = dataSrcProbe();
        List<DataTarget> dts = src == null ? List.of() : src.dataTargets();
        g.drawString(this.font, tr("logiscope.data.targets") + " · " + dts.size()
                + "  ·  " + tr("logiscope.data.targets_hint"), left, R.tgtTop(), COLOR_ANALYZE, false);
        for (int i = 0; i < R.tgtRows(); i++) {
            if (i >= dts.size()) {
                break;
            }
            DataTarget dt = dts.get(i);
            int ry = R.tgtTop() + (i + 1) * ROW_H;"""
new_tgt = """        // ---- 3) 追踪目标列表（确认添加后出现在这里；只能删，不能改；可滚动）----
        ProbeInstance src = dataSrcProbe();
        List<DataTarget> dts = src == null ? List.of() : src.dataTargets();
        dataTgtScroll = Math.max(0, Math.min(dataTgtScroll,
                Math.max(0, dts.size() - R.tgtRows())));
        g.drawString(this.font, tr("logiscope.data.targets") + " · " + dts.size()
                + "  ·  " + tr("logiscope.data.targets_hint"), left, R.tgtTop(), COLOR_ANALYZE, false);
        for (int i = 0; i < R.tgtRows(); i++) {
            int idx = dataTgtScroll + i;
            if (idx >= dts.size()) {
                break;
            }
            DataTarget dt = dts.get(idx);
            int ry = R.tgtTop() + (i + 1) * ROW_H;"""
rep(old_tgt, new_tgt)

old_more = """        if (dts.size() > R.tgtRows()) {
            String more = "+" + (dts.size() - R.tgtRows());
            g.drawString(this.font, more, left, R.tgtTop() + (R.tgtRows() + 1) * ROW_H,
                    0xFF9099A8, false);
        }"""
new_more = """        if (dts.size() > R.tgtRows()) {
            String more = (dataTgtScroll + R.tgtRows()) + " / " + dts.size()
                    + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, cut(more, L.w() - 2 * PAD),
                    left, R.tgtTop() + (R.tgtRows() + 1) * ROW_H, 0xFF9099A8, false);
        }"""
rep(old_more, new_more)

# ---- 5) 高亮颜色 ----
rep("                    g.fill(x1, yTop, Math.max(x2, x1 + 2), yTop + 9, 0x50E070D8);",
    "                    g.fill(x1, yTop, Math.max(x2, x1 + 2), yTop + 9, COLOR_HILIGHT);")

# ---- 6) 滚轮：三区 ----
rep("""            // DATA 页：滚命令原文 / 滚值列表（看光标落在哪一块）
            if (page == Page.DATA) {
                DataRects R = dataRects(L);
                int my = (int) mouseY;
                if (my >= R.textTop() && my < R.textTop() + R.textH()) {
                    dataTextScroll = Math.max(0, dataTextScroll - dir);
                } else {
                    dataScroll = Math.max(0, dataScroll - dir);
                }
                return true;
            }""",
    """            // DATA 页：滚原文 / 滚目标列表 / 滚值列表（看光标落在哪一块）
            if (page == Page.DATA) {
                DataRects R = dataRects(L);
                int my = (int) mouseY;
                if (my >= R.textTop() && my < R.textTop() + R.textH()) {
                    dataTextScroll = Math.max(0, dataTextScroll - dir);
                } else if (my >= R.tgtTop()) {
                    dataTgtScroll = Math.max(0, dataTgtScroll - dir);
                } else {
                    dataScroll = Math.max(0, dataScroll - dir);
                }
                return true;
            }""")

# ---- 7) 目标点击：按滚动偏移取行 ----
old_del = """                int row = (my - R.tgtTop() - ROW_H) / ROW_H;   // 第一行是表头
                ProbeInstance src2 = dataSrcProbe();
                List<DataTarget> dts = src2 == null ? List.of() : src2.dataTargets();
                if (row >= 0 && row < R.tgtRows() && row < dts.size()
                        && isIn(mx, my, L.x() + L.w() - 4 - DEL_W,
                                R.tgtTop() + (row + 1) * ROW_H - 1, DEL_W, ROW_H)) {
                    src2.removeDataTarget(dts.get(row).valuePath());
                }"""
new_del = """                int row = (my - R.tgtTop() - ROW_H) / ROW_H;   // 第一行是表头
                ProbeInstance src2 = dataSrcProbe();
                List<DataTarget> dts = src2 == null ? List.of() : src2.dataTargets();
                int idx = dataTgtScroll + row;
                if (row >= 0 && row < R.tgtRows() && idx < dts.size()
                        && isIn(mx, my, L.x() + L.w() - 4 - DEL_W,
                                R.tgtTop() + (row + 1) * ROW_H - 1, DEL_W, ROW_H)) {
                    src2.removeDataTarget(dts.get(idx).valuePath());
                }"""
rep(old_del, new_del)

# ---- 8) 进页归零 ----
rep("""        dataTextScroll = 0;
        dataScroll = 0;
        dataNextRequestTick = 0;
        page = Page.DATA;""",
    """        dataTextScroll = 0;
        dataScroll = 0;
        dataTgtScroll = 0;
        dataNextRequestTick = 0;
        page = Page.DATA;""")

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ScopeScreen ok")
