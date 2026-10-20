package com.jessetech.logiscope.archive;

import com.jessetech.logiscope.store.Sample;

import java.util.List;

/**
 * 一份存档的**全部内容**（G5）。纯数据，不引用任何游戏类。
 *
 * 【存档 = 一个目录】
 *   {@code .minecraft/logiscope/<存档名>/} 下两个文件：
 *     recording.json  —— 全量数据（回放用，含标注）
 *     recording.csv   —— 对外通用格式（外部工具用，不含 JSON 的嵌套）
 *   存档名 = 导出时刻的时间戳（{@code yyyy-MM-dd_HH-mm-ss}），由导出层生成。
 *
 * 【为什么 samples 直接复用 {@link Sample}】
 *   存储层的扁平时间序列本来就是"可序列化的最小信息"（见 Sample 的类注释），
 *   导出 = 把同一份真相写成文件，回放 = 把文件读回同一种结构再交给
 *   {@code ChartData#build} —— 分析层与画图层对"数据是活的还是回放的"完全无感。
 *
 * @param name      存档名（= 目录名，时间戳）
 * @param created   导出时间（人类可读）
 * @param world     世界名（单人 = 存档名；多人 = 服务器名；拿不到 = unknown）
 * @param probeName 探针名（设备名）
 * @param probeDesc 探针描述（槽位 + 坐标）
 * @param firstTick 首个样本的 tick
 * @param lastTick  末个样本的 tick
 * @param markers   波形标记（G4），随存档一起走
 * @param samples   扁平样本（含 no_data / gap 标记样本）
 */
public final class ArchiveData {
    private final String name;
    private final String created;
    private final String world;
    private final String probeName;
    private final String probeDesc;
    private final long firstTick;
    private final long lastTick;
    private final List<Long> markers;
    private final List<Sample> samples;

    public ArchiveData(String name, String created, String world, String probeName, String probeDesc, long firstTick, long lastTick, List<Long> markers, List<Sample> samples) {
        this.name = name;
        this.created = created;
        this.world = world;
        this.probeName = probeName;
        this.probeDesc = probeDesc;
        this.firstTick = firstTick;
        this.lastTick = lastTick;
        this.markers = markers;
        this.samples = samples;
    }

    public String name() {
        return name;
    }

    public String created() {
        return created;
    }

    public String world() {
        return world;
    }

    public String probeName() {
        return probeName;
    }

    public String probeDesc() {
        return probeDesc;
    }

    public long firstTick() {
        return firstTick;
    }

    public long lastTick() {
        return lastTick;
    }

    public List<Long> markers() {
        return markers;
    }

    public List<Sample> samples() {
        return samples;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ArchiveData other = (ArchiveData) o;
        return java.util.Objects.equals(this.name, other.name) && java.util.Objects.equals(this.created, other.created) && java.util.Objects.equals(this.world, other.world) && java.util.Objects.equals(this.probeName, other.probeName) && java.util.Objects.equals(this.probeDesc, other.probeDesc) && java.util.Objects.equals(this.firstTick, other.firstTick) && java.util.Objects.equals(this.lastTick, other.lastTick) && java.util.Objects.equals(this.markers, other.markers) && java.util.Objects.equals(this.samples, other.samples);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, created, world, probeName, probeDesc, firstTick, lastTick, markers, samples);
    }

    @Override
    public String toString() {
        return "ArchiveData[" + "name=" + name + ", " + "created=" + created + ", " + "world=" + world + ", " + "probeName=" + probeName + ", " + "probeDesc=" + probeDesc + ", " + "firstTick=" + firstTick + ", " + "lastTick=" + lastTick + ", " + "markers=" + markers + ", " + "samples=" + samples + "]";
    }

}
