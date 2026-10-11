package com.xingyan.shortlink.consumer.stats;

import com.xingyan.shortlink.common.api.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 看板只读统计（DESIGN 7.1 接口表 + 7.2「可达性与边界」，M3-05）。四条都是 GET、都只读 ClickHouse。
 *
 * <p>这条前缀<b>不经网关 HMAC</b>（nginx 直接反代到 consumer，是 v2.2 起就存在的路由），
 * 所以浏览器里不需要、也不能放密钥。代价写在 DESIGN 9.1 的风险表里：聚合数字对任何能访问入口的人开放。
 *
 * <p>{@code /links/{code}} 是 M1-09 就有的老接口，路径与 `click_count` 字段都保留着——
 * {@code make demo} 与 {@code accept-m1-09.sh} 在读它，改形状就是契约破坏（所以本轮必重跑那两条）。
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final StatsService stats;
    private final MeterRegistry registry;
    private final int defaultLimit;
    private final int maxLimit;

    public StatsController(StatsService stats, MeterRegistry registry,
                           @Value("${xsl.stats.list-limit:20}") int defaultLimit,
                           @Value("${xsl.stats.list-limit-max:200}") int maxLimit) {
        this.stats = stats;
        this.registry = registry;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    /** 单链接：汇总 + 时间序列（窗口左闭右开，粒度不给就自动）。 */
    @GetMapping("/links/{code}")
    public Map<String, Object> link(@PathVariable String code,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String granularity) {
        StatsWindow w = stats.resolveWindow(from, to, granularity);
        return timed("links", w, () -> ApiResponse.ok(stats.linkStats(code, w)));
    }

    /** 链接列表页：某租户在窗口内**有点击**的短码及其四档计数。 */
    @GetMapping("/tenants/{tenantId}/links")
    public Map<String, Object> tenantLinks(@PathVariable String tenantId,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) String granularity,
                                           @RequestParam(required = false, defaultValue = "-1") int limit,
                                           @RequestParam(required = false, defaultValue = "0") int offset) {
        long tenant = StatsService.requireTenant(tenantId);
        int size = limit < 0 ? defaultLimit : StatsService.clamp(limit, 1, maxLimit, "limit");
        int skip = StatsService.clamp(offset, 0, 100_000, "offset");
        StatsWindow w = stats.resolveWindow(from, to, granularity);
        return timed("tenant_links", w, () -> ApiResponse.ok(stats.tenantLinks(tenant, w, size, skip)));
    }

    /** 双指标页的时间序列：租户维度的走势（与渠道分布是两种 GROUP BY 轴，不共用一条接口）。 */
    @GetMapping("/tenants/{tenantId}/timeseries")
    public Map<String, Object> tenantTimeseries(@PathVariable String tenantId,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) String granularity) {
        long tenant = StatsService.requireTenant(tenantId);
        StatsWindow w = stats.resolveWindow(from, to, granularity);
        return timed("tenant_timeseries", w, () -> ApiResponse.ok(stats.tenantTimeseries(tenant, w)));
    }

    /** 渠道漏斗页：渠道 → 点击 →（有效／被频控拦下）+ 容器维度。 */
    @GetMapping("/tenants/{tenantId}/channels")
    public Map<String, Object> tenantChannels(@PathVariable String tenantId,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String granularity) {
        long tenant = StatsService.requireTenant(tenantId);
        StatsWindow w = stats.resolveWindow(from, to, granularity);
        return timed("tenant_channels", w, () -> ApiResponse.ok(stats.tenantChannels(tenant, w)));
    }

    /** 活动趋势：DESIGN 7.1 那行"活动漏斗"的兑现（转化那一档本期是 available=false）。 */
    @GetMapping("/campaigns/{campaignId}")
    public Map<String, Object> campaign(@PathVariable String campaignId,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String granularity) {
        StatsWindow w = stats.resolveWindow(from, to, granularity);
        return timed("campaign", w, () -> ApiResponse.ok(stats.campaignStats(campaignId, w)));
    }

    /**
     * 每次查询计一次时（`xsl_stats_query_total{endpoint,result}` + `xsl_stats_seconds{endpoint,window}`）。
     *
     * <p>失败也要计数，否则"CH 挂了"在指标上长得和"没人访问"一模一样——8.5 那条规矩（写下的指标
     * 必须被读到过一次、而且要能区分成败）就是为这个。histogram 桶开着才有 P95 可读，
     * "24h/7d 到底多贵"这个实测数字就从这里取。
     */
    private Map<String, Object> timed(String endpoint, StatsWindow w, Supplier<Map<String, Object>> call) {
        long started = System.nanoTime();
        String result = "backend_error";
        try {
            Map<String, Object> body = call.get();
            result = "ok";
            return body;
        } catch (StatsRequestException e) {
            result = "bad_request";
            throw e;
        } catch (StatsBackendException e) {
            throw e;
        } finally {
            registry.counter("xsl_stats_query_total", "endpoint", endpoint, "result", result).increment();
            Timer.builder("xsl_stats_seconds")
                    .tag("endpoint", endpoint)
                    .tag("window", w.granularity().key())
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }
}
