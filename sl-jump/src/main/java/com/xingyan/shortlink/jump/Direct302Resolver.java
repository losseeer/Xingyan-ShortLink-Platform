package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.attribution.AttributionParamMerger;
import com.xingyan.shortlink.common.event.ClickEvent;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.risk.ClientIdentity;
import com.xingyan.shortlink.jump.risk.RateLimiter;
import com.xingyan.shortlink.jump.route.RouteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 纯 302 决议（M1-07 形态，M1-08/09 接线，M2-11 插频控）。
 * 步骤序按 DESIGN 5.2 v2.3.0：路由 → status/expire → <b>频控</b> → access_limit 扣减
 * → 归因拼接 → ClickEvent → 302。
 *
 * <p>频控排在扣减之前：否则高频刷量能先把链接配额烧光（限流反成 DoS 面）；
 * 排在存在性/status 之后：否则对随机码的探测会给每个猜测值建 60s 频控键。
 * 命中频控的点击不发跳转、不扣配额，但<b>事件仍投递</b>（risk_score=+25），
 * 否则看板只能看到"流量凭空消失"，看不到被拦掉的那部分。
 */
@Service
public class Direct302Resolver implements JumpResolver {

    /** DESIGN 5.3 第 3 层给"超频"定的贡献分；评分层（M2 后续任务）交付前，这是 risk_score 唯一的非 0 来源。 */
    static final int RISK_OVER_FREQUENCY = 25;

    private final RouteRepository routes;
    private final AccessCounter accessCounter;
    private final RateLimiter rateLimiter;
    private final ClickEventProducer clickEvents;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper mapper;
    private final String ipSalt;
    private final MeterRegistry registry;

    public Direct302Resolver(RouteRepository routes,
                             AccessCounter accessCounter,
                             RateLimiter rateLimiter,
                             ClickEventProducer clickEvents,
                             SnowflakeIdGenerator idGenerator,
                             ObjectMapper mapper,
                             MeterRegistry registry,
                             @Value("${xsl.jump.ip-salt:local-dev-salt}") String ipSalt) {
        this.routes = routes;
        this.accessCounter = accessCounter;
        this.rateLimiter = rateLimiter;
        this.clickEvents = clickEvents;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.registry = registry;
        this.ipSalt = ipSalt;
    }

    @Override
    public JumpDecision resolve(String code, HttpServletRequest request) {
        Optional<RouteConfig> route = routes.find(code);
        if (route.isEmpty()) {
            return count(JumpDecision.of(HttpStatus.NOT_FOUND, "短链不存在或已删除"));
        }
        RouteConfig rc = route.get();
        if (rc.status() != 0) {
            return count(JumpDecision.of(HttpStatus.FORBIDDEN, "短链已停用或被封禁"));
        }
        if (rc.expireTime() != null && LocalDateTime.now().isAfter(rc.expireTime())) {
            return count(JumpDecision.of(HttpStatus.GONE, "短链已过期"));
        }

        String ipHash = ClientIdentity.hash(ipSalt, ClientIdentity.ip(request));
        RateLimiter.Decision limited = rateLimiter.check(code, ipHash, rc.rateLimitPerMinute());
        if (!limited.allowed()) {
            publish(buildEvent(code, rc, String.valueOf(idGenerator.nextId()), ipHash, request, RISK_OVER_FREQUENCY));
            return count(JumpDecision.rateLimited("访问过于频繁，请稍后再试", limited.retryAfterSeconds()));
        }

        if (rc.accessLimit() != null && !accessCounter.tryConsume(code, rc.accessLimit())) {
            return count(JumpDecision.of(HttpStatus.GONE, "短链访问次数已达上限"));
        }

        // M1-08 接线：白名单归因参数拼接（utm_* 从短链请求 query 透传）
        Map<String, String> attrs = new LinkedHashMap<>();
        putIfPresent(attrs, "channel_id", rc.channelId());
        putIfPresent(attrs, "campaign_id", rc.campaignId());
        putIfPresent(attrs, "promoter_id", rc.promoterId());
        Map<String, String> utm = new LinkedHashMap<>();
        if (request != null) {
            request.getParameterMap().forEach((k, v) -> {
                if (k.startsWith("utm_") && v != null && v.length > 0 && !v[0].isBlank()) {
                    attrs.put(k, v[0]);
                    utm.put(k, v[0]);
                }
            });
        }
        AttributionParamMerger.Result merged =
                AttributionParamMerger.merge(rc.originUrl(), attrs, idGenerator);
        publish(buildEvent(code, rc, merged.traceId(), ipHash, request, 0));
        return count(JumpDecision.redirect(merged.url()));
    }

    private void publish(ClickEvent event) {
        try {
            clickEvents.publish(event);
        } catch (Exception e) {
            registry.counter("xsl_jump_clickevent_total", "result", "build_error").increment();
        }
    }

    private ClickEvent buildEvent(String code, RouteConfig rc, String traceId, String ipHash,
                                  HttpServletRequest request, int riskScore) {
        ClickEvent ev = new ClickEvent();
        ev.setEventId(UUID.randomUUID().toString());
        ev.setShortCode(code);
        ev.setClickTime(System.currentTimeMillis() / 1000);
        ev.setIpHash(ipHash);
        ev.setUserAgent(request == null ? "" : header(request, "User-Agent"));
        ev.setReferer(request == null ? "" : header(request, "Referer"));
        ev.setDeviceType("");
        ev.setOs("");
        ev.setProvince("");
        ev.setCity("");
        ev.setChannelId(nullToEmpty(rc.channelId()));
        ev.setCampaignId(nullToEmpty(rc.campaignId()));
        ev.setPromoterId(nullToEmpty(rc.promoterId()));
        ev.setTraceId(traceId);
        Map<String, String> utm = new LinkedHashMap<>();
        if (request != null) {
            request.getParameterMap().forEach((k, v) -> {
                if (k.startsWith("utm_") && v != null && v.length > 0 && !v[0].isBlank()) {
                    utm.put(k, v[0]);
                }
            });
        }
        try {
            ev.setUtmParams(mapper.writeValueAsString(utm));
        } catch (Exception e) {
            ev.setUtmParams("{}");
        }
        ev.setRiskScore(riskScore);
        ev.setTenantId(rc.tenantId());
        return ev;
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static String header(HttpServletRequest r, String name) {
        String v = r.getHeader(name);
        return v == null ? "" : v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private JumpDecision count(JumpDecision decision) {
        registry.counter("xsl_jump_requests_total", "outcome", decision.status().name().toLowerCase()).increment();
        return decision;
    }
}
