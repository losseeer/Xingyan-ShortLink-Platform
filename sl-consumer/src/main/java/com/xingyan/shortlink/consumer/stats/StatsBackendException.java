package com.xingyan.shortlink.consumer.stats;

/** ClickHouse 读不到（容器重启、口令不对、查询超时）——503，与"这个窗口没有点击"必须是两个东西。 */
public class StatsBackendException extends RuntimeException {

    public StatsBackendException(String message) {
        super(message);
    }
}
