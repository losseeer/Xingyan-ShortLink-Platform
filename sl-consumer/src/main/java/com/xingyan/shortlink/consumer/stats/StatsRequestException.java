package com.xingyan.shortlink.consumer.stats;

/** 调用方参数写错了（窗口倒置、id 非法、limit 越界）——400，消息可以直接给页面显示。 */
public class StatsRequestException extends RuntimeException {

    public StatsRequestException(String message) {
        super(message);
    }
}
