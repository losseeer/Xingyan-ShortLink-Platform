package com.xingyan.shortlink.admin.route;

import com.xingyan.shortlink.common.route.RouteCacheContract;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

/**
 * 路由缓存回写 + 失效广播（DESIGN 8.4「失效缓存」那一半，M3-02）。管理面的两条发布路径——
 * {@code LinkService} 的直写与 {@code OutboxSyncService} 的补偿——都从这里出去，
 * 于是"改完配置"在两个 jump 实例上的收敛时间不再是 30s（那只是丢广播时的兜底）。
 *
 * <p>两个动作的失败处理刻意不同：缓存条件写失败要<b>抛穿</b>（直写路径会留下 status=0 的事件、
 * 同步器会重来），而广播失败<b>不抛穿</b>——数据已经在 link_route 与 L2 里了，为一条通知把已成功的
 * 写判成失败，只会让补偿链路反复重放同一件事。
 */
@Component
public class RouteCachePublisher {

    private static final Logger log = LoggerFactory.getLogger(RouteCachePublisher.class);
    private static final Duration TTL_BASE = Duration.ofHours(24);
    private static final int TTL_JITTER_MINUTES = 120;

    private static final RedisScript<Long> SET_IF_NEWER =
            new DefaultRedisScript<>(RouteCacheContract.SET_IF_NEWER_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final SecureRandom random = new SecureRandom();

    public RouteCachePublisher(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        this.registry = registry;
    }

    /**
     * 按 version 守卫写 L2（只允许把缓存变得更新），写动了才广播让各实例清 L1。
     * TTL 带随机抖动：同批创建/同一轮补偿的链接如果同一时刻过期，就会同一时刻一起打穿到 DB。
     *
     * @return 缓存是否真的变了。返回 false（守卫挡下、值本来就等于要写的）时不发广播——
     *         重放历史积压时绝大多数事件都属于这一类，为它们各发一条通知只会让两个 jump 实例白清一遍缓存。
     */
    public boolean publish(String code, String routeJson, long version) {
        Duration ttl = TTL_BASE.plusMinutes(random.nextInt(TTL_JITTER_MINUTES));
        Long written = redis.execute(SET_IF_NEWER, List.of("sl:r:" + code), routeJson, String.valueOf(version),
                String.valueOf(ttl.toSeconds()));
        if (written == null || written == 0L) {
            registry.counter("xsl_route_cache_write_total", "result", "kept").increment();
            return false;
        }
        registry.counter("xsl_route_cache_write_total", "result", "written").increment();
        evict(code);
        return true;
    }

    private void evict(String code) {
        try {
            redis.convertAndSend(RouteCacheContract.EVICT_CHANNEL, code);
            registry.counter("xsl_route_evict_total", "result", "sent").increment();
        } catch (Exception e) {
            registry.counter("xsl_route_evict_total", "result", "error").increment();
            log.warn("[route] 失效广播发送失败，各 jump 实例最坏等一次本地缓存 TTL 自愈 code={}: {}", code, e);
        }
    }
}
