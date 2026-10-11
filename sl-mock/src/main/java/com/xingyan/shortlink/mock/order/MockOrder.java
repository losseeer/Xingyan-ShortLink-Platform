package com.xingyan.shortlink.mock.order;

import java.time.LocalDateTime;

/**
 * 假订单（DESIGN 附录 B 的落库字段，M3-04）。
 *
 * <p>字段就是附录 B 那六个归因字段 + 一个 {@code orderId}。<b>没有金额、座位、场次</b>——
 * 端到端对账要验的是"这笔订单能不能按 {@code click_id} 找回那次点击"，业务字段对这件事一点忙都帮不上，
 * 加了只会多出一堆需要有人维护的假数据。
 *
 * @param orderId      调用方（购票侧）生成的订单号，同时是幂等键
 * @param clickId      对账键，值就是跳转 URL 上的 {@code xy_click_id}
 */
public record MockOrder(String orderId, String clickId, String channelId, String campaignId,
                        String promoterId, LocalDateTime firstClickTime, LocalDateTime lastClickTime) {
}
