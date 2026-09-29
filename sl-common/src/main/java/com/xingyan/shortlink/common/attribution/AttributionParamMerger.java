package com.xingyan.shortlink.common.attribution;

import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 归因参数白名单拼接（DESIGN 5.2/附录 B，M1-08）。
 * 规则：目标已有 query 用 &amp; 续接、无 query 用 ?；值严格 URL-encode；
 * 只追加白名单参数，禁止覆盖目标原有参数（含白名单键，防参数注入）；
 * query 注入止于首个 #，追加段同样写在 fragment 之前。
 */
public final class AttributionParamMerger {

    /** 附录 B 契约：utm_ 前缀 + 精确键；xy_click_id 由本模块生成，不接受调用方传入。 */
    private static final Set<String> EXACT_WHITELIST =
            Set.of("channel_id", "campaign_id", "promoter_id");
    private static final String CLICK_ID_KEY = "xy_click_id";
    private static final String UTM_PREFIX = "utm_";

    /**
     * @param attrs 归因参数（channel_id/campaign_id/promoter_id/utm_*）；非白名单键与 xy_click_id 被忽略
     * @return 拼接后的 URL + 实际追加的参数对（含 xy_click_id）；attrs 值原样入对，编码由本方法负责
     */
    public static Result merge(String targetUrl, Map<String, String> attrs, SnowflakeIdGenerator idGenerator) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalArgumentException("targetUrl must not be blank");
        }
        int frag = targetUrl.indexOf('#');
        String base = frag < 0 ? targetUrl : targetUrl.substring(0, frag);
        String fragment = frag < 0 ? "" : targetUrl.substring(frag);
        int q = base.indexOf('?');
        String query = q < 0 ? "" : base.substring(q + 1);
        Set<String> existing = existingKeys(query);

        Map<String, String> appended = new LinkedHashMap<>();
        if (attrs != null) {
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                String key = e.getKey();
                if (key == null || e.getValue() == null || CLICK_ID_KEY.equals(key)) continue;
                if (!allowed(key) || existing.contains(key)) continue;
                appended.put(key, e.getValue());
            }
        }
        String clickId = "c" + (idGenerator != null ? Long.toString(idGenerator.nextId())
                : Long.toHexString(System.nanoTime()));
        String traceId = Long.toHexString(idGenerator != null ? idGenerator.nextId() : System.nanoTime());
        if (!existing.contains(CLICK_ID_KEY)) {
            appended.put(CLICK_ID_KEY, clickId);
        }

        StringBuilder sb = new StringBuilder(base);
        if (appended.size() > 0) {
            if (q < 0 || query.isEmpty()) {
                // query 缺省或为悬空 ?：用 ? 续接；? 已存在则复用它
                if (q < 0) sb.append('?');
            } else {
                sb.append('&');
            }
            for (Map.Entry<String, String> e : appended.entrySet()) {
                sb.append(encode(e.getKey())).append('=').append(encode(e.getValue())).append('&');
            }
            sb.setLength(sb.length() - 1);
        }
        sb.append(fragment);
        // trace_id 不入 URL（附录 B 白名单之外），随 ClickEvent/日志贯通（DESIGN 8.5）
        return new Result(sb.toString(), clickId, traceId, appended);
    }

    private static boolean allowed(String key) {
        return EXACT_WHITELIST.contains(key) || key.startsWith(UTM_PREFIX);
    }

    /** 解析现有 query 的键集合（对截断的 %xx 容错；非法百分号转义按原文匹配，不抛异常）。 */
    private static Set<String> existingKeys(String query) {
        Set<String> keys = new LinkedHashSet<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            keys.add(decodeQuietly(rawKey));
            keys.add(rawKey);
        }
        return keys;
    }

    private static String decodeQuietly(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** RFC 3986 unreserved（A-Za-z0-9-._~）直出，其余按 UTF-8 百分号编码。 */
    private static String encode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    public record Result(String url, String clickId, String traceId, Map<String, String> appended) {
    }
}
