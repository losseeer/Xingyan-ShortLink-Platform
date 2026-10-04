package com.xingyan.shortlink.common.route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * UA 分类器（DESIGN 6.2「特征表自维护 + 配置加载」，特征表面在 sl-jump 的 {@code xsl.jump.ua.*}）。
 *
 * <p>两条判定各自独立、各自按声明顺序取<b>首个子串命中</b>：容器（{@link UaContainer}）与设备
 * （{@link UaDevice}）是两个维度，微信里的 iPhone 要同时给出 {@code wechat} + {@code ios}，
 * 所以不能把它们合成一个枚举。
 *
 * <p>表在构造时就做完整校验（key 在词汇表里、token 非空、key 不重复）：<b>配错的代价必须是起不来，
 * 而不是那条特征永远匹配不上</b>——后者会让一条动态路由规则静默失效，等到看板上"微信流量为 0"
 * 才发现，而那时已经查不出是配置、UA 还是路由的问题了。
 */
public final class UaClassifier {

    private record Rule(String key, List<String> tokens) {
    }

    private final List<Rule> containers;
    private final List<Rule> devices;

    public UaClassifier(List<UaFeature> containers, List<UaFeature> devices) {
        this.containers = compile(containers, "xsl.jump.ua.containers", UaContainer::fromKey);
        this.devices = compile(devices, "xsl.jump.ua.devices", UaDevice::fromKey);
    }

    /** 容器判定：全不命中＝{@link UaContainer#UNKNOWN}（规则侧靠 default 兜底，见 DESIGN 6.2）。 */
    public UaContainer container(String userAgent) {
        return containerOf(normalize(userAgent));
    }

    public UaDevice device(String userAgent) {
        return deviceOf(normalize(userAgent));
    }

    /** 跳转路径上的入口：UA 只转一次小写、只走两遍表。 */
    public Profile classify(String userAgent) {
        String ua = normalize(userAgent);
        return new Profile(containerOf(ua), deviceOf(ua));
    }

    /** 一次 UA 的两条判定结果（容器与设备是两个独立维度，见类注释）。 */
    public record Profile(UaContainer container, UaDevice device) {
    }

    private UaContainer containerOf(String ua) {
        return firstMatch(containers, ua).map(this::toContainer).orElse(UaContainer.UNKNOWN);
    }

    private UaDevice deviceOf(String ua) {
        return firstMatch(devices, ua).map(this::toDevice).orElse(UaDevice.UNKNOWN);
    }

    private static Optional<String> firstMatch(List<Rule> rules, String ua) {
        if (ua.isEmpty()) {
            return Optional.empty();
        }
        for (Rule rule : rules) {
            for (String token : rule.tokens()) {
                if (ua.contains(token)) {
                    return Optional.of(rule.key());
                }
            }
        }
        return Optional.empty();
    }

    private UaContainer toContainer(String key) {
        return UaContainer.fromKey(key).orElse(UaContainer.UNKNOWN);
    }

    private UaDevice toDevice(String key) {
        return UaDevice.fromKey(key).orElse(UaDevice.UNKNOWN);
    }

    private static String normalize(String userAgent) {
        return userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface Lookup {
        Optional<? extends Enum<?>> resolve(String key);
    }

    private static List<Rule> compile(List<UaFeature> features, String path, Lookup lookup) {
        if (features == null || features.isEmpty()) {
            throw new IllegalArgumentException(path + " 不能为空：一条特征都没有＝所有流量都判为 unknown");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Rule> rules = new ArrayList<>(features.size());
        for (UaFeature f : features) {
            String key = f.key() == null ? "" : f.key().trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new IllegalArgumentException(path + " 有条目缺 key");
            }
            if ("unknown".equals(key)) {
                // unknown 是"全不命中"的兜底结果，不该由表来指定：那样会把可识别的流量伪装成不可识别
                throw new IllegalArgumentException(path + " 不允许把 key 配成 unknown（它是兜底值，不是特征）");
            }
            if (lookup.resolve(key).isEmpty()) {
                throw new IllegalArgumentException(path + " 的 key '" + f.key() + "' 不在词汇表里（改 UA 串请动 tokens，"
                        + "新增类别要同时动枚举与 route 条件/统计取值三处，见 DESIGN 4.2）");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException(path + " 里 key '" + key + "' 出现两次（谁生效由顺序决定，等于埋雷）");
            }
            List<String> tokens = new ArrayList<>();
            for (String t : f.tokens() == null ? List.<String>of() : f.tokens()) {
                if (t != null && !t.isBlank()) {
                    tokens.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException(path + " 的 '" + key + "' 没有任何 token：这条永远不会命中");
            }
            rules.add(new Rule(key, List.copyOf(tokens)));
        }
        return List.copyOf(rules);
    }
}
