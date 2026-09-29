package com.xingyan.shortlink.common.attribution;

import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-08 验收（DEVELOPMENT_PLAN）：任意 origin_url 带 query/编码字符/# 片段时，
 * 拼接结果可被 java.net.URI 正确解析且原参数无损；注入样本（& / %26 伪造参数）不改变语义。
 */
class AttributionParamMergerPropertyTest {

    private static final SnowflakeIdGenerator IDS = new SnowflakeIdGenerator();

    private static String pc(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            b.append('%').append(String.format("%02X", x & 0xFF));
        }
        return b.toString();
    }

    /** 归因值样本：含 &、%26、#、?、空格、中文、超长串等注入/边界形态。 */
    @Provide
    Arbitrary<String> attrValues() {
        return Arbitraries.of("ch-1", "a&b=1", "%26evil=1", "x#y", "a b", "中文渠道", "utm;v", "v?v", "0".repeat(200));
    }

    /** 生成任意（可解析的）origin_url：path 可含编码字符，query 可为空/悬空 ?/多参数/尾 &，fragment 任意。 */
    @Provide
    Arbitrary<String> originUrls() {
        Arbitrary<String> seg = Arbitraries.strings().ofMinLength(1).ofMaxLength(3)
                .withCharRange('a', 'z').withCharRange('0', '9');
        Arbitrary<String> encPart = Combinators.combine(seg, Arbitraries.integers().between(0, 4))
                .as((s, k) -> pc(s.replace("a", "%26").replace("1", "#")) + "&".repeat(k));
        Arbitrary<String> pair = Combinators.combine(seg, seg).as((x, y) -> x + "=" + y);
        Arbitrary<String> query = Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.just("?"),
                Combinators.combine(pair.list().ofMinSize(0).ofMaxSize(4), Arbitraries.of("", "&"))
                        .as((ps, tail) -> "?" + String.join("&", ps) + tail));
        Arbitrary<String> encQuery = encPart.list().ofMinSize(1).ofMaxSize(3).map(ps -> "?" + String.join("", ps));
        Arbitrary<String> frag = Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.just("#"),
                Combinators.combine(seg, Arbitraries.integers().between(0, 2))
                        .as((f, k) -> "#" + f + "&".repeat(k)));
        return Arbitraries.oneOf(
                Arbitraries.just("https://mock.ticketsales.test/e"),
                Combinators.combine(
                        Arbitraries.just("https://mock.ticketsales.test/e"), query, frag)
                        .as((b, q, f) -> b + q + f),
                Combinators.combine(
                        Arbitraries.just("https://mock.ticketsales.test/e"), encQuery, frag)
                        .as((b, q, f) -> b + q + f));
    }

    private static Map<String, String> parsed(URI u) {
        Map<String, String> m = new LinkedHashMap<>();
        String raw = u.getRawQuery();
        if (raw == null || raw.isEmpty()) return m;
        for (String p : raw.split("&")) {
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String k = eq < 0 ? p : p.substring(0, eq);
            String v = eq < 0 ? "" : p.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    @Property(tries = 400)
    void resultIsParseableAndOriginalParamsIntact(@ForAll("originUrls") String url) throws URISyntaxException {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("channel_id", "ch-7");
        attrs.put("campaign_id", "cm&8");
        attrs.put("utm_source", "dou yin");

        AttributionParamMerger.Result r = AttributionParamMerger.merge(url, attrs, IDS);
        URI out = new URI(r.url());

        // 原串无损：输出 = 原前缀 + [?|&] + 追加对 + 原 fragment
        int hash = url.indexOf('#');
        String base = hash < 0 ? url : url.substring(0, hash);
        assertTrue(out.getRawQuery() != null && out.getRawQuery().startsWith(rawQueryOf(base)),
                "原 query 必须逐字节保留于前缀: " + out.getRawQuery());
        String fragRaw = hash < 0 ? "" : url.substring(hash + 1);
        assertEquals(fragRaw, raw(out.getRawFragment()), "fragment 原文必须逐字节保留");
        assertEquals(r.appended().size(), pairsAfterPrefix(out.getRawQuery(), rawQueryOf(base)));

        Map<String, String> m = parsed(out);
        assertEquals("ch-7", m.get("channel_id"));
        assertEquals("cm&8", m.get("campaign_id"));
        assertEquals("dou yin", m.get("utm_source"));
        assertEquals(r.clickId(), m.get("xy_click_id"));
    }

    private static String rawQueryOf(String base) {
        int q = base.indexOf('?');
        return q < 0 ? "" : base.substring(q + 1);
    }

    private static int pairsAfterPrefix(String rawQuery, String prefix) {
        String rest = rawQuery.substring(prefix.length());
        if (rest.isEmpty()) return 0;
        String trimmed = rest.startsWith("&") ? rest.substring(1) : rest.startsWith("?") ? rest.substring(1) : rest;
        return trimmed.isEmpty() ? 0 : trimmed.split("&").length;
    }

    /** 注入样本不改变语义：值中的 &/#/? 被编码，不产生新参数对、不泄漏 fragment。 */
    @Property(tries = 300)
    void injectedValuesNeverChangeSemantics(@ForAll("originUrls") String url,
                                            @ForAll("attrValues") String value) throws URISyntaxException {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("channel_id", value);
        AttributionParamMerger.Result r = AttributionParamMerger.merge(url, attrs, IDS);
        URI out = new URI(r.url());

        int hash = url.indexOf('#');
        String base = hash < 0 ? url : url.substring(0, hash);
        String prefix = rawQueryOf(base);
        String rest = out.getRawQuery().substring(prefix.length());
        String appendedSection = rest.startsWith("&") || rest.startsWith("?") ? rest.substring(1) : rest;
        // 追加段恰为 channel_id + xy_click_id 两对（值含 & 也只会编码为 %26）
        String[] pairs = appendedSection.split("&");
        assertEquals(2, pairs.length, "注入值不得增加参数对数: " + out.getRawQuery());
        Map<String, String> appendedPairs = new LinkedHashMap<>();
        for (String p : pairs) {
            int eq = p.indexOf('=');
            appendedPairs.put(URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
        }
        assertEquals(value, appendedPairs.get("channel_id"), "追加对解码后须还原原值");
        assertEquals(r.clickId(), appendedPairs.get("xy_click_id"));
        if (hash >= 0) {
            assertEquals(url.substring(hash + 1), raw(out.getRawFragment()), "fragment 不得被追加内容污染");
        }
    }

    private static String raw(String s) {
        return s == null ? "" : s;
    }

    /** 禁覆盖：目标原有参数（含白名单键、编码形态的键）一律保留原值。 */
    @Property(tries = 200)
    void neverOverridesExistingParams(@ForAll("originUrls") String url) throws URISyntaxException {
        String base = url.substring(0, url.indexOf('#') < 0 ? url.length() : url.indexOf('#'));
        String withChannel = base + (base.contains("?") ? "&" : "?") + "channel_id=ORIG";
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("channel_id", "INJECT");
        AttributionParamMerger.Result r = AttributionParamMerger.merge(withChannel, attrs, IDS);
        URI out = new URI(r.url());
        Map<String, String> m = parsed(out);
        assertEquals("ORIG", m.get("channel_id"), "已有参数不得被覆盖");
        assertFalse(r.url().contains("INJECT"));
        assertFalse(r.appended().containsKey("channel_id"));
        assertTrue(m.containsKey("xy_click_id"));
    }
}
