# -*- coding: utf-8 -*-
import io

p = "src/main/java/com/jessetech/logiscope/ui/ScopeScreen.java"
s = io.open(p, encoding="utf-8").read()


def rep(old, new, count=1):
    global s
    assert old in s, "NOT FOUND: " + old[:70]
    s = s.replace(old, new, count)


# ---- 1) Page 枚举 + imports ----
rep("""    /** 页面（REPLAY = 存档回放，第十一轮 / G5 加入） */
    public enum Page {
        ROOT, PICKER, CHART, REPLAY
    }""",
    """    /** 页面（REPLAY = 存档回放 G5；DATA = NBT 取数探针第十一轮③） */
    public enum Page {
        ROOT, PICKER, CHART, REPLAY, DATA
    }""")

rep("import com.jessetech.logiscope.analysis.SeriesPoint;",
    "import com.jessetech.logiscope.analysis.SeriesPoint;\nimport com.jessetech.logiscope.analysis.Snbt;")

rep("import com.jessetech.logiscope.bind.ContainerAnchor;",
    "import com.jessetech.logiscope.bind.ContainerAnchor;\nimport com.jessetech.logiscope.bind.DataCommand;\nimport com.jessetech.logiscope.bind.DataTarget;")

# ---- 2) 字段 ----
rep("""    /** 「运算」弹窗是否开着 */
    private boolean opPopupOpen;""",
    """    /** 「运算」弹窗是否开着 */
    private boolean opPopupOpen;

    // ---- NBT 取数探针（第十一轮③ / DATA 页）----
    /** 这一页在读哪个探针的方块（来自选格页「Probe data」） */
    private int dataSrcProbeId = Integer.MIN_VALUE;
    /** 采样值路径（可手输，也可点下面的值填进来） */
    private String dataPath = "";
    /** 名称字面量（可手输；点一个字符串值也会填这里） */
    private String dataName = "";
    /**
     * 名称的**来源路径**（非空 = 名称跟着 NBT 走，用于流体 / 化学品这类可变物品）。
     * 用户一旦手输名称，这个就被清掉（手输优先，见 {@link #dataNameEdited}）。
     */
    @Nullable
    private String dataNamePath;
    /** 当前聚焦的输入格：0 = 没有，1 = 值路径，2 = 名称 */
    private int dataFocus;
    private int dataScroll;
    private int dataTextScroll;
    /** `data get block` 解析出来的"路径 = 值"列表 */
    private List<Snbt.Entry> dataEntries = List.of();
    /** 上一次解析用的原文（变了才重解析） */
    private String dataParsedFrom = "";
    /** 自动刷新：下一次发命令的客户端 tick */
    private long dataNextRequestTick;
    /** 命令原文按当前宽度折行后的结果（缓存：宽度或原文变了才重算） */
    private List<String> dataTextLines = List.of();
    private String dataTextCachedFrom = "";
    private int dataTextCachedWidth = -1;""")

# ---- 3) 选格页按钮 ----
rep("""                out.add(new Item("clear_cells", tr("logiscope.btn.clear_cells"), selectionCount() > 0));
                out.add(new Item("done", tr("logiscope.btn.done"), selectionCount() > 0));
                out.add(new Item("to_root", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));""",
    """                out.add(new Item("clear_cells", tr("logiscope.btn.clear_cells"), selectionCount() > 0));
                out.add(new Item("done", tr("logiscope.btn.done"), selectionCount() > 0));
                // NBT 取数探针的入口：需要"这台设备有坐标"（没坐标读不到方块实体）
                ProbeInstance pp = pickerProbe();
                out.add(new Item("probe_data", tr("logiscope.btn.probe_data"),
                        pp != null && pp.target().hasPersistentId()));
                out.add(new Item("to_root", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));""")

rep("""            case REPLAY -> {
                out.add(new Item("replay_back", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }""",
    """            case REPLAY -> {
                out.add(new Item("replay_back", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            case DATA -> {
                out.add(new Item("data_refresh", tr("logiscope.btn.data_refresh"), true));
                out.add(new Item("data_sample", tr("logiscope.btn.data_sample"),
                        !dataPath.isBlank() && dataSrcBlock() != null));
                out.add(new Item("data_back", tr("logiscope.btn.to_picker"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }""")

# ---- 4) infoRows / 标题 / 状态 ----
rep("""    private int infoRows() {
        return page == Page.CHART || page == Page.REPLAY ? 3 : 2;
    }""",
    """    private int infoRows() {
        return page == Page.CHART || page == Page.REPLAY ? 3 : 2;
    }

    // ------------------------------------------------------------------
    // DATA 页（NBT 取数探针）的几何
    // ------------------------------------------------------------------

    private record DataRects(int textTop, int textH, int listTop, int listRows,
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
    }""")

rep("""            case REPLAY -> "LogiScope · " + tr("logiscope.page.replay");
        };""",
    """            case REPLAY -> "LogiScope · " + tr("logiscope.page.replay");
            case DATA -> "LogiScope · " + tr("logiscope.page.data");
        };""")

rep("""            case REPLAY -> "[ " + tr("logiscope.status.replay") + " ]";""",
    """            case REPLAY -> "[ " + tr("logiscope.status.replay") + " ]";
            case DATA -> "[ " + tr("logiscope.status.data") + " ]";""")

rep("""        if (page == Page.REPLAY) {
            return COLOR_ANALYZE;
        }
        ProbeInstance p = currentProbe();""",
    """        if (page == Page.REPLAY) {
            return COLOR_ANALYZE;
        }
        if (page == Page.DATA) {
            return DataCommand.statusColor();
        }
        ProbeInstance p = currentProbe();""")

# ---- 5) render 分发 ----
rep("""            case CHART, REPLAY -> {
                if (L.hasChart()) {
                    chart.render(g, this.font, chartData,
                            x + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), chartView(L, mouseX, mouseY));
                    renderAnalyzeBox(g, L);
                }
                renderToolbar(g, L.toolbar(), mouseX, mouseY);
                renderLegend(g, L.legend(), mouseX, mouseY);
            }""",
    """            case CHART, REPLAY -> {
                if (L.hasChart()) {
                    chart.render(g, this.font, chartData,
                            x + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), chartView(L, mouseX, mouseY));
                    renderAnalyzeBox(g, L);
                }
                renderToolbar(g, L.toolbar(), mouseX, mouseY);
                renderLegend(g, L.legend(), mouseX, mouseY);
            }
            case DATA -> renderDataPage(g, L, mouseX, mouseY);""")

# ---- 6) init / tick ----
rep("""        if (page == Page.REPLAY && replayArchive == null) {
            page = Page.ROOT;
        }""",
    """        if (page == Page.REPLAY && replayArchive == null) {
            page = Page.ROOT;
        }
        if (page == Page.DATA && dataSrcBlock() == null) {
            page = Page.ROOT;
        }""")

rep("""    @Override
    public void tick() {
        refreshSnapshots();
        if (page == Page.PICKER) {""",
    """    @Override
    public void tick() {
        refreshSnapshots();
        if (page == Page.DATA) {
            tickDataPage();
        }
        if (page == Page.PICKER) {""")

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ScopeScreen patch A ok")
