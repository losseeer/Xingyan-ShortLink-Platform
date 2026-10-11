package com.xingyan.shortlink.admin.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.admin.route.RouteCachePublisher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3-02 补偿链路的活体验收（compose MySQL @3307 / Redis @6380）。
 *
 * <p><b>断言只看终态，不看计数器</b>：部署里的 admin 容器同一个任务每 5s 也在扫这张表，
 * 谁先拿到这一行都可能（ITER-M3 §4 第一条记过同一类"两个写入方并存"）。终态是两边共同保证的东西，
 * 计数器不是。指标的口径在 {@code scripts/accept-m3-02.sh} 里对着容器的 /actuator/prometheus 断。
 */
class OutboxSyncServiceIntegrationTest {

    private static final long TENANT = 1001;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;

    private final List<String> mine = new ArrayList<>();

    @BeforeAll
    static void up() {
        assumeTrue(portOpen(6380) && portOpen(3307));
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
        if (redisFactory != null) redisFactory.destroy();
        if (ds != null) ds.close();
    }

    @AfterEach
    void clean() {
        for (String code : mine) {
            jdbc.update("DELETE FROM outbox WHERE aggregate_id = ?", code);
            jdbc.update("DELETE FROM link_route WHERE short_code = ?", code);
            jdbc.update("DELETE FROM code_tenant_index WHERE short_code = ?", code);
            redis.delete("sl:r:" + code);
        }
        mine.clear();
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private OutboxSyncService service(int maxRetry) {
        return new OutboxSyncService(jdbc, new ObjectMapper(),
                new RouteCachePublisher(redis, new SimpleMeterRegistry()), new SimpleMeterRegistry(),
                true, 500, maxRetry);
    }

    private String track() {
        String code = "m3o" + (System.nanoTime() % 1000000);
        mine.add(code);
        return code;
    }

    private static String routeJson(String code, long version, String origin) {
        return "{\"origin_url\":\"" + origin + "\",\"tenant_id\":1001,\"redirect_type\":1,"
                + "\"expire_time\":null,\"access_limit\":null,\"status\":0,\"version\":" + version + "}";
    }

    /** 与管理面 {@code LinkTxWriter} 同形状的事件：payload 里带完整的 route_json 快照。 */
    private void putEvent(String code, long version, String origin) {
        jdbc.update("INSERT INTO outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status) "
                        + "VALUES ('LINK', ?, ?, 'LINK_UPDATED', CAST(? AS JSON), 0)", code, TENANT,
                "{\"short_code\":\"" + code + "\",\"route_json\":" + routeJson(code, version, origin) + "}");
    }

    /** 坏事件：payload 是合法 JSON 但没有可用的 route_json.version —— 补偿不动，只该重试到上限。 */
    private void putBrokenEvent(String code) {
        jdbc.update("INSERT INTO outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status) "
                        + "VALUES ('LINK', ?, ?, 'LINK_UPDATED', CAST(? AS JSON), 0)", code, TENANT,
                "{\"short_code\":\"" + code + "\",\"reason\":\"NO_ROUTE_JSON\"}");
    }

    private void putRoute(String code, long version, String origin) {
        jdbc.update("INSERT INTO link_route (short_code, route_json, version) VALUES (?, CAST(? AS JSON), ?)",
                code, routeJson(code, version, origin), version);
    }

    private Map<String, Object> routeRow(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT route_json, version FROM link_route WHERE short_code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer outboxStatus(String code) {
        List<Integer> rows = jdbc.queryForList("SELECT status FROM outbox WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 一律走"只补偿这一条"的定点模式：扫描按 id 升序，本机首次上线时历史积压有 7,955 行，
     * 整批模式要等好几轮才轮到测试自己插的那一行（第一轮跑就是这么假失败的）。
     * 容器里那个定时任务仍在跑整批，所以断言看的还是终态而不是计数。
     */
    private void sync(String code, int maxRetry) {
        service(maxRetry).syncOnce(code);
    }

    /** 反复定点补偿直到这一行的 status 变成期望值（或次数用尽）。 */
    private void awaitStatus(String code, int expected, int rounds) {
        for (int i = 0; i < rounds && !Integer.valueOf(expected).equals(outboxStatus(code)); i++) {
            sync(code, 3);
        }
    }

    /**
     * 最坏的那种不一致：创建时 {@code insertRouteAndIndex} 整段没跑完，路由表缺行（跳转永久 404），
     * 归属索引也缺（管理面按码查不到）。补行必须把这一对一起补回来。
     */
    @Test
    void createsTheMissingRouteRowAndItsTenantIndex() {
        String code = track();
        putEvent(code, 3, "https://mock.ticketsales.test/from-outbox");

        sync(code, 10);
        awaitStatus(code, 1, 3);

        Map<String, Object> row = routeRow(code);
        assertNotNull(row, "outbox 里的 LINK_CREATED 事件要把缺失的路由行补出来");
        assertEquals(3L, ((Number) row.get("version")).longValue());
        List<Long> owners = jdbc.queryForList("SELECT tenant_id FROM code_tenant_index WHERE short_code = ?",
                Long.class, code);
        assertEquals(List.of(TENANT), owners, "只补路由不补归属，会得到一条跳转能 302、管理面却查不到的链接");
        assertNotNull(redis.opsForValue().get("sl:r:" + code), "补行之后缓存也要跟上，否则第一次跳转白打一次 DB");
    }

    @Test
    void movesAStaleRouteForward() {
        String code = track();
        putRoute(code, 1, "https://mock.ticketsales.test/STALE");
        putEvent(code, 2, "https://mock.ticketsales.test/fresh");

        sync(code, 10);
        awaitStatus(code, 1, 3);

        Map<String, Object> row = routeRow(code);
        assertNotNull(row);
        assertEquals(2L, ((Number) row.get("version")).longValue());
        assertTrue(String.valueOf(row.get("route_json")).contains("fresh"), "落后的快照要被事件里的新值推进");
    }

    /** 地基性质：整张 outbox 重放不会把路由表写回旧值（所以才敢在启动时无条件跑）。 */
    @Test
    void anOlderEventNeverMovesARouteBackward() {
        String code = track();
        putRoute(code, 5, "https://mock.ticketsales.test/current");
        putEvent(code, 4, "https://mock.ticketsales.test/SHOULD-NEVER-LAND");

        sync(code, 10);
        awaitStatus(code, 1, 3);

        Map<String, Object> row = routeRow(code);
        assertNotNull(row);
        assertEquals(5L, ((Number) row.get("version")).longValue(), "version 只能变新");
        String stored = String.valueOf(row.get("route_json"));
        assertTrue(stored.contains("current") && !stored.contains("SHOULD-NEVER-LAND"), "旧事件的内容不能落库: " + stored);
        assertEquals(5L, cachedVersion(code), "缓存同理，旧事件不许把它写回旧值");
    }

    /** 幂等：同一批事件重复投递（手动连点两次 replay）不会改状态、也不会造出第二个版本。 */
    @Test
    void replayingTheSameBatchTwiceChangesNothing() {
        String code = track();
        putEvent(code, 1, "https://mock.ticketsales.test/twice");

        sync(code, 10);
        awaitStatus(code, 1, 3);
        Map<String, Object> first = routeRow(code);
        assertNotNull(first);

        sync(code, 10);
        sync(code, 10);
        Map<String, Object> again = routeRow(code);
        assertEquals(((Number) first.get("version")).longValue(), ((Number) again.get("version")).longValue());
        assertEquals(String.valueOf(first.get("route_json")), String.valueOf(again.get("route_json")));
        assertEquals(1, outboxStatus(code), "已投递的行不该被重新打开");
    }

    /**
     * 坏事件不能永远占着待投递：到上限置 {@code 2}（"要人看"的终态），并且不再被扫。
     * 循环次数是有意多给的——容器里那个任务也在推进同一个计数，双方都可能各写一次。
     */
    @Test
    void aBadEventStopsBeingRetriedInsteadOfHangingForever() {
        String code = track();
        putBrokenEvent(code);

        for (int round = 0; round < 12 && !Integer.valueOf(2).equals(outboxStatus(code)); round++) {
            sync(code, 3);
        }

        assertEquals(2, outboxStatus(code), "连续失败到上限要置为失败，而不是每轮把整批额度吃一遍");
        assertNull(routeRow(code), "补偿失败不该凭空造出一条路由来");

        // 再等一轮容器自己的定时任务（5s）：它如果把这行改回待投递，说明两个写入方在互相覆盖，
        // 那是真问题，不该被这条测试的快循环盖过去。
        sleepPastContainerRound();
        assertEquals(2, outboxStatus(code), "置 2 之后不该再被任何一方打开");
        assertTrue(jdbc.queryForList("SELECT id FROM outbox WHERE aggregate_id = ? AND status = 0", code).isEmpty(),
                "失败终态不在待投递集合里");
    }

    private static void sleepPastContainerRound() {
        try {
            Thread.sleep(6500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long cachedVersion(String code) {
        try {
            String raw = redis.opsForValue().get("sl:r:" + code);
            assertNotNull(raw);
            return new ObjectMapper().readTree(raw).path("version").asLong();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
