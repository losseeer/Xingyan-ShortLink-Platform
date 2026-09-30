package com.xingyan.shortlink.jump;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.event.ClickEventProducer;
import com.xingyan.shortlink.jump.event.WalQueue;
import com.xingyan.shortlink.jump.route.RouteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Direct302ResolverTest {

    private RouteRepository routes;
    private AccessCounter counter;
    private KafkaTemplate<String, String> kafka;
    private Direct302Resolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        routes = mock(RouteRepository.class);
        counter = mock(AccessCounter.class);
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenAnswer(inv -> new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>() {
                });
        WalQueue wal = new WalQueue("/tmp/xsl-jump-test-wal");
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        ClickEventProducer producer = new ClickEventProducer(kafka, wal, mapper,
                new SimpleMeterRegistry(), "shortlink-click");
        resolver = new Direct302Resolver(routes, counter, producer, new SnowflakeIdGenerator(),
                mapper, new SimpleMeterRegistry(), "test-salt");
    }

    private RouteConfig rc(Integer status, LocalDateTime expire, Integer limit) {
        return new RouteConfig("https://mock.ticketsales.test/show/1", 1001, 1,
                expire, limit, status == null ? 0 : status, 1, "ch-9", "cm-9", null);
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
                null, null, 0, 1, null, null, null)));
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
}
