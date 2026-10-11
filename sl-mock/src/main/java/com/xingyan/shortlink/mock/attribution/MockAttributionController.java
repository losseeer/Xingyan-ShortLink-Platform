package com.xingyan.shortlink.mock.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.common.api.ApiResponse;
import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.common.security.HmacSigner;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mock 宣发平台的入口（DESIGN 7.3，M3-03）。
 *
 * <p>写侧 {@code POST /batches} 要过 HMAC 验签 + 时间戳窗口；<b>刻意不查 nonce</b>：同一批内容重投
 * 会被 {@code batch_id} 幂等吸收，再养一张 nonce 表只是多一份要清理的状态（口径见 7.3 ④）。
 * 读侧两个 GET 是给 M3-04 的对账脚本取数用的，不鉴权——这个服务只绑本机与 compose 内网、
 * 也不持有任何明细数据，读到的是聚合值。
 */
@RestController
@RequestMapping("/mock/attribution")
public class MockAttributionController {

    private static final Logger log = LoggerFactory.getLogger(MockAttributionController.class);

    private final MockAttributionService service;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final String apiKey;

    public MockAttributionController(MockAttributionService service,
                                     ObjectMapper mapper,
                                     JdbcTemplate jdbc,
                                     MeterRegistry registry,
                                     @Value("${xsl.mock.api-key:xy-key-push-mock-001}") String apiKey) {
        this.service = service;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.registry = registry;
        this.apiKey = apiKey;
    }

    @PostMapping("/batches")
    public ResponseEntity<Map<String, Object>> receive(
            HttpServletRequest request,
            @RequestBody byte[] raw,
            @RequestHeader(value = HmacSigner.HEADER_API_KEY, required = false) String key,
            @RequestHeader(value = HmacSigner.HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = HmacSigner.HEADER_NONCE, required = false) String nonce,
            @RequestHeader(value = HmacSigner.HEADER_SIGNATURE, required = false) String signature) {
        // 签名覆盖 method + path + ts + nonce + sha256(body)：body 用收到的原始字节算，
        // 解析再序列化一次会得到不同的字节串（键序、空格都可能变），签名就会永久对不上。
        MockSignature.Result verified = MockSignature.verify(apiKey, key, timestamp, nonce, signature,
                request.getMethod(), request.getRequestURI(), raw, System.currentTimeMillis());
        if (!verified.ok()) {
            return rejectSignature(verified.reason());
        }

        AttributionBatch batch;
        try {
            batch = mapper.readValue(raw, AttributionBatch.class);
        } catch (Exception e) {
            count("rejected_json");
            log.warn("[mock] 回传体解析失败: {}", e.toString());
            return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求体不是合法的 batch JSON");
        }
        if (batch.tenantId() != service.tenantId()) {
            count("rejected_tenant");
            return body(HttpStatus.FORBIDDEN, ErrorCode.TENANT_MISMATCH,
                    "这把 key 属于租户 " + service.tenantId() + "，批次却声明租户 " + batch.tenantId());
        }

        MockAttributionService.Outcome o = service.ingest(batch);
        count(o.verdict() == MockAttributionService.Verdict.ACCEPTED ? "accepted" : "duplicated");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batch_id", o.batchId());
        data.put("result", o.verdict().name().toLowerCase());
        data.put("duplicated", o.verdict() == MockAttributionService.Verdict.DUPLICATED);
        data.put("item_count", o.itemCount());
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(data));
    }

    /** 台账（一个批次一行）：对账时要能回答"你到底收到了哪几批"。 */
    @GetMapping("/batches")
    public Map<String, Object> batches(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT batch_id, tenant_id, window_from, window_to, item_count, checksum, times_received, "
                        + "first_received_at, last_received_at FROM attribution_batch "
                        + "ORDER BY last_received_at DESC LIMIT ?", Math.max(1, Math.min(limit, 500)));
        return ApiResponse.ok(rows);
    }

    /** 按桶的归因统计（发送端推来的最终值，已按桶覆盖）。 */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(name = "from", required = false) String from,
                                     @RequestParam(name = "to", required = false) String to,
                                     @RequestParam(name = "campaign_id", required = false) String campaignId,
                                     @RequestParam(name = "channel_id", required = false) String channelId) {
        StringBuilder sql = new StringBuilder("SELECT tenant_id, campaign_id, channel_id, minute, pv, uv, valid_pv, bot_pv, batch_id "
                + "FROM attribution_stat WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (notBlank(from)) {
            sql.append(" AND minute >= ?");
            args.add(LocalDateTime.parse(from.trim()));
        }
        if (notBlank(to)) {
            sql.append(" AND minute < ?");
            args.add(LocalDateTime.parse(to.trim()));
        }
        if (notBlank(campaignId)) {
            sql.append(" AND campaign_id = ?");
            args.add(campaignId.trim());
        }
        if (notBlank(channelId)) {
            sql.append(" AND channel_id = ?");
            args.add(channelId.trim());
        }
        sql.append(" ORDER BY minute, campaign_id, channel_id LIMIT 5000");
        return ApiResponse.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 校验和 / batch_id / 窗口不合法都从这里出去：400 且台账里不会留下这一批。 */
    @ExceptionHandler(MockRejectException.class)
    public ResponseEntity<Map<String, Object>> onReject(MockRejectException e) {
        String reason = e.getMessage() == null ? "" : e.getMessage();
        count(reason.startsWith("checksum") ? "rejected_checksum"
                : reason.startsWith("batch_id") ? "rejected_batch_id" : "rejected_window");
        log.warn("[mock] 拒收回传: {}", reason);
        return body(e.status(), e.code(), reason);
    }

    private ResponseEntity<Map<String, Object>> rejectSignature(String reason) {
        count("rejected_signature");
        log.warn("[mock] 验签失败: {}", reason);
        return body(HttpStatus.UNAUTHORIZED, ErrorCode.SIGN_INVALID, reason);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, ErrorCode code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(code, code.getMessage() + ": " + message));
    }

    private void count(String result) {
        registry.counter("xsl_mock_batch_total", "result", result).increment();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
