# -*- coding: utf-8 -*-
import io

p = "src/main/java/com/jessetech/logiscope/ui/ScopeScreen.java"
s = io.open(p, encoding="utf-8").read()


def rep(old, new):
    global s
    assert old in s, "NOT FOUND: " + old[:80]
    s = s.replace(old, new, 1)


# ---- 1) 字段：折行起始偏移（悬浮高亮要按源偏移定位）----
rep("""    /** 命令原文按当前宽度折行后的结果（缓存：宽度或原文变了才重算） */
    private List<String> dataTextLines = List.of();
    private String dataTextCachedFrom = "";
    private int dataTextCachedWidth = -1;""",
    """    /** 命令原文按当前宽度折行后的结果（缓存：宽度或原文变了才重算） */
    private List<String> dataTextLines = List.of();
    /** 与 {@link #dataTextLines} 一一对应的**源文本起始偏移**（悬浮高亮按它定位） */
    private List<Integer> dataTextStarts = List.of();
    private String dataTextCachedFrom = "";
    private int dataTextCachedWidth = -1;""")

# ---- 2) DataRects：三段式（原文 / 值列表 / 目标列表）----
rep("""    private record DataRects(int textTop, int textH, int listTop, int listRows,
                             int editTop, int cellW) {
    }

    private DataRects dataRects(Layout L) {
        int contentTop = L.infoTop() + L.infoRows() * ROW_H + 2;
        int buttonTop = L.buttons().isEmpty() ? L.y() + L.h() - PAD : L.buttons().get(0).y() - 3;
        int editTop = Math.max(contentTop + 30, buttonTop - CHIP_H - 1);
        int avail = Math.max(40, editTop - contentTop - 4);
        int textH = Math.max(16, avail * 2 / 5);
        int listTop = contentTop + textH + 4;
        int listRows = Math.max(1, (editTop - listTop - 2) / ROW_H);
        int cellW = Math.max(40, (L.w() - 3 * PAD) / 2);
        return new DataRects(contentTop, textH, listTop, listRows, editTop, cellW);
    }""",
    """    /** DATA 页三段式：NBT 原文 / 值列表 / 追踪目标列表（第十一轮④②） */
    private record DataRects(int textTop, int textH, int listTop, int listRows,
                             int tgtTop, int tgtRows, int editTop, int cellW) {
    }

    private DataRects dataRects(Layout L) {
        int contentTop = L.infoTop() + L.infoRows() * ROW_H + 2;
        int buttonTop = L.buttons().isEmpty() ? L.y() + L.h() - PAD : L.buttons().get(0).y() - 3;
        int editTop = Math.max(contentTop + 40, buttonTop - CHIP_H - 1);
        int avail = Math.max(56, editTop - contentTop - 4);
        int textH = Math.max(14, avail * 3 / 10);
        int listTop = contentTop + textH + 3;
        // 目标区：1 行表头 + 2 行目标（固定），剩下的给值列表（再扣 1 行 footer）
        int tgtRows = 2;
        int below = Math.max(ROW_H * 4, editTop - listTop - 2);
        int listRows = Math.max(1, (below - (1 + tgtRows) * ROW_H) / ROW_H - 1);
        int tgtTop = listTop + (listRows + 1) * ROW_H;
        int cellW = Math.max(40, (L.w() - 3 * PAD) / 2);
        return new DataRects(contentTop, textH, listTop, listRows, tgtTop, tgtRows, editTop, cellW);
    }""")

# ---- 3) 折行：同时记录每行的源偏移 ----
rep("""        dataTextCachedFrom = text;
        dataTextCachedWidth = width;
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length() && out.size() < 400) {
            int len = Math.min(text.length() - i, 96);
            String chunk = text.substring(i, i + len);
            // 折到放得下为止（贪心：能放多少放多少）
            while (len > 1 && this.font.width(chunk) > width) {
                len--;
                chunk = text.substring(i, i + len);
            }
            out.add(chunk);
            i += len;
        }
        dataTextLines = out;
    }""",
    """        dataTextCachedFrom = text;
        dataTextCachedWidth = width;
        List<String> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        int i = 0;
        while (i < text.length() && out.size() < 400) {
            int len = Math.min(text.length() - i, 96);
            String chunk = text.substring(i, i + len);
            // 折到放得下为止（贪心：能放多少放多少）
            while (len > 1 && this.font.width(chunk) > width) {
                len--;
                chunk = text.substring(i, i + len);
            }
            out.add(chunk);
            starts.add(i);
            i += len;
        }
        dataTextLines = out;
        dataTextStarts = starts;
    }""")

