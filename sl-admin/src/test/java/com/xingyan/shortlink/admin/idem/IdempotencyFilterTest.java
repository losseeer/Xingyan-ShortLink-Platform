package com.xingyan.shortlink.admin.idem;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyFilterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private IdempotencyFilter filter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        filter = new IdempotencyFilter(redis, new ObjectMapper());
    }

    private MockHttpServletRequest post(String uri, String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader("X-Tenant-Id", "1001");
        if (key != null) {
            request.addHeader(IdempotencyFilter.HEADER, key);
        }
        return request;
    }

    @Test
    void missExecutesChainAndCachesResponse() throws ServletException, IOException {
        MockHttpServletRequest request = post("/api/v1/ping", "k-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(valueOps.get("sl:idem:1001:k-1")).thenReturn(null);
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws IOException {
                res.getWriter().write("{\"requestId\":\"fixed-uuid\"}");
            }
        };

        filter.doFilter(request, response, chain);

        verify(valueOps).set(eq("sl:idem:1001:k-1"), any(String.class), eq(Duration.ofHours(24)));
        assertEquals("{\"requestId\":\"fixed-uuid\"}", response.getContentAsString());
    }

    @Test
    void hitReplaysCachedResponseWithoutChain() throws Exception {
        MockHttpServletRequest request = post("/api/v1/ping", "k-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String cached = new ObjectMapper().createObjectNode()
                .put("status", 200).put("contentType", "application/json")
                .put("body", "{\"requestId\":\"fixed-uuid\"}").toString();
        when(valueOps.get("sl:idem:1001:k-2")).thenReturn(cached);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(null, chain.getRequest(), "chain 不得执行");
        assertEquals("{\"requestId\":\"fixed-uuid\"}", response.getContentAsString());
        assertEquals(200, response.getStatus());
    }

    @Test
    void getRequestsBypassFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals(request, chain.getRequest());
        verify(valueOps, never()).get(any());
    }

    @Test
    void missingKeyHeaderBypassFilter() throws ServletException, IOException {
        MockHttpServletRequest request = post("/api/v1/ping", null);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals(request, chain.getRequest());
        verify(valueOps, never()).get(any());
    }
}
