package com.jessetech.logiscope.archive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.jessetech.logiscope.store.Sample;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 存档文件格式（G5）。只管"结构 ↔ 文本"的互相转换，不碰文件系统、不碰游戏类
 * （readHeader/toJson 的输入输出都是纯值；带 Path 的两个方法也只是薄封装，方便复用）。
 *
 * 【JSON 结构 —— 为扩展性留的余地】
 *
 * {@code
 * {
 *   "format": 1,                      ← 结构性变更 +1；读取方按自己认识的最大版本解释
 *   "kind": "logiscope-archive",      ← 文件指纹，读错文件直接拒绝
 *   "created": "2026-09-18 22:43:05",
 *   "world": "New World",
 *   "probe": { "name": "barrel", "description": "槽 0,1 · overworld 10,64,10" },
 *   "range": { "first": 1600, "last": 3271 },
 *   "markers": [2000, 2600],          ← G4 标注，随记录一起走
 *   "samples": [
 *     {"t": 1600, "i": "minecraft:iron_ingot", "n": 64},
 *     {"t": 1605, "m": "no_data"},    ← 标记样本单独一个键，绝不写成 0
 *     {"t": 1610, "m": "gap"}
 *   ]
 * }}
 *
 * ★ 为什么手拼 {@link JsonObject} 而不用 Gson 的反射绑定 ★
 *   反射绑定对"字段改名/增删"很脆弱，而且旧 Gson 对 record 支持不一。
 *   显式读写每个字段 + 逐字段给默认值：**旧文件永远能被新代码读**，
 *   新增字段也不需要迁移（读取方忽略不认识的键）—— 这就是"可扩展"的落地形式。
 *
 * 【CSV】给外部工具（Excel / Python / 视频工程）用的通用格式：
 *   数据行 {@code tick,item_id,count}；no_data / gap 是**标记行，count 留空** ——
 *   写成 0 等于编造"那一刻是空的"（GOALS 里明说的红线）。标记以注释行附在尾部。
 */
public final class ArchiveFormat {

    public static final int FORMAT = 1;
    public static final String KIND = "logiscope-archive";

    private ArchiveFormat() {
    }

    // ------------------------------------------------------------------
    // JSON 写
    // ------------------------------------------------------------------

    public static String toJson(ArchiveData d) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("kind", KIND);
        root.addProperty("name", d.name());
        root.addProperty("created", d.created());
        root.addProperty("world", d.world());

        JsonObject probe = new JsonObject();
        probe.addProperty("name", d.probeName());
        probe.addProperty("description", d.probeDesc());
        root.add("probe", probe);

        JsonObject range = new JsonObject();
        range.addProperty("first", d.firstTick());
        range.addProperty("last", d.lastTick());
        root.add("range", range);

        JsonArray markers = new JsonArray();
        for (long m : d.markers()) {
            markers.add(m);
        }
        root.add("markers", markers);

        JsonArray samples = new JsonArray();
        for (Sample s : d.samples()) {
            JsonObject o = new JsonObject();
            o.addProperty("t", s.tick());
            if (s.isNoData()) {
                o.addProperty("m", "no_data");
            } else if (s.isGap()) {
                o.addProperty("m", "gap");
            } else {
                o.addProperty("i", s.itemId());
                o.addProperty("n", s.count());
            }
            samples.add(o);
        }
        root.add("samples", samples);

