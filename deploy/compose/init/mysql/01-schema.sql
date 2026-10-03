-- Xingyan ShortLink Platform — 数据面/管理面表结构（DESIGN 4.1 / 4.2，M1-02）
-- 幂等：全部 IF NOT EXISTS，可对已灌数据的实例重复执行。
-- 分片演示：xsl_00 / xsl_01 结构完全一致，ShardingSphere 按分片键哈希路由（4.1）；
--          不分片的字典表（租户、域名池）落 xsl_base。
-- 全新环境由 docker-entrypoint-initdb.d 自动执行；已有数据卷用 `make db-init` 补建。

CREATE DATABASE IF NOT EXISTS xsl_00 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS xsl_01 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE IF NOT EXISTS xsl_base DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- ─── 按 short_code 哈希分片（数据面跳转 / 全局反查 / 短码池）───

CREATE TABLE IF NOT EXISTS xsl_00.link_route (
  short_code  VARCHAR(12)  NOT NULL,
  route_json  JSON         NOT NULL,
  version     BIGINT       NOT NULL DEFAULT 1,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (short_code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS xsl_00.code_tenant_index (
  short_code  VARCHAR(12)  NOT NULL,
  tenant_id   BIGINT       NOT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (short_code),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS xsl_00.short_code_pool (
  short_code  VARCHAR(12)  NOT NULL,
  namespace   INT          NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0-空闲 1-已租用 2-已消耗',
  lease_owner VARCHAR(64)  NULL,
  lease_time  DATETIME     NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (short_code),
  KEY idx_ns_status (namespace, status)
) ENGINE=InnoDB;

-- ─── 按 tenant_id 哈希分片（管理面元数据 / 规则 / 审计 / outbox）───

CREATE TABLE IF NOT EXISTS xsl_00.short_link (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  short_code    VARCHAR(12)  NOT NULL,
  origin_url    VARCHAR(2048) NOT NULL,
  tenant_id     BIGINT       NOT NULL,
  channel_id    VARCHAR(64)  NULL,
  campaign_id   VARCHAR(64)  NULL,
  promoter_id   VARCHAR(64)  NULL,
  redirect_type TINYINT      NOT NULL DEFAULT 1 COMMENT '1-302 2-中间页（不支持 301，见 5.2）',
  expire_time   DATETIME     NULL,
  access_limit  INT          NULL,
  rate_limit_per_minute INT  NULL COMMENT '同一客户端 IP 每分钟可点数；NULL=用 jump 全局默认（DESIGN 5.3 第 2 层）',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-正常 1-停用 2-封禁 3-待审核',
  review_remark VARCHAR(256) NULL,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_short_code (short_code),
  KEY idx_tenant_campaign (tenant_id, campaign_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS xsl_00.route_rule (
  id              BIGINT       NOT NULL,
  short_code      VARCHAR(12)  NOT NULL,
  tenant_id       BIGINT       NOT NULL COMMENT '分片键冗余，保证与 short_link 同分片',
  priority        INT          NOT NULL,
  condition_type  VARCHAR(32)  NULL COMMENT 'ua_container/device/geo_province/time_window',
  condition_op    VARCHAR(16)  NULL COMMENT 'eq/in/gt/between',
  condition_value VARCHAR(128) NULL,
  target_url      VARCHAR(2048) NOT NULL,
  target_type     VARCHAR(16)  NOT NULL COMMENT 'h5/app_scheme/wx_urllink/intermediate',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_code_priority (short_code, priority)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS xsl_00.audit_log (
  id          BIGINT       NOT NULL,
  tenant_id   BIGINT       NOT NULL,
  operator    VARCHAR(64)  NOT NULL,
  action      VARCHAR(64)  NOT NULL,
  target      VARCHAR(128) NULL,
  detail      VARCHAR(2048) NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tenant_time (tenant_id, create_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS xsl_00.outbox (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id  VARCHAR(64)  NOT NULL COMMENT '=short_code',
  tenant_id     BIGINT       NOT NULL COMMENT '分片键',
  event_type    VARCHAR(32)  NOT NULL,
  payload_json  JSON         NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待投递 1-已投递 2-失败',
  retry_count   INT          NOT NULL DEFAULT 0,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_status_create (status, create_time)
) ENGINE=InnoDB;

-- xsl_01 与 xsl_00 表结构完全一致
CREATE TABLE IF NOT EXISTS xsl_01.link_route        LIKE xsl_00.link_route;
CREATE TABLE IF NOT EXISTS xsl_01.code_tenant_index LIKE xsl_00.code_tenant_index;
CREATE TABLE IF NOT EXISTS xsl_01.short_code_pool   LIKE xsl_00.short_code_pool;
CREATE TABLE IF NOT EXISTS xsl_01.short_link        LIKE xsl_00.short_link;
CREATE TABLE IF NOT EXISTS xsl_01.route_rule        LIKE xsl_00.route_rule;
CREATE TABLE IF NOT EXISTS xsl_01.audit_log         LIKE xsl_00.audit_log;
CREATE TABLE IF NOT EXISTS xsl_01.outbox            LIKE xsl_00.outbox;

-- ─── 不分片的字典表 ───

CREATE TABLE IF NOT EXISTS xsl_base.tenant (
  tenant_id     BIGINT       NOT NULL,
  name          VARCHAR(128) NOT NULL,
  type          TINYINT      NOT NULL COMMENT '1-主办方 2-项目 3-内部',
  quota_links   INT          NOT NULL DEFAULT 1000,
  quota_qps     INT          NOT NULL DEFAULT 100,
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-正常 1-冻结',
  api_key       VARCHAR(64)  NOT NULL,
  api_key_status TINYINT     NOT NULL DEFAULT 0 COMMENT '0-有效 1-轮换中 2-吊销',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id),
  UNIQUE KEY uk_api_key (api_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS xsl_base.domain_pool (
  domain      VARCHAR(128) NOT NULL,
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0-可用 1-被举报 2-封禁',
  weight      INT          NOT NULL DEFAULT 100,
  switch_time DATETIME     NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (domain)
) ENGINE=InnoDB;

-- ─── 幂等补列（M2 起）───
-- 上面的 CREATE TABLE IF NOT EXISTS 对"已灌数据的老卷"不会补列，而 MySQL 8 没有
-- ADD COLUMN IF NOT EXISTS，所以逐列用 information_schema 守卫 + PREPARE。
-- 新库：CREATE 已带列，这里全部跳过；老库：`make db-init` 重放本文件即补齐。

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = 'xsl_00' AND TABLE_NAME = 'short_link'
                  AND COLUMN_NAME = 'rate_limit_per_minute') = 0,
  'ALTER TABLE xsl_00.short_link ADD COLUMN rate_limit_per_minute INT NULL AFTER access_limit', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = 'xsl_01' AND TABLE_NAME = 'short_link'
                  AND COLUMN_NAME = 'rate_limit_per_minute') = 0,
  'ALTER TABLE xsl_01.short_link ADD COLUMN rate_limit_per_minute INT NULL AFTER access_limit', 'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
