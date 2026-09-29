package com.xingyan.shortlink.gateway.auth;

import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.common.security.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 管理面 HMAC 签名鉴权（DESIGN 9.4）：校验 X-Api-Key/X-Timestamp/X-Nonce/X-Signature，
 * nonce 经 Redis SETNX 防重放，通过后注入 X-Tenant-Id 转发。租户字典 sl:tenant:api 由 seed 灌入。
 */
@Component
public class HmacAuthFilter implements WebFilter, Ordered {

    static final String TENANT_DICT_KEY = "sl:tenant:api";

    private final ReactiveStringRedisTemplate redis;
    private final boolean enabled;

    public HmacAuthFilter(ReactiveStringRedisTemplate redis,
                          @Value("${xsl.auth.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (!enabled || !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = request.getHeaders();
        String apiKey = headers.getFirst(HmacSigner.HEADER_API_KEY);
        String timestamp = headers.getFirst(HmacSigner.HEADER_TIMESTAMP);
        String nonce = headers.getFirst(HmacSigner.HEADER_NONCE);
        String signature = headers.getFirst(HmacSigner.HEADER_SIGNATURE);
        if (isBlank(apiKey) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return reject(exchange, "missing signature headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return reject(exchange, "bad timestamp");
        }
        if (!HmacSigner.timestampInWindow(ts, System.currentTimeMillis())) {
            return reject(exchange, "timestamp out of window");
        }
        return joinBody(exchange).flatMap(body -> {
            String canonical = HmacSigner.canonical(request.getMethod().name(), path, ts, nonce, body);
            if (!HmacSigner.verify(apiKey, canonical, signature)) {
                return reject(exchange, ErrorCode.SIGN_INVALID.getMessage());
            }
            String nonceKey = "sl:nonce:" + apiKey + ":" + nonce;
            return redis.opsForValue()
                    .setIfAbsent(nonceKey, "1", Duration.ofMillis(HmacSigner.TIMESTAMP_WINDOW_MILLIS * 2))
                    .flatMap(fresh -> {
                        if (!fresh) {
                            return reject(exchange, "replayed nonce");
                        }
                        // 成功链路是 Mono<Void>（空完成），switchIfEmpty 会误触发拒绝分支——用 thenReturn 哨兵隔离
                        return redis.<String, String>opsForHash().get(TENANT_DICT_KEY, apiKey)
                                .flatMap(tenantId -> chain.filter(withTenantHeader(exchange, body, tenantId)).thenReturn(Boolean.TRUE))
                                .switchIfEmpty(Mono.defer(() -> reject(exchange, "unknown api key")).thenReturn(Boolean.TRUE))
                                .then();
                    });
        });
    }

    private Mono<byte[]> joinBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                });
    }

    private ServerWebExchange withTenantHeader(ServerWebExchange exchange, byte[] body, String tenantId) {
        ServerHttpRequest original = exchange.getRequest();
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(original) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.putAll(super.getHeaders());
                httpHeaders.set(HmacSigner.HEADER_TENANT_ID, tenantId);
                httpHeaders.setContentLength(body.length);
                return httpHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String json = "{\"code\":\"" + ErrorCode.SIGN_INVALID.getCode()
                + "\",\"message\":\"" + ErrorCode.SIGN_INVALID.getMessage() + ": " + reason + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
