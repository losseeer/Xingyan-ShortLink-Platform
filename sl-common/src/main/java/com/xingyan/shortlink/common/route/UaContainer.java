package com.xingyan.shortlink.common.route;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 已知容器特征（DESIGN 6.2「UA 容器」维度）。
 *
 * <p>刻意只用枚举而不是引第三方 UA 解析库：本项目要区分的容器有限，通用库买到的是"显得权威"
 * 而不是更准。代价如实写明——覆盖面窄；兜底是识别不出来就落 {@code default} 规则，
 * 新容器最多不走高级规则，不会被误伤。
 */
public enum UaContainer {

    MICROMESSENGER("micromessenger"),
    DOUYIN("douyin"),
    QQ("qq"),
    WEIBO("weibo"),
    ALIPAY("alipay"),
    UNKNOWN("unknown");

    private static final Map<String, UaContainer> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toMap(c -> c.key, c -> c));

    private final String key;

    UaContainer(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<UaContainer> fromKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_KEY.get(raw.trim().toLowerCase(Locale.ROOT)));
    }

    public static boolean isKnown(String raw) {
        return fromKey(raw).filter(c -> c != UNKNOWN).isPresent();
    }
}
