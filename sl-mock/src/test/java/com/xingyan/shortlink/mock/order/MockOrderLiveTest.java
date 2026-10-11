package com.xingyan.shortlink.mock.order;

import com.xingyan.shortlink.mock.MockRejectException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 假订单落库的活体验收（compose MySQL @3307 的 {@code xsl_mock.mock_order}，M3-04）。
 *
 * <p>盯的是幂等：同一 {@code order_id} 重发不能再插一行。这一条如果坏了，端到端对账 ② 的分母
 * 会虚高，而症状表现为"转化率莫名偏低"——离真凶很远，所以要在离真凶最近的地方拦住。
 */
class MockOrderLiveTest {

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static MockOrderService service;

    private final List<String> mine = new ArrayList<>();

    @BeforeAll
    static void up() {
        assumeTrue(portOpen(3307), "MySQL 3307 不可达");
        String password = System.getenv("XSL_MYSQL_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "缺 XSL_MYSQL_PASSWORD（make verify 会从 .env 注入）");
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3307/xsl_mock?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        cfg.setUsername("root");
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        ds = new HikariDataSource(cfg);
        jdbc = new JdbcTemplate(ds);
        assumeTrue("1".equals(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'xsl_mock' AND table_name = 'mock_order'", String.class)),
                "xsl_mock.mock_order 还没建：先 make db-init");
        service = new MockOrderService(jdbc);
    }

    @AfterAll
    static void down() {
        if (ds != null) ds.close();
    }

    @AfterEach
    void clean() {
        for (String id : mine) {
            jdbc.update("DELETE FROM mock_order WHERE order_id = ?", id);
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

    private String track(String prefix) {
        String id = prefix + "-" + System.nanoTime() % 100000000;
        mine.add(id);
        return id;
    }

    private MockOrder order(String orderId, String clickId) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 11, 15, 4, 5);
        return new MockOrder(orderId, clickId, "ch-recon", "cp-recon", "pr-1", t, t.plusMinutes(3));
    }

    private Map<String, Object> row(String orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mock_order WHERE order_id = ?", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void sameOrderIdIsNotCountedTwice() {
        String id = track("mo-a");
        assertEquals(MockOrderService.Verdict.CREATED, service.ingest(order(id, "clk-" + id)).verdict());
        assertEquals(1, rowsOf(id));

        MockOrderService.Outcome again = service.ingest(order(id, "clk-" + id));
        assertEquals(MockOrderService.Verdict.DUPLICATED, again.verdict(), "同一订单号重发必须被判重复");
        assertEquals(1, rowsOf(id), "重复提交不得再插一行——那会把对账的分母弄虚高");
        assertEquals(2, again.timesSubmitted(), "提交次数要留在表里可查");
    }

    @Test
    void attributionFieldsLandAsSent() {
        String id = track("mo-b");
        service.ingest(order(id, "clk-xyz"));
        Map<String, Object> r = row(id);
        assertEquals("clk-xyz", r.get("click_id"));
        assertEquals("cp-recon", r.get("campaign_id"));
        assertEquals("ch-recon", r.get("channel_id"));
        assertEquals("pr-1", r.get("promoter_id"));
        // 比对 LocalDateTime 对象而不是它的字符串形态：驱动回来的 toString 带 'T'，
        // 断成"2026-10-11 15:04:05"会失败，而库里存的其实是对的（我这轮就先踩在这上面）。
        assertEquals(LocalDateTime.of(2026, 10, 11, 15, 4, 5), r.get("first_click_time"));
        assertEquals(LocalDateTime.of(2026, 10, 11, 15, 7, 5), r.get("last_click_time"));
    }

    @Test
    void missingKeysAreRejectedAndLeaveNoRow() {
        String id = track("mo-c");
        MockRejectException noOrder = assertThrows(MockRejectException.class,
                () -> service.ingest(order("  ", "clk-1")));
        assertTrue(noOrder.getMessage().contains("order_id"), noOrder.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, noOrder.status());

        MockRejectException noClick = assertThrows(MockRejectException.class,
                () -> service.ingest(order(id, " ")));
        assertTrue(noClick.getMessage().contains("click_id"), noClick.getMessage());
        assertNull(row(id), "缺对账键的订单不该留下半行数据");
    }

    private int rowsOf(String orderId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM mock_order WHERE order_id = ?", Integer.class, orderId);
        return n == null ? 0 : n;
    }
}
