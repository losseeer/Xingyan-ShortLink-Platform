-- Xingyan ShortLink Platform — mock 宣发平台的落库（DESIGN 7.3/附录 B，M3-03）
-- 幂等：全部 IF NOT EXISTS，可对已有数据卷重复执行（`make db-init` 会把 init/mysql/*.sql 全重放一遍）。
-- 独立库：这是"外部平台"的等价物，不与 xsl_00/xsl_01 的业务分片共库，也不经 ShardingSphere。

CREATE DATABASE IF NOT EXISTS xsl_mock DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- 批次台账：一个 batch_id 一行。有这张表，"平台到底收到了哪几批"才是能查的问题，
-- 而不是只能相信发送端的日志。times_received 让幂等真的被观测到（>1 就是重投被吸收了）。
--
-- ⚠ 两把时钟，别拿来互相比较：
--   · window_from / window_to / attribution_stat.minute 走的是**点击侧的墙上时钟**（应用容器 Asia/Shanghai，
--     而 ClickHouse 会话时区是 UTC、click_time 列里存的正是上海墙上时钟串——约定见 DESIGN 4.2），
--     它们表示"这一段流量发生在哪个分钟"；
--   · first_received_at / last_received_at 由 MySQL 的 CURRENT_TIMESTAMP 生成，本机 MySQL 容器是 **UTC**，
--     它们表示"这台服务器什么时候收到的"。
-- 两件事本来就不同源，硬要对齐反而造假；要换算请显式 +8h，不要改列的默认值来"让它们看起来一致"。
CREATE TABLE IF NOT EXISTS xsl_mock.attribution_batch (
  batch_id          VARCHAR(64)  NOT NULL COMMENT 'xsl-{tenant}-{from}-{to}-{内容 hash 前 10 位}',
  tenant_id         BIGINT       NOT NULL,
  window_from       DATETIME     NOT NULL COMMENT '窗口起点（含，分钟对齐）',
  window_to         DATETIME     NOT NULL COMMENT '窗口终点（不含）',
  item_count        INT          NOT NULL,
  checksum          CHAR(40)     NOT NULL COMMENT 'sha1(规范化 items)，服务端重算过才落到这里',
  times_received    INT          NOT NULL DEFAULT 1 COMMENT '同一 batch_id 到达次数（幂等吸收的观测）',
  first_received_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_received_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (batch_id),
  KEY idx_tenant_window (tenant_id, window_from)
) ENGINE=InnoDB;

-- 按桶的最终值：主键就是聚合轴，所以迟到变全后的新版是**覆盖**而不是累加（M3-03 口径第 5 条）。
-- 留 batch_id 是为了能从任一桶倒查"这一行的数是哪一批推来的"。
CREATE TABLE IF NOT EXISTS xsl_mock.attribution_stat (
  tenant_id   BIGINT      NOT NULL,
  campaign_id VARCHAR(64) NOT NULL,
  channel_id  VARCHAR(64) NOT NULL,
  minute      DATETIME    NOT NULL,
  pv          BIGINT      NOT NULL,
  uv          BIGINT      NOT NULL,
  valid_pv    BIGINT      NOT NULL,
  bot_pv      BIGINT      NOT NULL,
  batch_id    VARCHAR(64) NOT NULL,
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, campaign_id, channel_id, minute),
  KEY idx_window (minute, campaign_id)
) ENGINE=InnoDB;

-- 假订单（DESIGN 附录 B 的落点，M3-04）：订单侧带回来的归因字段。
-- 表名带 mock_ 前缀是没办法的事：order 是 MySQL 保留字，裸用它得在每条 SQL 里加反引号。
-- 幂等键就是订单号本身——真实形态里 order_id 本来就是购票系统生成的，用它最自然；
-- 不做幂等的话"用户双击提交"会把订单数算虚高，端到端对账 ② 的分母当场失真（times_submitted 让这个吸收可观测）。
-- 这一跳（购票页后端 → 平台）两端都在 mock 侧，所以不鉴权，理由写在 DESIGN 附录 B 的 v2.13.0 那段。
CREATE TABLE IF NOT EXISTS xsl_mock.mock_order (
  order_id         VARCHAR(64) NOT NULL COMMENT '调用方（购票侧）生成的订单号，同时是幂等键',
  click_id         VARCHAR(64) NOT NULL COMMENT '附录 B 的对账键 = 跳转 URL 上的 xy_click_id',
  channel_id       VARCHAR(64) NOT NULL DEFAULT '',
  campaign_id      VARCHAR(64) NOT NULL DEFAULT '',
  promoter_id      VARCHAR(64) NOT NULL DEFAULT '',
  first_click_time DATETIME    NULL COMMENT '首次归因口径由平台侧配置（附录 B），mock 只负责把带回来的值存下',
  last_click_time  DATETIME    NULL,
  times_submitted  INT         NOT NULL DEFAULT 1 COMMENT '同一 order_id 被提交几次（重复提交不重复计入的观测）',
  create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  KEY idx_click (click_id),
  KEY idx_campaign (campaign_id, create_time)
) ENGINE=InnoDB;
