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
        cfg.setJdbcUrl("jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=environment");
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
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AccessCounter counter = new AccessCounter(redis, jdbc, registry, 0);
        String code = "m7lim" + System.nanoTime() % 100000;
        usedCodes.add(code);
        redis.delete("sl:cnt:" + code);
        assertTrue(counter.tryConsume(code, 2));
        assertTrue(counter.tryConsume(code, 2));
        assertFalse(counter.tryConsume(code, 2), "第 3 次必须超限");
        assertEquals("0", redis.opsForValue().get("sl:cnt:" + code));
        // 键不在时脚本先回 -2（不猜种子），调用方读快照后二次进入 —— M2-12 的冷重建协议
        assertEquals(1.0, registry.get("xsl_jump_access_counter_total").tag("result", "rebuild")
                .counter().count(), "首击应触发一次冷重建");
    }

    /** DESIGN 8.4 的方向盘：Redis 键丢了要按快照收口（宁可少放），不能回到满额（超放）。 */
    @Test
    void accessLimitRebuildsFromSnapshotInsteadOfFullQuota() {
        String code = "m7rb" + System.nanoTime() % 100000;
        insertRoute(code, 1);
        jdbc.update("UPDATE link_route SET access_used = 9, access_used_at = NOW() WHERE short_code = ?", code);
        redis.delete("sl:cnt:" + code);

        AccessCounter counter = new AccessCounter(redis, jdbc, new SimpleMeterRegistry(), 1);
        // 上限 10、快照已用 9 → 只剩 1 次；缓冲再被"剩余额度的 1/4"封顶 → 实际扣 0
        assertTrue(counter.tryConsume(code, 10));
        assertFalse(counter.tryConsume(code, 10), "重建后必须按快照收口，不能重新播种成满额");
        assertEquals("0", redis.opsForValue().get("sl:cnt:" + code));
    }

    /** 缓冲是绝对次数口径，但绝不能把小配额链接一次打死：超过剩余额度 1/4 的部分要自动失效。 */
    @Test
    void rebuildBufferIsCappedAtQuarterOfRemainingSoSmallQuotasSurvive() {
        String code = "m7bc" + System.nanoTime() % 100000;
        insertRoute(code, 1);
        jdbc.update("UPDATE link_route SET access_used = 0, access_used_at = NOW() WHERE short_code = ?", code);
        redis.delete("sl:cnt:" + code);

        AccessCounter counter = new AccessCounter(redis, jdbc, new SimpleMeterRegistry(), 100);
        // 上限 10、已用 0 → 剩 10；min(100, 10/4=2) = 2 → 播种 8，首击后余 7
        assertTrue(counter.tryConsume(code, 10));
        assertEquals("7", redis.opsForValue().get("sl:cnt:" + code),
                "播种值应为 10 − 0 − 2（缓冲被剩余额度封顶）");
    }

    /**
     * "键不存在"有两种：计数器丢了（要按快照收口）与从没被点过的新链接（没有历史要保护）。
     * 区分信号是 {@code access_used_at}——对账只在 Redis 键存在时回写，它是 NULL 就说明这条
     * 链接从没消耗过配额。混为一谈的后果是每条新链接首击就被扣掉一个缓冲：
     * accept-m2-13 的 D1 段实测把 quota=50 的链接点 3 次后余数只剩 35，就是这么来的。
     */
    @Test
    void neverReconciledLinkSeedsFullQuota() {
        String code = "m7nf" + System.nanoTime() % 100000;
        insertRoute(code, 1);                       // access_used=0、access_used_at IS NULL
        redis.delete("sl:cnt:" + code);

        AccessCounter counter = new AccessCounter(redis, jdbc, new SimpleMeterRegistry(), 100);
        assertTrue(counter.tryConsume(code, 3));
        assertEquals("2", redis.opsForValue().get("sl:cnt:" + code), "全新链接该按满额播种：3 − 1");
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
