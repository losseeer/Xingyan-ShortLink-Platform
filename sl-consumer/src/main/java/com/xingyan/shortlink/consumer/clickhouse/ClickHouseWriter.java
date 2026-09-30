package com.xingyan.shortlink.consumer.clickhouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xingyan.shortlink.common.event.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class ClickHouseWriter {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseWriter.class);
    private static final DateTimeFormatter CH_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private final String auth;
    private final String database;
    private final String insertPrefix;

    public ClickHouseWriter(@Value("${xsl.ch.url:http://localhost:8123/}") String url,
                            @Value("${xsl.ch.user:xsl_app}") String user,
                            @Value("${xsl.ch.password:xsl-dev}") String password,
                            @Value("${xsl.ch.database:xsl}") String database) {
        this.url = url;
        this.auth = "Basic " + java.util.Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.database = database;
        this.insertPrefix = "INSERT INTO " + database + ".click_event FORMAT JSONEachRow ";
    }

    public void writeBatch(List<ClickEvent> events) throws IOException, InterruptedException {
        if (events.isEmpty()) return;
        StringBuilder body = new StringBuilder(insertPrefix);
        for (ClickEvent e : events) {
            body.append(mapper.writeValueAsString(toRow(e))).append('\n');
        }
        post(body.toString());
        log.debug("[ch] 批量写入 {} 条", events.size());
    }

    /** 管理语句（ALTER DELETE 清理等，测试与运维用）。 */
    public void execute(String sql) throws IOException, InterruptedException {
        post(sql);
    }

    public long countByCode(String shortCode) throws IOException, InterruptedException {
        String sql = "SELECT count() FROM " + database + ".click_event FINAL WHERE short_code = '"
                + shortCode.replace("'", "") + "'";
        return Long.parseLong(post(sql).trim());
    }

    private String post(String bodyStr) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("ClickHouse 查询失败 status=" + resp.statusCode() + " body=" + resp.body());
        }
        return resp.body();
    }

    private ObjectNode toRow(ClickEvent e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("event_id", nz(e.getEventId()));
        n.put("short_code", nz(e.getShortCode()));
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
