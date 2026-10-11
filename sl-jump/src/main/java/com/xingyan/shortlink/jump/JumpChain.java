package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.attribution.AttributionParamMerger;
import com.xingyan.shortlink.common.event.ClickEvent;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.common.route.UaClassifier;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.risk.ClientIdentity;
import com.xingyan.shortlink.jump.risk.RateLimiter;
import com.xingyan.shortlink.jump.route.RouteRepository;
import com.xingyan.shortlink.jump.target.IntermediatePage;
import com.xingyan.shortlink.jump.target.Target;
import com.xingyan.shortlink.jump.target.TargetKind;
import com.xingyan.shortlink.jump.target.UrlLinkProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 跳转决策链（DESIGN 5.2 的步骤序）：路由 → status/expire → <b>频控</b> → access_limit 扣减
 * → UA 分类 → <b>一级决策（子类的唯一变化点）</b> → 归因拼接 → ClickEvent → 响应形态映射。
 *
 * <p>两个子类（{@link Direct302Resolver} / {@link DynamicRouteResolver}）由 {@code xsl.jump.mode}
 * 条件装配选一个（DESIGN 6.2「真开关」），<b>共用这一条链路</b>：链路里"频控排在配额之前"
 * "被拦仍发事件""冷重建按快照播种"都是 M2-00/M2-11/M2-12 一轮轮修出来的结论，
 * 复制成两份实现就是埋一份会漂移的副本——那才是这里真正要防的事故。
 *
 * <p>归因拼接只做一次（DESIGN 6.2）：目标里的"主落地地址"拼上白名单参数与 {@code xy_click_id}，
 * 中间页里的其它 H5 引用一律复用这条已拼好的串，所以一次点击只有一个 click_id。
 * {@code app_scheme} 的 scheme 不拼（App deeplink 归因是另一条链路，本期 mock 不做）。
 */
abstract class JumpChain implements JumpResolver {

    /** DESIGN 5.3 第 3 层给"超频"定的贡献分；评分层交付前，这是 risk_score 唯一的非 0 来源。 */
    static final int RISK_OVER_FREQUENCY = 25;

    private final RouteRepository routes;
    private final AccessCounter accessCounter;
    private final RateLimiter rateLimiter;
    private final ClickEventProducer clickEvents;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final UaClassifier uaClassifier;
    private final UrlLinkProvider urlLinkProvider;
    private final String ipSalt;

    protected JumpChain(RouteRepository routes,
                        AccessCounter accessCounter,
                        RateLimiter rateLimiter,
                        ClickEventProducer clickEvents,
                        SnowflakeIdGenerator idGenerator,
                        ObjectMapper mapper,
                        MeterRegistry registry,
                        UaClassifier uaClassifier,
                        UrlLinkProvider urlLinkProvider,
                        String ipSalt) {
        this.routes = routes;
        this.accessCounter = accessCounter;
        this.rateLimiter = rateLimiter;
        this.clickEvents = clickEvents;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
        this.registry = registry;
        this.uaClassifier = uaClassifier;
        this.urlLinkProvider = urlLinkProvider;
        this.ipSalt = ipSalt;
    }

    /** 唯一的变化点：这次点击去哪儿。返回的 Target 只描述目标，不描述响应形态。 */
    protected abstract Target chooseTarget(RouteConfig rc, UaClassifier.Profile profile);

    @Override
    public final JumpDecision resolve(String code, HttpServletRequest request) {
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
            // 被拦的点击没有跳转目标，也就没有 URL 上的 xy_click_id —— click_id 留空串而不是造一个：
            // 对账时"查不到订单"和"这一击压根没落到落地页"必须是两件事（区分方式见 DESIGN 4.2 注释）
            publish(buildEvent(code, rc, "", String.valueOf(idGenerator.nextId()), ipHash, request,
                    uaClassifier.classify(header(request, "User-Agent")), RISK_OVER_FREQUENCY));
            return count(JumpDecision.rateLimited("访问过于频繁，请稍后再试", limited.retryAfterSeconds()));
        }

        if (rc.accessLimit() != null && !accessCounter.tryConsume(code, rc.accessLimit())) {
            return count(JumpDecision.of(HttpStatus.GONE, "短链访问次数已达上限"));
        }

