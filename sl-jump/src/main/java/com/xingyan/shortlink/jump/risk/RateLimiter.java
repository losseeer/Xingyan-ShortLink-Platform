package com.xingyan.shortlink.jump.risk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 窗口频控（DESIGN 5.3 第 2 层）：{@code (short_code, ip_hash)} 维度、60s 固定槽，
 * 单脚本 {@code INCR + 首次 EXPIRE} 原子完成（把 inc/expire 的竞态关进一个脚本里）。
 *
 * <p>刻意<b>不做</b>严格滑动窗口，也刻意<b>不用多键脚本</b>：两槽加权要么要求两个键在
 * Redis Cluster 下同槽（与 8.1-B「键设计无跨槽操作约束」的扩展承诺冲突），要么把上一槽计数
 * 缓存在进程内——jump 是 ×2 实例，轮询会把计数劈成两半而系统性低估。代价写在 DESIGN 5.3：
 * 窗口边界最坏放行约 2× 阈值。
 *
 * <p>Redis 不可用时退化为进程内窗口计数（DESIGN 8.3「频控退化为进程内计数」）：宁可放宽口径，
 * 也不能让频控自己把跳转链路打死。降级样本用 {@code mode="local"} 标签单独可查。
 */
@Component
public class RateLimiter {

    /**
     * 脚本正文在 {@code resources/lua/rate_limit_window.lua}——那份文件同时被
     * {@code bench/run-ratelimit-bench.sh} 拿去跑基线（原样、含注释，所以两边 sha 一致），
     * 被测的就是线上跑的这份。
     */
    private static final RedisScript<Long> WINDOW_COUNT = windowCountScript();

    private static RedisScript<Long> windowCountScript() {
        // 显式按 UTF-8 读：脚本注释里有中文，编码读错会让送进 Redis 的正文与仓库里的不是一份东西
        // （Spring Data Redis 3.3 起没有 ResourceScriptSource / setScriptCharset 可用）。
        try (var in = new ClassPathResource("lua/rate_limit_window.lua").getInputStream()) {
            return new DefaultRedisScript<>(new String(in.readAllBytes(), StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new IllegalStateException("频控 Lua 脚本 lua/rate_limit_window.lua 加载失败", e);
        }
    }

    /**
     * @param counted   本窗口内已累计的请求数（含本次）
     * @param degraded  true 表示这一条走的是进程内兜底，不是 Redis 权威值
     */
    public record Decision(boolean allowed, long counted, int limit, int retryAfterSeconds, boolean degraded) {
    }

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final boolean enabled;
    private final int defaultPermits;
    private final int windowSeconds;
    private final Cache<String, AtomicLong> localWindows;

    public RateLimiter(StringRedisTemplate redis,
                       MeterRegistry registry,
                       @Value("${xsl.jump.rate-limit.enabled:true}") boolean enabled,
                       @Value("${xsl.jump.rate-limit.permits-per-window:60}") int defaultPermits,
                       @Value("${xsl.jump.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redis = redis;
        this.registry = registry;
        this.enabled = enabled;
        this.defaultPermits = defaultPermits;
        this.windowSeconds = windowSeconds <= 0 ? 60 : windowSeconds;
        this.localWindows = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterWrite(Duration.ofSeconds(this.windowSeconds * 2L))
                .build();
    }

    /**
     * @param perLinkOverride {@code route_json.rate_limit_per_minute} 的链接级阈值；null/非正数则用全局默认
     */
    public Decision check(String code, String ipHash, Integer perLinkOverride) {
        if (!enabled) {
            return new Decision(true, 0, Integer.MAX_VALUE, 0, false);
        }
        int limit = perLinkOverride == null || perLinkOverride <= 0 ? defaultPermits : perLinkOverride;
        long nowSeconds = System.currentTimeMillis() / 1000;
        long slot = nowSeconds / windowSeconds;
        int retryAfter = (int) ((slot + 1) * windowSeconds - nowSeconds);
        String key = "sl:rl:" + code + ":" + ipHash + ":" + slot;

        boolean degraded = false;
        long counted;
        try {
            Long n = redis.execute(WINDOW_COUNT, List.of(key), String.valueOf(windowSeconds));
            counted = n == null ? 1L : n;
        } catch (Exception e) {
            degraded = true;
            counted = localWindows.get(key, k -> new AtomicLong()).incrementAndGet();
        }

        boolean allowed = counted <= limit;
        registry.counter("xsl_jump_rate_limit_total",
                "result", allowed ? "allow" : "block",
                "mode", degraded ? "local" : "redis").increment();
        return new Decision(allowed, counted, limit, retryAfter, degraded);
    }
}
