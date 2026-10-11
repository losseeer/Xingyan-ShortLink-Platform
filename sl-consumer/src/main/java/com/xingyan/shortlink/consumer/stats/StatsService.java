package com.xingyan.shortlink.consumer.stats;

import com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 看板只读统计（DESIGN 6.4「看板取数口径」/ 7.1 接口表 / 7.2 可达性边界，M3-05）。
 *
 * <p>四条查询全部读 **{@code click_event} 明细 + {@code FINAL}**，不读 {@code click_stat_minute}。
 * 三个理由写在 6.4，其中最硬的一条是聚合表**没有"被频控拦下"这一档**（列只有 pv/uv_state/bot_pv/
 * wx_pv/douyin_pv/browser_pv），而渠道漏斗必须有第三档。代价是每页要对窗口扫一到三次，
 * **这个代价由验收脚本实测决定要不要换**（24h 与 7d 两个窗口的 read_rows/耗时记进 ITER-M3 §3）。
 *
 * <p>四档之间的关系要说实话：因为 {@code is_bot} 现在恒 0（评分层未交付），
 * {@code pv = valid_pv + blocked_pv} 当前恒成立；评分层交付后这条会破（一条 risk_score≥25 的行
 * 也可能被判成 bot）。所以页面上是**四档各自显示**，不画成"两档相加等于总数"。
 *
 * <p>所有外部输入在进 SQL 之前都要过这里的白名单：时间串**解析成 LocalDateTime 再打回固定格式**
 * （不是过滤引号），id 类串按字符集整串校验。这些串最终是拼进 ClickHouse 的 SQL 文本的，
 * 参数化在 HTTP 接口那条路上用不了，所以只能让"能进 SQL 的字符集合"小到不含任何语法意义。
 */
@Service
public class StatsService {

    /** 短码：与 M1-06 的池码/自定义码形态一致（^[0-9a-zA-Z]{4,12}$），这里放宽到 1–32 只因为空串也要被拒。 */
    private static final Pattern CODE = Pattern.compile("^[0-9a-zA-Z]{1,32}$");
    /** 活动/渠道/推广者 id：seed 里是 ch-douyin、cp-m1-2026 这种形态，允许点/下划线/冒号/连字符。 */
    private static final Pattern ID = Pattern.compile("^[0-9a-zA-Z_.:-]{1,64}$");
    private static final Pattern TENANT = Pattern.compile("^[0-9]{1,19}$");

    private static final DateTimeFormatter CANONICAL = StatsWindow.CANONICAL;
    private static final List<DateTimeFormatter> TIME_FORMS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

    /** 四档的聚合式子，口径来自 DESIGN 6.4（门槛 25 = 5.3 第 3 层给"超频"定的贡献分）。 */
    private static final String AGG = "count() AS pv, uniqExact(ip_hash) AS uv, "
            + "countIf(is_bot = 0 AND risk_score < 25) AS valid_pv, "
            + "countIf(risk_score >= 25) AS blocked_pv, countIf(is_bot = 1) AS bot_pv";

    private final ClickHouseWriter ch;
    private final int defaultWindowHours;
    private final int maxWindowDays;

    public StatsService(ClickHouseWriter ch,
                        @Value("${xsl.stats.default-window-hours:24}") int defaultWindowHours,
                        @Value("${xsl.stats.max-window-days:31}") int maxWindowDays) {
        this.ch = ch;
        this.defaultWindowHours = Math.max(1, defaultWindowHours);
        this.maxWindowDays = Math.max(1, maxWindowDays);
    }

    // ---------------------------------------------------------------- 窗口

