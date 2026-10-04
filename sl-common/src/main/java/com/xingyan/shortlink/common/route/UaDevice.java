package com.xingyan.shortlink.common.route;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 设备大类（DESIGN 6.2「device」维度，同时用于 ClickEvent 的 device_type）。 */
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
