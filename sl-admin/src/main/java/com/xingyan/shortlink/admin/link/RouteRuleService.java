package com.xingyan.shortlink.admin.link;

import com.xingyan.shortlink.common.admission.UrlAdmissionChecker;
import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.common.route.RouteRule;
import com.xingyan.shortlink.common.route.UaContainer;
import com.xingyan.shortlink.common.route.UaDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 路由规则的行式真源读写 + 校验 + 编译（DESIGN 6.2 双轨、8.4 一致性）。
 *
 * <p>管理面唯一写路径：{@code route_rule}（逐条可校验、可查冲突）→ 编译成
 * {@code link_route.route_json.rules}（数据面唯一读取源）。两者不一致时以重编译为准。
 *
 * <p>校验刻意比 DESIGN 4.1 那行注释更窄：条件算子只允许每类条件<b>有意义</b>的那几种。
 * 表注释里的 {@code gt} 被排除——ua_container/device/geo_province 都是枚举值、time_window 是区间，
 * "大于"对它们没有可比语义；放着不报错只会让人以为自己能配出"号段之后生效"这类规则。
 * 这条收窄记在 ITER-M2 §4。
 */
@Component
public class RouteRuleService {

    static final int MAX_RULES = 20;
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]{1,40}://.{1,512}$");
    private static final Map<String, Set<String>> ALLOWED_OPS = Map.of(
            RouteRule.UA_CONTAINER, Set.of("eq", "in"),
            RouteRule.DEVICE, Set.of("eq", "in"),
            RouteRule.GEO_PROVINCE, Set.of("eq", "in"),
            RouteRule.TIME_WINDOW, Set.of("between"),
            RouteRule.DEFAULT, Set.of("any"));
    private static final Set<String> ALLOWED_TARGETS =
            Set.of(RouteRule.T_H5, RouteRule.T_APP_SCHEME, RouteRule.T_WX_URLLINK, RouteRule.T_INTERMEDIATE);

    private final JdbcTemplate jdbc;
    private final UrlAdmissionChecker admission;

    public RouteRuleService(JdbcTemplate jdbc, UrlAdmissionChecker admission) {
        this.jdbc = jdbc;
        this.admission = admission;
    }

    /** 调用方必须已在 {@code route_rule} 所在分片事务内（与 short_link 同 tenant_id 轴）。 */
    public void replaceAll(long tenantId, String code, List<RouteRule> rules) {
        jdbc.update("DELETE FROM route_rule WHERE short_code = ? AND tenant_id = ?", code, tenantId);
        for (RouteRule r : rules) {
            jdbc.update("INSERT INTO route_rule (id, short_code, tenant_id, priority, condition_type, condition_op, "
                            + "condition_value, target_type, target_url, scheme_url, fallback_url) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ruleId(tenantId, code, r.priority()), code, tenantId, r.priority(), r.conditionType(),
                    r.conditionOp(), r.conditionValue(), r.targetType(), r.targetUrl(), r.schemeUrl(), r.fallbackUrl());
        }
    }

    public List<RouteRule> load(long tenantId, String code) {
        return jdbc.query("SELECT priority, condition_type, condition_op, condition_value, target_type, "
                        + "target_url, scheme_url, fallback_url FROM route_rule "
                        + "WHERE short_code = ? AND tenant_id = ? ORDER BY priority ASC",
                (rs, i) -> new RouteRule(rs.getInt("priority"), rs.getString("condition_type"),
                        rs.getString("condition_op"), rs.getString("condition_value"), rs.getString("target_type"),
                        rs.getString("target_url"), rs.getString("scheme_url"), rs.getString("fallback_url")),
                code, tenantId);
    }

    /** 校验并编译：非法即 400，合法则返回按 priority 升序的不可变列表（进 route_json 的就是这份）。 */
    public List<RouteRule> validateAndCompile(List<RouteRule> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return List.of();
        }
        if (incoming.size() > MAX_RULES) {
            throw bad("too many rules: max " + MAX_RULES);
        }
        Set<Integer> priorities = new HashSet<>();
        Set<String> conditions = new HashSet<>();
        boolean sawDefault = false;
        List<RouteRule> compiled = new ArrayList<>(incoming.size());
        for (RouteRule r : incoming) {
            validateOne(r);
            if (!priorities.add(r.priority())) {
                throw bad("duplicated priority: " + r.priority());
            }
            if (r.unconditional()) {
                if (sawDefault) {
                    throw bad("only one default rule is allowed");
                }
                sawDefault = true;
            } else {
                String condKey = r.conditionType() + "|" + r.conditionOp() + "|"
                        + r.conditionValue().toLowerCase(Locale.ROOT);
                if (!conditions.add(condKey)) {
                    throw bad("duplicated condition: " + r.conditionType() + "=" + r.conditionValue());
                }
            }
            compiled.add(r);
        }
        compiled.sort(Comparator.comparingInt(RouteRule::priority));
        return List.copyOf(compiled);
    }

    private void validateOne(RouteRule r) {
        String type = lower(r.conditionType());
        String op = lower(r.conditionOp());
        String target = lower(r.targetType());
        if (r.priority() < 0 || r.priority() > 9999) {
            throw bad("priority must be within [0,9999]");
        }
        Set<String> ops = ALLOWED_OPS.get(type);
        if (ops == null) {
            throw bad("unknown condition_type: " + r.conditionType());
        }
        if (!ops.contains(op)) {
            throw bad("condition_op '" + r.conditionOp() + "' is not allowed for " + type + " (allowed: "
                    + String.join("/", ops) + ")");
        }
        if (!ALLOWED_TARGETS.contains(target)) {
            throw bad("unknown target_type: " + r.targetType());
        }
        if (!r.unconditional() && isBlank(r.conditionValue())) {
            throw bad("condition_value is required for " + type);
        }
        if (r.unconditional() && !isBlank(r.conditionValue())) {
            throw bad("default rule must not carry condition_value");
        }
        switch (type) {
            case RouteRule.UA_CONTAINER -> requireKnownContainer(r.conditionValue());
            case RouteRule.DEVICE -> requireKnownDevice(r.conditionValue());
            case RouteRule.GEO_PROVINCE -> {
                if (r.conditionValue().length() > 64) {
                    throw bad("geo_province value too long");
                }
            }
            case RouteRule.TIME_WINDOW -> requireDateRange(r.conditionValue());
            default -> {
            }
        }
        validateTarget(r, target);
    }

    private void validateTarget(RouteRule r, String target) {
        switch (target) {
            case RouteRule.T_H5, RouteRule.T_INTERMEDIATE -> requireHttpUrl(r.targetUrl(), "target_url");
            case RouteRule.T_APP_SCHEME -> {
                if (r.schemeUrl() == null || !SCHEME.matcher(r.schemeUrl()).matches()) {
                    throw bad("app_scheme rule needs a valid scheme_url (scheme://…)");
                }
                String scheme = r.schemeUrl().substring(0, r.schemeUrl().indexOf("://")).toLowerCase(Locale.ROOT);
                if (scheme.equals("http") || scheme.equals("https")) {
                    // app_scheme 的语义是"唤起 App、唤不起走 fallback"，填 http(s) 时中间页的降级判定毫无意义
                    throw bad("app_scheme scheme_url must not be http(s) — use target_type=h5 for a plain link");
                }
                requireHttpUrl(r.fallbackUrl(), "fallback_url");
            }
            case RouteRule.T_WX_URLLINK -> {
                // 小程序页面路径走 scheme_url 位（DESIGN 6.2 的 path 字段）；失败引导必须有 H5 兜底
                if (isBlank(r.schemeUrl())) {
                    throw bad("wx_urllink rule needs the mini-program path in scheme_url");
                }
                requireHttpUrl(r.targetUrl(), "target_url");
            }
            default -> throw bad("unknown target_type: " + target);
        }
    }

    private void requireHttpUrl(String url, String field) {
        if (isBlank(url)) {
            throw bad(field + " is required for this target_type");
        }
        String reject = admission.check(url);
        if (reject != null) {
            throw bad(field + " rejected: " + reject);
        }
    }

    private void requireKnownContainer(String value) {
        for (String v : splitList(value)) {
            if (!UaContainer.isKnown(v)) {
                throw bad("unknown ua_container: " + v + " (known: " + knownContainers() + ")");
            }
        }
    }

    private void requireKnownDevice(String value) {
        for (String v : splitList(value)) {
            if (!UaDevice.isKnown(v)) {
                throw bad("unknown device: " + v + " (known: " + knownDevices() + ")");
            }
        }
    }

    /** 错误信息里给出可选值：规则是给人配的，"unknown condition_value" 这种提示不解决问题。 */
    private static String knownContainers() {
        return java.util.Arrays.stream(UaContainer.values())
                .filter(c -> c != UaContainer.UNKNOWN).map(UaContainer::key).collect(java.util.stream.Collectors.joining(","));
    }

    private static String knownDevices() {
        return java.util.Arrays.stream(UaDevice.values())
                .filter(d -> d != UaDevice.UNKNOWN).map(UaDevice::key).collect(java.util.stream.Collectors.joining(","));
    }

    private void requireDateRange(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw bad("time_window value must be a JSON array of two ISO dates");
        }
        String[] parts = trimmed.substring(1, trimmed.length() - 1).split(",");
        if (parts.length != 2) {
            throw bad("time_window needs exactly [from,to]");
        }
        LocalDate from = parseDate(parts[0]);
        LocalDate to = parseDate(parts[1]);
        if (from.isAfter(to)) {
            throw bad("time_window from must not be after to");
        }
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim().replace("\"", ""));
        } catch (DateTimeParseException e) {
            throw bad("invalid date in time_window: " + raw);
        }
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        for (String p : value.split(",")) {
            String v = p.trim().replace("\"", "");
            if (!v.isEmpty()) {
                out.add(v.toLowerCase(Locale.ROOT));
            }
        }
        if (out.isEmpty()) {
            throw bad("condition_value is empty");
        }
        return out;
    }

    /**
     * 规则行不需要全局 ID，但必须是<b>确定</b>的：同 (tenant, code, priority) 重编译后仍是同一主键，
     * 这样"整体替换"失败重试不会留下两套行。
     * 刻意用 FNV-1a 64 而不是 {@code code.hashCode()}：后者只有 32 位，两个码撞同一个哈希时主键直接冲突
     * （表现是 500，而冲突原因藏在哈希里，查起来毫无线索）。
     */
    private static long ruleId(long tenantId, String code, int priority) {
        long h = 0xcbf29ce484222325L;
        String key = tenantId + "/" + code + "/" + priority;
        for (int i = 0; i < key.length(); i++) {
            h = (h ^ key.charAt(i)) * 0x100000001b3L;
        }
        // 清掉符号位：列是有符号 BIGINT，负数并非非法但会让 id 在排查时读成"另一个租户"
        return h & Long.MAX_VALUE;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static BizException bad(String msg) {
        return new BizException(ErrorCode.VALIDATION_FAILED, org.springframework.http.HttpStatus.BAD_REQUEST, msg);
    }
}
