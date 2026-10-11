package com.xingyan.shortlink.consumer.stats;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 看板查询的时间窗（DESIGN 6.4「看板取数口径」第 4 条：左闭右开 + 粒度自动）。
 *
 * <p>{@code from}/{@code to} 是**已经重新格式化过**的规范串（{@code yyyy-MM-dd HH:mm:ss}），
 * 不是调用方给的原样。这一步不是洁癖：这些串要拼进 ClickHouse 的 SQL，
 * 先把它们解析成时间对象再打回固定格式，就把"字符串里塞 SQL"这条路彻底关掉了。
 *
 * <p>时钟口径：串里是**点击侧的墙上时钟**（应用容器 Asia/Shanghai，而 ClickHouse 会话时区是 UTC、
 * {@code click_time} 存的就是那个墙上时钟串）——与 DESIGN 4.2 和 {@code 03-mock-schema.sql} 头注同一条约定。
 *
 * @param granularity 分桶粒度，同时决定 ClickHouse 用哪个 toStartOfXxx
 */
public record StatsWindow(String from, String to, Granularity granularity, long spanSeconds) {

    public enum Granularity {
        MINUTE("minute", "toStartOfMinute"),
        HOUR("hour", "toStartOfHour"),
        DAY("day", "toStartOfDay");

        private final String key;
        private final String chFunction;

        Granularity(String key, String chFunction) {
            this.key = key;
            this.chFunction = chFunction;
        }

        public String key() {
            return key;
        }

        public String bucketFunction() {
            return chFunction;
        }

        /** 入参是外部串，认不出来就返回 null，由调用方给 400——不在枚举里静默兜 default。 */
        public static Granularity from(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String v = raw.trim().toLowerCase(Locale.ROOT);
            for (Granularity g : values()) {
                if (g.key.equals(v)) {
                    return g;
                }
            }
            return null;
        }
    }

    /** 规范串之间比较用（"这一分钟是否已关闭"这类判断）。 */
    public static final DateTimeFormatter CANONICAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}