# ---- 4) renderDataPage：三段式 + 悬浮高亮 ----
old_render = s[s.index("    private void renderDataPage(GuiGraphics g, Layout L, int mouseX, int mouseY) {"):
               s.index("    /** 一个可编辑的小格：上面一行小标签，下面一格内容（焦点时描边 + 光标） */")]
new_render = """    private void renderDataPage(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        DataRects R = dataRects(L);
        int left = L.x() + PAD;
        int width = L.w() - 2 * PAD;

        // ---- 0) 悬浮的值列表项（决定 NBT 原文里高亮哪一段）----
        int hoverIdx = -1;
        if (mouseY >= R.listTop() && mouseY < R.listTop() + R.listRows() * ROW_H) {
            int row = (mouseY - R.listTop()) / ROW_H;
            int idx = dataScroll + row;
            if (idx < dataEntries.size()) {
                hoverIdx = idx;
            }
        }

        // ---- 1) NBT 原文（实时；悬浮值对应的字段整段高亮）----
        g.fill(left, R.textTop(), left + width, R.textTop() + R.textH(), 0xFF13131B);
        ring(g, left, R.textTop(), width, R.textH(), 0xFF33333F);
        DataCommand.Status st = DataCommand.status();
        if (st == DataCommand.Status.OK || (DataCommand.text() != null && !DataCommand.text().isEmpty())) {
            ensureDataTextWrapped(width - 8);
            int lineH = 10;
            int maxLines = Math.max(1, (R.textH() - 4) / lineH);
            dataTextScroll = Math.max(0, Math.min(dataTextScroll,
                    Math.max(0, dataTextLines.size() - maxLines)));
            // 高亮底色先画（文字压在上面）
            if (hoverIdx >= 0 && hoverIdx < dataEntries.size()) {
                Snbt.Entry he = dataEntries.get(hoverIdx);
                int hs = he.node().start();
                int heEnd = he.node().end();
                for (int li = dataTextScroll;
                     li < Math.min(dataTextLines.size(), dataTextScroll + maxLines); li++) {
                    int ls = dataTextStarts.get(li);
                    int le = ls + dataTextLines.get(li).length();
                    if (heEnd <= ls || hs >= le) {
                        continue;
                    }
                    int from = Math.max(hs, ls) - ls;
                    int to = Math.min(heEnd, le) - ls;
                    String lineText = dataTextLines.get(li);
                    int x1 = left + 3 + this.font.width(lineText.substring(0, from));
                    int x2 = left + 3 + this.font.width(lineText.substring(0, to));
                    int yTop = R.textTop() + 3 + (li - dataTextScroll) * lineH;
                    g.fill(x1, yTop, Math.max(x2, x1 + 2), yTop + 9, 0x50E070D8);
                }
            }
            int ty = R.textTop() + 3;
            for (int i = dataTextScroll;
                 i < Math.min(dataTextLines.size(), dataTextScroll + maxLines); i++) {
                g.drawString(this.font, dataTextLines.get(i), left + 3, ty, 0xFF9BE1CB, false);
                ty += lineH;
            }
            if (dataTextLines.size() > maxLines) {
                String more = (dataTextScroll + maxLines) + " / " + dataTextLines.size();
                g.drawString(this.font, more, left + width - 4 - this.font.width(more),
                        R.textTop() + R.textH() - 10, 0xFF707070, false);
            }
        } else {
            String msg = tr(DataCommand.statusKey());
            g.drawString(this.font, msg, left + 4, R.textTop() + 3, DataCommand.statusColor(), false);
            if (st == DataCommand.Status.NO_PERMISSION) {
                g.drawString(this.font, cut(tr("logiscope.data.hint.permission"), width - 8),
                        left + 4, R.textTop() + 14, 0xFF8A8AA0, false);
            }
        }

        // ---- 2) 值列表（解析出来的"路径 = 值"；左键取值 / 左键文本或右键当名称）----
        int listTop = R.listTop();
        if (dataEntries.isEmpty()) {
            g.drawString(this.font, tr("logiscope.data.no_values"), left, listTop, 0x8A8AA0, false);
        } else {
            int maxRows = R.listRows();
            dataScroll = Math.max(0, Math.min(dataScroll,
                    Math.max(0, dataEntries.size() - maxRows)));
            for (int i = 0; i < maxRows; i++) {
                int idx = dataScroll + i;
                if (idx >= dataEntries.size()) {
                    break;
                }
                Snbt.Entry e = dataEntries.get(idx);
                int ry = listTop + i * ROW_H;
                boolean hover = isIn(mouseX, mouseY, L.x() + 4, ry - 1, L.w() - 8, ROW_H);
                if (hover) {
                    g.fill(L.x() + 4, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1, 0x40FFFFFF);
                }
                boolean picked = e.path().equals(dataPath);
                if (picked) {
                    g.fill(L.x() + 4, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1, 0x303FE0A0);
                }
                boolean named = e.textual() && e.path().equals(dataNamePath);
                // 数值叶子 = 可采样（亮）；字符串叶子 = 可作名称（青）
                int pathColor = e.numeric() ? (picked ? 0xFF3FE0A0 : 0xFFE0E0EC)
                        : (named ? 0xFFE070D8 : 0xFF9BE1CB);
                String value = e.node().raw();
                int vw = this.font.width(value);
                int avail = L.w() - 8 - vw - 8;
                g.drawString(this.font, cut(e.path() + (named ? "  [名称]" : ""), Math.max(20, avail)),
                        L.x() + 8, ry, pathColor, false);
                g.drawString(this.font, value, L.x() + L.w() - 6 - vw, ry, 0xFF9099A8, false);
            }
            int footerY = listTop + Math.min(dataEntries.size(), R.listRows()) * ROW_H;
            String footer = (dataScroll + R.listRows()) + " / " + dataEntries.size()
                    + "  ·  " + tr("logiscope.data.hint.pick");
            g.drawString(this.font, cut(footer, L.w() - 2 * PAD), L.x() + PAD, footerY, 0xFF707070, false);
        }

        // ---- 3) 追踪目标列表（确认添加后出现在这里；只能删，不能改）----
        ProbeInstance src = dataSrcProbe();
        List<DataTarget> dts = src == null ? List.of() : src.dataTargets();
        g.drawString(this.font, tr("logiscope.data.targets") + " · " + dts.size()
                + "  ·  " + tr("logiscope.data.targets_hint"), left, R.tgtTop(), COLOR_ANALYZE, false);
        for (int i = 0; i < R.tgtRows(); i++) {
            if (i >= dts.size()) {
                break;
            }
            DataTarget dt = dts.get(i);
            int ry = R.tgtTop() + (i + 1) * ROW_H;
            boolean overDel = isIn(mouseX, mouseY, L.x() + L.w() - 4 - DEL_W, ry - 1, DEL_W, ROW_H);
            boolean over = isIn(mouseX, mouseY, L.x() + 4, ry - 1, L.w() - 8, ROW_H);
            if (over) {
                g.fill(L.x() + 4, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            String name = dt.hasNamePath() ? "\u25c2" + dt.namePath()
                    : (dt.fallbackName().equals(dt.valuePath()) ? "(自动)" : dt.fallbackName());
            String text = dt.valuePath() + "  \u2192  " + name;
            int textW = L.w() - 8 - DEL_W - 6;
            g.drawString(this.font, cut(text, Math.max(20, textW)), L.x() + 8, ry, 0xFFE0E0EC, false);
            g.fill(L.x() + L.w() - 4 - DEL_W, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1,
                    overDel ? 0xFF7A3038 : 0xFF2A2A38);
            g.drawString(this.font, "\u00d7", L.x() + L.w() - 4 - DEL_W + 4, ry,
                    overDel ? 0xFFFFD0D0 : 0xFFC0A0A8, false);
        }
        if (dts.size() > R.tgtRows()) {
            String more = "+" + (dts.size() - R.tgtRows());
            g.drawString(this.font, more, left, R.tgtTop() + (R.tgtRows() + 1) * ROW_H,
                    0xFF9099A8, false);
        }

        // ---- 4) 虚拟容器：一行两格（值 / 名称）----
        int cellY = R.editTop();
        drawEditCell(g, left, cellY, R.cellW(), tr("logiscope.data.field.value"),
                dataPath, dataFocus == 1, false);
        drawEditCell(g, left + R.cellW() + PAD, cellY, R.cellW(), tr("logiscope.data.field.name"),
                dataName, dataFocus == 2, dataNamePath != null);
    }

"""
s = s.replace(old_render, new_render, 1)