    /**
     * 解析窗口：缺省最近 {@code default-window-hours} 小时；粒度不给就按区间长度自动
     * （≤2h→分钟、≤7d→小时、更长→天）。左闭右开。
     */
    public StatsWindow resolveWindow(String fromRaw, String toRaw, String granularityRaw) {
        LocalDateTime to = parseTime(toRaw, "to");
        if (to == null) {
            to = LocalDateTime.now();
        }
        LocalDateTime from = parseTime(fromRaw, "from");
        if (from == null) {
            from = to.minusHours(defaultWindowHours);
        }
        if (!from.isBefore(to)) {
            throw new StatsRequestException("from 必须早于 to（窗口左闭右开）");
        }
        Duration span = Duration.between(from, to);
        if (span.toDays() > maxWindowDays) {
            throw new StatsRequestException("窗口最长 " + maxWindowDays + " 天，当前 " + span.toDays() + " 天");
        }
        StatsWindow.Granularity g = StatsWindow.Granularity.from(granularityRaw);
        if (g == null && granularityRaw != null && !granularityRaw.isBlank()) {
            throw new StatsRequestException("granularity 只能是 minute|hour|day");
        }
        if (g == null) {
            g = span.toHours() <= 2 ? StatsWindow.Granularity.MINUTE
                    : span.toDays() <= 7 ? StatsWindow.Granularity.HOUR : StatsWindow.Granularity.DAY;
        }
        // 打回规范串：之后进 SQL 的只有这个形态
        String f = from.format(CANONICAL);
        String t = to.format(CANONICAL);
        return new StatsWindow(f, t, g, span.getSeconds());
    }

