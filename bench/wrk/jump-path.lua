-- wrk 请求脚本：从热码文件随机取一个 short_code，打 /s/{code}（真实入口口径）。
-- 两点必须显式处理，否则测的不是同一条链路：
--   1) wrk 连的是 127.0.0.1，默认 Host 头会变成 127.0.0.1 → nginx default server 直接 444；
--      这里固定 Host 为已登记域名，走 xyN.test 的 server_name 分支。
--   2) utm_source 带上以驱动归因拼接路径（AttributionParamMerger 每请求都跑）。
local codes = {}
local path = os.getenv("BENCH_CODES_FILE") or "bench/.tmp/hot-codes.txt"
for line in io.lines(path) do
  if line ~= "" then codes[#codes + 1] = line end
end
assert(#codes > 0, "热码文件为空，先跑 bench/setup-hot-links.sh")

wrk.method = "GET"
wrk.headers["Host"] = os.getenv("BENCH_HOST") or "xy1.test"
wrk.headers["Connection"] = "keep-alive"

math.randomseed(os.time())

request = function()
  return wrk.format(nil, "/s/" .. codes[math.random(#codes)] .. "?utm_source=wrk")
end

-- 状态判定不放在这里的 Lua 钩子里：本机 wrk 4.2.0 的 response() 钩子实测不会被调用
-- （done() 里计数恒为 0，而 summary.requests 正常）——所以"全是 302、没有 5xx"由
-- run-jump-bench.sh 用服务侧计数器交叉验证，比客户端钩子更可信。