        return root.toString();
    }

    // ------------------------------------------------------------------
    // JSON 读
    // ------------------------------------------------------------------

    /** 存档头部的小结（列表页展示用，不建 Sample 对象） */
    public record Header(String created, String world, String probeName, String probeDesc,
                         long firstTick, long lastTick, int markerCount, int sampleCount) {
    }

    public static @Nullable ArchiveData fromJson(String text) {
        JsonObject root = parseObject(text);
        if (root == null) {
            return null;
        }
        List<Sample> samples = new ArrayList<>();
        JsonArray arr = arrayOrNull(root, "samples");
        if (arr != null) {
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                long t = o.has("t") ? o.get("t").getAsLong() : 0L;
                if (o.has("m")) {
                    String m = o.get("m").getAsString();
                    samples.add("gap".equals(m) ? Sample.gap(t) : Sample.noData(t));
                } else {
                    String item = o.has("i") ? o.get("i").getAsString() : Sample.EMPTY_ITEM;
                    int n = o.has("n") ? o.get("n").getAsInt() : 0;
                    samples.add(new Sample(t, item, n));
                }
            }
        }
        List<Long> markers = new ArrayList<>();
        JsonArray ms = arrayOrNull(root, "markers");
        if (ms != null) {
            for (JsonElement e : ms) {
                markers.add(e.getAsLong());
            }
        }
        JsonObject probe = root.has("probe") && root.get("probe").isJsonObject()
                ? root.getAsJsonObject("probe") : new JsonObject();
        JsonObject range = root.has("range") && root.get("range").isJsonObject()
                ? root.getAsJsonObject("range") : new JsonObject();
        return new ArchiveData(
                str(root, "name", ""),
                str(root, "created", ""),
                str(root, "world", "unknown"),
                str(probe, "name", ""),
                str(probe, "description", ""),
                range.has("first") ? range.get("first").getAsLong() : 0L,
                range.has("last") ? range.get("last").getAsLong() : 0L,
                markers,
                samples);
    }

    /**
     * 读头部（列表页扫描用）：只要小结，**不把几万条样本建成对象**。
     *
     * ★ 第十六轮⑤ 改成流式读 ★
     * 旧实现是 `JsonParser.parseString(Files.readString(...))` —— 先把整份文件读成字符串、
     * 再把**每一条样本都建成 JsonObject**，只为取几行头部。存档大时（几万样本）
     * 每次打开面板都要付这份代价，而注释一直写着"只读 JSON 头，几 KB 级"（名实不符）。
     * 现在用 {@link JsonReader} 顺着 token 走：认识的键取值，`samples` 整段
     * `skipValue()` 数个数就跳过 —— 不建对象、不把整份文本留在内存里。
     *
     * 容错口径与旧实现一致：非对象 / 语法坏 → `IOException`（调用方跳过这个存档）；
     * 某个键的类型不对 → 当它不存在（用默认值），不因此丢掉整个存档。
     */
    public static Header readHeader(Path file) throws IOException {
        String created = "";
        String world = "unknown";
        String probeName = "";
        String probeDesc = "";
        long first = 0L;
        long last = 0L;
        int markerCount = 0;
        int sampleCount = 0;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             JsonReader r = new JsonReader(in)) {
            r.setLenient(true);
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "created" -> created = stringOr(r, "");
                    case "world" -> world = stringOr(r, "unknown");
                    case "probe" -> {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject();
                            while (r.hasNext()) {
                                String k = r.nextName();
                                if ("name".equals(k)) {
                                    probeName = stringOr(r, "");
                                } else if ("description".equals(k)) {
                                    probeDesc = stringOr(r, "");
                                } else {
                                    r.skipValue();
                                }
                            }
                            r.endObject();
                        } else {
                            r.skipValue();
                        }
                    }
                    case "range" -> {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject();
                            while (r.hasNext()) {
                                String k = r.nextName();
                                if ("first".equals(k)) {
                                    first = longOr(r, 0L);
                                } else if ("last".equals(k)) {
                                    last = longOr(r, 0L);
                                } else {
                                    r.skipValue();
                                }
                            }
                            r.endObject();
                        } else {
                            r.skipValue();
                        }
                    }
                    case "markers" -> markerCount = countArray(r);
                    case "samples" -> sampleCount = countArray(r);
                    default -> r.skipValue();      // 不认识的键（含将来的新字段）直接跳过
                }
            }
            r.endObject();
        } catch (RuntimeException e) {
            throw new IOException("not a LogiScope archive: " + file, e);
        }
        return new Header(created, world, probeName, probeDesc, first, last, markerCount, sampleCount);
    }

    /** 数一个数组有几个元素 —— 逐个 token 跳过去，不建任何对象 */
    private static int countArray(JsonReader r) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue();
            return 0;
        }
        int n = 0;
        r.beginArray();
        while (r.hasNext()) {
            r.skipValue();
            n++;
        }
        r.endArray();
        return n;
    }

    private static String stringOr(JsonReader r, String def) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING || t == JsonToken.NUMBER) {
            return r.nextString();
        }
        r.skipValue();
        return def;
    }

    private static long longOr(JsonReader r, long def) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NUMBER) {
            return r.nextLong();
        }
        if (t == JsonToken.STRING) {
            try {
                return Long.parseLong(r.nextString());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        r.skipValue();
        return def;
    }

    // ------------------------------------------------------------------
    // CSV 写
    // ------------------------------------------------------------------

    public static List<String> csvLines(ArchiveData d) {
        List<String> out = new ArrayList<>();
        out.add("# LogiScope archive " + d.name());
        out.add("# created " + d.created());
        out.add("# probe " + d.probeName() + " - " + d.probeDesc());
        out.add("# tick,item_id,count");
        out.add("# no_data / gap rows are markers: count left EMPTY (never write 0)");
        for (Sample s : d.samples()) {
            if (s.isMarker()) {
                out.add(s.tick() + "," + s.itemId() + ",");
            } else {
                out.add(s.tick() + "," + s.itemId() + "," + s.count());
            }
        }
        for (long m : d.markers()) {
            out.add("# marker " + m);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 小工具（逐字段取值 + 默认值：旧文件永远能被新代码读）
    // ------------------------------------------------------------------

    private static @Nullable JsonObject parseObject(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static @Nullable JsonArray arrayOrNull(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }
}
