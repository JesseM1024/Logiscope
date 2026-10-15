package com.jessetech.logiscope.ui;

import com.jessetech.logiscope.LogiScopeConfig;
import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.analysis.Aggregator;
import com.jessetech.logiscope.analysis.Analyzer;
import com.jessetech.logiscope.analysis.ChartData;
import com.jessetech.logiscope.analysis.CurveMath;
import com.jessetech.logiscope.analysis.Series;
import com.jessetech.logiscope.analysis.Val;
import com.jessetech.logiscope.analysis.SeriesPoint;
import com.jessetech.logiscope.analysis.NbtSlotLayout;
import com.jessetech.logiscope.analysis.SlotAlign;
import com.jessetech.logiscope.analysis.Snbt;
import com.jessetech.logiscope.archive.ArchiveData;
import com.jessetech.logiscope.archive.Archives;
import com.jessetech.logiscope.collect.AeNetworkReader;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.DataCommand;
import com.jessetech.logiscope.bind.DataTarget;
import com.jessetech.logiscope.bind.ProbeContext;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.collect.ContainerAccess;
import com.jessetech.logiscope.collect.ProbeInstance;
import com.jessetech.logiscope.collect.ProbeManager;
import com.jessetech.logiscope.collect.ProbeSlots;
import com.jessetech.logiscope.store.Sample;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 呈现层：示波器面板。**三个页面共用一个浮层**。
 *
 * ★ 它是一个"可以拖动、可以缩放、不遮挡原界面"的浮层，不是一个全屏界面 ★
 *
 * 实现方式是 NeoForge 的 GUI 图层栈：
 *   ClientHooks.pushGuiLayer(mc, this)   —— 把当前容器界面压进栈里当背景，自己当顶层
 *   ClientHooks.popGuiLayer(mc)          —— 弹回容器界面
 *
 * 核实要点（这一条在本项目文档里曾被写错，见 docs/ARCHITECTURE.md 第四节）：
 * ClientHooks 里的 guiLayers 是货真价实的 {@code Stack<Screen>}，
 * 源码注释写着 "Contains the *extra* GUI layers. The current top layer stays in
 * Minecraft#currentScreen, and the rest serve as a background for it."
 * 渲染时 ClientHooks#drawScreen 会先把栈里的每一层画一遍（鼠标坐标传 MAX_VALUE），
 * 再画最顶层 —— 所以被压住的容器界面**仍然会被完整绘制**，这才是"不覆盖"的来源。
 *
 * ==================================================================
 * 第九轮的三页结构（需求 1 / 2 / 3 直接落在这里）
 * ==================================================================
 *
 * ```
 *  ROOT 根页          「＋ 创建探针」 + 「正在运行的探针」列表（每行末尾一个 × 删除）
 *    │ 点创建探针 / 准星指着无 GUI 的容器
 *    ▼
 *  PICKER 选格页      「选择容器格」可视化选格 + 列表选格 + 「清空选择」 + 「完成」
 *    │ 点完成
 *    ▼
 *  CHART 图表页       波形本身；「选格」回到**本实例**的选格页，「列表」回根页
 * ```
 *
 * 为什么"根页"取代了原来的"槽位列表"：多探针之后，"打开面板"这件事的默认意图
 * 变成了"我要建一个新的 / 我要看某个已经在跑的"，而不是"我要在这一台设备上选一格"。
 *
 * 【可视化选格（PICKER 的主方案）】
 *   点「选择容器格」后进入 cellMode：
 *     · **光标不再与容器交互**（`mouseClicked/Dragged/Released/Scrolled` 在面板外一律吃掉，
 *       不转发给被压住的容器界面）—— 否则"点一个格子"会顺手把物品拿起来
 *     · 左键点/划过的格子 → 选中；右键点/划过的格子 → 取消（需求原文）
 *     · 拖动时按**线段采样**逐格命中，快速划过也不会漏（只按当前位置判定会漏格）
 *     · 面板外的槽位列表仍然可以点，两种选法结果一致
 *   滚轮列表选格保留（无 GUI 的容器只能用它，见需求 3）。
 *
 * 【本类只做三件事】
 *   1. 布局：把面板切成标题 / 信息 / 工具栏 / 列表或波形 / 图例 / 按钮几块
 *   2. 交互：鼠标命中、拖动、缩放、框选、图例开关、选格
 *   3. 转交：面板之外的鼠标事件交给被压住的容器界面（选格模式下**不转交**）
 * 画波形的事全在 ChartRenderer 里；布局与命中判定共用 layout() 一份结果，
 * 避免"渲染按一套坐标、点击按另一套坐标"这类最难查的错位 bug。
 */
public final class ScopeScreen extends Screen {

    /** 页面（REPLAY = 存档回放 G5；DATA = NBT 取数探针第十一轮③） */
    public enum Page {
        ROOT, PICKER, CHART, REPLAY, DATA
    }

    /** 选格页的"槽位清单从哪来" */
    private enum Source {
        /** 一个都没有（没开容器、实例也没坐标） */
        NONE,
        /** 容器界面开着 → 菜单槽，可视化选格可用 */
        MENU,
        /** 靠服务端读方块实体列出来的清单（无 GUI 容器），只能按列表选 */
        WORLD
    }

    // ==================================================================
    // 面板几何（跨"关闭-重开"保持 —— 这是使用偏好，不是数据）
    // ==================================================================

    private static final int DEFAULT_W = 372;
    private static final int DEFAULT_H = 258;
    /** 最小尺寸压到这个 —— 再小波形就没法看了（会显示"面板太小"的提示） */
    private static final int MIN_W = 250;
    private static final int MIN_H = 168;

    private static int panelX = Integer.MIN_VALUE;
    private static int panelY = Integer.MIN_VALUE;
    private static int panelW = DEFAULT_W;
    private static int panelH = DEFAULT_H;

    /** 可见窗口宽度（tick）。默认 400 tick = 20 秒，是看"产线节奏"比较舒服的尺度 */
    private static int windowTicks = 400;
    private static final int MIN_WINDOW = 20;
    private static final int MAX_WINDOW = 20 * 60 * 30;

    /** 滚轮每格的缩放系数（>1 放大 = 看更短的时间）。老的 ×2 太猛，"一格就缩到头"就是它 */
    private static final double WHEEL_ZOOM_FACTOR = 1.25;
    /** 按钮一次的缩放系数（要有比滚轮更明确的步进感） */
    private static final double BUTTON_ZOOM_FACTOR = 1.5;

    /** 图例里被关掉的物品线。跨开合保持：它是"看图的偏好"，不是数据 */
    private static final Set<String> hiddenItems = new HashSet<>();

    /**
     * 开了 IO 线（单 tick 净流量）的物品。
     *
     * 为什么单独一份集合而不是"hiddenItems 的补集"：两者是**两级**开关，
     * 四象限都有意义（只看存量 / 只看 IO / 都看 / 都不看）。
     */
    private static final Set<String> ioShown = new HashSet<>();

    /** 由输入层设置的"一打开面板就提示这句话"（例如"手上的物品会抢走右键"） */
    @Nullable
    private static String pendingFlashKey;

    // ==================================================================
    // 布局常量
    // ==================================================================

    private static final int TITLE_H = 14;
    private static final int ROW_H = 11;
    private static final int CHIP_H = 12;
    private static final int CHIP_GAP = 3;
    private static final int PAD = 5;
    private static final int RESIZE_GRIP = 12;
    private static final int MAX_LEGEND_ROWS = 3;
    /** 图例弹出菜单每行的高度 */
    private static final int POPUP_ROW_H = 13;
    /** 波形区低于这个高度就不画了 —— 画出来的东西没有信息量，只会误导 */
    private static final int MIN_CHART_H = 44;
    /** 列表行末尾删除按钮的宽度 */
    private static final int DEL_W = 13;

    private static final String SERIES_PREFIX = "series:";
    /** 「分析」选中曲线 / 波形标记的品红系（同一语汇：都是"用户钉在这里的东西"） */
    private static final int COLOR_ANALYZE = 0xFFE070D8;
    /** NBT 原文悬浮高亮：黄（用户反馈淡紫在深底上难认；黄与深底对比最强） */
    private static final int COLOR_HILIGHT = 0x80FFD24A;
    /** QoL（第十六轮⑦）：「主界面」按钮淡绿 / 「回选格」淡蓝 —— 也用作顶栏光效的页色 */
    private static final int COLOR_MAINSCREEN = 0xFF90EE90;
    private static final int COLOR_BACKSLOT = 0xFF8FB8E8;
    private static final int COLOR_CHART_GLOW = 0xFFFFE08A;
    private static final int COLOR_CALC_GLOW = 0xFFC0A0F0;
    /** DATA 页自动重发 `data get block` 的节拍（客户端 tick）—— 1 秒一次就是"实时"了 */
    private static final long DataRefreshTicks = 20;

    // ==================================================================
    // 会话状态（每次打开重置）
    // ==================================================================

    private Page page;
    /** 选格页正在配置哪个实例（-1 = 没有） */
    private int pickerProbeId;

    // ---- 图例弹出菜单 ----
    /**
     * 当前弹出菜单对应的物品（null = 没弹）。
     *
     * ★ 第十轮⑥：IO 不再是图例条上独立的子格，而是**并入物品图例** ★
     * 点物品图例 → 弹出「数量 / IO」两项的小菜单，两项分别控制存量线与 IO 线。
     * 为什么改成弹窗：图例条是流式换行的色块带，"父格 + IO 子格"成对出现
     * 让条目数翻倍（截图实测 9 种物品挤满 3 行上限），而且 IO 子格和物品格
     * 长得几乎一样，层级关系要靠猜。弹窗把"每个物品有两层开关"这件事
     * 收进第二次点击里，图例条本身永远一行一个物品。
     *
     * 它是**瞬态 UI 状态**（不是用户偏好），所以是实例字段，重开面板即复位。
     */
    @Nullable
    private String legendPopup;

    // ---- 存档回放（REPLAY 页，第十一轮 / G5）----
    /** 当前载入的存档；null = 没在回放。回放数据在打开面板期间驻留（存档读盘一次，之后全内存） */
    @Nullable
    private ArchiveData replayArchive;
    /** 回放的波形标记：内存里编辑（加/删），不自动写回存档文件（重导出才会带上） */
    private final List<Long> replayMarkers = new ArrayList<>();
    /**
     * 回放页里被「分析」选中的曲线（可扩展点）。
     *
     * 需求原文：回放逻辑要有可扩展性，"例如我可能需要选中某一个曲线，
     * 进行下一步的数学计算操作"。本轮只把**选中**这件事做出来（图例弹窗里的
     * 「分析」一项，选中后图例块描品红边），后续的数学运算（差值 / 滤波 / 对比）
     * 都挂在这个字段上往前叠，不用再动采集与存储层。
     */
    @Nullable
    private String replaySelectedItem;

    // ---- 存档重命名（第十三轮④）----
    /** 当前打开存档的显示名（可被重命名覆盖）与目录名（身份标识，永不改） */
    private String replayName = "";
    private String replayDir = "";
    /** 重命名输入框：是否激活 + 缓冲区（激活时吃掉键盘，Enter 确认 / Esc 取消） */
    private boolean renameFocus;
    private String renameBuf = "";

    // ---- 悬浮详情（第十三轮⑤）：长列表用滚轮翻，避免"只有个 +N 看不出是啥" ----
    private String tipTitle = "";
    private List<String> tipLines = List.of();
    private int tipScroll;
    private int tipKey;
    private int tipKeyShown;
    private final int[] tipBuf = new int[4];
    @Nullable
    private int[] tipRect;

    // ---- NBT 槽位型容器（第十六轮）：slot 号写在 NBT 自己身上，按 slot 监测才准 ----
    /** 检测到"数据里带 slot 号的物品列表"（只决定按钮能不能点） */
    private boolean nbtSlotDetected;
    /** 用户**显式开启**了 NBT 追踪（默认关 —— 避免误判机械手这类设备） */
    private boolean nbtSlotMode;
    /** 检测到的物品列表路径（如 Items） */
    private String nbtSlotListPath = "";
    /** 检测到的元素（slot / 物品 / 数量），用于"按内容反查 slot 号" */
    private List<NbtSlotLayout.Entry> nbtSlotEntries = List.of();
    /** AE 网络模式（第十六轮⑧）：菜单是 AE 的 ME 存储界面家族时启用 —— 点格 = 选"网络中的物品" */
    private boolean aeMode;
    /** AE 模式选中的物品 registry id（覆写语义：「完成」= 替换全部 AE 目标） */
    private final Set<String> selectedAeIds = new LinkedHashSet<>();
    private String nbtSlotParsedFrom = "";
    private long nbtSlotNextRequest;
    /**
     * "还没把已有锚点回填到界面上"。
     * 进选格页时数据可能还没抓到，回填会失败 —— 所以**每次 tick 再试**，
     * 一抓到就回填（否则「完成」= 覆写 会把用户看不见的目标全清掉）。
     */
    private boolean nbtSeedPending;

    // ---- 回放计算（第十一轮②）----
    /** 运算选择（工具栏「运算」弹窗）。OP_* 常量见下 */
    private int analyzeOp = OP_NONE;
    /** 计算对象：false = 数量（存量线），true = IO（净流量线）。**一次只算一个** */
    private boolean analyzeIo;
    /** 「运算」弹窗是否开着 */
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
    /** 追踪目标列表的滚动位置（目标可以攒很多，固定两三行放不下） */
    private int dataTgtScroll;
    /** `data get block` 解析出来的"路径 = 值"列表 */
    private List<Snbt.Entry> dataEntries = List.of();
    /** 上一次解析用的原文（变了才重解析） */
    private String dataParsedFrom = "";
    /** 自动刷新：下一次发命令的客户端 tick */
    private long dataNextRequestTick;
    /** 命令原文按当前宽度折行后的结果（缓存：宽度或原文变了才重算） */
    private List<String> dataTextLines = List.of();
    /** 与 {@link #dataTextLines} 一一对应的**源文本起始偏移**（悬浮高亮按它定位） */
    private List<Integer> dataTextStarts = List.of();
    private String dataTextCachedFrom = "";
    private int dataTextCachedWidth = -1;
    /** 计算结果（框选 + 曲线 + 对象 + 运算 四元组变化时才重算，其余帧直接用缓存） */
    private String analyzeTitle = "";
    private List<String> analyzeLines = List.of();
    private long aLo = Long.MIN_VALUE;
    private long aHi;
    private String aItem = "";
    private boolean aIo;
    private int aOp;

    /** 运算选择（与 {@link CurveMath} 的六种运算一一对应） */
    private static final int OP_NONE = 0;
    private static final int OP_EXTREMES = 1;
    private static final int OP_STATS = 2;
    private static final int OP_TOTAL = 3;
    private static final int OP_EVENTS = 4;
    private static final int OP_RISE_EVENTS = 5;
    private static final int OP_FALL_EVENTS = 6;

    private static String opKey(int op) {
        return switch (op) {
            case OP_EXTREMES -> "logiscope.calc.op.extremes";
            case OP_STATS -> "logiscope.calc.op.stats";
            case OP_TOTAL -> "logiscope.calc.op.total";
            case OP_EVENTS -> "logiscope.calc.op.events";
            case OP_RISE_EVENTS -> "logiscope.calc.op.rise_events";
            case OP_FALL_EVENTS -> "logiscope.calc.op.fall_events";
            default -> "logiscope.calc.op.none";
        };
    }

    private static final int[] OP_VALUES = {OP_NONE, OP_EXTREMES, OP_STATS, OP_TOTAL,
            OP_EVENTS, OP_RISE_EVENTS, OP_FALL_EVENTS};

    /** 「运算」弹窗矩形（渲染与命中共用；锚在工具栏 calc_op 块下方，底部放不下就往上弹） */
    @Nullable
    private int[] opPopupRect(Layout L) {
        if (!opPopupOpen) {
            return null;
        }
        for (Chip c : L.toolbar()) {
            if (!"calc_op".equals(c.id())) {
                continue;
            }
            int w = 0;
            for (int op : OP_VALUES) {
                w = Math.max(w, this.font.width(tr(opKey(op))) + 30);
            }
            int h = OP_VALUES.length * POPUP_ROW_H + 4;
            int x = Math.min(c.x(), L.x() + L.w() - w - 4);
            int y = c.y() + c.h() + 1;
            if (y + h > L.y() + L.h()) {
                y = c.y() - h - 1;
            }
            return new int[]{x, y, w, h};
        }
        return null;
    }

    /** 「运算」弹窗：压在工具栏 / 波形之上渲染 */
    private void renderOpPopup(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int[] pr = opPopupRect(L);
        if (pr == null) {
            return;
        }
        g.fill(pr[0], pr[1], pr[0] + pr[2], pr[1] + pr[3], 0xF2181822);
        ring(g, pr[0], pr[1], pr[2], pr[3], 0xFF3A3A4A);
        g.fill(pr[0], pr[1], pr[0] + pr[2], pr[1] + 1, COLOR_ANALYZE);
        int ry = pr[1] + 2;
        for (int op : OP_VALUES) {
            String label = tr(opKey(op));
            boolean hover = isIn(mouseX, mouseY, pr[0] + 1, ry, pr[2] - 2, POPUP_ROW_H);
            if (hover) {
                g.fill(pr[0] + 1, ry, pr[0] + pr[2] - 1, ry + POPUP_ROW_H, 0xFF2E2E3E);
            }
            // 当前选中项：品红方块（与「分析」选中曲线同一语汇）
            g.fill(pr[0] + 6, ry + 3, pr[0] + 12, ry + 9,
                    op == analyzeOp ? COLOR_ANALYZE : 0xFF3A3A44);
            g.drawString(this.font, label, pr[0] + 16, ry + 2,
                    op == analyzeOp ? 0xFFE0E0EC : 0xFFB8B8C8, false);
            ry += POPUP_ROW_H;
        }
    }

    // ---- 可视化选格 ----
    /** 「选择容器格」开关：true = 光标只用于选格，不与容器交互 */
    private boolean cellMode;
    /** 正在拖动选格时按住的是哪个键（0 加选 / 1 减选）；-1 = 没在拖 */
    private int cellDragButton = -1;
    private int cellLastX = Integer.MIN_VALUE;
    private int cellLastY = Integer.MIN_VALUE;
    /** 菜单槽下标（MENU 模式下的权威选择集合） */
    private final Set<Integer> selMenuSlots = new LinkedHashSet<>();
    /** 容器内下标；MENU 模式下由菜单派生，WORLD 模式下由用户直接编辑 */
    private final Set<Integer> selContainerSlots = new LinkedHashSet<>();
    /** 已经用实例的现有目标初始化过选格页了吗（按实例 id 记，rebuildWidgets 不重复清空） */
    private int seededForProbeId = Integer.MIN_VALUE;

    // ---- 图表页视图状态 ----
    /** 实时滚动 / 冻结。框选会自动切成冻结，见 mouseReleased */
    private boolean live = true;
    private long frozenEnd;
    /**
     * 用户是否主动缩放过（滚轮 / ± 按钮 / 全览之外的动作）。
     *
     * 为什么要记：window() 在"数据还没铺满窗口"时会把窗口起点钳到第一个数据点
     * （否则刚接上探头时波形挤在右边一小条）。但用户**主动缩放**之后必须忠实反映
     * windowTicks —— 不然数据未铺满窗口时怎么缩都看不见变化，
     * 这就是第七轮"多按几次才动"的根因。attach / 清空 / 全览时复位。
     */
    private boolean userZoomed;
    @Nullable
    private ChartRenderer.Selection selection;
    private boolean selecting;
    private int selAnchorX;
    private int selCurrentX;
    /** 框选期间锁住的可见窗口 —— 不锁的话波形会在拖的过程中往前跑，选不准 */
    private long selectWindowStart;
    private long selectWindowEnd;

    // ---- 通用交互 ----
    private boolean dragging;
    private int dragOffX;
    private int dragOffY;
    private boolean resizing;
    private int resizeGripX;
    private int resizeGripY;
    private int scrollRow;
    /** 根页下半存档列表的滚动位置 */
    private int scrollArchives;
    /** 根页展示的存档清单（init 时扫一次；导出/删除后手动失效） */
    private List<Archives.Info> archiveList = List.of();
    /** 存档筛选框的文字（第十八轮③）：非空时存档列表只显示名称/文件夹包含它的条目 */
    private String archFilter = "";
    /** 筛选框是否有键盘焦点（聚焦时按键进框；Esc 清空并收起，Enter 保留并收起） */
    private boolean archFilterFocus;

    private final ChartRenderer chart = new ChartRenderer();

    // 派生数据的缓存：只在 SampleStore 的 version 变化后重算（见 SampleStore#version）
    private ChartData chartData = ChartData.EMPTY;
    private Analyzer.Summary summary = Analyzer.Summary.EMPTY;
    private List<ChartData.SeriesRange> selectionStats = List.of();
    private long builtVersion = Long.MIN_VALUE;
    private int builtProbeId = Integer.MIN_VALUE;

    /** 本帧鼠标悬停到的「背景容器槽位」。render 开头刷新，末尾用它画悬浮提示 */
    @Nullable
    private Slot backgroundHoveredSlot;

    /** 这一 tick 的清单快照（选格页用） */
    private List<ContainerAccess.SlotView> menuSlots = List.of();
    private List<ContainerAccess.SlotView> worldSlots = List.of();
    private Source source = Source.NONE;
    /**
     * 这一 tick 有没有"可探测的容器"（决定「＋ 创建探针」能不能点）。
     *
     * ★ 为什么要缓存成字段，而不是在布局里现算 ★
     *   `ProbeContext.current` 里要做方块实体查询 + 最多 7 次 capability 查找，
     *   而 `layout()` 每帧会被调用好几次（render 一次，鼠标事件各一次）。
     *   每 tick 算一次就够，反正创建探针是用户点按钮时才发生的。
     */
    private boolean hasTarget;

    private String flash = "";
    private long flashUntil;
    /** 提示折行后的行（按 {@link #flash} 缓存：文本没变就不重算） */
    private List<String> flashLines = List.of();
    private String flashWrappedFrom = null;

    @Nullable
    private final Screen parent;
    private final boolean overlayMode;

