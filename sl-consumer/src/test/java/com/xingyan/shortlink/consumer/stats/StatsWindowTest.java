package com.xingyan.shortlink.consumer.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口解析与入参把关（纯单测，不碰 ClickHouse）。
 *
 * <p>这里盯的两件事最容易在活体测试里被"恰好通过"掩盖：粒度自动规则、以及"外部串不许原样进 SQL"。
 * 前者决定看板的时间序列长什么样，后者是唯一挡住 SQL 注入的地方——ClickHouse 走 HTTP 拼文本，
 * 没有参数化可依赖，所以把关只能前移到解析。
 */
class StatsWindowTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StatsService service = newService(24, 31);

    private static StatsService newService(int defaultHours, int maxDays) {
        // 这个 4 参构造不发网络请求，只是把库名和口令准备好
        return new StatsService(new com.xingyan.shortlink.consumer.clickhouse.ClickHouseWriter(
                "http://localhost:8123/", "xsl_app", "irrelevant-for-window-parsing", "xsl"), defaultHours, maxDays);
    }

    @Test
    void defaultsToLastNHoursAndAlignsNothingButFormat() {
        StatsWindow w = service.resolveWindow(null, null, null);
        assertEquals(w.spanSeconds(), 24 * 3600, "缺省窗口就是 default-window-hours");
        assertTrue(w.from().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "进 SQL 的串必须是规范形态");
        // 不给粒度时按区间长度自动：24h → 小时
        assertEquals(StatsWindow.Granularity.HOUR, w.granularity());
    }

    @Test
    void granularityAutoRulesAreTheDocumentedThreeBands() {
        LocalDateTime to = LocalDateTime.of(2026, 10, 11, 12, 0, 0);
        assertEquals(StatsWindow.Granularity.MINUTE,
                service.resolveWindow(F.format(to.minusHours(2)), F.format(to), null).granularity(), "≤2h 走分钟");
        assertEquals(StatsWindow.Granularity.HOUR,
                service.resolveWindow(F.format(to.minusDays(7)), F.format(to), null).granularity(), "≤7d 走小时");
        assertEquals(StatsWindow.Granularity.DAY,
                service.resolveWindow(F.format(to.minusDays(8)), F.format(to), null).granularity(), "更长走天");
        // 显式给了就听它的，不自动覆盖
        assertEquals(StatsWindow.Granularity.DAY,
                service.resolveWindow(F.format(to.minusHours(1)), F.format(to), "day").granularity());
    }

    @Test
    void isoAndSpaceTimeFormsBothAcceptedAndReRenderedCanonically() {
        StatsWindow w = service.resolveWindow("2026-10-11T10:00:00", "2026-10-11T11:00:00", null);
        assertEquals("2026-10-11 10:00:00", w.from(), "ISO 进来要打回 CH 认的空格形式");
        assertEquals("2026-10-11 11:00:00", w.to());
        StatsWindow m = service.resolveWindow("2026-10-11 10:00", "2026-10-11 11:00", null);
        assertEquals("2026-10-11 10:00:00", m.from(), "秒省略时补 00，别把半截串交给 SQL");
    }

    @Test
    void badWindowsAreRejectedRatherThanSilentlySwapped() {
        assertThrows(StatsRequestException.class,
                () -> service.resolveWindow("2026-10-11 12:00:00", "2026-10-11 11:00:00", null),
                "from 晚于 to 不能悄悄对调——那会让看板显示一个没人问过的窗口");
        assertThrows(StatsRequestException.class, () -> service.resolveWindow("昨天", null, null));
        assertThrows(StatsRequestException.class,
                () -> service.resolveWindow(null, null, "week"),
                "认不出的粒度要报错，不能兜个默认值继续跑");
        StatsService narrow = newService(24, 3);
        assertThrows(StatsRequestException.class,
                () -> narrow.resolveWindow("2026-09-01 00:00:00", "2026-10-11 00:00:00", null),
                "超过 max-window-days 就是护栏该起作用的地方");
    }

    @Test
    void idsAndPaginationBoundsAreCheckedBeforeTheyReachSql() {
        assertThrows(StatsRequestException.class, () -> StatsService.requireTenant("1001;DROP"));
        assertThrows(StatsRequestException.class, () -> StatsService.requireTenant(""));
        assertEquals(1001L, StatsService.requireTenant(" 1001 "));
        assertThrows(StatsRequestException.class, () -> StatsService.clamp(500, 1, 200, "limit"));
        assertThrows(StatsRequestException.class, () -> StatsService.clamp(-1, 0, 100_000, "offset"));
        // 带引号/空格的 id 必须在这里就挡住：它们后来是被拼进 SQL 文本的
        assertThrows(StatsRequestException.class, () -> service.campaignStats("cp'; SELECT 1", null));
        assertThrows(StatsRequestException.class, () -> service.linkStats("bad code", null));
    }
}
