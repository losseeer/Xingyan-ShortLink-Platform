package com.xingyan.shortlink.common.attribution;

import java.time.LocalDateTime;

/**
 * 归因回传里的一行分钟桶（DESIGN 7.3 的 `items` 元素，M3-03）。
 *
 * <p>刻意<b>不带 tenant_id</b>：租户身份在 batch 信封上，由接收端与"这把 key 属于哪个租户"对齐后才收。
 * 把租户塞进每一行，等于给接收方一个可以被伪造后越权的字段。
 *
 * @param campaignId 活动 ID（空串不会出现在这里——发送端按非空 campaign 过滤）
 * @param channelId  渠道 ID
 * @param minute     分钟桶起点（秒位恒为 0）
 * @param pv         该桶点击数
 * @param uv         去重访客数（明细侧精确去重）
 * @param validPv    有效点击：非 bot 且未被频控拦下（{@code risk_score < 25}）
 * @param botPv      疑似刷量：评分层落地前恒 0，这是能力边界不是链路缺陷（DESIGN 6.4）
 */
public record AttributionStat(String campaignId, String channelId, LocalDateTime minute,
                              long pv, long uv, long validPv, long botPv) {
}
