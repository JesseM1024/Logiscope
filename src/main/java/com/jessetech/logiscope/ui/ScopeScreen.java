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
import com.jessetech.logiscope.analysis.PickerFilter;
import com.jessetech.logiscope.analysis.SlotAlign;
import com.jessetech.logiscope.analysis.Snbt;
import com.jessetech.logiscope.archive.ArchiveData;
import com.jessetech.logiscope.archive.Archives;
import com.jessetech.logiscope.bind.ContainerAnchor;
import com.jessetech.logiscope.bind.DataCommand;
import com.jessetech.logiscope.bind.DataTarget;
import com.jessetech.logiscope.bind.NameText;
import com.jessetech.logiscope.bind.ProbeContext;
import com.jessetech.logiscope.bind.ProbeTarget;
import com.jessetech.logiscope.bind.RsKeys;
import com.jessetech.logiscope.collect.ContainerAccess;
import com.jessetech.logiscope.collect.ProbeInstance;
import com.jessetech.logiscope.collect.ProbeManager;
import com.jessetech.logiscope.collect.ProbeSlots;
import com.jessetech.logiscope.store.Sample;
import net.minecraft.util.Util;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraft.util.text.TextComponentTranslation;
import com.jessetech.logiscope.compat.Dim;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.tileentity.TileEntity;
import com.jessetech.logiscope.collect.RsNetworkReader;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 呈现层：示波器面板。**三个页面共用一个浮层**。
 *
 * 它是一个"可以拖动、可以缩放、不遮挡原界面"的浮层，不是一个全屏界面 *
 * 实现方式是 NeoForge 的 GUI 图层栈：
 *   ClientHooks.pushGuiLayer(mc, this)   —— 把当前容器界面压进栈里当背景，自己当顶层
 *   ClientHooks.popGuiLayer(mc)          —— 弹回容器界面
 *
 * 核实要点（参见 docs/ARCHITECTURE.md CP8；勿与 HUD 图层系统混淆）：
 * ClientHooks 里的 guiLayers 是货真价实的 {@code Stack<GuiScreen>}，
 * 源码注释写着 "Contains the *extra* GUI layers. The current top layer stays in
 * Minecraft#currentScreen, and the rest serve as a background for it."
 * 渲染时 ClientHooks#drawScreen 会先把栈里的每一层画一遍（鼠标坐标传 MAX_VALUE），
 * 再画最顶层 —— 所以被压住的容器界面**仍然会被完整绘制**，这才是"不覆盖"的来源。
 *
 * ==================================================================
 * 页面结构（需求 1 / 2 / 3）
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
public final class ScopeScreen extends GuiScreen {

    /** 页面（REPLAY = 存档回放 G5；DATA = NBT 取数探针） */
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
    /** 列表行里「高亮追踪目标」按钮的宽度（十字准星图标，紧挨在删除按钮左边） */
    private static final int HL_W = 11;

    private static final String SERIES_PREFIX = "series:";
    /** 选格页「忽略空槽」按钮（占筛选行右端，不进 Layout.buttons） */
    private static final String PICKER_IGNORE_EMPTY = "picker_ignore_empty";
    /** 「分析」选中曲线 / 波形标记的品红系（同一语汇：都是"用户钉在这里的东西"） */
    private static final int COLOR_ANALYZE = 0xFFE070D8;
    /** NBT 原文悬浮高亮：黄（实测反馈淡紫在深底上难认；黄与深底对比最强） */
    private static final int COLOR_HILIGHT = 0x80FFD24A;
    /** 「按数值读取」（DATA 页右键填入值格）的黄标 —— 值格右端点的小方格（同悬浮高亮的黄） */
    private static final int COLOR_FORCED = 0xFFFFD24A;
    /** QoL：「主界面」按钮淡绿 / 「回选格」淡蓝 —— 也用作顶栏光效的页色 */
    private static final int COLOR_MAINSCREEN = 0xFF90EE90;
    private static final int COLOR_BACKSLOT = 0xFF8FB8E8;
    private static final int COLOR_CHART_GLOW = 0xFFFFE08A;
    private static final int COLOR_CALC_GLOW = 0xFFC0A0F0;
    /**
     * DATA 页刷新滑条的档位数：idx 0..39 = 每 1..40 t 抓一次，
     * idx 40（最右档）= 不刷新。档位值存 `LogiScopeConfig.dataRefreshTicks`
     * （整个游戏运行实例一份，不随探针隔离；滑条与 config 是同一个值）。
     */
    private static final int RATE_STOPS = 40;

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
     * IO 不再是图例条上独立的子格，而是**并入物品图例**。点物品图例 → 弹出「数量 / IO」两项的小菜单，两项分别控制存量线与 IO 线。
     * 为什么改成弹窗：图例条是流式换行的色块带，"父格 + IO 子格"成对出现
     * 让条目数翻倍（截图实测 9 种物品挤满 3 行上限），而且 IO 子格和物品格
     * 长得几乎一样，层级关系要靠猜。弹窗把"每个物品有两层开关"这件事
     * 收进第二次点击里，图例条本身永远一行一个物品。
     *
     * 它是**瞬态 UI 状态**（不是用户偏好），所以是实例字段，重开面板即复位。
     */
    @Nullable
    private String legendPopup;

    // ---- 存档回放（REPLAY 页， / G5）----
    /** 当前载入的存档；null = 没在回放。回放数据在打开面板期间驻留（存档读盘一次，之后全内存） */
    @Nullable
    private ArchiveData replayArchive;
    /** 回放的波形标记：内存里编辑（加/删），不自动写回存档文件（重导出才会带上） */
    private final List<Long> replayMarkers = new ArrayList<>();
    /**
     * 回放页里被「分析」选中的曲线（可扩展点）。
     *
     * 需求原文：回放逻辑要有可扩展性，"例如我可能需要选中某一个曲线，
     * 进行下一步的数学计算操作"。本次只把**选中**这件事做出来（图例弹窗里的
     * 「分析」一项，选中后图例块描品红边），后续的数学运算（差值 / 滤波 / 对比）
     * 都挂在这个字段上往前叠，不用再动采集与存储层。
     */
    @Nullable
    private String replaySelectedItem;

    // ---- 存档重命名----
    /** 当前打开存档的显示名（可被重命名覆盖）与目录名（身份标识，永不改） */
    private String replayName = "";
    private String replayDir = "";
    /** 重命名输入框：是否激活 + 缓冲区（激活时吃掉键盘，Enter 确认 / Esc 取消） */
    private boolean renameFocus;
    private String renameBuf = "";

    // ---- 悬浮详情：长列表用滚轮翻，避免"只有个 +N 看不出是啥" ----
    private String tipTitle = "";
    private List<String> tipLines = java.util.Collections.emptyList();
    private int tipScroll;
    private int tipKey;
    private int tipKeyShown;
    private final int[] tipBuf = new int[4];
    @Nullable
    private int[] tipRect;

    // ---- NBT 槽位型容器：slot 号写在 NBT 自己身上，按 slot 监测才准 ----
    /** 检测到"数据里带 slot 号的物品列表"（只决定按钮能不能点） */
    private boolean nbtSlotDetected;
    /** 用户**显式开启**了 NBT 追踪（默认关 —— 避免误判机械手这类设备） */
    private boolean nbtSlotMode;
    /** 检测到的物品列表路径（如 Items） */
    private String nbtSlotListPath = "";
    /** 检测到的元素（slot / 物品 / 数量），用于"按内容反查 slot 号" */
    private List<NbtSlotLayout.Entry> nbtSlotEntries = java.util.Collections.emptyList();
    /** AE 网络模式：菜单是 AE 的 ME 存储界面家族时启用 —— 点格 = 选"网络中的物品" */
    private boolean aeMode;
    /**
     * RS（精致存储）网络模式：菜单是 RS 的网格界面家族时启用。
     *
     * 与 {@link #aeMode} **并列而不是合并**：两者的读值链、身份格式、行来源都不同，
     * 合成一个布尔就必须再带一个"是哪一家"的枚举。两个布尔 + {@link #netMode()}
     * 让"网络类语义"（选中/提交/禁选容器格）只写一次。
     */
    private boolean rsMode;
    /** AE 模式选中的物品 registry id（覆写语义：「完成」= 替换全部 AE 目标） */
    private final Set<String> selectedNetIds = new LinkedHashSet<>();
    private String nbtSlotParsedFrom = "";
    private long nbtSlotNextRequest;
    /**
     * "还没把已有锚点回填到界面上"。
     * 进选格页时数据可能还没抓到，回填会失败 —— 所以**每次 tick 再试**，
     * 一抓到就回填（否则「完成」= 覆写 会把用户看不见的目标全清掉）。
     */
    private boolean nbtSeedPending;

    // ---- 回放计算----
    /** 运算选择（工具栏「运算」弹窗）。OP_* 常量见下 */
    private int analyzeOp = OP_NONE;
    /** 计算对象：false = 数量（存量线），true = IO（净流量线）。**一次只算一个** */
    private boolean analyzeIo;
    /** 「运算」弹窗是否开着 */
    private boolean opPopupOpen;

