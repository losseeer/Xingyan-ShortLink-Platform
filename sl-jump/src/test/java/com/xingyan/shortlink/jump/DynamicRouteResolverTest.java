package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.common.route.RouteRule;
import com.xingyan.shortlink.common.route.UaClassifier;
import com.xingyan.shortlink.common.route.UaFeature;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.event.WalQueue;
import com.xingyan.shortlink.jump.risk.RateLimiter;
import com.xingyan.shortlink.jump.route.RouteRepository;
import com.xingyan.shortlink.jump.target.MockUrlLinkProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一级决策（M2-33）：priority 升序首条命中、四类 target、未识别落 default、
 * 以及"未知事实恒不匹配"这条能力边界。
 *
 * <p>time_window 用固定 Clock（2026-09-25）判，不依赖跑测试那天是几号；
 * 事件投递一律走 mock KafkaTemplate，本文件只断言决策与页面内容。
 */
class DynamicRouteResolverTest {

    private static final String ORIGIN = "https://mock.ticketsales.test/show/1";
    private static final Clock FIXED_NOON = Clock.fixed(Instant.parse("2026-09-25T04:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    private RouteRepository routes;
    private RateLimiter rateLimiter;
    private DynamicRouteResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        routes = mock(RouteRepository.class);
        AccessCounter counter = mock(AccessCounter.class);
        rateLimiter = mock(RateLimiter.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(inv -> new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>() {
                });
        when(rateLimiter.check(any(), any(), any())).thenReturn(new RateLimiter.Decision(true, 1, 60, 0, false));
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        UaClassifier ua = new UaClassifier(
                List.of(new UaFeature("wechat", List.of("micromessenger")),
                        new UaFeature("douyin", List.of("douyin", "aweme"))),
                List.of(new UaFeature("ios", List.of("iphone")),
                        new UaFeature("android", List.of("android")),
                        new UaFeature("desktop", List.of("windows nt", "macintosh"))));
        resolver = new DynamicRouteResolver(routes, counter, rateLimiter,
                new ClickEventProducer(kafka, new WalQueue("/tmp/xsl-jump-dyn-test-wal"), mapper,
                        new SimpleMeterRegistry(), "shortlink-click"),
                new SnowflakeIdGenerator(), mapper, new SimpleMeterRegistry(), ua,
                new MockUrlLinkProvider(), "test-salt", FIXED_NOON);
    }

    private static RouteConfig rc(RouteRule... rules) {
        return new RouteConfig(ORIGIN, 1001, 1, null, null, 0, 1, "ch-wx", "cp-1", "pr-9", null,
                rules.length == 0 ? null : List.of(rules));
    }

    private static MockHttpServletRequest ua(String raw) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", raw);
        return request;
    }

    private void route(String code, RouteConfig rc) {
        when(routes.find(code)).thenReturn(Optional.of(rc));
    }

    private static RouteRule rule(int priority, String type, String op, String value, String targetType,
                                  String url, String scheme, String fallback) {
        return new RouteRule(priority, type, op, value, targetType, url, scheme, fallback);
    }

    /** 快照里没有规则 = 与 M1 同形：302 到 origin_url，归因参数照旧拼。 */
    @Test
    void noRulesBehavesLikeDirect302() {
        route("plain001", rc());
        var d = resolver.resolve("plain001", ua("Mozilla/5.0 (iPhone) MicroMessenger/8.0"));
        assertEquals(HttpStatus.FOUND, d.status());
        assertNull(d.htmlBody());
        assertTrue(d.location().startsWith(ORIGIN + "?"), d.location());
        assertTrue(d.location().contains("channel_id=ch-wx"), d.location());
    }

    @Test
    void h5RuleRedirectsToTargetUrlWithAttribution() {
        route("h5x0001", rc(rule(1, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_H5,
                "https://mock.ticketsales.test/wx-landing", null, null)));
        var d = resolver.resolve("h5x0001", ua("MicroMessenger/8.0 iPhone"));
        assertEquals(HttpStatus.FOUND, d.status());
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/wx-landing?"), d.location());
        assertTrue(d.location().contains("campaign_id=cp-1"), d.location());
    }

    @Test
    void appSchemeRendersPageWithSchemeAndMergedFallback() {
        route("sch0001", rc(rule(1, RouteRule.UA_CONTAINER, "eq", "douyin", RouteRule.T_APP_SCHEME,
                null, "xyapp://open/order?id=9", "https://mock.ticketsales.test/dl")));
        var d = resolver.resolve("sch0001", ua("… douyin_29.6.0"));
        assertEquals(HttpStatus.OK, d.status());
        String html = d.htmlBody();
        assertNotNull(html);
        assertTrue(html.contains("'xyapp://open/order?id=9'"), html);
        assertTrue(html.contains("window.location.href="), html);
        assertTrue(html.contains("setTimeout(function(){if(!left){window.location.replace("), html);
        // 兜底 H5 带归因，scheme 不带（DESIGN 6.2：App deeplink 归因是另一条链路）
        assertTrue(html.contains("dl?channel_id=ch-wx"), html);
        assertTrue(!html.contains("xyapp://open/order?id=9?") && !html.contains("xyapp://open/order?id=9&"),
                "scheme 不该被拼上归因参数");
    }

    @Test
    void wxUrllinkPageUsesTheMockProviderAndKeepsGuideLink() {
        route("wxl0001", rc(rule(1, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_WX_URLLINK,
                "https://mock.ticketsales.test/mp-guide", "/pages/order/detail?id=9", null)));
        var d = resolver.resolve("wxl0001", ua("MicroMessenger iPhone"));
        assertEquals(HttpStatus.OK, d.status());
        assertTrue(d.htmlBody().contains("window.location.replace('https://wxaurl.cn/xsl-mock/"), d.htmlBody());
        assertTrue(d.htmlBody().contains("mp-guide?channel_id=ch-wx"), "取不到链接时的引导地址也要带归因");
    }

    @Test
    void intermediatePageDoesNotAutoJump() {
        route("int0001", rc(rule(1, RouteRule.DEVICE, "eq", "desktop", RouteRule.T_INTERMEDIATE,
                "https://mock.ticketsales.test/notice", null, null)));
        var d = resolver.resolve("int0001", ua("Mozilla/5.0 (Windows NT 10.0) Chrome/117"));
        assertEquals(HttpStatus.OK, d.status());
        assertTrue(!d.htmlBody().contains("window.location"), "intermediate 只做引导，不自动跳");
        assertTrue(d.htmlBody().contains("<a href=\"https://mock.ticketsales.test/notice?"), d.htmlBody());
    }

    /**
     * 首条生效由 <b>priority</b> 决定，不由数组顺序决定。管理面产出的快照是升序的，但数据面读到的
     * 不一定（M3 的 outbox 补偿、手工修数据、校验器上线前的旧行都可能乱序），而乱序不报错——
     * 只会让一条规则永远轮不到。这里故意按 20→5 的顺序写快照。
     */
    @Test
    void firstMatchingPriorityWinsNotArrayOrder() {
        route("ord0001", rc(
                rule(20, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_H5,
                        "https://mock.ticketsales.test/late", null, null),
                rule(5, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_H5,
                        "https://mock.ticketsales.test/early", null, null)));
        var d = resolver.resolve("ord0001", ua("MicroMessenger iPhone"));
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/early?"), d.location());
    }

    @Test
    void unmatchedUaFallsBackToDefaultRuleThenOrigin() {
        route("def0001", rc(
                rule(1, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_H5,
                        "https://mock.ticketsales.test/wx-only", null, null),
                rule(99, RouteRule.DEFAULT, "any", null, RouteRule.T_H5,
                        "https://mock.ticketsales.test/anybody", null, null)));
        var d = resolver.resolve("def0001", ua("Mozilla/5.0 (Windows NT 10.0) Chrome/117"));
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/anybody?"), d.location());

        // 没有 default 时不是错误：落 origin_url（新容器不被误伤，只是不走高级规则）
        route("def0002", rc(rule(1, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_H5,
                "https://mock.ticketsales.test/wx-only", null, null)));
        var d2 = resolver.resolve("def0002", ua("Mozilla/5.0 (Windows NT 10.0) Chrome/117"));
        assertEquals(HttpStatus.FOUND, d2.status());
        assertTrue(d2.location().startsWith(ORIGIN + "?"), d2.location());
    }

    /** unknown 是被判出来的值，可以显式命中："只给认不出的流量看引导页"是合法需求。 */
    @Test
    void unknownContainerCanMatchExplicitly() {
        route("unk0001", rc(
                rule(1, RouteRule.UA_CONTAINER, "eq", "unknown", RouteRule.T_INTERMEDIATE,
                        "https://mock.ticketsales.test/guide-for-unknown", null, null),
                rule(9, RouteRule.DEFAULT, "any", null, RouteRule.T_H5, "https://mock.ticketsales.test/any", null, null)));
        var d = resolver.resolve("unk0001", ua("curl/8.4.0"));
        assertEquals(HttpStatus.OK, d.status());
        assertTrue(d.htmlBody().contains("guide-for-unknown"), d.htmlBody());
    }

    @Test
    void inOperatorMatchesAnyListedValue() {
        route("in00001", rc(rule(1, RouteRule.DEVICE, "in", "ios,android", RouteRule.T_H5,
                "https://mock.ticketsales.test/mobile", null, null)));
        assertTrue(resolver.resolve("in00001", ua("Android 13")).location().startsWith("https://mock.ticketsales.test/mobile?"));
        assertTrue(resolver.resolve("in00001", ua("iPhone")).location().startsWith("https://mock.ticketsales.test/mobile?"));
        // 设备判不出来（desktop 不在这份测试表里 → unknown），条件就不成立
        assertTrue(resolver.resolve("in00001", ua("Windows NT 10.0")).location().startsWith(ORIGIN + "?"));
    }

    /** geo_province 在离线地理库接入前恒不匹配——这是能力边界，写成果然生效的条件就是假实现。 */
    @Test
    void geoProvinceNeverMatches() {
        route("geo0001", rc(
                rule(1, RouteRule.GEO_PROVINCE, "eq", "广东", RouteRule.T_H5,
                        "https://mock.ticketsales.test/gd", null, null),
                rule(9, RouteRule.DEFAULT, "any", null, RouteRule.T_H5, "https://mock.ticketsales.test/any", null, null)));
        var d = resolver.resolve("geo0001", ua("MicroMessenger iPhone"));
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/any?"), d.location());
    }

    @Test
    void timeWindowIsInclusiveOnBothEnds() {
        route("tw00001", rc(rule(1, RouteRule.TIME_WINDOW, "between", "[\"2026-09-25\",\"2026-09-30\"]",
                RouteRule.T_H5, "https://mock.ticketsales.test/presale", null, null)));
        assertTrue(resolver.resolve("tw00001", ua("iPhone")).location().startsWith("https://mock.ticketsales.test/presale?"),
                "今天正好是区间首日：含首尾");

        route("tw00002", rc(rule(1, RouteRule.TIME_WINDOW, "between", "[\"2026-09-20\",\"2026-09-25\"]",
                RouteRule.T_H5, "https://mock.ticketsales.test/past", null, null)));
        assertTrue(resolver.resolve("tw00002", ua("iPhone")).location().startsWith("https://mock.ticketsales.test/past?"),
                "今天正好是区间末日：含尾");

        route("tw00003", rc(rule(1, RouteRule.TIME_WINDOW, "between", "[\"2026-10-01\",\"2026-10-10\"]",
                RouteRule.T_H5, "https://mock.ticketsales.test/future", null, null)));
        assertTrue(resolver.resolve("tw00003", ua("iPhone")).location().startsWith(ORIGIN + "?"), "还没开始就不该命中");
    }

    /**
     * 命中了但目标残缺（旧快照、或有人绕过管理面写库）：跳过这条继续往下找，
     * 而不是回一个"跳去 null"的白屏响应。
     */
    @Test
    void matchedButUnusableTargetSkipsToNextRule() {
        route("bad0001", rc(
                rule(1, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_H5, null, null, null),
                rule(2, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_APP_SCHEME, null, null, null),
                rule(3, RouteRule.UA_CONTAINER, "eq", "wechat", RouteRule.T_APP_SCHEME,
                        null, "xyapp://open/o", null),
                rule(9, RouteRule.DEFAULT, "any", null, RouteRule.T_H5, "https://mock.ticketsales.test/any", null, null)));
        var d = resolver.resolve("bad0001", ua("MicroMessenger iPhone"));
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/any?"), d.location());
    }

    @Test
    void malformedTimeWindowValueIsSkippedNotFatal() {
        route("bad0002", rc(
                rule(1, RouteRule.TIME_WINDOW, "between", "2026-09-25", RouteRule.T_H5,
                        "https://mock.ticketsales.test/should-not-hit", null, null),
                rule(9, RouteRule.DEFAULT, "any", null, RouteRule.T_H5, "https://mock.ticketsales.test/any", null, null)));
        var d = resolver.resolve("bad0002", ua("iPhone"));
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/any?"), d.location());
    }

    @Test
    void unknownTargetTypeIsIgnored() {
        route("bad0003", rc(rule(1, RouteRule.UA_CONTAINER, "eq", "wechat", "native_wallet",
                ORIGIN, null, null)));
        var d = resolver.resolve("bad0003", ua("MicroMessenger iPhone"));
        assertTrue(d.location().startsWith(ORIGIN + "?"), d.location());
    }
}
