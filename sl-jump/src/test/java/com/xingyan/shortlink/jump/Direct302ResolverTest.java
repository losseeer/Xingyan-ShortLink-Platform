package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.event.WalQueue;
import com.xingyan.shortlink.jump.risk.RateLimiter;
import com.xingyan.shortlink.jump.route.RouteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Direct302ResolverTest {

    private RouteRepository routes;
    private AccessCounter counter;
    private RateLimiter rateLimiter;
    private KafkaTemplate<String, String> kafka;
    private Direct302Resolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        routes = mock(RouteRepository.class);
        counter = mock(AccessCounter.class);
        rateLimiter = mock(RateLimiter.class);
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(inv -> new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>() {
                });
        allowAll(60);
        WalQueue wal = new WalQueue("/tmp/xsl-jump-test-wal");
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        ClickEventProducer producer = new ClickEventProducer(kafka, wal, mapper,
                new SimpleMeterRegistry(), "shortlink-click");
        resolver = new Direct302Resolver(routes, counter, rateLimiter, producer, new SnowflakeIdGenerator(),
                mapper, new SimpleMeterRegistry(), "test-salt");
    }

    private void allowAll(int limit) {
        when(rateLimiter.check(any(), any(), any()))
                .thenReturn(new RateLimiter.Decision(true, 1, limit, 0, false));
    }

    private void block(int retryAfter) {
        when(rateLimiter.check(any(), any(), any()))
                .thenReturn(new RateLimiter.Decision(false, 61, 60, retryAfter, false));
    }

    private RouteConfig rc(Integer status, LocalDateTime expire, Integer limit) {
        return rc(status, expire, limit, null);
    }

    private RouteConfig rc(Integer status, LocalDateTime expire, Integer limit, Integer rateLimit) {
        return new RouteConfig("https://mock.ticketsales.test/show/1", 1001, 1,
                expire, limit, status == null ? 0 : status, 1, "ch-9", "cm-9", null, rateLimit);
    }

    @Test
    void normalRouteRedirects302() {
        when(routes.find("abcd123")).thenReturn(Optional.of(rc(0, null, null)));
        var d = resolver.resolve("abcd123", null);
        assertEquals(HttpStatus.FOUND, d.status());
        assertTrue(d.location().startsWith("https://mock.ticketsales.test/show/1?"));
        assertTrue(d.location().contains("xy_click_id=c"));
        assertTrue(d.location().contains("channel_id=ch-9"));
    }

    @Test
    void utmPassthroughAndNoOverride() {
        when(routes.find("utm0001")).thenReturn(Optional.of(new RouteConfig(
                "https://mock.ticketsales.test/show/1?utm_source=orig", 1001, 1,
                null, null, 0, 1, null, null, null, null)));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("utm_source", "injected");
        req.addParameter("utm_medium", "sms");
        req.addParameter("evil_param", "x");
        var d = resolver.resolve("utm0001", req);
        // utm_source 已存在于目标 query → 禁覆盖；utm_medium 追加；非白名单丢弃
        assertTrue(d.location().contains("utm_source=orig"));
        assertTrue(d.location().contains("&utm_medium=sms"));
        assertTrue(!d.location().contains("evil_param"));
    }

    @Test
    void unknownCode404() {
        when(routes.find("nosuch1")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, resolver.resolve("nosuch1", null).status());
    }

    @Test
    void disabledOrBanned403() {
        when(routes.find("off0001")).thenReturn(Optional.of(rc(1, null, null)));
        assertEquals(HttpStatus.FORBIDDEN, resolver.resolve("off0001", null).status());
        when(routes.find("ban0001")).thenReturn(Optional.of(rc(2, null, null)));
        assertEquals(HttpStatus.FORBIDDEN, resolver.resolve("ban0001", null).status());
    }

    @Test
    void expired410() {
        when(routes.find("exp0001")).thenReturn(Optional.of(rc(0, LocalDateTime.now().minusSeconds(1), null)));
        assertEquals(HttpStatus.GONE, resolver.resolve("exp0001", null).status());
    }

    @Test
    void futureExpireStillRedirects() {
        when(routes.find("fut0001")).thenReturn(Optional.of(rc(0, LocalDateTime.now().plusDays(1), null)));
        assertEquals(HttpStatus.FOUND, resolver.resolve("fut0001", null).status());
    }

    @Test
    void accessLimitExceeded410() {
        when(routes.find("lim00001")).thenReturn(Optional.of(rc(0, null, 100)));
        when(counter.tryConsume("lim00001", 100)).thenReturn(false);
        assertEquals(HttpStatus.GONE, resolver.resolve("lim00001", null).status());

        when(counter.tryConsume("lim00001", 100)).thenReturn(true);
        assertEquals(HttpStatus.FOUND, resolver.resolve("lim00001", null).status());
    }

    /** DESIGN 5.2 步骤序（v2.3.0）：频控命中就不该再消耗配额，否则刷量能把链接配额自己烧光。 */
    @Test
    void rateLimitedReturns429AndDoesNotConsumeQuota() {
        when(routes.find("rl00001")).thenReturn(Optional.of(rc(0, null, 100)));
        block(37);
        var d = resolver.resolve("rl00001", null);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, d.status());
        assertEquals(37, d.retryAfterSeconds());
        verify(counter, never()).tryConsume(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** 被拦下的点击仍要进事件流，否则看板只看到流量凭空消失，看不到被拦掉的那部分。 */
    @Test
    void rateLimitedClickStillPublishesEvent() {
        when(routes.find("rl00002")).thenReturn(Optional.of(rc(0, null, null)));
        block(12);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resolver.resolve("rl00002", null).status());
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    /** 链接级阈值必须原样传给频控（全局阈值对推给十万人的链接会误伤正常流量）。 */
    @Test
    void perLinkRateLimitReachesTheLimiter() {
        when(routes.find("rl00003")).thenReturn(Optional.of(rc(0, null, null, 5000)));
        resolver.resolve("rl00003", null);
        verify(rateLimiter).check(org.mockito.ArgumentMatchers.eq("rl00003"), any(),
                org.mockito.ArgumentMatchers.eq(5000));
    }
}
