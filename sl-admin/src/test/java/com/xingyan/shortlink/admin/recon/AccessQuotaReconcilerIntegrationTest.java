package com.xingyan.shortlink.admin.recon;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M2-12 对账任务的活体验收（compose MySQL @3307 / Redis @6380）：
 * Redis 权威剩余量要按 {@code used = access_limit − remain} 回写进 {@code link_route.access_used}，
 * 键不在时必须保留上一次快照（否则一次 flush 就把"少放"变成"超放"）。
 */
class AccessQuotaReconcilerIntegrationTest {

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
            jdbc.update("DELETE FROM link_route WHERE short_code = ?", code);
            jdbc.update("DELETE FROM short_link WHERE short_code = ?", code);
            redis.delete("sl:cnt:" + code);
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

    /** 直插两表（绕开签发链路，只针对对账这件事）；access_limit=7。 */
    private String fixture(String code) {
        mine.add(code);
        long id = System.nanoTime();
        jdbc.update("INSERT INTO short_link (id, short_code, origin_url, tenant_id, channel_id, campaign_id, "
                + "redirect_type, access_limit, status) VALUES (?, ?, ?, ?, ?, ?, 1, 7, 0)",
                id, code, "https://mock.ticketsales.test/quota", 1001L, "ch-q", "cp-q");
        jdbc.update("INSERT INTO link_route (short_code, route_json, version) VALUES (?, CAST(? AS JSON), 1)",
                code, "{\"origin_url\":\"https://mock.ticketsales.test/quota\",\"tenant_id\":1001,"
                        + "\"redirect_type\":1,\"expire_time\":null,\"access_limit\":7,\"status\":0,\"version\":1}");
        return code;
    }

    private Integer usedOf(String code) {
        return jdbc.queryForObject("SELECT access_used FROM link_route WHERE short_code = ?", Integer.class, code);
    }

    private AccessQuotaReconciler reconciler() {
        return new AccessQuotaReconciler(jdbc, redis, new SimpleMeterRegistry(), true, 500);
    }

    @Test
    void writesAuthoritativeUsageBackToTheSnapshot() {
        String code = fixture("q" + System.nanoTime() % 10000000);
        redis.opsForValue().set("sl:cnt:" + code, "4");     // 权威剩余 4 → 已用 7−4=3

        AccessQuotaReconciler.Summary s = reconciler().reconcileOnce();
        assertEquals(3, usedOf(code));
        assertTrue(s.tracked() >= 1);
        assertTrue(s.updated() >= 1);

        // 幂等：值没变就不该再写（UPDATE ... WHERE access_used <> ? 命中 0 行）
        redis.opsForValue().set("sl:cnt:" + code, "4");
        AccessQuotaReconciler.Summary again = reconciler().reconcileOnce();
        assertEquals(3, usedOf(code));
        assertTrue(again.unchanged() >= 1, "同一权威值重复对账应记为 unchanged 而不是又写一遍");
    }

    @Test
    void keepsLastSnapshotWhenTheRedisKeyIsGone() {
        String code = fixture("k" + System.nanoTime() % 10000000);
        redis.opsForValue().set("sl:cnt:" + code, "2");     // 已用 5
        reconciler().reconcileOnce();
        assertEquals(5, usedOf(code));

        redis.delete("sl:cnt:" + code);                     // 模拟键丢失（flush / 驱逐）
        AccessQuotaReconciler.Summary s = reconciler().reconcileOnce();
        assertEquals(5, usedOf(code), "没有权威值时保留上一次快照，不能倒回 0（那才是超放的源头）");
        assertTrue(s.noKey() >= 1);
    }

    @Test
    void clampsNegativeUsageToZero() {
        String code = fixture("n" + System.nanoTime() % 10000000);
        redis.opsForValue().set("sl:cnt:" + code, "9");     // 比上限还大：管理员改小过 access_limit
        reconciler().reconcileOnce();
        assertEquals(0, usedOf(code), "已用量不该是负数");
    }
}
