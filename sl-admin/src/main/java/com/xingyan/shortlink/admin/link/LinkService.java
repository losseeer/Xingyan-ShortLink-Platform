package com.xingyan.shortlink.admin.link;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingyan.shortlink.admin.pool.ShortCodePoolService;
import com.xingyan.shortlink.common.route.RouteConfig;
import com.xingyan.shortlink.common.admission.UrlAdmissionChecker;
import com.xingyan.shortlink.common.error.ErrorCode;
import com.xingyan.shortlink.common.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 短链生成/查询/更新（DESIGN 5.1、7.2、8.4、6.4 越权防护）。
 * 一致性口径：short_link+outbox 同一本地事务（同 tenant_id 分片，见 LinkTxWriter）；
 * link_route+code_tenant_index 按 short_code 分片轴，主事务提交后同步补写，
 * 失败留 outbox(status=0) 待 M3 补偿重放。
 * 短码唯一性：Redis SETNX 第一道防线 + code_tenant_index/link_route 主键兜底。
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9a-zA-Z]{4,12}$");
    private static final Set<String> RESERVED_WORDS =
            Set.of("api", "admin", "s", "health", "static", "login", "logout", "internal", "stats", "metrics", "assets", "www", "favicon");
    private static final Duration CACHE_BASE_TTL = Duration.ofHours(24);
    private static final int MAX_CODE_RETRIES = 3;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ShortCodePoolService pool;
    private final UrlAdmissionChecker admission;
    private final SnowflakeIdGenerator idGenerator;
    private final LinkTxWriter txWriter;
    private final ObjectMapper mapper;
    private final String baseDomain;
    private final SecureRandom random = new SecureRandom();

    public LinkService(StringRedisTemplate redis,
                       JdbcTemplate jdbc,
                       ShortCodePoolService pool,
                       UrlAdmissionChecker admission,
                       SnowflakeIdGenerator idGenerator,
                       LinkTxWriter txWriter,
                       ObjectMapper mapper,
                       @Value("${xsl.link.base-domain:xy1.test}") String baseDomain) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.pool = pool;
        this.admission = admission;
        this.idGenerator = idGenerator;
        this.txWriter = txWriter;
        this.mapper = mapper;
        this.baseDomain = baseDomain;
    }

    public Map<String, Object> create(long tenantId, CreateLinkRequest req) {
        String reject = admission.check(req.originUrl());
        if (reject != null) {
            throw new BizException(ErrorCode.URL_NOT_ALLOWED, HttpStatus.BAD_REQUEST, reject);
        }
        int redirectType = req.redirectType() == null ? 1 : req.redirectType();
        if (redirectType != 1) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "M1 supports redirect_type=1 (302) only");
        }
        if (isBlank(req.channelId()) || isBlank(req.campaignId())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "channel_id and campaign_id are required");
        }
        if (req.accessLimit() != null && req.accessLimit() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "access_limit must be positive");
        }
        validateRateLimit(req.rateLimitPerMinute());
        if (req.expireTime() != null && req.expireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "expire_time must be in the future");
        }

        String code = allocateCode(req.shortCode());
        long id = idGenerator.nextId();
        RouteConfig snapshot = new RouteConfig(req.originUrl(), tenantId, redirectType,
                req.expireTime(), req.accessLimit(), 0, 1,
                req.channelId(), req.campaignId(), req.promoterId(), req.rateLimitPerMinute());
        String routeJson = writeJson(snapshot);

        try {
            txWriter.createCommitted(id, code, req, tenantId, routeJson);
        } catch (RuntimeException e) {
            releaseCodeClaim(code);
            throw e;
        }
        try {
            insertRouteAndIndex(code, tenantId, routeJson);
            cacheRoute(code, routeJson);
            consumeFromPool(code);
        } catch (RuntimeException e) {
            // 主事务已提交：路由/索引同步失败留 outbox(status=0) 待补偿；M1 如实上抛
            log.error("[link] 路由同步失败，outbox 待补偿 code={} tenant={}", code, tenantId, e);
            throw e;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(id));
        data.put("short_code", code);
        data.put("short_url", shortUrl(code));
        data.put("origin_url", req.originUrl());
        data.put("tenant_id", tenantId);
        data.put("channel_id", req.channelId());
        data.put("campaign_id", req.campaignId());
        data.put("promoter_id", req.promoterId());
        data.put("expire_time", req.expireTime());
        data.put("access_limit", req.accessLimit());
        data.put("rate_limit_per_minute", req.rateLimitPerMinute());
        data.put("status", 0);
        return data;
    }

    public Map<String, Object> get(long tenantId, String code) {
        requireOwnership(tenantId, code);
        Map<String, Object> row = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT id, short_code, origin_url, channel_id, campaign_id, promoter_id, "
                        + "redirect_type, expire_time, access_limit, rate_limit_per_minute, status, create_time "
                        + "FROM short_link WHERE tenant_id = ? AND short_code = ?", tenantId, code));
        row.put("id", String.valueOf(row.get("id")));
        row.put("short_url", shortUrl(code));
        return row;
    }

    public Map<String, Object> update(long tenantId, String code, UpdateLinkRequest req) {
        requireOwnership(tenantId, code);
        if (req.status() != null && (req.status() < 0 || req.status() > 3)) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "status must be in [0,3]");
        }
        if (req.accessLimit() != null && req.accessLimit() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "access_limit must be positive");
        }
        validateRateLimit(req.rateLimitPerMinute());
        if (req.expireTime() != null && req.expireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "expire_time must be in the future");
        }
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT origin_url, redirect_type, expire_time, access_limit, rate_limit_per_minute, status, "
                        + "channel_id, campaign_id, promoter_id "
                        + "FROM short_link WHERE tenant_id = ? AND short_code = ?", tenantId, code);
        LocalDateTime expireTime = req.expireTime() != null ? req.expireTime() : toLocalDateTime(current.get("expire_time"));
        Integer accessLimit = req.accessLimit() != null ? req.accessLimit() : toInteger(current.get("access_limit"));
        Integer rateLimit = req.rateLimitPerMinute() != null
                ? req.rateLimitPerMinute() : toInteger(current.get("rate_limit_per_minute"));
        int status = req.status() != null ? req.status() : ((Number) current.get("status")).intValue();

        long version = nextRouteVersion(code);
        RouteConfig snapshot = new RouteConfig((String) current.get("origin_url"), tenantId,
                ((Number) current.get("redirect_type")).intValue(), expireTime, accessLimit, status, version,
                (String) current.get("channel_id"), (String) current.get("campaign_id"),
                (String) current.get("promoter_id"), rateLimit);
        String routeJson = writeJson(snapshot);

        txWriter.updateCommitted(tenantId, code, expireTime, accessLimit, status, rateLimit, routeJson);
        jdbc.update("UPDATE link_route SET route_json = CAST(? AS JSON), version = ? WHERE short_code = ?",
                routeJson, version, code);
        cacheRoute(code, routeJson);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("short_code", code);
        data.put("expire_time", expireTime);
        data.put("access_limit", accessLimit);
        data.put("rate_limit_per_minute", rateLimit);
        data.put("status", status);
        data.put("route_version", version);
        return data;
    }

    /** 归属反查（DESIGN 6.4）：code_tenant_index 命中且租户一致才放行 */
    void requireOwnership(long tenantId, String code) {
        List<Long> owners = jdbc.queryForList(
                "SELECT tenant_id FROM code_tenant_index WHERE short_code = ?", Long.class, code);
        if (owners.isEmpty()) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND, HttpStatus.NOT_FOUND, "short code not found: " + code);
        }
        if (owners.get(0) != tenantId) {
            throw new BizException(ErrorCode.LINK_FORBIDDEN, HttpStatus.FORBIDDEN,
                    "code belongs to another tenant");
        }
    }

    private String allocateCode(String custom) {
        if (custom != null && !custom.isBlank()) {
            String code = custom.trim();
            if (!CODE_PATTERN.matcher(code).matches()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                        "custom short_code must match ^[0-9a-zA-Z]{4,12}$");
            }
            if (RESERVED_WORDS.contains(code.toLowerCase(Locale.ROOT))) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                        "short_code is reserved: " + code);
            }
            if (codeExistsInDb(code) || !claimCodeQuietly(code)) {
                throw new BizException(ErrorCode.SHORT_CODE_TAKEN, HttpStatus.CONFLICT,
                        "custom short code already used: " + code);
            }
            return code;
        }
        for (int attempt = 0; attempt < MAX_CODE_RETRIES; attempt++) {
            String code = pool.lease(1).get(0);
            if (!codeExistsInDb(code) && claimCodeQuietly(code)) {
                return code;
            }
            log.warn("[link] 池码已被占用，重新租用 attempt={} code={}", attempt + 1, code);
        }
        throw new BizException(ErrorCode.SHORT_CODE_TAKEN, HttpStatus.CONFLICT, "cannot allocate a free code");
    }

    private boolean codeExistsInDb(String code) {
        return !jdbc.queryForList("SELECT 1 FROM code_tenant_index WHERE short_code = ?", Integer.class, code).isEmpty();
    }

    private boolean claimCodeQuietly(String code) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("sl:code:" + code, "claimed"));
    }

    private void releaseCodeClaim(String code) {
        redis.delete("sl:code:" + code);
    }

    /** route+index 同 short_code 分片轴：同库单次往返序列完成 */
    private void insertRouteAndIndex(String code, long tenantId, String routeJson) {
        jdbc.update("INSERT INTO link_route (short_code, route_json, version) VALUES (?, CAST(? AS JSON), 1)",
                code, routeJson);
        jdbc.update("INSERT INTO code_tenant_index (short_code, tenant_id) VALUES (?, ?)", code, tenantId);
    }

    private void cacheRoute(String code, String routeJson) {
        Duration ttl = CACHE_BASE_TTL.plusMinutes(random.nextInt(120));
        redis.opsForValue().set("sl:r:" + code, routeJson, ttl);
    }

    private void consumeFromPool(String code) {
        jdbc.update("UPDATE short_code_pool SET status = 2 WHERE short_code = ? AND status = 1", code);
    }

    private long nextRouteVersion(String code) {
        List<Long> versions = jdbc.queryForList("SELECT version FROM link_route WHERE short_code = ?", Long.class, code);
        return versions.isEmpty() ? 1 : versions.get(0) + 1;
    }

    private String shortUrl(String code) {
        return "https://" + baseDomain + "/" + code;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 频控阈值（DESIGN 5.3 第 2 层）：null = 用 jump 的全局默认；给了就必须是正数。 */
    private static void validateRateLimit(Integer rateLimitPerMinute) {
        if (rateLimitPerMinute != null && rateLimitPerMinute <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                    "rate_limit_per_minute must be positive");
        }
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        throw new IllegalStateException("unexpected expire_time type: " + value.getClass());
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private String writeJson(RouteConfig snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("route_json serialization failed", e);
        }
    }
}
