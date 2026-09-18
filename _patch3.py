# -*- coding: utf-8 -*-
import io

p = "src/main/java/com/jessetech/logiscope/ui/ScopeScreen.java"
s = io.open(p, encoding="utf-8").read()


def rep(old, new, count=1):
    global s
    assert old in s, "NOT FOUND: " + old[:70]
    s = s.replace(old, new, count)


# ---- onChip 分支 ----
rep("""            case "calc_op" -> opPopupOpen = !opPopupOpen;""",
    """            case "calc_op" -> opPopupOpen = !opPopupOpen;
            case "probe_data" -> enterDataPage();
            case "data_refresh" -> {
                BlockPos pos = dataSrcBlock();
                if (pos != null) {
                    DataCommand.request(pos);
                    say(tr(DataCommand.statusKey()));
                }
            }
            case "data_sample" -> startDataSampling();
            case "data_back" -> {
                cellMode = false;
                page = Page.PICKER;
            }""")

# ---- mouseScrolled：DATA 页滚动 ----
rep("""            // 根页 / 选格页：滚轮翻列表（需求要求保留"滚轮列表选择"这条路）""",
    """            // DATA 页：滚命令原文 / 滚值列表（看光标落在哪一块）
            if (page == Page.DATA) {
                DataRects R = dataRects(L);
                if (my >= R.textTop() && my < R.textTop() + R.textH()) {
                    dataTextScroll = Math.max(0, dataTextScroll - dir);
                } else {
                    dataScroll = Math.max(0, dataScroll - dir);
                }
                return true;
            }
            // 根页 / 选格页：滚轮翻列表（需求要求保留"滚轮列表选择"这条路）""")

# ---- mouseClicked：DATA 页点击 ----
rep("""        // 4) 工具栏 / 图例 / 按钮""",
    """        // 3.5) DATA 页：值列表 / 编辑格
        if (page == Page.DATA && handleDataClick(mx, my, L, button)) {
            return true;
        }

        // 4) 工具栏 / 图例 / 按钮""")

