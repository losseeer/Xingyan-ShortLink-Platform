package com.xingyan.shortlink.admin.link;

import java.time.LocalDateTime;

/**
 * POST /api/v1/links 请求体（snake_case 经 Jackson SNAKE_CASE 策略绑定）。
 */
public record CreateLinkRequest(
        String originUrl,
        String channelId,
        String campaignId,
        String promoterId,
        String shortCode,
        LocalDateTime expireTime,
        Integer accessLimit,
        Integer redirectType) {
}
