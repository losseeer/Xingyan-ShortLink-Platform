package com.xingyan.shortlink.mock.attribution;

import com.xingyan.shortlink.common.attribution.AttributionBatchCodec;
import com.xingyan.shortlink.common.attribution.AttributionStat;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3-03 接收端判定的活体验收（compose MySQL @3307 的独立库 `xsl_mock`）：
 * 幂等吸收、校验和拒收、按桶覆盖——这三条是 DESIGN 7.3 承诺给宣发平台的语义，不能只靠读代码相信。
 *
 * <p>基础设施不齐时整体跳过（口令缺失也跳过而不是回落成别的口令）；表不存在时提示先 {@code make db-init}，
 * 免得看到一片 skip 却不知道为什么。
 */
class AttributionIngestLiveTest {

    private static final long TENANT = 1001;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 11, 3, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 11, 3, 4);
    private static final LocalDateTime M1 = LocalDateTime.of(2026, 10, 11, 3, 1);
    private static final LocalDateTime M2 = LocalDateTime.of(2026, 10, 11, 3, 2);

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static MockAttributionService service;

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
        assumeTrue("2".equals(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'xsl_mock' AND table_name IN ('attribution_batch','attribution_stat')", String.class)),
                "xsl_mock 的表还没建：先 make db-init");
        service = new MockAttributionService(jdbc, TENANT);
    }

    @AfterAll
    static void down() {
        if (ds != null) ds.close();
    }

    @AfterEach
    void clean() {
        for (String batchId : mine) {
            jdbc.update("DELETE FROM attribution_batch WHERE batch_id = ?", batchId);
            jdbc.update("DELETE FROM attribution_stat WHERE batch_id = ?", batchId);
        }
        mine.clear();
    }

    private static AttributionStat stat(String campaign, String channel, LocalDateTime minute, long pv) {
        return new AttributionStat(campaign, channel, minute, pv, pv - 1, pv, 0);
    }

    private AttributionBatch batch(LocalDateTime from, LocalDateTime to, List<AttributionStat> items) {
        String batchId = AttributionBatchCodec.batchId(TENANT, from, to, items);
        mine.add(batchId);
        return new AttributionBatch(batchId, TENANT, from, to, AttributionBatchCodec.checksum(items), items);
    }

    private Map<String, Object> statRow(LocalDateTime minute) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pv, valid_pv, batch_id FROM attribution_stat WHERE tenant_id = ? AND campaign_id = 'cp-t' AND channel_id = 'ch-t' AND minute = ?",
                TENANT, minute);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer timesReceived(String batchId) {
        List<Integer> v = jdbc.queryForList("SELECT times_received FROM attribution_batch WHERE batch_id = ?",
                Integer.class, batchId);
        return v.isEmpty() ? null : v.get(0);
    }

    @Test
    void sameBatchTwiceIsAbsorbedNotDoubleCounted() {
        List<AttributionStat> items = List.of(stat("cp-t", "ch-t", M1, 9), stat("cp-t", "ch-t", M2, 5));
        AttributionBatch b = batch(FROM, TO, items);

        assertEquals(MockAttributionService.Verdict.ACCEPTED, service.ingest(b).verdict());
        assertEquals(9L, ((Number) statRow(M1).get("pv")).longValue());

        MockAttributionService.Outcome again = service.ingest(b);
        assertEquals(MockAttributionService.Verdict.DUPLICATED, again.verdict(), "同内容重投必须被判重复");
        assertEquals(2, timesReceived(b.batchId()), "台账要留下「被吸收了几次」这个观测");
        assertEquals(9L, ((Number) statRow(M1).get("pv")).longValue(), "重复批次不得把数再加一遍");
    }

    @Test
    void tamperedValuesAreRejectedAndLeaveNoLedgerRow() {
        List<AttributionStat> honest = List.of(stat("cp-t", "ch-t", M1, 9));
        String checksum = AttributionBatchCodec.checksum(honest);
        // 发送端数值被改过但校验和还是旧的：这正是 checksum 要拦的那件事
        List<AttributionStat> tampered = List.of(stat("cp-t", "ch-t", M1, 99));
        String batchId = AttributionBatchCodec.batchId(TENANT, FROM, TO, tampered);
        mine.add(batchId);

        MockRejectException e = assertThrows(MockRejectException.class, () -> service.ingest(
                new AttributionBatch(batchId, TENANT, FROM, TO, checksum, tampered)));
        assertTrue(e.getMessage().startsWith("checksum"), e.getMessage());
        assertEquals(null, timesReceived(batchId), "坏批次不该在台账里留下一行——那会让事后以为收到了");
        assertEquals(null, statRow(M1));
    }

    @Test
    void batchIdClaimedByTheSenderIsRecomputedHere() {
        List<AttributionStat> items = List.of(stat("cp-t", "ch-t", M1, 4));
        String realId = AttributionBatchCodec.batchId(TENANT, FROM, TO, items);
        mine.add(realId);
        MockRejectException e = assertThrows(MockRejectException.class, () -> service.ingest(
                new AttributionBatch("xsl-9999-冒充的批次号", TENANT, FROM, TO,
                        AttributionBatchCodec.checksum(items), items)));
        assertTrue(e.getMessage().startsWith("batch_id"), e.getMessage());
    }

    @Test
    void lateArrivingRowsOverwriteTheBucket() {
        AttributionBatch first = batch(FROM, TO, List.of(stat("cp-t", "ch-t", M1, 9)));
        service.ingest(first);

        // 同一窗口、同一桶，pv 从 9 变成 12（迟到点击补全）：id 因此不同，走覆盖而不是被幂等丢掉
        AttributionBatch later = batch(FROM, TO, List.of(stat("cp-t", "ch-t", M1, 12)));
        assertNotEquals(first.batchId(), later.batchId());
        assertEquals(MockAttributionService.Verdict.ACCEPTED, service.ingest(later).verdict());

        Map<String, Object> row = statRow(M1);
        assertEquals(12L, ((Number) row.get("pv")).longValue(), "更全的那一版要落得下来");
        assertEquals(later.batchId(), row.get("batch_id"), "桶上要能倒查这行数是哪个批次推来的");
    }

    @Test
    void bucketOutsideTheEnvelopeWindowIsRejected() {
        List<AttributionStat> items = List.of(stat("cp-t", "ch-t", LocalDateTime.of(2026, 10, 11, 9, 9), 3));
        String batchId = AttributionBatchCodec.batchId(TENANT, FROM, TO, items);
        mine.add(batchId);
        MockRejectException e = assertThrows(MockRejectException.class, () -> service.ingest(
                new AttributionBatch(batchId, TENANT, FROM, TO, AttributionBatchCodec.checksum(items), items)));
        assertTrue(e.getMessage().contains("窗口"), e.getMessage());
    }

    @Test
    void emptyItemsAreRejected() {
        List<AttributionStat> none = List.of();
        String batchId = AttributionBatchCodec.batchId(TENANT, FROM, TO, none);
        mine.add(batchId);
        assertThrows(MockRejectException.class, () -> service.ingest(
                new AttributionBatch(batchId, TENANT, FROM, TO, AttributionBatchCodec.checksum(none), none)));
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