# ---- 键盘输入 ----
rep("""    // ==================================================================
    // 小工具
    // ==================================================================""",
    """    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (page == Page.DATA && dataFocus != 0 && !Character.isISOControl(codePoint)) {
            if (dataFocus == 1) {
                if (dataPath.length() < 120) {
                    dataPath += codePoint;
                }
            } else if (dataName.length() < 48) {
                dataName += codePoint;
                dataNamePath = null;   // 手输名称 → 不再跟 NBT 走（手输优先）
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (page == Page.DATA && dataFocus != 0) {
            switch (keyCode) {
                case 259 -> {   // Backspace
                    if (dataFocus == 1) {
                        dataPath = chop(dataPath);
                    } else {
                        dataName = chop(dataName);
                    }
                    return true;
                }
                case 256, 257, 335 -> {   // Esc / Enter / 小键盘 Enter → 收起焦点
                    dataFocus = 0;
                    return true;
                }
                case 86 -> {   // Ctrl+V：路径又长又难打，支持粘贴
                    if (hasControlDown()) {
                        pasteIntoFocusedField();
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static String chop(String s) {
        return s.isEmpty() ? s : s.substring(0, s.length() - 1);
    }

    private void pasteIntoFocusedField() {
        try {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip == null) {
                return;
            }
            clip = clip.trim();
            if (dataFocus == 1) {
                dataPath = clip.length() > 120 ? clip.substring(0, 120) : clip;
            } else {
                dataName = clip.length() > 48 ? clip.substring(0, 48) : clip;
                dataNamePath = null;
            }
        } catch (Exception ignored) {
            // 剪贴板不可用时静默（不该因为粘贴失败把界面搞崩）
        }
    }

    // ==================================================================
    // NBT 取数探针（第十一轮③ / DATA 页）
    // ==================================================================

    /** 「Probe data」：进入这一页，开始实时抓 `data get block` */
    private void enterDataPage() {
        ProbeInstance p = pickerProbe();
        if (p == null || !p.target().hasPersistentId()) {
            say(tr("logiscope.flash.no_target"));
            return;
        }
        dataSrcProbeId = p.id();
        dataPath = "";
        dataName = "";
        dataNamePath = null;
        dataFocus = 0;
        dataEntries = List.of();
        dataParsedFrom = "";
        dataTextLines = List.of();
        dataTextCachedFrom = "";
        dataTextScroll = 0;
        dataScroll = 0;
        dataNextRequestTick = 0;
        page = Page.DATA;
        DataCommand.request(p.target().pos());
        dataNextRequestTick = LogiScopeMod.probes().currentTick() + DataRefreshTicks;
    }

    /** 每客户端 tick：抓数（实时刷新）+ 解析 */
    private void tickDataPage() {
        DataCommand.tick();
        BlockPos pos = dataSrcBlock();
        if (pos == null) {
            return;
        }
        long now = LogiScopeMod.probes().currentTick();
        if (now >= dataNextRequestTick) {
            DataCommand.request(pos);
            dataNextRequestTick = now + DataRefreshTicks;
        }
        reparseData();
    }

    @Nullable
    private BlockPos dataSrcBlock() {
        ProbeInstance p = LogiScopeMod.probes().find(dataSrcProbeId);
        return p != null && p.target().hasPersistentId() ? p.target().pos() : null;
    }

    @Nullable
    private ProbeInstance dataSrcProbe() {
        return LogiScopeMod.probes().find(dataSrcProbeId);
    }

    /** 命令原文变了就重新解析成"路径 = 值"（解析失败 → 空表，界面会说明） */
    private void reparseData() {
        String text = DataCommand.text();
        if (text == null || text.equals(dataParsedFrom)) {
            return;
        }
        dataParsedFrom = text;
        Snbt.Node root = Snbt.parse(text);
        dataEntries = root == null ? List.of() : Snbt.flatten(root);
        dataScroll = 0;
        dataTextScroll = 0;
    }

    /** 命令原文按当前宽度折行（缓存：宽度或原文变了才重算） */
    private void ensureDataTextWrapped(int width) {
        String text = DataCommand.text();
        if (text == null) {
            dataTextLines = List.of();
            return;
        }
        if (text.equals(dataTextCachedFrom) && width == dataTextCachedWidth) {
            return;
        }
        dataTextCachedFrom = text;
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
    }

    private void renderDataPage(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        DataRects R = dataRects(L);
        int left = L.x() + PAD;
        int width = L.w() - 2 * PAD;

        // ---- 1) 命令原文（实时）----
        g.fill(left, R.textTop(), left + width, R.textTop() + R.textH(), 0xFF13131B);
        ring(g, left, R.textTop(), width, R.textH(), 0xFF33333F);
        DataCommand.Status st = DataCommand.status();
        if (st == DataCommand.Status.OK || (DataCommand.text() != null && !DataCommand.text().isEmpty())) {
            ensureDataTextWrapped(width - 8);
            int lineH = 10;
            int maxLines = Math.max(1, (R.textH() - 4) / lineH);
            dataTextScroll = Math.max(0, Math.min(dataTextScroll,
                    Math.max(0, dataTextLines.size() - maxLines)));
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

        // ---- 2) 值列表（解析出来的"路径 = 值"）----
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

        // ---- 3) 虚拟容器：一行两格（值 / 名称）----
        int cellY = R.editTop();
        String nameText = dataNamePath != null && !dataName.isBlank() ? dataName : dataName;
        drawEditCell(g, left, cellY, R.cellW(), tr("logiscope.data.field.value"),
                dataPath, dataFocus == 1, false);
        drawEditCell(g, left + R.cellW() + PAD, cellY, R.cellW(), tr("logiscope.data.field.name"),
                nameText, dataFocus == 2, dataNamePath != null);
    }

    /** 一个可编辑的小格：上面一行小标签，下面一格内容（焦点时描边 + 光标） */
    private void drawEditCell(GuiGraphics g, int x, int y, int w, String label,
                              String value, boolean focused, boolean autoName) {
        g.drawString(this.font, label, x, y - 9, 0xFF8A8AA0, false);
        int border = focused ? 0xFF8A8AC8 : 0xFF4A4A5E;
        g.fill(x, y, x + w, y + CHIP_H, focused ? 0xFF2A2A38 : 0xFF1C1C26);
        ring(g, x, y, w, CHIP_H, border);
        String shown = value == null ? "" : value;
        int avail = w - 12;
        String draw = this.font.width(shown) > avail ? cut(shown, avail) : shown;
        int color = shown.isEmpty() ? 0xFF6A6A78 : 0xFFE0E0EC;
        g.drawString(this.font, shown.isEmpty() ? tr("logiscope.data.field.empty") : draw,
                x + 5, y + 3, color, false);
        if (autoName && !shown.isEmpty()) {
            // 名称跟着 NBT 走时给一个品红点（与"名称来源是 NBT"同一语汇）
            g.fill(x + w - 5, y + 3, x + w - 2, y + 6, COLOR_ANALYZE);
        }
        if (focused && (LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = x + 5 + Math.min(this.font.width(shown), avail);
            g.fill(cx, y + 3, cx + 1, y + CHIP_H - 3, 0xFFE0E0EC);
        }
    }

    /** DATA 页的鼠标点击：值列表（左键取值路径 / 右键用它的文字作名称）+ 两个编辑格 */
    private boolean handleDataClick(int mx, int my, Layout L, int button) {
        DataRects R = dataRects(L);
        int left = L.x() + PAD;

        // 编辑格
        if (my >= R.editTop() && my < R.editTop() + CHIP_H) {
            if (isIn(mx, my, left, R.editTop(), R.cellW(), CHIP_H)) {
                dataFocus = 1;
                return true;
            }
            if (isIn(mx, my, left + R.cellW() + PAD, R.editTop(), R.cellW(), CHIP_H)) {
                dataFocus = 2;
                return true;
            }
        }

        // 值列表
        if (my >= R.listTop()) {
            int row = (my - R.listTop()) / ROW_H;
            int idx = dataScroll + row;
            if (row >= 0 && row < R.listRows() && idx < dataEntries.size()) {
                Snbt.Entry e = dataEntries.get(idx);
                if (button == 0) {
                    if (e.numeric()) {
                        dataPath = e.path();
                    } else {
                        // 数值叶子才可采样：点到字符串值就当作"指定名称"
                        dataName = e.node().text() == null ? e.path() : e.node().text();
                        dataNamePath = e.path();
                    }
                    return true;
                }
                if (button == 1) {
                    dataName = e.node().text() == null ? e.path() : e.node().text();
                    dataNamePath = e.textual() ? e.path() : null;
                    return true;
                }
            }
            return true;
        }

        // 命令原文区：点一下收起焦点（并允许滚轮）
        if (my < R.listTop()) {
            dataFocus = 0;
            return true;
        }
        return false;
    }

    /**
     * 「开始采样」：新建一个**数据探针**并按这条路径逐 tick 取值。
     *
     * 与物品探针的入口（选格 → 完成）并列的第二条创建路径：选格页那条走
     * "菜单槽 / ItemHandler 能力"，这条走 "NBT 路径"。两者产出的都是 ProbeInstance，
     * 所以图表 / 框选 / 标记 / 存档 / 计算全部通用。
     */
    private void startDataSampling() {
        ProbeInstance src = dataSrcProbe();
        if (src == null) {
            return;
        }
        String path = dataPath.trim();
        if (path.isEmpty()) {
            say(tr("logiscope.flash.data_need_path"));
            return;
        }
        ProbeTarget t = src.target();
        ProbeInstance created = LogiScopeMod.probes().create(
                new ProbeTarget(List.of(), t.deviceName(), t.dimension(), t.pos()));
        if (created == null) {
            say(tr("logiscope.flash.too_many_probes"));
            return;
        }
        created.configureData(new DataTarget(path, dataNamePath, dataName.trim(),
                t.deviceName(), t.dimension(), t.pos()));
        // 选格页那个"还没选格的空实例"顺手清掉（不留垃圾，与 leavePicker 同一个规矩）
        if (!src.hasSlots()) {
            LogiScopeMod.probes().remove(src.id());
        }
        LogiScopeMod.probes().setActive(created.id());
        hiddenItems.clear();
        ioShown.clear();
        selection = null;
        selectionStats = List.of();
        live = true;
        userZoomed = false;
        builtVersion = Long.MIN_VALUE;
        page = Page.CHART;
        say(tr("logiscope.flash.data_sampling").replace("%s", path));
    }

    // ==================================================================
    // 小工具
    // ==================================================================""")