    private static LocalDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        for (DateTimeFormatter fmt : TIME_FORMS) {
            try {
                return LocalDateTime.parse(v, fmt);
            } catch (DateTimeParseException ignored) {
                // 试下一个形态；全部失败才报错
            }
        }
        throw new StatsRequestException(field + " 时间格式不对（要 'YYYY-MM-DD HH:MM:SS' 或 ISO）");
    }

    // ---------------------------------------------------------------- 四条查询

    /** 单链接：汇总 + 时间序列。响应保留 `click_count`——make demo 与 accept-m1-09 在读它。 */
    public Map<String, Object> linkStats(String rawCode, StatsWindow w) {
        long started = System.nanoTime();
        String code = require(rawCode, CODE, "short_code");
        String where = "short_code = '" + code + "' AND " + window(w);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("short_code", code);
        data.putAll(envelope(w));
        Map<String, Object> totals = totals(where);
        data.put("click_count", num(totals, "pv"));        // 兼容字段，语义 == pv，别改成"含被拦"以外的口径
        data.put("totals", totals);
        data.put("series", series(where, w));
        data.put("meta", meta(started));
        return data;
    }

    /** 租户的"有点击的短码"列表（不是管理面台账：origin_url/status/expire 在 MySQL，见 DESIGN 7.2）。 */
    public Map<String, Object> tenantLinks(long tenantId, StatsWindow w, int limit, int offset) {
        long started = System.nanoTime();
        String where = "tenant_id = " + tenantId + " AND " + window(w) + " AND short_code != ''";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant_id", tenantId);
        data.putAll(envelope(w));
        data.put("totals", totals(where));
        List<Map<String, Object>> rows = rows("SELECT short_code, any(campaign_id) AS campaign_id, "
                + "any(channel_id) AS channel_id, any(promoter_id) AS promoter_id, " + AGG
                + ", max(click_time) AS last_click_time FROM " + table() + " FINAL WHERE " + where
                + " GROUP BY short_code ORDER BY pv DESC LIMIT " + limit + " OFFSET " + offset);
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("short_code", str(r, "short_code"));
            one.put("campaign_id", str(r, "campaign_id"));
            one.put("channel_id", str(r, "channel_id"));
            one.put("promoter_id", str(r, "promoter_id"));
            one.put("pv", num(r, "pv"));
            one.put("uv", num(r, "uv"));
            one.put("valid_pv", num(r, "valid_pv"));
            one.put("blocked_pv", num(r, "blocked_pv"));
            one.put("bot_pv", num(r, "bot_pv"));
            one.put("last_click_time", iso(str(r, "last_click_time")));
            links.add(one);
        }
        data.put("links", links);
        // 只给 returned/has_more，不给 total：凑一个"总码数"要再扫一遍窗口，而看板翻页并不需要它
        // （多出来的那一遍正是上面那个实测要警惕的成本，不为了一个显示字段预付）
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("limit", limit);
        page.put("offset", offset);
        page.put("returned", links.size());
        page.put("has_more", links.size() == limit);
        data.put("page", page);
        data.put("meta", meta(started));
        return data;
    }

    /**
     * 租户的时间序列（双指标页用）。
     *
     * <p>它是第五个接口，不是四条之一——因为做页面的时候才发现"渠道漏斗"那条给不出时间桶：
     * 那一页要的是渠道分布，双指标要的是随时间的走势，两者的 GROUP BY 轴不同。
     * 让双指标去借渠道接口的结果，症状是**页面显示"没有点击"而后端一切正常**（M3-07 浏览器实测抓到）。
     */
    public Map<String, Object> tenantTimeseries(long tenantId, StatsWindow w) {
        long started = System.nanoTime();
        String where = "tenant_id = " + tenantId + " AND " + window(w);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant_id", tenantId);
        data.putAll(envelope(w));
        data.put("totals", totals(where));
        data.put("series", series(where, w));
        data.put("meta", meta(started));
        return data;
    }

    /** 渠道漏斗：渠道 → 点击 →（有效／被频控拦下）。转化那一档本期不出（见 6.4 末）。 */
    public Map<String, Object> tenantChannels(long tenantId, StatsWindow w) {
        long started = System.nanoTime();
        String where = "tenant_id = " + tenantId + " AND " + window(w);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenant_id", tenantId);
        data.putAll(envelope(w));
        data.put("totals", totals(where));
        data.put("channels", dimension(where, "channel_id"));
        // 容器维度单开一条查询：uv 是 uniqExact，**不能由分组值相加**（各容器去重后相加 ≠ 全局去重），
        // 所以不能让前端拿渠道行自己凑——那一凑就是一个看起来合理、实际偏高的 UV。
        List<Map<String, Object>> containers = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT device_type, count() AS pv, "
                + "countIf(risk_score >= 25) AS blocked_pv FROM " + table() + " FINAL WHERE " + where
                + " GROUP BY device_type ORDER BY pv DESC")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("device_type", str(r, "device_type"));
            one.put("pv", num(r, "pv"));
            one.put("blocked_pv", num(r, "blocked_pv"));
            containers.add(one);
        }
        data.put("containers", containers);
        data.put("conversion", notAvailable());
        data.put("meta", meta(started));
        return data;
    }

    /** 活动趋势：汇总 + 时间序列 + 该活动下的渠道分布。 */
    public Map<String, Object> campaignStats(String rawCampaignId, StatsWindow w) {
        long started = System.nanoTime();
        String campaignId = require(rawCampaignId, ID, "campaign_id");
        String where = "campaign_id = '" + campaignId + "' AND " + window(w);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("campaign_id", campaignId);
        data.putAll(envelope(w));
        data.put("totals", totals(where));
        data.put("series", series(where, w));
        data.put("channels", dimension(where, "channel_id"));
        data.put("conversion", notAvailable());
        data.put("meta", meta(started));
        return data;
    }

    // ---------------------------------------------------------------- 公共片段

    private String table() {
        return ch.database() + ".click_event";
    }

    private String window(StatsWindow w) {
        return "click_time >= '" + w.from() + "' AND click_time < '" + w.to() + "'";
    }

    private Map<String, Object> envelope(StatsWindow w) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", iso(w.from()));
        window.put("to", iso(w.to()));
        window.put("granularity", w.granularity().key());
        window.put("span_seconds", w.spanSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window);
        out.put("source", "clickhouse:click_event FINAL");
        return out;
    }

    /**
     * 本轮取数耗时放在**返回前**才写：它是整个查询过程的真实长度，不是某个子步骤的。
     * 与归因回传那个 `query_millis` 同一个用途——"到底多贵"这个数要从服务自己身上读，
     * 而不是只靠外部脚本掐表（外面量到的是含 HTTP 与串形化的总时长，两者都留着才对得上账）。
     */
    private static Map<String, Object> meta(long startedNanos) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("query_millis", (System.nanoTime() - startedNanos) / 1_000_000);
        return meta;
    }

    private Map<String, Object> totals(String where) {
        List<Map<String, Object>> rows = rows("SELECT " + AGG + ", min(click_time) AS first_click_time, "
                + "max(click_time) AS last_click_time FROM " + table() + " FINAL WHERE " + where);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("pv", 0L);
            out.put("uv", 0L);
            out.put("valid_pv", 0L);
            out.put("blocked_pv", 0L);
            out.put("bot_pv", 0L);
            return out;
        }
        Map<String, Object> r = rows.get(0);
        out.put("pv", num(r, "pv"));
        out.put("uv", num(r, "uv"));
        out.put("valid_pv", num(r, "valid_pv"));
        out.put("blocked_pv", num(r, "blocked_pv"));
        out.put("bot_pv", num(r, "bot_pv"));
        out.put("first_click_time", iso(str(r, "first_click_time")));
        out.put("last_click_time", iso(str(r, "last_click_time")));
        return out;
    }

    private List<Map<String, Object>> series(String where, StatsWindow w) {
        String bucket = w.granularity().bucketFunction() + "(click_time)";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT " + bucket + " AS bucket, " + AGG + " FROM " + table()
                + " FINAL WHERE " + where + " GROUP BY bucket ORDER BY bucket")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("bucket", iso(str(r, "bucket")));
            one.put("pv", num(r, "pv"));
            one.put("uv", num(r, "uv"));
            one.put("valid_pv", num(r, "valid_pv"));
            one.put("blocked_pv", num(r, "blocked_pv"));
            one.put("bot_pv", num(r, "bot_pv"));
            out.add(one);
        }
        return out;
    }

    private List<Map<String, Object>> dimension(String where, String column) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT " + column + ", " + AGG + " FROM " + table()
                + " FINAL WHERE " + where + " GROUP BY " + column + " ORDER BY pv DESC")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put(column, str(r, column));
            one.put("pv", num(r, "pv"));
            one.put("uv", num(r, "uv"));
            one.put("valid_pv", num(r, "valid_pv"));
            one.put("blocked_pv", num(r, "blocked_pv"));
            one.put("bot_pv", num(r, "bot_pv"));
            out.add(one);
        }
        return out;
    }

    private Map<String, Object> notAvailable() {
        Map<String, Object> conversion = new LinkedHashMap<>();
        conversion.put("available", false);
        conversion.put("reason", "ClickHouse 里没有订单数据（假订单在 MySQL xsl_mock.mock_order）；"
                + "跨源现读会让转化率随两个源的延迟自己跳，所以本期留空。见 DESIGN 6.4 末与 ITER-M3 §6");
        return conversion;
    }

    private List<Map<String, Object>> rows(String sql) {
        try {
            return ch.rows(sql);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StatsBackendException("ClickHouse 查询失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 入参把关

    static long requireTenant(String raw) {
        if (raw == null || !TENANT.matcher(raw.trim()).matches()) {
            throw new StatsRequestException("tenant_id 必须是数字");
        }
        return Long.parseLong(raw.trim());
    }

    static int clamp(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new StatsRequestException(field + " 必须在 " + min + "–" + max + " 之间");
        }
        return value;
    }

    private static String require(String raw, Pattern pattern, String field) {
        String v = raw == null ? "" : raw.trim();
        if (!pattern.matcher(v).matches()) {
            throw new StatsRequestException(field + " 不合法（只允许 " + pattern + "）");
        }
        return v;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : v.toString();
    }

    private static long num(Map<String, Object> row, String key) {
        return ClickHouseWriter.longOf(row.get(key));
    }

    /** CH 回来的时间串是空格形式，出去统一写成 ISO（M3-03 的教训：格式不一致会伪装成解析错）。 */
    private static String iso(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("1970")) {
            return null;
        }
        return raw.replace(' ', 'T');
    }
}
