-- Redis 窗口频控（DESIGN 5.3 第 2 层 / 4.3 键 sl:rl:{code}:{iphash}:{slot}）
-- 单脚本内完成 INCR + 首次 EXPIRE：分两次调用会有"INCR 成功但 EXPIRE 丢失 → 键永不过期"的竞态。
--
-- 这个文件之所以存在（而不是内联在 RateLimiter.java 里）：bench/run-ratelimit-bench.sh 要用
-- redis-benchmark 直接跑它来出 DESIGN 8.1-A 的「频控 Lua ≥3 万次/s」基线。抽成文件后，
-- 被测的就是线上这一份，不会各写一遍然后悄悄漂移。
--
-- KEYS[1] = sl:rl:{code}:{ipHash}:{slot}   ARGV[1] = 窗口秒数（只在首次创建键时用）
-- 返回：窗口内的累计次数（含本次）；阈值判定在调用方，脚本只负责"原子地数"。
local n = redis.call('INCR', KEYS[1])
if n == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return n
