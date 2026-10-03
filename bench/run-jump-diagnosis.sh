#!/usr/bin/env bash
# M2-00 诊断：把 P99 的尾巴归因到具体某一层，而不是继续猜 GC / 网络 / JVM。
# 三层时间同时采样，缺口在哪、问题就在哪：
#   ① wrk（客户端视角端到端）  ② nginx 日志 rt/uct/urt（逐请求精确）  ③ jump 服务端耗时（sum/count 增量=精确均值）
# 判读：
#   ② urt ≈ ① 而 ③ ≪ ②   → 上游应答里含排队时间，jump 自测处理很快 → 排队在连接/线程接受层
#   ③ ≈ ② ≈ ①             → 业务处理本身慢，查实现路径
#   ② urt ≪ ①              → 尾巴在 nginx↔客户端之间（loopback、wrk 与容器同机争 CPU）
source "$(dirname "$0")/lib-bench.sh"

CONNS=${DIAG_CONNS:-200}
THREADS=${DIAG_THREADS:-8}
DURATION=${DIAG_DURATION:-40s}
SECS=${DURATION%s}
export BENCH_CODES_FILE="${BENCH_CODES_FILE:-$TMP_DIR/hot-codes.txt}"
[[ -s "$BENCH_CODES_FILE" ]] || { echo "先跑 bench/setup-hot-links.sh"; exit 1; }

REPORT=$(report_new "m2-00-jump-diagnosis-c$CONNS")
echo "报告：$REPORT"

# 两个坑：① 该系列标签紧跟指标名、中间没空格，`$1=="http_server_requests_seconds_count"` 永远不成立
#         （本机实测读成"0 次请求、均值 0ms"），必须用 index() 前缀匹配；
#      ② Micrometer 的分位数是 10 分钟衰减窗口，含上一轮负载，只当趋势看；
#         服务端耗时用 sum/count 增量算，那才是精确值。
jump_snap() { # 输出 "<count> <sum>"，两实例 /s/{code} 系列合并
  curl -s -m 5 "http://127.0.0.1:8020/actuator/prometheus" "http://127.0.0.1:8022/actuator/prometheus" \
    | awk '
        index($1, "http_server_requests_seconds_count{") == 1 && $0 ~ /uri="\/s\/\{code\}"/ && $0 !~ /quantile/ { c += $2 }
        index($1, "http_server_requests_seconds_sum{")   == 1 && $0 ~ /uri="\/s\/\{code\}"/ { s += $2 }
        END { printf "%d %.6f", c, s }'
}
jump_p() { # jump_p <quantile> —— 两实例取较大者（仅趋势用）
  curl -s -m 5 "http://127.0.0.1:8020/actuator/prometheus" "http://127.0.0.1:8022/actuator/prometheus" \
    | grep 'http_server_requests_seconds{' | grep 'uri="/s/{code}"' | grep "quantile=\"$1\"" \
    | awk '{print $2+0}' | sort -g | tail -1
}

read -r BEFORE_CNT BEFORE_SUM <<< "$(jump_snap)"
# 不要数 /var/log/nginx/access.log 的行数：镜像里它是 /dev/stdout 的软链接，
# `wc -l < access.log` 永远读不到 EOF（本机实测挂死两次）。日志一律走 docker logs。
( sleep $((SECS - 6)); { jump_p 0.5; jump_p 0.99; jump_p 0.999; } > "$TMP_DIR/diag-q.txt"; ) &
SAMPLER=$!

wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" --latency -s "$BENCH_DIR/wrk/jump-path.lua" http://127.0.0.1:80 \
  > "$TMP_DIR/diag-wrk.txt" 2>&1
wait "$SAMPLER" 2>/dev/null

read -r AFTER_CNT AFTER_SUM <<< "$(jump_snap)"
QTXT=$(tr '\n' ' ' < "$TMP_DIR/diag-q.txt" 2>/dev/null)

{
  echo "- 负载：\`wrk -t$THREADS -c$CONNS -d$DURATION\`（与 M1-11 同入口、同热码集，只加三层观测）"
  echo
  echo "## ① 客户端（wrk 端到端）"
  echo
  echo '```'
  grep -E "Latency +[0-9]|Requests/sec|^ +50%|^ +75%|^ +90%|^ +99%|requests in|Socket errors" "$TMP_DIR/diag-wrk.txt"
  echo '```'
  echo
  echo "## ③ jump 服务端耗时（两实例合并）"
  echo
  python3 - "$BEFORE_CNT" "$BEFORE_SUM" "$AFTER_CNT" "$AFTER_SUM" "$QTXT" <<'PY'
import sys
bc, bs, ac, asu, qs = (float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5])
n = max(ac - bc, 1.0)
print(f"- **精确均值 {((asu - bs) / n) * 1000:.3f} ms**（本轮 {int(ac - bc)} 次服务端处理：请求进 servlet 到发出响应）")
try:
    p50, p99, p999 = (float(x) * 1000 for x in qs.split()[:3])
    print(f"- 分位数（10 分钟衰减窗口，含本轮之前负载，**只当趋势**）：p50 {p50:.2f} / p99 {p99:.2f} / p999 {p999:.2f} ms")
except Exception:
    print("- 分位数：本轮未采到")
PY
  echo
  echo "## ② nginx 日志分解（本轮窗口内逐请求，单位 ms）"
  echo
} >> "$REPORT"

docker logs --since "$((SECS + 5))s" xsl-nginx-1 2>&1 \
  | python3 "$BENCH_DIR/parse-nginx-timing.py" >> "$REPORT"

{
  echo
  echo "## 判读"
  echo
  echo "- 对照 ①②③：jump 均值远小于 urt → 时间花在 jump 的接受/排队侧而非业务代码；urt ≪ 端到端 → 同机争抢或 loopback 开销。"
  echo "- 本轮只观测不改配置；任何调参另开存档，保留治理前基线的可比性（ITER-M2 §5）。"
} >> "$REPORT"
sed -n '/## ③/,$p' "$REPORT" | head -20
