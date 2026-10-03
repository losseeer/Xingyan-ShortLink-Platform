#!/usr/bin/env bash
# M2-14：DESIGN 8.1-A 唯一未测项「频控 Lua ≥ 3 万次/s」的基线。
#
# 方法照 8.1-A 写死的口径来：redis-benchmark 跑自定义脚本。脚本正文不是抄的第二份，
# 是从 sl-jump/src/main/resources/lua/rate_limit_window.lua 原样取来（jump 运行时加载的也是这份），
# 保证"测的即跑的"。
#
# 两条口径声明（写进报告，别把这条基线读成端到端能力）：
#   ① benchmark 在 redis 容器内跑 loopback，不含 jump→Redis 的 compose 网络与 Lettuce 客户端开销；
#      端到端容量看 bench/run-jump-loadcurve.sh（那边频控确实在被测路径上）。
#   ② -P>1 是流水线，测的是 Redis 单线程事件循环的批量上限；-P 1 才接近"一问一答"的真实等待成本，
#      所以判定只看 -P 1 的行。
source "$(dirname "$0")/lib-bench.sh"

LUA_SRC="$REPO_ROOT/sl-jump/src/main/resources/lua/rate_limit_window.lua"
LUA_IN=/tmp/xsl-rate-limit-window.lua
KEYS=${RL_KEYS:-200000}                  # -r 键空间：模拟大量 (code, ip) 组合，避免热键聚堆
CONNS=${RL_CONNS:-"1 10 50"}             # 连接数档位
TARGET=${RL_TARGET:-30000}               # DESIGN 8.1-A 目标：≥3 万次/s
N_PER_CONN=${RL_N_PER_CONN:-30000}

[[ -f "$LUA_SRC" ]] || { echo "找不到频控脚本：$LUA_SRC"; exit 1; }
$COMPOSE exec -T redis redis-cli ping > /dev/null || { echo "redis 不可达，先 make up"; exit 1; }

SHA=$(shasum -a 256 "$LUA_SRC" | awk '{print $1}')
docker cp "$LUA_SRC" xsl-redis-1:"$LUA_IN" > /dev/null
# 原样送脚本正文（含注释）：jump 侧 DefaultRedisScript 加载的就是这份文件本身，
# 连注释一起送才和它算出的 SHA 一致——"测的即跑的"包括字节级。
SCRIPT=$(cat "$LUA_SRC")

REPORT=$(report_new "m2-14-ratelimit-lua")
echo "报告：$REPORT"

{
  echo "- 被测脚本：\`sl-jump/src/main/resources/lua/rate_limit_window.lua\`（sha256 \`${SHA:0:16}…\`，jump 运行时加载同一份）"
  echo "- 调用形态：\`EVAL <脚本> 1 sl:rl:bench:<run>:<__rand_int__> <ARGV1=1>\`，键空间 $KEYS，负载器为 redis 容器内 \`redis-benchmark\`"
  echo "- 窗口参数在这里取 **1 秒**只为让压测键自然过期（否则实例里会留 20 万个键）；"
  echo "  EXPIRE 的数值不参与任何判定，阈值比较在 jump 侧，脚本做的事一个字节没改。"
  echo "- 同机声明：benchmark 与 Redis 同容器/同宿主，数字是**脚本本身的吞吐**，不含 jump→Redis 的网络与客户端开销。"
  echo
  echo "| 连接数 | 请求数 | 吞吐 (req/s) | 平均延迟 (ms) | p99 (ms) | 判定（目标 ≥ $TARGET/s） |"
  echo "|---|---|---|---|---|---|"
} >> "$REPORT"

PASS=1
for C in $CONNS; do
  N=$((C * N_PER_CONN))
  OUT=$($COMPOSE exec -T redis redis-benchmark -n "$N" -c "$C" -P 1 -r "$KEYS" \
        eval "$SCRIPT" 1 "sl:rl:bench:$(date +%s):__rand_int__" 1 GET 2>&1)
  QPS=$(echo "$OUT" | awk '/throughput summary/ {print int($3)}')
  # redis-benchmark 的延迟块是"表头行 + 数值行"两张量，不能用 `avg: X` 那种形式去抠
  read -r AVG P99 <<< "$(echo "$OUT" | awk '/^ +avg +min +p50/ {getline v; split(v, a, /[ \t]+/); print a[2], a[6]; exit}')"
  if [[ -z "$QPS" ]]; then
    echo "| $C | $N | 采样失败 | — | — | ❌ 未跑通 |" >> "$REPORT"
    echo "  c=$C 失败：$(echo "$OUT" | tail -2 | tr '\n' ' ')"
    PASS=0
    continue
  fi
  OK=$(python3 -c "import sys;print('yes' if float(sys.argv[1]) >= $TARGET else 'no')" "$QPS")
  VERDICT=$([[ "$OK" == "yes" ]] && echo '✅ 达标' || echo '❌ 低于目标')
  echo "| $C | $N | $QPS | ${AVG:-?} | ${P99:-?} | $VERDICT |" >> "$REPORT"
  echo "  c=$C → $QPS req/s（avg ${AVG:-?}ms、p99 ${P99:-?}ms）"
done

{
  echo
  echo "## 判读"
  echo
  echo "- **目标本身没写连接数口径**，所以这里三档都给，不挑最好看的那一个报："
  echo "  单连接不流水线是 Redis 单线程事件循环的\"一问一答\"上限； jump 侧每个请求发一次 EVAL，"
  echo "  但两个实例各自持有连接池，真实形态介于\"单连接\"与\"多连接\"之间，因此两档都要看。"
  echo "- 这条基线只回答\"频控脚本自身扛不扛得住\"。端到端（含 compose 网络、Lettuce、每请求一次 EVAL"
  echo "  加路由缓存读）看同日的 \`m2-14-jump-loadcurve\` 存档：跳转上限由事件链决定（ITER-M2 §3），"
  echo "  频控不在瓶颈上。"
  echo "- 目标出处：DESIGN 8.1-A「频控 Lua ≥ 3 万次/s（redis-benchmark 自定义脚本）」。"
} >> "$REPORT"

sed -n '/^| 连接数/,/^$/p' "$REPORT"
echo "已写入 $REPORT"
exit $((PASS == 0 ? 1 : 0))
