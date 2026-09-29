package com.xingyan.shortlink.jump.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.jump.AccessCounter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M1-07 验收（活体 compose Redis/MySQL）：冷码回源→写回缓存、空值标记防穿透、access_limit Lua 扣减。
 * 基础设施不可达整体跳过。
 */
class RouteRepositoryLiveTest {

    private static final String ORIGIN = "https://mock.ticketsales.test/live";
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static final List<String> usedCodes = new ArrayList<>();

    @BeforeAll
    static void up() {
        assumeTrue(infraUp());
        redisFactory = new LettuceConnectionFactory("127.0.0.1", 6380);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        cfg.setJdbcUrl("jdbc:shardingsphere:classpath:sharding-jump.yaml?placeholder-type=environment");
        cfg.setMaximumPoolSize(4);
        ds = new HikariDataSource(cfg);
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void down() {
        if (redis != null && ds != null) {
            for (String code : usedCodes) {
                redis.delete(List.of("sl:r:" + code, "sl:r:nx:" + code, "sl:cnt:" + code));
                jdbc.update("DELETE FROM link_route WHERE short_code = ?", code);
            }
        }
        if (redisFactory != null) redisFactory.destroy();
        if (ds != null) ds.close();
    }

    private static RouteRepository repo(SimpleMeterRegistry registry) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new RouteRepository(redis, jdbc, mapper, registry, 1000, 30);
    }

    private static String insertRoute(String code, int version) {
        usedCodes.add(code);
        redis.delete(List.of("sl:r:" + code, "sl:r:nx:" + code, "sl:cnt:" + code));
        String json = "{\"origin_url\":\"" + ORIGIN + "\",\"tenant_id\":1001,\"redirect_type\":1,"
                + "\"expire_time\":null,\"access_limit\":null,\"status\":0,\"version\":" + version + "}";
        jdbc.update("INSERT INTO link_route (short_code, route_json, version) VALUES (?, CAST(? AS JSON), ?)",
                code, json, version);
        return json;
    }

    @Test
    void coldCodeLoadsFromDbAndWritesBackRedis() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouteRepository repository = repo(registry);
        String code = "m7cold" + System.nanoTime() % 100000;
        insertRoute(code, 1);

        Optional<RouteConfig> first = repository.find(code);
        assertTrue(first.isPresent());
        assertEquals(ORIGIN, first.get().originUrl());
        assertEquals(1, first.get().version());
        assertEquals(1.0, registry.get("xsl_jump_route_lookup_total").tag("level", "db").counter().count());
        assertNotNull(redis.opsForValue().get("sl:r:" + code), "回源后必须写回 Redis");

        repository.find(code);
        assertEquals(1.0, registry.get("xsl_jump_route_lookup_total").tag("level", "db").counter().count(),
                "二查不得再打 DB（L1/L2 命中）");
        assertTrue(registry.get("xsl_jump_route_lookup_total").tag("level", "local").counter().count() >= 1.0);
    }

    @Test
    void unknownCodeSetsNullMarker() {
        RouteRepository repository = repo(new SimpleMeterRegistry());
        String code = "m7nul" + System.nanoTime() % 100000;
        usedCodes.add(code);
        assertTrue(repository.find(code).isEmpty());
        assertEquals("1", redis.opsForValue().get("sl:r:nx:" + code));
        Long ttl = redis.getExpire("sl:r:nx:" + code);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300, "空值标记 TTL ≤5min，实际 " + ttl + "s");

        // 标记存续期间二查：仍空且不报错（DB 已被跳过由 marker 保证）
        assertTrue(repository.find(code).isEmpty());
    }

    @Test
    void accessLimitLuaDecrementExhausts() {
        AccessCounter counter = new AccessCounter(redis);
        String code = "m7lim" + System.nanoTime() % 100000;
        usedCodes.add(code);
        redis.delete("sl:cnt:" + code);
        assertTrue(counter.tryConsume(code, 2));
        assertTrue(counter.tryConsume(code, 2));
        assertFalse(counter.tryConsume(code, 2), "第 3 次必须超限");
        assertEquals("0", redis.opsForValue().get("sl:cnt:" + code));
    }

    private static boolean infraUp() {
        return portOpen("127.0.0.1", 6380) && portOpen("127.0.0.1", 3307);
    }

    private static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