    // ---- NBT 取数探针----
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
    /**
     * 值是不是**右键按数值读取**来的：字符串值会被尝试解析成数值
     * （见 {@code DataProbe#asValForced}）。值格右端点的黄标显示这个口径；
     * 手改路径（手输 / 粘贴 / 退格）或左键取值都会清掉它（回到自动口径）。
     */
    private boolean dataForceNumeric;
    /** 当前聚焦的输入格：0 = 没有，1 = 值路径，2 = 名称 */
    private int dataFocus;
    private int dataScroll;
    private int dataTextScroll;
    /** 追踪目标列表的滚动位置（目标可以攒很多，固定两三行放不下） */
    private int dataTgtScroll;
    /** `data get block` 解析出来的"路径 = 值"列表 */
    private List<Snbt.Entry> dataEntries = java.util.Collections.emptyList();
    /** 上一次解析用的原文（变了才重解析） */
    private String dataParsedFrom = "";
    /** 自动刷新：下一次发命令的客户端 tick。速率 = `LogiScopeConfig.dataRefreshTicks`（0 = 不刷新） */
    private long dataNextRequestTick;
    /** 刷新滑条是否正被拖动：按住后鼠标拖动改档位，松手结束 */
    private boolean dataSliderDrag;
    /** 命令原文按当前宽度折行后的结果（缓存：宽度或原文变了才重算） */
    private List<String> dataTextLines = java.util.Collections.emptyList();
    /** 与 {@link #dataTextLines} 一一对应的**源文本起始偏移**（悬浮高亮按它定位） */
    private List<Integer> dataTextStarts = java.util.Collections.emptyList();
    private String dataTextCachedFrom = "";
    private int dataTextCachedWidth = -1;
    /** 计算结果（框选 + 曲线 + 对象 + 运算 四元组变化时才重算，其余帧直接用缓存） */
    private String analyzeTitle = "";
    private List<String> analyzeLines = java.util.Collections.emptyList();
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
        switch (op) {
            case OP_EXTREMES:
                return "logiscope.calc.op.extremes";
            case OP_STATS:
                return "logiscope.calc.op.stats";
            case OP_TOTAL:
                return "logiscope.calc.op.total";
            case OP_EVENTS:
                return "logiscope.calc.op.events";
            case OP_RISE_EVENTS:
                return "logiscope.calc.op.rise_events";
            case OP_FALL_EVENTS:
                return "logiscope.calc.op.fall_events";
            default:
                return "logiscope.calc.op.none";
        }
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
                w = Math.max(w, this.fontRenderer.getStringWidth(tr(opKey(op))) + 30);
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
    private void renderOpPopup(Draw g, Layout L, int mouseX, int mouseY) {
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
            g.drawString(this.fontRenderer, label, pr[0] + 16, ry + 2,
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
     * 这就是早期"多按几次才动"的根因。attach / 清空 / 全览时复位。
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
    private List<Archives.Info> archiveList = java.util.Collections.emptyList();
    /** 存档筛选框的文字：非空时存档列表只显示名称/文件夹包含它的条目 */
    private String archFilter = "";
    /** 筛选框是否有键盘焦点（聚焦时按键进框；Esc 清空并收起，Enter 保留并收起） */
    private boolean archFilterFocus;
    /**
     * 已"待确认删除"的存档目录名（空 = 没有待删项）。
     *
     * 存档是**用户的文件**，误删无法撤销（不像探针只是内存实例），所以做成两段式：
     * 第一下只把那一行的 × 变成红色「!」并提示"再点一次"，第二下同一个按钮才真删；
     * 期间点任何别的地方（含别的存档行 / 别的按钮）都会把它清掉。
     */
    private String archiveDeleteArmed = "";
    /** 选格页筛选框的文字（规则见 {@link PickerFilter}） */
    private String pickerFilter = "";
    /** 选格页筛选框是否有键盘焦点（语义同 archFilterFocus） */
    private boolean pickerFilterFocus;
    /**
     * 选格页「忽略空槽」开关：静态字段（看列表的偏好，跨开关面板保持；进程启动默认 false）。
     * 与筛选框叠加：先剔空槽再按文字筛。
     */
    private static boolean pickerIgnoreEmpty;

    private final ChartRenderer chart = new ChartRenderer();

    // 派生数据的缓存：只在 SampleStore 的 version 变化后重算（见 SampleStore#version）
    private ChartData chartData = ChartData.EMPTY;
    private Analyzer.Summary summary = Analyzer.Summary.EMPTY;
    private List<ChartData.SeriesRange> selectionStats = java.util.Collections.emptyList();
    private long builtVersion = Long.MIN_VALUE;
    private int builtProbeId = Integer.MIN_VALUE;

    /** 本帧鼠标悬停到的「背景容器槽位」。render 开头刷新，末尾用它画悬浮提示 */
    @Nullable
    private Slot backgroundHoveredSlot;

    /** 这一 tick 的清单快照（选格页用） */
    private List<ContainerAccess.SlotView> menuSlots = java.util.Collections.emptyList();
    private List<ContainerAccess.SlotView> worldSlots = java.util.Collections.emptyList();
    private Source source = Source.NONE;
    /**
     * 这一 tick 有没有"可探测的容器"（决定「＋ 创建探针」能不能点）。
     *
     * 为什么要缓存成字段，而不是在布局里现算 *   `ProbeContext.current` 里要做方块实体查询 + 最多 7 次 capability 查找，
     *   而 `layout()` 每帧会被调用好几次（render 一次，鼠标事件各一次）。
     *   每 tick 算一次就够，反正创建探针是用户点按钮时才发生的。
     */
    private boolean hasTarget;

    private String flash = "";
    private long flashUntil;
    /** 提示折行后的行（按 {@link #flash} 缓存：文本没变就不重算） */
    private List<String> flashLines = java.util.Collections.emptyList();
    private String flashWrappedFrom = null;

    @Nullable
    private final GuiScreen parent;
    private final boolean overlayMode;
    /** 关闭动作只发一次（防"关闭 → 被通知关闭 → 再关闭"的无限递归，见 closeScope） */
    private boolean closing;

    public ScopeScreen(@Nullable GuiScreen parent, Page page, int probeId) {
        // 1.12.2 的 GuiScreen 没有"标题"参数（那是 1.13+ 的 Screen 才有的）
        super();
        this.parent = parent;
        this.overlayMode = parent != null;
        this.page = page;
        this.pickerProbeId = probeId;
    }

    // ==================================================================
    // 打开 / 关闭
    // ==================================================================

    /**
     * 在当前容器界面上"压一层"打开 —— **不换 `mc.currentScreen`**（★ 1.12.2 的真图层）。
     *
     * 【为什么不能换屏幕：AE 的网络更新包只发给"当前界面"】
     * `PacketMEInventoryUpdate#clientPacketData` 的实际逻辑（javap rv6-stable-7）：
     * <pre>
     * Minecraft mc = Minecraft.getMinecraft();
     * if (mc.currentScreen instanceof GuiCraftConfirm) ...
     * else if (mc.currentScreen instanceof GuiMEMonitorable) ((GuiMEMonitorable) mc.currentScreen).postUpdate(list);
     * </pre>
     * 也就是说：**当前界面不是那个终端 GUI，包到了客户端就直接被丢掉** ——
     * 终端显示冻结、探针读到的也永远是旧值（实测：漏斗往网络里灌，数字不动，
     * 直到关掉面板。`parent.updateScreen()` 转发**救不了**，因为 `updateView()`
     * 根本不在 `updateScreen` 里，而在 `postUpdate` 里）。
     *
     * 所以这一版把面板做成"真图层"：
     *   · `mc.currentScreen` **保持是父界面** —— 容器的 tick、AE 的包、JEI 的布局全都正常；
     *   · 面板在 `DrawScreenEvent.Post`（LOWEST）里画 → 永远在最上层；
     *   · 输入靠 `GuiScreenEvent.MouseInputEvent/KeyboardInputEvent.Pre` 拦（见 {@link PanelInput}），
     *     落在面板上的事件被吃掉，落在面板外的**照常交给容器**（这是顺带修好的老限制）。
     *
     * 1.12.2 没有 `pushGuiLayer`，这也是"压层"唯一不伤父界面的做法。
     */
    public static void openOverlay(Minecraft mc, Page page, int probeId) {
        if (mc.currentScreen == null) {
            // 没有可压的父界面：直接独立模式，两条路径各自自洽
            openStandalone(mc, page, probeId);
            return;
        }
        GuiScreen bg = mc.currentScreen;
        ScopeScreen scope = new ScopeScreen(bg, page, probeId);
        PanelPainter.ensureRegistered();
        PanelInput.ensureRegistered();
        // 只为拿到 width/height/fontRenderer（顺带跑一次 initGui 里的面板归位）；
        // 它**不会**改 mc.currentScreen。
        scope.setWorldAndResolution(mc, bg.width, bg.height);
        ACTIVE = scope;
        LogiScopeMod.LOGGER.debug("[LogiScope] 压浮层(真图层：父界面仍是 currentScreen) page={} parent={}",
                page, bg.getClass().getSimpleName());
    }

    /** 没有容器界面可压时，当普通界面打开（例如关掉界面后只看波形） */
    public static void openStandalone(Minecraft mc, Page page, int probeId) {
        GuiScreen bg = mc.currentScreen;
        PanelPainter.ensureRegistered();
        ScopeScreen scope = new ScopeScreen(null, page, probeId);
        ACTIVE = scope;
        mc.displayGuiScreen(scope);
        // ★ 1.16.5：displayGuiScreen 对"旧屏"会调 onClose()，所以这里会顺手把旧界面关掉
        //   （箱子界面被关就是这么来的）。正常路径不该走到这里 —— 有容器界面时走 openOverlay。
        LogiScopeMod.LOGGER.debug("[LogiScope] 独立打开 page={} 旧屏={}（旧屏将被 onClose 关闭）",
                page, bg == null ? "null" : bg.getClass().getSimpleName());
    }

    /**
     * 准星指着"有容器方块实体但没有 GUI"的设备时（需求 3）：
     * 直接新建一个绑定到它的探针实例，并进入选格页 —— 没有菜单，所以走列表选格。
     */
    public static void openWorldCreate(Minecraft mc, Dim dim, BlockPos pos, String label) {
        ProbeManager mgr = LogiScopeMod.probes();
        ProbeInstance created = mgr.create(new ProbeTarget(java.util.Collections.emptyList(), label, dim, pos));
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

    /** 关闭面板：浮层模式只是"摘掉这一层"（父界面一直是 currentScreen），独立模式回到游戏 */
    public void closeScope() {
        // 重入保护：关闭链上任何一环再发起关闭都会无限递归（实测 StackOverflow 闪退）
        if (closing) {
            return;
        }
        closing = true;
        Minecraft mc = Minecraft.getMinecraft();
        LogiScopeMod.LOGGER.debug("[LogiScope] 关闭面板 overlay={}", overlayMode);
        if (overlayMode && parent != null) {
            // ★ 真图层：**什么都不用还给父界面** —— 它本来就是 mc.currentScreen，
            //   也从没被 onGuiClosed/closePlayerScreen 碰过。只要把这一层摘掉。
            if (ACTIVE == this) {
                ACTIVE = null;
            }
        } else {
            // 独立模式：正常关屏（会重新抓鼠标）
            if (ACTIVE == this) {
                ACTIVE = null;
            }
            mc.displayGuiScreen(null);
        }
    }

    // ------------------------------------------------------------------
    // 真图层：面板实例表（同一时刻只可能有一个浮层面板）
    // ------------------------------------------------------------------

    /** 当前压着的面板；null = 没开。**输入与绘制都靠它定位**。 */
    private static ScopeScreen ACTIVE;

    /** 由 {@link PanelPainter} / {@link PanelInput} / {@link KeyBindings} 查 */
    @org.jetbrains.annotations.Nullable
    public static ScopeScreen activePanel() {
        return ACTIVE;
    }

    /** 是不是"压在容器界面上"的浮层（false = 独立屏，那种情况我们就是 currentScreen） */
    boolean isOverlay() {
        return overlayMode;
    }

    /**
     * 这一帧应该挂在**哪个界面**的 `DrawScreenEvent.Post` 上画：
     * 浮层模式 = 父界面（它是 currentScreen）；独立模式 = 我们自己。
     */
    GuiScreen renderTarget() {
        return overlayMode && parent != null ? parent : this;
    }

    /** 这个坐标是否落在面板矩形里（输入分流的判据） */
    boolean panelContains(int mouseX, int mouseY) {
        return overPanel(mouseX, mouseY, layout());
    }

    /**
     * 面板是否正在"拖东西"（拖动 / 缩放 / 框选 / 滑条 / 拖选格子）。
     * 是的话，面板外的鼠标移动与松手也必须归我们 —— 否则拖到面板外就断了。
     */
    boolean panelDragging() {
        return dragging || resizing || selecting || dataSliderDrag || cellDragButton >= 0;
    }

    /**
     * 面板外的点击要不要**也吃掉**。
     *
     * 默认为 false（放行给容器 —— 面板开着也能拿放物品）。
     * 但**选格模式必须吃掉**：面板外就是容器格子，放行的话一次点击等于"拿起来/放下去"，
     * 选格逻辑就彻底失效了（用户 2026-10-22 的反馈第 1 条）。
     */
    boolean panelBlocksOuterClicks() {
        return cellMode;
    }

    /** 面板是否正在吃键盘（有输入框聚焦） */
    boolean panelWantsKeys() {
        return renameFocus || archFilterFocus || pickerFilterFocus || dataFocus != 0;
    }

    // ---- 输入适配：把 Forge 的 GUI 输入事件翻译成既有的 handleXxx ----

    boolean feedMouseDown(int mouseX, int mouseY, int button) {
        return handleClick(mouseX, mouseY, button);
    }

    boolean feedMouseUp(int mouseX, int mouseY, int button) {
        return handleRelease(mouseX, mouseY, button);
    }

    boolean feedMouseMove(int mouseX, int mouseY, int button) {
        return handleDrag(mouseX, mouseY, button);
    }

    boolean feedScroll(int mouseX, int mouseY, int dir) {
        return handleScroll(mouseX, mouseY, dir);
    }

    boolean feedChar(char ch) {
        return handleCharTyped(ch);
    }

    boolean feedKey(int keyCode) {
        return handleKeyPressed(keyCode);
    }

    /**
     * ★ 可视化选格（在容器界面上直接框格子）在本线**暂时关闭**。
     *
     * 实机症状（1.12.2，多轮复现）：一开 Pick cells，面板就整片变成纯灰块；
     * 已经排除了深度（`beginPanel` 关深度）、不透明底、事件优先级（`LOWEST`）、
     * 假屏幕换层（现已改成真图层）这几条，仍会在**进入 cellMode 的那一帧**出现。
     * 唯一只在 cellMode 里发生、且发生在面板自身填充**之前**的动作是
     * `renderCellOverlay → repaintSlotItem → Draw#renderItem`（在面板还没画的时候先画了物品）。
     *
     * 用户决定：**先把这个功能关掉**（按钮置灰 + 悬浮说明），保留"按序号勾选"这条路 ——
     * 功能不丢，只是不能直接在容器界面上点格子。
     * 以后要重开：把这里改成 true 即可，同时记得把 {@link PanelInput} 里
     * "cellMode 时不吃面板外点击"的那条保留（否则点面板外会拿放物品，选格就废了）。
     */
    private static final boolean VISUAL_CELL_PICK = false;

    /**
     * ★ 1.12.2 只有一个关闭钩子：{@code onGuiClosed()}。
     *
     * 与 1.16.5 不同，这一代"被通知关闭"和"用户按 ESC"是**同一个**回调，
     * 所以这里绝不能调 {@link #closeScope()}（会变成关闭→回调→再关闭的无限递归）。
     */
    @Override
    public void onGuiClosed() {
        // 只做收尾（保存输入、清焦点）。当前没有这类状态。
    }

    @Override
    public void initGui() {
        if (panelX == Integer.MIN_VALUE) {
            panelX = Math.max(2, this.width - panelW - 6);
            panelY = 6;
        }
        // 每次打开都做一次"收回屏幕内"// 拖动/缩放时是允许把面板推出屏幕外的（大 GUI scale 下有用，见 clampDuringDrag），
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
        // 存档清单每次**打开面板**扫一次。注释修正// 旧注释写"只读各存档的 JSON 头，几 KB 级" —— 名实不符：readHeader 当时是整份
        // 解析（几万条样本全部建成对象）。现在它改成流式读（只取头部、samples 整段跳过），
        // 那句说法才真正成立。另外 vanilla 的 GuiScreen#resize 只走 rebuildWidgets()、
        // **不会**重跑 init()，所以拖动窗口不会反复扫盘（原先担心的那条不成立）。
        archiveList = Archives.scan();

        if (pendingFlashKey != null) {
            say(tr(pendingFlashKey));
            pendingFlashKey = null;
        }
    }

    /** 单机也不暂停游戏 —— 否则 tick 停了，采样也就停了 */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        // 浮层模式下我们不是 currentScreen ⇒ 这个方法是 **PanelInput 从客户端 tick 里调的**
        // （vanilla 只会 tick 当前界面 = 父容器，而那种"替父界面 tick"从一开始就跑不通：
        //  AE 的网络更新包走的是 `currentScreen instanceof GuiMEMonitorable` 的投递，
        //  在 `updateScreen` 里怎么转发都补不回来。见 openOverlay 的注释。）
        refreshSnapshots();
        if (page == Page.DATA) {
            tickDataPage();
        }
        if (page == Page.PICKER) {
            ProbeInstance p = LogiScopeMod.probes().find(pickerProbeId);
            if (p != null && p.target().hasPersistentId()) {
                ProbeSlots.request(p.target().dimension(), p.target().pos());
                // NBT 槽位型容器的检测// 顺手把方块 NBT 抓一次（单人 = 服务端直读，不花权限），解析出
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
        // RS 1.6 的清单挂在**界面**上（IGridView），不在菜单里 —— 所以这一条要在
        // ContainerAccess 之前判：那个方法对 RS 菜单只返回空表（它的槽位是合成格）。
        List<RsKeys.Row> rsRows = RsKeys.rows(parent);
        menuSlots = rsRows != null ? ContainerAccess.fromRsRows(rsRows) : ContainerAccess.snapshot();
        worldSlots = ProbeSlots.slots();
        source = resolveSource();
        hasTarget = ProbeContext.current(Minecraft.getMinecraft()) != null;
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
        // 1.12.2：锚点用 Container 的**对象身份**匹配（windowId 在 InitGuiEvent 时还没赋值）
        ContainerAnchor.Anchor a = ContainerAnchor.activeFor(ContainerAccess.currentMenu());
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
     * 修的 bug：只有前两条路会回填"已选格子" * 实测反馈：选完格开始测量 → 退出界面 → 再打开面板 → 进选格页，
     * 显示的是"已选 0 格"。根因就是「图表页点选格」没走回填那条路。
     * 现在统一从这里进，回填的去重（{@link #seededForProbeId}）保证
     * 不会把用户正在改的选择冲掉。
     */
    private void enterPicker(ProbeInstance p) {
        pickerProbeId = p.id();
        // AE 模式检测必须在这里、每次进入都判// 不能放进 seedSelectionOnce：createProbe 会**先**打上 seededForProbeId 预标记，
        // 首次进入时 seed 直接早退 —— 检测就永远不跑。
        // 判据 = 当前菜单 ∈ ME 存储家族；纯 AE 探针（无槽位 + 有 AE 目标）即使在
        // GUI 关闭的状态下回选格页，也保持 AE 模式（否则勾选状态无法显示）。
        // 本线不做 AE（1.12.2 的 AE2 是 rv6，另一套 API）⇒ 菜单永远不算 AE 终端。
        // AE 模式的界面与数据通路保留着，等将来接上读值链即可复用。
        boolean menuIsAe = false;
        boolean menuIsRs = RsKeys.isRsGridMenu(ContainerAccess.currentMenu());
        boolean pureAeProbe = p.target().slots().isEmpty()
                && p.dataTargets().stream().anyMatch(DataTarget::isAeNetwork);
        boolean pureRsProbe = p.target().slots().isEmpty()
                && p.dataTargets().stream().anyMatch(DataTarget::isRsNetwork);
        aeMode = menuIsAe || (pureAeProbe && !menuIsRs);
        rsMode = menuIsRs || (pureRsProbe && !menuIsAe);
        // 选择集 = 探针现有的网络目标（覆写语义的不变量：界面上勾的就是探针在测的）。
        // 每次进入都重建 —— 用户没 Done 就离开的编辑不保留（与槽位选择不同，但一致于覆写）。
        selectedNetIds.clear();
        for (DataTarget d : p.dataTargets()) {
            if ((aeMode && d.isAeNetwork()) || (rsMode && d.isRsNetwork())) {
                selectedNetIds.add(d.literalName());
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
        GuiContainer bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        Set<Integer> derived = new LinkedHashSet<>();
        for (Slot slot : bg.inventorySlots.inventorySlots) {
            if (selMenuSlots.contains(slot.slotNumber) && ContainerAccess.isSelectable(slot)) {
                derived.add(slot.getSlotIndex());
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
    private GuiContainer backgroundContainer() {
        return parent instanceof GuiContainer ? (GuiContainer) parent : null;
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
        for (String id : new java.util.ArrayList<>(hiddenItems)) {
            if (chartData.byItem(id) == null) {
                hiddenItems.remove(id);
            }
        }
        for (String id : new java.util.ArrayList<>(ioShown)) {
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
        selectionStats = selection == null ? java.util.Collections.emptyList() : chartData.ranges(selection.lo(), selection.hi());
    }

    // ==================================================================
    // 布局：渲染与命中判定共用这一份
    // ==================================================================

    private static final class Item {
        private final String id;
        private final String label;
        private final boolean enabled;

        public Item(String id, String label, boolean enabled) {
            this.id = id;
            this.label = label;
            this.enabled = enabled;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public boolean enabled() {
            return enabled;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Item other = (Item) o;
            return java.util.Objects.equals(this.id, other.id) && java.util.Objects.equals(this.label, other.label) && java.util.Objects.equals(this.enabled, other.enabled);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, label, enabled);
        }

        @Override
        public String toString() {
            return "Item[" + "id=" + id + ", " + "label=" + label + ", " + "enabled=" + enabled + "]";
        }

    }

    private static final class Chip {
        private final String id;
        private final String label;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final boolean enabled;
        private final int accent;

        public Chip(String id, String label, int x, int y, int w, int h, boolean enabled, int accent) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.enabled = enabled;
            this.accent = accent;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int w() {
            return w;
        }

        public int h() {
            return h;
        }

        public boolean enabled() {
            return enabled;
        }

        public int accent() {
            return accent;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Chip other = (Chip) o;
            return java.util.Objects.equals(this.id, other.id) && java.util.Objects.equals(this.label, other.label) && java.util.Objects.equals(this.x, other.x) && java.util.Objects.equals(this.y, other.y) && java.util.Objects.equals(this.w, other.w) && java.util.Objects.equals(this.h, other.h) && java.util.Objects.equals(this.enabled, other.enabled) && java.util.Objects.equals(this.accent, other.accent);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, label, x, y, w, h, enabled, accent);
        }

        @Override
        public String toString() {
            return "Chip[" + "id=" + id + ", " + "label=" + label + ", " + "x=" + x + ", " + "y=" + y + ", " + "w=" + w + ", " + "h=" + h + ", " + "enabled=" + enabled + ", " + "accent=" + accent + "]";
        }


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
    private static final class PopupRow {
        private final int kind;
        private final String label;
        private final boolean on;

        public PopupRow(int kind, String label, boolean on) {
            this.kind = kind;
            this.label = label;
            this.on = on;
        }

        public int kind() {
            return kind;
        }

        public String label() {
            return label;
        }

        public boolean on() {
            return on;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PopupRow other = (PopupRow) o;
            return java.util.Objects.equals(this.kind, other.kind) && java.util.Objects.equals(this.label, other.label) && java.util.Objects.equals(this.on, other.on);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(kind, label, on);
        }

        @Override
        public String toString() {
            return "PopupRow[" + "kind=" + kind + ", " + "label=" + label + ", " + "on=" + on + "]";
        }

        static final int QUANTITY = 0;
        static final int IO = 1;
        static final int ANALYZE = 2;
    }

    /**
     * 一帧布局。{@code archTop/archRows} 只有根页非 0：根页下半 = 存档列表（G5）。
     */
    private static final class Layout {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final int infoTop;
        private final int infoRows;
        private final int listTop;
        private final int listRows;
        private final int filterTop;
        private final int filterH;
        private final int archTop;
        private final int archRows;
        private final int chartTop;
        private final int chartH;
        private final List<Chip> toolbar;
        private final List<Chip> legend;
        private final List<Chip> buttons;

        public Layout(int x, int y, int w, int h, int infoTop, int infoRows, int listTop, int listRows, int filterTop, int filterH, int archTop, int archRows, int chartTop, int chartH, List<Chip> toolbar, List<Chip> legend, List<Chip> buttons) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.infoTop = infoTop;
            this.infoRows = infoRows;
            this.listTop = listTop;
            this.listRows = listRows;
            this.filterTop = filterTop;
            this.filterH = filterH;
            this.archTop = archTop;
            this.archRows = archRows;
            this.chartTop = chartTop;
            this.chartH = chartH;
            this.toolbar = toolbar;
            this.legend = legend;
            this.buttons = buttons;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int w() {
            return w;
        }

        public int h() {
            return h;
        }

        public int infoTop() {
            return infoTop;
        }

        public int infoRows() {
            return infoRows;
        }

        public int listTop() {
            return listTop;
        }

        public int listRows() {
            return listRows;
        }

        public int filterTop() {
            return filterTop;
        }

        public int filterH() {
            return filterH;
        }

        public int archTop() {
            return archTop;
        }

        public int archRows() {
            return archRows;
        }

        public int chartTop() {
            return chartTop;
        }

        public int chartH() {
            return chartH;
        }

        public List<Chip> toolbar() {
            return toolbar;
        }

        public List<Chip> legend() {
            return legend;
        }

        public List<Chip> buttons() {
            return buttons;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Layout other = (Layout) o;
            return java.util.Objects.equals(this.x, other.x) && java.util.Objects.equals(this.y, other.y) && java.util.Objects.equals(this.w, other.w) && java.util.Objects.equals(this.h, other.h) && java.util.Objects.equals(this.infoTop, other.infoTop) && java.util.Objects.equals(this.infoRows, other.infoRows) && java.util.Objects.equals(this.listTop, other.listTop) && java.util.Objects.equals(this.listRows, other.listRows) && java.util.Objects.equals(this.archTop, other.archTop) && java.util.Objects.equals(this.archRows, other.archRows) && java.util.Objects.equals(this.chartTop, other.chartTop) && java.util.Objects.equals(this.chartH, other.chartH) && java.util.Objects.equals(this.toolbar, other.toolbar) && java.util.Objects.equals(this.legend, other.legend) && java.util.Objects.equals(this.buttons, other.buttons);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(x, y, w, h, infoTop, infoRows, listTop, listRows, archTop, archRows, chartTop, chartH, toolbar, legend, buttons);
        }

        @Override
        public String toString() {
            return "Layout[" + "x=" + x + ", " + "y=" + y + ", " + "w=" + w + ", " + "h=" + h + ", " + "infoTop=" + infoTop + ", " + "infoRows=" + infoRows + ", " + "listTop=" + listTop + ", " + "listRows=" + listRows + ", " + "archTop=" + archTop + ", " + "archRows=" + archRows + ", " + "chartTop=" + chartTop + ", " + "chartH=" + chartH + ", " + "toolbar=" + toolbar + ", " + "legend=" + legend + ", " + "buttons=" + buttons + "]";
        }


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
        List<Chip> legend = java.util.Collections.emptyList();
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
        List<Chip> toolbar = java.util.Collections.emptyList();
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
        // 选格页：列表顶上让出一条筛选框；没有可筛的清单时不占位
        int filterTop = 0;
        int filterH = 0;
        if (page == Page.PICKER && source != Source.NONE && !pickerList().isEmpty()) {
            filterTop = listTop;
            filterH = CHIP_H;   // 这一行右边是按钮，10px 时文字会压到边框上
            listTop += filterH + 2;
        }
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
                infoTop, infoRows, listTop, listRows, filterTop, filterH, archTop, archRows,
                chartTop, chartH, toolbar, legend, buttons);
    }

    private int infoRows() {
        return page == Page.CHART || page == Page.REPLAY ? 3 : 2;
    }

    // ------------------------------------------------------------------
    // DATA 页（NBT 取数探针）的几何
    // ------------------------------------------------------------------

    /** DATA 页三段式：NBT 原文 / 值列表 / 追踪目标列表 */
    private static final class DataRects {
        private final int textTop;
        private final int textH;
        private final int listTop;
        private final int listRows;
        private final int tgtTop;
        private final int tgtRows;
        private final int editTop;
        private final int cellW;

        public DataRects(int textTop, int textH, int listTop, int listRows, int tgtTop, int tgtRows, int editTop, int cellW) {
            this.textTop = textTop;
            this.textH = textH;
            this.listTop = listTop;
            this.listRows = listRows;
            this.tgtTop = tgtTop;
            this.tgtRows = tgtRows;
            this.editTop = editTop;
            this.cellW = cellW;
        }

        public int textTop() {
            return textTop;
        }

        public int textH() {
            return textH;
        }

        public int listTop() {
            return listTop;
        }

        public int listRows() {
            return listRows;
        }

        public int tgtTop() {
            return tgtTop;
        }

        public int tgtRows() {
            return tgtRows;
        }

        public int editTop() {
            return editTop;
        }

        public int cellW() {
            return cellW;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DataRects other = (DataRects) o;
            return java.util.Objects.equals(this.textTop, other.textTop) && java.util.Objects.equals(this.textH, other.textH) && java.util.Objects.equals(this.listTop, other.listTop) && java.util.Objects.equals(this.listRows, other.listRows) && java.util.Objects.equals(this.tgtTop, other.tgtTop) && java.util.Objects.equals(this.tgtRows, other.tgtRows) && java.util.Objects.equals(this.editTop, other.editTop) && java.util.Objects.equals(this.cellW, other.cellW);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(textTop, textH, listTop, listRows, tgtTop, tgtRows, editTop, cellW);
        }

        @Override
        public String toString() {
            return "DataRects[" + "textTop=" + textTop + ", " + "textH=" + textH + ", " + "listTop=" + listTop + ", " + "listRows=" + listRows + ", " + "tgtTop=" + tgtTop + ", " + "tgtRows=" + tgtRows + ", " + "editTop=" + editTop + ", " + "cellW=" + cellW + "]";
        }

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
            case ROOT:
            {
                ProbeManager mgr = LogiScopeMod.probes();
                out.add(new Item("create", tr("logiscope.btn.create_probe"),
                        !mgr.isFull() && hasTarget));
                // ⑦修订：与 new probe / hide 同一行（原先浮在存档列表下，
                // 实测被 new probe 挡住）
                out.add(new Item("open_folder", tr("logiscope.btn.open_folder"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            break;
            case PICKER:
            {
                boolean menu = source == Source.MENU;
                // NBT 追踪是**显式开关**// 原因（需求原文）：错误地自动启用 NBT 追踪，会让"机械手"这类
                // 形状相似但没有真正 slot 语义的设备**没法按常规路径追踪**。
                // 所以默认关；只有检测到"数据里带 slot 号的物品列表"时才可点。
                ProbeInstance pick = pickerProbe();
                out.add(new Item("nbt_mode",
                        nbtSlotMode ? tr("logiscope.btn.nbt_mode_on") : tr("logiscope.btn.nbt_mode"),
                        pick != null && pick.target().hasPersistentId() && nbtSlotDetected));
                out.add(new Item("cell_mode",
                        cellMode ? tr("logiscope.btn.cell_mode_on") : tr("logiscope.btn.cell_mode"),
                        menu && !netMode() && VISUAL_CELL_PICK));
                out.add(new Item("clear_cells", tr("logiscope.btn.clear_cells"), selectionCount() > 0));
                // 
                ProbeInstance pp = pickerProbe();
                out.add(new Item("probe_data", tr("logiscope.btn.probe_data"),
                        pp != null && pp.target().hasPersistentId()));
                ProbeInstance doneProbe = pickerProbe();
                out.add(new Item("done", tr("logiscope.btn.done"),
                        selectionCount() > 0 || (doneProbe != null && doneProbe.isDataProbe())));
                // 名称写法：选格页 / 图表页 / 计算页都有这一个按钮（三处同源，见 NameText）
                out.add(new Item("name_style", nameStyleLabel(), true));
                out.add(new Item("to_root", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            break;
            case CHART:
            {
                ProbeInstance p = currentProbe();
                boolean running = p != null && (p.state() == ProbeInstance.State.RECORDING
                        || p.state() == ProbeInstance.State.FOLLOWING);
                // ⑦按用户指定顺序：pause → resume → clear → stop & archive
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
                out.add(new Item("name_style", nameStyleLabel(), true));
                out.add(new Item("list", tr("logiscope.btn.to_list"), true));
            }
            break;
            case REPLAY:
            {
                out.add(new Item("rename", tr("logiscope.btn.rename"), replayArchive != null));
                // 直接打开这次回放自己的目录（csv / json 就在里面）
                out.add(new Item("open_archive_folder", tr("logiscope.btn.open_archive_folder"),
                        replayArchive != null && replayDir != null && !replayDir.trim().isEmpty()));
                out.add(new Item("name_style", nameStyleLabel(), true));
                out.add(new Item("replay_back", tr("logiscope.btn.to_list"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            break;
            case DATA:
            {
                ProbeInstance dsrc = dataSrcProbe();
                out.add(new Item("data_refresh", tr("logiscope.btn.data_refresh"), true));
                // 确认添加：把当前 值/路径 + 名称 变成一个追踪目标（可以攒多个）
                out.add(new Item("data_confirm", tr("logiscope.btn.data_confirm"),
                        dsrc != null && !dataPath.trim().isEmpty()));
                // 「完成」：与选格页的 done 同一个 id、同一条处理路径 // 仅追 NBT 的探针不用"回选格再完成"了 —— 在这里直接提交并进图表页。
                // 置灰条件与选格页一致：选了格子、或已经攒了数据目标。
                out.add(new Item("done", tr("logiscope.btn.done"),
                        dsrc != null && (selectionCount() > 0 || dsrc.isDataProbe())));
                out.add(new Item("data_back", tr("logiscope.btn.to_picker"), true));
                out.add(new Item("close", tr("logiscope.btn.close"), true));
            }
            break;
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
            // 回放计算：对象开关 + 运算选择。没选中曲线时置灰
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
     * 图例条目：**一个物品一格**。
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

    /**
     * 「名称」按钮的文字（{@code 名称: 实际名} / {@code 名称: 注册名}）。
     *
     * 三页共用这一份 —— 文案与状态永远同源（{@link NameText#showDisplay()}），
     * 不在三个地方各拼一次串。按钮点下去调 {@code NameText.toggle()}，会立刻写回 config。
     */
    private String nameStyleLabel() {
        return tr("logiscope.btn.name_style",
                tr(NameText.showDisplay() ? "logiscope.name.display" : "logiscope.name.registry"));
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
                w = Math.max(w, this.fontRenderer.getStringWidth(r.label()) + 30);
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
            int w = Math.max(minW, this.fontRenderer.getStringWidth(it.label()) + padW);
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
                int w = Math.max(minW, this.fontRenderer.getStringWidth(it.label()) + padW);
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
        if (PICKER_IGNORE_EMPTY.equals(id)) {
            return pickerIgnoreEmpty ? 0xFF3FE0A0 : 0;
        }
        if ("nbt_mode".equals(id)) {
            // ⑦：NBT tracking ON 时按钮文字转黄色
            return nbtSlotMode ? 0xFFFFD24A : 0;
        }
        if ("to_root".equals(id) || "list".equals(id) || "replay_back".equals(id)) {
            return COLOR_MAINSCREEN;   // 「主界面」淡绿
        }
        if ("pick".equals(id)) {
            return COLOR_BACKSLOT;     // 「回选格」淡蓝
        }
        if ("done".equals(id)) {
            return 0xFFFFD24A;         // 「Done」黄色
        }
        if ("archive".equals(id)) {
            return COLOR_CALC_GLOW;    // 「Stop & archive」淡紫（与计算页光效同色系）
        }
        if ("name_style".equals(id)) {
            // 与 info 行同色：实际名（默认）时淡青，注册名时回到按钮本色
            return NameText.showDisplay() ? 0xFF9BE1CB : 0;
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

    /**
     * 只在**独立模式**下被调用（那时我们才是 `mc.currentScreen`）。
     *
     * 浮层模式下面板不是当前界面 —— 父容器才是，由 vanilla 负责渲染它，
     * 我们只在 `DrawScreenEvent.Post` 里往上叠一层（见 {@link PanelPainter}）。
     * 所以这里没有任何绘制代码：数据刷新与绘制都挪到 {@link #renderPanel} 里了。
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        if (overlayMode && parent != null) {
            // 理论上走不到（浮层模式不会把本屏设成 currentScreen）；留着以防将来改回去
            parent.drawScreen(mouseX, mouseY, partialTick);
        }
        if (!PanelPainter.isReady()) {
            // 兜底：延后绘制没注册上时，宁可自己画（顺序差一点），也不能让面板消失
            renderPanel(mouseX, mouseY, partialTick);
        }
    }

    /**
     * 真正画面板的那一段。**只能由 {@link PanelPainter} 在一帧的 DrawScreenEvent.Post 里调用一次**
     * （父界面已在 {@link #drawScreen} 里画好；两者必须在同一帧内配对）。
     *
     * 为什么整段包在 {@link Draw#beginPanel()}/{@link Draw#endPanel()} 里：
     * 1.12.2 的 `RenderItem` 固定把物品画在 z=100，而面板方块/文字在 z=0 ——
     * 深度测试开着时物品会**穿透面板**。见 Draw#beginPanel 的注释。
     */
    void renderPanel(int mouseX, int mouseY, float partialTick) {
        Draw g = new Draw();
        // 浮层模式：尺寸跟着父界面走（玩家改窗口大小时父界面会重新 init，我们不跟着就会被夹住）
        GuiScreen target = renderTarget();
        if (target != this && (this.width != target.width || this.height != target.height)) {
            this.width = target.width;
            this.height = target.height;
        }
        // ★ 帧级状态刷新放在这里（不是 drawScreen）：浮层模式下我们不是 currentScreen，
        //   `drawScreen` **一次都不会被调用**（父界面才是被渲染的那个）。
        //   Post 里的这一次调用是两种模式**唯一**共用的"每帧一次"的入口。
        rebuildIfNeeded();
        refreshAnalyze();
        g.beginPanel();
        // 悬浮详情每帧重建（鼠标动一下就可能换目标）
        tipKey = 0;
        tipTitle = "";
        tipLines = java.util.Collections.emptyList();
        Layout L = layout();
        int x = L.x();
        int y = L.y();

        // 先补画背景容器界面的 hover 高亮（原理见方法注释），并记下命中的槽位
        renderBackgroundHover(g, mouseX, mouseY, L);

        // 选格覆盖层必须画在面板**之前** // 容器格子有可能落在面板底下（面板是半透明浮层，位置可以拖），
        // 反过来的话选中框会画到面板上面去。
        renderCellOverlay(g, mouseX, mouseY);

        // 只有面板本身，不再整屏压黑 —— 被压住的容器界面要能看见
        g.fill(x + 2, y + 3, x + L.w() + 3, y + L.h() + 4, 0x66000000);
        g.fill(x - 1, y - 1, x + L.w() + 1, y + L.h() + 1, 0xFF5A5A68);
        // ★ 面板底必须**完全不透明**：0xF2（95%）会让底下的物品以 5% 透出来
        //   （实测采样：白物品透过处是 (25,25,34)，纯面板是 (15,15,20)）。
        //   这是个观测工具 —— 面板里的每一个像素都该是我们画的。
        g.fill(x, y, x + L.w(), y + L.h(), 0xFF101017);

        // 光效必须画在标题栏背景**之后**：renderTitle 会整条 fill 标题区，
        //   先画就被完全盖住（实测踩过）
        renderTitle(g, L, mouseX, mouseY);
        renderTopGlow(g, L);
        renderInfo(g, L, mouseX, mouseY);

        switch (page) {
            case ROOT:
                renderRoot(g, L, mouseX, mouseY);
                break;
            case PICKER:
                renderSlotPicker(g, L, mouseX, mouseY);
                break;
            case CHART:
            case REPLAY:
            {
                if (L.hasChart()) {
                    chart.render(g, this.fontRenderer, chartData,
                            x + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), chartView(L, mouseX, mouseY));
                    renderAnalyzeBox(g, L);
                }
                renderToolbar(g, L.toolbar(), mouseX, mouseY);
                renderLegend(g, L.legend(), mouseX, mouseY);
            }
            break;
            case DATA:
                renderDataPage(g, L, mouseX, mouseY);
                break;
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
        // ★ 重新断言一次面板状态：vanilla 的 drawHoveringText 收尾会把 depth
        //   **重新打开**，之后继续画的东西就又会被"z=100 物品规则"顶掉。
        g.beginPanel();
        renderFlash(g, y);

        // 最后两样必须压在最上层，和 vanilla 的顺序一致：
        // 手持物品永远跟着光标可见，悬浮提示在它下面。
        renderCarriedItem(g, mouseX, mouseY);
        renderBackgroundTooltip(g, mouseX, mouseY);
        g.endPanel();
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
                : currentProbe() != null ? currentProbe().markers().toList() : java.util.Collections.emptyList();
        // ④：选区基础信息只列**启用的线**（与十字线读数的 readable 同一规则）：
        // 5 条线里 3 条 quantity/IO 全关，选区统计就不写这 3 条——写了只会把小框挤爆，
        // 还让人误以为"这条线在图上"。图例开关随时可变，所以过滤放在每帧的 View 组装处。
        return new ChartRenderer.View(start, end, hiddenItems, ioShown, sel, selecting,
                visibleSelectionStats(), markers, mouseX, mouseY);
    }

    /**
     * 选区统计的可见过滤：只保留**启用的线**。
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
        // 缩放后必须忠实反映 windowTicks，否则缩放没有反馈。
        if (!userZoomed && chartData.firstTick() > start) {
            start = chartData.firstTick();
        }
        if (end <= start) {
            end = start + 1;
        }
        return new long[]{start, end};
    }

    private void renderTitle(Draw g, Layout L, int mouseX, int mouseY) {
        int x = L.x();
        int y = L.y();
        boolean overTitle = isIn(mouseX, mouseY, x, y, L.w(), TITLE_H);
        g.fill(x, y, x + L.w(), y + TITLE_H, dragging ? 0xFF3B3B55 : (overTitle ? 0xFF2C2C3E : 0xFF22222E));
        g.fill(x, y + TITLE_H - 1, x + L.w(), y + TITLE_H, 0xFF5A5A68);

        String title;
        switch (page) {
            case ROOT:
                title = "LogiScope";
                break;
            case PICKER:
                title = "LogiScope · " + tr("logiscope.page.picker");
                break;
            case CHART:
                title = "LogiScope · " + tr("logiscope.page.chart");
                break;
            case REPLAY:
                title = "LogiScope · " + tr("logiscope.page.replay");
                break;
            case DATA:
                title = "LogiScope · " + tr("logiscope.page.data");
                break;
            default:
                // 经典 switch 不被 javac 视为穷尽（Page 目前只有 5 个值），这里兜底
                title = "LogiScope";
                break;
}
        g.drawString(this.fontRenderer, title, x + 4, y + 4, 0xFFE6E6F0, false);

        if (dragging || overTitle) {
            g.drawString(this.fontRenderer, tr("logiscope.hint.drag"), x + 4 + this.fontRenderer.getStringWidth(title) + 6, y + 4,
                    0xFF8A8AA0, false);
        }

        String status = statusText();
        g.drawString(this.fontRenderer, status, x + L.w() - 4 - this.fontRenderer.getStringWidth(status), y + 4,
                statusColor(), false);
    }

    /** 标题栏右侧的状态徽标 */
    private String statusText() {
        ProbeManager mgr = LogiScopeMod.probes();
        switch (page) {
            case ROOT:
                return "[ " + tr("logiscope.status.probes") + " " + mgr.count() + " ]";
            case PICKER:
                return "[ " + tr("logiscope.status.picking") + " " + selectionCount() + " ]";
            case REPLAY:
                return "[ " + tr("logiscope.status.replay") + " ]";
            case DATA:
                return "[ " + tr("logiscope.status.data") + " ]";
            case CHART:
            default: {
                ProbeInstance p = currentProbe();
                if (p == null) {
                    return "[ " + tr("logiscope.status.idle") + " ]";
                }
                String key;
                switch (p.state()) {
                    case ARMED:
                        key = "logiscope.status.armed";
                        break;
                    case RECORDING:
                        key = "logiscope.status.recording";
                        break;
                    case FOLLOWING:
                        key = "logiscope.status.following";
                        break;
                    case PAUSED:
                    default:
                        key = p.isManuallyPaused()
                                ? "logiscope.status.paused"
                                : "logiscope.status.paused_auto";
                        break;
                }
                return "[ " + tr(key) + " ]";
            }
        }
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
        switch (p.state()) {
            case RECORDING:
                return 0x5DCAA5;
            case FOLLOWING:
                return 0x6FB6E8;
            case PAUSED:
                return 0xEF9F27;
            case ARMED:
                return 0x85B7EB;
                    default:
                        // 枚举已穷举；经典 switch 不被 javac 视为穷尽，这里兜底
                        return 0x888780;
        }
    }

    // ------------------------------------------------------------------
    // 信息区
    // ------------------------------------------------------------------

    private void renderInfo(Draw g, Layout L, int mouseX, int mouseY) {
        int left = L.x() + PAD;
        int right = L.x() + L.w() - PAD;
        int y = L.infoTop();

        switch (page) {
            case ROOT:
            {
                ProbeManager mgr = LogiScopeMod.probes();
                String head = tr("logiscope.info.probes") + " " + mgr.count() + " / " + LogiScopeConfig.maxProbes();
                g.drawString(this.fontRenderer, head, left, y, 0xBFBFBF, false);
                y += ROW_H;
                String hint = hasTarget ? tr("logiscope.hint.create_ready") : tr("logiscope.hint.no_target");
                g.drawString(this.fontRenderer, cut(hint, L.w() - 2 * PAD), left, y, 0x8A8AA0, false);
            }
            break;
            case PICKER:
            {
                ProbeInstance p = pickerProbe();
                String head = p == null ? tr("logiscope.hint.no_container") : p.describe();
                g.drawString(this.fontRenderer, cut(head, L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
                y += ROW_H;
                if (netMode()) {
                    // 网络终端（AE / RS）：这一页的语义完全不同。
                    // 说明行改为**悬浮**在黄行上显示
                    String line = tr(rsMode ? "logiscope.picker.rs_mode" : "logiscope.picker.ae_mode") + "  ·  "
                            + tr("logiscope.info.selected") + " " + selectionCount();
                    g.drawString(this.fontRenderer, cut(line, L.w() - 2 * PAD), left, y, 0xFFE8C97A, false);
            break;
                }
                if (source == Source.WORLD && worldSlots.isEmpty()) {
                    // 无容器方块实体：没有格子可选，但 NBT / 数据页仍可用。
                    // 相关按钮（选格/清空/可视化选格）本来就由 selectionCount/menu 驱动，已自动置灰。
                    String line = tr("logiscope.picker.no_cells");
                    if (nbtSlotMode) {
                        line += "  ·  " + tr("logiscope.btn.nbt_mode_on")
                                + " (" + nbtSlotEntries.size() + ")";
                    }
                    g.drawString(this.fontRenderer, cut(line, L.w() - 2 * PAD), left, y, 0xFFE8C97A, false);
            break;
                }
                String badge;
                switch (source) {
                    case MENU:
                        badge = tr("logiscope.src.menu");
                        break;
                    case WORLD:
                        badge = tr("logiscope.src.world");
                        break;
                    case NONE:
                        badge = tr("logiscope.src.none");
                        break;
                    default:
                        // 经典 switch 不被 javac 视为穷尽（Source 目前只有 3 个值），这里兜底
                        badge = tr("logiscope.src.none");
                        break;
}
                String line = badge + " · " + tr("logiscope.info.selected") + " " + selectionCount();
                if (nbtSlotMode) {
                    // ⑦：删掉"NBT slot-list, track by slot"那句（按钮本身已表达状态），
                    // 只在追踪开着时显示 ON + 元素数
                    line += "  ·  " + tr("logiscope.btn.nbt_mode_on")
                            + " (" + nbtSlotEntries.size() + ")";
                }
                g.drawString(this.fontRenderer, cut(line, L.w() - 2 * PAD), left, y, 0x9BE1CB, false);
            }
    break;
            case CHART:
            {
                ProbeInstance p = currentProbe();
                if (p == null) {
                    g.drawString(this.fontRenderer, tr("logiscope.hint.no_probe"), left, y, 0xAAAAAA, false);
                    return;
                }
                g.drawString(this.fontRenderer, cut(p.describe(), L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
                y += ROW_H;

                String now = "now " + currentValueText(p);
                String counts = "ticks " + summary.dataTicks() + "  no-data " + summary.noDataTicks();
                // 读数用**准确整数**，位数比短格式多，所以按剩余宽度裁一刀防止压到右边的计数
                int nowW = L.w() - 2 * PAD - this.fontRenderer.getStringWidth(counts) - 8;
                g.drawString(this.fontRenderer, cut(now, nowW), left, y, 0x9BE1CB, false);
                g.drawString(this.fontRenderer, counts, right - this.fontRenderer.getStringWidth(counts), y, 0x808088, false);
                y += ROW_H;

                // ④：min/max/avg 删掉（要看统计就存档后做区间运算，那里更完整），
                // 换成"实时样本条数 / 缓冲上限" —— 这才是实时页真正想知道的事。
                String mm = tr("logiscope.info.samples") + " " + p.store().size()
                        + " / " + p.store().capacity();
                String sw = "switch " + chartData.switches() + "  series " + chartData.series().size();
                if (chartData.droppedKinds() > 0) {
                    // 超过 maxItemKinds 的种类：数据照记，只是不画线 —— 得让用户知道
                    sw += "  " + tr("logiscope.info.kinds_dropped")
                            .replace("%s", String.valueOf(chartData.droppedKinds()));
                }
                int mmW = L.w() - 2 * PAD - this.fontRenderer.getStringWidth(sw) - 8;
                g.drawString(this.fontRenderer, cut(mm, mmW), left, y, 0xC8C8D4, false);
                g.drawString(this.fontRenderer, sw, right - this.fontRenderer.getStringWidth(sw), y, 0x808088, false);
            }
            break;
            case DATA:
            {
                ProbeInstance src = dataSrcProbe();
                String head = src == null ? tr("logiscope.hint.no_target")
                        : src.name() + "  " + src.target().anchorText();
                g.drawString(this.fontRenderer, cut(head, L.w() - 2 * PAD), left, y, 0xBFBFBF, false);
                y += ROW_H;
                String line = tr("logiscope.data.src") + "  " + tr(DataCommand.statusKey());
                g.drawString(this.fontRenderer, cut(line, L.w() - 2 * PAD), left, y,
                        DataCommand.statusColor(), false);
                // 刷新速率滑条：贴在 "status ok" 同一行右侧
                renderDataRateSlider(g, L, mouseX, mouseY);
            }
            break;
            case REPLAY:
            {
                // 回放页的信息区：存档名 / 探针来源 / 统计
                ArchiveData a = replayArchive;
                if (a != null) {
                    g.drawString(this.fontRenderer, cut(replayName + "  ·  " + a.created(), L.w() - 2 * PAD),
                            left, y, 0xE0B8E0, false);
                    y += ROW_H;
                    g.drawString(this.fontRenderer, cut(a.probeName() + " " + a.probeDesc(), L.w() - 2 * PAD),
                            left, y, 0xBFBFBF, false);
                    y += ROW_H;
                }
                String counts = "ticks " + summary.dataTicks() + "  no-data " + summary.noDataTicks();
                String sw = "series " + chartData.series().size();
                if (replaySelectedItem != null) {
                    sw += "  " + tr("logiscope.info.analyze")
                            .replace("%s", ChartRenderer.label(chartData.indexOf(replaySelectedItem), replaySelectedItem));
                }
                g.drawString(this.fontRenderer, counts, left, y, 0x808088, false);
                g.drawString(this.fontRenderer, sw, right - this.fontRenderer.getStringWidth(sw), y, 0x808088, false);
            }
            break;
        }
    }

    /**
     * 面板上那行 "now"。RECORDING 直接读菜单，其余从最近一条样本取。
     *
     * 多格监控时这里会有**多条**（每个物品一条）* 最多显示 3 条，多的收成 "+N" —— 和十字线读数的处理一致，不把一行塞爆。
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
    private void renderRoot(Draw g, Layout L, int mouseX, int mouseY) {
        renderProbeList(g, L, mouseX, mouseY);
        renderArchives(g, L, mouseX, mouseY);
    }

    /** 根页下半：存档列表（读取分析数据）。默认高亮当前/最近一次的存档 */
    private void renderArchives(Draw g, Layout L, int mouseX, int mouseY) {
        int x = L.x();
        int y = L.archTop();
        List<Archives.Info> head = archiveList;
        String colDir = tr("logiscope.arch.col.dir");
        String colCnt = tr("logiscope.arch.col.count");
        int headNameW = Math.max(96, (L.w() - 16) * 44 / 100);
        int headNameX = x + 8;
        int headDirX = headNameX + headNameW + 6;
        int headCntX = archRight(L) - this.fontRenderer.getStringWidth(colCnt);
        // 标题行左侧 = 筛选输入框：空时显示原「存档」标题文字作占位符
        renderArchiveFilter(g, L, mouseX, mouseY);
        if (!head.isEmpty()) {
            // 列名贴在标题行右侧，和下面的三列同位置 —— "名称-文件夹-样本数"一眼对上
            g.drawString(this.fontRenderer, colDir, headDirX, y, 0x6A6A78, false);
            g.drawString(this.fontRenderer, colCnt, headCntX, y, 0x6A6A78, false);
        }
        if (L.archRows() <= 0) {
            return;
        }
        List<Archives.Info> list = filteredArchives();
        if (list.isEmpty()) {
            // 区分"真没有存档"与"筛掉了" —— 后者别让曾误以为数据丢了
            String hint = head.isEmpty()
                    ? tr("logiscope.hint.no_archives")
                    : tr("logiscope.hint.arch_no_match");
            g.drawString(this.fontRenderer, hint, x + PAD + 4, y + ROW_H, 0x8A8AA0, false);
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
            boolean over = isIn(mouseX, mouseY, x + 4, ry - 1, L.w() - 8 - DEL_W - 2, ROW_H);
            boolean overDel = isIn(mouseX, mouseY, archDeleteX(L), ry - 1, DEL_W, ROW_H);
            boolean armed = info.dirName().equals(archiveDeleteArmed);
            if (over) {
                g.fill(x + 4, ry - 1, x + L.w() - 4 - DEL_W - 2, ry + ROW_H - 1, 0x40FFFFFF);
            }
            if (loaded) {
                g.fill(x + 4, ry - 1, x + 6, ry + ROW_H - 1, COLOR_ANALYZE);
            }
            // 三列**固定对齐**：名称（左侧留宽，可重命名） / 文件夹 / 样本数（右对齐）
            String name = info.displayName().trim().isEmpty() ? info.dirName() : info.displayName();
            String cnt = String.valueOf(info.sampleCount());
            int nameW = Math.max(96, (L.w() - 16) * 44 / 100);
            int nameX = x + 8;
            int dirX = nameX + nameW + 6;
            int cntX = archRight(L) - this.fontRenderer.getStringWidth(cnt);
            g.drawString(this.fontRenderer, cut(name, nameW), nameX, ry,
                    loaded ? 0xFFF0E0F8 : 0xFFC8C8D8, false);
            int dirW = Math.max(16, cntX - dirX - 8);
            if (dirW > 16) {
                g.drawString(this.fontRenderer, cut(info.dirName(), dirW), dirX, ry, 0xFF9099A8, false);
            }
            g.drawString(this.fontRenderer, cnt, cntX, ry, 0xFF9099A8, false);

            // 删除按钮（行尾，与探针列表同一个观感）：
            // 第一次点只是**待确认**（红色「!」+ 提示），再点同一个按钮才真删 —— 存档是用户的文件，不能一点就没
            int dx = archDeleteX(L);
            g.fill(dx, ry - 1, dx + DEL_W, ry + ROW_H - 1,
                    armed ? 0xFFB03040 : overDel ? 0xFF7A3038 : 0xFF2A2A38);
            g.drawString(this.fontRenderer, armed ? "!" : "×", dx + 4, ry,
                    armed ? 0xFFFFE0E0 : overDel ? 0xFFFFD0D0 : 0xFFC0A0A8, false);
        }
        if (list.size() > L.archRows()) {
            String more = (scrollArchives + L.archRows()) + " / " + list.size()
                    + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.fontRenderer, more, x + PAD, y + ROW_H + L.archRows() * ROW_H, 0xFF707070, false);
        }
    }

    /**
     * 筛选后要显示的存档清单。筛选串为空 = 全量原样返回。
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
            String name = i.displayName().trim().isEmpty() ? i.dirName() : i.displayName();
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

    /** 存档筛选框：空时显示占位符（原标题文字），聚焦时闪光标 */
    private void renderArchiveFilter(Draw g, Layout L, int mouseX, int mouseY) {
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
        String draw = this.fontRenderer.getStringWidth(text) <= avail ? text : cut(text, avail);
        g.drawString(this.fontRenderer, draw, tx, ty, empty ? 0xFF9A8AA8 : 0xFFE0E0EC, false);
        if (archFilterFocus && (LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = tx + Math.min(this.fontRenderer.getStringWidth(text), avail);
            g.fill(cx, ty, cx + 1, ty + 9, 0xFFE0E0EC);
        }
    }

    /**
     * 用系统文件管理器打开**当前这次回放的**存档目录
     * （{@code logiscope/<存档名>/}，recording.csv / recording.json 就在这一层）。
     */
    private void openArchiveFolder() {
        if (replayDir == null || replayDir.trim().isEmpty()) {
            return;
        }
        try {
            Path dir = Archives.dirOf(replayDir);
            Files.createDirectories(dir);
            openFolder(dir.toFile());
        } catch (Exception e) {
            LogiScopeMod.LOGGER.warn("open archive folder failed", e);
            say(tr("logiscope.flash.open_folder_fail"));
        }
    }

    /**
     * 用系统默认程序打开一个目录。
     *
     * 1.12.2 的 {@code Util.EnumOS} **没有** {@code openFile}（那是 1.13+ 才加的），
     * 所以这里退回 {@code java.awt.Desktop}。打不开只记日志、不弹错 ——
     * "打开文件夹"是便利功能，不该影响主流程。
     */
    private static void openFolder(java.io.File dir) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面那条日志
        }
        LogiScopeMod.LOGGER.info("[LogiScope] 存档目录: {}", dir.getAbsolutePath());
    }

    /** 用系统文件管理器打开存档目录 {@code .minecraft/logiscope/} */
    private void openStorageFolder() {
        try {
            Files.createDirectories(Archives.rootDir());
            openFolder(Archives.rootDir().toFile());
        } catch (Exception e) {
            LogiScopeMod.LOGGER.warn("open logiscope folder failed", e);
            say(tr("logiscope.flash.open_folder_fail"));
        }
    }

    private void renderProbeList(Draw g, Layout L, int mouseX, int mouseY) {
        int x = L.x();
        List<ProbeInstance> list = LogiScopeMod.probes().all();

        if (list.isEmpty()) {
            g.drawString(this.fontRenderer, tr("logiscope.hint.no_probe"), x + PAD, L.listTop(), 0xAAA6A0, false);
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
            boolean overRow = isIn(mouseX, mouseY, x + 4, ry - 1, L.w() - 8 - DEL_W - HL_W - 4, ROW_H);
            boolean overDel = isIn(mouseX, mouseY, deleteX(L), ry - 1, DEL_W, ROW_H);
            boolean overHl = isIn(mouseX, mouseY, probeHlX(L), ry - 1, HL_W, ROW_H);

            if (overRow) {
                g.fill(x + 4, ry - 1, x + L.w() - 4 - DEL_W - HL_W - 4, ry + ROW_H - 1, 0x40FFFFFF);
            }
            if (active) {
                g.fill(x + 4, ry - 1, x + 4 + 2, ry + ROW_H - 1, 0xFF3FE0A0);
            }

            // 行首是**设备名**（需求：探针名称里前缀加方块名），然后是状态、槽位+坐标
            String name = cut(p.name(), 92);
            g.drawString(this.fontRenderer, name, x + 8, ry, active ? 0xFFF0F0FF : 0xFFC8C8D8, false);
            int stX = x + 8 + this.fontRenderer.getStringWidth(name) + 6;
            String st = shortState(p);
            g.drawString(this.fontRenderer, st, stX, ry, stateColor(p), false);
            String desc = p.describe();
            int descX = stX + this.fontRenderer.getStringWidth(st) + 6;
            int descW = x + L.w() - 4 - DEL_W - HL_W - 4 - descX;
            if (descW > 20) {
                g.drawString(this.fontRenderer, cut(desc, descW), descX, ry,
                        p.hasSlots() ? 0xFF9099A8 : 0xFF70707A, false);
            }

            // 高亮按钮（十字准星）：把这条探针的追踪目标在世界里画出来（穿墙，1Hz 红黄闪）
            int hx = probeHlX(L);
            boolean hlOn = ProbeHighlight.isActiveFor(p.id());
            g.fill(hx, ry - 1, hx + HL_W, ry + ROW_H - 1,
                    hlOn ? 0xFF5A4420 : overHl ? 0xFF3A4A5A : 0xFF2A2A38);
            drawCrosshair(g, hx, ry,
                    hlOn ? 0xFFFFD24A : overHl ? 0xFFE8EEFF : 0xFF9AA0B0);

            // 删除按钮：停止监测 + 删掉这个实例的缓存
            int dx = deleteX(L);
            g.fill(dx, ry - 1, dx + DEL_W, ry + ROW_H - 1, overDel ? 0xFF7A3038 : 0xFF2A2A38);
            g.drawString(this.fontRenderer, "×", dx + 4, ry, overDel ? 0xFFFFD0D0 : 0xFFC0A0A8, false);
        }

        if (total > maxRows) {
            String more = (scrollRow + maxRows) + " / " + total + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.fontRenderer, more, x + PAD, listTop + maxRows * ROW_H + 1, 0xFF707070, false);
        }
    }

    private static int deleteX(Layout L) {
        return L.x() + L.w() - 4 - DEL_W;
    }

    /** 探针行的「高亮目标」按钮左边界：紧挨在删除按钮左边 */
    private static int probeHlX(Layout L) {
        return deleteX(L) - HL_W - 2;
    }

    /**
     * 画一个十字准星图标：上下左右四段短线 + 中心一个方形像素点（需求原文的图形）。
     *
     * 全部用 {@code g.fill} 拼（1~2px 的细条），不依赖字体里的符号 —— 字体里的 "✛"
     * 在不同语言包里宽度和基线都不一样，画出来会偏。坐标取整行高度 11px 内居中。
     */
    private void drawCrosshair(Draw g, int x, int ry, int color) {
        int cx = x + HL_W / 2;          // 竖线所在列
        // 行高 11（按钮占 ry-1 … ry+9，共 11 行），四条线段要以按钮为基准上下对称：
        // cy = ry+5 时下段会贴到按钮底边（实测偏下一像素），改成 ry+4 正好居中
        int cy = ry + 4;
        g.fill(cx, cy - 4, cx + 1, cy - 2, color);      // 上
        g.fill(cx, cy + 3, cx + 1, cy + 5, color);      // 下
        g.fill(cx - 4, cy, cx - 2, cy + 1, color);      // 左
        g.fill(cx + 3, cy, cx + 5, cy + 1, color);      // 右
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);  // 中心方块
    }

    /**
     * 点十字准星：高亮 / 取消高亮这条探针的追踪目标。
     *
     * 开启前先校验一次（没坐标 / 在别的维度 / 超过 128 格都直接拒绝并说明原因）——
     * 与其画一个看不见的框让人以为坏了，不如明确说为什么看不见。
     * 开启之后**每帧还会再判一次**（见 {@link ProbeHighlight}）：走出去只是不画，状态保留。
     */
    private void toggleProbeHighlight(ProbeInstance p) {
        if (ProbeHighlight.isActiveFor(p.id())) {
            ProbeHighlight.clear();
            say(tr("logiscope.flash.hl_off"));
            return;
        }
        ProbeTarget t = p.target();
        if (!t.hasPersistentId()) {
            say(tr("logiscope.flash.hl_no_target"));
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }
        if (!Dim.of(mc.world).equals(t.dimension())) {
            say(tr("logiscope.flash.hl_other_dim")
                    .replace("%s", t.dimension() == null ? "?" : t.dimension().path()));
            return;
        }
        BlockPos tp = t.pos();
        BlockPos pp = new BlockPos(mc.player.posX, mc.player.posY, mc.player.posZ);
        // 需求：不算欧氏距离，三轴差值绝对值都 < 128
        if (Math.abs(tp.getX() - pp.getX()) >= ProbeHighlight.RANGE
                || Math.abs(tp.getY() - pp.getY()) >= ProbeHighlight.RANGE
                || Math.abs(tp.getZ() - pp.getZ()) >= ProbeHighlight.RANGE) {
            say(tr("logiscope.flash.hl_far").replace("%s", String.valueOf(ProbeHighlight.RANGE)));
            return;
        }
        ProbeHighlight.set(p.id(), t.dimension(), tp);
        say(tr("logiscope.flash.hl_on").replace("%s", p.name()));
    }

    /** 存档行的删除按钮左边界（与探针行同一个观感：贴右边、{@link #DEL_W} 宽） */
    private static int archDeleteX(Layout L) {
        return L.x() + L.w() - 4 - DEL_W;
    }

    /** 存档行「列内容」的右边界：样本数右对齐到这里，右边让给删除按钮 */
    private static int archRight(Layout L) {
        return archDeleteX(L) - 4;
    }

    private String shortState(ProbeInstance p) {
        String key;
        switch (p.state()) {
            case ARMED:
                key = "logiscope.short.armed";
                break;
            case RECORDING:
                key = "logiscope.short.recording";
                break;
            case FOLLOWING:
                key = "logiscope.short.following";
                break;
            case PAUSED:
            default:
                key = "logiscope.short.paused";
                break;
        }
        return tr(key);
    }

    private int stateColor(ProbeInstance p) {
        switch (p.state()) {
            case RECORDING:
                return 0x5DCAA5;
            case FOLLOWING:
                return 0x6FB6E8;
            case PAUSED:
                return 0xEF9F27;
            case ARMED:
                return 0x85B7EB;
                    default:
                        // 枚举已穷举；经典 switch 不被 javac 视为穷尽，这里兜底
                        return 0x888780;
        }
    }

    // ------------------------------------------------------------------
    // 选格页
    // ------------------------------------------------------------------

    /**
     * 现在是不是**网络类**模式（AE 终端 / RS 网格）。
     *
     * 两者的界面语义完全一致：行是"网络里的一种东西"而不是"一个容器格"，
     * 选中靠身份串而不是槽位下标，「完成」= 覆写全部网络目标。
     */
    private boolean netMode() {
        return aeMode || rsMode;
    }

    private int selectionCount() {
        if (netMode()) {
            return selectedNetIds.size();
        }
        return source == Source.WORLD ? selContainerSlots.size() : selMenuSlots.size();
    }

    private List<ContainerAccess.SlotView> pickerList() {
        return source == Source.MENU ? menuSlots : worldSlots;
    }

    /**
     * **筛选后要显示的槽位清单**：渲染 / 点击命中 / 翻页三处共用它
     * （"看到的行 = 点的行"，与存档筛选框同一条纪律）。
     */
    private List<ContainerAccess.SlotView> pickerVisible() {
        List<ContainerAccess.SlotView> all = pickerList();
        boolean byText = !pickerFilter.trim().isEmpty();
        if (all.isEmpty() || (!byText && !pickerIgnoreEmpty)) {
            return all;
        }
        String emptyLabel = tr("logiscope.slot.empty");
        List<ContainerAccess.SlotView> out = new ArrayList<ContainerAccess.SlotView>();
        for (ContainerAccess.SlotView v : all) {
            if (pickerIgnoreEmpty && v.empty()) {
                continue;   // ① 忽略空槽
            }
            // 参与匹配的就是这一行**显示出来的**那几段：序号 / 物品 id / 短名（AE 行是显示名）
            // + 当前「名称」开关下真正画出来的那个名字 —— "看到什么就能筛什么"。
            String shownName = NameText.of(v.itemId(), v.label());
            boolean hit = !byText || PickerFilter.matches(pickerFilter, v.containerSlotIndex(),
                    v.itemId(), v.shortName(), v.displayName(), shownName,
                    v.empty() ? emptyLabel : null);
            if (hit) {
                out.add(v);
            }
        }
        return out;
    }

    private boolean isSelected(ContainerAccess.SlotView v) {
        if (netMode()) {
            return !v.empty() && selectedNetIds.contains(v.itemId());
        }
        return source == Source.MENU ? selMenuSlots.contains(v.menuSlotIndex())
                : selContainerSlots.contains(v.containerSlotIndex());
    }

    private void renderSlotPicker(Draw g, Layout L, int mouseX, int mouseY) {
        int x = L.x();

        switch (source) {
            case NONE:
            {
                g.drawString(this.fontRenderer, tr("logiscope.hint.no_container"), x + PAD, L.listTop(), 0xAAA6A0, false);
                return;
            }
            case WORLD:
            {
                if (worldSlots.isEmpty()) {
                    ProbeSlots.Status st = ProbeSlots.status();
                    String key = st == ProbeSlots.Status.NONE
                            ? "logiscope.hint.not_a_container"
                            : "logiscope.hint.world_reading";
                    g.drawString(this.fontRenderer, tr(key), x + PAD, L.listTop(), 0xAAA6A0, false);
                    return;
                }
            }
            break;
        }

        List<ContainerAccess.SlotView> all = pickerList();
        if (all.isEmpty()) {
            g.drawString(this.fontRenderer, tr("logiscope.hint.no_container"), x + PAD, L.listTop(), 0xAAA6A0, false);
            return;
        }

        renderPickerFilter(g, L, mouseX, mouseY);
        renderPickerIgnore(g, L, mouseX, mouseY);

        List<ContainerAccess.SlotView> list = pickerVisible();
        if (list.isEmpty()) {
            String key = pickerFilter.trim().isEmpty()
                    ? "logiscope.picker.all_empty" : "logiscope.picker.no_match";
            g.drawString(this.fontRenderer, tr(key), x + PAD, L.listTop(), 0xAAA6A0, false);
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
            g.drawString(this.fontRenderer, selected ? "[x]" : "[ ]", x + 8, ry,
                    selected ? 0xFF3FE0A0 : 0xFF6A6A78, false);
            String label = "#" + v.containerSlotIndex()
                    // 名字：交给 NameText —— 注册名 / 实际名由「名称」开关统一决定
                    + "  " + (v.empty() ? tr("logiscope.slot.empty")
                    : NameText.of(v.itemId(), v.label()) + " x" + v.count());
            g.drawString(this.fontRenderer, label, x + 30, ry,
                    v.empty() ? (selected ? 0xFFA8C8B8 : 0xFF707070) : 0xFFE0E0E0, false);
        }

        String footer = (scrollRow + maxRows) + " / " + total + "  " + tr("logiscope.hint.scroll");
        if (total != all.size()) {
            footer = footer + "  ·  " + total + " / " + all.size() + " " + tr("logiscope.picker.filtered");
        }
        if (cellMode) {
            footer = footer + "  ·  " + tr("logiscope.hint.cell_mode_short");
        }
        g.drawString(this.fontRenderer, footer, x + PAD, listTop + maxRows * ROW_H + 1, 0xFF707070, false);
    }

    // ------------------------------------------------------------------
    // 图例 / 工具栏 / 按钮
    // ------------------------------------------------------------------

    /** 「忽略空槽」按钮矩形：钉在筛选行右端（"搜索框后面"） */
    private int[] pickerIgnoreRect(Layout L) {
        int want = this.fontRenderer.getStringWidth(tr("logiscope.picker.ignore_empty")) + 10;
        int w = Math.min(want, Math.max(36, L.w() - 2 * PAD - 60));
        return new int[]{L.x() + L.w() - PAD - w, L.filterTop(), w, L.filterH()};
    }

    private boolean inPickerIgnore(Layout L, int mx, int my) {
        if (page != Page.PICKER || L.filterH() <= 0) {
            return false;
        }
        int[] r = pickerIgnoreRect(L);
        return isIn(mx, my, r[0], r[1], r[2], r[3]);
    }

    /** 筛选框矩形：占筛选行除去按钮的剩余宽度 */
    private int[] pickerFilterRect(Layout L) {
        int[] btn = pickerIgnoreRect(L);
        int w = Math.max(40, btn[0] - 4 - (L.x() + PAD));
        return new int[]{L.x() + PAD, L.filterTop(), w, L.filterH()};
    }

    private boolean inPickerFilter(Layout L, int mx, int my) {
        if (page != Page.PICKER || L.filterH() <= 0) {
            return false;
        }
        int[] r = pickerFilterRect(L);
        return isIn(mx, my, r[0], r[1], r[2], r[3]);
    }

    /** 「忽略空槽」开关按钮：复用按钮观感，开着时文字转绿 */
    private void renderPickerIgnore(Draw g, Layout L, int mouseX, int mouseY) {
        int[] r = pickerIgnoreRect(L);
        Chip c = new Chip(PICKER_IGNORE_EMPTY, tr("logiscope.picker.ignore_empty"),
                r[0], r[1], r[2], r[3], true, 0);
        renderChip(g, c, mouseX, mouseY, c.hit(mouseX, mouseY), accentFor(PICKER_IGNORE_EMPTY));
    }

    /** 选格页筛选框：空时显示占位符，聚焦时闪光标 */
    private void renderPickerFilter(Draw g, Layout L, int mouseX, int mouseY) {
        int[] r = pickerFilterRect(L);
        int x = r[0];
        int y = r[1];
        int w = r[2];
        int h = r[3];
        boolean over = isIn(mouseX, mouseY, x, y, w, h);
        g.fill(x, y, x + w, y + h, 0xC0101018);
        ring(g, x, y, w, h, pickerFilterFocus ? COLOR_ANALYZE
                : over ? 0xFF6A6A7A : 0xFF3A3A46);
        boolean empty = pickerFilter.isEmpty();
        String text = empty ? tr("logiscope.picker.filter_hint") : pickerFilter;
        int tx = x + 4;
        int avail = w - 6;
        String draw = this.fontRenderer.getStringWidth(text) <= avail ? text : cut(text, avail);
        g.drawString(this.fontRenderer, draw, tx, y + 1, empty ? 0xFF8A8AA0 : 0xFFE0E0EC, false);
        if (pickerFilterFocus && (LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = tx + Math.min(this.fontRenderer.getStringWidth(text), avail);
            g.fill(cx, y + 1, cx + 1, y + 9, 0xFFE0E0EC);
        }
    }

    private void renderToolbar(Draw g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            renderChip(g, c, mouseX, mouseY, c.enabled() && c.hit(mouseX, mouseY), c.accent());
        }
    }

    /**
     * 图例：一行一个色块 + 名字，点一下弹出「数量 / IO」小菜单（见 {@link #legendPopup}）。
     *
     * 图标分上下两半：一个物品有两层线（存量 / IO），只给一块色板表达不出来：
     * 只开 IO 时整块都是暗的，用户会以为"这个物品关掉了"，其实 IO 线正开着。
     * 所以色板劈成上下两半：**上半 = 存量线颜色，下半 = IO 线颜色（浅一档）**，
     * 关掉的那层显示灰 —— 每一层开关的状态都直接写在图标上。
     * 字体的明暗只服从一条规则：**两层全关才变暗**（单关一层时这行图例还活着）。
     */
    private void renderLegend(Draw g, List<Chip> chips, int mouseX, int mouseY) {
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
            g.drawString(this.fontRenderer, c.label(), c.x() + 13, c.y() + 2,
                    dim ? 0xFF70707A : 0xFFD8D8E4, false);
        }
    }

    /**
     * 图例弹出菜单：列在弹窗渲染**之后**调用（压在最上层，不然会被底部按钮盖住）。
     */
    private void renderLegendPopup(Draw g, Layout L, int mouseX, int mouseY) {
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
            int swatch;
            if (!r.on()) {
                swatch = 0xFF3A3A44;
            } else {
                switch (r.kind()) {
                    case PopupRow.IO:
                        swatch = ChartRenderer.flowColor(accent);
                        break;
                    case PopupRow.ANALYZE:
                        swatch = COLOR_ANALYZE;
                        break;
                    default:
                        swatch = accent;
                        break;
                }
            }
            g.fill(pr[0] + 6, ry + 3, pr[0] + 12, ry + 9, swatch);
            g.drawString(this.fontRenderer, r.label(), pr[0] + 16, ry + 2,
                    r.on() ? 0xFFE0E0EC : 0xFF8A8A9A, false);
            ry += POPUP_ROW_H;
        }
    }

    private void renderButtons(Draw g, List<Chip> chips, int mouseX, int mouseY) {
        for (Chip c : chips) {
            // accent 在渲染时求值（不取构造时存的 c.accent()）：
            //   语义色（主界面淡绿 / 回选格淡蓝 / NBT ON 黄）依赖运行状态，
            //   而按钮行只在 rebuild 时重建 —— 每帧求值才不会滞后。
            //   之前两个 bug 都在这条链上：传 0 = 颜色全丢；构造时求值 = 状态切换后滞后。
            renderChip(g, c, mouseX, mouseY, c.enabled() && c.hit(mouseX, mouseY), accentFor(c.id()));
        }
    }

    // ------------------------------------------------------------------
    // 回放计算
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
            analyzeLines = java.util.Collections.emptyList();
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
            return java.util.Collections.emptyList();
        }
        if (!analyzeIo) {
            List<SeriesPoint> pts = inRange(s.points(), lo, hi);
            if (pts.isEmpty()) {
                out.add(tr("logiscope.calc.no_data"));
                return out;
            }
            switch (analyzeOp) {
                case OP_EXTREMES:
                {
                    CurveMath.Extremes e = CurveMath.extremes(pts);
                    out.add("min " + ChartRenderer.fmtExact(e.min()) + " @t" + e.minTick());
                    out.add("max " + ChartRenderer.fmtExact(e.max()) + " @t" + e.maxTick());
                }
                break;
                case OP_STATS:
                {
                    CurveMath.Stats st = CurveMath.stats(pts);
                    out.add("mean " + fmtNum(st.mean()));
                    out.add("med " + fmtNum(st.med()));
                    out.add("var " + fmtNum(st.var()));
                }
                break;
                case OP_TOTAL:
                {
                    CurveMath.TotalChange t = CurveMath.totalChange(pts);
                    out.add(tr("logiscope.calc.rise", signedNum(t.rise())));
                    out.add(tr("logiscope.calc.fall", signedNum(t.fall())));
                    out.add(tr("logiscope.calc.net", signedNum(t.net())));
                    if (t.gaps() > 0) {
                        out.add(tr("logiscope.calc.gaps", t.gaps()));
                    }
                }
                break;
                case OP_EVENTS:
                    addEventLines(out, CurveMath.changeEvents(pts),
                                        "logiscope.calc.events");
                    break;
                case OP_RISE_EVENTS:
                    addEventLines(out, CurveMath.changeRiseEvents(pts),
                                        "logiscope.calc.rise_events");
                    break;
                case OP_FALL_EVENTS:
                    addEventLines(out, CurveMath.changeFallEvents(pts),
                                        "logiscope.calc.fall_events");
                    break;
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
            case OP_EXTREMES:
            {
                CurveMath.Extremes e = CurveMath.extremes(flow);
                out.add("min " + signedNum(e.min()) + " @t" + e.minTick());
                out.add("max " + signedNum(e.max()) + " @t" + e.maxTick());
            }
            break;
            case OP_STATS:
            {
                CurveMath.Stats st = CurveMath.stats(flow);
                out.add("mean " + signedNum(st.mean()));
                out.add("med " + signedNum(st.med()));
                out.add("var " + fmtNum(st.var()));
            }
            break;
            case OP_TOTAL:
            {
                CurveMath.TotalChange t = CurveMath.flowTotal(flow);
                out.add(tr("logiscope.calc.rise", signedNum(t.rise())));
                out.add(tr("logiscope.calc.fall", signedNum(t.fall())));
                out.add(tr("logiscope.calc.net", signedNum(t.net())));
                if (t.gaps() > 0) {
                    out.add(tr("logiscope.calc.gaps", t.gaps()));
                }
            }
            break;
            case OP_EVENTS:
                addEventLines(out, CurveMath.flowEvents(flow),
                                "logiscope.calc.events");
                break;
            case OP_RISE_EVENTS:
                addEventLines(out, CurveMath.flowRiseEvents(flow),
                                "logiscope.calc.rise_events");
                break;
            case OP_FALL_EVENTS:
                addEventLines(out, CurveMath.flowFallEvents(flow),
                                "logiscope.calc.fall_events");
                break;
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
    private void renderAnalyzeBox(Draw g, Layout L) {
        if (page != Page.REPLAY || analyzeLines.isEmpty()) {
            return;
        }
        int[] pr = ChartRenderer.plotRect(L.x() + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH());
        int w = this.fontRenderer.getStringWidth(analyzeTitle) + 8;
        for (String line : analyzeLines) {
            w = Math.max(w, this.fontRenderer.getStringWidth(line) + 8);
        }
        int h = (analyzeLines.size() + 1) * 10 + 4;
        int bx = pr[0] + pr[2] - w - 4;
        int by = pr[1] + 4;
        g.fill(bx - 2, by - 2, bx + w + 2, by + h, 0xE0181822);
        g.fill(bx - 2, by - 2, bx + w + 2, by - 1, COLOR_ANALYZE);
        g.drawString(this.fontRenderer, analyzeTitle, bx, by, COLOR_ANALYZE, false);
        int ly = by + 11;
        for (String line : analyzeLines) {
            g.drawString(this.fontRenderer, line, bx, ly, 0xFFE0E0EC, false);
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

    private void renderChip(Draw g, Chip c, int mouseX, int mouseY, boolean hover, int accent) {
        int bg = !c.enabled() ? 0xFF1A1A22 : (hover ? 0xFF41415A : 0xFF2A2A38);
        int border = !c.enabled() ? 0xFF2A2A34 : (hover ? 0xFF8A8AC8 : 0xFF4A4A5E);
        int fg = !c.enabled() ? 0xFF6A6A78 : (accent != 0 ? accent : (hover ? 0xFFF0F0FF : 0xFFC8C8D8));
        g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), bg);
        g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + 1, border);
        g.fill(c.x(), c.y() + c.h() - 1, c.x() + c.w(), c.y() + c.h(), border);
        g.fill(c.x(), c.y(), c.x() + 1, c.y() + c.h(), border);
        g.fill(c.x() + c.w() - 1, c.y(), c.x() + c.w(), c.y() + c.h(), border);
        int tw = this.fontRenderer.getStringWidth(c.label());
        g.drawString(this.fontRenderer, c.label(), c.x() + (c.w() - tw) / 2, c.y() + (c.h() - 8) / 2 + 1, fg, false);
    }

    /** 右下角的缩放把手。画三道嵌套的直角线，比画"◢"可靠（不依赖字体有没有那个字形） */
    private void renderResizeGrip(Draw g, Layout L, int mouseX, int mouseY) {
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
            g.drawString(this.fontRenderer, hint,
                    L.x() + L.w() - RESIZE_GRIP - 4 - this.fontRenderer.getStringWidth(hint),
                    L.y() + L.h() - RESIZE_GRIP + 3, 0xFF8A8AA0, false);
        }
    }

    /**
     * 面板上/下方的一句临时提示（"已清空"这类操作反馈）。
     *
     * 排版规则：内容宽度**定死为面板宽度**、自动换行、**最后一行贴着面板顶** ——
     * 旧实现是"单行 + 按文字宽度居中"，提示一长（"超出可绘制范围"就是长句）
     * 就横着冒出面板。现在整块宽度 = panelW、逐字符折行（中文没有空格，按词折会失败），
     * 块的最后一行贴在面板顶上；顶上放不下（面板贴着屏幕顶）就把整块挪到面板下方，
     * 首行贴着面板底 —— 与旧逻辑的"挪下去"一致，只是从一行变成了整块。
     * 折行结果按文本缓存：提示每帧都要画，逐字符算宽度不能每帧来一遍。
     */
    private void renderFlash(Draw g, int panelTop) {
        if (flash.isEmpty() || System.currentTimeMillis() > flashUntil) {
            return;
        }
        if (!flash.equals(flashWrappedFrom)) {
            flashLines = wrapText(flash, panelW - 8);
            flashWrappedFrom = flash;
        }
        int lineH = this.fontRenderer.FONT_HEIGHT;
        int blockH = flashLines.size() * lineH + 4;
        boolean above = panelTop - blockH >= 0;
        int x = panelX;
        int y0 = above ? panelTop - blockH : panelTop + panelH + 2;
        g.fill(x, y0, x + panelW, y0 + blockH, 0xE0202030);
        for (int i = 0; i < flashLines.size(); i++) {
            g.drawString(this.fontRenderer, flashLines.get(i), x + 4, y0 + 2 + i * lineH, 0xFFE6E6F0, false);
        }
    }

    /**
     * 面板顶边的 2px 光效条：
     * 根页淡绿 / 选格页（含数据页）淡蓝 / 图表页淡黄 / 计算页淡紫。
     * 实现 = 静态底条 + 一个从左到右移动的亮斑（15 段梯形 alpha），每帧约 16 次 fill ——
     * 与面板背景同量级，渲染压力可忽略（用户口径：压力大才降级成呼吸光效，实测不需要）。
     */
    private void renderTopGlow(Draw g, Layout L) {
        int base;
        switch (page) {
            case ROOT:
                base = COLOR_MAINSCREEN;
                break;
            case PICKER:
            case DATA:
                base = COLOR_BACKSLOT;
                break;
            case CHART:
                base = COLOR_CHART_GLOW;
                break;
            case REPLAY:
                base = COLOR_CALC_GLOW;
                break;
            default:
                // 经典 switch 不被 javac 视为穷尽（Page 目前只有 5 个值），这里兜底
                base = COLOR_MAINSCREEN;
                break;
}
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
            if (this.fontRenderer.getStringWidth(next) > maxW && !cur.isEmpty()) {
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
     * 为什么必须自己画 * 背景层是由 ClientHooks#drawScreen 渲染的，它给背景层传的鼠标坐标是
     * Integer.MAX_VALUE（为了不让背景层的控件误高亮），而 GuiContainer
     * 恰恰是在 render 里用这两个坐标算出 hoveredSlot 的
     * → 背景层的 hoveredSlot 永远是 null，格子不会亮、悬浮提示也不出来。
     * 我们在最顶层，手里有真实鼠标坐标，所以自己补上。
     *
     * 需要的全是公开 API（都已核实）：
     *   getGuiLeft() / getGuiTop() / getSlotColor(int)
     *   Slot.x / Slot.y / isActive() / isHighlightable()
     *   renderSlotHighlight(Draw,int,int,int,int)（public static）
     */
    private void renderBackgroundHover(Draw g, int mouseX, int mouseY, Layout L) {
        backgroundHoveredSlot = null;
        GuiContainer bg = backgroundContainer();
        if (bg == null || overPanel(mouseX, mouseY, L)) {
            return;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.inventorySlots.inventorySlots) {
            // 与 vanilla 的判定保持一致：isActive 决定"能不能交互"，
            // isHighlightable 决定"要不要画高亮"（部分模组用假槽位占位，两样都要看）
            if (!slot.isEnabled()) {
                continue;
            }
            int sx = left + slot.xPos;
            int sy = top + slot.yPos;
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
                backgroundHoveredSlot = slot;
                // 选格模式下由我们自己画"选中/可选中"的框，别叠上原版的高亮
                if (!cellMode) {
                    // ★ 只画**格子外侧**的光晕，格内 16×16 一个像素都不碰。
                    //
                    // 曾经这里是 vanilla 那套 `fill(0x80FFFFFF)` 整格填充 + 事后重画物品，
                    // 实测**重画物品不生效**（物品照旧被 50% 白洗掉；同一份重画代码在
                    // 选格覆盖层里却是好的，原因未定位到——两处坐标系、z、状态完全相同）。
                    //
                    // 结论：不要再依赖"事后再补画物品"这种和渲染顺序对抗的做法。
                    // 物品铺满整个 16×16 格子，**任何**格内填充都必然盖住它，
                    // 所以高亮只能画在格子外面：外圈半透明光晕 + 贴身 1px 实线，
                    // 视觉上仍是"这一格被点亮了"，但物品永远不被遮。
                    ring(g, sx - 2, sy - 2, 20, 20, 0x55FFFFFF);
                    ring(g, sx - 1, sy - 1, 18, 18, 0xFFFFFFFF);
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
    private void renderCellOverlay(Draw g, int mouseX, int mouseY) {
        if (!cellMode) {
            return;
        }
        GuiContainer bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.inventorySlots.inventorySlots) {
            if (!slot.isEnabled()) {
                continue;
            }
            int sx = left + slot.xPos - 1;
            int sy = top + slot.yPos - 1;
            boolean selectable = ContainerAccess.isSelectable(slot);
            boolean selected = selMenuSlots.contains(slot.slotNumber);
            boolean hover = mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18;

            if (selected) {
                g.fill(sx, sy, sx + 18, sy + 18, 0x4822CC88);
                ring(g, sx, sy, 18, 18, 0xFF3FE0A0);
                repaintSlotItem(g, slot, left, top);
            } else if (hover && selectable) {
                ring(g, sx, sy, 18, 18, 0xFFFFFFFF);
            } else if (!selectable) {
                // 玩家自己的背包格 / 模组假槽位：压暗，明确"这里点不了"
                g.fill(sx, sy, sx + 18, sy + 18, 0x38000000);
                // 压暗只该作用在格子底色上：物品照常清晰（与 vanilla 的不可用槽位一致），
                //   否则用户根本看不出那格里是什么
                repaintSlotItem(g, slot, left, top);
            }
        }
    }

    /**
     * 把某一格的物品重新画一遍，压在我们刚补的底色/高亮**之上**。
     *
     * 为什么需要 ★ vanilla 的绘制顺序是「格子底色 → 悬停高亮 → 物品」，
     * 也就是**高亮本来就该在物品下面**；而我们的浮层是在背景容器界面
     * **整体画完之后**才画的（Forge 的 GuiLayer 机制），补画高亮必然盖住物品。
     * 实测表现（实机反馈）：
     *   · 选格模式 → 已选格里的 4 个草方块被绿色块洗成一片，看不清是什么；
     *   · 非选格模式 → 悬停格的物品被 50% 白盖住（0x80FFFFFF）。
     *
     * 补画一次物品即可还原正确观感 —— 效果**等价于**"高亮在物品之下"，
     * 而且不必接管背景层的渲染管线（那条路要放弃 pushGuiLayer 自己维护父屏，
     * 输入转发/缩放/关闭语义全得重写，代价远大于收益）。
     *
     * z 抬高 1px：保证压在高亮之上又低于光标上的手持物品（那层用 232）。
     * 数量/耐久叠层要跟着重画，否则数字被底色吃掉。
     */
    private void repaintSlotItem(Draw g, Slot slot, int guiLeft, int guiTop) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) {
            return;
        }
        int sx = guiLeft + slot.xPos;
        int sy = guiTop + slot.yPos;
        g.pushZ(1.0F);
        g.renderItem(stack, sx, sy);
        g.renderItemDecorations(this.fontRenderer, stack, sx, sy);
        g.popZ();
    }

    private static void ring(Draw g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 自己把「鼠标手持的物品」画到真实鼠标位置上。
     *
     * 为什么必须自己画 * 背景容器界面是用 `Integer.MAX_VALUE` 当鼠标坐标渲染的，
     * 于是 `GuiContainer.render` 末尾那段"把 carried item 画在光标旁边"
     * 的代码把它画到了屏幕外 —— 表现就是实测反馈的
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
    private void renderCarriedItem(Draw g, int mouseX, int mouseY) {
        GuiContainer bg = backgroundContainer();
        if (bg == null) {
            return;
        }
        ItemStack carried = Minecraft.getMinecraft().player.inventory.getItemStack();
        if (carried.isEmpty()) {
            return;
        }
        int x = mouseX - 8;
        int y = mouseY - 8;
        g.pushZ(232.0F);
        g.renderItem(carried, x, y);
        // 1.18.2 没有 IClientItemExtensions（1.19 才引入），
        // 物品数量字体一律用界面默认字体 —— 极少数自定义数量字体的模组会显示成默认字形。
        g.renderItemDecorations(this.fontRenderer, carried, x, y);
        g.popZ();
    }

    /**
     * 被压下容器的槽位悬浮提示。
     * 同样是因为 `hoveredSlot` 在背景层恒为 null，vanilla 的 `renderTooltip` 也不干活。
     * 手持物品时按 vanilla 的规矩不显示。
     */
    private void renderBackgroundTooltip(Draw g, int mouseX, int mouseY) {
        Slot slot = backgroundHoveredSlot;
        GuiContainer bg = backgroundContainer();
        if (slot == null || bg == null || !slot.getHasStack()) {
            return;
        }
        if (!Minecraft.getMinecraft().player.inventory.getItemStack().isEmpty()) {
            return;
        }
        // 1.18.2 的 GuiScreen#renderTooltip 收 MatrixStack，不收 GuiGraphics（本项目里由 Draw 包着矩阵）
        this.renderToolTip(slot.getStack(), mouseX, mouseY);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    private boolean overPanel(double mouseX, double mouseY, Layout L) {
        return isIn((int) mouseX, (int) mouseY, L.x(), L.y(), L.w(), L.h());
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws java.io.IOException {
        if (!handleClick(mouseX, mouseY, button)) {
            super.mouseClicked(mouseX, mouseY, button);
        }
    }

    /** 1.16.5 的返回值（true = 已处理）在这里只用于决定要不要转交给 super */
    private boolean handleClick(double mouseX, double mouseY, int button) {
        Layout L = layout();

        // 面板之外一律让给被压住的容器界面 // 这样"面板打开时机器还能操作"。可行性依据：GuiContainer#mouseClicked
        // 内部用 findSlot(mouseX, mouseY) 现算坐标、**不依赖 hoveredSlot**
        // （而背景层的 hoveredSlot 恰好恒为 null），所以转发是有效的。
        //
        // 例外：选格模式。需求要的是"光标禁用物品交互"，所以这一支什么都转交，
        //   只用来做可视化选格（否则点一格就会顺手把物品拿起来）。
        if (!overPanel(mouseX, mouseY, L)) {
            if (cellMode) {
                handleCellClick(mouseX, mouseY, button);
                return true;
            }
            GuiContainer bg = backgroundContainer();
            // 1.12.2：GuiContainer#mouseClicked 是 protected，跨包调不到（见 handleClick 的注释）
            return false;
        }

        // 右键在波形上 = 加 / 删标记（G4）// 需求原文是"左击打标记、双击删除"，但左键已经用于框选区间，双击又很难
        // 与两次单击区分 —— 用户自己提出改右键：右键空白处加标记，右键已有标记
        // （±3px）删掉，加与删是同一个动作，也不用和框选抢键位。
        if (button == 1 && (page == Page.CHART || page == Page.REPLAY) && L.hasChart()
                && ChartRenderer.inPlot(L.x() + PAD, L.chartTop(), L.w() - 2 * PAD, L.chartH(), mouseX, mouseY)) {
            toggleMarker((int) mouseX, L);
            return true;
        }

        // QoL：计算页右键图例 = 按下该图例的「分析」。
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

        // QoL：DATA 页右键值条目 = **按数值读取**口径，把该路径**填入值格**
        // （不直接加入追踪：填好后可以先设名称、再「确认添加」）。
        // 必须在下面 `button != 0` 早退**之前** —— 右键分支曾写在只接左键的
        // {@link #handleDataClick} 里而成为死代码（实机表现为"右键无反应"），勿再下移。
        if (button == 1 && page == Page.DATA
                && handleDataValueRightClick((int) mouseX, (int) mouseY, L)) {
            return true;
        }

        if (button != 0) {
            return true;
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        // 存档筛选框：左键点到框外 → 收起焦点（框内点击在下面 ROOT 段重新聚焦）// 放在一切命中判定之前：点任何别的控件都先让输入框失焦，"焦点跟着点击走"。
        if (archFilterFocus && !inArchFilter(L, mx, my)) {
            archFilterFocus = false;
        }
        // 选格页筛选框同理
        if (pickerFilterFocus && !inPickerFilter(L, mx, my)) {
            pickerFilterFocus = false;
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
                        case PopupRow.IO:
                            toggleIo(legendPopup);
                            break;
                        case PopupRow.ANALYZE:
                            replaySelectedItem =
                                                        legendPopup.equals(replaySelectedItem) ? null : legendPopup;
                            break;
                        default:
                            toggleSeries(legendPopup);
                            break;
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
            // 根页上任何一次点击都先解除"待确认删除"：只有**接着点同一个 ×** 才算确认，
            // 否则点别处（别的行 / 别的按钮 / 空白）都自动反悔 —— 存档是用户的文件，不能一点就没。
            String armedBeforeClick = archiveDeleteArmed;
            archiveDeleteArmed = "";
            // 筛选框：点在框上 → 聚焦（吃掉这次点击，不触发别的）
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
                    Archives.Info info = arch.get(idx);
                    // 删除按钮优先命中（它在行内右侧，先判它）
                    if (isIn(mx, my, archDeleteX(L), ry - 1, DEL_W, ROW_H)) {
                        clickArchiveDelete(info, info.dirName().equals(armedBeforeClick));
                        return true;
                    }
                    if (isIn(mx, my, L.x() + 4, ry - 1, L.w() - 8 - DEL_W - 2, ROW_H)) {
                        openArchive(info);
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
                // 最右侧的「高亮」按钮先判（它在删除按钮左边，两者不重叠但要按顺序判）
                if (isIn(mx, my, probeHlX(L), ry - 1, HL_W, ROW_H)) {
                    toggleProbeHighlight(list.get(idx));
                    return true;
                }
                // 删除按钮优先命中（它在行内右侧，先判它）
                if (isIn(mx, my, deleteX(L), ry - 1, DEL_W, ROW_H)) {
                    ProbeManager mgr = LogiScopeMod.probes();
                    ProbeInstance p = list.get(idx);
                    mgr.remove(p.id());
                    // 探针没了，它的高亮框也不该继续画（否则那个方框会永远留在世界里）
                    if (ProbeHighlight.isActiveFor(p.id())) {
                        ProbeHighlight.clear();
                    }
                    say(tr("logiscope.flash.probe_deleted").replace("%s", p.name()));
                    return true;
                }
                if (isIn(mx, my, L.x() + 4, ry - 1, L.w() - 8 - DEL_W - HL_W - 4, ROW_H)) {
                    openProbe(list.get(idx));
                    return true;
                }
            }
        } else if (page == Page.PICKER) {
            if (source == Source.NONE) {
                // 这一页此时只画了一句提示，没有可点的行
                return true;
            }
            if (inPickerIgnore(L, mx, my)) {
                pickerIgnoreEmpty = !pickerIgnoreEmpty;
                scrollRow = 0;
                return true;
            }
            if (inPickerFilter(L, mx, my)) {
                pickerFilterFocus = true;
                return true;
            }
            List<ContainerAccess.SlotView> list = pickerVisible();
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
        // 离开根页就把"待确认删除"清掉：回来之后不该一次点击就删掉存档
        archiveDeleteArmed = "";
        if (p.hasAnything()) {
            page = Page.CHART;
            live = true;
            selection = null;
            selectionStats = java.util.Collections.emptyList();
            userZoomed = false;
        } else {
            enterPicker(p);
        }
        legendPopup = null;
        builtVersion = Long.MIN_VALUE;
    }

    /** 选格页点了一行：切换这一格 */
    private void toggleRow(ContainerAccess.SlotView v) {
        if (rsMode) {
            // RS 网格：这一页的每一行**都是**网络里的条目（行来自界面的 IGridView，
            // 见 RsKeys），所以不需要 AE 那条"必须落在 RepoSlot 上"的守卫。
            if (v.empty()) {
                return;
            }
            if (!selectedNetIds.remove(v.itemId())) {
                selectedNetIds.add(v.itemId());
            }
            return;
        }
        if (aeMode) {
            // AE 网络：只有网络列表的显示格（RepoSlot）能选 ——
            // 背包/合成格里的东西不是"网络中的物品"，点了忽略（不弹错，误触不该有反馈负担）。
            if (v.empty()) {
                return;
            }
            Container menu = ContainerAccess.currentMenu();
            if (menu == null || v.menuSlotIndex() < 0 || v.menuSlotIndex() >= menu.inventorySlots.size()) {
                return;
            }
            if (!menu.inventorySlots.get(v.menuSlotIndex()).getClass().getName().endsWith(".RepoSlot")) {
                return;
            }
            if (!selectedNetIds.remove(v.itemId())) {
                selectedNetIds.add(v.itemId());
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
     * 为什么要按线段采样、而不是只判当前位置 *   鼠标事件是按帧来的，快速拖动时相邻两帧的坐标可能已经跨过两三个格子 ——
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
        if (netMode()) {
            return;   // 网络模式没有"容器格"语义（cell_mode 按钮也已禁用，双保险）
        }
        if (add) {
            selMenuSlots.add(slot.slotNumber);
        } else {
            selMenuSlots.remove(slot.slotNumber);
        }
        syncContainerSelection();
    }

    /** 鼠标落在容器界面的哪个槽位上（与 vanilla 的 findSlot 判定一致：±1px 容差） */
    @Nullable
    private Slot slotAt(double mouseX, double mouseY) {
        GuiContainer bg = backgroundContainer();
        if (bg == null) {
            return null;
        }
        int left = bg.getGuiLeft();
        int top = bg.getGuiTop();
        for (Slot slot : bg.inventorySlots.inventorySlots) {
            if (!slot.isEnabled()) {
                continue;
            }
            int sx = left + slot.xPos;
            int sy = top + slot.yPos;
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) {
                return slot;
            }
        }
        return null;
    }

    private void beginSelection(int mx) {
        // 必须先取窗口、再置 selecting // window() 的第一分支就是"框选中 → 返回锁住的窗口"，
        // 先置位的话这里读到的是上一次的残留值（本会话首次是初始值 0,0）
        // → 窗口被锁成 [0,0] → 波形立刻变"这一区间没有数据"，
        //   且永远进不了冻结态（下游表现：M16 的"实时"按钮看起来点不动）。
        // 这正是四条框选失败用例的共同根因 ——
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
            selectionStats = java.util.Collections.emptyList();
            return;
        }

        // 建立选择就冻结视图 // 否则波形会继续往前滚，刚框住的那段几秒后就跑出屏幕了。
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
     * 为什么"完成"这一步同时是"创建"那一步的终点，而不是在建实例时就把目标定死 *   因为槽位是**在选格页里才选出来的**。实例只是个容器（放状态机和缓冲），
     *   目标在点「完成」时才确定 —— 这样"点了创建探针又反悔"只需要把空实例删掉。
     */
    private void commitPicker() {
        ProbeManager mgr = LogiScopeMod.probes();
        ProbeInstance p = mgr.find(pickerProbeId);
        if (p == null) {
            page = Page.ROOT;
            return;
        }

        // NBT 槽位型容器// 这类机器（MEK 等）把槽位号写在 NBT 的 `slot` 字段里，菜单位置与容器下标都可能
        // 对不上。所以**不组 SlotRef**，改成："把点到的格子按内容反查成 slot 号"，
        // 之后无论 GUI 开还是关，都只按 slot 号读 NBT —— 一条路，不会自相矛盾。
        if (netMode()) {
            commitNetworkTargets(p);
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
                    v.itemId(), (int) v.count()));
            forVerify.add(v);
        }
        if (refs.isEmpty()) {
            for (ContainerAccess.SlotView v : worldSlots) {
                if (!selContainerSlots.contains(v.containerSlotIndex())) {
                    continue;
                }
                // 无 GUI 容器没有菜单槽下标 → -1，只能走世界侧读取
                refs.add(new ProbeTarget.SlotRef(-1, v.containerSlotIndex(), v.itemId(), (int) v.count()));
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
        selectionStats = java.util.Collections.emptyList();
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
     * 两条语义：
     *   1. **覆写，不是追加**：界面上勾了几格，探针就只测那几格 —— 追加语义会
     *      让用户永远删不掉不想要的格子（只能去数据页一格一格 ×）。
     *      **纯数据目标（数据页加的 FE / 流体）不受影响** —— 那是另一条线。
     *   2. **宁可不选，也不给一个错的 slot 号**：全部判据都要求"内容"对上，
     *      对不上就跳过并在提示里报数（见 {@link #resolveNbtSlot}）。
     *
     * @see SlotAlign 为什么"顺序 + 内容"能定，而"容器下标"不能
     */
    /**
     * AE 模式的「完成」：**覆写**全部 AE 网络目标 ——
     * 界面上选了哪些物品，探针就只测哪些（与槽位锚点同一覆写语义）。
     * DATA 页的纯路径目标与 NBT 槽位锚点保留；一个都没选 = 什么都不动
     * （"读不到数据" ≠ "用户想清空"，同一纪律）。
     *
     * **没有锚点就拒绝提交**：AE 读值靠"这台设备方块所在的网络"
     * （{@code AeNetworkReader} 走 AE2 的 node host 查询，入参是 level + pos），
     * 而采样路径在维度为 null 时直接早退 —— 存下去只会得到一个
     * **永远 no_data、连状态都显示 RECORDING 的空目标**。什么时候没有锚点：用无线终端
     * 或快捷键打开的界面（客户端没有右键方块那一刻，见 {@code ProbeContext} 的第三种
     * 来源）；右键方块终端打开则一定有坐标。0.1.3 的闪退就是这里把 null 塞进了目标。
     */
    private void commitNetworkTargets(ProbeInstance p) {
        if (selectedNetIds.isEmpty()) {
            say(tr(rsMode ? "logiscope.flash.rs_none" : "logiscope.flash.ae_none"));
            return;
        }
        ProbeTarget t = p.target();
        if (!t.hasPersistentId()) {
            say(tr(rsMode ? "logiscope.flash.rs_need_anchor" : "logiscope.flash.ae_need_anchor"));
            return;
        }
        if (!anchorIsDevice(t)) {
            return;   // 提示已在里面说过
        }
        List<DataTarget> next = new ArrayList<DataTarget>();
        for (String id : selectedNetIds) {
            next.add(rsMode
                    ? DataTarget.rsNetwork(id, t.deviceName(), t.dimension(), t.pos())
                    : DataTarget.aeNetwork(id, t.deviceName(), t.dimension(), t.pos()));
        }
        int total = p.replaceNetworkTargets(next);

        ProbeTarget old = p.target();
        // 槽位清空：AE 目标只走"网络读值"一条路，不碰菜单/坐标槽位 ——
        // 与 NBT 锚点同一条纪律：两条路读数必然不一致，就只留一条。
        p.configure(new ProbeTarget(java.util.Collections.emptyList(), old.deviceName(), old.dimension(), old.pos()), null);
        LogiScopeMod.probes().setActive(p.id());

        hiddenItems.clear();
        ioShown.clear();
        legendPopup = null;
        selection = null;
        selectionStats = java.util.Collections.emptyList();
        live = true;
        userZoomed = false;
        builtVersion = Long.MIN_VALUE;
        cellMode = false;
        page = Page.CHART;
        say(tr(rsMode ? "logiscope.flash.rs_replaced" : "logiscope.flash.ae_replaced")
                .replace("%s", String.valueOf(total)));
    }

    /**
     * **提交前的锚点校验：锚点上的方块必须真的是一台可读的网络设备。**
     *
     * 起因：{@code ContainerAnchor} 的锚点来自右键方块事件，手持便携网格对着地面右键时
     * 那次右键也算数 —— 于是"有锚点"却指向一块泥土，目标存下来恒 no_data 且无任何提示。
     *
     * 判据的强弱按"会不会误拒"分：跨维度 / 没世界 → **放行**（验不了不凭空拒绝）；
     * 方块没有方块实体 → 拒绝；RS 模式下不是 RS 网络方块 → 拒绝
     * （与读值器同一个判据 {@link RsNetworkReader#isNetworkBlock}）；
     * AE 模式下 BE 存在 → 放行（本线不做 AE，先不引入可能误拒的判定）。
     */
    private boolean anchorIsDevice(ProbeTarget t) {
        Minecraft mc = Minecraft.getMinecraft();
        World level = mc.world;
        if (level == null || t.dimension() == null || t.pos() == null
                || !Dim.of(level).equals(t.dimension())) {
            return true;   // 验不了（跨维度 / 没世界）→ 不凭空拒绝
        }
        BlockPos pos = t.pos();
        TileEntity be = level.getTileEntity(pos);
        boolean ok = be != null && (!rsMode || RsNetworkReader.isNetworkBlock(be));
        if (ok) {
            return true;
        }
        String what = ProbeContext.blockName(level, pos);
        say(tr(rsMode ? "logiscope.flash.rs_anchor_not_grid" : "logiscope.flash.ae_anchor_not_device")
                .replace("%s", what));
        return false;
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
            String name = v.empty() || v.itemId() == null || v.itemId().trim().isEmpty()
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
        p.configure(new ProbeTarget(java.util.Collections.emptyList(), old.deviceName(), old.dimension(), old.pos()), null);
        int total = p.replaceSlotAnchors(next);
        LogiScopeMod.probes().setActive(p.id());

        hiddenItems.clear();
        ioShown.clear();
        legendPopup = null;
        selection = null;
        selectionStats = java.util.Collections.emptyList();
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
     * 把"点到的这一格"翻译成"数据里的 slot 号"。
     * 判据链本体在纯类里（{@link SlotAlign#resolve}，离线有断言 t33），这里只做装配：
     * 空格子的 itemId 常被填成 {@code minecraft:air}，所以"空不空"必须用 {@code v.empty()} 判。
     */
    @Nullable
    private Integer resolveNbtSlot(ContainerAccess.SlotView v, int ordinal,
                                   List<NbtSlotLayout.Entry> entries,
                                   SlotAlign.Result align, boolean idxUsable) {
        boolean empty = v.empty() || v.itemId() == null || v.itemId().trim().isEmpty();
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
            if (v.empty() || v.itemId() == null || v.itemId().trim().isEmpty()) {
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
        nbtSlotMode = false;      // 每次进选格页都回到"默认关"（需求明确要求默认不启用）
        aeMode = false;           // 网络模式跟随菜单（重开时由 seedSelectionOnce 重新检测）
        rsMode = false;
        nbtSeedPending = false;
        nbtSlotListPath = "";
        nbtSlotEntries = java.util.Collections.emptyList();
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
     * 顺序有讲究 * 1. 先写文件 —— **写失败就什么都不动**（探针与数据原地保留），绝不出现
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
            // 身份用目录名（永不改），显示名可用 name.txt 覆盖
            replayDir = info.dirName();
            replayName = info.displayName().trim().isEmpty() ? info.dirName() : info.displayName();
            archiveDeleteArmed = "";
            say(tr("logiscope.flash.replay_loaded").replace("%s", replayName));
        } catch (Exception e) {
            LogiScopeMod.LOGGER.error("[LogiScope] archive load failed: {}", info.dirName(), e);
            say(tr("logiscope.flash.archive_failed"));
        }
    }

    /**
     * 存档行的删除按钮。**两段式**：第一下只是"待确认"（按钮变红「!」+ 提示），
     * 第二下点同一个按钮才真删目录 —— 存档是用户磁盘上的文件，误删不可撤销。
     *
     * @param armed 这一次点击**之前**这一行就已经是待确认状态
     */
    private void clickArchiveDelete(Archives.Info info, boolean armed) {
        String shown = info.displayName().trim().isEmpty() ? info.dirName() : info.displayName();
        if (!armed) {
            archiveDeleteArmed = info.dirName();
            say(tr("logiscope.flash.arch_delete_confirm").replace("%s", shown));
            return;
        }
        if (Archives.delete(info.dirName())) {
            // 删掉的正好是当前载入的回放 → 把回放状态一起收掉，
            // 否则回放页还挂着一份"目录已经不在了"的数据
            if (info.dirName().equals(replayDir)) {
                replayArchive = null;
                replayDir = "";
            }
            archiveList = Archives.scan();
            scrollArchives = Math.max(0, Math.min(scrollArchives,
                    Math.max(0, archiveList.size() - 1)));
            say(tr("logiscope.flash.arch_deleted").replace("%s", shown));
        } else {
            say(tr("logiscope.flash.arch_delete_fail").replace("%s", shown));
        }
    }

    /**
     * 进入回放页：把存档数据一次性建好（ChartData / Summary / 标记 / 视图状态）。
     *
     * 回放复用图表页的全部机制 * 数据塞进同一个 {@code chartData} 字段之后，窗口 / 框选 / 十字线 / 图例 /
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
        selectionStats = java.util.Collections.emptyList();
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
                : currentProbe() != null ? currentProbe().markers().toList() : java.util.Collections.emptyList();
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
            case "create":
                createProbe();
                break;
            case "open_folder":
                openStorageFolder();
                break;
            case "open_archive_folder":
                openArchiveFolder();
                break;
            case "cell_mode":
            {
                cellMode = !cellMode;
                cellDragButton = -1;
                if (cellMode) {
                    say(tr("logiscope.flash.cell_mode_on"));
                }
            }
            break;
            case "clear_cells":
            {
                selMenuSlots.clear();
                selContainerSlots.clear();
                selectedNetIds.clear();
                say(tr("logiscope.flash.cells_cleared"));
            }
            break;
            case "done":
                commitPicker();
                break;
            case "to_root":
                leavePicker();
                break;
            case "list":
            {
                // 图表页的「列表」：回根页挑别的探针
                // 
                cellMode = false;
                cellDragButton = -1;
                page = Page.ROOT;
            }
            break;
            case "pick":
            {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    cellMode = false;
                    cellDragButton = -1;
                    enterPicker(p);
                }
            }
            break;
            case "pause":
            {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    p.pause();
                    say(tr("logiscope.flash.paused"));
                }
            }
            break;
            case "resume":
            {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    p.resume();
                    say(p.state() == ProbeInstance.State.PAUSED
                            ? tr("logiscope.flash.resume_failed")
                            : tr("logiscope.flash.resumed"));
                }
            }
            break;
            case "clear":
            {
                ProbeInstance p = currentProbe();
                if (p != null) {
                    p.clearData();
                    selection = null;
                    selectionStats = java.util.Collections.emptyList();
                    live = true;
                    userZoomed = false;
                    say(tr("logiscope.flash.cleared"));
                }
            }
            break;
            case "follow":
            {
                mgr.setFollowAfterClose(!mgr.followAfterClose());
                say(followLabel());
            }
            break;
            case "archive":
                stopAndArchive();
                break;
            case "replay_back":
                page = Page.ROOT;
                break;
            case "calc_obj":
            {
                // 数量 ↔ IO，一次只算一个（切过去时缓存键变了，结果框自动重算）
                analyzeIo = !analyzeIo;
            }
            break;
            case "calc_op":
                opPopupOpen = !opPopupOpen;
                break;
            case "probe_data":
                enterDataPage();
                break;
            case "data_refresh":
            {
                ProbeInstance src = dataSrcProbe();
                if (src != null) {
                    DataCommand.request(src.target().dimension(), src.target().pos());
                    say(tr(DataCommand.statusKey()));
                }
            }
            break;
            case "data_confirm":
                confirmDataTarget();
                break;
            case "nbt_mode":
            {
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
            break;
            case "name_style":
            {
                // 一个开关同时管选格页 / 图例 / 计算页；写回 config，下次启动保持。
                // 不碰任何已记录的名字（身份串不变），见 NameText 的类注释。
                NameText.toggle();
                // 计算页把「名字 + 运算」拼好缓存在 analyzeTitle 里（键是 区间×曲线×对象×运算），
                // 换风格不在这四个键里 —— 不主动作废的话标题会一直停在旧写法。
                aLo = Long.MIN_VALUE;
                say(tr("logiscope.flash.name_style",
                        tr(NameText.showDisplay() ? "logiscope.name.display" : "logiscope.name.registry")));
            }
            break;
            case "rename":
            {
                // 点一下弹输入框（不占常规布局，也就不干扰图表交互 —— 需求原文）
                renameFocus = true;
                renameBuf = replayName;
            }
            break;
            case "data_back":
            {
                cellMode = false;
                page = Page.PICKER;
            }
            break;
            case "close":
                closeScope();
                break;
            case "live":
            {
                // 回到实时 = 放弃选择。两者不并存（选择会冻结视图，见 endSelection）
                live = true;
                selection = null;
                selectionStats = java.util.Collections.emptyList();
                say(tr("logiscope.flash.live"));
            }
            break;
            case "zoom_in":
                zoom(true);
                break;
            case "zoom_out":
                zoom(false);
                break;
            case "fit":
            {
                long span = Math.max(MIN_WINDOW, chartData.spanTicks() + 1);
                windowTicks = (int) Math.min(MAX_WINDOW, span);
                live = false;
                frozenEnd = Math.max(chartData.lastTick(), chartData.firstTick());
                userZoomed = false;
                say(tr("logiscope.flash.fit"));
            }
            break;
            case "clear_sel":
            {
                selection = null;
                selectionStats = java.util.Collections.emptyList();
                say(tr("logiscope.flash.sel_cleared"));
            }
            break;
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
        Minecraft mc = Minecraft.getMinecraft();
        ProbeContext.Candidate cand = ProbeContext.current(mc);
        if (cand == null) {
            say(tr("logiscope.flash.no_target"));
            return;
        }
        ProbeInstance created = mgr.create(new ProbeTarget(java.util.Collections.emptyList(), cand.label(), cand.dimension(), cand.pos()));
        if (created == null) {
            say(tr("logiscope.flash.too_many_probes"));
            return;
        }
        // MEK 设备：自动开启 NBT 追踪 + 提示 // MEK 的机器没有可从外部列出的容器槽位（worldSlots 为空），槽位号写在 NBT 里 ——
        // 用户点名：创建这类设备的探针时直接开好，别让人手动按按钮再猜。
        // 判据 = 准星所指方块的注册命名空间（mekanism）；NBT 识别本身仍靠形状（CP24）。
        net.minecraft.block.Block anchorBlock = mc.world == null || !cand.hasAnchor()
                ? null : mc.world.getBlockState(cand.pos()).getBlock();
        if (anchorBlock != null && anchorBlock.getRegistryName() != null
                && "mekanism".equals(anchorBlock.getRegistryName().getResourceDomain())) {
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
     * 缩放。
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
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (!handleDrag(mouseX, mouseY, clickedMouseButton)) {
            super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        }
    }

    private boolean handleDrag(double mouseX, double mouseY, int button) {
        double dragX = 0;
        double dragY = 0;
        // 刷新滑条拖动：优先于面板外转交（拖出面板也继续跟手）
        if (dataSliderDrag && button == 0) {
            applyDataSlider((int) mouseX, layout());
            return true;
        }
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
        return false;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (!handleRelease(mouseX, mouseY, button)) {
            super.mouseReleased(mouseX, mouseY, button);
        }
    }

    private boolean handleRelease(double mouseX, double mouseY, int button) {
        if (dataSliderDrag && button == 0) {
            dataSliderDrag = false;
            return true;
        }
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
        // 松手同样要转交，否则容器的拖拽状态会卡住（GuiContainer 里
        // 用 mouseReleased 收尾 quick-craft）
        return false;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0) {
            return;
        }
        // 1.12.2 的滚轮只能从 Mouse 事件里读；坐标要按当前 GUI 缩放反算
        int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        handleScroll(mx, my, dWheel > 0 ? 1 : -1);
    }

    private boolean handleScroll(double mouseX, double mouseY, double scrollY) {
        // 1.20.1 的 mouseScrolled 为单滚动参数（1.21.1 线拆为 scrollX/scrollY）
        Layout L = layout();
        if (overPanel(mouseX, mouseY, L)) {
            int dir = (int) Math.signum(scrollY);
            if (dir == 0) {
                return true;
            }
            // 悬浮详情框里的长列表：滚它（需求"文本过多时能上下翻"）
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
                // 滚轮 = 缩放：
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
        // 1.12.2 的 GuiContainer 没有 mouseScrolled（滚轮走 handleMouseInput），
        // 所以面板外滚轮**无法**转交给容器 —— 已知限制，记在 docs/PORT-1.12.2.md。
        return false;
    }

    private boolean handleCharTyped(char codePoint) {
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
        if (pickerFilterFocus && !Character.isISOControl(codePoint)) {
            if (pickerFilter.length() < 48) {
                pickerFilter += codePoint;
                scrollRow = 0;
            }
            return true;
        }
        if (page == Page.DATA && dataFocus != 0 && !Character.isISOControl(codePoint)) {
            if (dataFocus == 1) {
                if (dataPath.length() < 120) {
                    dataPath += codePoint;
                }
                dataForceNumeric = false;   // 手输 = 自动口径（要强制就重新右键标）
            } else if (dataName.length() < 48) {
                dataName += codePoint;
                dataNamePath = null;   // 手输名称 → 不再跟 NBT 走（手输优先）
            }
            return true;
        }
        return false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        // 1.12.2 没有单独的 charTyped 事件：字符与功能键都从这一个入口进来
        if (typedChar != 0 && !Character.isISOControl(typedChar) && handleCharTyped(typedChar)) {
            return;
        }
        if (handleKeyPressed(keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private boolean handleKeyPressed(int keyCode) {
        // 焦点别留在已经离开的页面上 // 重命名框只活在回放页；用户点了「重命名」再点「返回列表」时，输入框不再绘制
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
                case Keyboard.KEY_BACK:
                {   // Backspace
                    renameBuf = chop(renameBuf);
                    return true;
                }
                case Keyboard.KEY_RETURN:
                case Keyboard.KEY_NUMPADENTER:
                {   // Enter / 小键盘 Enter → 确认
                    commitRename();
                    return true;
                }
                case Keyboard.KEY_ESCAPE:
                {   // Esc → 取消
                    renameFocus = false;
                    return true;
                }
                case Keyboard.KEY_V:
                {   // Ctrl+V
                    if (isCtrlKeyDown()) {
                        try {
                            String clip = getClipboardString();
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
                break;
            }
            return true;   // 输入框激活时吃掉所有按键，免得误触发别的交互
        }
        if (archFilterFocus) {
            switch (keyCode) {
                case Keyboard.KEY_BACK:
                {   // Backspace
                    archFilter = chop(archFilter);
                    scrollArchives = 0;
                    return true;
                }
                case Keyboard.KEY_RETURN:
                case Keyboard.KEY_NUMPADENTER:
                {   // Enter / 小键盘 Enter → 确认：保留筛选、收起焦点
                    archFilterFocus = false;
                    return true;
                }
                case Keyboard.KEY_ESCAPE:
                {   // Esc → 取消：清空筛选并收起
                    archFilterFocus = false;
                    archFilter = "";
                    scrollArchives = 0;
                    return true;
                }
                case Keyboard.KEY_V:
                {   // Ctrl+V
                    if (isCtrlKeyDown()) {
                        pasteIntoArchFilter();
                        return true;
                    }
                }
                break;
            }
            return true;   // 输入框激活时吃掉所有按键（与重命名框一致）
        }
        if (pickerFilterFocus) {
            switch (keyCode) {
                case Keyboard.KEY_BACK:
                {   // Backspace
                    pickerFilter = chop(pickerFilter);
                    scrollRow = 0;
                    return true;
                }
                case Keyboard.KEY_RETURN:
                case Keyboard.KEY_NUMPADENTER:
                {   // Enter → 保留筛选、收起焦点
                    pickerFilterFocus = false;
                    return true;
                }
                case Keyboard.KEY_ESCAPE:
                {   // Esc → 清空筛选并收起
                    pickerFilterFocus = false;
                    pickerFilter = "";
                    scrollRow = 0;
                    return true;
                }
                case Keyboard.KEY_V:
                {   // Ctrl+V
                    if (isCtrlKeyDown()) {
                        pasteIntoPickerFilter();
                        return true;
                    }
                }
                break;
            }
            return true;
        }
        if (page == Page.DATA && dataFocus != 0) {
            switch (keyCode) {
                case Keyboard.KEY_BACK:
                {   // Backspace
                    if (dataFocus == 1) {
                        dataPath = chop(dataPath);
                        dataForceNumeric = false;   // 手输同口径：回到自动
                    } else {
                        dataName = chop(dataName);
                    }
                    return true;
                }
                case Keyboard.KEY_ESCAPE:
                case Keyboard.KEY_RETURN:
                case Keyboard.KEY_NUMPADENTER:
                {   // Esc / Enter / 小键盘 Enter → 收起焦点
                    dataFocus = 0;
                    return true;
                }
                case Keyboard.KEY_V:
                {   // Ctrl+V：路径又长又难打，支持粘贴
                    if (isCtrlKeyDown()) {
                        pasteIntoFocusedField();
                        return true;
                    }
                }
                break;
            }
        }
        return false;
    }

    private static String chop(String s) {
        return s.isEmpty() ? s : s.substring(0, s.length() - 1);
    }

    private void pasteIntoFocusedField() {
        try {
            String clip = getClipboardString();
            if (clip == null) {
                return;
            }
            clip = clip.trim();
            if (dataFocus == 1) {
                dataPath = clip.length() > 120 ? clip.substring(0, 120) : clip;
                dataForceNumeric = false;   // 手输同口径：回到自动
            } else {
                dataName = clip.length() > 48 ? clip.substring(0, 48) : clip;
                dataNamePath = null;
            }
        } catch (Exception ignored) {
            // 剪贴板不可用时静默（不该因为粘贴失败把界面搞崩）
        }
    }

    /** 粘贴进选格页筛选框（同上：去首尾空白、限长 48） */
    private void pasteIntoPickerFilter() {
        try {
            String clip = getClipboardString();
            if (clip == null) {
                return;
            }
            clip = clip.trim();
            pickerFilter = clip.length() > 48 ? clip.substring(0, 48) : clip;
            scrollRow = 0;
        } catch (Exception ignored) {
            // 剪贴板不可用时静默
        }
    }

    /** 粘贴进存档筛选框，同其他输入框：去首尾空白、限长 48 */
    private void pasteIntoArchFilter() {
        try {
            String clip = getClipboardString();
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
    // 存档重命名
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
                    replayName = i.displayName().trim().isEmpty() ? i.dirName() : i.displayName();
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
    private void renderRenameBox(Draw g, Layout L) {
        if (!renameFocus || !L.hasChart()) {
            return;
        }
        int x = L.x() + PAD + 4;
        int y = L.chartTop() + 4;
        int w = Math.min(L.w() - 2 * PAD - 8, 260);
        String label = tr("logiscope.rename.label");
        g.fill(x, y, x + w, y + 26, 0xF2181822);
        ring(g, x, y, w, 26, COLOR_ANALYZE);
        g.drawString(this.fontRenderer, label, x + 5, y + 3, 0xFF8A8AA0, false);
        String shown = renameBuf;
        int avail = w - 10;
        String draw = this.fontRenderer.getStringWidth(shown) <= avail ? shown : cut(shown, avail);
        g.drawString(this.fontRenderer, draw, x + 5, y + 13, 0xFFE0E0EC, false);
        if ((LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = x + 5 + Math.min(this.fontRenderer.getStringWidth(shown), avail);
            g.fill(cx, y + 12, cx + 1, y + 21, 0xFFE0E0EC);
        }
        g.drawString(this.fontRenderer, tr("logiscope.rename.hint"), x + w + 6, y + 13, 0xFF9099A8, false);
    }

    // ==================================================================
    // 悬浮详情：+N / data xN / 槽列表 —— 鼠标悬浮出完整列表
    // ==================================================================

    /**
     * 解析抓起到的 NBT，判断这是不是"NBT 自带 slot 号"的容器。
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
        nbtSlotEntries = layout == null ? java.util.Collections.emptyList() : layout.entries();
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
            String item = ref.itemId() == null || ref.itemId().trim().isEmpty()
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
        // --- 0) 置灰的「Pick cells」也要能问出原因 ---
        // 只把它变灰而不解释，用户只会以为按钮坏了。这条不受 enabled 影响。
        if (page == Page.PICKER) {
            for (Chip c : L.buttons()) {
                if (c.id().equals("cell_mode") && c.hit(mouseX, mouseY)) {
                    List<String> lines = new ArrayList<>();
                    lines.add(tr("logiscope.tip.cell_mode_broken_hint"));
                    wantTip(6, tr("logiscope.tip.cell_mode_broken"), lines);
                    return;
                }
            }
        }

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
            ProbeInstance p;
            switch (page) {
                case PICKER:
                    p = pickerProbe();
                    break;
                case DATA:
                    p = dataSrcProbe();
                    break;
                case CHART:
                    p = currentProbe();
                    break;
                default:
                    p = null;
                    break;
}
            if (p != null) {
                wantTip(2, probeTipTitle(p), probeTipLines(p));
                return;
            }
        }

        // --- 2.5) 网络模式第二行（黄色提示行）→ 悬浮显示说明---
        if (page == Page.PICKER && netMode()
                && mouseY >= L.infoTop() + ROW_H && mouseY < L.infoTop() + 2 * ROW_H) {
            wantTip(4, tr(rsMode ? "logiscope.picker.rs_mode" : "logiscope.picker.ae_mode"),
                    java.util.Arrays.asList(tr(rsMode ? "logiscope.picker.rs_hint" : "logiscope.picker.ae_hint")));
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
    /**
     * 悬浮详情框。
     *
     * ★ 两条硬约束（实测反馈：提示条比窗口还宽，右边被切掉看不清）：
     *   1. **整框限制在游戏窗口内** —— 过去只按"面板矩形"夹，面板贴着屏幕边时框就跑出去了；
     *   2. **长文本按像素宽度折行**（{@link #wrapText}），不是截断 —— 一行 150 个字也读得完。
     * 折行在**画之前**做，因为它会改变行数 ⇒ 高度、滚动上限都得按"折行后的行数"算。
     */
    private void renderTip(Draw g, Layout L, int mouseX, int mouseY) {
        if (tipLines.isEmpty()) {
            tipRect = null;
            return;
        }
        if (tipKey != tipKeyShown) {
            tipKeyShown = tipKey;
            tipScroll = 0;
        }
        final int lineH = 10;
        final int margin = 4;
        // 可用宽度：屏幕（不是面板）留出边距，再给框留 4px 描边
        int maxTextW = Math.max(40, this.width - 2 * margin - 14);
        // 正常提示不需要铺满整屏：超过 ~420px 就折行，读起来更舒服
        maxTextW = Math.min(maxTextW, 420);

        List<String> titleLines = wrapText(tipTitle.isEmpty() ? " " : tipTitle, maxTextW);
        List<String> body = new ArrayList<>();
        for (String l : tipLines) {
            body.addAll(wrapText(l, maxTextW));
        }

        // 高度也要夹进屏幕：行数上限由可用高度决定
        int maxRows = Math.max(1, Math.min(14, (this.height - 2 * margin - 4) / lineH - 2));
        int rows = Math.min(body.size(), maxRows);
        boolean scrollable = body.size() > rows;
        tipScroll = Math.max(0, Math.min(tipScroll, Math.max(0, body.size() - rows)));

        int w = 0;
        for (String l : titleLines) {
            w = Math.max(w, this.fontRenderer.getStringWidth(l));
        }
        for (String l : body) {
            w = Math.max(w, this.fontRenderer.getStringWidth(l));
        }
        String more = scrollable
                ? (tipScroll + rows) + " / " + body.size() + "  " + tr("logiscope.hint.scroll")
                : null;
        if (more != null) {
            w = Math.max(w, this.fontRenderer.getStringWidth(more));
        }
        w = Math.min(w, maxTextW) + 10;

        int h = (titleLines.size() + rows) * lineH + 4 + (scrollable ? lineH : 0);
        h = Math.min(h, this.height - 2 * margin);

        // 默认放在光标右下；放不下就翻到左上，仍然放不下就贴边 —— 保证**整框可见**
        int x = mouseX + 10;
        int y = mouseY + 10;
        if (x + w + margin > this.width) {
            x = mouseX - w - 10;
        }
        x = Math.max(margin, Math.min(x, this.width - w - margin));
        if (y + h + margin > this.height) {
            y = mouseY - h - 10;
        }
        y = Math.max(margin, Math.min(y, this.height - h - margin));

        g.fill(x - 2, y - 2, x + w + 2, y + h, 0xF2181822);
        ring(g, x - 2, y - 2, w + 4, h + 2, 0xFF3A3A4A);
        int ty = y;
        for (String l : titleLines) {
            g.drawString(this.fontRenderer, l, x + 2, ty, COLOR_HILIGHT, false);
            ty += lineH;
        }
        int ly = ty + 1;
        for (int i = tipScroll; i < Math.min(body.size(), tipScroll + rows); i++) {
            g.drawString(this.fontRenderer, body.get(i), x + 2, ly, 0xFFE0E0EC, false);
            ly += lineH;
        }
        if (more != null) {
            g.drawString(this.fontRenderer, more, x + 2, y + h - lineH, 0xFF9099A8, false);
        }
        tipBuf[0] = x - 2;
        tipBuf[1] = y - 2;
        tipBuf[2] = w + 4;
        tipBuf[3] = h + 2;
        tipRect = tipBuf;
    }

    // ==================================================================
    // NBT 取数探针
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
        dataForceNumeric = false;
        dataFocus = 0;
        dataEntries = java.util.Collections.emptyList();
        dataParsedFrom = "";
        dataTextLines = java.util.Collections.emptyList();
        dataTextCachedFrom = "";
        dataTextScroll = 0;
        dataScroll = 0;
        dataTgtScroll = 0;
        dataNextRequestTick = 0;
        dataSliderDrag = false;
        page = Page.DATA;
        DataCommand.request(p.target().dimension(), p.target().pos());
        // 进页面立刻抓一次（"不刷新"档也抓 —— 不刷新只表示**不自动重发**）。
        // 之后的自动刷新间隔 = 全局配置档位
        int rate = LogiScopeConfig.dataRefreshTicks();
        dataNextRequestTick = rate > 0
                ? LogiScopeMod.probes().currentTick() + rate
                : Long.MAX_VALUE;
    }

    /** 每客户端 tick：抓数（实时刷新）+ 解析 */
    private void tickDataPage() {
        DataCommand.tick();
        ProbeInstance src = dataSrcProbe();
        if (src == null) {
            return;
        }
        long now = LogiScopeMod.probes().currentTick();
        int rate = LogiScopeConfig.dataRefreshTicks();
        if (rate > 0) {
            // 速率被改小（或从"不刷新"切回来）→ 把过期的排期拉回来，不等旧节拍走完。
            // 速率改大则不动（当前这一拍按旧节拍发出即可，下一拍自然变慢）。
            dataNextRequestTick = Math.min(dataNextRequestTick, now + rate);
            if (now >= dataNextRequestTick) {
                DataCommand.request(src.target().dimension(), src.target().pos());
                dataNextRequestTick = now + rate;
            }
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
     * ①修：重解析**不动滚动位置** * NBT 里的数值（能量 / 流体量）几乎每秒都在变 → 原文每秒都"变了"；
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
        dataEntries = root == null ? java.util.Collections.emptyList() : Snbt.flatten(root);
    }

    /** 命令原文按当前宽度折行（缓存：宽度或原文变了才重算） */
    private void ensureDataTextWrapped(int width) {
        String text = DataCommand.text();
        if (text == null) {
            dataTextLines = java.util.Collections.emptyList();
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
            while (len > 1 && this.fontRenderer.getStringWidth(chunk) > width) {
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

    // ------------------------------------------------------------------
    // DATA 页的刷新速率滑条
    // ------------------------------------------------------------------

    /**
     * 滑条几何 [x, y, w, h]（渲染与命中共用同一个矩形）。
     * x 贴在状态文字右侧（"status ok" 后面）；右侧给档位数值标签（"20t" / "不刷新"）留 36px。
     */
    private int[] dataSliderRect(Layout L) {
        int left = L.x() + PAD;
        int right = L.x() + L.w() - PAD;
        int y = L.infoTop() + ROW_H - 2;
        String line = tr("logiscope.data.src") + "  " + tr(DataCommand.statusKey());
        int sx = left + this.fontRenderer.getStringWidth(line) + 10;
        int sw = Math.min(220, right - sx - 36);
        return new int[]{sx, y, Math.max(0, sw), ROW_H + 2};
    }

    /** 档位 ↔ 索引：idx 0..39 = 1..40t；idx 40（最右）= 不刷新（0） */
    private static int rateToIdx(int value) {
        return value <= 0 ? RATE_STOPS : value - 1;
    }

    private static int idxToRate(int idx) {
        return idx >= RATE_STOPS ? 0 : idx + 1;
    }

    /** 画刷新滑条：轨道 + 已选段 + 拖钮 + 右侧档位文字；悬停/拖动时提亮 */
    private void renderDataRateSlider(Draw g, Layout L, int mouseX, int mouseY) {
        int[] r = dataSliderRect(L);
        int sx = r[0];
        int sw = r[2];
        if (sw < 24) {
            return;   // 面板太窄，放不下就不画（命中判定同样会跳过）
        }
        int sy = r[1];
        int cy = sy + (ROW_H + 2) / 2;               // 与状态文字垂直居中
        int value = LogiScopeConfig.dataRefreshTicks();
        boolean off = value <= 0;
        int idx = rateToIdx(value);
        int kx = sx + sw * idx / RATE_STOPS;
        boolean over = isIn(mouseX, mouseY, sx, sy, sw, r[3]);
        boolean active = dataSliderDrag || over;

        g.fill(sx, cy - 1, sx + sw, cy + 1, active ? 0xFF4A4A5A : 0xFF3A3A46);          // 轨道
        g.fill(sx, cy - 1, kx, cy + 1, off ? 0xFF8A6030 : 0xFF5DCAA5);                 // 已选段
        g.fill(kx - 2, cy - 4, kx + 3, cy + 4,
                dataSliderDrag ? 0xFFFFD24A : active ? 0xFFE0E0EC : 0xFFB8B8C8);       // 拖钮
        String label = off ? tr("logiscope.data.rate_off") : value + "t";
        g.drawString(this.fontRenderer, label, sx + sw + 5, sy + 2,
                off ? 0xFFEF9F27 : 0xFF9099A8, false);
    }

    /** 把鼠标位置换算成档位并写回（写盘由 config 的 setter 负责；值没变则什么都不做） */
    private void applyDataSlider(int mx, Layout L) {
        int[] r = dataSliderRect(L);
        if (r[2] <= 0) {
            return;
        }
        int idx = Math.round((mx - r[0]) * (float) RATE_STOPS / r[2]);
        idx = Math.max(0, Math.min(RATE_STOPS, idx));
        LogiScopeConfig.setDataRefreshTicks(idxToRate(idx));
        // 排期不用这里管：tickDataPage 每 tick 会把过期排期拉回来（见那里的注释）
    }

    private void renderDataPage(Draw g, Layout L, int mouseX, int mouseY) {
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
                    int x1 = left + 3 + this.fontRenderer.getStringWidth(lineText.substring(0, from));
                    int x2 = left + 3 + this.fontRenderer.getStringWidth(lineText.substring(0, to));
                    int yTop = R.textTop() + 3 + (li - dataTextScroll) * lineH;
                    g.fill(x1, yTop, Math.max(x2, x1 + 2), yTop + 9, COLOR_HILIGHT);
                }
            }
            int ty = R.textTop() + 3;
            for (int i = dataTextScroll;
                 i < Math.min(dataTextLines.size(), dataTextScroll + maxLines); i++) {
                g.drawString(this.fontRenderer, dataTextLines.get(i), left + 3, ty, 0xFF9BE1CB, false);
                ty += lineH;
            }
            if (dataTextLines.size() > maxLines) {
                String more = (dataTextScroll + maxLines) + " / " + dataTextLines.size();
                g.drawString(this.fontRenderer, more, left + width - 4 - this.fontRenderer.getStringWidth(more),
                        R.textTop() + R.textH() - 10, 0xFF707070, false);
            }
        } else {
            String msg = tr(DataCommand.statusKey());
            g.drawString(this.fontRenderer, msg, left + 4, R.textTop() + 3, DataCommand.statusColor(), false);
            if (st == DataCommand.Status.NO_PERMISSION) {
                g.drawString(this.fontRenderer, cut(tr("logiscope.data.hint.permission"), width - 8),
                        left + 4, R.textTop() + 14, 0xFF8A8AA0, false);
            }
        }

        // ---- 2) 值列表（解析出来的"路径 = 值"；左键取值 / 左键文本或右键当名称）----
        int listTop = R.listTop();
        if (dataEntries.isEmpty()) {
            g.drawString(this.fontRenderer, tr("logiscope.data.no_values"), left, listTop, 0x8A8AA0, false);
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
                int vw = this.fontRenderer.getStringWidth(value);
                int avail = L.w() - 8 - vw - 8;
                g.drawString(this.fontRenderer, cut(e.path() + (named ? "  " + tr("logiscope.data.name_mark") : ""),
                        Math.max(20, avail)),
                        L.x() + 8, ry, pathColor, false);
                g.drawString(this.fontRenderer, value, L.x() + L.w() - 6 - vw, ry, 0xFF9099A8, false);
            }
            int footerY = listTop + Math.min(dataEntries.size(), R.listRows()) * ROW_H;
            String footer = (dataScroll + R.listRows()) + " / " + dataEntries.size()
                    + "  ·  " + tr("logiscope.data.hint.pick");
            g.drawString(this.fontRenderer, cut(footer, L.w() - 2 * PAD), L.x() + PAD, footerY, 0xFF707070, false);
        }

        // ---- 3) 追踪目标列表（确认添加后出现在这里；只能删，不能改；可滚动）----
        ProbeInstance src = dataSrcProbe();
        List<DataTarget> dts = src == null ? java.util.Collections.emptyList() : src.dataTargets();
        dataTgtScroll = Math.max(0, Math.min(dataTgtScroll,
                Math.max(0, dts.size() - R.tgtRows())));
        g.drawString(this.fontRenderer, cut(tr("logiscope.data.targets") + " · " + dts.size()
                + "  ·  " + tr("logiscope.data.targets_hint"), L.w() - 2 * PAD),
                left, R.tgtTop(), COLOR_ANALYZE, false);
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
            g.drawString(this.fontRenderer, cut(text, Math.max(20, textW)), L.x() + 8, ry, 0xFFE0E0EC, false);
            g.fill(L.x() + L.w() - 4 - DEL_W, ry - 1, L.x() + L.w() - 4, ry + ROW_H - 1,
                    overDel ? 0xFF7A3038 : 0xFF2A2A38);
            g.drawString(this.fontRenderer, "×", L.x() + L.w() - 4 - DEL_W + 4, ry,
                    overDel ? 0xFFFFD0D0 : 0xFFC0A0A8, false);
        }
        if (dts.size() > R.tgtRows()) {
            String more = (dataTgtScroll + R.tgtRows()) + " / " + dts.size()
                    + "  " + tr("logiscope.hint.scroll");
            g.drawString(this.fontRenderer, cut(more, L.w() - 2 * PAD),
                    left, R.tgtTop() + (R.tgtRows() + 1) * ROW_H, 0xFF9099A8, false);
        }

        // ---- 4) 虚拟容器：一行两格（值 / 名称）----
        int cellY = R.editTop();
        drawEditCell(g, left, cellY, R.cellW(), tr("logiscope.data.field.value"),
                dataPath, dataFocus == 1, dataForceNumeric ? COLOR_FORCED : 0);
        drawEditCell(g, left + R.cellW() + PAD, cellY, R.cellW(), tr("logiscope.data.field.name"),
                dataName, dataFocus == 2, dataNamePath != null ? COLOR_ANALYZE : 0);
    }

    /**
     * 一个可编辑的小格：上面一行小标签，下面一格内容（焦点时描边 + 光标）。
     *
     * {@code marker} 非 0 时在右端点画一个小方格，颜色由调用方给（同一语汇，两种含义）：
     * 名称跟着 NBT 走 = 品红；值被标了「按数值读取」 = 黄。
     */
    private void drawEditCell(Draw g, int x, int y, int w, String label,
                              String value, boolean focused, int marker) {
        g.drawString(this.fontRenderer, label, x, y - 9, 0xFF8A8AA0, false);
        int border = focused ? 0xFF8A8AC8 : 0xFF4A4A5E;
        g.fill(x, y, x + w, y + CHIP_H, focused ? 0xFF2A2A38 : 0xFF1C1C26);
        ring(g, x, y, w, CHIP_H, border);
        String shown = value == null ? "" : value;
        int avail = w - 12;
        String draw = this.fontRenderer.getStringWidth(shown) > avail ? cut(shown, avail) : shown;
        int color = shown.isEmpty() ? 0xFF6A6A78 : 0xFFE0E0EC;
        g.drawString(this.fontRenderer, shown.isEmpty() ? tr("logiscope.data.field.empty") : draw,
                x + 5, y + 3, color, false);
        if (marker != 0 && !shown.isEmpty()) {
            // 右端点的小方格：名称跟 NBT = 品红；值按数值读取 = 黄（色由调用方给）
            g.fill(x + w - 5, y + 3, x + w - 2, y + 6, marker);
        }
        if (focused && (LogiScopeMod.probes().currentTick() / 10) % 2 == 0) {
            int cx = x + 5 + Math.min(this.fontRenderer.getStringWidth(shown), avail);
            g.fill(cx, y + 3, cx + 1, y + CHIP_H - 3, 0xFFE0E0EC);
        }
    }

    /**
     * DATA 页的鼠标点击（**只接左键**，见 {@link #mouseClicked} 的 button != 0 早退）：
     * 值列表（数值 = 取值路径 / 文字 = 当名称）+ 两个编辑格 + 刷新滑条。
     * 右键（按数值读取填入值格）走 {@link #handleDataValueRightClick}。
     */
    private boolean handleDataClick(int mx, int my, Layout L, int button) {
        // 刷新滑条：左键按下即开始拖动（拖动继续在 mouseDragged 里跟）
        int[] sl = dataSliderRect(L);
        if (button == 0 && sl[2] >= 24 && isIn(mx, my, sl[0], sl[1], sl[2], sl[3])) {
            dataFocus = 0;   // 与"点信息/原文区收起编辑焦点"同一惯例
            dataSliderDrag = true;
            applyDataSlider(mx, L);
            return true;
        }
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
        // 修的 bug：原先写的是 `my >= R.listTop()` —— 把列表以下
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
                        dataForceNumeric = false;   // 左键取值 = 自动口径
                    } else {
                        // 数值叶子才可采样：点到字符串值就当作"指定名称"
                        dataName = e.node().text() == null ? e.path() : e.node().text();
                        dataNamePath = e.path();
                    }
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
                List<DataTarget> dts = src2 == null ? java.util.Collections.emptyList() : src2.dataTargets();
                int idx = dataTgtScroll + row;
                if (row >= 0 && row < R.tgtRows() && idx < dts.size()
                        && isIn(mx, my, L.x() + L.w() - 4 - DEL_W,
                                R.tgtTop() + (row + 1) * ROW_H - 1, DEL_W, ROW_H)) {
                    // 按目标自身删（不是按 valuePath）：AE 目标共用 "ae:network" 这个标记路径
                    src2.removeDataTarget(dts.get(idx));
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
     * DATA 页值列表的**右键**：把该路径**按「按数值读取」口径填进值格**（不直接加入追踪）。
     *
     * 不直接加的原因：填进值格后用户可以接着设名称（左键字符串值 / 手输），
     * 再点「确认添加」—— 一步式把"追哪个数"和"叫什么名"绑死了，两步更可控。
     * 值格右端点的**黄色小方格**标记这个口径（与名称格的品红点同一语汇）；
     * 手改路径（手输 / 粘贴 / 退格）或左键取值都会清掉它（回到自动口径）。
     * 数值条目也允许（读数与自动口径一致，只是显式声明按数值读）。
     *
     * 非单值条目（列表 / 复合）没有可采样的数：明确提示，不填。
     *
     * @return 是否处理了这次右键（false = 没点在值条目行上，事件继续往上走）
     */
    private boolean handleDataValueRightClick(int mx, int my, Layout L) {
        DataRects R = dataRects(L);
        int listBottom = R.listTop() + R.listRows() * ROW_H;
        if (my < R.listTop() || my >= listBottom) {
            return false;
        }
        int row = (my - R.listTop()) / ROW_H;
        int idx = dataScroll + row;
        if (idx >= dataEntries.size()) {
            return false;   // 行带内的空白 → 不处理（右键在面板其他各处本来也无操作）
        }
        Snbt.Entry e = dataEntries.get(idx);
        if (!e.numeric() && !e.textual()) {
            // 列表 / 复合节点：不是单个可读值，按数值读也读不出东西 —— 明确说清楚
            say(tr("logiscope.flash.data_not_scalar").replace("%s", e.path()));
            return true;
        }
        dataPath = e.path();
        dataForceNumeric = true;
        say(tr("logiscope.flash.data_force").replace("%s", e.path()));
        return true;
    }

    /**
     * 「确认添加」：把当前选的 值/路径 + 名称 作为**一个追踪目标**加进探针。
     *
     * 这是与物品探针入口（选格 → 完成）并列的第二条创建路径：那条走"菜单槽 /
     * ItemHandler 能力"，这条走 "NBT 路径"。目标攒在**这个探针**的列表里（可以加
     * 好几个），回选格点「完成」后与容器格的物品监测**一起**开始。
     * 同路径再次确认、设置变了（读取口径 / 名称）= **替换**旧的那条（同一路径只留
     * 一条）；设置没变才提示重复。
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
        DataTarget target = new DataTarget(path, dataNamePath, dataName.trim(),
                t.deviceName(), t.dimension(), t.pos(), -1, null, dataForceNumeric);
        // 同一路径只留一条普通目标：设置变了（读取口径 / 名称）就**替换** ——
        // 「右键填入 + 确认」的常见意图就是把已有那条改成按数值读、或补个名称，
        // 不必先删再加。只扫普通路径目标：slot 锚点 / AE 目标即便路径字符串偶然相同也不参与。
        DataTarget old = null;
        for (DataTarget dt : src.dataTargets()) {
            if (!dt.isSlotAnchor() && !dt.isNetwork() && dt.valuePath().equals(path)) {
                old = dt;
                break;
            }
        }
        if (old != null && old.forceNumeric() == dataForceNumeric
                && Objects.equals(old.namePath(), dataNamePath)
                && old.literalName().equals(dataName.trim())) {
            say(tr("logiscope.flash.data_dup").replace("%s", path));
            return;
        }
        boolean ok = old != null ? src.replaceDataTarget(old, target) : src.addDataTarget(target);
        if (ok) {
            dataPath = "";
            dataName = "";
            dataNamePath = null;
            dataForceNumeric = false;
            String msg = tr(old != null ? "logiscope.flash.data_updated" : "logiscope.flash.data_added")
                    .replace("%s", path);
            // 值装不下就当场说清楚 // 样本存储是 int（±21 亿）。追一个 2^63 的值（创造能量立方这类）时，
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
        if (this.fontRenderer.getStringWidth(s) <= maxW) {
            return s;
        }
        String ell = "...";
        int budget = maxW - this.fontRenderer.getStringWidth(ell);
        int i = s.length();
        while (i > 0 && this.fontRenderer.getStringWidth(s.substring(0, i)) > budget) {
            i--;
        }
        return s.substring(0, Math.max(0, i)) + ell;
    }

    private static String tr(String key) {
        return new TextComponentTranslation(key).getUnformattedText();
    }

    /** 带参数的翻译（%s 占位）。注意：数字格式化交给调用方，这里只做文本替换 */
    private static String tr(String key, Object... args) {
        return new TextComponentTranslation(key, args).getUnformattedText();
    }
}
