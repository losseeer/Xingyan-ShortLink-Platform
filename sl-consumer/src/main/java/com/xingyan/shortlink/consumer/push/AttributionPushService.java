package com.xingyan.shortlink.consumer.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.attribution.AttributionBatchCodec;
import com.xingyan.shortlink.common.attribution.AttributionStat;
import com.xingyan.shortlink.common.security.HmacSigner;
import com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 归因回传（DESIGN 6.3-3 / 7.3，M3-03）：把分钟桶的点击事实推给宣发数据平台（本机是 sl-mock 等价实现）。
 *
 * <p>三件值得先说清的事：
 * ① <b>取明细表 {@code click_event FINAL} 而不是聚合表</b>——聚合表没有 valid_pv，且它靠 async_insert
 * 后台合并，part 未合并时读它本身就带重复/缺失风险，而推给外部平台的必须是能当基准的那份数（6.4 的对账
 * 也以它为基准）。代价是每轮扫一遍窗口明细，{@code queryMillis} 就是量这个代价的，实测写进 ITER-M3 §3。
 * ② <b>batch_id 由内容决定</b>（{@link AttributionBatchCodec#batchId}）：同一分钟的数会因迟到而变全，
 * 纯窗口 id 会把"更全的那一版"当重复丢掉。
 * ③ <b>不重试、不水位</b>：失败就等下一轮。回看窗口内同批内容算出同一个 id，接收端幂等吸收；
 * 因此这里没有任何需要持久化的游标，代价是"迟到超过回看窗口的点击不进回传"（边界写在 6.3）。
 */
@Component
public class AttributionPushService {

    private static final Logger log = LoggerFactory.getLogger(AttributionPushService.class);
    private static final DateTimeFormatter CH_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Summary(int tenants, int items, int sent, int duplicated, int rejected, int skipped, int errors,
                          long queryMillis, String windowFrom, String windowTo) {
    }

    private final ClickHouseWriter ch;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final boolean enabled;
    private final int lookbackMinutes;
    private final String endpoint;
    private final String apiKey;
    private final long tenantAllowList;
    private final Duration timeout;

    public AttributionPushService(ClickHouseWriter ch,
                                  ObjectMapper mapper,
                                  MeterRegistry registry,
                                  @Value("${xsl.push.enabled:true}") boolean enabled,
                                  @Value("${xsl.push.lookback-minutes:3}") int lookbackMinutes,
                                  @Value("${xsl.push.endpoint:}") String endpoint,
                                  @Value("${xsl.push.api-key:}") String apiKey,
                                  @Value("${xsl.push.tenant-id:1001}") long tenantId,
                                  @Value("${xsl.push.timeout-ms:5000}") int timeoutMs) {
        this.ch = ch;
        this.mapper = mapper;
        this.registry = registry;
        this.enabled = enabled;
        this.lookbackMinutes = Math.max(1, Math.min(lookbackMinutes, 60));
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.tenantAllowList = tenantId;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 500));
    }

    /** 定时与手动触发（{@code POST /internal/attribution/push}）共用这一份逻辑。 */
    @Scheduled(fixedDelayString = "${xsl.push.interval-ms:300000}",
               initialDelayString = "${xsl.push.initial-delay-ms:30000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        Summary s = pushOnce();
        if (s.items() > 0 || s.errors() > 0) {
            log.info("[push] 窗口 [{}, {}) tenants={} items={} 新收={} 重复={} 拒收={} 跳过={} 失败={} 取数={}ms",
                    s.windowFrom(), s.windowTo(), s.tenants(), s.items(),
                    s.sent(), s.duplicated(), s.rejected(), s.skipped(), s.errors(), s.queryMillis());
        }
    }

    public Summary pushOnce() {
        // 只推"已经关闭的分钟"：now 那一分钟还没写完，推它会得到一个必然偏小的数
        LocalDateTime to = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime from = to.minusMinutes(lookbackMinutes);
        Map<Long, List<AttributionStat>> byTenant;
        long started = System.nanoTime();
        try {
            byTenant = aggregate(from, to);
        } catch (Exception e) {
            log.error("[push] 取数失败，本轮放弃（下一轮同一窗口会重投）: {}", e.toString());
            count("error", 1);
            return new Summary(0, 0, 0, 0, 0, 0, 1, 0, from.toString(), to.toString());
        }
        long queryMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        int sent = 0;
        int duplicated = 0;
        int rejected = 0;
        int skipped = 0;
        int errors = 0;
        int items = 0;
        for (Map.Entry<Long, List<AttributionStat>> e : byTenant.entrySet()) {
            items += e.getValue().size();
            switch (deliver(from, to, e.getKey(), e.getValue())) {
                case SENT -> sent++;
                case DUPLICATED -> duplicated++;
                case REJECTED -> rejected++;
                case SKIPPED -> skipped++;
                case ERROR -> errors++;
            }
        }
        registry.counter("xsl_push_items_total").increment(items);
        registry.timer("xsl_push_seconds").record(Duration.ofNanos(System.nanoTime() - started));
        return new Summary(byTenant.size(), items, sent, duplicated, rejected, skipped, errors,
                queryMillis, from.toString(), to.toString());
    }

    /**
     * SENT/DUPLICATED/REJECTED 都说的是<b>对端怎么回我</b>；SKIPPED 是本机决定不推、ERROR 是没送到。
     * 分开是有必要的：开发库里 seed 了 1002/1003 两个租户，它们的点击如果和 rejected 混在一起，
     * 每轮都会冒出一个"对端拒收"的假信号，而真拒收反而被淹掉。
     */
    private enum Result {SENT, DUPLICATED, REJECTED, SKIPPED, ERROR}

    private Result deliver(LocalDateTime from, LocalDateTime to, long tenant, List<AttributionStat> stats) {
        if (endpoint.isBlank() || apiKey.isBlank()) {
            log.warn("[push] 没配 xsl.push.endpoint / api-key，回传未启用（本轮 tenant={} 未投递）", tenant);
            return Result.ERROR;
        }
        if (tenant != tenantAllowList) {
            // 单机只配一把 key、一个租户；真实形态是每租户一把（DESIGN 7.3 ①）。
            // 越界的数据宁可不推，也不带着一个接收端无法对齐身份的租户号出去。
            log.info("[push] 租户 {} 不在本部署的推送身份内（只推 {}），这一批跳过 items={}",
                    tenant, tenantAllowList, stats.size());
            count("skipped", 1);
            return Result.SKIPPED;
        }
        String batchId = AttributionBatchCodec.batchId(tenant, from, to, stats);
        String checksum = AttributionBatchCodec.checksum(stats);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("batch_id", batchId);
        envelope.put("tenant_id", tenant);
        envelope.put("from", from);
        envelope.put("to", to);
        envelope.put("checksum", checksum);
        envelope.put("items", stats);
        byte[] body;
        try {
            // 签名要覆盖"发出去的那串字节"，所以 body 只生成一次：先序列化、后按同一份字节算签名
            body = mapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            log.error("[push] batch 序列化失败 tenant={}: {}", tenant, e.toString());
            count("error", 1);
            return Result.ERROR;
        }
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String path = URI.create(endpoint).getPath();
        String signature = HmacSigner.sign(apiKey,
                HmacSigner.canonical("POST", path, ts, nonce, body));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header(HmacSigner.HEADER_API_KEY, apiKey)
                    .header(HmacSigner.HEADER_TIMESTAMP, String.valueOf(ts))
                    .header(HmacSigner.HEADER_NONCE, nonce)
                    .header(HmacSigner.HEADER_SIGNATURE, signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 200) {
                boolean dup = resp.body().contains("\"duplicated\":true")
                        || resp.body().contains("\"duplicated\": true");
                count(dup ? "duplicated" : "sent", 1);
                log.debug("[push] 投递成功 batch_id={} duplicated={}", batchId, dup);
                return dup ? Result.DUPLICATED : Result.SENT;
            }
            if (status == 400 || status == 401 || status == 403) {
                // 对端明确说这批有问题（校验和、签名、租户身份）——重试同一批内容不会有不同结果，
                // 所以照计 rejected，让它在指标上露头而不是被"下一轮再说"淹掉。
                count("rejected", 1);
                log.warn("[push] 对端拒收 batch_id={} status={} body={}", batchId, status, brief(resp.body()));
                return Result.REJECTED;
            }
            count("error", 1);
            log.warn("[push] 投递失败 batch_id={} status={} body={}", batchId, status, brief(resp.body()));
            return Result.ERROR;
        } catch (Exception e) {
            count("error", 1);
            log.warn("[push] 投递异常 batch_id={}: {}", batchId, e.toString());
            return Result.ERROR;
        }
    }

    /** 窗口内按 (tenant, campaign, channel, minute) 聚合；空活动的点击不进回传（宣发平台按活动建漏斗）。 */
    private Map<Long, List<AttributionStat>> aggregate(LocalDateTime from, LocalDateTime to) throws Exception {
        String sql = "SELECT tenant_id, campaign_id, channel_id, toStartOfMinute(click_time) AS minute,"
                + " count() AS pv, uniqExact(ip_hash) AS uv,"
                + " countIf(is_bot = 0 AND risk_score < 25) AS valid_pv, countIf(is_bot = 1) AS bot_pv"
                + " FROM " + ch.database() + ".click_event FINAL"
                + " WHERE click_time >= '" + CH_TS.format(from) + "' AND click_time < '" + CH_TS.format(to) + "'"
                + " AND campaign_id != ''"
                + " GROUP BY tenant_id, campaign_id, channel_id, minute"
                + " ORDER BY tenant_id, minute, campaign_id, channel_id";
        Map<Long, List<AttributionStat>> byTenant = new TreeMap<>();
        for (Map<String, Object> row : ch.rows(sql)) {
            AttributionStat stat = new AttributionStat(
                    String.valueOf(row.getOrDefault("campaign_id", "")),
                    String.valueOf(row.getOrDefault("channel_id", "")),
                    parseMinute(String.valueOf(row.get("minute"))),
                    ClickHouseWriter.longOf(row.get("pv")),
                    ClickHouseWriter.longOf(row.get("uv")),
                    ClickHouseWriter.longOf(row.get("valid_pv")),
                    ClickHouseWriter.longOf(row.get("bot_pv")));
            byTenant.computeIfAbsent(ClickHouseWriter.longOf(row.get("tenant_id")), k -> new ArrayList<>()).add(stat);
        }
        return byTenant;
    }

    /** ClickHouse 的 DateTime 串是 "yyyy-MM-dd HH:mm:ss"，两侧都要同一个形状才能算出同一个 hash。 */
    private static LocalDateTime parseMinute(String raw) {
        return LocalDateTime.parse(raw.trim().replace(' ', 'T'));
    }

    private void count(String result, int n) {
        registry.counter("xsl_push_total", "result", result).increment(n);
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
