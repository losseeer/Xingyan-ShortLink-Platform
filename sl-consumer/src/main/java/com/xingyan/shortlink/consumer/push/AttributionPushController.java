package com.xingyan.shortlink.consumer.push;

import com.xingyan.shortlink.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手动触发一轮归因回传（DESIGN 7.2，M3-03）。
 *
 * <p>和 outbox 补偿那台一样<b>不在 {@code /api/} 前缀下</b>：网关与 nginx 都只路由 {@code /api/**}
 * 与数据面两条，所以它既不经租户 HMAC 也不从对外域名可达（DESIGN 7.2 的可达性说明同一节）。
 * 存在的理由很具体：回传默认 5 分钟一轮，验收与对账脚本不该为了拿一个数字等五分钟；
 * 顺带把 {@code queryMillis} 暴露出来——"每轮扫明细到底多贵"这个数就是从这里读的。
 */
@RestController
public class AttributionPushController {

    private final AttributionPushService pushService;

    public AttributionPushController(AttributionPushService pushService) {
        this.pushService = pushService;
    }

    @PostMapping("/internal/attribution/push")
    public Map<String, Object> push() {
        AttributionPushService.Summary s = pushService.pushOnce();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("window_from", s.windowFrom());
        data.put("window_to", s.windowTo());
        data.put("tenants", s.tenants());
        data.put("items", s.items());
        data.put("sent", s.sent());
        data.put("duplicated", s.duplicated());
        data.put("rejected", s.rejected());
        data.put("skipped", s.skipped());
        data.put("errors", s.errors());
        data.put("query_millis", s.queryMillis());
        return ApiResponse.ok(data);
    }
}
