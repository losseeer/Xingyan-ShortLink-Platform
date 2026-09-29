package com.xingyan.shortlink.admin.pool;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M1-05 验收：短码池补池/租用/水位（DESIGN 5.1/4.3）。
 * 依赖本机 compose Redis(6380) 与 MySQL(3307)，不可达则整体跳过。隔离 ns=9999。
 */
class ShortCodePoolServiceTest {

    private static final int NS = 9999;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static HikariDataSource ssDs;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void up() {
        assumeTrue(infraUp());
        redisFactory = new LettuceConnectionFactory("127.0.0.1", 6380);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        ds.setJdbcUrl("jdbc:shardingsphere:classpath:sharding.yaml");
        ds.setMaximumPoolSize(10);
        ssDs = ds;
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void down() {
        if (redisFactory != null) redisFactory.destroy();
        if (ssDs != null) ssDs.close();
    }

    @BeforeEach
    void clean() {
        assumeTrue(infraUp());
        redis.delete(List.of("sl:pool:" + NS, "sl:pool:" + NS + ":seen", "sl:pool:refill:lock:" + NS));
        jdbc.update("DELETE FROM short_code_pool WHERE namespace = ?", NS);
    }

    private static boolean infraUp() {
        return portOpen("127.0.0.1", 6380) && portOpen("127.0.0.1", 3307);
    }

    private static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ShortCodePoolService service(long capacity) {
        return new ShortCodePoolService(redis, jdbc, NS, capacity, 0.2);
    }

    @Test
    void refillPopulatesUniquePoolAndDb() {
        ShortCodePoolService svc = service(300);
        int added = svc.refill();
        assertEquals(300, added);
        assertEquals(300, svc.size());
        Set<String> all = new HashSet<>(redis.opsForList().range("sl:pool:" + NS, 0, -1));
        assertEquals(300, all.size(), "池内码必须互不重复");
        Integer dbCount = jdbc.queryForObject("SELECT COUNT(*) FROM short_code_pool WHERE namespace = ?", Integer.class, NS);
        assertEquals(300, dbCount, "DB 登记数与池容量一致");
    }

    @Test
    void concurrentLeaseNeverDuplicates() throws Exception {
        service(1000).refill();
        int threads = 20, per = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            ShortCodePoolService svc = service(1000);
            List<Callable<List<String>>> jobs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                jobs.add(() -> svc.lease(per));
            }
            List<String> leased = new ArrayList<>();
            for (Future<List<String>> f : pool.invokeAll(jobs)) {
                leased.addAll(f.get());
            }
            assertEquals(threads * per, leased.size());
            Set<String> unique = leased.stream().collect(Collectors.toSet());
            assertEquals(1000, unique.size(), "并发租用取回的码不得重复");
            // 并发过程中若触发补池，池内可能仍有富余；唯一性/总数不受影响，故不断言 size==0
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emptyPoolTriggersRefillWithFreshCodes() {
        ShortCodePoolService svc = service(50);
        svc.refill();
        List<String> first = svc.lease(50);
        assertEquals(50, first.size());
        assertEquals(0, svc.size());

        List<String> second = svc.lease(10); // 水位为 0，触发补池后取 10 条
        assertEquals(10, second.size());
        Set<String> overlap = new HashSet<>(first);
        overlap.retainAll(second);
        assertTrue(overlap.isEmpty(), "补池后的新码不得与已租用码重叠");
    }
}