    public ScopeScreen(@Nullable Screen parent, Page page, int probeId) {
        super(Component.translatable("logiscope.title"));
        this.parent = parent;
        this.overlayMode = parent != null;
        this.page = page;
        this.pickerProbeId = probeId;
    }

    // ==================================================================
    // 打开 / 关闭
    // ==================================================================

    /** 压在当前容器界面上打开 */
    public static void openOverlay(Minecraft mc, Page page, int probeId) {
        ScopeScreen scope = new ScopeScreen(mc.screen, page, probeId);
        ForgeHooksClient.pushGuiLayer(mc, scope);
        // ★ pushGuiLayer 不会替你释放鼠标（那是 Minecraft#setScreen 干的活），
        //   不手动释放的话界面上根本看不到光标，面板就没法点、没法拖、没法框选。
        mc.mouseHandler.releaseMouse();
        KeyMapping.releaseAll();
    }

    /** 没有容器界面可压时，当普通界面打开（例如关掉界面后只看波形） */
    public static void openStandalone(Minecraft mc, Page page, int probeId) {
        mc.setScreen(new ScopeScreen(null, page, probeId));
    }

    /**
     * 准星指着"有容器方块实体但没有 GUI"的设备时（需求 3）：
     * 直接新建一个绑定到它的探针实例，并进入选格页 —— 没有菜单，所以走列表选格。
     */
    public static void openWorldCreate(Minecraft mc, ResourceKey<Level> dim, BlockPos pos, String label) {
        ProbeManager mgr = LogiScopeMod.probes();
        ProbeInstance created = mgr.create(new ProbeTarget(List.of(), label, dim, pos));
        if (created == null) {
            flashOnOpen("logiscope.flash.too_many_probes");
            openStandalone(mc, Page.ROOT, -1);
            return;
        }
        ProbeSlots.request(dim, pos);
        openStandalone(mc, Page.PICKER, created.id());
    }

    /** 让下一次打开的面板显示一句提示（跨"创建界面"这一步传递） */
    public static void flashOnOpen(String langKey) {
        pendingFlashKey = langKey;
    }

    /** 关闭面板：浮层模式弹栈还原容器界面，独立模式回到游戏 */
    public void closeScope() {
        Minecraft mc = Minecraft.getInstance();
        if (overlayMode) {
            ForgeHooksClient.popGuiLayer(mc);
        } else {
            mc.setScreen(null);
        }
    }

    @Override
    public void onClose() {
        closeScope();
    }

    @Override
    protected void init() {
        if (panelX == Integer.MIN_VALUE) {
            panelX = Math.max(2, this.width - panelW - 6);
            panelY = 6;
        }
        // ★ 每次打开都做一次"收回屏幕内"★
        // 拖动/缩放时是允许把面板推出屏幕外的（大 GUI scale 下有用，见 clampDuringDrag），
        // 但那可能让它正好压在别的按钮底下再也点不到。所以收起再打开就是一次救援。
        snapInsideOnOpen();

        ProbeManager mgr = LogiScopeMod.probes();
        if (page == Page.CHART && mgr.active() == null) {
            page = Page.ROOT;
        }
        if (page == Page.REPLAY && replayArchive == null) {
            page = Page.ROOT;
        }
        if (page == Page.DATA && dataSrcBlock() == null) {
            page = Page.ROOT;
        }
        if (page == Page.PICKER) {
            ProbeInstance p = mgr.find(pickerProbeId);
            if (p == null) {
                page = Page.ROOT;
            } else {
                enterPicker(p);
            }
        }
        refreshSnapshots();
        // 存档清单每次**打开面板**扫一次。★ 注释修正（第十六轮⑤）★
        // 旧注释写"只读各存档的 JSON 头，几 KB 级" —— 名实不符：readHeader 当时是整份
        // 解析（几万条样本全部建成对象）。现在它改成流式读（只取头部、samples 整段跳过），
        // 那句说法才真正成立。另外 vanilla 的 Screen#resize 只走 rebuildWidgets()、
        // **不会**重跑 init()，所以拖动窗口不会反复扫盘（原先担心的那条不成立）。
        archiveList = Archives.scan();

        if (pendingFlashKey != null) {
            say(tr(pendingFlashKey));
            pendingFlashKey = null;
        }
    }

    /** 单机也不暂停游戏 —— 否则 tick 停了，采样也就停了 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        refreshSnapshots();
        if (page == Page.DATA) {
            tickDataPage();
        }
        if (page == Page.PICKER) {
            ProbeInstance p = LogiScopeMod.probes().find(pickerProbeId);
            if (p != null && p.target().hasPersistentId()) {
                ProbeSlots.request(p.target().dimension(), p.target().pos());
                // ★ NBT 槽位型容器的检测（第十六轮）★
                // 顺手把方块 NBT 抓一次（单人 = 服务端直读，不花权限），解析出
                // "这个容器是不是把 slot 号写在 NBT 里"。是的话，选格就按 slot 走。
                DataCommand.tick();
                if (LogiScopeMod.probes().currentTick() >= nbtSlotNextRequest) {
                    // 选格期间抓得勤一点（10 tick = 半秒）：按「完成」时要的是"那一刻"的列表
                    DataCommand.request(p.target().dimension(), p.target().pos());
                    nbtSlotNextRequest = LogiScopeMod.probes().currentTick() + 10;
                }
                parseNbtSlotLayout();
                // 数据到了就把已有锚点勾回界面上（详见 nbtSeedPending 的注释）。
                // 条件里加上"用户还没勾过"—— 万一数据来得比用户的手慢，也别把人家刚点的清掉。
                if (nbtSeedPending && nbtSlotMode && !nbtSlotEntries.isEmpty()
                        && selectionCount() == 0) {
                    seedNbtSelection(p);
                    nbtSeedPending = false;
                }
            }
            syncContainerSelection();
        }
    }

    private void refreshSnapshots() {
        menuSlots = ContainerAccess.snapshot();
        worldSlots = ProbeSlots.slots();
        source = resolveSource();
        hasTarget = ProbeContext.current(Minecraft.getInstance()) != null;
    }

    /**
     * 选格页的槽位清单从哪来。
     *
     * 规则（宁可保守，也不要张冠李戴把槽位记到别的方块上）：
     *   1. 容器界面开着，而且（实例还没有坐标 / 实例的坐标就是当前这个容器）→ 用菜单槽
     *   2. 否则实例有坐标 → 用服务端读出来的方块实体清单
     *   3. 都没有 → 空
     */
    private Source resolveSource() {
        ProbeInstance p = LogiScopeMod.probes().find(pickerProbeId);
        if (p == null) {
            return Source.NONE;
        }
        ProbeTarget t = p.target();
        if (ContainerAccess.hasForeignContainerOpen()
                && (!t.hasPersistentId() || matchesOpenContainer(t))) {
            return Source.MENU;
        }
        if (t.hasPersistentId()) {
            return Source.WORLD;
        }
        return Source.NONE;
    }

    /** 当前开着的容器界面是不是"这个实例要探测的那台设备" */
    private boolean matchesOpenContainer(ProbeTarget t) {
        ContainerAnchor.Anchor a = ContainerAnchor.activeFor(ContainerAccess.currentContainerId());
        if (a == null || !t.hasPersistentId()) {
            return false;
        }
        return a.dimension().equals(t.dimension()) && a.pos().equals(t.pos());
    }

    /**
     * 进入选格页时，把实例已有的槽位回填成"已勾选"状态 ——
     * 用户点「选格」回来是想改一两格，不该面对一张空表。
     */
    /**
     * 进入选格页 —— **三个入口共用**（打开面板时落在选格页 / 根页点了一个还没选格的探针 /
     * 图表页点「选格」）。
     *
     * ★ 第十轮修的 bug：只有前两条路会回填"已选格子" ★
     * 用户实测反馈：选完格开始测量 → 退出界面 → 再打开面板 → 进选格页，
     * 显示的是"已选 0 格"。根因就是「图表页点选格」没走回填那条路。
     * 现在统一从这里进，回填的去重（{@link #seededForProbeId}）保证
     * 不会把用户正在改的选择冲掉。
     */
    private void enterPicker(ProbeInstance p) {
        pickerProbeId = p.id();
        // ★ AE 模式检测必须在这里、每次进入都判（第十六轮⑧实测 A1 失败的根因）★
        // 不能放进 seedSelectionOnce：createProbe 会**先**打上 seededForProbeId 预标记，
        // 首次进入时 seed 直接早退 —— 检测就永远不跑。
        // 判据 = 当前菜单 ∈ ME 存储家族；纯 AE 探针（无槽位 + 有 AE 目标）即使在
        // GUI 关闭的状态下回选格页，也保持 AE 模式（否则勾选状态无法显示）。
        boolean menuIsAe = AeNetworkReader.isAeStorageMenu(ContainerAccess.currentMenu());
        boolean pureAeProbe = p.target().slots().isEmpty()
                && p.dataTargets().stream().anyMatch(DataTarget::isAeNetwork);
        aeMode = menuIsAe || pureAeProbe;
        // AE 选择 = 探针现有 AE 目标（覆写语义的不变量：界面上勾的就是探针在测的）。
        // 每次进入都重建 —— 用户没 Done 就离开的编辑不保留（与槽位选择不同，但一致于覆写）。
        selectedAeIds.clear();
        for (DataTarget d : p.dataTargets()) {
            if (d.isAeNetwork()) {
                selectedAeIds.add(d.literalName());
            }
        }
        seedSelectionOnce(p);
        if (p.target().hasPersistentId()) {
            ProbeSlots.request(p.target().dimension(), p.target().pos());
        }
        // 这个探针本来就是"按 NBT slot 监测"的（有锚点、又没有菜单/坐标槽位）→ 直接按那套语义进来，
        // 并把已有锚点勾出来。**只在"纯锚点探针"上这么做**：混合探针（既有槽位又有锚点）
        // 必须由用户自己按按钮切换，否则一次「完成」会把槽位那半边的目标丢掉。
        boolean pureAnchorProbe = p.target().slots().isEmpty()
                && p.dataTargets().stream().anyMatch(DataTarget::isSlotAnchor);
        if (pureAnchorProbe) {
            nbtSlotMode = true;
            nbtSeedPending = true;
        }
        page = Page.PICKER;
    }

    private void seedSelectionOnce(ProbeInstance p) {
        if (seededForProbeId == p.id()) {
            return;
        }
        seededForProbeId = p.id();
        selMenuSlots.clear();
        selContainerSlots.clear();
        cellMode = false;
        cellDragButton = -1;
        for (ProbeTarget.SlotRef ref : p.target().slots()) {
            if (ref.menuSlotIndex() >= 0) {
                selMenuSlots.add(ref.menuSlotIndex());
            }
            selContainerSlots.add(ref.containerSlotIndex());
        }
    }

    /** MENU 模式下"容器内下标"这份派生集合要跟着菜单刷新（MEK 那种全 0 的机器只能这样维护） */
    private void syncContainerSelection() {
        if (source != Source.MENU) {
            return;
        }
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        Set<Integer> derived = new LinkedHashSet<>();
        for (Slot slot : bg.getMenu().slots) {
            if (selMenuSlots.contains(slot.index) && ContainerAccess.isSelectable(slot)) {
                derived.add(slot.getContainerSlot());
            }
        }
        selContainerSlots.clear();
        selContainerSlots.addAll(derived);
    }

    /** 打开时把面板收回屏幕内（完全可见），越界就贴边；尺寸也跟着夹一下 */
    private void snapInsideOnOpen() {
        panelW = Math.max(MIN_W, Math.min(panelW, Math.max(MIN_W, this.width - 4)));
        panelH = Math.max(MIN_H, Math.min(panelH, Math.max(MIN_H, this.height - 4)));
        panelX = Math.max(0, Math.min(panelX, Math.max(0, this.width - panelW)));
        panelY = Math.max(0, Math.min(panelY, Math.max(0, this.height - panelH)));
    }

    /**
     * 拖动中的边界：**允许把面板推出屏幕左右和下方**，但必须留一段能抓住的"拖动条"。
     * 为什么要允许推出去：GUI scale 调大时逻辑分辨率很小（可能只有 320 宽），
     * 不推出去就永远挡着容器界面。留多少：左右至少 width/8（最少 8px），下方只留标题栏。
     */
    private void clampDuringDrag() {
        int marginX = Math.max(8, this.width / 8);
        panelX = Math.max(-(panelW - marginX), Math.min(panelX, this.width - marginX));
        panelY = Math.max(0, Math.min(panelY, this.height - TITLE_H));
    }

    @Nullable
    private AbstractContainerScreen<?> backgroundContainer() {
        return parent instanceof AbstractContainerScreen<?> container ? container : null;
    }

    @Nullable
    private ProbeInstance currentProbe() {
        return LogiScopeMod.probes().active();
    }

    @Nullable
    private ProbeInstance pickerProbe() {
        return LogiScopeMod.probes().find(pickerProbeId);
    }

    // ==================================================================
    // 派生数据（每 tick 最多重算一次）
    // ==================================================================

    private void rebuildIfNeeded() {
        if (page == Page.REPLAY) {
            // 回放数据是静态的：loadReplay 时一次性建好（chartData/summary），
            // 这里不重算 —— 回放页没有"新样本"可言
            return;
        }
        ProbeInstance p = currentProbe();
        if (p == null) {
            chartData = ChartData.EMPTY;
            summary = Analyzer.Summary.EMPTY;
            return;
        }
        long v = p.store().version();
        if (v == builtVersion && p.id() == builtProbeId) {
            return;
        }
        builtVersion = v;
        builtProbeId = p.id();
        List<Sample> samples = p.store().toList();
        chartData = ChartData.build(v, samples, LogiScopeConfig.maxItemKinds());
        summary = Analyzer.summarize(samples);
        pruneHidden();
        refreshSelectionStats();
    }

    /**
     * 丢掉"已经不存在的数据线"的隐藏记录。
     * 不做的话，某个物品消失又出现时会莫名其妙地保持隐藏，玩家找不到原因。
     */
    private void pruneHidden() {
        for (String id : List.copyOf(hiddenItems)) {
            if (chartData.byItem(id) == null) {
                hiddenItems.remove(id);
            }
        }
        for (String id : List.copyOf(ioShown)) {
            if (chartData.byItem(id) == null) {
                ioShown.remove(id);
            }
        }
        // 弹出菜单指着一个已经消失的物品 → 收起
        if (legendPopup != null && chartData.byItem(legendPopup) == null) {
            legendPopup = null;
        }
    }

    private void refreshSelectionStats() {
        selectionStats = selection == null ? List.of() : chartData.ranges(selection.lo(), selection.hi());
    }

    // ==================================================================
    // 布局：渲染与命中判定共用这一份
    // ==================================================================

    private record Item(String id, String label, boolean enabled) {
    }

    private record Chip(String id, String label, int x, int y, int w, int h, boolean enabled, int accent) {

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean hit(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /**
     * 图例弹出菜单的一行。{@code kind}：0 = 数量（存量线）, 1 = IO（净流量线）,
     * 2 = 分析（回放页的"选中这条曲线"扩展点，见 {@link #replaySelectedItem}）。
     */
    private record PopupRow(int kind, String label, boolean on) {
        static final int QUANTITY = 0;
        static final int IO = 1;
        static final int ANALYZE = 2;
    }

    /**
     * 一帧布局。{@code archTop/archRows} 只有根页非 0：根页下半 = 存档列表（G5）。
     */
    private record Layout(
            int x, int y, int w, int h,
            int infoTop, int infoRows,
            int listTop, int listRows,
            int archTop, int archRows,
            int chartTop, int chartH,
            List<Chip> toolbar, List<Chip> legend, List<Chip> buttons) {

        boolean hasChart() {
            return chartH >= MIN_CHART_H;
        }
    }

    private Layout layout() {
        int cw = Math.max(40, panelW - 2 * PAD);
        int left = panelX + PAD;

        // ---- 底部按钮（贴底）----
        List<List<Item>> btnRows = wrap(buttonItems(), cw, 44, 12);
        int buttonsTop = panelY + panelH - PAD - blockHeight(btnRows);
        List<Chip> buttons = place(btnRows, left, buttonsTop, 44, 12);

        // ---- 图例（方块按钮之上，图表页 / 回放页有）----
        List<Chip> legend = List.of();
        int legendH = 0;
        if ((page == Page.CHART || page == Page.REPLAY) && !chartData.series().isEmpty()) {
            List<List<Item>> rows = capRows(wrap(legendItems(), cw, 0, 20), MAX_LEGEND_ROWS);
            legendH = blockHeight(rows);
            legend = place(rows, left, buttonsTop - legendH - 3, 0, 20);
        }
        int contentBottom = buttonsTop - (legendH > 0 ? legendH + 3 : 0) - 2;

        int infoTop = panelY + TITLE_H + 2;
        int infoRows = infoRows();

        // ---- 图表页 / 回放页：工具栏 + 波形 ----
        List<Chip> toolbar = List.of();
        int chartTop = 0;
        int chartH = 0;
        if (page == Page.CHART || page == Page.REPLAY) {
            List<List<Item>> tbRows = wrap(toolbarItems(), cw, 22, 10);
            int toolbarH = blockHeight(tbRows);
            toolbar = place(tbRows, left, infoTop + infoRows * ROW_H + 1, 22, 10);
            chartTop = infoTop + infoRows * ROW_H + 1 + toolbarH + 2;
            chartH = Math.max(0, contentBottom - chartTop);
        }

        // ---- 根页 / 选格页：列表 ----
        int listTop = infoTop + infoRows * ROW_H + 4;
        int listRows = Math.max(1, (contentBottom - listTop) / ROW_H - 1);

        // ---- 根页下半：存档列表（标题行 + N 行，N ≈ 总行数的 1/3）----
        int archTop = 0;
        int archRows = 0;
        if (page == Page.ROOT) {
            archRows = Math.max(1, listRows / 3);
            archRows = Math.min(archRows, Math.max(0, (contentBottom - listTop) / ROW_H - 1));
            // 探针列表让出下半（-1 是"存档"标题行）；再小也要给探针留 1 行
            listRows = Math.max(1, listRows - archRows - 1);
            archTop = listTop + listRows * ROW_H + ROW_H;
        }

        return new Layout(panelX, panelY, panelW, panelH,
                infoTop, infoRows, listTop, listRows, archTop, archRows, chartTop, chartH,
                toolbar, legend, buttons);
    }

    private int infoRows() {
        return page == Page.CHART || page == Page.REPLAY ? 3 : 2;
    }

    // ------------------------------------------------------------------
    // DATA 页（NBT 取数探针）的几何
    // ------------------------------------------------------------------

    /** DATA 页三段式：NBT 原文 / 值列表 / 追踪目标列表（第十一轮④②） */
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
        // 目标区：1 行表头 + N 行目标。N 随窗口高度增减（默认面板正好 3 行），
        // 超出的行靠滚动（目标可以攒很多，固定两行不够用）
        int below = Math.max(ROW_H * 4, editTop - listTop - 2);
        int tgtRows = Math.max(1, Math.min(8, below / 3 / ROW_H));
        int listRows = Math.max(1, (below - (1 + tgtRows) * ROW_H) / ROW_H - 1);
        int tgtTop = listTop + (listRows + 1) * ROW_H;
        int cellW = Math.max(40, (L.w() - 3 * PAD) / 2);
        return new DataRects(contentTop, textH, listTop, listRows, tgtTop, tgtRows, editTop, cellW);
    }

