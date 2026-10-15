package com.jessetech.logiscope.store;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 环形缓冲。
 *
 * 为什么要环形而不是 ArrayList：
 *   采样频率是每秒 20 次。挂机一小时就是 72000 个样本，
 *   如果用 ArrayList 一直涨，长时间记录迟早把客户端内存吃掉。
 *   环形缓冲固定容量、写满后覆盖最旧的样本 —— 内存占用可预测。
 *
 * 为什么要 synchronized：
 *   采样在客户端 tick 线程，绘制在渲染线程。虽然 1.20+ 两者基本同一个线程，
 *   但加上锁的成本可忽略，能避免以后引入异步分析时踩坑。
 *
 * 为什么要有 version *   界面每帧都要"把时间序列整理成按物品分组的折线"（见 analysis/ChartData）。
 *   这个整理是 O(n) 的：不缓存的话，60 fps 下每秒白烧 60 次全量遍历。
 *   有了 version，界面就能"只在数据真的变了之后重算一次"——
 *   采样是 20 tick/秒，所以从 60 次/秒 降到 20 次/秒，且改动只集中在这一处
 *   （不会出现"某条路径忘了通知界面刷新"的漏网情况）。
 */
public final class SampleStore {

    private Sample[] buf;
    private int size;
    private int head;

    /** 数据版本号：只要内容变了就 +1。界面靠它判断"要不要重算派生数据"。 */
    private long version;

    public SampleStore(int capacity) {
        this.buf = new Sample[Math.max(1, capacity)];
    }

    public synchronized void add(Sample sample) {
        buf[head] = sample;
        head = (head + 1) % buf.length;
        if (size < buf.length) {
            size++;
        }
        version++;
    }

    /**
     * 换一个容量（配置改了之后**热生效**）。
     *
     * 保留**最新**的样本：新容量更小时丢掉最旧的，更大时把现有的搬过去。
     * 版本号会 +1，于是界面会自动重算派生数据（不用重建探针、也不用重启）。
     *
     * @param newCapacity 新的容量（至少 1）
     */
    public synchronized void resize(int newCapacity) {
        int cap = Math.max(1, newCapacity);
        if (cap == buf.length) {
            return;
        }
        int keep = Math.min(size, cap);
        Sample[] fresh = new Sample[cap];
        for (int i = 0; i < keep; i++) {
            fresh[i] = buf[Math.floorMod(head - keep + i, buf.length)];
        }
        buf = fresh;
        size = keep;
        head = keep % cap;
        version++;
    }

    public synchronized int size() {
        return size;
    }

    /** 数据版本号，见类注释。任何 add / clear 都会让它变化。 */
    public synchronized long version() {
        return version;
    }

    public int capacity() {
        return buf.length;
    }

    public synchronized Sample latest() {
        if (size == 0) {
            return null;
        }
        return buf[(head - 1 + buf.length) % buf.length];
    }

    /**
     * 时间轴最后一格里写下的**数据**样本（面板上那行 `now` 用它）。
     *
     * 为什么不是"最后一条样本"：一个世界 tick 可能写好几条（多格监控时每个物品一条，
     * 见 {@code analysis/Aggregator}），而最后一条也可能是标记（`no_data` / `gap`）——
     * 那种情况返回 null（这一刻没有数据），不要拿上一格的值冒充。
     *
     * 往回扫到 tick 变化为止：一个 tick 的条目数 = 被监控的物品种数，很小。
     */
    @Nullable
    public synchronized List<Sample> latestTick() {
        if (size == 0) {
            return null;
        }
        Sample last = buf[Math.floorMod(head - 1, buf.length)];
        if (last == null || last.isMarker()) {
            return null;
        }
        long tick = last.tick();
        List<Sample> out = new ArrayList<>(4);
        for (int i = 0; i < size; i++) {
            Sample s = buf[Math.floorMod(head - 1 - i, buf.length)];
            if (s == null || s.isMarker() || s.tick() != tick) {
                break;
            }
            out.add(s);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * 按时间先后顺序导出副本（最旧 -> 最新）。
     *
     * 注意：这会真的复制一份（最多 36000 个引用，约 290 KB）。
     * 界面只在 version 变化后才调用它，即最多 20 次/秒 —— 对年轻代 GC 完全无压力。
     * 想再省一步的话，可以改成提供一个"按序遍历"的回调，但那样会把
     * ChartData 的纯函数形态破坏掉，不划算。
     */
    public synchronized List<Sample> toList() {
        List<Sample> out = new ArrayList<>(size);
        int start = (head - size + buf.length) % buf.length;
        for (int i = 0; i < size; i++) {
            out.add(buf[(start + i) % buf.length]);
        }
        return out;
    }

    public synchronized void clear() {
        Arrays.fill(buf, null);
        size = 0;
        head = 0;
        version++;
    }
}
