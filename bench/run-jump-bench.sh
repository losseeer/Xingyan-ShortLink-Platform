#!/usr/bin/env bash
# M1-11 主测：跳转吞吐与延迟（DESIGN 8.1-A 口径 wrk -t8 -c200 -d60s，连续 3 轮）。
# 入口是 nginx :80（不是直连 jump），命中路径为 Caffeine(L1)/Redis(L2)，
# 每轮前后取 xsl_jump_route_lookup_total 的分层计数，命中率按 (local+redis)/总查询算。
# 达标与否照实写进报告，不做四舍五入式美化。
source "$(dirname "$0")/lib.sh"

ROUNDS=${ROUNDS:-3}
DURATION=${DURATION:-60s}
THREADS=${THREADS:-8}
CONNS=${CONNS:-200}
CODES_FILE="${BENCH_CODES_FILE:-$TMP_DIR/hot-codes.txt}"
export BENCH_CODES_FILE="$CODES_FILE"

[[ -s "$CODES_FILE" ]] || { echo "热码文件不存在：$CODES_FILE（先 bash bench/setup-hot-links.sh）"; exit 1; }
command -v wrk >/dev/null || { echo "wrk 未安装（brew install wrk）"; exit 1; }

REPORT=$(report_new "m1-11-jump")
echo "报告：$REPORT"
{
  echo "## 参数"
  echo
  echo "- 命令：\`wrk -t$THREADS -c$CONNS -d$DURATION --latency -s bench/wrk/jump-path.lua http://127.0.0.1:80\`"
  echo "- 目标：nginx :80 → upstream xsl_jump（jump-1 + jump-2 轮询），Host=xy1.test，路径 \`/s/{code}?utm_source=wrk\`"
  echo "- 热码：$(wc -l < "$CODES_FILE" | tr -d ' ') 个（bench/setup-hot-links.sh 产出，已预热 L1/L2）"
  echo "- 客户端与服务同宿主（Docker VM 10 核），按 DESIGN 8.1-A 要求以保守口径报告"
  echo
  echo "## 三轮结果"
} >> "$REPORT"

for r in $(seq "$ROUNDS"); do
  echo "── 第 $r/$ROUNDS 轮（$DURATION）──"
  BEFORE=$(snap_routes)
  BEFORE_GC=$(snap_gc)
  SAMPLER="$TMP_DIR/stats-r$r.txt"; : > "$SAMPLER"
  # 负载进行中间歇采样 docker stats（结束后采到的是空载，指认不了瓶颈）
  ( while sleep 10; do docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
      | grep -E 'jump|nginx' >> "$SAMPLER"; done ) & SAMPLER_PID=$!
  BEFORE_FOUND=$(jump_metric 'xsl_jump_requests_total.*outcome="found"')
  BEFORE_ALL=$(jump_metric 'xsl_jump_requests_total')
  OUT=$(wrk -t"$THREADS" -c"$CONNS" -d"$DURATION" --latency \
        -s "$BENCH_DIR/wrk/jump-path.lua" http://127.0.0.1:80 2>&1)
  AFTER=$(snap_routes)
  AFTER_GC=$(snap_gc)
  kill "$SAMPLER_PID" 2>/dev/null; wait "$SAMPLER_PID" 2>/dev/null
  AFTER_FOUND=$(jump_metric 'xsl_jump_requests_total.*outcome="found"')
  AFTER_ALL=$(jump_metric 'xsl_jump_requests_total')
  REQ=$(python3 -c "import re,sys;print(re.search(r'(\\d+) requests in', sys.argv[1]).group(1))" "$OUT")
  NON302=$(( (AFTER_ALL - BEFORE_ALL) - (AFTER_FOUND - BEFORE_FOUND) ))
  echo "$OUT" | tail -12
  {
    echo
    echo "### 第 $r 轮"
    echo
    echo '```'
    echo "$OUT"
    echo '```'
    echo
    echo "- 服务侧交叉验证：\`outcome=found\` 增 $((AFTER_FOUND - BEFORE_FOUND))、请求总数增 $((AFTER_ALL - BEFORE_ALL))、客户端完成 $REQ 次 → 非 302 响应 **$NON302** 次"
    echo "- 分层计数 前：\`$BEFORE\`"
    echo "- 分层计数 后：\`$AFTER\`"
    echo "- GC 停顿：前 \`$BEFORE_GC\` / 后 \`$AFTER_GC\`"
    echo "- 负载进行中采样（每 10s）："
    echo
    echo '```'
    cat "$SAMPLER"
    echo '```'
    python3 - "$BEFORE" "$AFTER" <<'PY'
import re, sys
def parse(s):
    return {k: int(v) for k, v in re.findall(r"(\w+)=(-?\d+)", s)}
b, a = parse(sys.argv[1]), parse(sys.argv[2])
d = {k: a[k] - b[k] for k in a}
lookups = d.get("local", 0) + d.get("redis", 0) + d.get("db", 0) + d.get("miss", 0)
hits = d.get("local", 0) + d.get("redis", 0)
rate = 100.0 * hits / lookups if lookups else 0.0
print(f"- 本轮增量：local=+{d.get('local',0)} redis=+{d.get('redis',0)} db=+{d.get('db',0)} "
      f"miss=+{d.get('miss',0)} → 缓存命中率 **{rate:.3f}%**（目标 >99.5%）")
PY
  } >> "$REPORT"
  sleep 5   # 轮间歇一下，避免把上一轮的排队尾巴算进下一轮
done

{
  echo
  echo "## 观测与瓶颈"
  echo
  docker stats --no-stream --format '- {{.Name}}: CPU {{.CPUPerc}} / MEM {{.MemUsage}}' | grep -E 'jump|nginx|redis'
  echo "- jump 实例请求计数（含 outcome 分布）：$(jump_metric 'xsl_jump_requests_total')（两实例累计）"
} >> "$REPORT"
echo "已写入 $REPORT"
