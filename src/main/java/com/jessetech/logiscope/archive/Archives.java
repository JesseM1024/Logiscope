package com.jessetech.logiscope.archive;

import com.jessetech.logiscope.LogiScopeMod;
import com.jessetech.logiscope.collect.ProbeInstance;
import com.jessetech.logiscope.store.Sample;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 存档的**文件系统与游戏侧**操作（G5）。格式细节在 {@link ArchiveFormat}。
 *
 * 【目录布局】{@code .minecraft/logiscope/<存档名>/}，存档名 = 导出时刻的时间戳。
 *   recording.json 是回放的真相；recording.csv 是给外部工具的通用格式。
 *   目录用 `Files.list` 扫描：一个存档一个目录，用户也能直接在文件管理器里
 *   拷贝/备份整个存档（"允许跨存档读取"最朴素的形式）。
 *
 * 【"存档并停止"的事务性】
 *   导出失败（磁盘满 / 路径异常）时**必须让探针活着** —— 先写文件、写成功才删探针。
 *   反过来删了探针又没导出成功，几万 tick 的记录就真没了，这不可接受。
 */
public final class Archives {

    public static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    public static final DateTimeFormatter CREATED_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Archives() {
    }

    /** 存档根目录：{@code .minecraft/logiscope/}（runClient 下 = run/logiscope/） */
    public static Path rootDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("logiscope");
    }

    /**
     * 某一个存档自己的目录（{@code recording.json} / {@code recording.csv} 就在这一层）。
     *
     * 为什么要有它：根目录下是几十个 `yyyy-MM-dd_HH-mm-ss` 的文件夹，从那里找某一次记录
     * 得一个个点进去看。回放页的「打开存档文件夹」直接落到这一层。
     */
    public static Path dirOf(String dirName) {
        return rootDir().resolve(dirName);
    }

    /**
     * 删除一个存档目录（连同里面的 {@code recording.json} / {@code .csv} / {@code name.txt}）。
     *
     * 【为什么要有前缀校验】{@code dirName} 来自扫描结果，本来只会是根目录下的一层目录名；
     * 但它是字符串，将来万一有人从输入框拼进来（`../..`），一句 {@code deleteIfExists}
     * 就能把别的东西删掉。所以先 resolve + normalize，再要求"父目录正好是根目录"。
     *
     * 【为什么用 walk 逆序】目录非空时 {@code Files.delete} 会失败；{@code walk} 是前序遍历，
     * 反过来就保证"先删文件、再删空目录"。
     *
     * @return 是否真的删掉了（目录本来就不存在也算成功 —— 用户手动删过）
     */
    public static boolean delete(String dirName) {
        if (dirName == null || dirName.trim().isEmpty()) {
            return false;
        }
        try {
            Path root = rootDir().toAbsolutePath().normalize();
            Path dir = root.resolve(dirName).toAbsolutePath().normalize();
            if (!root.equals(dir.getParent())) {
                return false;   // 不在根目录的下一层 → 拒绝（防 ../ 逃逸）
            }
            if (!Files.exists(dir)) {
                return true;
            }
            List<Path> all = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.forEach(all::add);
            }
            all.sort(Comparator.reverseOrder());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
            return !Files.exists(dir);
        } catch (Exception e) {
            LogiScopeMod.LOGGER.warn("[LogiScope] delete archive failed: {}", dirName, e);
            return false;
        }
    }


    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /** 把一个探针实例的记录导出成新存档（写文件；不删探针 —— 那是调用方的责任） */
    public static ArchiveData export(ProbeInstance p) throws IOException {
        List<Sample> samples = p.store().toList();
        if (samples.isEmpty()) {
            throw new IOException("nothing recorded");
        }
        LocalDateTime now = LocalDateTime.now();
        ArchiveData data = new ArchiveData(
                NAME_FMT.format(now),
                CREATED_FMT.format(now),
                worldName(),
                p.name(),
                p.describe(),
                samples.get(0).tick(),
                samples.get(samples.size() - 1).tick(),
                p.markers().toList(),
                List.copyOf(samples));
        writeFiles(rootDir().resolve(data.name()), data);
        return data;
    }

    /** 写一份存档（JSON + CSV）。目录不存在就创建 */
    public static void writeFiles(Path dir, ArchiveData data) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("recording.json"), ArchiveFormat.toJson(data), StandardCharsets.UTF_8);
        StringBuilder csv = new StringBuilder();
        for (String line : ArchiveFormat.csvLines(data)) {
            csv.append(line).append('\n');
        }
        Files.writeString(dir.resolve("recording.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    /** 世界名：单人 = 存档名（level name）；多人 = "multiplayer"；拿不到 = "unknown" */
    private static String worldName() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            return server.getWorldData().getLevelName();
        }
        return mc.getCurrentServer() != null ? "multiplayer" : "unknown";
    }

    // ------------------------------------------------------------------
    // 扫描 / 读取
    // ------------------------------------------------------------------

    /** 存档列表里的一行小结（不载入样本） */
    public record Info(String dirName, String displayName, String created, String world,
                       String probeName, String probeDesc, long firstTick, long lastTick,
                       int markerCount, int sampleCount, Path dir) {
    }

    /** 自定义名称的文件名：有它就用它当"名称"，没有就退回探针名 / 目录名 */
    public static final String NAME_FILE = "name.txt";

    /** 一个存档目录当前显示用的名称 */
    public static String displayName(Path dir, String probeName) {
        Path f = dir.resolve(NAME_FILE);
        try {
            if (Files.isRegularFile(f)) {
                String n = Files.readString(f, StandardCharsets.UTF_8).trim();
                if (!n.isEmpty()) {
                    return n;
                }
            }
        } catch (IOException ignored) {
            // 读不到就当没有自定义名
        }
        return probeName;
    }

    /**
     * 重命名一份存档：只写 {@link #NAME_FILE}，**不动 recording.json**。
     * 这样旧存档天然兼容、改名也不需要重写几万个样本。
     * 传空串 = 清掉自定义名（回到探针名）。名称过长截到 48 字符。
     */
    public static boolean rename(Info info, String name) throws IOException {
        Path f = info.dir().resolve(NAME_FILE);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > 48) {
            trimmed = trimmed.substring(0, 48);
        }
        if (trimmed.isEmpty()) {
            Files.deleteIfExists(f);
            return true;
        }
        Files.writeString(f, trimmed, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * 扫描根目录下的所有存档，按创建时间倒序（最新的在最上面 ——
     * "默认读取当前/最近存档"就靠这个排序表达）。
     */
    public static List<Info> scan() {
        Path root = rootDir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Info> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path json = dir.resolve("recording.json");
                if (!Files.isRegularFile(json)) {
                    return;
                }
                try {
                    ArchiveFormat.Header h = ArchiveFormat.readHeader(json);
                    String dirName = dir.getFileName().toString();
                    out.add(new Info(dirName, displayName(dir, h.probeName()), h.created(),
                            h.world(), h.probeName(), h.probeDesc(), h.firstTick(),
                            h.lastTick(), h.markerCount(), h.sampleCount(), dir));
                } catch (Exception e) {
                    // 坏存档不挡别的存档：记日志、跳过
                    LogiScopeMod.LOGGER.warn("[LogiScope] skipping broken archive {}: {}",
                            dir.getFileName(), e.toString());
                }
            });
        } catch (IOException e) {
            LogiScopeMod.LOGGER.warn("[LogiScope] archive scan failed: {}", e.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(Info::created).reversed());
        return out;
    }

    /** 载入一份存档（读 JSON → 全量数据） */
    public static ArchiveData load(Info info) throws IOException {
        String text = Files.readString(info.dir().resolve("recording.json"), StandardCharsets.UTF_8);
        ArchiveData data = ArchiveFormat.fromJson(text);
        if (data == null) {
            throw new IOException("malformed archive: " + info.dirName());
        }
        // 存档名以目录为准（JSON 里的 name 只是冗余记录）
        return new ArchiveData(info.dirName(), data.created(), data.world(),
                data.probeName(), data.probeDesc(), data.firstTick(), data.lastTick(),
                data.markers(), data.samples());
    }

    /** 当前（最近一次导出/载入）的存档目录名，供根页"默认读取当前存档"用 */
    @Nullable
    public static String lastName() {
        return lastName;
    }

    public static void remember(String name) {
        lastName = name;
    }

    @Nullable
    private static String lastName;
}
