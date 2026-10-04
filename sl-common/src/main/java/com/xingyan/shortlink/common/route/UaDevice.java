package com.xingyan.shortlink.common.route;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 设备大类（DESIGN 6.2「device」维度）。
 *
 * <p>{@link #key()} 同时是 {@code click_event.os} 的取值与 {@code route_rule} 里 {@code device} 条件的值
 * （词汇表约束同 {@link UaContainer}）。注意 CH 侧的容器维度落在 {@code device_type} 列、
 * 设备维度落在 {@code os} 列——列名与语义的这层错位是 M1 建表时定下的，DESIGN 4.2 已注明。
 */
public enum UaDevice {

    IOS("ios"),
    ANDROID("android"),
    DESKTOP("desktop"),
    UNKNOWN("unknown");

    private static final Map<String, UaDevice> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toMap(d -> d.key, d -> d));

    private final String key;

    UaDevice(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<UaDevice> fromKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_KEY.get(raw.trim().toLowerCase(Locale.ROOT)));
    }

    public static boolean isKnown(String raw) {
        return fromKey(raw).filter(d -> d != UNKNOWN).isPresent();
    }
}
