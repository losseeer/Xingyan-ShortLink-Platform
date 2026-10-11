package com.xingyan.shortlink.mock.order;

import com.xingyan.shortlink.common.api.ApiResponse;
import com.xingyan.shortlink.mock.MockRejectException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假订单入口（DESIGN 附录 B，M3-04）。
 *
 * <p>这一跳<b>不鉴权</b>，是写下来的决定而不是漏掉：它扮演"购票页后端 → 平台"，两端都在 mock 侧，
 * 只往 {@code xsl_mock.mock_order} 里落归因字段，碰不到业务库；我们系统真正的对外契约只有 7.3 的
 * "归因回传"那一行（那边是 HMAC + 校验和 + 幂等，一样不少）。现在给它套一层假签名，
 * 只会多出一个没人验证、还让人误以为已解决的东西。
 */
@RestController
@RequestMapping("/mock/orders")
public class MockOrderController {

    private final MockOrderService service;
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public MockOrderController(MockOrderService service, JdbcTemplate jdbc, MeterRegistry registry) {
        this.service = service;
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody MockOrder order) {
        MockOrderService.Outcome o = service.ingest(order);
        count(o.verdict() == MockOrderService.Verdict.CREATED ? "created" : "duplicated");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_id", o.orderId());
        data.put("result", o.verdict().name().toLowerCase());
        data.put("duplicated", o.verdict() == MockOrderService.Verdict.DUPLICATED);
        data.put("times_submitted", o.timesSubmitted());
        return ApiResponse.ok(data);
    }

    /** 给端到端对账取数用（按订单号/活动/时间过滤，只读不鉴权的理由同上：这里面只有 mock 自己造的假订单）。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(name = "campaign_id", required = false) String campaignId,
                                    @RequestParam(name = "click_id", required = false) String clickId,
                                    @RequestParam(name = "from", required = false) String from,
                                    @RequestParam(name = "to", required = false) String to) {
        StringBuilder sql = new StringBuilder("SELECT order_id, click_id, channel_id, campaign_id, promoter_id, "
                + "first_click_time, last_click_time, times_submitted, create_time FROM mock_order WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (notBlank(campaignId)) {
            sql.append(" AND campaign_id = ?");
            args.add(campaignId.trim());
        }
        if (notBlank(clickId)) {
            sql.append(" AND click_id = ?");
            args.add(clickId.trim());
        }
        if (notBlank(from)) {
            sql.append(" AND create_time >= ?");
            args.add(from.trim().replace('T', ' '));
        }
        if (notBlank(to)) {
            sql.append(" AND create_time < ?");
            args.add(to.trim().replace('T', ' '));
        }
        sql.append(" ORDER BY create_time, order_id LIMIT 5000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", rows.size());
        data.put("orders", rows);
        return ApiResponse.ok(data);
    }

    @ExceptionHandler(MockRejectException.class)
    public ResponseEntity<Map<String, Object>> onReject(MockRejectException e) {
        count("rejected_validation");
        return ResponseEntity.status(e.status())
                .body(ApiResponse.error(e.code(), e.code().getMessage() + ": " + e.getMessage()));
    }

    private void count(String result) {
        registry.counter("xsl_mock_order_total", "result", result).increment();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
