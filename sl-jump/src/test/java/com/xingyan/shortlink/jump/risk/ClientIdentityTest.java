package com.xingyan.shortlink.jump.risk;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2-11 客户端 IP 口径（频控维度与 ClickEvent.ip_hash 同源）。
 * 关键性质：客户端自带的 {@code X-Forwarded-For} 不能改变身份——XFF 首元素由发起方任意填，
 * 用它做频控键等于把开关交给攻击者。nginx 侧 {@code X-Real-IP=$remote_addr} 是覆盖式写入的，
 * 所以优先信它；只有绕过 nginx 直连（本机直读 8020 的验收路径）才回落 XFF 末段 / remoteAddr。
 */
class ClientIdentityTest {

    @Test
    void prefersRealIpOverForgedForwardedFor() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-Real-IP", "203.0.113.7");
        r.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 9.9.9.9");
        r.setRemoteAddr("172.18.0.5");
        assertEquals("203.0.113.7", ClientIdentity.ip(r));
    }

    @Test
    void withoutRealIpTakesTheLastHopThatNginxAppended() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");
        r.setRemoteAddr("172.18.0.5");
        assertEquals("203.0.113.9", ClientIdentity.ip(r), "首元素是客户端自控的，末段才是本机 nginx 追加的那一跳");
    }

    @Test
    void fallsBackToPeerAddressThenEmpty() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.9");
        assertEquals("10.0.0.9", ClientIdentity.ip(r));
        assertEquals("", ClientIdentity.ip(null));
    }

    @Test
    void blankRealIpIsIgnoredAndSaltChangesTheHash() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-Real-IP", "   ");
        r.setRemoteAddr("10.0.0.9");
        assertEquals("10.0.0.9", ClientIdentity.ip(r));

        String a = ClientIdentity.hash("salt-a", "10.0.0.9");
        String b = ClientIdentity.hash("salt-b", "10.0.0.9");
        assertNotEquals(a, b, "加盐轮换（DESIGN 9.6）后同一 IP 的哈希必须不同");
        assertEquals(16, a.length());
        assertTrue(a.matches("[A-Za-z0-9+/]{16}"), "截断 16 位 Base64（无填充）");
        assertEquals("", ClientIdentity.hash("salt", ""), "空 IP 不产哈希，避免把不同来源聚成同一个键");
    }
}
