package com.xingyan.shortlink.common.admission;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UrlAdmissionCheckerTest {

    private final UrlAdmissionChecker checker = new UrlAdmissionChecker(
            Set.of("mock.ticketsales.test", ".xystage.test"));

    @Test
    void acceptsWhitelistedHosts() {
        assertNull(checker.check("https://mock.ticketsales.test/detail"));
        assertNull(checker.check("https://ticket.a.xystage.test/show/1"));
        assertNull(checker.check("https://xystage.test/"));
        assertNull(checker.check("HTTPS://MOCK.TICKETSALES.TEST/p"));
    }

    @Test
    void rejectsNonHttpsAndMalformed() {
        assertNotNull(checker.check(null));
        assertNotNull(checker.check(" "));
        assertNotNull(checker.check("http://mock.ticketsales.test/p"));
        assertNotNull(checker.check("ftp://mock.ticketsales.test/p"));
        assertNotNull(checker.check("mock.ticketsales.test/p"));
        assertNotNull(checker.check("https:// bad host/"));
    }

    @Test
    void rejectsOutsideWhitelistAndTricks() {
        assertNotNull(checker.check("https://evil.test/p"));
        assertNotNull(checker.check("https://xystage.evil.test/p"));
        // userinfo 伪装：evil.com@good.test 实际目标是 good.test，但 M1 一律拒绝 userinfo
        assertNotNull(checker.check("https://evil.com@mock.ticketsales.test/p"));
        assertNotNull(checker.check("https://127.0.0.1/p"));
        assertNotNull(checker.check("https://localhost:8443/p"));
        assertNotNull(checker.check("https://mock.ticketsales.test:8443/p"));
        assertNotNull(checker.check("https:///p"));
    }

    @Test
    void toleratesTrailingDotHost() {
        assertNull(checker.check("https://mock.ticketsales.test./p"));
    }
}