    private List<Item> buttonItems() {
        List<Item> out = new ArrayList<>();
        switch (page) {
            case ROOT -> {
                ProbeManager mgr = LogiScopeMod.probes();
                out.add(new Item("create", tr("logiscope.btn.create_probe"),
                        !mgr.isFull() && hasTarget));
                // 第十六轮⑦修订：与 new probe / hide 同一行（原先浮在存档列表下，
                // 实测被 new probe 挡住）
                out.add(new Item("open_folder", tr("logiscope.btn.open_folder"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            case PICKER -> {
                boolean menu = source == Source.MENU;
                // ★ NBT 追踪是**显式开关**（第十六轮③）★
                // 原因（用户原话）：错误地自动启用 NBT 追踪，会让"机械手"这类
                // 形状相似但没有真正 slot 语义的设备**没法按常规路径追踪**。
                // 所以默认关；只有检测到"数据里带 slot 号的物品列表"时才可点。
                ProbeInstance pick = pickerProbe();
                out.add(new Item("nbt_mode",
                        nbtSlotMode ? tr("logiscope.btn.nbt_mode_on") : tr("logiscope.btn.nbt_mode"),
                        pick != null && pick.target().hasPersistentId() && nbtSlotDetected));
                out.add(new Item("cell_mode",
                        cellMode ? tr("logiscope.btn.cell_mode_on") : tr("logiscope.btn.cell_mode"),
                        menu && !aeMode));
                out.add(new Item("clear_cells", tr("logiscope.btn.clear_cells"), selectionCount() > 0));
                // （第十六轮⑦：probe data 挪到 done 左侧）
                ProbeInstance pp = pickerProbe();
                out.add(new Item("probe_data", tr("logiscope.btn.probe_data"),
                        pp != null && pp.target().hasPersistentId()));
                ProbeInstance doneProbe = pickerProbe();
                out.add(new Item("done", tr("logiscope.btn.done"),
                        selectionCount() > 0 || (doneProbe != null && doneProbe.isDataProbe())));
                out.add(new Item("to_root", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            case CHART -> {
                ProbeInstance p = currentProbe();
                boolean running = p != null && (p.state() == ProbeInstance.State.RECORDING
                        || p.state() == ProbeInstance.State.FOLLOWING);
                // 第十六轮⑦按用户指定顺序：pause → resume → clear → stop & archive
                //   → keep tracking… → back to slot（淡蓝）→ main screen（淡绿）
                out.add(new Item("pause", tr("logiscope.btn.pause"), running));
                out.add(new Item("resume", tr("logiscope.btn.resume"),
                        p != null && p.state() == ProbeInstance.State.PAUSED));
                out.add(new Item("clear", tr("logiscope.btn.clear"),
                        p != null && p.store().size() > 0));
                // G5：导出当前记录 → 存档并停止 → 直接进入回放页
                out.add(new Item("archive", tr("logiscope.btn.archive"),
                        p != null && p.store().size() > 0));
                out.add(new Item("follow", followLabel(), true));
                out.add(new Item("pick", tr("logiscope.btn.pick_cells"), true));
                out.add(new Item("list", tr("logiscope.btn.to_list"), true));
            }
            case REPLAY -> {
                out.add(new Item("rename", tr("logiscope.btn.rename"), replayArchive != null));
                out.add(new Item("replay_back", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            case DATA -> {
                ProbeInstance dsrc = dataSrcProbe();
                out.add(new Item("data_refresh", tr("logiscope.btn.data_refresh"), true));
                // 确认添加：把当前 值/路径 + 名称 变成一个追踪目标（可以攒多个）
                out.add(new Item("data_confirm", tr("logiscope.btn.data_confirm"),
                        dsrc != null && !dataPath.isBlank()));
                out.add(new Item("data_back", tr("logiscope.btn.to_picker"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
        }
        return out;
    }

    /** 图表工具栏。分两行放不下时会自动折行（见 wrap） */
    private List<Item> toolbarItems() {
        List<Item> out = new ArrayList<>();
        if (page == Page.REPLAY) {
            // 回放没有"实时滚动"可言：数据是死的
            out.add(new Item("fit", tr("logiscope.tool.fit"), chartData.hasData()));
            out.add(new Item("zoom_out", "-", true));
            out.add(new Item("zoom_in", "+", true));
            out.add(new Item("clear_sel", tr("logiscope.tool.clear_sel"), selection != null));
            // 回放计算（第十一轮②）：对象开关 + 运算选择。没选中曲线时置灰
            boolean calcReady = replaySelectedItem != null;
            out.add(new Item("calc_obj", tr("logiscope.calc.obj") + " "
                    + (analyzeIo ? tr("logiscope.calc.io") : tr("logiscope.calc.quantity")), calcReady));
            out.add(new Item("calc_op", tr("logiscope.calc.op") + " " + tr(opKey(analyzeOp)), calcReady));
            return out;
        }
        out.add(new Item("live", live ? tr("logiscope.tool.live") : tr("logiscope.tool.frozen"), true));
        out.add(new Item("zoom_out", "-", true));
        out.add(new Item("zoom_in", "+", true));
        out.add(new Item("fit", tr("logiscope.tool.fit"), chartData.hasData()));
        out.add(new Item("clear_sel", tr("logiscope.tool.clear_sel"), selection != null));
        return out;
    }

    /**
     * 图例条目：**一个物品一格**（第十轮⑥起）。
     *
     * 点它弹出「数量 / IO」小菜单（见 {@link #legendPopup}），两级开关收进弹窗里；
     * (empty) 只有「数量」一项 —— 空格子没有进出可言，IO 对它没有意义。
     */
    private List<Item> legendItems() {
        List<Item> out = new ArrayList<>();
        for (Series s : chartData.series()) {
            out.add(new Item(SERIES_PREFIX + s.itemId(),
                    ChartRenderer.label(chartData.indexOf(s.itemId()), s.itemId()), true));
        }
        return out;
    }

    private String followLabel() {
        return LogiScopeMod.probes().followAfterClose()
                ? tr("logiscope.btn.follow_on")
                : tr("logiscope.btn.follow_off");
    }

    // ------------------------------------------------------------------
    // 图例弹出菜单（数量 / IO）
    // ------------------------------------------------------------------

    /**
     * 弹出菜单的行：一个物品固定有「数量」；(empty) 没有「IO」—— 空格子没有进出可言。
     * 回放页多一项「分析」（G5 的可扩展点：选中曲线，后续数学运算挂在它上面）。
     */
    private List<PopupRow> popupRows(String itemId) {
        List<PopupRow> rows = new ArrayList<>();
        rows.add(new PopupRow(PopupRow.QUANTITY, tr("logiscope.legend.quantity"),
                !hiddenItems.contains(itemId)));
        if (!ChartData.EMPTY_ITEM.equals(itemId)) {
            rows.add(new PopupRow(PopupRow.IO, tr("logiscope.legend.io"), ioShown.contains(itemId)));
        }
        if (page == Page.REPLAY) {
            rows.add(new PopupRow(PopupRow.ANALYZE, tr("logiscope.legend.analyze"),
                    itemId.equals(replaySelectedItem)));
        }
        return rows;
    }

    /**
     * 弹出菜单的矩形（渲染与命中判定**共用这一份几何**，不会错位）。
     * 从所属图例块的下方弹出；底部放不下（贴近面板下缘）就往上弹；
     * 右缘夹回面板内。图例块找不到（被 `+N` 收起 / 物品刚消失）返回 null。
     */
    @Nullable
    private int[] popupRect(Layout L) {
        if (legendPopup == null) {
            return null;
        }
        for (Chip c : L.legend()) {
            if (!c.id().equals(SERIES_PREFIX + legendPopup)) {
                continue;
            }
            List<PopupRow> rows = popupRows(legendPopup);
            int w = 0;
            for (PopupRow r : rows) {
                w = Math.max(w, this.font.width(r.label()) + 30);
            }
            int h = rows.size() * POPUP_ROW_H + 4;
            int x = Math.min(c.x(), L.x() + L.w() - w - 4);
            int y = c.y() + c.h() + 1;
            if (y + h > L.y() + L.h()) {
                y = c.y() - h - 1;
            }
            return new int[]{x, y, w, h};
        }
        return null;
    }

    // ---- 折行与摆放（按钮、工具栏、图例共用）----

    private List<List<Item>> wrap(List<Item> items, int avail, int minW, int padW) {
        List<List<Item>> rows = new ArrayList<>();
        List<Item> row = new ArrayList<>();
        int used = 0;
        for (Item it : items) {
            int w = Math.max(minW, this.font.width(it.label()) + padW);
            if (!row.isEmpty() && used + CHIP_GAP + w > avail) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : CHIP_GAP) + w;
            row.add(it);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private List<Chip> place(List<List<Item>> rows, int left, int top, int minW, int padW) {
        List<Chip> out = new ArrayList<>();
        int y = top;
        for (List<Item> row : rows) {
            int x = left;
            for (Item it : row) {
                int w = Math.max(minW, this.font.width(it.label()) + padW);
                out.add(new Chip(it.id(), it.label(), x, y, w, CHIP_H, it.enabled(), accentFor(it.id())));
                x += w + CHIP_GAP;
            }
            y += CHIP_H + 2;
        }
        return out;
    }

    private static int blockHeight(List<List<Item>> rows) {
        return rows.isEmpty() ? 0 : rows.size() * CHIP_H + (rows.size() - 1) * 2;
    }

    private int accentFor(String id) {
        if ("live".equals(id)) {
            return live ? 0xFF5DCAA5 : 0xFFEF9F27;
        }
        if ("cell_mode".equals(id)) {
            return cellMode ? 0xFF3FE0A0 : 0;
        }
        if ("nbt_mode".equals(id)) {
            // 第十六轮⑦：NBT tracking ON 时按钮文字转黄色
            return nbtSlotMode ? 0xFFFFD24A : 0;
        }
        if ("to_root".equals(id) || "list".equals(id) || "replay_back".equals(id)) {
            return COLOR_MAINSCREEN;   // 「主界面」淡绿
        }
        if ("pick".equals(id)) {
            return COLOR_BACKSLOT;     // 「回选格」淡蓝
        }
        if ("done".equals(id)) {
            return 0xFFFFD24A;         // 「Done」黄色（第十六轮⑦续，与 NBT ON 同一黄）
        }
        if ("archive".equals(id)) {
            return COLOR_CALC_GLOW;    // 「Stop & archive」淡紫（与计算页光效同色系）
        }
        return 0;
    }

    /** 图例行数超限时，把最后一行末尾换成 "+N" */
    private List<List<Item>> capRows(List<List<Item>> rows, int max) {
        if (rows.size() <= max) {
            return rows;
        }
        int total = 0;
        for (List<Item> r : rows) {
            total += r.size();
        }
        List<List<Item>> out = new ArrayList<>();
        int kept = 0;
        for (int i = 0; i < max - 1; i++) {
            out.add(rows.get(i));
            kept += rows.get(i).size();
        }
        List<Item> last = new ArrayList<>(rows.get(max - 1));
        if (!last.isEmpty()) {
            last.remove(last.size() - 1);
        }
        int shown = kept + last.size();
        last.add(new Item("legend_more", "+" + Math.max(0, total - shown), false));
        out.add(last);
        return out;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        rebuildIfNeeded();
        refreshAnalyze();
        // 悬浮详情每帧重建（鼠标动一下就可能换目标）
        tipKey = 0;
        tipTitle = "";
        tipLines = List.of();
        Layout L = layout();
        int x = L.x();
        int y = L.y();

        // 先补画背景容器界面的 hover 高亮（原理见方法注释），并记下命中的槽位
        renderBackgroundHover(g, mouseX, mouseY, L);

        // ★ 选格覆盖层必须画在面板**之前** ★
        // 容器格子有可能落在面板底下（面板是半透明浮层，位置可以拖），
        // 反过来的话选中框会画到面板上面去。
        renderCellOverlay(g, mouseX, mouseY);

        // 只有面板本身，不再整屏压黑 —— 被压住的容器界面要能看见
        g.fill(x + 2, y + 3, x + L.w() + 3, y + L.h() + 4, 0x66000000);
        g.fill(x - 1, y - 1, x + L.w() + 1, y + L.h() + 1, 0xFF5A5A68);
        g.fill(x, y, x + L.w(), y + L.h(), 0xF2101017);

        // ★ 光效必须画在标题栏背景**之后**：renderTitle 会整条 fill 标题区，
        //   先画就被完全盖住（实测踩过）
        renderTitle(g, L, mouseX, mouseY);
        renderTopGlow(g, L);
        renderInfo(g, L);

        switch (page) {
            case ROOT -> renderRoot(g, L, mouseX, mouseY);
            case PICKER -> renderSlotPicker(g, L, mouseX, mouseY);
            case CHART, REPLAY -> {
                if (L.hasChart()) {
                    chart.render(g, this.font, chartData,
                            x + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), chartView(L, mouseX, mouseY));
                    renderAnalyzeBox(g, L);
                }
                renderToolbar(g, L.toolbar(), mouseX, mouseY);
                renderLegend(g, L.legend(), mouseX, mouseY);
            }
            case DATA -> renderDataPage(g, L, mouseX, mouseY);
        }

        renderButtons(g, L.buttons(), mouseX, mouseY);
        // 弹出菜单压在按钮之上（它经常盖住底部按钮区）
        renderLegendPopup(g, L, mouseX, mouseY);
        renderOpPopup(g, L, mouseX, mouseY);
        renderRenameBox(g, L);
        renderResizeGrip(g, L, mouseX, mouseY);
        // 悬浮详情最后画：它永远在最上层，且位置跟着鼠标
        updateHoverTips(L, mouseX, mouseY);
        renderTip(g, L, mouseX, mouseY);
        renderFlash(g, y);

        // 最后两样必须压在最上层，和 vanilla 的顺序一致：
        // 手持物品永远跟着光标可见，悬浮提示在它下面。
        renderCarriedItem(g, mouseX, mouseY);
        renderBackgroundTooltip(g, mouseX, mouseY);
    }

    /** 组装这一帧要画的东西：可见窗口 + 隐藏集合 + 框选状态 + 波形标记 */
    private ChartRenderer.View chartView(Layout L, int mouseX, int mouseY) {
        long[] win = window();
        long start = win[0];
        long end = win[1];

        ChartRenderer.Selection sel = selection;
        if (selecting) {
            sel = draftSelection(L, start, end);
        }
        List<Long> markers = page == Page.REPLAY
                ? replayMarkers
                : currentProbe() != null ? currentProbe().markers().toList() : List.of();
        // ★ 第十二轮④：选区基础信息只列**启用的线**（与十字线读数的 readable 同一规则）：
        // 5 条线里 3 条 quantity/IO 全关，选区统计就不写这 3 条——写了只会把小框挤爆，
        // 还让人误以为"这条线在图上"。图例开关随时可变，所以过滤放在每帧的 View 组装处。
        return new ChartRenderer.View(start, end, hiddenItems, ioShown, sel, selecting,
                visibleSelectionStats(), markers, mouseX, mouseY);
    }

    /**
     * 选区统计的可见过滤：只保留**启用的线**（第十二轮④）。
     * 规则与 {@code ChartRenderer#readable} 同源：存量线或 IO 线至少一个开着；
     * (empty) 没有 IO，只看存量。放在 View 组装处而不是统计缓存里，
     * 是因为图例开关随时可变，而统计只在框选完成时重算。
     */
    private List<ChartData.SeriesRange> visibleSelectionStats() {
        if (selectionStats.isEmpty()) {
            return selectionStats;
        }
        List<ChartData.SeriesRange> out = new ArrayList<>();
        for (ChartData.SeriesRange r : selectionStats) {
            boolean readable = ChartData.EMPTY_ITEM.equals(r.itemId())
                    ? !hiddenItems.contains(r.itemId())
                    : !hiddenItems.contains(r.itemId()) || ioShown.contains(r.itemId());
            if (readable) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 当前该显示哪一段 tick 区间。
     *
     * 三条规则叠在一起：
     *   1. 框选中 → 用拖动开始时锁住的那一段（否则波形跟着手跑）
     *   2. 实时   → 右端跟着最新数据走；冻结 → 右端定在 frozenEnd
     *   3. 数据还不够铺满一个窗口时，从第一点开始画
     *      —— 不这样处理的话，刚接上探头那几秒波形会挤在右边一小条里，看着像坏了
     */
    private long[] window() {
        if (selecting) {
            return new long[]{selectWindowStart, selectWindowEnd};
        }
        long end = live ? Math.max(chartData.lastTick(), chartData.firstTick()) : frozenEnd;
        long start = end - windowTicks;
        // 数据还不够铺满一个窗口时，从第一点开始画 —— 只在用户没主动缩放过时。
        // 缩放后必须忠实反映 windowTicks，否则缩放没有反馈（第七轮 P5）。
        if (!userZoomed && chartData.firstTick() > start) {
            start = chartData.firstTick();
        }
        if (end <= start) {
            end = start + 1;
        }
        return new long[]{start, end};
    }

    private void renderTitle(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int x = L.x();
        int y = L.y();
        boolean overTitle = isIn(mouseX, mouseY, x, y, L.w(), TITLE_H);
        g.fill(x, y, x + L.w(), y + TITLE_H, dragging ? 0xFF3B3B55 : (overTitle ? 0xFF2C2C3E : 0xFF22222E));
        g.fill(x, y + TITLE_H - 1, x + L.w(), y + TITLE_H, 0xFF5A5A68);

        String title = switch (page) {
            case ROOT -> "LogiScope";
            case PICKER -> "LogiScope · " + tr("logiscope.page.picker");
            case CHART -> "LogiScope · " + tr("logiscope.page.chart");
            case REPLAY -> "LogiScope · " + tr("logiscope.page.replay");
            case DATA -> "LogiScope · " + tr("logiscope.page.data");
        };
        g.drawString(this.font, title, x + 4, y + 4, 0xFFE6E6F0, false);

        if (dragging || overTitle) {
            g.drawString(this.font, tr("logiscope.hint.drag"), x + 4 + this.font.width(title) + 6, y + 4,
                    0xFF8A8AA0, false);
        }

        String status = statusText();
        g.drawString(this.font, status, x + L.w() - 4 - this.font.width(status), y + 4,
                statusColor(), false);
    }

    /** 标题栏右侧的状态徽标 */
    private String statusText() {
        ProbeManager mgr = LogiScopeMod.probes();
        return switch (page) {
            case ROOT -> "[ " + tr("logiscope.status.probes") + " " + mgr.count() + " ]";
            case PICKER -> "[ " + tr("logiscope.status.picking") + " " + selectionCount() + " ]";
            case REPLAY -> "[ " + tr("logiscope.status.replay") + " ]";
            case DATA -> "[ " + tr("logiscope.status.data") + " ]";
            case CHART -> {
                ProbeInstance p = currentProbe();
                if (p == null) {
                    yield "[ " + tr("logiscope.status.idle") + " ]";
                }
                String key = switch (p.state()) {
                    case ARMED -> "logiscope.status.armed";
                    case RECORDING -> "logiscope.status.recording";
                    case FOLLOWING -> "logiscope.status.following";
                    case PAUSED -> p.isManuallyPaused()
                            ? "logiscope.status.paused"
                            : "logiscope.status.paused_auto";
                };
                yield "[ " + tr(key) + " ]";
            }
        };
    }

    private int statusColor() {
        if (page == Page.ROOT) {
            return 0x85B7EB;
        }
        if (page == Page.PICKER) {
            return 0x3FE0A0;
        }
        if (page == Page.REPLAY) {
            return COLOR_ANALYZE;
        }
        if (page == Page.DATA) {
            return DataCommand.statusColor();
        }
        ProbeInstance p = currentProbe();
        if (p == null) {
            return 0x888780;
        }
        return switch (p.state()) {
            case RECORDING -> 0x5DCAA5;
            case FOLLOWING -> 0x6FB6E8;
            case PAUSED -> 0xEF9F27;
            case ARMED -> 0x85B7EB;
        };
    }

    // ------------------------------------------------------------------
    // 信息区
    // ------------------------------------------------------------------

    private void renderInfo(GuiGraphics g, Layout L) {
        int left = L.x() + PAD;
        int right = L.x() + L.w() - PAD;
        int y = L.infoTop();

        switch (page) {
            case ROOT -> {
                ProbeManager mgr = LogiScopeMod.probes();
                String head = tr("logiscope.info.probes") + " " + mgr.count() + " / " + LogiScopeConfig.maxProbes();
                g.drawString(this.font, head, left, y, 0xBFBFBF, false);
                y += ROW_H;
                String hint = hasTarget ? tr("logiscope.hint.create_ready") : tr("logiscope.hint.no_target");
                g.drawString(this.font, cut(hint, L.w() - 2 * PAD), left, y, 0x8A8AA0, false);
            }
            case PICKER -> {
                ProbeInstance p = pickerProbe();
                String head = p == null ? tr("logiscope.hint.no_container") : p.describe();
                g.drawString(this.font, cut(head, L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
                y += ROW_H;
                if (aeMode) {
                    // AE 网络终端（第十六轮⑧）：这一页的语义完全不同。
                    // 说明行改为**悬浮**在黄行上显示（第十六轮⑨，省一行空间）
                    String line = tr("logiscope.picker.ae_mode") + "  ·  "
                            + tr("logiscope.info.selected") + " " + selectionCount();
                    g.drawString(this.font, cut(line, L.w() - 2 * PAD), left, y, 0xFFE8C97A, false);
                    break;
                }
                if (source == Source.WORLD && worldSlots.isEmpty()) {
                    // 无容器方块实体（第十六轮⑨）：没有格子可选，但 NBT / 数据页仍可用。
                    // 相关按钮（选格/清空/可视化选格）本来就由 selectionCount/menu 驱动，已自动置灰。
                    String line = tr("logiscope.picker.no_cells");
                    if (nbtSlotMode) {
                        line += "  ·  " + tr("logiscope.btn.nbt_mode_on")
                                + " (" + nbtSlotEntries.size() + ")";
                    }
                    g.drawString(this.font, cut(line, L.w() - 2 * PAD), left, y, 0xFFE8C97A, false);
                    break;
                }
                String badge = switch (source) {
                    case MENU -> tr("logiscope.src.menu");
                    case WORLD -> tr("logiscope.src.world");
                    case NONE -> tr("logiscope.src.none");
                };
                String line = badge + " · " + tr("logiscope.info.selected") + " " + selectionCount();
                if (nbtSlotMode) {
                    // 第十六轮⑦：删掉"NBT slot-list, track by slot"那句（按钮本身已表达状态），
                    // 只在追踪开着时显示 ON + 元素数
                    line += "  ·  " + tr("logiscope.btn.nbt_mode_on")
                            + " (" + nbtSlotEntries.size() + ")";
                }
                g.drawString(this.font, cut(line, L.w() - 2 * PAD), left, y, 0x9BE1CB, false);
            }
            case CHART -> {
                ProbeInstance p = currentProbe();
                if (p == null) {
                    g.drawString(this.font, tr("logiscope.hint.no_probe"), left, y, 0xAAAAAA, false);
                    return;
                }
                g.drawString(this.font, cut(p.describe(), L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
                y += ROW_H;

                String now = "now " + currentValueText(p);
                String counts = "ticks " + summary.dataTicks() + "  no-data " + summary.noDataTicks();
                // 读数用**准确整数**，位数比短格式多，所以按剩余宽度裁一刀防止压到右边的计数
                int nowW = L.w() - 2 * PAD - this.font.width(counts) - 8;
                g.drawString(this.font, cut(now, nowW), left, y, 0x9BE1CB, false);
                g.drawString(this.font, counts, right - this.font.width(counts), y, 0x808088, false);
                y += ROW_H;

                // 第十三轮④：min/max/avg 删掉（要看统计就存档后做区间运算，那里更完整），
                // 换成"实时样本条数 / 缓冲上限" —— 这才是实时页真正想知道的事。
                String mm = tr("logiscope.info.samples") + " " + p.store().size()
                        + " / " + p.store().capacity();
                String sw = "switch " + chartData.switches() + "  series " + chartData.series().size();
                if (chartData.droppedKinds() > 0) {
                    // 超过 maxItemKinds 的种类：数据照记，只是不画线 —— 得让用户知道
                    sw += "  " + tr("logiscope.info.kinds_dropped")
                            .replace("%s", String.valueOf(chartData.droppedKinds()));
                }
                int mmW = L.w() - 2 * PAD - this.font.width(sw) - 8;
                g.drawString(this.font, cut(mm, mmW), left, y, 0xC8C8D4, false);
                g.drawString(this.font, sw, right - this.font.width(sw), y, 0x808088, false);
            }
            case DATA -> {
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
                // 回放页的信息区：存档名 / 探针来源 / 统计
                ArchiveData a = replayArchive;
                if (a != null) {
                    g.drawString(this.font, cut(replayName + "  ·  " + a.created(), L.w() - 2 * PAD),
                            left, y, 0xE0B8E0, false);
                    y += ROW_H;
                    g.drawString(this.font, cut(a.probeName() + " " + a.probeDesc(), L.w() - 2 * PAD),
                            left, y, 0xBFBFBF, false);
                    y += ROW_H;
                }
                String counts = "ticks " + summary.dataTicks() + "  no-data " + summary.noDataTicks();
                String sw = "series " + chartData.series().size();
                if (replaySelectedItem != null) {
                    sw += "  " + tr("logiscope.info.analyze")
                            .replace("%s", ChartRenderer.label(chartData.indexOf(replaySelectedItem), replaySelectedItem));
                }
                g.drawString(this.font, counts, left, y, 0x808088, false);
                g.drawString(this.font, sw, right - this.font.width(sw), y, 0x808088, false);
            }
        }
    }

    /**
     * 面板上那行 "now"。RECORDING 直接读菜单，其余从最近一条样本取。
     *
     * ★ 多格监控时这里会有**多条**（每个物品一条）★
     * 最多显示 3 条，多的收成 "+N" —— 和十字线读数的处理一致，不把一行塞爆。
     */
    private String currentValueText(ProbeInstance p) {
        List<Aggregator.Totals> totals = p.liveTotals();
        if (totals == null || totals.isEmpty()) {
            return tr("logiscope.nodata");
        }
        if (totals.size() == 1 && totals.get(0).empty()) {
            return tr("logiscope.slot.empty");
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Aggregator.Totals t : totals) {
            if (shown == 3) {
                sb.append(" +").append(totals.size() - shown);
                break;
            }
            if (shown > 0) {
                sb.append("  ·  ");
            }
            sb.append(ChartRenderer.label(-1, t.itemId())).append(" x")
                    .append(ChartRenderer.fmtExact(t.count()));
            if (ioShown.contains(t.itemId())) {
                Series s = chartData.byItem(t.itemId());
                if (s != null && s.hasFlow()) {
                    Val f = s.lastFlow();
                    String txt = ChartRenderer.fmtExact(f);
                    sb.append(" [").append(f.compareTo(Val.ZERO) > 0 ? "+" + txt : txt).append("/t]");
                }
            }
            shown++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 根页：上半 = 正在运行的探针 / 下半 = 存档（G5）
    // ------------------------------------------------------------------

    /** 根页的探针列表（上半部分）。下半部分的存档列表见 renderArchives */
    private void renderRoot(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        renderProbeList(g, L, mouseX, mouseY);
        renderArchives(g, L, mouseX, mouseY);
    }

    /** 根页下半：存档列表（读取分析数据）。默认高亮当前/最近一次的存档 */
    private void renderArchives(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int x = L.x();
        int y = L.archTop();
        List<Archives.Info> head = archiveList;
        String colDir = tr("logiscope.arch.col.dir");
        String colCnt = tr("logiscope.arch.col.count");
        int headNameW = Math.max(96, (L.w() - 16) * 44 / 100);
        int headNameX = x + 8;
        int headDirX = headNameX + headNameW + 6;
        int headCntX = x + L.w() - 8 - this.font.width(colCnt);
        // 标题行左侧 = 筛选输入框（第十八轮③）：空时显示原「存档」标题文字作占位符
        renderArchiveFilter(g, L, mouseX, mouseY);
        if (!head.isEmpty()) {
            // 列名贴在标题行右侧，和下面的三列同位置 —— "名称-文件夹-样本数"一眼对上
            g.drawString(this.font, colDir, headDirX, y, 0x6A6A78, false);
            g.drawString(this.font, colCnt, headCntX, y, 0x6A6A78, false);
        }
        if (L.archRows() <= 0) {
            return;
        }
        List<Archives.Info> list = filteredArchives();
        if (list.isEmpty()) {
            // 区分"真没有存档"与"筛掉了" —— 后者别让用户以为数据丢了
            String hint = head.isEmpty()
                    ? tr("logiscope.hint.no_archives")
                    : tr("logiscope.hint.arch_no_match");
            g.drawString(this.font, hint, x + PAD + 4, y + ROW_H, 0x8A8AA0, false);
            return;
        }
        scrollArchives = Math.max(0, Math.min(scrollArchives, Math.max(0, list.size() - L.archRows())));
        for (int i = 0; i < L.archRows(); i++) {
            int idx = scrollArchives + i;
            if (idx >= list.size()) {
                break;
            }
            Archives.Info info = list.get(idx);
            int ry = y + ROW_H + i * ROW_H;
            boolean loaded = replayArchive != null && info.dirName().equals(replayDir);
            boolean over = isIn(mouseX, mouseY, x + 4, ry - 1, L.w() - 8, ROW_H);
            if (over) {
                g.fill(x + 4, ry - 1, x + L.w() - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            if (loaded) {
                g.fill(x + 4, ry - 1, x + 6, ry + ROW_H - 1, COLOR_ANALYZE);
            }
            // 三列**固定对齐**：名称（左侧留宽，可重命名） / 文件夹 / 样本数（右对齐）
            String name = info.displayName().isBlank() ? info.dirName() : info.displayName();
            String cnt = String.valueOf(info.sampleCount());
            int nameW = Math.max(96, (L.w() - 16) * 44 / 100);
            int nameX = x + 8;
            int dirX = nameX + nameW + 6;
            int cntX = x + L.w() - 8 - this.font.width(cnt);
            g.drawString(this.font, cut(name, nameW), nameX, ry,
                    loaded ? 0xFFF0E0F8 : 0xFFC8C8D8, false);
            int dirW = Math.max(16, cntX - dirX - 8);
            if (dirW > 16) {
                g.drawString(this.font, cut(info.dirName(), dirW), dirX, ry, 0xFF9099A8, false);
            }
            g.drawString(this.font, cnt, cntX, ry, 0xFF9099A8, false);
        }
        if (list.size() > L.archRows()) {
            String more = (scrollArchives + L.archRows()) + " / " + list.size()
                    + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, more, x + PAD, y + ROW_H + L.archRows() * ROW_H, 0xFF707070, false);
        }
    }

    /**
     * 筛选后要显示的存档清单（第十八轮③）。筛选串为空 = 全量原样返回。
     * 匹配对象 = 列表里显示的**名称**（displayName，没有则退到文件夹名）+ **文件夹名**，
     * 大小写不敏感。渲染 / 点击命中 / 滚动三处共用它，保证"看到的行 = 点的行"。
     */
    private List<Archives.Info> filteredArchives() {
        String q = archFilter.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return archiveList;
        }
        List<Archives.Info> out = new ArrayList<>();
        for (Archives.Info i : archiveList) {
            String name = i.displayName().isBlank() ? i.dirName() : i.displayName();
            if (name.toLowerCase(Locale.ROOT).contains(q)
                    || i.dirName().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(i);
            }
        }
        return out;
    }

    /** 筛选框矩形 [x, y, w, h]：占标题行左侧（与名称列同宽），右侧列名位置不动 */
    private int[] archFilterRect(Layout L) {
        int nameW = Math.max(96, (L.w() - 16) * 44 / 100);
        return new int[]{L.x() + 4, L.archTop() - 1, nameW + 6, ROW_H - 1};
    }

    /** 点是否落在筛选框上（渲染与命中判定共用同一个矩形） */
    private boolean inArchFilter(Layout L, int mx, int my) {
        if (page != Page.ROOT) {
            return false;
        }
        int[] r = archFilterRect(L);
        return isIn(mx, my, r[0], r[1], r[2], r[3]);
    }

    /** 存档筛选框（第十八轮③）：空时显示占位符（原标题文字），聚焦时闪光标 */
    private void renderArchiveFilter(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int[] r = archFilterRect(L);
        int x = r[0];
        int y = r[1];
        int w = r[2];
        int h = r[3];
        boolean over = isIn(mouseX, mouseY, x, y, w, h);
        g.fill(x, y, x + w, y + h, 0xC0101018);
        ring(g, x, y, w, h, archFilterFocus ? COLOR_ANALYZE
                : over ? 0xFF6A6A7A : 0xFF3A3A46);
        boolean empty = archFilter.isEmpty();
        String text = empty ? tr("logiscope.info.archives") : archFilter;
        int tx = x + 3;
        int ty = y + 1;
        int avail = w - 6;
        String draw = this.font.width(text) <= avail ? text : cut(text, avail);
        g.drawString(this.font, draw, tx, ty, empty ? 0xFF9A8AA8 : 0xFFE0E0EC, false);
        if (archFilterFocus && (LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = tx + Math.min(this.font.width(text), avail);
            g.fill(cx, ty, cx + 1, ty + 9, 0xFFE0E0EC);
        }
    }

    /** 用系统文件管理器打开存档目录 {@code .minecraft/logiscope/}（第十六轮⑦） */
    private void openStorageFolder() {
        try {
            Files.createDirectories(Archives.rootDir());
            Util.getPlatform().openFile(Archives.rootDir().toFile());
        } catch (Exception e) {
            LogiScopeMod.LOGGER.warn("open logiscope folder failed", e);
            say(tr("logiscope.flash.open_folder_fail"));
        }
    }

    private void renderProbeList(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int x = L.x();
        List<ProbeInstance> list = LogiScopeMod.probes().all();

        if (list.isEmpty()) {
            g.drawString(this.font, tr("logiscope.hint.no_probe"), x + PAD, L.listTop(), 0xAAA6A0, false);
            return;
        }

        int listTop = L.listTop();
        int maxRows = L.listRows();
        int total = list.size();
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, total - maxRows)));

        for (int i = 0; i < maxRows; i++) {
            int idx = scrollRow + i;
            if (idx >= total) {
                break;
            }
            ProbeInstance p = list.get(idx);
            int ry = listTop + i * ROW_H;
            boolean active = p.id() == LogiScopeMod.probes().activeId();
            boolean overRow = isIn(mouseX, mouseY, x + 4, ry - 1, L.w() - 8 - DEL_W - 2, ROW_H);
            boolean overDel = isIn(mouseX, mouseY, deleteX(L), ry - 1, DEL_W, ROW_H);

            if (overRow) {
                g.fill(x + 4, ry - 1, x + L.w() - 4 - DEL_W - 2, ry + ROW_H - 1, 0x40FFFFFF);
            }
            if (active) {
                g.fill(x + 4, ry - 1, x + 4 + 2, ry + ROW_H - 1, 0xFF3FE0A0);
            }

            // 行首是**设备名**（用户要求：探针名称里前缀加方块名），然后是状态、槽位+坐标
            String name = cut(p.name(), 92);
            g.drawString(this.font, name, x + 8, ry, active ? 0xFFF0F0FF : 0xFFC8C8D8, false);
            int stX = x + 8 + this.font.width(name) + 6;
            String st = shortState(p);
            g.drawString(this.font, st, stX, ry, stateColor(p), false);
            String desc = p.describe();
            int descX = stX + this.font.width(st) + 6;
            int descW = x + L.w() - 4 - DEL_W - 4 - descX;
            if (descW > 20) {
                g.drawString(this.font, cut(desc, descW), descX, ry,
                        p.hasSlots() ? 0xFF9099A8 : 0xFF70707A, false);
            }

            // 删除按钮：停止监测 + 删掉这个实例的缓存
            int dx = deleteX(L);
            g.fill(dx, ry - 1, dx + DEL_W, ry + ROW_H - 1, overDel ? 0xFF7A3038 : 0xFF2A2A38);
            g.drawString(this.font, "×", dx + 4, ry, overDel ? 0xFFFFD0D0 : 0xFFC0A0A8, false);
        }

        if (total > maxRows) {
            String more = (scrollRow + maxRows) + " / " + total + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, more, x + PAD, listTop + maxRows * ROW_H + 1, 0xFF707070, false);
        }
    }

    private static int deleteX(Layout L) {
        return L.x() + L.w() - 4 - DEL_W;
    }

    private String shortState(ProbeInstance p) {
        return tr(switch (p.state()) {
            case ARMED -> "logiscope.short.armed";
            case RECORDING -> "logiscope.short.recording";
            case FOLLOWING -> "logiscope.short.following";
            case PAUSED -> "logiscope.short.paused";
        });
    }

    private int stateColor(ProbeInstance p) {
        return switch (p.state()) {
            case RECORDING -> 0x5DCAA5;
            case FOLLOWING -> 0x6FB6E8;
            case PAUSED -> 0xEF9F27;
            case ARMED -> 0x85B7EB;
        };
    }

    // ------------------------------------------------------------------
    // 选格页
    // ------------------------------------------------------------------

    private int selectionCount() {
        if (aeMode) {
            return selectedAeIds.size();
        }
        return source == Source.WORLD ? selContainerSlots.size() : selMenuSlots.size();
    }

    private List<ContainerAccess.SlotView> pickerList() {
        return source == Source.MENU ? menuSlots : worldSlots;
    }

    private boolean isSelected(ContainerAccess.SlotView v) {
        if (aeMode) {
            return !v.empty() && selectedAeIds.contains(v.itemId());
        }
        return source == Source.MENU ? selMenuSlots.contains(v.menuSlotIndex())
                : selContainerSlots.contains(v.containerSlotIndex());
    }

    private void renderSlotPicker(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int x = L.x();

        switch (source) {
            case NONE -> {
                g.drawString(this.font, tr("logiscope.hint.no_container"), x + PAD, L.listTop(), 0xAAA6A0, false);
                return;
            }
            case WORLD -> {
                if (worldSlots.isEmpty()) {
                    ProbeSlots.Status st = ProbeSlots.status();
                    String key = st == ProbeSlots.Status.NONE
                            ? "logiscope.hint.not_a_container"
                            : "logiscope.hint.world_reading";
                    g.drawString(this.font, tr(key), x + PAD, L.listTop(), 0xAAA6A0, false);
                    return;
                }
            }
            default -> {
            }
        }

        List<ContainerAccess.SlotView> list = pickerList();
        if (list.isEmpty()) {
            g.drawString(this.font, tr("logiscope.hint.no_container"), x + PAD, L.listTop(), 0xAAA6A0, false);
            return;
        }

        int listTop = L.listTop();
        int maxRows = L.listRows();
        int total = list.size();
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, total - maxRows)));

        for (int i = 0; i < maxRows; i++) {
            int idx = scrollRow + i;
            if (idx >= total) {
                break;
            }
            ContainerAccess.SlotView v = list.get(idx);
            int ry = listTop + i * ROW_H;
            boolean selected = isSelected(v);
            if (isIn(mouseX, mouseY, x + 4, ry - 1, L.w() - 8, ROW_H)) {
                g.fill(x + 4, ry - 1, x + L.w() - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            if (selected) {
                g.fill(x + 4, ry - 1, x + L.w() - 4, ry + ROW_H - 1, 0x303FE0A0);
            }
            g.drawString(this.font, selected ? "[x]" : "[ ]", x + 8, ry,
                    selected ? 0xFF3FE0A0 : 0xFF6A6A78, false);
            String label = "#" + v.containerSlotIndex()
                    + "  " + (v.empty() ? tr("logiscope.slot.empty") : v.shortName() + " x" + v.count());
            g.drawString(this.font, label, x + 30, ry,
                    v.empty() ? (selected ? 0xFFA8C8B8 : 0xFF707070) : 0xFFE0E0E0, false);
        }

        String footer = (scrollRow + maxRows) + " / " + total + "  " + tr("logiscope.hint.scroll");
        if (cellMode) {
            footer = footer + "  ·  " + tr("logiscope.hint.cell_mode_short");
        }
        g.drawString(this.font, footer, x + PAD, listTop + maxRows * ROW_H + 1, 0xFF707070, false);
    }

    // ------------------------------------------------------------------
    // 图例 / 工具栏 / 按钮
    // ------------------------------------------------------------------

    private void renderToolbar(GuiGraphics g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            renderChip(g, c, mouseX, mouseY, c.enabled() && c.hit(mouseX, mouseY), c.accent());
        }
    }

    /**
     * 图例：一行一个色块 + 名字，点一下弹出「数量 / IO」小菜单（见 {@link #legendPopup}）。
     *
     * ★ 图标分上下两半（第十一轮）★
     * 一个物品有两层线（存量 / IO），只给一块色板表达不出来：
     * 只开 IO 时整块都是暗的，用户会以为"这个物品关掉了"，其实 IO 线正开着。
     * 所以色板劈成上下两半：**上半 = 存量线颜色，下半 = IO 线颜色（浅一档）**，
     * 关掉的那层显示灰 —— 每一层开关的状态都直接写在图标上。
     * 字体的明暗只服从一条规则：**两层全关才变暗**（单关一层时这行图例还活着）。
     */
    private void renderLegend(GuiGraphics g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            if (!c.id().startsWith(SERIES_PREFIX)) {
                renderChip(g, c, mouseX, mouseY, false, 0);
                continue;
            }
            String itemId = c.id().substring(SERIES_PREFIX.length());
            boolean qOn = !hiddenItems.contains(itemId);
            boolean empty = ChartData.EMPTY_ITEM.equals(itemId);
            boolean ioOn = !empty && ioShown.contains(itemId);
            boolean dim = !qOn && !ioOn;
            boolean selected = page == Page.REPLAY && itemId.equals(replaySelectedItem);
            boolean hover = c.hit(mouseX, mouseY);
            int bg = dim ? 0xFF17171E : (hover ? 0xFF2A2A38 : 0xFF1C1C26);
            int border = selected ? COLOR_ANALYZE
                    : dim ? 0xFF252530 : (hover ? 0xFF8A8AC8 : 0xFF3A3A4A);
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), bg);
            g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + 1, border);
            g.fill(c.x(), c.y() + c.h() - 1, c.x() + c.w(), c.y() + c.h(), border);
            g.fill(c.x(), c.y(), c.x() + 1, c.y() + c.h(), border);
            g.fill(c.x() + c.w() - 1, c.y(), c.x() + c.w(), c.y() + c.h(), border);

            int color = ChartRenderer.colorOf(chartData.indexOf(itemId), itemId);
            int ix = c.x() + 4;
            int iy = c.y() + 2;
            if (empty) {
                // (empty) 没有 IO：保留原来的一整块
                g.fill(ix, iy + 1, ix + 6, iy + 9, qOn ? color : 0xFF3A3A44);
            } else {
                int flow = ChartRenderer.flowColor(color);
                g.fill(ix, iy + 1, ix + 6, iy + 5, qOn ? color : 0xFF3A3A44);
                g.fill(ix, iy + 5, ix + 6, iy + 9, ioOn ? flow : 0xFF3A3A44);
            }
            g.drawString(this.font, c.label(), c.x() + 13, c.y() + 2,
                    dim ? 0xFF70707A : 0xFFD8D8E4, false);
        }
    }

    /**
     * 图例弹出菜单：列在弹窗渲染**之后**调用（压在最上层，不然会被底部按钮盖住）。
     */
    private void renderLegendPopup(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int[] pr = popupRect(L);
        if (pr == null) {
            return;
        }
        String itemId = legendPopup;
        List<PopupRow> rows = popupRows(itemId);
        int accent = ChartRenderer.colorOf(chartData.indexOf(itemId), itemId);

        g.fill(pr[0], pr[1], pr[0] + pr[2], pr[1] + pr[3], 0xF2181822);
        ring(g, pr[0], pr[1], pr[2], pr[3], 0xFF3A3A4A);
        // 顶边一条物品色：标明这个菜单是哪个物品的（和图例色块同色）
        g.fill(pr[0], pr[1], pr[0] + pr[2], pr[1] + 1, accent);

        int ry = pr[1] + 2;
        for (PopupRow r : rows) {
            boolean hover = isIn(mouseX, mouseY, pr[0] + 1, ry, pr[2] - 2, POPUP_ROW_H);
            if (hover) {
                g.fill(pr[0] + 1, ry, pr[0] + pr[2] - 1, ry + POPUP_ROW_H, 0xFF2E2E3E);
            }
            // 状态色块：数量 = 线色 / IO = 浅一档 / 分析 = 品红（标记同色系），关 = 灰
            int swatch = !r.on() ? 0xFF3A3A44
                    : switch (r.kind()) {
                        case PopupRow.IO -> ChartRenderer.flowColor(accent);
                        case PopupRow.ANALYZE -> COLOR_ANALYZE;
                        default -> accent;
                    };
            g.fill(pr[0] + 6, ry + 3, pr[0] + 12, ry + 9, swatch);
            g.drawString(this.font, r.label(), pr[0] + 16, ry + 2,
                    r.on() ? 0xFFE0E0EC : 0xFF8A8A9A, false);
            ry += POPUP_ROW_H;
        }
    }

    private void renderButtons(GuiGraphics g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            // ★ accent 在渲染时求值（不取构造时存的 c.accent()）：
            //   语义色（主界面淡绿 / 回选格淡蓝 / NBT ON 黄）依赖运行状态，
            //   而按钮行只在 rebuild 时重建 —— 每帧求值才不会滞后。
            //   之前两个 bug 都在这条链上：传 0 = 颜色全丢；构造时求值 = 状态切换后滞后。
            renderChip(g, c, mouseX, mouseY, c.enabled() && c.hit(mouseX, mouseY), accentFor(c.id()));
        }
    }

    // ------------------------------------------------------------------
    // 回放计算（第十一轮②）
    // ------------------------------------------------------------------

    /**
     * 重算缓存：结果随 **框选区间 × 曲线 × 对象（数量/IO）× 运算** 四元组变化才重算，
     * 其余帧直接用缓存 —— 框选拖动时不重算（拖完才出结果），静止时零开销。
     */
    private void refreshAnalyze() {
        String item = replaySelectedItem;
        if (page != Page.REPLAY || selection == null || item == null
                || chartData.byItem(item) == null) {
            analyzeTitle = "";
            analyzeLines = List.of();
            aLo = Long.MIN_VALUE;
            return;
        }
        long lo = selection.lo();
        long hi = selection.hi();
        if (lo == aLo && hi == aHi && item.equals(aItem) && aIo == analyzeIo && aOp == analyzeOp) {
            return;
        }
        aLo = lo;
        aHi = hi;
        aItem = item;
        aIo = analyzeIo;
        aOp = analyzeOp;
        analyzeTitle = tr("logiscope.calc.title",
                ChartRenderer.label(chartData.indexOf(item), item),
                analyzeIo ? tr("logiscope.calc.io") : tr("logiscope.calc.quantity"),
                tr(opKey(analyzeOp)));
        analyzeLines = computeAnalyzeLines(chartData.byItem(item), lo, hi);
    }

    private List<String> computeAnalyzeLines(Series s, long lo, long hi) {
        List<String> out = new ArrayList<>();
        if (analyzeOp == OP_NONE) {
            return List.of();
        }
        if (!analyzeIo) {
            List<SeriesPoint> pts = inRange(s.points(), lo, hi);
            if (pts.isEmpty()) {
                out.add(tr("logiscope.calc.no_data"));
                return out;
            }
            switch (analyzeOp) {
                case OP_EXTREMES -> {
                    CurveMath.Extremes e = CurveMath.extremes(pts);
                    out.add("min " + ChartRenderer.fmtExact(e.min()) + " @t" + e.minTick());
                    out.add("max " + ChartRenderer.fmtExact(e.max()) + " @t" + e.maxTick());
                }
                case OP_STATS -> {
                    CurveMath.Stats st = CurveMath.stats(pts);
                    out.add("mean " + fmtNum(st.mean()));
                    out.add("med " + fmtNum(st.med()));
                    out.add("var " + fmtNum(st.var()));
                }
                case OP_TOTAL -> {
                    CurveMath.TotalChange t = CurveMath.totalChange(pts);
                    out.add(tr("logiscope.calc.rise", signedNum(t.rise())));
                    out.add(tr("logiscope.calc.fall", signedNum(t.fall())));
                    out.add(tr("logiscope.calc.net", signedNum(t.net())));
                    if (t.gaps() > 0) {
                        out.add(tr("logiscope.calc.gaps", t.gaps()));
                    }
                }
                case OP_EVENTS -> addEventLines(out, CurveMath.changeEvents(pts),
                        "logiscope.calc.events");
                case OP_RISE_EVENTS -> addEventLines(out, CurveMath.changeRiseEvents(pts),
                        "logiscope.calc.rise_events");
                case OP_FALL_EVENTS -> addEventLines(out, CurveMath.changeFallEvents(pts),
                        "logiscope.calc.fall_events");
                default -> {
                }
            }
            return out;
        }

        // ---- IO：流量线（值本身就是变化量）----
        if (!s.hasFlow()) {
            out.add(tr("logiscope.calc.no_flow"));
            return out;
        }
        // 段内口径：只统计**严格在段内**发生的变化 —— 流量在"段首采样那个 tick"上
        // 是从区间外带进来的变化，算进去会和 Δ 对不上。段首取区间内第一个存量样本的 tick。
        List<SeriesPoint> qty = inRange(s.points(), lo, hi);
        long bound = qty.isEmpty() ? lo - 1 : qty.get(0).tick();
        List<SeriesPoint> flow = new ArrayList<>();
        for (SeriesPoint p : s.flow()) {
            if (p.tick() <= bound) {
                continue;
            }
            if (p.tick() > hi) {
                break;
            }
            flow.add(p);
        }
        if (flow.isEmpty()) {
            out.add(tr("logiscope.calc.no_data"));
            return out;
        }
        switch (analyzeOp) {
            case OP_EXTREMES -> {
                CurveMath.Extremes e = CurveMath.extremes(flow);
                out.add("min " + signedNum(e.min()) + " @t" + e.minTick());
                out.add("max " + signedNum(e.max()) + " @t" + e.maxTick());
            }
            case OP_STATS -> {
                CurveMath.Stats st = CurveMath.stats(flow);
                out.add("mean " + signedNum(st.mean()));
                out.add("med " + signedNum(st.med()));
                out.add("var " + fmtNum(st.var()));
            }
            case OP_TOTAL -> {
                CurveMath.TotalChange t = CurveMath.flowTotal(flow);
                out.add(tr("logiscope.calc.rise", signedNum(t.rise())));
                out.add(tr("logiscope.calc.fall", signedNum(t.fall())));
                out.add(tr("logiscope.calc.net", signedNum(t.net())));
                if (t.gaps() > 0) {
                    out.add(tr("logiscope.calc.gaps", t.gaps()));
                }
            }
            case OP_EVENTS -> addEventLines(out, CurveMath.flowEvents(flow),
                    "logiscope.calc.events");
            case OP_RISE_EVENTS -> addEventLines(out, CurveMath.flowRiseEvents(flow),
                    "logiscope.calc.rise_events");
            case OP_FALL_EVENTS -> addEventLines(out, CurveMath.flowFallEvents(flow),
                    "logiscope.calc.fall_events");
            default -> {
            }
        }
        return out;
    }

    /** 变更区间 / 上升事件 / 下降事件共用的三行输出（三种运算只有首行的文案键不同） */
    private void addEventLines(List<String> out, CurveMath.Events ev, String countKey) {
        out.add(tr(countKey, ev.count()));
        if (ev.count() > 0) {
            out.add(tr("logiscope.calc.delta", signedNum(ev.dMean()), fmtNum(ev.dVar())));
            out.add(tr("logiscope.calc.period", fmtNum(ev.pMean()), fmtNum(ev.pVar())));
        }
    }

    /** 裁出 [lo, hi] 内的采样点（点按 tick 升序，可提前 break） */
    private static List<SeriesPoint> inRange(List<SeriesPoint> pts, long lo, long hi) {
        List<SeriesPoint> out = new ArrayList<>();
        for (SeriesPoint p : pts) {
            if (p.tick() < lo) {
                continue;
            }
            if (p.tick() > hi) {
                break;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * 计算结果框：画在绘图区**右上角**（框选统计在左上角）——
     * 两块并存、互不覆盖，"计算结果不影响区间选中的基础信息"就是这么落地的。
     * 纯展示、无命中，不会挡框选。
     */
    private void renderAnalyzeBox(GuiGraphics g, Layout L) {
        if (page != Page.REPLAY || analyzeLines.isEmpty()) {
            return;
        }
        int[] pr = ChartRenderer.plotRect(L.x() + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH());
        int w = this.font.width(analyzeTitle) + 8;
        for (String line : analyzeLines) {
            w = Math.max(w, this.font.width(line) + 8);
        }
        int h = (analyzeLines.size() + 1) * 10 + 4;
        int bx = pr[0] + pr[2] - w - 4;
        int by = pr[1] + 4;
        g.fill(bx - 2, by - 2, bx + w + 2, by + h, 0xE0181822);
        g.fill(bx - 2, by - 2, bx + w + 2, by - 1, COLOR_ANALYZE);
        g.drawString(this.font, analyzeTitle, bx, by, COLOR_ANALYZE, false);
        int ly = by + 11;
        for (String line : analyzeLines) {
            g.drawString(this.font, line, bx, ly, 0xFFE0E0EC, false);
            ly += 10;
        }
    }

    /** 数字读数：整数原样，小数保留 3 位（与框选统计的"读数精确"一个原则，mean/var 允许小数） */
    private static String fmtNum(double v) {
        if (Double.isNaN(v)) {
            return "-";
        }
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** 带正负号的数字读数（正值显式加 +，负值自带 -，0 原样） */
    private static String signedNum(double v) {
        return v > 0 ? "+" + fmtNum(v) : fmtNum(v);
    }

    /** 自适应数值的读数：整数精确打印，小数走短格式 */
    private static String fmtNum(Val v) {
        return v.isIntegral() ? Long.toString(v.i()) : fmtNum(v.d());
    }

    private static String signedNum(Val v) {
        return v.compareTo(Val.ZERO) > 0 ? "+" + fmtNum(v) : fmtNum(v);
    }

    private void renderChip(GuiGraphics g, Chip c, int mouseX, int mouseY, boolean hover, int accent) {
        int bg = !c.enabled() ? 0xFF1A1A22 : (hover ? 0xFF41415A : 0xFF2A2A38);
        int border = !c.enabled() ? 0xFF2A2A34 : (hover ? 0xFF8A8AC8 : 0xFF4A4A5E);
        int fg = !c.enabled() ? 0xFF6A6A78 : (accent != 0 ? accent : (hover ? 0xFFF0F0FF : 0xFFC8C8D8));
        g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), bg);
        g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + 1, border);
        g.fill(c.x(), c.y() + c.h() - 1, c.x() + c.w(), c.y() + c.h(), border);
        g.fill(c.x(), c.y(), c.x() + 1, c.y() + c.h(), border);
        g.fill(c.x() + c.w() - 1, c.y(), c.x() + c.w(), c.y() + c.h(), border);
        int tw = this.font.width(c.label());
        g.drawString(this.font, c.label(), c.x() + (c.w() - tw) / 2, c.y() + (c.h() - 8) / 2 + 1, fg, false);
    }

    /** 右下角的缩放把手。画三道嵌套的直角线，比画"◢"可靠（不依赖字体有没有那个字形） */
    private void renderResizeGrip(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        int gx = L.x() + L.w() - RESIZE_GRIP;
        int gy = L.y() + L.h() - RESIZE_GRIP;
        boolean hover = isIn(mouseX, mouseY, gx, gy, RESIZE_GRIP, RESIZE_GRIP);
        int c = hover ? 0xFFC8C8E0 : 0xFF5A5A6A;
        int bx = L.x() + L.w() - 1;
        int by = L.y() + L.h() - 1;
        for (int i = 0; i < 3; i++) {
            int d = 3 + i * 3;
            g.fill(bx - d, by - 1, bx, by, c);
            g.fill(bx - 1, by - d, bx, by, c);
        }
        if (hover) {
            String hint = tr("logiscope.hint.resize");
            g.drawString(this.font, hint,
                    L.x() + L.w() - RESIZE_GRIP - 4 - this.font.width(hint),
                    L.y() + L.h() - RESIZE_GRIP + 3, 0xFF8A8AA0, false);
        }
    }

    /**
     * 面板上/下方的一句临时提示（"已清空"这类操作反馈）。
     *
     * ★ 排版规则（第十六轮⑥，用户指定）★
     * 内容宽度**定死为面板宽度**、自动换行、**最后一行贴着面板顶** ——
     * 旧实现是"单行 + 按文字宽度居中"，提示一长（本轮的"超出可绘制范围"就是长句）
     * 就横着冒出面板。现在整块宽度 = panelW、逐字符折行（中文没有空格，按词折会失败），
     * 块的最后一行贴在面板顶上；顶上放不下（面板贴着屏幕顶）就把整块挪到面板下方，
     * 首行贴着面板底 —— 与旧逻辑的"挪下去"一致，只是从一行变成了整块。
     * 折行结果按文本缓存：提示每帧都要画，逐字符算宽度不能每帧来一遍。
     */
    private void renderFlash(GuiGraphics g, int panelTop) {
        if (flash.isEmpty() || System.currentTimeMillis() > flashUntil) {
            return;
        }
        if (!flash.equals(flashWrappedFrom)) {
            flashLines = wrapText(flash, panelW - 8);
            flashWrappedFrom = flash;
        }
        int lineH = this.font.lineHeight;
        int blockH = flashLines.size() * lineH + 4;
        boolean above = panelTop - blockH >= 0;
        int x = panelX;
        int y0 = above ? panelTop - blockH : panelTop + panelH + 2;
        g.fill(x, y0, x + panelW, y0 + blockH, 0xE0202030);
        for (int i = 0; i < flashLines.size(); i++) {
            g.drawString(this.font, flashLines.get(i), x + 4, y0 + 2 + i * lineH, 0xFFE6E6F0, false);
        }
    }

    /**
     * 面板顶边的 2px 光效条（第十六轮⑦，用户指定）：
     * 根页淡绿 / 选格页（含数据页）淡蓝 / 图表页淡黄 / 计算页淡紫。
     * 实现 = 静态底条 + 一个从左到右移动的亮斑（15 段梯形 alpha），每帧约 16 次 fill ——
     * 与面板背景同量级，渲染压力可忽略（用户口径：压力大才降级成呼吸光效，实测不需要）。
     */
    private void renderTopGlow(GuiGraphics g, Layout L) {
        int base = switch (page) {
            case ROOT -> COLOR_MAINSCREEN;
            case PICKER, DATA -> COLOR_BACKSLOT;
            case CHART -> COLOR_CHART_GLOW;
            case REPLAY -> COLOR_CALC_GLOW;
        };
        int rgb = base & 0xFFFFFF;
        g.fill(L.x(), L.y(), L.x() + L.w(), L.y() + 2, rgb | 0x30000000);
        long period = 2800L;
        int span = L.w() + 120;
        int center = (int) ((System.currentTimeMillis() % period) * span / period) - 60;
        int half = 45;
        for (int dx = -42; dx < 42; dx += 6) {
            int a = (int) (0xB4 * (1.0 - Math.abs(dx) / (double) half));
            if (a <= 0) {
                continue;
            }
            int x0 = Math.max(L.x(), L.x() + center + dx);
            int x1 = Math.min(L.x() + L.w(), L.x() + center + dx + 6);
            if (x1 > x0) {
                g.fill(x0, L.y(), x1, L.y() + 2, rgb | (a << 24));
            }
        }
    }

    /**
     * 按像素宽度折行：优先在最近的空格处断（英文），整段没有空格（中文）就按字符断。
     * 提示文本很短，O(n²) 的宽度计算无所谓；但调用频率必须压到"文本变了才算一次"。
     */
    private List<String> wrapText(String text, int maxW) {
        List<String> out = new ArrayList<>();
        String cur = "";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(cur);
                cur = "";
                continue;
            }
            String next = cur + c;
            if (this.font.width(next) > maxW && !cur.isEmpty()) {
                int sp = cur.lastIndexOf(' ');
                if (sp > 0) {
                    out.add(cur.substring(0, sp));
                    cur = cur.substring(sp + 1) + c;
                } else {
                    out.add(cur);
                    cur = String.valueOf(c);
                }
            } else {
                cur = next;
            }
        }
        if (!cur.isEmpty() || out.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private void say(String text) {
        this.flash = text;
        this.flashUntil = System.currentTimeMillis() + 1800L;
    }

    // ------------------------------------------------------------------
    // 背景容器界面的补画（高亮 / 手持物品 / 悬浮提示 / 选格覆盖层）
    // ------------------------------------------------------------------

    /**
     * 自己给被压住的容器界面补 hover 高亮，并把命中的槽位记下来给 tooltip 用。
     *
     * ★ 为什么必须自己画 ★
     * 背景层是由 ClientHooks#drawScreen 渲染的，它给背景层传的鼠标坐标是
     * Integer.MAX_VALUE（为了不让背景层的控件误高亮），而 AbstractContainerScreen
     * 恰恰是在 render 里用这两个坐标算出 hoveredSlot 的
     * → 背景层的 hoveredSlot 永远是 null，格子不会亮、悬浮提示也不出来。
     * 我们在最顶层，手里有真实鼠标坐标，所以自己补上。
     *
     * 需要的全是公开 API（都已核实）：
     *   getGuiLeft() / getGuiTop() / getSlotColor(int)
     *   Slot.x / Slot.y / isActive() / isHighlightable()
     *   renderSlotHighlight(GuiGraphics,int,int,int,int)（public static）
     */
    private void renderBackgroundHover(GuiGraphics g, int mouseX, int mouseY, Layout L) {
        backgroundHoveredSlot = null;
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null || overPanel(mouseX, mouseY, L)) {
            return;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.getMenu().slots) {
            // 与 vanilla 的判定保持一致：isActive 决定"能不能交互"，
            // isHighlightable 决定"要不要画高亮"（部分模组用假槽位占位，两样都要看）
            if (!slot.isActive() || !slot.isHighlightable()) {
                continue;
            }
            int sx = left + slot.x;
            int sy = top + slot.y;
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
                backgroundHoveredSlot = slot;
                // 选格模式下由我们自己画"选中/可选中"的框，别叠上原版的高亮
                if (!cellMode) {
                    AbstractContainerScreen.renderSlotHighlight(g, sx, sy, 0, bg.getSlotColor(slot.index));
                }
                return;
            }
        }
    }

    /**
     * 选格模式的覆盖层：把被压住的容器格子标出"已选 / 鼠标在这 / 不可选"。
     * 只有这一层能告诉用户"我点到的是哪一格" —— 否则光标禁用了物品交互之后，
     * 点上去毫无反馈，用户会以为坏了。
     */
    private void renderCellOverlay(GuiGraphics g, int mouseX, int mouseY) {
        if (!cellMode) {
            return;
        }
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.getMenu().slots) {
            if (!slot.isActive() || !slot.isHighlightable()) {
                continue;
            }
            int sx = left + slot.x - 1;
            int sy = top + slot.y - 1;
            boolean selectable = ContainerAccess.isSelectable(slot);
            boolean selected = selMenuSlots.contains(slot.index);
            boolean hover = mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18;

            if (selected) {
                g.fill(sx, sy, sx + 18, sy + 18, 0x4822CC88);
                ring(g, sx, sy, 18, 18, 0xFF3FE0A0);
            } else if (hover && selectable) {
                ring(g, sx, sy, 18, 18, 0xFFFFFFFF);
            } else if (!selectable) {
                // 玩家自己的背包格 / 模组假槽位：压暗，明确"这里点不了"
                g.fill(sx, sy, sx + 18, sy + 18, 0x38000000);
            }
        }
    }

    private static void ring(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 自己把「鼠标手持的物品」画到真实鼠标位置上。
     *
     * ★ 为什么必须自己画 ★
     * 背景容器界面是用 `Integer.MAX_VALUE` 当鼠标坐标渲染的，
     * 于是 `AbstractContainerScreen.render` 末尾那段"把 carried item 画在光标旁边"
     * 的代码把它画到了屏幕外 —— 表现就是实机反馈的
     * "东西确实拿起来了，但跟着鼠标的图标和数字不见了"。
     *
     * 这段复刻 vanilla 的 `renderFloatingItem`（**它是 private，只能照着写**）：
     *   pose.translate(0,0,232) → renderItem(stack,x,y) → renderItemDecorations(font,stack,x,y)
     * 位置取 `(mouseX-8, mouseY-8)`；vanilla 是在平移了 `(leftPos,topPos)` 的 pose 里
     * 传 `(mouseX-leftPos-8, mouseY-topPos-8)`，两者等价。
     *
     * 已知差异：vanilla 在做「拖拽逐格分配」时会把数字画成剩余待分配量
     * （`quickCraftingRemainder`，private，我们读不到），这里只能画完整数量。
     */
    private void renderCarriedItem(GuiGraphics g, int mouseX, int mouseY) {
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        ItemStack carried = bg.getMenu().getCarried();
        if (carried.isEmpty()) {
            return;
        }
        int x = mouseX - 8;
        int y = mouseY - 8;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 232.0F);
        g.renderItem(carried, x, y);
        Font countFont = IClientItemExtensions.of(carried)
                .getFont(carried, IClientItemExtensions.FontContext.ITEM_COUNT);
        g.renderItemDecorations(countFont == null ? this.font : countFont, carried, x, y);
        g.pose().popPose();
    }

    /**
     * 被压下容器的槽位悬浮提示。
     * 同样是因为 `hoveredSlot` 在背景层恒为 null，vanilla 的 `renderTooltip` 也不干活。
     * 手持物品时按 vanilla 的规矩不显示。
     */
    private void renderBackgroundTooltip(GuiGraphics g, int mouseX, int mouseY) {
        Slot slot = backgroundHoveredSlot;
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (slot == null || bg == null || !slot.hasItem()) {
            return;
        }
        if (!bg.getMenu().getCarried().isEmpty()) {
            return;
        }
        g.renderTooltip(this.font, slot.getItem(), mouseX, mouseY);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    private boolean overPanel(double mouseX, double mouseY, Layout L) {
        return isIn((int) mouseX, (int) mouseY, L.x(), L.y(), L.w(), L.h());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Layout L = layout();

        // ★ 面板之外一律让给被压住的容器界面 ★
        // 这样"面板打开时机器还能操作"。可行性依据：AbstractContainerScreen#mouseClicked
        // 内部用 findSlot(mouseX, mouseY) 现算坐标、**不依赖 hoveredSlot**
        // （而背景层的 hoveredSlot 恰好恒为 null），所以转发是有效的。
        //
        // ★ 例外：选格模式。★ 需求要的是"光标禁用物品交互"，所以这一支什么都转交，
        //   只用来做可视化选格（否则点一格就会顺手把物品拿起来）。
        if (!overPanel(mouseX, mouseY, L)) {
            if (cellMode) {
                handleCellClick(mouseX, mouseY, button);
                return true;
            }
            AbstractContainerScreen<?> bg = backgroundContainer();
            return bg != null && bg.mouseClicked(mouseX, mouseY, button);
        }

        // ★ 右键在波形上 = 加 / 删标记（G4）★
        // 需求原文是"左击打标记、双击删除"，但左键已经用于框选区间，双击又很难
        // 与两次单击区分 —— 用户自己提出改右键：右键空白处加标记，右键已有标记
        // （±3px）删掉，加与删是同一个动作，也不用和框选抢键位。
        if (button == 1 && (page == Page.CHART || page == Page.REPLAY) && L.hasChart()
                && ChartRenderer.inPlot(L.x() + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), mouseX, mouseY)) {
            toggleMarker((int) mouseX, L);
            return true;
        }

        // QoL（第十六轮⑦）：计算页右键图例 = 按下该图例的「分析」。
        // 左键仍是弹「数量 / IO / 分析」菜单；右键直达，省一次弹窗点击。
        if (button == 1 && page == Page.REPLAY) {
            for (Chip c : L.legend()) {
                if (c.enabled() && c.hit(mouseX, mouseY) && c.id().startsWith(SERIES_PREFIX)) {
                    String itemId = c.id().substring(SERIES_PREFIX.length());
                    replaySelectedItem = itemId.equals(replaySelectedItem) ? null : itemId;
                    legendPopup = null;
                    return true;
                }
            }
        }

        if (button != 0) {
            return true;
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        // ★ 存档筛选框（第十八轮③）：左键点到框外 → 收起焦点（框内点击在下面 ROOT 段重新聚焦）★
        // 放在一切命中判定之前：点任何别的控件都先让输入框失焦，"焦点跟着点击走"。
        if (archFilterFocus && !inArchFilter(L, mx, my)) {
            archFilterFocus = false;
        }

        // 1) 右下角缩放把手（优先于标题栏，不然永远点不到）
        if (isIn(mx, my, L.x() + L.w() - RESIZE_GRIP, L.y() + L.h() - RESIZE_GRIP, RESIZE_GRIP, RESIZE_GRIP)) {
            resizing = true;
            resizeGripX = L.x() + L.w() - mx;
            resizeGripY = L.y() + L.h() - my;
            return true;
        }

        // 2) 标题栏 → 开始拖动
        if (isIn(mx, my, L.x(), L.y(), L.w(), TITLE_H)) {
            dragging = true;
            dragOffX = mx - L.x();
            dragOffY = my - L.y();
            return true;
        }

        // 2.5) 图例弹出菜单：优先于它盖住的一切控件。
        //   点在菜单里 → 切换那一行的开关（菜单保持开着，好连着切 IO）；
        //   点在所属图例块上 → 交给下面的 chip 循环去收起/切换；
        //   点在其余任何地方 → 关掉菜单并吃掉这次点击（标准菜单语义）。
        if (legendPopup != null) {
            int[] pr = popupRect(L);
            if (pr == null) {
                legendPopup = null;   // 物品消失 / 图例被 +N 收起，菜单失效
            } else if (isIn(mx, my, pr[0], pr[1], pr[2], pr[3])) {
                List<PopupRow> rows = popupRows(legendPopup);
                int row = (my - pr[1] - 2) / POPUP_ROW_H;
                if (row >= 0 && row < rows.size()) {
                    PopupRow r = rows.get(row);
                    switch (r.kind()) {
                        case PopupRow.IO -> toggleIo(legendPopup);
                        case PopupRow.ANALYZE -> replaySelectedItem =
                                legendPopup.equals(replaySelectedItem) ? null : legendPopup;
                        default -> toggleSeries(legendPopup);
                    }
                }
                return true;
            } else {
                boolean onOwnerChip = false;
                for (Chip c : L.legend()) {
                    if (c.id().equals(SERIES_PREFIX + legendPopup) && c.hit(mx, my)) {
                        onOwnerChip = true;
                        break;
                    }
                }
                if (!onOwnerChip) {
                    legendPopup = null;
                    return true;
                }
            }
        }

        // 2.6) 「运算」弹窗（回放计算）：语义与图例弹窗一致
        //   点在菜单里 → 选中那个运算并关闭；点在所属工具栏块上 → 交给 chip 循环收起；
        //   点在其余任何地方 → 关掉并吃掉这次点击。
        if (opPopupOpen) {
            int[] pr = opPopupRect(L);
            if (pr == null) {
                opPopupOpen = false;
            } else if (isIn(mx, my, pr[0], pr[1], pr[2], pr[3])) {
                int row = (my - pr[1] - 2) / POPUP_ROW_H;
                if (row >= 0 && row < OP_VALUES.length) {
                    analyzeOp = OP_VALUES[row];
                    opPopupOpen = false;
                }
                return true;
            } else {
                boolean onOwnerChip = false;
                for (Chip c : L.toolbar()) {
                    if ("calc_op".equals(c.id()) && c.hit(mx, my)) {
                        onOwnerChip = true;
                        break;
                    }
                }
                if (!onOwnerChip) {
                    opPopupOpen = false;
                    return true;
                }
            }
        }

        // 3) 波形区 → 开始框选（图表页 / 回放页有波形）
        if ((page == Page.CHART || page == Page.REPLAY) && L.hasChart()
                && ChartRenderer.inPlot(L.x() + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), mx, my)) {
            beginSelection(mx);
            return true;
        }

        // 3.5) DATA 页：值列表 / 编辑格
        if (page == Page.DATA && handleDataClick(mx, my, L, button)) {
            return true;
        }

        // 4) 工具栏 / 图例 / 按钮
        for (Chip c : allChips(L)) {
            if (c.enabled() && c.hit(mx, my)) {
                onChip(c.id());
                return true;
            }
        }

        // 5) 列表（根页的实例列表 / 选格页的槽位列表）+ 根页的存档列表
        if (page == Page.ROOT) {
            // 筛选框（第十八轮③）：点在框上 → 聚焦（吃掉这次点击，不触发别的）
            if (inArchFilter(L, mx, my)) {
                archFilterFocus = true;
                return true;
            }
            // 存档行（下半部分）先判：它几何上在探针列表之外
            List<Archives.Info> arch = filteredArchives();
            if (L.archRows() > 0 && !arch.isEmpty()) {
                for (int i = 0; i < L.archRows(); i++) {
                    int idx = scrollArchives + i;
                    if (idx >= arch.size()) {
                        break;
                    }
                    int ry = L.archTop() + ROW_H + i * ROW_H;
                    if (isIn(mx, my, L.x() + 4, ry - 1, L.w() - 8, ROW_H)) {
                        openArchive(arch.get(idx));
                        return true;
                    }
                }
            }
            List<ProbeInstance> list = LogiScopeMod.probes().all();
            for (int i = 0; i < L.listRows(); i++) {
                int idx = scrollRow + i;
                if (idx >= list.size()) {
                    break;
                }
                int ry = L.listTop() + i * ROW_H;
                // 删除按钮优先命中（它在行内右侧，先判它）
                if (isIn(mx, my, deleteX(L), ry - 1, DEL_W, ROW_H)) {
                    ProbeManager mgr = LogiScopeMod.probes();
                    ProbeInstance p = list.get(idx);
                    mgr.remove(p.id());
                    say(tr("logiscope.flash.probe_deleted").replace("%s", p.name()));
                    return true;
                }
                if (isIn(mx, my, L.x() + 4, ry - 1, L.w() - 8 - DEL_W - 2, ROW_H)) {
                    openProbe(list.get(idx));
                    return true;
                }
            }
        } else if (page == Page.PICKER) {
            List<ContainerAccess.SlotView> list = pickerList();
            for (int i = 0; i < L.listRows(); i++) {
                int idx = scrollRow + i;
                if (idx >= list.size()) {
                    break;
                }
                int ry = L.listTop() + i * ROW_H;
                if (isIn(mx, my, L.x() + 4, ry - 1, L.w() - 8, ROW_H)) {
                    toggleRow(list.get(idx));
                    return true;
                }
            }
        }

        // 点在面板上但没打中任何控件：吃掉，别透传给底下
        return true;
    }

    private List<Chip> allChips(Layout L) {
        List<Chip> out = new ArrayList<>(L.buttons().size() + L.toolbar().size() + L.legend().size());
        out.addAll(L.legend());
        out.addAll(L.toolbar());
        out.addAll(L.buttons());
        return out;
    }

    /** 根页点了一行：有格子就去看图表，还没选格就进选格页 */
    private void openProbe(ProbeInstance p) {
        LogiScopeMod.probes().setActive(p.id());
        scrollRow = 0;
        if (p.hasAnything()) {
            page = Page.CHART;
            live = true;
            selection = null;
            selectionStats = List.of();
            userZoomed = false;
        } else {
            enterPicker(p);
        }
        legendPopup = null;
        builtVersion = Long.MIN_VALUE;
    }

    /** 选格页点了一行：切换这一格 */
    private void toggleRow(ContainerAccess.SlotView v) {
        if (aeMode) {
            // AE 网络（第十六轮⑧）：只有网络列表的显示格（RepoSlot）能选 ——
            // 背包/合成格里的东西不是"网络中的物品"，点了忽略（不弹错，误触不该有反馈负担）。
            if (v.empty()) {
                return;
            }
            AbstractContainerMenu menu = ContainerAccess.currentMenu();
            if (menu == null || v.menuSlotIndex() < 0 || v.menuSlotIndex() >= menu.slots.size()) {
                return;
            }
            if (!menu.slots.get(v.menuSlotIndex()).getClass().getName().endsWith(".RepoSlot")) {
                return;
            }
            if (!selectedAeIds.remove(v.itemId())) {
                selectedAeIds.add(v.itemId());
            }
            return;
        }
        if (source == Source.MENU) {
            if (!selMenuSlots.remove(v.menuSlotIndex())) {
                selMenuSlots.add(v.menuSlotIndex());
            }
            syncContainerSelection();
        } else if (source == Source.WORLD) {
            if (!selContainerSlots.remove(v.containerSlotIndex())) {
                selContainerSlots.add(v.containerSlotIndex());
            }
        }
    }

    // ------------------------------------------------------------------
    // 可视化选格
    // ------------------------------------------------------------------

    /** 面板外的一次点击 = 对某个容器格子的加选/减选（左键加，右键减） */
    private void handleCellClick(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return;
        }
        Slot slot = slotAt(mouseX, mouseY);
        cellDragButton = -1;
        if (slot != null && ContainerAccess.isSelectable(slot)) {
            applyCell(slot, button == 0);
            cellDragButton = button;
            cellLastX = (int) mouseX;
            cellLastY = (int) mouseY;
        }
    }

    /**
     * 拖过一串格子。
     *
     * ★ 为什么要按线段采样、而不是只判当前位置 ★
     *   鼠标事件是按帧来的，快速拖动时相邻两帧的坐标可能已经跨过两三个格子 ——
     *   只判当前位置会**漏格**，而"划过的容器都被选中"是需求明写的。
     *   这里沿着上一帧到这一帧的线段每 4px 取一个采样点，逐点命中。
     */
    private void handleCellDrag(double mouseX, double mouseY) {
        if (cellDragButton < 0) {
            return;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int x0 = cellLastX == Integer.MIN_VALUE ? mx : cellLastX;
        int y0 = cellLastY == Integer.MIN_VALUE ? my : cellLastY;
        double dist = Math.hypot(mx - x0, my - y0);
        int steps = Math.max(1, (int) Math.ceil(dist / 4.0));
        boolean add = cellDragButton == 0;
        for (int s = 0; s <= steps; s++) {
            double t = steps == 0 ? 1.0 : (double) s / steps;
            int px = (int) Math.round(x0 + (mx - x0) * t);
            int py = (int) Math.round(y0 + (my - y0) * t);
            Slot slot = slotAt(px, py);
            if (slot != null && ContainerAccess.isSelectable(slot)) {
                applyCell(slot, add);
            }
        }
        cellLastX = mx;
        cellLastY = my;
    }

    private void applyCell(Slot slot, boolean add) {
        if (aeMode) {
            return;   // AE 模式没有"容器格"语义（cell_mode 按钮也已禁用，双保险）
        }
        if (add) {
            selMenuSlots.add(slot.index);
        } else {
            selMenuSlots.remove(slot.index);
        }
        syncContainerSelection();
    }

    /** 鼠标落在容器界面的哪个槽位上（与 vanilla 的 findSlot 判定一致：±1px 容差） */
    @Nullable
    private Slot slotAt(double mouseX, double mouseY) {
        AbstractContainerScreen<?> bg = backgroundContainer();
        if (bg == null) {
            return null;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.getMenu().slots) {
            if (!slot.isActive() || !slot.isHighlightable()) {
                continue;
            }
            int sx = left + slot.x;
            int sy = top + slot.y;
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
                return slot;
            }
        }
        return null;
    }

    private void beginSelection(int mx) {
        // ★ 必须先取窗口、再置 selecting ★
        // window() 的第一分支就是"框选中 → 返回锁住的窗口"，
        // 先置位的话这里读到的是上一次的残留值（本会话首次是初始值 0,0）
        // → 窗口被锁成 [0,0] → 波形立刻变"这一区间没有数据"，
        //   且永远进不了冻结态（下游表现：M16 的"实时"按钮看起来点不动）。
        // 这正是第六轮 M12/M13/M14/M16 四条的共同根因 ——
        // 教训：给"读状态的函数"加分支时，先查所有调用点有没有"边改状态边调它"。
        long[] win = window();
        selecting = true;
        selAnchorX = mx;
        selCurrentX = mx;
        selectWindowStart = win[0];
        selectWindowEnd = win[1];
    }

    private ChartRenderer.Selection draftSelection(Layout L, long start, long end) {
        int cx = L.x() + PAD;
        int cw = L.w() - 2 * PAD;
        long a = ChartRenderer.tickAtX(cx, cw, start, end, selAnchorX);
        long b = ChartRenderer.tickAtX(cx, cw, start, end, selCurrentX);
        return new ChartRenderer.Selection(a, b);
    }

    private void endSelection(Layout L) {
        if (!selecting) {
            return;
        }
        selecting = false;

        // 按"拖动时锁住的那个窗口"把像素换算成 tick —— 不能重算窗口，
        // 否则松开手的那一帧窗口已经变了，选框会跳一下
        ChartRenderer.Selection sel = draftSelection(L, selectWindowStart, selectWindowEnd);
        if (Math.abs(selCurrentX - selAnchorX) < 3 || !sel.isValid()) {
            // 只是点了一下（没有拖动）→ 当作"取消选择"，其余状态不动
            selection = null;
            selectionStats = List.of();
            return;
        }

        // ★ 建立选择就冻结视图 ★
        // 否则波形会继续往前滚，刚框住的那段几秒后就跑出屏幕了。
        // 想回到实时就点工具栏的「实时」（它会一并清掉选择）—— 规则只有这一条，好记。
        live = false;
        frozenEnd = selectWindowEnd;
        selection = sel;
        refreshSelectionStats();
    }

    // ------------------------------------------------------------------
    // 选格页的「完成」
    // ------------------------------------------------------------------

    /**
     * 把选格页上的勾选变成探针目标并开始记录。
     *
     * ★ 为什么"完成"这一步同时是"创建"那一步的终点，而不是在建实例时就把目标定死 ★
     *   因为槽位是**在选格页里才选出来的**。实例只是个容器（放状态机和缓冲），
     *   目标在点「完成」时才确定 —— 这样"点了创建探针又反悔"只需要把空实例删掉。
     */
    private void commitPicker() {
        ProbeManager mgr = LogiScopeMod.probes();
        ProbeInstance p = mgr.find(pickerProbeId);
        if (p == null) {
            page = Page.ROOT;
            return;
        }

        // ★ NBT 槽位型容器（第十六轮）★
        // 这类机器（MEK 等）把槽位号写在 NBT 的 `slot` 字段里，菜单位置与容器下标都可能
        // 对不上。所以**不组 SlotRef**，改成："把点到的格子按内容反查成 slot 号"，
        // 之后无论 GUI 开还是关，都只按 slot 号读 NBT —— 一条路，不会自相矛盾。
        if (aeMode) {
            commitAeTargets(p);
            return;
        }
        if (nbtSlotMode) {
            commitNbtSlotAnchors(p);
            return;
        }

        List<ProbeTarget.SlotRef> refs = new ArrayList<>();
        List<ContainerAccess.SlotView> forVerify = new ArrayList<>();

        // 先按"菜单槽"这条路组目标（界面开着时它最准，也带着可验证的下标）。
        // 容器界面可能在选格途中被关掉（菜单槽清单变空），所以组不出来时再退到世界侧清单。
        for (ContainerAccess.SlotView v : menuSlots) {
            if (!selMenuSlots.contains(v.menuSlotIndex())) {
                continue;
            }
            refs.add(new ProbeTarget.SlotRef(v.menuSlotIndex(), v.containerSlotIndex(),
                    v.itemId(), v.count()));
            forVerify.add(v);
        }
        if (refs.isEmpty()) {
            for (ContainerAccess.SlotView v : worldSlots) {
                if (!selContainerSlots.contains(v.containerSlotIndex())) {
                    continue;
                }
                // 无 GUI 容器没有菜单槽下标 → -1，只能走世界侧读取
                refs.add(new ProbeTarget.SlotRef(-1, v.containerSlotIndex(), v.itemId(), v.count()));
                forVerify.add(v);
            }
        }

        if (refs.isEmpty() && p.dataTargets().isEmpty()) {
            say(tr("logiscope.flash.need_cells"));
            return;
        }

        ProbeTarget old = p.target();
        ProbeTarget target = new ProbeTarget(refs, old.deviceName(), old.dimension(), old.pos());
        // verifyViews：下标指纹验证只在"界面开着"这一刻做得准，所以顺手带上
        p.configure(target, forVerify);
        mgr.setActive(p.id());

        hiddenItems.clear();
        ioShown.clear();
        legendPopup = null;
        selection = null;
        selectionStats = List.of();
        live = true;
        userZoomed = false;
        builtVersion = Long.MIN_VALUE;
        cellMode = false;
        page = Page.CHART;
        say(tr("logiscope.flash.probe_configured"));
    }

    /**
     * NBT 槽位模式下的「完成」：把**这一刻勾选的格子**翻译成"数据里的 slot 号"，然后
     * **覆写**这个探针的全部槽位锚点。
     *
     * ★ 两条语义（第十六轮④，都被实机推着改过一次）★
     *   1. **覆写，不是追加**：界面上勾了几格，探针就只测那几格。第十六轮②的"追加"
     *      让用户永远删不掉不想要的格子（只能去数据页一格一格 ×）。
     *      **纯数据目标（数据页加的 FE / 流体）不受影响** —— 那是另一条线。
     *   2. **宁可不选，也不给一个错的 slot 号**：全部判据都要求"内容"对上，
     *      对不上就跳过并在提示里报数（见 {@link #resolveNbtSlot}）。
     *
     * @see SlotAlign 为什么"顺序 + 内容"能定，而"容器下标"不能
     */
    /**
     * AE 模式的「完成」（第十六轮⑧）：**覆写**全部 AE 网络目标 ——
     * 界面上选了哪些物品，探针就只测哪些（与槽位锚点同一覆写语义，第十六轮④）。
     * DATA 页的纯路径目标与 NBT 槽位锚点保留；一个都没选 = 什么都不动
     * （"读不到数据" ≠ "用户想清空"，同一纪律）。
     */
    private void commitAeTargets(ProbeInstance p) {
        if (selectedAeIds.isEmpty()) {
            say(tr("logiscope.flash.ae_none"));
            return;
        }
        ProbeTarget t = p.target();
        List<DataTarget> ae = new ArrayList<>();
        for (String id : selectedAeIds) {
            ae.add(DataTarget.aeNetwork(id, t.deviceName(), t.dimension(), t.pos()));
        }
        int total = p.replaceAeTargets(ae);

        ProbeTarget old = p.target();
        // 槽位清空：AE 目标只走"网络读值"一条路，不碰菜单/坐标槽位 ——
        // 与 NBT 锚点同一条纪律：两条路读数必然不一致，就只留一条。
        p.configure(new ProbeTarget(List.of(), old.deviceName(), old.dimension(), old.pos()), null);
        LogiScopeMod.probes().setActive(p.id());

        hiddenItems.clear();
        ioShown.clear();
        legendPopup = null;
        selection = null;
        selectionStats = List.of();
        live = true;
        userZoomed = false;
        builtVersion = Long.MIN_VALUE;
        cellMode = false;
        page = Page.CHART;
        say(tr("logiscope.flash.ae_replaced").replace("%s", String.valueOf(total)));
    }

    private void commitNbtSlotAnchors(ProbeInstance p) {
        Snbt.Node tree = Snbt.parse(DataCommand.text());
        List<NbtSlotLayout.Entry> entries = NbtSlotLayout.entriesAt(tree, nbtSlotListPath);
        List<ContainerAccess.SlotView> space = pickerList();
        SlotAlign.Result align = SlotAlign.align(nbtCells(space), entries);
        boolean idxUsable = containerIndexUnique(space);

        List<DataTarget> next = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < space.size(); i++) {
            ContainerAccess.SlotView v = space.get(i);
            if (!isSelected(v)) {
                continue;
            }
            Integer slot = resolveNbtSlot(v, nbtOrdinal(space, i, v), entries, align, idxUsable);
            if (slot == null) {
                skipped++;      // 空格且推不出 slot / 分不出是哪一格 —— 不猜
                continue;
            }
            // 采样用的名字：非空格 = **物品 id**（不带后缀）→ 同物品多格在聚合时自然求和；
            // 空格 = 占位名（它现在没有物品，等有东西了名字会从数据里取，等于新开一条线）。
            String name = v.empty() || v.itemId() == null || v.itemId().isBlank()
                    ? tr("logiscope.data.slot_fallback").replace("%s", String.valueOf(slot))
                    : v.itemId();
            next.add(DataTarget.slotAnchor(nbtSlotListPath, name,
                    p.target().deviceName(), p.target().dimension(), p.target().pos(), slot,
                    NbtSlotLayout.rootTypeId(tree)));
        }

        // 一格都没解出来 → 什么都不动。**绝不把已有锚点清空** —— "读不到数据"和
        // "用户想清空"是两件事，混在一起就必然在某一侧撒谎（用户宁可不显示也不要错值）。
        if (next.isEmpty()) {
            say(tr(selectionCount() == 0
                    ? "logiscope.flash.slot_anchor_need_cells"
                    : "logiscope.flash.slot_anchor_none"));
            return;
        }

        ProbeTarget old = p.target();
        // 槽位列表留空：这类容器的物品读数**只走 NBT**，不走菜单/能力下标。
        // dataTargets 由 replaceSlotAnchors 覆写（只换锚点，纯数据目标保留）。
        p.configure(new ProbeTarget(List.of(), old.deviceName(), old.dimension(), old.pos()), null);
        int total = p.replaceSlotAnchors(next);
        LogiScopeMod.probes().setActive(p.id());

        hiddenItems.clear();
        ioShown.clear();
        legendPopup = null;
        selection = null;
        selectionStats = List.of();
        live = true;
        userZoomed = false;
        builtVersion = Long.MIN_VALUE;
        cellMode = false;
        page = Page.CHART;
        String msg = tr("logiscope.flash.slot_anchor_added").replace("%s", String.valueOf(total));
        if (skipped > 0) {
            msg += "  ·  " + tr("logiscope.flash.slot_anchor_skipped").replace("%s", String.valueOf(skipped));
        }
        say(msg);
    }

    /**
     * 把"点到的这一格"翻译成"数据里的 slot 号"（第十六轮④）。
     * 判据链本体在纯类里（{@link SlotAlign#resolve}，离线有断言 t33），这里只做装配：
     * 空格子的 itemId 常被填成 {@code minecraft:air}，所以"空不空"必须用 {@code v.empty()} 判。
     */
    @Nullable
    private Integer resolveNbtSlot(ContainerAccess.SlotView v, int ordinal,
                                   List<NbtSlotLayout.Entry> entries,
                                   SlotAlign.Result align, boolean idxUsable) {
        boolean empty = v.empty() || v.itemId() == null || v.itemId().isBlank();
        return SlotAlign.resolve(new SlotAlign.Pick(ordinal, v.containerSlotIndex(), v.itemId(), empty),
                entries, align, idxUsable);
    }

    /**
     * 机器格在"数据 slot 序号"上的位置：
     *   · 菜单型容器 → 它在菜单机器格序列里的**序数**（模组按物品栏顺序 addSlot，两者同序）；
     *   · 无 GUI 型容器 → 服务端读出来的 handler **下标本身就是 slot 号**（`ProbeSlots` 按 0..n-1 读）。
     */
    private int nbtOrdinal(List<ContainerAccess.SlotView> space, int i, ContainerAccess.SlotView v) {
        if (source == Source.WORLD && v.containerSlotIndex() >= 0) {
            return v.containerSlotIndex();
        }
        return i;
    }

    /** 对齐要的输入：**只放非空格**（空格子两侧都不在，因此不影响对齐），按序数升序 */
    private List<SlotAlign.Cell> nbtCells(List<ContainerAccess.SlotView> space) {
        List<SlotAlign.Cell> out = new ArrayList<>();
        for (int i = 0; i < space.size(); i++) {
            ContainerAccess.SlotView v = space.get(i);
            if (v.empty() || v.itemId() == null || v.itemId().isBlank()) {
                continue;
            }
            out.add(new SlotAlign.Cell(nbtOrdinal(space, i, v), v.menuSlotIndex(), v.itemId()));
        }
        return out;
    }

    /**
     * 这个清单的"容器下标"能不能当身份用 —— 判据本体在 {@link SlotAlign#containerIndexReliable}
     * （**同一份清单里出现重复下标 ⇒ 它不是身份**；Mekanism 每格都报 0，就是实测踩到的那个坑）。
     */
    private boolean containerIndexUnique(List<ContainerAccess.SlotView> space) {
        List<Integer> idx = new ArrayList<>(space.size());
        for (ContainerAccess.SlotView v : space) {
            idx.add(v.containerSlotIndex());
        }
        return SlotAlign.containerIndexReliable(idx);
    }

    /**
     * 打开 NBT 追踪时，把**已有的锚点勾回界面上**（按格反查用 {@link SlotAlign.Result#bySlot}，
     * 当前空着的格子用统一位移外推）。
     *
     * 为什么必须有这一步：既然「完成」是**覆写**，那么"进页面时界面上没勾任何格"就等于
     * "一点完成就把目标全清掉" —— 用户点了开追踪、想加一格，结果把原来的都清了，
     * 这就成了新的坑。回填之后，界面上的勾选状态 == 探针当前的目标，覆写才是安全的。
     */
    private void seedNbtSelection(ProbeInstance p) {
        selMenuSlots.clear();
        selContainerSlots.clear();
        if (p == null) {
            return;
        }
        List<ContainerAccess.SlotView> space = pickerList();
        Snbt.Node tree = Snbt.parse(DataCommand.text());
        SlotAlign.Result align = SlotAlign.align(nbtCells(space),
                NbtSlotLayout.entriesAt(tree, nbtSlotListPath));
        Integer shift = align.shift();
        for (DataTarget d : p.dataTargets()) {
            if (!d.isSlotAnchor() || !d.valuePath().equals(nbtSlotListPath)) {
                continue;
            }
            Integer ordinal = align.bySlot().get(d.nbtSlot());
            if (ordinal == null && shift != null) {
                ordinal = d.nbtSlot() + shift;
            }
            if (ordinal == null) {
                continue;
            }
            for (int i = 0; i < space.size(); i++) {
                ContainerAccess.SlotView v = space.get(i);
                if (nbtOrdinal(space, i, v) == ordinal) {
                    if (source == Source.MENU) {
                        selMenuSlots.add(v.menuSlotIndex());
                    } else {
                        selContainerSlots.add(v.containerSlotIndex());
                    }
                    break;
                }
            }
        }
        syncContainerSelection();
    }

    /** 选格页的「返回列表」。还没选过格的空实例顺手删掉，不留垃圾。 */
    private void leavePicker() {
        nbtSlotMode = false;      // 每次进选格页都回到"默认关"（用户明确要求默认不启用）
        aeMode = false;           // AE 模式跟随菜单（重开时由 seedSelectionOnce 重新检测）
        nbtSeedPending = false;
        nbtSlotListPath = "";
        nbtSlotEntries = List.of();
        nbtSlotDetected = false;
        ProbeInstance p = LogiScopeMod.probes().find(pickerProbeId);
        if (p != null && !p.hasAnything()) {
            LogiScopeMod.probes().remove(p.id());
        }
        cellMode = false;
        cellDragButton = -1;
        page = Page.ROOT;
    }

    // ------------------------------------------------------------------
    // 存档与回放（G5）
    // ------------------------------------------------------------------

    /**
     * 「存档并停止」：导出当前记录 → 停止记录并删除探针与缓存 → 直接进入回放页。
     *
     * ★ 顺序有讲究 ★
     * 1. 先写文件 —— **写失败就什么都不动**（探针与数据原地保留），绝不出现
     *    "探针删了、存档没写出来"的半途状态；
     * 2. 写成功才删探针（`remove` 会停止记录并清掉缓存）；
     * 3. 载入刚导出的数据进入 REPLAY 页 —— 用户要的"默认读取当前存档"就是这条直连路径。
     */
    private void stopAndArchive() {
        ProbeInstance p = currentProbe();
        if (p == null || p.store().size() == 0) {
            return;
        }
        ArchiveData data;
        try {
            data = Archives.export(p);
        } catch (Exception e) {
            LogiScopeMod.LOGGER.error("[LogiScope] archive export failed", e);
            say(tr("logiscope.flash.archive_failed"));
            return;
        }
        LogiScopeMod.probes().remove(p.id());
        archiveList = Archives.scan();
        loadReplay(data);
        say(tr("logiscope.flash.archived").replace("%s", data.name()));
    }

    /** 根页点了一个存档行 → 载入并进入回放页（跨存档读取就是这条路） */
    private void openArchive(Archives.Info info) {
        try {
            loadReplay(Archives.load(info));
            // 身份用目录名（永不改），显示名可用 name.txt 覆盖（第十三轮④）
            replayDir = info.dirName();
            replayName = info.displayName().isBlank() ? info.dirName() : info.displayName();
            say(tr("logiscope.flash.replay_loaded").replace("%s", replayName));
        } catch (Exception e) {
            LogiScopeMod.LOGGER.error("[LogiScope] archive load failed: {}", info.dirName(), e);
            say(tr("logiscope.flash.archive_failed"));
        }
    }

    /**
     * 进入回放页：把存档数据一次性建好（ChartData / Summary / 标记 / 视图状态）。
     *
     * ★ 回放复用图表页的全部机制 ★
     * 数据塞进同一个 {@code chartData} 字段之后，窗口 / 框选 / 十字线 / 图例 /
     * 缩放全都不用改 —— "数据是活的还是回放的"只对采集层有意义，
     * 呈现层拿到的都是 ChartData（这正是当初分层要买的 flexibility）。
     */
    private void loadReplay(ArchiveData data) {
        replayArchive = data;
        replayName = data.name();
        replayDir = data.name();   // 导出时目录名 == 存档名；从列表打开时由 openArchive 覆盖
        chartData = ChartData.build(0, data.samples(), LogiScopeConfig.maxItemKinds());
        summary = Analyzer.summarize(data.samples());
        replayMarkers.clear();
        replayMarkers.addAll(data.markers());
        replaySelectedItem = null;
        opPopupOpen = false;
        aLo = Long.MIN_VALUE;   // 计算结果缓存失效（框选也清了，结果框自然消失）
        hiddenItems.clear();
        ioShown.clear();
        legendPopup = null;
        selection = null;
        selectionStats = List.of();
        live = false;
        userZoomed = false;
        // 全览整段：回放数据是死的，一进来就该看到全貌
        windowTicks = (int) Math.min(MAX_WINDOW,
                Math.max(MIN_WINDOW, chartData.spanTicks() + 1));
        frozenEnd = chartData.lastTick();
        builtVersion = Long.MIN_VALUE;   // 回图表页时强制重算探针数据
        page = Page.REPLAY;
    }

    // ------------------------------------------------------------------
    // 波形标记（G4）
    // ------------------------------------------------------------------

    /** 右键波形：点在已有标记附近（±3px）→ 删除；否则 → 在该 tick 加标记 */
    private void toggleMarker(int mx, Layout L) {
        int chartX = L.x() + PAD;
        int chartW = L.w() - 2 * PAD;
        long[] win = window();
        // 先判"删"：光标附近的既有标记优先（否则密集标记区永远加不进新的）
        List<Long> markers = page == Page.REPLAY ? replayMarkers
                : currentProbe() != null ? currentProbe().markers().toList() : List.of();
        for (long m : markers) {
            long t = Math.max(win[0], Math.min(win[1], m));
            int x = ChartRenderer.xOfTick(chartX, chartW, win[0], win[1], t);
            if (Math.abs(x - mx) <= ChartRenderer.MARKER_HIT_PX) {
                if (page == Page.REPLAY) {
                    replayMarkers.remove(m);
                } else if (currentProbe() != null) {
                    currentProbe().markers().remove(m);
                }
                return;
            }
        }
        long t = ChartRenderer.tickAtX(chartX, chartW, win[0], win[1], mx);
        if (page == Page.REPLAY) {
            // 回放的标记只改内存（重导出才会写进文件），存档文件保持"导出时的样子"
            insertSorted(replayMarkers, t);
        } else if (currentProbe() != null) {
            currentProbe().markers().add(t);
        }
    }

    /** 往升序列表里插入（回放标记的存放形式；同一 tick 不重复） */
    private static void insertSorted(List<Long> list, long tick) {
        int i = 0;
        while (i < list.size() && list.get(i) < tick) {
            i++;
        }
        if (i < list.size() && list.get(i) == tick) {
            return;
        }
        list.add(i, tick);
    }

    private void onChip(String id) {
        ProbeManager mgr = LogiScopeMod.probes();
        if (id.startsWith(SERIES_PREFIX)) {
            // 点图例 = 切换这个物品的弹出菜单（开关在菜单里点，见 mouseClicked 2.5）
            String itemId = id.substring(SERIES_PREFIX.length());
            legendPopup = itemId.equals(legendPopup) ? null : itemId;
            return;
        }
        switch (id) {
            case "create" -> createProbe();
            case "open_folder" -> openStorageFolder();
            case "cell_mode" -> {
                cellMode = !cellMode;
                cellDragButton = -1;
                if (cellMode) {
                    say(tr("logiscope.flash.cell_mode_on"));
                }
            }
            case "clear_cells" -> {
                selMenuSlots.clear();
                selContainerSlots.clear();
                selectedAeIds.clear();
                say(tr("logiscope.flash.cells_cleared"));
            }
            case "done" -> commitPicker();
            case "to_root" -> leavePicker();
            case "list" -> {
                // 图表页的「列表」：回根页挑别的探针
                // （第十轮修的 bug：按钮 id 是 "list"，而 onChip 里只写了 "to_root"，
                //   于是这个按钮此前是**完全没反应**的 —— 用户 A 组实测反馈）
                cellMode = false;
                cellDragButton = -1;
                page = Page.ROOT;
            }
            case "pick" -> {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    cellMode = false;
                    cellDragButton = -1;
                    enterPicker(p);
                }
            }
            case "pause" -> {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    p.pause();
                    say(tr("logiscope.flash.paused"));
                }
            }
            case "resume" -> {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    p.resume();
                    say(p.state() == ProbeInstance.State.PAUSED
                            ? tr("logiscope.flash.resume_failed")
                            : tr("logiscope.flash.resumed"));
                }
            }
            case "clear" -> {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    p.clearData();
                    selection = null;
                    selectionStats = List.of();
                    live = true;
                    userZoomed = false;
                    say(tr("logiscope.flash.cleared"));
                }
            }
            case "follow" -> {
                mgr.setFollowAfterClose(!mgr.followAfterClose());
                say(followLabel());
            }
            case "archive" -> stopAndArchive();
            case "replay_back" -> page = Page.ROOT;
            case "calc_obj" -> {
                // 数量 ↔ IO，一次只算一个（切过去时缓存键变了，结果框自动重算）
                analyzeIo = !analyzeIo;
            }
            case "calc_op" -> opPopupOpen = !opPopupOpen;
            case "probe_data" -> enterDataPage();
            case "data_refresh" -> {
                ProbeInstance src = dataSrcProbe();
                if (src != null) {
                    DataCommand.request(src.target().dimension(), src.target().pos());
                    say(tr(DataCommand.statusKey()));
                }
            }
            case "data_confirm" -> confirmDataTarget();
            case "nbt_mode" -> {
                if (!nbtSlotDetected) {
                    return;
                }
                nbtSlotMode = !nbtSlotMode;
                if (nbtSlotMode) {
                    // 开的时候清掉"按菜单/下标"的选择（避免两套语义混在一条探针里），
                    // 再把**已有的锚点按格回填**成勾选状态 —— 「完成」是覆写，
                    // 不先把当前状态显示出来，用户一进来点完成就会把目标全清掉。
                    selMenuSlots.clear();
                    selContainerSlots.clear();
                    seedNbtSelection(pickerProbe());
                    nbtSeedPending = false;
                    say(tr("logiscope.picker.nbt_mode_hint"));
                } else {
                    say(tr("logiscope.flash.nbt_mode_off"));
                }
                syncContainerSelection();
            }
            case "rename" -> {
                // 点一下弹输入框（不占常规布局，也就不干扰图表交互 —— 用户原话）
                renameFocus = true;
                renameBuf = replayName;
            }
            case "data_back" -> {
                cellMode = false;
                page = Page.PICKER;
            }
            case "close" -> closeScope();
            case "live" -> {
                // 回到实时 = 放弃选择。两者不并存（选择会冻结视图，见 endSelection）
                live = true;
                selection = null;
                selectionStats = List.of();
                say(tr("logiscope.flash.live"));
            }
            case "zoom_in" -> zoom(true);
            case "zoom_out" -> zoom(false);
            case "fit" -> {
                long span = Math.max(MIN_WINDOW, chartData.spanTicks() + 1);
                windowTicks = (int) Math.min(MAX_WINDOW, span);
                live = false;
                frozenEnd = Math.max(chartData.lastTick(), chartData.firstTick());
                userZoomed = false;
                say(tr("logiscope.flash.fit"));
            }
            case "clear_sel" -> {
                selection = null;
                selectionStats = List.of();
                say(tr("logiscope.flash.sel_cleared"));
            }
            default -> {
            }
        }
    }

    /**
     * 根页的「＋ 创建探针」：新建实例 → 进选格页。
     *
     * 初始目标只带**锚点**（维度+坐标），不带槽位 —— 槽位是选格页选出来的。
     * 目标从哪来：优先"容器界面开着的这台"（有菜单 → 可视化选格可用），
     * 否则"准星上那台无 GUI 的容器"。
     */
    private void createProbe() {
        ProbeManager mgr = LogiScopeMod.probes();
        if (mgr.isFull()) {
            say(tr("logiscope.flash.too_many_probes"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ProbeContext.Candidate cand = ProbeContext.current(mc);
        if (cand == null) {
            say(tr("logiscope.flash.no_target"));
            return;
        }
        ProbeInstance created = mgr.create(new ProbeTarget(List.of(), cand.label(), cand.dimension(), cand.pos()));
        if (created == null) {
            say(tr("logiscope.flash.too_many_probes"));
            return;
        }
        // ★ MEK 设备（第十六轮⑨）：自动开启 NBT 追踪 + 提示 ★
        // MEK 的机器没有可从外部列出的容器槽位（worldSlots 为空），槽位号写在 NBT 里 ——
        // 用户点名：创建这类设备的探针时直接开好，别让人手动按按钮再猜。
        // 判据 = 准星所指方块的注册命名空间（mekanism）；NBT 识别本身仍靠形状（CP24）。
        if (cand.hasAnchor() && mc.level != null && "mekanism".equals(
                BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(cand.pos()).getBlock())
                        .getNamespace())) {
            nbtSlotMode = true;
            say(tr("logiscope.flash.mek_nbt"));
        }
        // 全新实例：先把"已回填"标记打上，免得 enterPicker 里的回填把它清成空
        // （其实也没得回填，但这样语义明确：回填只针对"已经有槽位的实例"）
        seededForProbeId = created.id();
        selMenuSlots.clear();
        selContainerSlots.clear();
        cellMode = false;
        enterPicker(created);
        say(tr("logiscope.flash.probe_created").replace("%s", created.name()));
    }

    private void toggleSeries(String itemId) {
        if (!hiddenItems.remove(itemId)) {
            hiddenItems.add(itemId);
        }
    }

    /** 图例里 IO 子行的开关：控制这条物品的**单 tick 净流量**线画不画 */
    private void toggleIo(String itemId) {
        if (!ioShown.remove(itemId)) {
            ioShown.add(itemId);
        }
    }

    /**
     * 缩放（第七轮 P5 重做）。
     *
     * @param factor  >1 放大（看更短时间），<1 缩小
     * @param anchorX 锚点像素。null = 实时态右端锚定 / 冻结态中心锚定；
     *                非 null = **冻结态光标锚定** —— 光标处的 t 值保持原位、两侧缩放，
     *                即视频编辑软件时间轨的操作方式
     *
     * 锚点约束满足不了时（窗口推到数据边界之外），**以数据起始/结束端点为准**；
     * 冻结态窗口最大就是"全览"（= 数据跨度）。
     */
    private void zoomBy(double factor, @Nullable Integer anchorX) {
        long[] win = window();
        long start = win[0];
        long end = win[1];
        long span = Math.max(MIN_WINDOW, end - start);

        long dataFirst = chartData.firstTick();
        long dataLast = Math.max(chartData.lastTick(), chartData.firstTick());
        long dataSpan = Math.max(1L, dataLast - dataFirst + 1);

        long maxSpan = live ? MAX_WINDOW : Math.max(MIN_WINDOW, dataSpan);
        long newSpan = Math.round(span / factor);
        newSpan = Math.max(MIN_WINDOW, Math.min(maxSpan, newSpan));

        if (dataSpan <= 1) {
            // 还没有数据：只动窗口宽度，别碰锚点
            windowTicks = (int) Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, newSpan));
            userZoomed = true;
            return;
        }

        if (live) {
            // 实时态：右端锚定（最新数据必须一直在视野里）
            windowTicks = (int) newSpan;
            userZoomed = true;
            return;
        }

        // 冻结态：锚点保持
        int cx = panelX + PAD;
        int cw = panelW - 2 * PAD;
        int anchor = anchorX == null ? cx + cw / 2 : anchorX;
        double ratio = cw > 1 ? Math.max(0.0, Math.min(1.0, (anchor - cx) / (double) (cw - 1))) : 0.5;
        long anchorTick = ChartRenderer.tickAtX(cx, cw, start, end, anchor);
        long newStart = Math.round(anchorTick - ratio * newSpan);
        long newEnd = newStart + newSpan;

        // 锚点满足不了 → 贴数据端点（P5）
        if (newStart < dataFirst) {
            newStart = dataFirst;
            newEnd = newStart + newSpan;
        }
        if (newEnd > dataLast) {
            newEnd = dataLast;
            newStart = newEnd - newSpan;
        }

        windowTicks = (int) newSpan;
        frozenEnd = newEnd;
        userZoomed = true;
        if (selection != null) {
            refreshSelectionStats();
        }
    }

    private void zoom(boolean in) {
        zoomBy(in ? BUTTON_ZOOM_FACTOR : 1.0 / BUTTON_ZOOM_FACTOR, null);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (cellMode && !overPanel(mouseX, mouseY, layout()) && cellDragButton >= 0 && button == cellDragButton) {
            handleCellDrag(mouseX, mouseY);
            return true;
        }
        if (resizing && button == 0) {
            panelW = (int) mouseX + resizeGripX - panelX;
            panelH = (int) mouseY + resizeGripY - panelY;
            panelW = Math.max(MIN_W, Math.min(panelW, Math.max(MIN_W, this.width - panelX)));
            panelH = Math.max(MIN_H, Math.min(panelH, Math.max(MIN_H, this.height - panelY)));
            return true;
        }
        if (dragging && button == 0) {
            panelX = (int) mouseX - dragOffX;
            panelY = (int) mouseY - dragOffY;
            clampDuringDrag();
            return true;
        }
        if (selecting && button == 0) {
            selCurrentX = (int) mouseX;
            return true;
        }
        // 面板外：拖拽也是容器的交互（原版用它做快速合成/批量摆放），要转交。
        // 选格模式下不转交 —— 那一支在上面已经 return 了。
        if (cellMode) {
            return true;
        }
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (cellDragButton >= 0 && button == cellDragButton) {
            cellDragButton = -1;
            return true;
        }
        if (resizing && button == 0) {
            resizing = false;
            return true;
        }
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        if (selecting && button == 0) {
            selCurrentX = (int) mouseX;
            endSelection(layout());
            return true;
        }
        if (cellMode) {
            return true;
        }
        // 松手同样要转交，否则容器的拖拽状态会卡住（AbstractContainerScreen 里
        // 用 mouseReleased 收尾 quick-craft）
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        // 1.20.1 的 mouseScrolled 只有单滚动参数（NeoForge 21.1 拆成 scrollX/scrollY）
        Layout L = layout();
        if (overPanel(mouseX, mouseY, L)) {
            int dir = (int) Math.signum(scrollY);
            if (dir == 0) {
                return true;
            }
            // 悬浮详情框里的长列表：滚它（用户要求"文本过多时能上下翻"）
            if (tipRect != null && !tipLines.isEmpty()
                    && isIn((int) mouseX, (int) mouseY, tipRect[0], tipRect[1], tipRect[2], tipRect[3])) {
                tipScroll = Math.max(0, tipScroll - dir);
                return true;
            }
            // DATA 页：滚原文 / 滚目标列表 / 滚值列表（看光标落在哪一块）
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
            }
            // 根页 / 选格页：滚轮翻列表（需求要求保留"滚轮列表选择"这条路）
            if (page == Page.ROOT || page == Page.PICKER) {
                if (page == Page.ROOT && L.archRows() > 0 && mouseY >= L.archTop()) {
                    // 光标在下半存档区 → 翻存档列表
                    scrollArchives = Math.max(0, scrollArchives - dir);
                } else {
                    scrollRow -= dir;
                    scrollRow = Math.max(0, scrollRow);
                }
                return true;
            }
            if ((page == Page.CHART || page == Page.REPLAY) && L.hasChart()) {
                // 滚轮 = 缩放（第七轮 P5 重做）：
                //   实时态  右端锚定（最新数据必须一直在视野里）
                //   冻结态  **光标锚定** —— 光标处的 t 值保持原位、两侧缩放
                double factor = Math.pow(WHEEL_ZOOM_FACTOR, scrollY);
                if (Math.abs(factor - 1.0) > 1e-9) {
                    zoomBy(factor, live ? null : (int) mouseX);
                }
                return true;
            }
            return true;
        }
        if (cellMode) {
            // 选格模式下光标只用于选格，滚轮也不转交（避免把容器界面滚翻页）
            return true;
        }
        // 面板外滚轮让给容器（部分模组的容器界面用滚轮翻页）
        AbstractContainerScreen<?> bg = backgroundContainer();
        return bg != null && bg.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renameFocus && !Character.isISOControl(codePoint)) {
            if (renameBuf.length() < 48) {
                renameBuf += codePoint;
            }
            return true;
        }
        if (archFilterFocus && !Character.isISOControl(codePoint)) {
            if (archFilter.length() < 48) {
                archFilter += codePoint;
                scrollArchives = 0;   // 筛选变化 → 回到列表顶部
            }
            return true;
        }
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
        // ★ 焦点别留在已经离开的页面上 ★（第十六轮⑤）
        // 重命名框只活在回放页；用户点了「重命名」再点「返回列表」时，输入框不再绘制
        // （renderRenameBox 的前置条件），但 renameFocus 还是 true → 下面那条
        // "输入框激活时吃掉所有按键"会把**整个键盘**吃掉，表现就是"键盘突然坏了"
        // （只有 Esc 能救）。这里按"页面还在不在"兜底清掉。
        if (renameFocus && page != Page.REPLAY) {
            renameFocus = false;
        }
        if (archFilterFocus && page != Page.ROOT) {
            // 与重命名框同理：焦点别留在已经离开的页面上（否则键盘全被吃掉）
            archFilterFocus = false;
        }
        if (renameFocus) {
            switch (keyCode) {
                case 259 -> {   // Backspace
                    renameBuf = chop(renameBuf);
                    return true;
                }
                case 257, 335 -> {   // Enter / 小键盘 Enter → 确认
                    commitRename();
                    return true;
                }
                case 256 -> {   // Esc → 取消
                    renameFocus = false;
                    return true;
                }
                case 86 -> {   // Ctrl+V
                    if (hasControlDown()) {
                        try {
                            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                            if (clip != null) {
                                String t = clip.trim();
                                renameBuf = t.length() > 48 ? t.substring(0, 48) : t;
                            }
                        } catch (Exception ignored) {
                            // 剪贴板不可用就算了
                        }
                        return true;
                    }
                }
                default -> {
                }
            }
            return true;   // 输入框激活时吃掉所有按键，免得误触发别的交互
        }
        if (archFilterFocus) {
            switch (keyCode) {
                case 259 -> {   // Backspace
                    archFilter = chop(archFilter);
                    scrollArchives = 0;
                    return true;
                }
                case 257, 335 -> {   // Enter / 小键盘 Enter → 确认：保留筛选、收起焦点
                    archFilterFocus = false;
                    return true;
                }
                case 256 -> {   // Esc → 取消：清空筛选并收起
                    archFilterFocus = false;
                    archFilter = "";
                    scrollArchives = 0;
                    return true;
                }
                case 86 -> {   // Ctrl+V
                    if (hasControlDown()) {
                        pasteIntoArchFilter();
                        return true;
                    }
                }
                default -> {
                }
            }
            return true;   // 输入框激活时吃掉所有按键（与重命名框一致）
        }
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

    /** 粘贴进存档筛选框（第十八轮③），同其他输入框：去首尾空白、限长 48 */
    private void pasteIntoArchFilter() {
        try {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clip == null) {
                return;
            }
            clip = clip.trim();
            archFilter = clip.length() > 48 ? clip.substring(0, 48) : clip;
            scrollArchives = 0;
        } catch (Exception ignored) {
            // 剪贴板不可用时静默
        }
    }

    // ==================================================================
    // 存档重命名（第十三轮④）
    // ==================================================================

    /** 提交重命名：只写 name.txt（不动 recording.json），然后刷新列表与标题 */
    private void commitRename() {
        renameFocus = false;
        Archives.Info info = null;
        for (Archives.Info i : archiveList) {
            if (i.dirName().equals(replayDir)) {
                info = i;
                break;
            }
        }
        if (info == null) {
            return;
        }
        try {
            Archives.rename(info, renameBuf);
            archiveList = Archives.scan();
            for (Archives.Info i : archiveList) {
                if (i.dirName().equals(replayDir)) {
                    replayName = i.displayName().isBlank() ? i.dirName() : i.displayName();
                    break;
                }
            }
            say(tr("logiscope.flash.renamed").replace("%s", replayName));
        } catch (Exception e) {
            LogiScopeMod.LOGGER.warn("[LogiScope] rename failed: {}", e.toString());
            say(tr("logiscope.flash.archive_failed"));
        }
    }

    /** 重命名输入框：画在绘图区顶部（浮层，不占常规布局） */
    private void renderRenameBox(GuiGraphics g, Layout L) {
        if (!renameFocus || !L.hasChart()) {
            return;
        }
        int x = L.x() + PAD + 4;
        int y = L.chartTop() + 4;
        int w = Math.min(L.w() - 2 * PAD - 8, 260);
        String label = tr("logiscope.rename.label");
        g.fill(x, y, x + w, y + 26, 0xF2181822);
        ring(g, x, y, w, 26, COLOR_ANALYZE);
        g.drawString(this.font, label, x + 5, y + 3, 0xFF8A8AA0, false);
        String shown = renameBuf;
        int avail = w - 10;
        String draw = this.font.width(shown) <= avail ? shown : cut(shown, avail);
        g.drawString(this.font, draw, x + 5, y + 13, 0xFFE0E0EC, false);
        if ((LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = x + 5 + Math.min(this.font.width(shown), avail);
            g.fill(cx, y + 12, cx + 1, y + 21, 0xFFE0E0EC);
        }
        g.drawString(this.font, tr("logiscope.rename.hint"), x + w + 6, y + 13, 0xFF9099A8, false);
    }

    // ==================================================================
    // 悬浮详情（第十三轮⑤）：+N / data xN / 槽列表 —— 鼠标悬浮出完整列表
    // ==================================================================

    /**
     * 解析抓起到的 NBT，判断这是不是"NBT 自带 slot 号"的容器（第十六轮）。
     * 判定纯逻辑在 {@link NbtSlotLayout}（离线有断言），这里只做缓存与状态更新。
     */
    private void parseNbtSlotLayout() {
        String text = DataCommand.text();
        if (text == null || text.equals(nbtSlotParsedFrom)) {
            return;
        }
        nbtSlotParsedFrom = text;
        NbtSlotLayout.Layout layout = NbtSlotLayout.detect(Snbt.parse(text));
        nbtSlotDetected = layout != null;
        nbtSlotListPath = layout == null ? "" : layout.listPath();
        nbtSlotEntries = layout == null ? List.of() : layout.entries();
        if (!nbtSlotDetected) {
            nbtSlotMode = false;   // 检测不到就谈不上开启
        }
    }

    /** 探针详情标题：与面板上的 "槽 … · 数据 xN" 呼应 */
    private String probeTipTitle(ProbeInstance p) {
        return String.format(tr("logiscope.tip.probe_title"),
                p.dataTargets().size(), p.target().slots().size());
    }

    /**
     * 探针的**展开详情**：先列追踪的 NBT 键，再列每个已选格子（含当前内容）。
     * 三个地方都用它（根页探针行 / 选格页第一行 / 图表页第一行），
     * 免得"同一个东西三处显示不一样"。
     */
    private List<String> probeTipLines(ProbeInstance p) {
        List<String> lines = new ArrayList<>();
        for (DataTarget dt : p.dataTargets()) {
            lines.add(tr("logiscope.tip.line_data") + "  " + dt.displayValue()
                    + (dt.hasNamePath() ? "  ← " + dt.namePath() : ""));
        }
        for (ProbeTarget.SlotRef ref : p.target().slots()) {
            String item = ref.itemId() == null || ref.itemId().isBlank()
                    ? tr("logiscope.slot.empty")
                    : ChartRenderer.label(-1, ref.itemId()) + " x" + ref.count();
            lines.add(tr("logiscope.tip.slot").replace("%s", String.valueOf(ref.menuSlotIndex()))
                    + "  ·  " + item);
        }
        return lines;
    }

    /** 本帧请求一条悬浮详情（后调用者覆盖先调用者；调用方自己判鼠标是否在上面） */
    private void wantTip(int key, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        tipKey = key;
        tipTitle = title;
        tipLines = lines;
    }

    /**
     * 判定"鼠标现在压着哪个需要展开的东西"，并填好详情内容。
     * 三处来源：图例的 {@code +N}（收起的线）/ 探针行的 {@code 数据 xN}（NBT 键）/
     * 选格页第一行的 {@code 槽 …}（完整格子列表）。
     */
    private void updateHoverTips(Layout L, int mouseX, int mouseY) {
        // --- 1) 图例 +N：列出被收起的那些线 ---
        for (Chip c : L.legend()) {
            if (!c.id().equals("legend_more") || !c.hit(mouseX, mouseY)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Series ser : chartData.series()) {
                boolean shown = false;
                for (Chip lc : L.legend()) {
                    if (lc.id().equals(SERIES_PREFIX + ser.itemId())) {
                        shown = true;
                        break;
                    }
                }
                if (!shown) {
                    lines.add(ChartRenderer.label(chartData.indexOf(ser.itemId()), ser.itemId()));
                }
            }
            wantTip(1, c.label() + " " + tr("logiscope.tip.hidden_series"), lines);
            return;
        }

        // --- 2) 信息区第一行（各页都是"探针描述"）→ 探针详情 ---
        // 描述里那句 "槽 0,1,2+15 · … · 数据 x2" 就是这里展开的
        if (mouseY >= L.infoTop() && mouseY < L.infoTop() + L.infoRows() * ROW_H
                && (mouseY - L.infoTop()) / ROW_H == 0) {
            ProbeInstance p = switch (page) {
                case PICKER -> pickerProbe();
                case DATA -> dataSrcProbe();
                case CHART -> currentProbe();
                default -> null;      // 根页第一行是"探针 N/16"，回放页第一行是存档名
            };
            if (p != null) {
                wantTip(2, probeTipTitle(p), probeTipLines(p));
                return;
            }
        }

        // --- 2.5) AE 模式第二行（黄色提示行）→ 悬浮显示说明（第十六轮⑨）---
        if (page == Page.PICKER && aeMode
                && mouseY >= L.infoTop() + ROW_H && mouseY < L.infoTop() + 2 * ROW_H) {
            wantTip(4, tr("logiscope.picker.ae_mode"),
                    List.of(tr("logiscope.picker.ae_hint")));
            return;
        }

        // --- 3) 根页探针列表行 → 同一个探针详情 ---
        if (page == Page.ROOT && L.listRows() > 0 && mouseY >= L.listTop()
                && mouseY < L.listTop() + L.listRows() * ROW_H) {
            int row = (mouseY - L.listTop()) / ROW_H;
            int idx = scrollRow + row;
            List<ProbeInstance> probes = LogiScopeMod.probes().all();
            if (idx >= 0 && idx < probes.size()) {
                ProbeInstance pr = probes.get(idx);
                List<String> lines = probeTipLines(pr);
                if (!lines.isEmpty()) {
                    wantTip(3, probeTipTitle(pr), lines);
                }
            }
        }
    }

    /** 画详情框（跟着鼠标，夹在面板内；行多时底部给"x / y 滚轮"提示） */
    private void renderTip(GuiGraphics g, Layout L, int mouseX, int mouseY) {
        if (tipLines.isEmpty()) {
            tipRect = null;
            return;
        }
        if (tipKey != tipKeyShown) {
            tipKeyShown = tipKey;
            tipScroll = 0;
        }
        int lineH = 10;
        int maxRows = 12;
        int w = this.font.width(tipTitle) + 10;
        for (String l : tipLines) {
            w = Math.max(w, this.font.width(l) + 10);
        }
        int rows = Math.min(tipLines.size(), maxRows);
        int h = (rows + 1) * lineH + 4 + (tipLines.size() > rows ? lineH : 0);
        int x = mouseX + 10;
        int y = mouseY + 10;
        if (x + w > L.x() + L.w() - 2) {
            x = Math.max(L.x() + 2, mouseX - w - 10);
        }
        if (y + h > L.y() + L.h() - 2) {
            y = Math.max(L.y() + 2, mouseY - h - 10);
        }
        tipScroll = Math.max(0, Math.min(tipScroll, Math.max(0, tipLines.size() - rows)));
        g.fill(x - 2, y - 2, x + w + 2, y + h, 0xF2181822);
        ring(g, x - 2, y - 2, w + 4, h + 2, 0xFF3A3A4A);
        g.drawString(this.font, tipTitle, x + 2, y, COLOR_HILIGHT, false);
        int ly = y + lineH + 1;
        for (int i = tipScroll; i < Math.min(tipLines.size(), tipScroll + rows); i++) {
            g.drawString(this.font, cut(tipLines.get(i), w - 6), x + 2, ly, 0xFFE0E0EC, false);
            ly += lineH;
        }
        if (tipLines.size() > rows) {
            String more = (tipScroll + rows) + " / " + tipLines.size() + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, more, x + 2, y + h - lineH, 0xFF9099A8, false);
        }
        tipBuf[0] = x - 2;
        tipBuf[1] = y - 2;
        tipBuf[2] = w + 4;
        tipBuf[3] = h + 2;
        tipRect = tipBuf;
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
        dataTgtScroll = 0;
        dataNextRequestTick = 0;
        page = Page.DATA;
        DataCommand.request(p.target().dimension(), p.target().pos());
        dataNextRequestTick = LogiScopeMod.probes().currentTick() + DataRefreshTicks;
    }

    /** 每客户端 tick：抓数（实时刷新）+ 解析 */
    private void tickDataPage() {
        DataCommand.tick();
        ProbeInstance src = dataSrcProbe();
        if (src == null) {
            return;
        }
        long now = LogiScopeMod.probes().currentTick();
        if (now >= dataNextRequestTick) {
            DataCommand.request(src.target().dimension(), src.target().pos());
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

    /**
     * 命令原文变了就重新解析成"路径 = 值"（解析失败 → 空表，界面会说明）。
     *
     * ★ 第十二轮①修：重解析**不动滚动位置** ★
     * NBT 里的数值（能量 / 流体量）几乎每秒都在变 → 原文每秒都"变了"；
     * 原先这里顺手把两个滚动位置归零，等于"每秒弹回顶部"，
     * 用户根本没机会点到列表下方的值。滚动只在**进页**时归零（enterDataPage），
     * 之后完全交给用户；选中（dataPath / dataNamePath）按**路径**匹配，
     * 跨刷新天然稳定，不依赖列表对象不变。
     */
    private void reparseData() {
        String text = DataCommand.text();
        if (text == null || text.equals(dataParsedFrom)) {
            return;
        }
        dataParsedFrom = text;
        Snbt.Node root = Snbt.parse(text);
        dataEntries = root == null ? List.of() : Snbt.flatten(root);
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
    }

    private void renderDataPage(GuiGraphics g, Layout L, int mouseX, int mouseY) {
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
                    g.fill(x1, yTop, Math.max(x2, x1 + 2), yTop + 9, COLOR_HILIGHT);
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
                g.drawString(this.font, cut(e.path() + (named ? "  " + tr("logiscope.data.name_mark") : ""),
                        Math.max(20, avail)),
                        L.x() + 8, ry, pathColor, false);
                g.drawString(this.font, value, L.x() + L.w() - 6 - vw, ry, 0xFF9099A8, false);
            }
            int footerY = listTop + Math.min(dataEntries.size(), R.listRows()) * ROW_H;
            String footer = (dataScroll + R.listRows()) + " / " + dataEntries.size()
                    + "  ·  " + tr("logiscope.data.hint.pick");
            g.drawString(this.font, cut(footer, L.w() - 2 * PAD), L.x() + PAD, footerY, 0xFF707070, false);
        }

        // ---- 3) 追踪目标列表（确认添加后出现在这里；只能删，不能改；可滚动）----
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
            int ry = R.tgtTop() + (i + 1) * ROW_H;
            boolean overDel = isIn(mouseX, mouseY, L.x() + L.w() - 4 - DEL_W, ry - 1, DEL_W, ROW_H);
            boolean over = isIn(mouseX, mouseY, L.x() + 4, ry - 1, L.w() - 8, ROW_H);
            if (over) {
                g.fill(L.x() + 4, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            String name = dt.hasNamePath() ? "◂" + dt.namePath()
                    : (dt.fallbackName().equals(dt.valuePath()) ? tr("logiscope.data.auto") : dt.fallbackName());
            String text = dt.displayValue() + "  →  " + name;
            int textW = L.w() - 8 - DEL_W - 6;
            g.drawString(this.font, cut(text, Math.max(20, textW)), L.x() + 8, ry, 0xFFE0E0EC, false);
            g.fill(L.x() + L.w() - 4 - DEL_W, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1,
                    overDel ? 0xFF7A3038 : 0xFF2A2A38);
            g.drawString(this.font, "×", L.x() + L.w() - 4 - DEL_W + 4, ry,
                    overDel ? 0xFFFFD0D0 : 0xFFC0A0A8, false);
        }
        if (dts.size() > R.tgtRows()) {
            String more = (dataTgtScroll + R.tgtRows()) + " / " + dts.size()
                    + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.font, cut(more, L.w() - 2 * PAD),
                    left, R.tgtTop() + (R.tgtRows() + 1) * ROW_H, 0xFF9099A8, false);
        }

        // ---- 4) 虚拟容器：一行两格（值 / 名称）----
        int cellY = R.editTop();
        drawEditCell(g, left, cellY, R.cellW(), tr("logiscope.data.field.value"),
                dataPath, dataFocus == 1, false);
        drawEditCell(g, left + R.cellW() + PAD, cellY, R.cellW(), tr("logiscope.data.field.name"),
                dataName, dataFocus == 2, dataNamePath != null);
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

        // 值列表：**只吃列表行带内的点击**。
        // ★ 第十一轮④修的 bug：原先写的是 `my >= R.listTop()` —— 把列表以下
        //   的一切（包括底部的「开始采样」按钮）全部吞掉，按钮永远点不到。
        int listBottom = R.listTop() + R.listRows() * ROW_H;
        if (my >= R.listTop() && my < listBottom) {
            int row = (my - R.listTop()) / ROW_H;
            int idx = dataScroll + row;
            if (idx < dataEntries.size()) {
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

        // 追踪目标列表：只有行尾的 × 是活的（值与名称**不能**在列表里改，要改就删了重加）
        if (my >= R.tgtTop() && my < R.tgtTop() + (R.tgtRows() + 1) * ROW_H) {
            if (button == 0) {
                int row = (my - R.tgtTop() - ROW_H) / ROW_H;   // 第一行是表头
                ProbeInstance src2 = dataSrcProbe();
                List<DataTarget> dts = src2 == null ? List.of() : src2.dataTargets();
                int idx = dataTgtScroll + row;
                if (row >= 0 && row < R.tgtRows() && idx < dts.size()
                        && isIn(mx, my, L.x() + L.w() - 4 - DEL_W,
                                R.tgtTop() + (row + 1) * ROW_H - 1, DEL_W, ROW_H)) {
                    src2.removeDataTarget(dts.get(idx).valuePath());
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
    }

    /**
     * 「开始采样」：新建一个**数据探针**并按这条路径逐 tick 取值。
     *
     * 与物品探针的入口（选格 → 完成）并列的第二条创建路径：选格页那条走
     * "菜单槽 / ItemHandler 能力"，这条走 "NBT 路径"。两者产出的都是 ProbeInstance，
     * 所以图表 / 框选 / 标记 / 存档 / 计算全部通用。
     */
    /**
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
            String msg = tr("logiscope.flash.data_added").replace("%s", path);
            // ★ 值装不下就当场说清楚 ★（第十六轮⑤）
            // 样本存储是 int（±21 亿）。追一个 2^63 的值（创造能量立方这类）时，
            // 图上会一条线都没有、只有 no-data 在涨 —— 与其让用户对着空图猜原因，
            // 不如在添加的那一刻把数值和上限都摆出来（只提醒、不拦着：值可能以后落回范围内）。
            String warn = outOfRangeHint(path);
            if (warn != null) {
                msg += "  ·  " + warn;
            }
            say(msg);
        } else {
            say(tr("logiscope.flash.data_dup").replace("%s", path));
        }
    }

    /**
     * 这个路径**现在**的值是否已经超出样本存储的可表示范围；超了就返回一句提醒，没超返回 null。
     * 只看添加那一刻的实时值（数据页每秒都在刷新 NBT 原文，`dataEntries` 就是它摊平的结果）。
     */
    @Nullable
    private String outOfRangeHint(String path) {
        for (Snbt.Entry e : dataEntries) {
            if (!e.path().equals(path) || !e.numeric()) {
                continue;
            }
            double v = e.node().number();
            if (Double.isFinite(v) && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                return null;
            }
            return tr("logiscope.flash.data_out_of_range").replace("%s", e.node().raw());
        }
        return null;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static boolean isIn(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 按像素宽度截断，超出加省略号（探测目标那行可能带很长的维度名） */
    private String cut(String s, int maxW) {
        if (this.font.width(s) <= maxW) {
            return s;
        }
        String ell = "...";
        int budget = maxW - this.font.width(ell);
        int i = s.length();
        while (i > 0 && this.font.width(s.substring(0, i)) > budget) {
            i--;
        }
        return s.substring(0, Math.max(0, i)) + ell;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** 带参数的翻译（%s 占位）。注意：数字格式化交给调用方，这里只做文本替换 */
    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
