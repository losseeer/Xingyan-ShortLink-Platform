package com.xingyan.shortlink.gateway.auth;

import com.xingyan.shortlink.common.security.HmacSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HmacAuthFilterTest {

    private static final String API_KEY = "xy-key-alice-001";

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valueOps;
    private ReactiveHashOperations<String, String, String> hashOps;
    private HmacAuthFilter filter;
    private final AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
    private final WebFilterChain chain = exchange -> {
        passed.set(exchange);
        return Mono.empty();
    };

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        hashOps = mock(ReactiveHashOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.<String, String>opsForHash()).thenReturn(hashOps);
        filter = new HmacAuthFilter(redis, true);
    }

    private MockServerWebExchange signedExchange(HttpMethod method, String path, String body, long ts, String nonce, String secret) {
        String canonical = HmacSigner.canonical(method.name(), path, ts, nonce, body.getBytes(StandardCharsets.UTF_8));
        var builder = org.springframework.mock.http.server.reactive.MockServerHttpRequest
                .method(method, URI.create("http://localhost:8010" + path))
                .header(HmacSigner.HEADER_API_KEY, API_KEY)
                .header(HmacSigner.HEADER_TIMESTAMP, String.valueOf(ts))
                .header(HmacSigner.HEADER_NONCE, nonce)
                .header(HmacSigner.HEADER_SIGNATURE, HmacSigner.sign(secret, canonical));
        var request = body.isEmpty() ? builder.build() : builder.body(body);
        return MockServerWebExchange.from(request);
    }

    @Test
    void nonApiPathBypassesAuth() {
        var exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://localhost:8010/s/code123")).build());
        filter.filter(exchange, chain).block();
        assertNotNull(passed.get());
        verify(redis, never()).opsForValue();
    }

    @Test
    void validSignaturePassesAndInjectsTenantId() {
        long ts = System.currentTimeMillis();
        var exchange = signedExchange(HttpMethod.POST, "/api/v1/ping", "{\"a\":1}", ts, "nonce-1", API_KEY);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(Mono.just(true));
        when(hashOps.get(HmacAuthFilter.TENANT_DICT_KEY, API_KEY)).thenReturn(Mono.just("1001"));

        filter.filter(exchange, chain).block();

        assertNotNull(passed.get());
        assertEquals("1001", passed.get().getRequest().getHeaders().getFirst(HmacSigner.HEADER_TENANT_ID));
        assertNull(exchange.getResponse().getStatusCode(), "成功链路不得再写拒绝响应（Mono<Void> switchIfEmpty 陷阱回归）");
    }

    @Test
    void tamperedSignatureRejected() {
        long ts = System.currentTimeMillis();
        var exchange = signedExchange(HttpMethod.POST, "/api/v1/ping", "{\"a\":1}", ts, "nonce-2", "wrong-secret");
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void replayedOldTimestampRejected() {
        long ts = System.currentTimeMillis() - HmacSigner.TIMESTAMP_WINDOW_MILLIS - 60_000;
        var exchange = signedExchange(HttpMethod.GET, "/api/v1/ping", "", ts, "nonce-3", API_KEY);
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void replayedNonceRejected() {
        long ts = System.currentTimeMillis();
        var exchange = signedExchange(HttpMethod.POST, "/api/v1/ping", "{\"a\":1}", ts, "nonce-4", API_KEY);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(Mono.just(false));
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void unknownApiKeyRejected() {
        long ts = System.currentTimeMillis();
        var exchange = signedExchange(HttpMethod.POST, "/api/v1/ping", "{}", ts, "nonce-5", API_KEY);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(Mono.just(true));
        when(hashOps.get(HmacAuthFilter.TENANT_DICT_KEY, API_KEY)).thenReturn(Mono.empty());
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void missingHeadersRejected() {
        var exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .method(HttpMethod.POST, URI.create("http://localhost:8010/api/v1/ping")).build());
        filter.filter(exchange, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }
}
