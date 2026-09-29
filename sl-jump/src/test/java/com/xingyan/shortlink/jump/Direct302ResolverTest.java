package com.xingyan.shortlink.jump;

import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.route.RouteRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Direct302ResolverTest {

    private RouteRepository routes;
    private AccessCounter counter;
    private Direct302Resolver resolver;

    @BeforeEach
    void setup() {
        routes = mock(RouteRepository.class);
        counter = mock(AccessCounter.class);
        resolver = new Direct302Resolver(routes, counter, new SimpleMeterRegistry());
    }

    private RouteConfig rc(Integer status, LocalDateTime expire, Integer limit) {
        return new RouteConfig("https://mock.ticketsales.test/show/1", 1001, 1,
                expire, limit, status == null ? 0 : status, 1);
    }

    @Test
    void normalRouteRedirects302() {
        when(routes.find("abcd123")).thenReturn(Optional.of(rc(0, null, null)));
        var d = resolver.resolve("abcd123", null);
        assertEquals(HttpStatus.FOUND, d.status());
        assertEquals("https://mock.ticketsales.test/show/1", d.location());
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
