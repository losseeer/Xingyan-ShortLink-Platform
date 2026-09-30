package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.attribution.AttributionParamMerger;
import com.xingyan.shortlink.common.event.ClickEvent;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.route.RouteRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 纯 302 决议（M1-07 默认形态，M1-08/09 接线）：路由存在性 → status → 过期 → 次数上限
 * → 归因白名单拼接（AttributionParamMerger）→ ClickEvent 异步投递 → 302。
 * M2 动态路由在 JumpResolver 接口上加实现，DESIGN 第十章"纯 302 开关"即 direct302 形态为默认。
 */
@Service
public class Direct302Resolver implements JumpResolver {

    private final RouteRepository routes;
    private final AccessCounter accessCounter;
    private final ClickEventProducer clickEvents;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper mapper;
    private final String ipSalt;
    private final MeterRegistry registry;

    public Direct302Resolver(RouteRepository routes,
                             AccessCounter accessCounter,
                             ClickEventProducer clickEvents,
                             SnowflakeIdGenerator idGenerator,
                             ObjectMapper mapper,
                             MeterRegistry registry,
                             @Value("${xsl.jump.ip-salt:local-dev-salt}") String ipSalt) {
        this.routes = routes;
        this.accessCounter = accessCounter;
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
        try {
            clickEvents.publish(buildEvent(code, rc, merged, utm, request));
        } catch (Exception e) {
            registry.counter("xsl_jump_clickevent_total", "result", "build_error").increment();
        }
        return count(JumpDecision.redirect(merged.url()));
    }

    private ClickEvent buildEvent(String code, RouteConfig rc, AttributionParamMerger.Result merged,
                                  Map<String, String> utm, HttpServletRequest request) {
        ClickEvent ev = new ClickEvent();
        ev.setEventId(UUID.randomUUID().toString());
        ev.setShortCode(code);
        ev.setClickTime(System.currentTimeMillis() / 1000);
        ev.setIpHash(request == null ? "" : sha256Truncated(ipSalt + "|" + clientIp(request)));
        ev.setUserAgent(request == null ? "" : header(request, "User-Agent"));
        ev.setReferer(request == null ? "" : header(request, "Referer"));
        ev.setDeviceType("");
        ev.setOs("");
        ev.setProvince("");
        ev.setCity("");
        ev.setChannelId(nullToEmpty(rc.channelId()));
        ev.setCampaignId(nullToEmpty(rc.campaignId()));
        ev.setPromoterId(nullToEmpty(rc.promoterId()));
        ev.setTraceId(merged.traceId());
        try {
            ev.setUtmParams(mapper.writeValueAsString(utm));
        } catch (Exception e) {
            ev.setUtmParams("{}");
        }
        ev.setTenantId(rc.tenantId());
        return ev;
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static String clientIp(HttpServletRequest r) {
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return r.getRemoteAddr() == null ? "" : r.getRemoteAddr();
    }

    private static String header(HttpServletRequest r, String name) {
        String v = r.getHeader(name);
        return v == null ? "" : v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 脱敏口径（DESIGN 8.5）：不留明文 IP，截断 16 hex 位足够 uniq 统计。 */
    private static String sha256Truncated(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(d).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private JumpDecision count(JumpDecision decision) {
        registry.counter("xsl_jump_requests_total", "outcome", decision.status().name().toLowerCase()).increment();
        return decision;
    }
}