# ---- DATA 页的 info 行 ----
rep("""            case REPLAY -> {
                // 回放页的信息区：存档名 / 探针来源 / 统计""",
    """            case DATA -> {
                ProbeInstance src = dataSrcProbe();
                String head = src == null ? tr("logiscope.hint.no_target")
                        : src.name() + "  " + src.target().anchorText();
                g.drawString(this.font, cut(head, L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
                y += ROW_H;
                String line = tr("logiscope.data.src") + "  " + tr(DataCommand.statusKey());
                g.drawString(this.font, cut(line, L.w() - 2 * PAD), left, y,
                        DataCommand.statusColor(), false);
            }
            case REPLAY -> {
                // 回放页的信息区：存档名 / 探针来源 / 统计""")

# ---- 常量：刷新节拍 ----
rep("""    /** 「分析」选中曲线 / 波形标记的品红系（同一语汇：都是"用户钉在这里的东西"） */
    private static final int COLOR_ANALYZE = 0xFFE070D8;""",
    """    /** 「分析」选中曲线 / 波形标记的品红系（同一语汇：都是"用户钉在这里的东西"） */
    private static final int COLOR_ANALYZE = 0xFFE070D8;
    /** DATA 页自动重发 `data get block` 的节拍（客户端 tick）—— 1 秒一次就是"实时"了 */
    private static final long DataRefreshTicks = 20;""")

# ---- openProbe：数据探针也能直接进图表页 ----
rep("""        if (p.hasSlots()) {
            page = Page.CHART;""",
    """        if (p.hasAnything()) {
            page = Page.CHART;""")

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ScopeScreen patch B ok")
