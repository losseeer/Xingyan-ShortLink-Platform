package com.xingyan.shortlink.consumer.stats;

import com.xingyan.shortlink.common.api.ApiResponse;
import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /stats/links/{code}（M1-09 最小版）：直查 ClickHouse 明细 count FINAL。
 * 看板级聚合（分钟/UV/有效·刷量双指标）属 M2/M3。
 */
@RestController
@RequestMapping("/api/v1/stats/links")
public class StatsController {

    private final ClickHouseWriter ch;

    public StatsController(ClickHouseWriter ch) {
        this.ch = ch;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Object> linkStats(@PathVariable String code) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("short_code", code);
            data.put("click_count", ch.countByCode(code));
            data.put("source", "clickhouse:click_event FINAL");
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "stats backend unavailable"));
        }
    }
}
