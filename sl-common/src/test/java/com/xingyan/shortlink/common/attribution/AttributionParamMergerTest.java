package com.xingyan.shortlink.common.attribution;

import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1-08 确定性用例：&/? 续接、fragment 前插、白名单过滤、禁覆盖、click/trace 生成。 */
class AttributionParamMergerTest {

    private static final SnowflakeIdGenerator IDS = new SnowflakeIdGenerator();

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void appendsWithQuestionWhenNoQuery() throws URISyntaxException {
        var r = AttributionParamMerger.merge("https://h/p", attrs("channel_id", "7"), IDS);
        assertTrue(r.url().startsWith("https://h/p?channel_id=7&xy_click_id="));
        new URI(r.url());
    }

    @Test
    void appendsWithAmpWhenQueryExists() throws URISyntaxException {
        var r = AttributionParamMerger.merge("https://h/p?a=1", attrs("channel_id", "7"), IDS);
        assertEquals("https://h/p?a=1&channel_id=7&xy_click_id=" + r.clickId(), r.url());
    }

    private static Map<String, String> params(URI u) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : u.getRawQuery().split("&")) {
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            m.putIfAbsent(java.net.URLDecoder.decode(p.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                    eq < 0 ? "" : java.net.URLDecoder.decode(p.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void reusesDanglingQuestionMark() throws URISyntaxException {
        var r = AttributionParamMerger.merge("https://h/p?", attrs("channel_id", "7"), IDS);
        assertEquals("https://h/p?channel_id=7&xy_click_id=" + r.clickId(), r.url());
        assertEquals("7", params(new URI(r.url())).get("channel_id"));
    }

    @Test
    void insertsBeforeFragment() {
        var r = AttributionParamMerger.merge("https://h/p?a=1#top", attrs("channel_id", "7"), IDS);
        assertEquals("https://h/p?a=1&channel_id=7&xy_click_id=" + r.clickId() + "#top", r.url());
    }

    @Test
    void strictlyEncodesValues() throws URISyntaxException {
        var r = AttributionParamMerger.merge("https://h/p",
                attrs("utm_source", "a&b=1#c%d 中文", "channel_id", "x=y"), IDS);
        URI u = new URI(r.url());
        assertEquals("a&b=1#c%d 中文", params(u).get("utm_source"));
        assertEquals("x=y", params(u).get("channel_id"));
        assertTrue(r.url().contains("utm_source=a%26b%3D1%23c%25d%20%E4%B8%AD%E6%96%87"));
        assertTrue(r.url().contains("channel_id=x%3Dy"));
    }

    @Test
    void filtersNonWhitelistAndClickIdOverride() {
        var r = AttributionParamMerger.merge("https://h/p",
                attrs("redirect_url", "https://evil", "xy_click_id", "forged", "utm_medium", "sms", "channel_id", "1"), IDS);
        assertEquals("sms", r.appended().get("utm_medium"));
        assertEquals("1", r.appended().get("channel_id"));
        assertFalse(r.appended().containsKey("redirect_url"));
        assertNotEquals("forged", r.clickId());
        assertTrue(r.clickId().startsWith("c"));
        assertFalse(r.traceId().isBlank());
    }

    @Test
    void neverOverridesExistingWhitelistedKey() {
        var r = AttributionParamMerger.merge("https://h/p?channel_id=orig", attrs("channel_id", "inj"), IDS);
        assertFalse(r.appended().containsKey("channel_id"));
        assertTrue(r.url().contains("channel_id=orig"));
        assertFalse(r.url().contains("inj"));
    }

    @Test
    void recognizesPercentEncodedExistingKeys() {
        var r = AttributionParamMerger.merge("https://h/p?chan%6Eel_id=orig", attrs("channel_id", "inj"), IDS);
        assertFalse(r.appended().containsKey("channel_id"));
    }

    @Test
    void rejectsBlankTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> AttributionParamMerger.merge("  ", attrs("channel_id", "1"), IDS));
    }
}
