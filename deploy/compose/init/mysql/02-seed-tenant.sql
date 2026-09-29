-- M1-04 seed：mock 租户（api_key 即 HMAC 共享密钥）与本地多域名池（DESIGN 9.4 / 9.5）
-- 幂等：ON DUPLICATE KEY UPDATE 只刷新非敏感字段，不覆盖运行期状态。

INSERT INTO xsl_base.tenant (tenant_id, name, type, quota_links, quota_qps, status, api_key, api_key_status)
VALUES
  (1001, '模拟主办方A·星演主场', 1, 1000, 100, 0, 'xy-key-alice-001', 0),
  (1002, '模拟项目B·粉丝裂变',   2,  500, 100, 0, 'xy-key-bob-002',   0),
  (1003, '内部C·压测通道',       3,  100, 500, 0, 'xy-key-carol-003', 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO xsl_base.domain_pool (domain, status, weight)
VALUES
  ('xy1.test', 0, 100),
  ('xy2.test', 0, 100),
  ('xy3.test', 0, 100)
ON DUPLICATE KEY UPDATE status = VALUES(status);