# ---- 5) handleDataClick：加目标区的删除命中 ----
old_click = """        // 命令原文区：点一下收起焦点（并允许滚轮）
        if (my < R.listTop()) {
            dataFocus = 0;
            return true;
        }
        // 其余（列表带与编辑格之间 / 按钮区）→ 交给按钮循环（false = 不吞）
        return false;
    }"""
new_click = """        // 追踪目标列表：只有行尾的 × 是活的（值与名称**不能**在列表里改，要改就删了重加）
        if (my >= R.tgtTop() && my < R.tgtTop() + (R.tgtRows() + 1) * ROW_H) {
            if (button == 0) {
                int row = (my - R.tgtTop() - ROW_H) / ROW_H;   // 第一行是表头
                ProbeInstance src2 = dataSrcProbe();
                List<DataTarget> dts = src2 == null ? List.of() : src2.dataTargets();
                if (row >= 0 && row < R.tgtRows() && row < dts.size()
                        && isIn(mx, my, L.x() + L.w() - 4 - DEL_W,
                                R.tgtTop() + (row + 1) * ROW_H - 1, DEL_W, ROW_H)) {
                    src2.removeDataTarget(dts.get(row).valuePath());
                }
            }
            return true;
        }

        // 命令原文区：点一下收起焦点（并允许滚轮）
        if (my < R.listTop()) {
            dataFocus = 0;
            return true;
        }
        // 其余（列表带与编辑格之间 / 按钮区）→ 交给按钮循环（false = 不吞）
        return false;
    }"""
