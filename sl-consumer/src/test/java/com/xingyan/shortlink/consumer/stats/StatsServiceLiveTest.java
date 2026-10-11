package com.xingyan.shortlink.consumer.stats;

import com.xingyan.shortlink.common.event.ClickEvent;
import com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 看板四条只读查询的活体验收（compose ClickHouse @8123，不可达则跳过，M3-05）。
 *
 * <p>自己往明细里插一批**形状已知**的点击（8 放行 / 2 超频 / 10 个不同 ip_hash），再要求四条查询
 * 报出同一个已知的数。这就是"看板与直查一致"那条出口标准在服务层的版本——脚本那层会再测一次端到端。
 *
 * <p>租户用一个撞不上别人的私有数字（而不是 seed 里的 1001）：本机 CH 里躺着历史演示数据，
 * 按 1001 查出来的聚合值不是确定数，断言就会变成"今天有没有别人跑过脚本"的函数。
 * 这与 {@code ClickPipelineLiveTest} 用隔离短码是同一种思路。
 */
class StatsServiceLiveTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long TENANT = 910_000_000L + Math.abs(System.nanoTime() % 1000);
    private static final String CODE = "st" + Long.toString(Math.abs(System.nanoTime() % 100_000_000L), 36);
    private static final String CAMPAIGN = "cp-stats-" + CODE;
    private static final String CHANNEL = "ch-stats-" + CODE;

    private static ClickHouseWriter ch;
    private static StatsService stats;

    @BeforeAll
    static void up() throws Exception {
        assumeTrue(portOpen(), "ClickHouse 8123 不可达");
        ch = new ClickHouseWriter("http://localhost:8123/", "xsl_app",
                System.getenv().getOrDefault("XSL_CH_PASSWORD", ""), "xsl");
        stats = new StatsService(ch, 24, 31);

        // 6 击落在"上一分钟"，4 击落在"这一分钟"，其中 2 击 risk_score=25（被频控拦下的那一档）
        long now = System.currentTimeMillis() / 1000;
        List<ClickEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(event(i, i < 6 ? now - 90 : now, i >= 8 ? 25 : 0, i < 7 ? "wechat" : "unknown"));
        }
        ch.writeBatch(events);

        // 等这批真的可见：async_insert 已经 wait=1，但"POST 返回"与"FINAL 查询读得到"之间仍有毫秒级窗口。
        // 不等就断言会拿到随机结果（ITER-M3 §4 记过同族教训）。
        String from = F.format(LocalDateTime.now().minusSeconds(900));
        String to = F.format(LocalDateTime.now().plusSeconds(120));
        for (int i = 0; i < 20; i++) {
            if (directCount(from, to) == 10L) {
                return;
            }
            Thread.sleep(500);
        }
        assertEquals(10L, directCount(from, to), "写入后 10s 内直查应该能数到 10 行");
    }

    @AfterAll
    static void clean() {
        if (ch != null && portOpen()) {
            try {
                ch.execute("ALTER TABLE xsl.click_event DELETE WHERE short_code = '" + CODE + "'");
            } catch (Exception ignored) {
                // 收尾失败不该把测试判红；隔离串本身不会污染别人的查询
            }
        }
    }

    private static boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 8123), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static ClickEvent event(int i, long ts, int riskScore, String deviceType) {
        ClickEvent e = new ClickEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setShortCode(CODE);
        e.setClickId("c-stats-" + i + "-" + CODE);
        e.setClickTime(ts);
        e.setIpHash("stats-ip-" + CODE + "-" + i);        // 十个各不相同 ⇒ uv 应当正好是 10
        e.setUserAgent("Mozilla/5.0 (stats-live-test)");
        e.setReferer("");
        e.setDeviceType(deviceType);
        e.setOs("desktop");
        e.setProvince("");
        e.setCity("");
        e.setChannelId(CHANNEL);
        e.setCampaignId(CAMPAIGN);
        e.setPromoterId("pr-stats");
        e.setUtmParams("{}");
        e.setRiskScore(riskScore);
        e.setIsBot(0);                                     // 评分层未交付：恒 0 是事实，不是偷懒
        e.setTenantId(TENANT);
        return e;
    }

    /** 与四条接口无关的一条直查：用来确认"数据已经能读到"，再开始断言服务给的数。 */
    private static long directCount(String from, String to) throws IOException, InterruptedException {
        return ClickHouseWriter.longOf(ch.scalar("SELECT count() FROM xsl.click_event FINAL WHERE short_code = '"
                + CODE + "' AND click_time >= '" + from + "' AND click_time < '" + to + "'"));
    }

    private static StatsWindow minuteWindow() {
        return stats.resolveWindow(F.format(LocalDateTime.now().minusMinutes(15)),
                F.format(LocalDateTime.now().plusMinutes(2)), "minute");
    }

    private static long num(Map<?, ?> row, String key) {
        return ClickHouseWriter.longOf(row.get(key));
    }

    private static String str(Map<?, ?> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> child(Map<String, Object> data, String key) {
        return (Map<?, ?>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> list(Map<String, Object> data, String key) {
        return (List<Map<?, ?>>) data.get(key);
    }

    @Test
    void totalsMatchTheKnownShapeOfWhatWasWritten() {
        Map<String, Object> data = stats.linkStats(CODE, minuteWindow());
        Map<?, ?> totals = child(data, "totals");
        assertEquals(10L, num(totals, "pv"), "pv 就是写进去的 10 击");
        assertEquals(8L, num(totals, "valid_pv"), "valid_pv = is_bot=0 且 risk_score<25");
        assertEquals(2L, num(totals, "blocked_pv"), "blocked_pv = risk_score>=25 那一档");
        assertEquals(0L, num(totals, "bot_pv"), "评分层未交付，bot 恒 0 要如实报 0 而不是消失");
        assertEquals(10L, num(totals, "uv"), "十个不同 ip_hash ⇒ uv 精确等于 10");
        // 这里**不**断言 pv == valid + blocked：当前它俩相加确实等于 pv（因为 is_bot 恒 0），
        // 但那是能力缺失造成的巧合，评分层一交付就会破。钉死一个巧合等于给未来埋一条假红。
        assertEquals(num(totals, "pv"), num(data, "click_count"), "click_count 是兼容字段，语义必须继续等于 pv");
    }

    @Test
    void seriesSplitsByMinuteAndAddsUpToTheTotal() {
        List<Map<?, ?>> series = list(stats.linkStats(CODE, minuteWindow()), "series");
        assertEquals(2, series.size(), "6 击在上一分钟、4 击在这一分钟 ⇒ 两个桶");
        long sum = 0;
        for (Map<?, ?> bucket : series) {
            String label = str(bucket, "bucket");
            assertTrue(label.contains("T"), "桶标签出去是 ISO 形式（空格形式会伪装成解析错）: " + label);
            sum += num(bucket, "pv");
        }
        assertEquals(10L, sum, "时间序列各桶相加要等于汇总——不然看板两栏会显示两个不同的数");
    }

    @Test
    void tenantLinkListCarriesTheCodeWithItsFourTiers() {
        Map<String, Object> data = stats.tenantLinks(TENANT, minuteWindow(), 20, 0);
        List<Map<?, ?>> links = list(data, "links");
        assertEquals(1, links.size(), "这个私有租户在本窗口里只有这一条码");
        Map<?, ?> one = links.get(0);
        assertEquals(CODE, str(one, "short_code"));
        assertEquals(CAMPAIGN, str(one, "campaign_id"));
        assertEquals(CHANNEL, str(one, "channel_id"));
        assertEquals(10L, num(one, "pv"));
        assertEquals(8L, num(one, "valid_pv"));
        assertEquals(2L, num(one, "blocked_pv"));
        Map<?, ?> page = child(data, "page");
        assertEquals(1L, num(page, "returned"));
        assertFalse(Boolean.TRUE.equals(page.get("has_more")), "1 条 < limit ⇒ 没有下一页");
        // 来源写进响应里，"与直查一致"这句才有可复核的对象（是这张表，不是另一张）
        assertEquals("clickhouse:click_event FINAL", str(data, "source"));
    }

    @Test
    void channelFunnelTiersAndContainerSplitAreBothPresent() {
        Map<String, Object> data = stats.tenantChannels(TENANT, minuteWindow());
        List<Map<?, ?>> channels = list(data, "channels");
        assertEquals(1, channels.size());
        assertEquals(10L, num(channels.get(0), "pv"));
        assertEquals(8L, num(channels.get(0), "valid_pv"));
        assertEquals(2L, num(channels.get(0), "blocked_pv"));
        List<Map<?, ?>> containers = list(data, "containers");
        assertEquals(2, containers.size(), "wechat / unknown 两类容器都要出现");
        long wechat = 0;
        for (Map<?, ?> c : containers) {
            if ("wechat".equals(str(c, "device_type"))) {
                wechat = num(c, "pv");
            }
        }
        assertEquals(7L, wechat, "容器计数来自 device_type 词汇表（M2-32 那套三处共用的串）");
        assertFalse(Boolean.TRUE.equals(child(data, "conversion").get("available")),
                "转化那一档本期就是没接：页面要显示「未接入」而不是画一个 0");
    }

    @Test
    void campaignTrendAndLinkViewAgreeOnTheSameNumbers() {
        StatsWindow w = minuteWindow();
        Map<String, Object> campaign = stats.campaignStats(CAMPAIGN, w);
        Map<String, Object> link = stats.linkStats(CODE, w);
        assertEquals(num(child(campaign, "totals"), "pv"), num(child(link, "totals"), "pv"),
                "同一批点击从两个入口进来必须给同一个数——否则看板两页自相矛盾");
        assertEquals(2, list(campaign, "series").size());
        assertEquals(1, list(campaign, "channels").size());
        assertFalse(Boolean.TRUE.equals(child(campaign, "conversion").get("available")));
    }

    @Test
    void emptyWindowIsZerosRatherThanAnError() {
        StatsWindow w = stats.resolveWindow("2019-01-01 00:00:00", "2019-01-02 00:00:00", "day");
        Map<String, Object> data = stats.linkStats(CODE, w);
        assertEquals(0L, num(child(data, "totals"), "pv"), "空窗口是 0，不是异常");
        assertTrue(list(data, "series").isEmpty());
        assertTrue(list(stats.tenantLinks(TENANT, w, 20, 0), "links").isEmpty(),
                "列表页在空窗口要给空数组（页面显示空态）——后端故障与「没人点过」必须是两件事");
    }

    @Test
    void invalidIdsAndWindowsAreRejectedBeforeAnyQuery() {
        StatsWindow w = minuteWindow();
        assertThrows(StatsRequestException.class, () -> stats.linkStats("bad code", w));
        assertThrows(StatsRequestException.class, () -> stats.linkStats(CODE + "'", w));
        assertThrows(StatsRequestException.class, () -> stats.campaignStats("cp; DROP TABLE x", w));
        assertThrows(StatsRequestException.class,
                () -> stats.resolveWindow("2026-10-11 12:00:00", "2026-10-11 11:00:00", null));
    }
}