        UaClassifier.Profile profile = uaClassifier.classify(header(request, "User-Agent"));
        Target target = chooseTarget(rc, profile);
        Map<String, String> attrs = attribution(rc, request);
        AttributionParamMerger.Result merged =
                AttributionParamMerger.merge(target.landingForAttribution(), attrs, idGenerator);
        String urlLink = target.kind() == TargetKind.WX_URLLINK
                ? urlLinkProvider.create(target.path(), merged.url()) : null;
        publish(buildEvent(code, rc, merged.clickId(), merged.traceId(), ipHash, request, profile, 0));
        return count(deliver(target, merged.url(), urlLink));
    }

    /** 目标 → 响应形态。h5 走 302，其余三类都是 200 中间页（controller 会给它们加 no-store）。 */
    private JumpDecision deliver(Target target, String landing, String urlLink) {
        registry.counter("xsl_jump_route_target_total", "kind", target.kind().key()).increment();
        if (target.kind() == TargetKind.H5) {
            return JumpDecision.redirect(landing);
        }
        return JumpDecision.page(IntermediatePage.render(target, landing, target.schemeUrl(), urlLink));
    }

    /** M1-08 接线：白名单归因参数（channel/campaign/promoter + utm_* 从短链请求 query 透传）。 */
    private static Map<String, String> attribution(RouteConfig rc, HttpServletRequest request) {
        Map<String, String> attrs = new LinkedHashMap<>();
        putIfPresent(attrs, "channel_id", rc.channelId());
        putIfPresent(attrs, "campaign_id", rc.campaignId());
        putIfPresent(attrs, "promoter_id", rc.promoterId());
        if (request != null) {
            request.getParameterMap().forEach((k, v) -> {
                if (k.startsWith("utm_") && v != null && v.length > 0 && !v[0].isBlank()) {
                    attrs.put(k, v[0]);
                }
            });
        }
        return attrs;
    }

    private void publish(ClickEvent event) {
        try {
            clickEvents.publish(event);
        } catch (Exception e) {
            registry.counter("xsl_jump_clickevent_total", "result", "build_error").increment();
        }
    }

    private ClickEvent buildEvent(String code, RouteConfig rc, String clickId, String traceId, String ipHash,
                                  HttpServletRequest request, UaClassifier.Profile profile, int riskScore) {
        ClickEvent ev = new ClickEvent();
        ev.setEventId(UUID.randomUUID().toString());
        ev.setShortCode(code);
        ev.setClickId(clickId);
        ev.setClickTime(System.currentTimeMillis() / 1000);
        ev.setIpHash(ipHash);
        ev.setUserAgent(header(request, "User-Agent"));
        ev.setReferer(header(request, "Referer"));
        // M2-32：device_type 落容器、os 落设备大类（词汇表与 route 条件值同源，见 DESIGN 4.2）。
        // 认不出一律写 unknown 而不是空串：空串的意思是"这列没填"（M1 的历史行就是那样），
        // unknown 的意思是"填了但认不出"；至于是"没带 UA"还是"带了但没匹配上"，看 user_agent 是否为空。
        // 分类在 resolve() 里就做好了并传进来——跳转路径现在是被测出的瓶颈，不在这儿扫第二遍 UA。
        ev.setDeviceType(profile.container().key());
        ev.setOs(profile.device().key());
        ev.setProvince("");
        ev.setCity("");
        ev.setChannelId(nullToEmpty(rc.channelId()));
        ev.setCampaignId(nullToEmpty(rc.campaignId()));
        ev.setPromoterId(nullToEmpty(rc.promoterId()));
        ev.setTraceId(traceId);
        ev.setUtmParams(utmParams(request));
        ev.setRiskScore(riskScore);
        ev.setTenantId(rc.tenantId());
        return ev;
    }

    private String utmParams(HttpServletRequest request) {
        Map<String, String> utm = new LinkedHashMap<>();
        if (request != null) {
            request.getParameterMap().forEach((k, v) -> {
                if (k.startsWith("utm_") && v != null && v.length > 0 && !v[0].isBlank()) {
                    utm.put(k, v[0]);
                }
            });
        }
        try {
            return mapper.writeValueAsString(utm);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static String header(HttpServletRequest r, String name) {
        if (r == null) {
            return "";
        }
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

    /** 子类埋点用（比如"规则命中但目标残缺"这种只有子类看得见的情况）。 */
    protected final void counter(String name, String tag, String value) {
        registry.counter(name, tag, value).increment();
    }
}
