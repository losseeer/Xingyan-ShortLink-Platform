package com.xingyan.shortlink.consumer.clickhouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xingyan.shortlink.common.event.ClickEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ClickHouse HTTP 接口写入（JSONEachRow，零额外驱动依赖，DESIGN 4.2 click_event）。
 * 幂等：ReplacingMergeTree ORDER BY (short_code, click_time, event_id) 消化 Kafka at-least-once 重复。
 *
 * <p>M2-21 起写入请求带批量语义：一批一行 JSONEachRow 仍是一次 POST，但服务端走
 * {@code async_insert} 把多个小插入合并成一个 part，避免"每批一个 part"造成的 part 风暴
 * （M2-00 归因里 ClickHouse 在负载中吃掉 ~1.3–1.9 核，主要就在 part 合并上）。
 *
 * <p><b>{@code wait_for_async_insert} 必须是 1</b>：consumer 是"写入成功才提交 offset"，
 * 若让 POST 先返回、服务端后台再落盘，进程或容器一重启这段数据就既不在 CH 也回不来
 * （offset 已提交），DESIGN 1.3 那条 0.0000% 丢失率就名存实亡。所以这里换的是
 * <em>part 数量与合并开销</em>，不换持久性语义。
 */
@Component
public class ClickHouseWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseWriter.class);
    private static final DateTimeFormatter CH_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final String insertUrl;
    private final String auth;
    private final String database;
    private final Duration postTimeout;
    private final Counter postsOk;
    private final Counter postsFailed;
    private final Counter rowsWritten;
    private final io.micrometer.core.instrument.Timer postTimer;

    /** 测试/运维用的 4 参构造（不注册指标）。 */
    public ClickHouseWriter(String url, String user, String password, String database) {
        this(url, user, password, database, 10, "", new SimpleMeterRegistry());
    }

    @Autowired
    public ClickHouseWriter(@Value("${xsl.ch.url:http://localhost:8123/}") String url,
                            @Value("${xsl.ch.user:xsl_app}") String user,
                            @Value("${xsl.ch.password}") String password,
                            @Value("${xsl.ch.database:xsl}") String database,
                            @Value("${xsl.ch.post-timeout-seconds:10}") int postTimeoutSeconds,
                            @Value("${xsl.ch.insert-settings:async_insert=1&wait_for_async_insert=1&async_insert_busy_timeout_ms=200}")
                            String insertSettings,
                            MeterRegistry registry) {
        this.url = url;
        this.insertUrl = insertSettings.isBlank() || url.contains("?")
                ? url
                : url + (url.endsWith("?") ? "" : "?") + insertSettings;
        this.auth = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.database = database;
        this.postTimeout = Duration.ofSeconds(Math.max(postTimeoutSeconds, 1));
        this.postsOk = registry.counter("xsl_consumer_ch_posts_total", "result", "ok");
        this.postsFailed = registry.counter("xsl_consumer_ch_posts_total", "result", "fail");
        this.rowsWritten = registry.counter("xsl_consumer_ch_rows_total");
        this.postTimer = registry.timer("xsl_consumer_ch_post_seconds");
    }

    /** @return 本次 POST 携带的行数（用于"每 5,000 行发了几次 POST"这类归因） */
    public int writeBatch(List<ClickEvent> events) throws IOException, InterruptedException {
        if (events.isEmpty()) return 0;
        StringBuilder body = new StringBuilder("INSERT INTO ").append(database)
                .append(".click_event FORMAT JSONEachRow ");
        for (ClickEvent e : events) {
            body.append(mapper.writeValueAsString(toRow(e))).append('\n');
        }
        long started = System.nanoTime();
        try {
            post(insertUrl, body.toString());
        } finally {
            postTimer.record(Duration.ofNanos(System.nanoTime() - started));
        }
        postsOk.increment();
        rowsWritten.increment(events.size());
        log.debug("[ch] 批量写入 {} 条（一次 POST）", events.size());
        return events.size();
    }

    /** 管理语句（ALTER DELETE 清理等，测试与运维用）。 */
    public void execute(String sql) throws IOException, InterruptedException {
        post(url, sql);
    }

    public long countByCode(String shortCode) throws IOException, InterruptedException {
        String sql = "SELECT count() FROM " + database + ".click_event FINAL WHERE short_code = '"
                + shortCode.replace("'", "") + "'";
        return Long.parseLong(scalar(sql));
    }

    /** 单值读（自证与运维用，和 countByCode 同一条认证/超时路径，避免测试里再拼一份 HTTP 客户端）。 */
    public String scalar(String sql) throws IOException, InterruptedException {
        return post(url, sql).trim();
    }

    private String post(String endpoint, String bodyStr) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(postTimeout)
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | RuntimeException e) {
            postsFailed.increment();
            throw e;
        }
        if (resp.statusCode() != 200) {
            postsFailed.increment();
            throw new IOException("ClickHouse 查询失败 status=" + resp.statusCode() + " body=" + resp.body());
        }
        return resp.body();
    }

    private ObjectNode toRow(ClickEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("event_id", nz(e.getEventId()));
        n.put("short_code", nz(e.getShortCode()));
        n.put("click_id", nz(e.getClickId()));
        n.put("click_time", CH_DT.format(Instant.ofEpochSecond(e.getClickTime())));
        n.put("ip_hash", nz(e.getIpHash()));
        n.put("user_agent", nz(e.getUserAgent()));
        n.put("referer", nz(e.getReferer()));
        n.put("device_type", nz(e.getDeviceType()));
        n.put("os", nz(e.getOs()));
        n.put("province", nz(e.getProvince()));
        n.put("city", nz(e.getCity()));
        n.put("channel_id", nz(e.getChannelId()));
        n.put("campaign_id", nz(e.getCampaignId()));
        n.put("promoter_id", nz(e.getPromoterId()));
        n.put("utm_params", nz(e.getUtmParams()));
        n.put("risk_score", e.getRiskScore());
        n.put("is_bot", e.getIsBot());
        n.put("tenant_id", e.getTenantId());
        return n;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
