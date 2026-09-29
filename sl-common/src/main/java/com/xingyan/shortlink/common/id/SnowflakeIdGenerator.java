package com.xingyan.shortlink.common.id;

import java.security.SecureRandom;

/**
 * 单机版雪花 ID（41bit 毫秒时间戳 + 10bit 节点 + 12bit 序列）。
 * 节点号默认启动时随机（个人项目单机多实例，碰撞概率可忽略；M2 注册中心可显式分配）。
 * 时钟回退：等待追平，超过 5s 抛异常拒绝发号。
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH_MILLIS = 1704038400000L; // 2024-01-01T00:00:00Z
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_CLOCK_BACKWARD_MILLIS = 5000L;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        this(new SecureRandom().nextInt(1 << NODE_BITS));
    }

    public SnowflakeIdGenerator(int nodeId) {
        if (nodeId < 0 || nodeId >= (1 << NODE_BITS)) {
            throw new IllegalArgumentException("nodeId out of range [0," + (1 << NODE_BITS) + ")");
        }
        this.nodeId = nodeId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            if (lastTimestamp - now > MAX_CLOCK_BACKWARD_MILLIS) {
                throw new IllegalStateException("clock backward too large: " + (lastTimestamp - now) + "ms");
            }
            now = waitUntil(lastTimestamp);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = now;
        return ((now - EPOCH_MILLIS) << (NODE_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | sequence;
    }

    private long waitUntil(long timestampExclusiveUpperBound) {
        long now = System.currentTimeMillis();
        while (now <= timestampExclusiveUpperBound) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }
}
