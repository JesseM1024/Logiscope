package com.jessetech.logiscope.store;

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
 */
public final class SampleStore {

    private final Sample[] buf;
    private int size;
    private int head;

    public SampleStore(int capacity) {
        this.buf = new Sample[Math.max(1, capacity)];
    }

    public synchronized void add(Sample sample) {
        buf[head] = sample;
        head = (head + 1) % buf.length;
        if (size < buf.length) {
            size++;
        }
    }

    public synchronized int size() {
        return size;
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

    /** 按时间先后顺序导出副本（最旧 -> 最新） */
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
    }
}
