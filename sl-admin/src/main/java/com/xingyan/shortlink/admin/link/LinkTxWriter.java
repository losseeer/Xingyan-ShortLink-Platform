package com.xingyan.shortlink.admin.link;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同分片键（tenant_id）主表 + outbox 的本地事务写（DESIGN 8.4）。
 * 独立 bean：避免 LinkService 自调用绕过事务代理。
 */
@Component
public class LinkTxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LinkTxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void createCommitted(long id, String code, CreateLinkRequest req, long tenantId, String routeJson) {
        jdbc.update("INSERT INTO short_link (id, short_code, origin_url, tenant_id, channel_id, campaign_id, "
                        + "promoter_id, redirect_type, expire_time, access_limit, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, code, req.originUrl(), tenantId, req.channelId(), req.campaignId(), req.promoterId(),
                req.redirectType() == null ? 1 : req.redirectType(), req.expireTime(), req.accessLimit());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("short_code", code);
        payload.put("origin_url", req.originUrl());
        payload.put("route_json", readTree(routeJson));
        jdbc.update("INSERT INTO outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status) "
                        + "VALUES ('LINK', ?, ?, 'LINK_CREATED', ?, 0)",
                code, tenantId, write(payload));
    }

    @Transactional
    public void updateCommitted(long tenantId, String code, LocalDateTime expireTime, Integer accessLimit,
                                int status, String routeJson) {
        jdbc.update("UPDATE short_link SET expire_time = ?, access_limit = ?, status = ? "
                        + "WHERE tenant_id = ? AND short_code = ?",
                expireTime, accessLimit, status, tenantId, code);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("short_code", code);
        payload.put("route_json", readTree(routeJson));
        jdbc.update("INSERT INTO outbox (aggregate_type, aggregate_id, tenant_id, event_type, payload_json, status) "
                        + "VALUES ('LINK', ?, ?, 'LINK_UPDATED', ?, 0)",
                code, tenantId, write(payload));
    }

    private Object readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
