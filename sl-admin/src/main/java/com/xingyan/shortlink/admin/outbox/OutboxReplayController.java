package com.xingyan.shortlink.admin.outbox;

import com.xingyan.shortlink.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * outbox 手动补偿（DESIGN 7.2 的 {@code POST /internal/outbox/replay}，M3-02）。
 *
 * <p>刻意<b>不放在 {@code /api/} 前缀下</b>：网关只路由 {@code /api/**}、nginx 只路由
 * {@code /api/**} 与数据面两条，所以这个接口既不经租户 HMAC 也不从对外域名可达——
 * "内部：运维触发"由此是拓扑事实，不是命名约定。它跑的是与定时任务同一份逻辑，
 * 而 version 条件写保证多跑不错、跑不坏，因此不再为它单独造一套凭据。
 */
@RestController
public class OutboxReplayController {

    private final OutboxSyncService syncService;

    public OutboxReplayController(OutboxSyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * {@code ?short_code=xxx} 只补那一条链接（大批积压时不必等整批扫到）；不带参数就是整轮补偿，
     * 与定时任务做的是同一件事。
     */
    @PostMapping("/internal/outbox/replay")
    public Map<String, Object> replay(@RequestParam(name = "short_code", required = false) String shortCode) {
        OutboxSyncService.Summary s = syncService.syncOnce(shortCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scanned", s.scanned());
        data.put("applied", s.applied());
        data.put("inserted", s.inserted());
        data.put("superseded", s.superseded());
        data.put("failed", s.failed());
        data.put("backlog", s.backlog());
        data.put("oldest_age_seconds", s.oldestAgeSeconds());
        return ApiResponse.ok(data);
    }
}
