package com.xingyan.shortlink.admin.link;

import java.time.LocalDateTime;

/**
 * PATCH /api/v1/links/{code}：字段均可选，仅更新给出项（DESIGN 7.2）。
 */
public record UpdateLinkRequest(
        LocalDateTime expireTime,
        Integer accessLimit,
        Integer status) {
}
