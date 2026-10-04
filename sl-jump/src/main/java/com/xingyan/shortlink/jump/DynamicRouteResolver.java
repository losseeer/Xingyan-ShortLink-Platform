package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.common.route.RouteRule;
import com.xingyan.shortlink.common.route.UaClassifier;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.risk.RateLimiter;
import com.xingyan.shortlink.jump.route.RouteRepository;
import com.xingyan.shortlink.jump.target.Target;
import com.xingyan.shortlink.jump.target.TargetKind;
import com.xingyan.shortlink.jump.target.UrlLinkProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 一级决策（DESIGN 6.2，M2-33）：按 {@code route_json.rules} 的 priority 升序取<b>首条命中</b>，
 * 全不命中就落 {@code origin_url}（与 {@code direct302} 同形，不是错误）。
 *
 * <p>各维度的判定口径（写在 DESIGN 里，这里逐条对应）：
 * <ul>
 *   <li>{@code ua_container}/{@code device} 比的是 {@link UaClassifier} 出来的 key，
 *       与 {@code click_event.device_type/os} 同一套串；</li>
 *   <li>{@code geo_province} <b>恒不匹配</b>——没有离线地理库之前它是"未知事实"，
 *       让它恒假比让它"看起来能用"诚实；</li>
 *   <li>{@code time_window} 判日期区间、含首尾、以服务端时钟为准；</li>
 *   <li>{@code default} 恒真；不认识的维度一律不匹配（新快照形状不该被当成命中）。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "xsl.jump.mode", havingValue = "dynamic", matchIfMissing = true)
public class DynamicRouteResolver extends JumpChain {

    private final Clock clock;

    @Autowired
    public DynamicRouteResolver(RouteRepository routes,
                                AccessCounter accessCounter,
                                RateLimiter rateLimiter,
                                ClickEventProducer clickEvents,
                                SnowflakeIdGenerator idGenerator,
                                ObjectMapper mapper,
                                MeterRegistry registry,
                                UaClassifier uaClassifier,
                                UrlLinkProvider urlLinkProvider,
                                @Value("${xsl.jump.ip-salt:local-dev-salt}") String ipSalt) {
        this(routes, accessCounter, rateLimiter, clickEvents, idGenerator, mapper, registry,
                uaClassifier, urlLinkProvider, ipSalt, Clock.systemDefaultZone());
    }

    /** 固定 Clock 的那份给单测用：time_window 的边界判定不能依赖跑测试那天是几号。 */
    DynamicRouteResolver(RouteRepository routes,
                         AccessCounter accessCounter,
                         RateLimiter rateLimiter,
                         ClickEventProducer clickEvents,
                         SnowflakeIdGenerator idGenerator,
                         ObjectMapper mapper,
                         MeterRegistry registry,
                         UaClassifier uaClassifier,
                         UrlLinkProvider urlLinkProvider,
                         String ipSalt,
                         Clock clock) {
        super(routes, accessCounter, rateLimiter, clickEvents, idGenerator, mapper, registry,
                uaClassifier, urlLinkProvider, ipSalt);
        this.clock = clock;
    }

    @Override
    protected Target chooseTarget(RouteConfig rc, UaClassifier.Profile profile) {
        List<RouteRule> rules = rc.rulesOrEmpty();
        // "首条生效"由 priority 决定，不是由数组顺序决定。管理面写出的编译产物确实是升序的，
        // 但数据面读到的快照不一定：M3 的 outbox 补偿、手工修数据、校验器上线前写的旧行都可能乱序，
        // 而顺序错了不会报错——只会让一条规则永远轮不到、另一条提前生效。
        if (rules.size() > 1) {
            rules = rules.stream().sorted(java.util.Comparator.comparingInt(RouteRule::priority)).toList();
        }
        for (RouteRule rule : rules) {
            if (!matches(rule, profile)) {
                continue;
            }
            Optional<Target> target = compile(rule);
            if (target.isPresent()) {
                return target.get();
            }
            // 命中了但目标残缺（旧快照、或有人绕过管理面直接写库）：继续找下一条，
            // 而不是返回一个"跳去 null"的响应——那种响应在浏览器里是白屏，排查成本比计数器高得多
            counter("xsl_jump_route_rule_unusable_total", "type", nullToEmpty(rule.targetType()));
        }
        return Target.h5(rc.originUrl());
    }

    private boolean matches(RouteRule rule, UaClassifier.Profile profile) {
        return switch (nullToEmpty(rule.conditionType())) {
            case RouteRule.UA_CONTAINER -> valueMatches(rule, profile.container().key());
            case RouteRule.DEVICE -> valueMatches(rule, profile.device().key());
            case RouteRule.GEO_PROVINCE -> false;
            case RouteRule.TIME_WINDOW -> timeWindowMatches(rule);
            case RouteRule.DEFAULT -> true;
            default -> false;
        };
    }

    private static boolean valueMatches(RouteRule rule, String actual) {
        String value = nullToEmpty(rule.conditionValue()).toLowerCase(Locale.ROOT);
        return switch (nullToEmpty(rule.conditionOp())) {
            case "eq" -> actual.equals(value.trim());
            case "in" -> {
                for (String one : value.split(",")) {
                    if (one.trim().equals(actual)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    private boolean timeWindowMatches(RouteRule rule) {
        String[] bounds = splitRange(rule.conditionValue());
        if (bounds == null) {
            counter("xsl_jump_route_rule_unusable_total", "type", "time_window_format");
            return false;
        }
        try {
            LocalDate today = LocalDate.now(clock);
            return !today.isBefore(LocalDate.parse(bounds[0])) && !today.isAfter(LocalDate.parse(bounds[1]));
        } catch (RuntimeException e) {
            counter("xsl_jump_route_rule_unusable_total", "type", "time_window_format");
            return false;
        }
    }

    /** 值形如 {@code ["2026-09-20","2026-09-30"]}（DESIGN 6.2：通用三元组不为某个维度加列）。 */
    private static String[] splitRange(String raw) {
        String trimmed = nullToEmpty(raw).trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return null;
        }
        String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",");
        if (parts.length != 2) {
            return null;
        }
        return new String[]{clean(parts[0]), clean(parts[1])};
    }

    private static String clean(String s) {
        return s.trim().replace("\"", "");
    }

    /**
     * 规则 → 目标。管理面校验器保证过形状，但快照可能是校验器上线前写的，所以这里仍要判空：
     * 判不过就返回 empty，由调用方决定"跳过这条"。
     */
    private static Optional<Target> compile(RouteRule rule) {
        Optional<TargetKind> kind = Target.kindOf(rule.targetType());
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        String url = trimToNull(rule.targetUrl());
        String scheme = trimToNull(rule.schemeUrl());
        String fallback = trimToNull(rule.fallbackUrl());
        return switch (kind.get()) {
            case H5 -> url == null ? Optional.empty() : Optional.of(Target.h5(url));
            case INTERMEDIATE -> url == null ? Optional.empty()
                    : Optional.of(new Target(TargetKind.INTERMEDIATE, url, null, null, null));
            case APP_SCHEME -> scheme == null || fallback == null ? Optional.empty()
                    : Optional.of(new Target(TargetKind.APP_SCHEME, null, scheme, fallback, null));
            // wx_urllink：scheme_url 位存小程序 path（M2-31 的口径），target_url 是取不到链接时的引导
            case WX_URLLINK -> url == null || scheme == null ? Optional.empty()
                    : Optional.of(new Target(TargetKind.WX_URLLINK, url, null, null, scheme));
        };
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
