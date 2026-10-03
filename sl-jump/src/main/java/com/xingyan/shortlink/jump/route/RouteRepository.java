package com.xingyan.shortlink.jump.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xingyan.shortlink.common.route.RouteConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 跳转路由三级读取（DESIGN 5.2/8.1）：Caffeine(L1) → Redis sl:r:{code}(L2) → link_route 回源(L3)。
 * 空值标记 sl:r:nx:{code} 防穿透（布隆/Cuckoo 留 M2/M3，M1 以空值标记为唯一存在性负反馈）。
 * 回源写回用 Lua version 比较防旧值覆盖新值（DESIGN 8.4）。
 */
@Component
public class RouteRepository {

    private static final Logger log = LoggerFactory.getLogger(RouteRepository.class);
    private static final Duration NULL_MARK_TTL = Duration.ofMinutes(5);
    private static final Duration REDIS_TTL_BASE = Duration.ofHours(24);

    private static final RedisScript<Long> WRITE_BACK_IF_NEWER = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[1])
            if cur then
              local ok, obj = pcall(cjson.decode, cur)
              if ok and obj and tonumber(obj.version) and tonumber(obj.version) >= tonumber(ARGV[2]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[3]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Cache<String, Optional<RouteConfig>> local;
    private final Counter hitLocal;
    private final Counter hitRedis;
    private final Counter hitDb;
    private final Counter missNull;

    public RouteRepository(StringRedisTemplate redis,
                           JdbcTemplate jdbc,
                           ObjectMapper mapper,
                           MeterRegistry registry,
                           @Value("${xsl.jump.local-cache-size:10000}") int localSize,
                           @Value("${xsl.jump.local-cache-ttl-seconds:30}") long localTtl) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.local = Caffeine.newBuilder().maximumSize(localSize)
                .expireAfterWrite(Duration.ofSeconds(localTtl)).build();
        this.hitLocal = registry.counter("xsl_jump_route_lookup_total", "level", "local");
        this.hitRedis = registry.counter("xsl_jump_route_lookup_total", "level", "redis");
        this.hitDb = registry.counter("xsl_jump_route_lookup_total", "level", "db");
        this.missNull = registry.counter("xsl_jump_route_lookup_total", "level", "miss");
        registry.gauge("xsl_jump_route_l1_size", local, Cache::estimatedSize);
    }

    public Optional<RouteConfig> find(String code) {
        Optional<RouteConfig> cached = local.getIfPresent(code);
        if (cached != null) {
            hitLocal.increment();
            return cached;
        }
        Optional<RouteConfig> loaded = load(code);
        local.put(code, loaded);
        return loaded;
    }

    private Optional<RouteConfig> load(String code) {
        // Redis 读失败要当成"未命中"而不是让请求抛穿（DESIGN 8.3：Redis 挂 → Caffeine 兜底 + 回源直读，
        // 跳转成功率不跌零）。M2-11 之前只有写回侧做了保护，读侧靠 30s 的 L1 侥幸挡着。
        String json = null;
        boolean nullMarked = false;
        try {
            json = redis.opsForValue().get("sl:r:" + code);
            if (json == null) {
                nullMarked = Boolean.TRUE.equals(redis.hasKey("sl:r:nx:" + code));
            }
        } catch (Exception e) {
            log.warn("[jump] Redis 不可读，本次直连 DB 回源 code={}: {}", code, e.toString());
        }
        if (json != null) {
            hitRedis.increment();
            return parse(json);
        }
        if (nullMarked) {
            hitRedis.increment();
            return Optional.empty();
        }
        List<String> rows = jdbc.queryForList(
                "SELECT route_json FROM link_route WHERE short_code = ?", String.class, code);
        if (rows.isEmpty()) {
            missNull.increment();
            try {
                redis.opsForValue().setIfAbsent("sl:r:nx:" + code, "1", NULL_MARK_TTL);
            } catch (Exception e) {
                log.warn("[jump] 空值标记写失败（本次仍按 404 处理） code={}", code);
            }
            return Optional.empty();
        }
        hitDb.increment();
        String routeJson = rows.get(0);
        writeBackIfNewer(code, routeJson);
        return parse(routeJson);
    }

    private void writeBackIfNewer(String code, String routeJson) {
        try {
            long version = mapper.readTree(routeJson).path("version").asLong(1);
            redis.execute(WRITE_BACK_IF_NEWER, List.of("sl:r:" + code),
                    routeJson, String.valueOf(version),
                    String.valueOf(REDIS_TTL_BASE.toSeconds()));
        } catch (Exception e) {
            log.warn("[jump] 路由写回缓存失败（不影响本次跳转） code={}", code, e);
        }
    }

    private Optional<RouteConfig> parse(String json) {
        try {
            return Optional.of(mapper.readValue(json, RouteConfig.class));
        } catch (Exception e) {
            log.error("[jump] route_json 解析失败: {}", json, e);
            return Optional.empty();
        }
    }
}
