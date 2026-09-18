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
public record ArchiveData(
        String name,
        String created,
        String world,
        String probeName,
        String probeDesc,
        long firstTick,
        long lastTick,
        List<Long> markers,
        List<Sample> samples) {
}
