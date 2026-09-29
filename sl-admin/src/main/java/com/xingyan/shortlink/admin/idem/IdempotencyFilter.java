package com.xingyan.shortlink.admin.idem;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Idempotency-Key 中间件（DESIGN 7.2）：POST /api/** 携带该头时，
 * 首次执行并缓存响应（Redis 24h），重试直接重放缓存——主办方网络抖动重试不产生重复链接。
 */
@Component
@Order(10)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public IdempotencyFilter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!"POST".equalsIgnoreCase(request.getMethod()) || key == null || key.isBlank()
                || !request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String tenant = request.getHeader("X-Tenant-Id");
        String redisKey = "sl:idem:" + (tenant == null ? "anon" : tenant) + ":" + key;

        String cached = redis.opsForValue().get(redisKey);
        if (cached != null) {
            replay(cached, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapped);
        if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
            redis.opsForValue().set(redisKey, encode(wrapped), TTL);
        }
        wrapped.copyBodyToResponse();
    }

    private String encode(ContentCachingResponseWrapper wrapped) throws IOException {
        var node = mapper.createObjectNode();
        node.put("status", wrapped.getStatus());
        node.put("contentType", wrapped.getContentType() == null ? MediaType.APPLICATION_JSON_VALUE : wrapped.getContentType());
        node.put("body", new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
        return mapper.writeValueAsString(node);
    }

    private void replay(String cached, HttpServletResponse response) throws IOException {
        var node = mapper.readTree(cached);
        response.setStatus(node.get("status").asInt());
        response.setContentType(node.get("contentType").asText());
        response.getOutputStream().write(node.get("body").asText().getBytes(StandardCharsets.UTF_8));
    }
}
