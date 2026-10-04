package com.xingyan.shortlink.jump;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 响应形态的落点（M2-33）：中间页必须 {@code Cache-Control: no-store}。
 *
 * <p>页里带着本次点击的 {@code xy_click_id}，被缓存（浏览器强缓存或 CDN）就会把 A 的 click_id
 * 发给 B——归因数据静默作废。DESIGN 6.2 初稿那句"中间页本身可缓存 CDN"已按这条改掉。
 */
class JumpControllerTest {

    private final JumpResolver resolver = mock(JumpResolver.class);
    private final JumpController controller = new JumpController(resolver);

    @Test
    void redirectCarriesLocationAndNoBody() {
        when(resolver.resolve(eq("abcd123"), any())).thenReturn(
                JumpDecision.redirect("https://mock.ticketsales.test/show/1?xy_click_id=c1"));
        ResponseEntity<String> r = controller.jump("abcd123", new MockHttpServletRequest());
        assertEquals(HttpStatus.FOUND, r.getStatusCode());
        assertEquals("https://mock.ticketsales.test/show/1?xy_click_id=c1", String.valueOf(r.getHeaders().getLocation()));
        assertNull(r.getBody());
    }

    @Test
    void intermediatePageIsNeverCacheable() {
        when(resolver.resolve(eq("pg00001"), any())).thenReturn(JumpDecision.page("<!DOCTYPE html><p>hi</p>"));
        ResponseEntity<String> r = controller.jump("pg00001", new MockHttpServletRequest());
        assertEquals(HttpStatus.OK, r.getStatusCode());
        HttpHeaders h = r.getHeaders();
        assertEquals("no-store", r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("<!DOCTYPE html><p>hi</p>", r.getBody());
        assertTrue(String.valueOf(h.getContentType()).startsWith("text/html"), String.valueOf(h.getContentType()));
    }

    @Test
    void errorPagesKeepRetryAfter() {
        when(resolver.resolve(eq("rl00001"), any()))
                .thenReturn(JumpDecision.rateLimited("访问过于频繁，请稍后再试", 37));
        ResponseEntity<String> r = controller.jump("rl00001", new MockHttpServletRequest());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, r.getStatusCode());
        assertEquals("37", r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertTrue(r.getBody().contains("429"), r.getBody());
    }
}