rep(old_click, new_click)

# ---- 6) startDataSampling → confirmDataTarget ----
old_start = s[s.index("    private void startDataSampling() {"):
              s.index("    // ==================================================================\n    // 小工具")]
new_start = """    /**
     * 「确认添加」：把当前选的 值/路径 + 名称 作为**一个追踪目标**加进探针。
     *
     * ★ 与第十一轮③的区别 ★ 原先是"立即建一个纯数据探针开始采样"，一次只能追一个值；
     * 现在目标攒在**这个探针**的列表里（可以加好几个），回选格点「完成」后与
     * 容器格的物品监测**一起**开始。目标加进列表后值与名称就锁定了 —— 要改就删掉重加
     * （"配置"和"正在跑的记录"必须分开，改配置不碰数据）。
     */
    private void confirmDataTarget() {
        ProbeInstance src = dataSrcProbe();
        String path = dataPath.trim();
        if (src == null) {
            return;
        }
        if (path.isEmpty()) {
            say(tr("logiscope.flash.data_need_path"));
            return;
        }
        ProbeTarget t = src.target();
        boolean added = src.addDataTarget(new DataTarget(path, dataNamePath, dataName.trim(),
                t.deviceName(), t.dimension(), t.pos()));
        if (added) {
            dataPath = "";
            dataName = "";
            dataNamePath = null;
            say(tr("logiscope.flash.data_added").replace("%s", path));
        } else {
            say(tr("logiscope.flash.data_dup").replace("%s", path));
        }
    }

"""
s = s.replace(old_start, new_start, 1)

# ---- 7) onChip：data_sample → data_confirm ----
rep('            case "data_sample" -> startDataSampling();',
    '            case "data_confirm" -> confirmDataTarget();')

# ---- 8) DATA 按钮 ----
rep("""            case DATA -> {
                out.add(new Item("data_refresh", tr("logiscope.btn.data_refresh"), true));
                out.add(new Item("data_sample", tr("logiscope.btn.data_sample"),
                        !dataPath.isBlank() && dataSrcBlock() != null));
                out.add(new Item("data_back", tr("logiscope.btn.to_picker"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }""",
    """            case DATA -> {
                ProbeInstance dsrc = dataSrcProbe();
                out.add(new Item("data_refresh", tr("logiscope.btn.data_refresh"), true));
                // 确认添加：把当前 值/路径 + 名称 变成一个追踪目标（可以攒多个）
                out.add(new Item("data_confirm", tr("logiscope.btn.data_confirm"),
                        dsrc != null && !dataPath.isBlank()));
                out.add(new Item("data_back", tr("logiscope.btn.to_picker"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }""")

# ---- 9) 选格页「完成」：有数据目标时允许不选格子（纯数据探针）----
rep("""                out.add(new Item("clear_cells", tr("logiscope.btn.clear_cells"), selectionCount() > 0));
                out.add(new Item("done", tr("logiscope.btn.done"), selectionCount() > 0));""",
    """                out.add(new Item("clear_cells", tr("logiscope.btn.clear_cells"), selectionCount() > 0));
                ProbeInstance doneProbe = pickerProbe();
                out.add(new Item("done", tr("logiscope.btn.done"),
                        selectionCount() > 0 || (doneProbe != null && doneProbe.isDataProbe())));""")

# ---- 10) commitPicker：有数据目标时允许空格子 ----
rep("""        if (refs.isEmpty()) {
            say(tr("logiscope.flash.need_cells"));
            return;
        }""",
    """        if (refs.isEmpty() && p.dataTargets().isEmpty()) {
            say(tr("logiscope.flash.need_cells"));
            return;
        }""")

# ---- 11) leavePicker：带数据目标的探针不许顺手删 ----
rep("""    private void leavePicker() {
        ProbeInstance p = LogiScopeMod.probes().find(pickerProbeId);
        if (p != null && !p.hasSlots()) {
            LogiScopeMod.probes().remove(p.id());
        }""",
    """    private void leavePicker() {
        ProbeInstance p = LogiScopeMod.probes().find(pickerProbeId);
        if (p != null && !p.hasAnything()) {
            LogiScopeMod.probes().remove(p.id());
        }""")

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ScopeScreen patch C ok")
