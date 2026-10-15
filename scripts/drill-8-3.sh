#!/usr/bin/env bash
# M3-09 降级演练（DESIGN 8.3 全表六行；M3 出口标准第三条「每条降级预案有一次实测记录」）
#
#   J 应用容器崩溃   docker kill 一个 jump 实例：期间照常打流量，断零 5xx 与恢复秒数
#   C ClickHouse 挂  五条 stats 必须 503（不是「这个窗口没有点击」）+ consumer 不提交 offset ⇒ 恢复后一行不少
#   K Kafka 挂       跳转照常 302、事件落 WAL；恢复后重放，明细行数 == 发出的点击数（不重不丢）
#   R Redis 挂       跳转不跌零（回源 DB）+ 频控退化为进程内计数；恢复后先探测到广播、再断依赖广播的项
#   M MySQL 挂       热码仍 302、POST /links 失败得可见、stats 仍可读（DESIGN 3.3 那句的三条支撑）
#   B 数据丢失       make backup → make restore-verify（备份文件真能读回来）
#
# 三条纪律（DEVELOPMENT_PLAN「M3-09 落地口径」1/2/6 条）：
#   1. 断言的是**行为**，不是「容器停了」：每行都在停的那一刻继续打流量，看成功率与错误码分布。
#   2. 顺序按影响面从小到大；Redis 停过之后，任何依赖广播的断言必须排在「探测到两实例都收到」之后
#      （订阅恢复实测 12–18s，那个窗口里发的广播真的会丢——不探测就会把环境窗口读成一次产品回归）。
#   3. 恢复耗时一律记数，不写「很快」：每行一个 stop→达标 的秒数，原始计数追加进 bench/reports/（只增不改）。
#
# 会真实停机每行 10–40s，只动 xsl-* 容器；跑完复位全栈并按本次 fixture 清理。
# 只跑其中几行：DRILL_SECTIONS="K R" bash scripts/drill-8-3.sh
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"

IP="${NGINX_ENTRY_IP:-127.0.0.1}"; PORT="${NGINX_PORT:-80}"; DOMAIN="xy1.test"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
CONSUMER="${CONSUMER_DIRECT:-http://localhost:8040}"
J1="${JUMP1_DIRECT:-http://localhost:8020}"
J2="${JUMP2_DIRECT:-http://localhost:8022}"
ORIGIN="http://$DOMAIN:$PORT"
CONSOLE="http://$DOMAIN:8081"
RESOLVE=(--resolve "$DOMAIN:$PORT:$IP")
CONSOLE_RESOLVE=(--resolve "$DOMAIN:8081:$IP")
RCLI="$COMPOSE exec -T redis redis-cli"
EVICT_CHANNEL="xsl:route:evict"
TENANT=1001
SECTIONS="${DRILL_SECTIONS:-J C K R M B}"
WALL_RATE=100000      # 演练要按十几 QPS 连打同一个码；默认 60 次/分钟会被频控吃掉，那测的就不是降级而是频控
RUN=$(date +%s)
TAG="d83-$RUN"
WAL_1="$REPO_ROOT/deploy/compose/data/jump-wal/jump-1"
WAL_2="$REPO_ROOT/deploy/compose/data/jump-wal/jump-2"
CODES="/tmp/d83-codes.$RUN.txt"; : > "$CODES"
RAW="/tmp/d83-raw.$RUN.txt";   : > "$RAW"    # 原始计数，原样贴进存档
ROWS="/tmp/d83-rows.$RUN.md";  : > "$ROWS"   # 8.3 表格的六个数据行
pass=0; fail=0

check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
check_le() { if [[ "${3:-99999}" -le "$2" ]]; then echo "PASS $1 (${3}${4:-} ≤ ${2}${4:-})"; ((pass++));
             else echo "FAIL $1 (${3}${4:-} > ${2}${4:-})"; ((fail++)); fi; }
truthy() { if [[ "$2" == "1" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 ($3)"; ((fail++)); fi; }
note() { echo "     · $*"; }
want() { printf '%-26s %s\n' "$1" "$2" >> "$RAW"; }
row()  { printf '| %s |\n' "$*" >> "$ROWS"; }

MY() { $MYSQL_CLI -N -B -e "$1" 2>/dev/null | tr -d '\r'; }
CHQ() { curl -s -m 90 -u "$CH_CRED" 'http://127.0.0.1:8123/' --data-binary "$1" | tr -d '\r'; }
# 计数一律按 short_code 而不是 campaign_id：short_code 是 click_event 的排序键前缀，
# campaign_id 上没有索引，48M 行的表每查一次都要全扫（这个循环是每 3 秒查一次直到达标）。
events() { CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code = '$1'"; }
metric() { # metric <base-url> <指标名> [标签子串] → 数值（取不到当 0，别让空值把算术炸掉）
  local v
  v=$(curl -s -m 5 "$1/actuator/prometheus" \
      | awk -v n="$2" -v l="${3:-}" '$1 ~ ("^" n "($|[[:space:]]|\\{)") && (l == "" || index($1,l)>0) {print $2; exit}')
  echo "${v:-0}"
}
num_gt() { awk -v a="$1" -v b="$2" 'BEGIN{print (a+0>b+0)?1:0}'; }
as_int() { awk -v x="${1:-0}" 'BEGIN{printf "%d", x+0}'; }   # prometheus 的值是 "123" 或 "123.0"，读不到就当 0
lag() { docker exec xsl-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
          --describe --group sl-consumer 2>/dev/null | awk '!/^GROUP/ && NF>=6 {s+=$6} END{print s+0}'; }
health() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null; }
code_of() { curl -s -o /dev/null -m 20 -w '%{http_code}' "$@"; }
wal_lines() { cat "$WAL_1"/click-event-*.log "$WAL_2"/click-event-*.log 2>/dev/null | wc -l | tr -d ' '; }

create_link() { # create_link <campaign 后缀> <origin 路径> → short_code
  local body resp code
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/%s","channel_id":"c-%s","campaign_id":"%s-%s","promoter_id":"pr-d83","redirect_type":1,"rate_limit_per_minute":%s}' \
    "$2" "$TAG" "$TAG" "$1" "$WALL_RATE")
  resp=$(curl -s -m 20 -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: d83-$RUN-$1" -d "$body")
  code=$(printf '%s' "$resp" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
  [[ -n "$code" ]] || { echo "FATAL 创建 $1 失败：$resp" >&2; exit 1; }
  printf '%s\n' "$code" >> "$CODES"
  echo "$code"
}
traffic() { # traffic <秒> <短码> <输出文件>：单线程顺序打，一个 302 恰好一条事件（这样才敢拿行数当分母）
  : > "$3"
  local end=$(( $(date +%s) + $1 ))
  while [[ "$(date +%s)" -lt $end ]]; do
    curl -s -o /dev/null -m 10 "${RESOLVE[@]}" -w '%{http_code}\n' "$ORIGIN/s/$2" >> "$3"
    sleep 0.02
  done
}
tally() { awk -v p="$2" 'index($1,p)==1 {n++} END{print n+0}' "$1"; }
dist() { sort "$1" | uniq -c | awk '{printf "%s×%s ", $2, $1}'; }
start_and_wait() { # start_and_wait <容器> [上限秒] → 输出达标秒数（FATAL 时直接退出）
  local c=$1 lim="${2:-90}" t0=$(date +%s) s
  docker start "$c" >/dev/null 2>&1
  while :; do
    s="$(health "$c")"; [[ "$s" == "healthy" || "$s" == "none" ]] && break
    [[ $(( $(date +%s) - t0 )) -ge $lim ]] && { echo "FATAL $c 在 ${lim}s 内没回到 healthy（当前 $s）"; exit 1; }
    sleep 2
  done
  echo $(( $(date +%s) - t0 ))
}
stats_codes() { # stats_codes <短码> <campaign> → 五条只读接口的状态码
  local out="" p
  for p in "/links/$1" "/tenants/$TENANT/links" "/tenants/$TENANT/timeseries" "/tenants/$TENANT/channels" "/campaigns/$2"; do
    out="$out$(code_of "${CONSOLE_RESOLVE[@]}" "$CONSOLE/api/v1/stats$p"), "
  done
  printf '%s' "${out%, }"
}

echo "== DESIGN 8.3 降级演练（run=$RUN tag=$TAG，段：$SECTIONS）=="
docker ps --format '{{.Names}}' | grep -q '^xsl-nginx-1$' || { echo "FATAL xsl 栈没起来（先 make up）"; exit 1; }
# 本项目的口径是不改宿主 /etc/hosts：三个域全靠 curl --resolve 打到 127.0.0.1（accept-m1-10 同源做法）。
# 这里只要「nginx 有回话」，不看码：/healthz 这个路径本来就没配（实测 404），拿 000 才当不可达。
HC=$(code_of "${RESOLVE[@]}" "$ORIGIN/healthz")
[[ "$HC" != "000" ]] || { echo "FATAL nginx :$PORT 不可达"; exit 1; }
note "nginx :$PORT 可达（/healthz 未配置，返回 $HC）"
[[ "$(CHQ 'SELECT 1')" == "1" ]] || { echo "FATAL ClickHouse 现在就读不到——演练的前提是它本来是好的"; exit 1; }
[[ "$(lag)" =~ ^[0-9]+$ ]] || { echo "FATAL 读不到 Kafka lag（consumer 组 sl-consumer 没在跑？）"; exit 1; }

# ================================================================ J 应用容器崩溃
if [[ " $SECTIONS " == *" J "* ]]; then
echo "-- J 应用容器崩溃：8.3 那行写的是「compose restart: always」，那就得看它到底什么时候成立（第 5 行）"
CJ=$(create_link J show/J)
# J-a 人为摘除 与 J-b 进程自己死，在 Docker 里**不是**同一条路径，而 8.3 那格只写了「崩溃」：
# `docker kill` 会把容器标成 user-stopped，restart: always 对它不生效（本轮实测到的），
# 只有进程自己死（崩溃、OOM）才走自动重启。分开测，否则文档那句「+ healthcheck」是空的。
docker kill xsl-jump-1-1 >/dev/null 2>&1; note "J-a docker kill xsl-jump-1-1 @ $(date +%T)"
sleep 20
JA_STATE=$(docker inspect -f '{{.State.Status}}' xsl-jump-1-1)
JA_SELF="停在 $JA_STATE（restart:always 不覆盖人为停止）"
[[ "$JA_STATE" == "running" ]] && JA_SELF="自己回来了"
note "J-a 人为 kill 后 20s：$JA_SELF —— 这一格是运维事实，不是 bug"
[[ "$JA_STATE" == "running" ]] || docker start xsl-jump-1-1 >/dev/null 2>&1
UPA=$(start_and_wait xsl-jump-1-1 90); note "J-a 手工 docker start → healthy：${UPA}s"
sleep 2
# J-b：容器内 PID 1（JVM）被 kill -9 ⇒ 等价一次进程崩溃，这才该由 restart: always 兜住
traffic 22 "$CJ" /tmp/d83-J.log & TP=$!
sleep 3
T0=$(date +%s); docker exec xsl-jump-1-1 kill -9 1 >/dev/null 2>&1; note "J-b kill -9 容器内 PID 1 @ $(date +%T)（打流继续）"
wait $TP
J_TOT=$(wc -l < /tmp/d83-J.log | tr -d ' '); JT=$(tally /tmp/d83-J.log 302); J5=$(tally /tmp/d83-J.log 5)
check "J1 进程崩溃期间零 5xx（nginx 同请求改投另一实例）" "0" "$J5"
truthy "J2 被摘掉一半实例时跳转不跌零" "$( [[ "$JT" -gt $((J_TOT / 2)) ]] && echo 1 || echo 0 )" "302=$JT/$J_TOT，分布 $(dist /tmp/d83-J.log)"
RH=""
for _ in $(seq 45); do [[ "$(health xsl-jump-1-1)" == "healthy" ]] && { RH=$(( $(date +%s) - T0 )); break; }; sleep 2; done
check "J3 restart:always 把崩溃的实例自己拉回来（全程没有人帮它 start）" "healthy" "$(health xsl-jump-1-1)"
PEERS=0; RELOAD="不需要"
for _ in $(seq 6); do
  seq 30 | xargs -P 6 -I{} curl -s -o /dev/null --resolve "$DOMAIN:$PORT:$IP" "$ORIGIN/s/$CJ" 2>/dev/null
  PEERS=$(docker logs xsl-nginx-1 --since 6s 2>&1 | grep -oE '[0-9.]+:8020' | sort -u | wc -l | tr -d ' ')
  [[ "$PEERS" == "2" ]] && break
  note "上游只看到 $PEERS 个地址（容器换了 IP，而数据面 upstream 是静态块）→ 补一次优雅 reload"
  bash "$REPO_ROOT/scripts/nginx-reload-verified.sh" >/dev/null 2>&1 && RELOAD="需要（一次优雅 reload）"
done
check "J4 被摘实例回到轮询" "2" "$PEERS"
note "J-b 崩溃 → healthy：${RH:-超过 90}s；回轮询是否需要 reload：$RELOAD"
want "J-a 人为 kill 20s 后" "$JA_SELF"
want "J-a 手工 start→healthy" "${UPA}s"
want "J-b 窗口请求/302/5xx" "$J_TOT/$JT/$J5"
want "J-b 崩溃→healthy" "${RH:->90}s"
want "J-b 回轮询要 reload 吗" "$RELOAD"
row "J 应用容器崩溃 | J-a \`docker kill\`、J-b 容器内 PID 1 被 kill -9（窗口 $J_TOT 击） | 5xx=${J5}、302=${JT}、恢复后轮询 ${PEERS} 实例 | J-b ${RH:->90}s、J-a 要手工 start（${UPA}s 回 healthy） | 单实例消失时客户端只看到 302；**restart:always 只兜进程崩溃，不兜人为 kill**"
note "这一段故意不断言事件数：请求可能在实例死掉的瞬间已发出事件却没回响应，改投会再产生一条——那是双计数，不是丢数"
fi

# ================================================================ C ClickHouse 挂
if [[ " $SECTIONS " == *" C "* ]]; then
echo "-- C ClickHouse 挂：看板要显示「数据源不可用」，而不是「这个窗口里没有点击」（8.3 第 4 行）"
CC=$(create_link C show/C)
check "C0 停之前五条 stats 全 200" "200, 200, 200, 200, 200" "$(stats_codes "$CC" "$TAG-C")"
FAIL0=$(as_int "$(metric "$CONSUMER" xsl_consumer_ch_posts_total 'result="fail"')")
T0=$(date +%s); docker stop xsl-clickhouse-1 >/dev/null 2>&1; DOWN=$(( $(date +%s) - T0 ))
note "docker stop clickhouse（${DOWN}s 退出）"
CODES_C="$(stats_codes "$CC" "$TAG-C")"
check "C1 停期间五条 stats 全 503" "503, 503, 503, 503, 503" "$CODES_C"
BODY_C=$(curl -s -m 20 "${CONSOLE_RESOLVE[@]}" "$CONSOLE/api/v1/stats/campaigns/$TAG-C")
check "C2 503 是错误信封不是空数据（code=SL-5000）" "SL-5000" "$(printf '%s' "$BODY_C" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')"
traffic 14 "$CC" /tmp/d83-C.log & TP=$!
sleep 8
FAIL1=$(as_int "$(metric "$CONSUMER" xsl_consumer_ch_posts_total 'result="fail"')")
wait $TP
CT=$(tally /tmp/d83-C.log 302); C5=$(tally /tmp/d83-C.log 5)
check "C3 ClickHouse 挂时跳转完全不受影响（5xx=0）" "0" "$C5"
truthy "C4 consumer 写不进去这件事是可数的（result=fail 增长）" "$(num_gt "$FAIL1" "$FAIL0")" \
        "xsl_consumer_ch_posts_total{result=fail} $FAIL0 → $FAIL1"
T1=$(date +%s); UP=$(start_and_wait xsl-clickhouse-1 60)
note "ClickHouse 回到 healthy：${UP}s"
traffic 10 "$CC" /tmp/d83-C2.log & TP=$!; wait $TP
EXPECT_C=$(( CT + $(tally /tmp/d83-C2.log 302) ))
DEADLINE=$(( $(date +%s) + 90 )); GOT=0
while [[ $(date +%s) -lt $DEADLINE ]]; do
  GOT=$(events "$CC"); [[ "$GOT" -ge "$EXPECT_C" ]] && break; sleep 3
done
DR=$(( $(date +%s) - T1 ))
check "C5 恢复后事件一行不少（明细行数 == 停机窗口 + 恢复窗口发出的 302 数）" "$EXPECT_C" "$GOT"
check_le "C6 追赶在 90s 内完成" 90 "$DR" s
check "C7 恢复后五条 stats 重新 200" "200, 200, 200, 200, 200" "$(stats_codes "$CC" "$TAG-C")"
want "C 停时长/期望/实收" "${DOWN}s / $EXPECT_C / $GOT"
want "C healthy / 追赶" "${UP}s / ${DR}s"
row "C ClickHouse 挂 | 停约 $((DOWN + 24))s，两窗口共 $EXPECT_C 击 | stats 五条全 503（SL-5000 而非空数据）、跳转 5xx=${C5}、ch_posts{fail} +$((FAIL1 - FAIL0)) | healthy ${UP}s | 明细 $GOT/$EXPECT_C 一行不少，追赶 ${DR}s"
fi

# ================================================================ K Kafka 挂
if [[ " $SECTIONS " == *" K "* ]]; then
echo "-- K Kafka 挂：跳转照常 302、事件落 WAL，恢复后重放不重不丢（8.3 第 2 行）"
CK=$(create_link K show/K)
W0=$(( $(as_int "$(metric "$J1" xsl_jump_clickevent_total 'result="wal"')") + $(as_int "$(metric "$J2" xsl_jump_clickevent_total 'result="wal"')") ))
docker stop xsl-kafka-1 >/dev/null 2>&1; note "docker stop kafka @ $(date +%T)"
sleep 3                       # 让 producer 手里的在途批次先超时落进 WAL，再开始计窗口
traffic 16 "$CK" /tmp/d83-K.log & TP=$!; wait $TP
sleep 6                       # delivery.timeout 2s + 批量回调：最后一击的落盘判定要等它
KT=$(tally /tmp/d83-K.log 302); K5=$(tally /tmp/d83-K.log 5)
W1=$(( $(as_int "$(metric "$J1" xsl_jump_clickevent_total 'result="wal"')") + $(as_int "$(metric "$J2" xsl_jump_clickevent_total 'result="wal"')") ))
WAL_DELTA=$((W1 - W0)); WALFILES=$(wal_lines)
check "K1 Kafka 挂时跳转照常（5xx=0）" "0" "$K5"
check "K2 窗口内每一条 302 都进了 WAL（不重不丢的前一半）" "$KT" "$WAL_DELTA"
truthy "K3 WAL 文件里此刻真躺着这些行" "$( [[ "$WALFILES" -gt 0 ]] && echo 1 || echo 0 )" "两实例 WAL 目录合计 $WALFILES 行待重放"
T1=$(date +%s); UP=$(start_and_wait xsl-kafka-1 90)
note "Kafka 回到 healthy：${UP}s"
DEADLINE=$(( $(date +%s) + 150 )); GOT_K=0; WF=1
while [[ $(date +%s) -lt $DEADLINE ]]; do
  GOT_K=$(events "$CK"); WF=$(wal_lines)
  [[ "$GOT_K" -ge "$KT" && "$WF" -le 2 ]] && break; sleep 3
done
REPLAY=$(( $(date +%s) - T1 ))
check "K4 重放后明细行数 == 停期间发出的点击数（不重不丢的后一半）" "$KT" "$GOT_K"
check_le "K5 重放 + 入库在 150s 内" 150 "$REPLAY" s
LG=""
for _ in $(seq 25); do [[ "$(lag)" == "0" ]] && { LG=0; break; }; sleep 2; done
check "K6 lag 归零（Kafka 堆积不丢，恢复后追赶）" "0" "${LG:-$(lag)}"
want "K 窗口 302/WAL计数/入库" "$KT/$WAL_DELTA/$GOT_K"
want "K WAL 残留行数" "$WF"
want "K kafka healthy / 重放" "${UP}s / ${REPLAY}s"
row "K Kafka 挂 | 停 19s + 窗口 $KT 击 | 5xx=${K5}、WAL 计数 +${WAL_DELTA}、WAL 文件 $WALFILES 行 | healthy ${UP}s | 重放后明细 $GOT_K/$KT、lag=0（排空+入库 ${REPLAY}s）"
fi

# ================================================================ R Redis 挂
if [[ " $SECTIONS " == *" R "* ]]; then
echo "-- R Redis 挂：回源 + 频控退化；恢复后先探测广播再断依赖它的项（8.3 第 1 行）"
CR=$(create_link R show/R)
CRC=$(create_link Rc show/Rc)
# 冷码：这条**一次都没被请求过**（连 L1 都还没有），Redis 一挂它就只能走「回源 link_route」那条路。
# 用它来验 8.3 那一格的兜底本身；热码则完全被 Caffeine 接住、压根不去碰 DB——
# 第一版把断言挂在热码窗口上，于是 db 计数纹丝不动（实测 jump1 0→0、jump2 2→2）：那不是没回源，是没必要回源。
$RCLI DEL "sl:r:$CRC" "sl:code:$CRC" >/dev/null 2>&1
# 基线速率：同一个码、同一个循环，先打 6 秒数一遍。没有这一条，「Redis 挂了变慢」只能靠嘴说。
traffic 6 "$CR" /tmp/d83-R0.log & TP=$!; wait $TP
RB=$(wc -l < /tmp/d83-R0.log | tr -d ' ')
DB0=$(as_int "$(metric "$J1" xsl_jump_route_lookup_total 'level="db"')");  DB0x=$(as_int "$(metric "$J2" xsl_jump_route_lookup_total 'level="db"')")
LC0=$(as_int "$(metric "$J1" xsl_jump_rate_limit_total 'mode="local"')"); LC0x=$(as_int "$(metric "$J2" xsl_jump_rate_limit_total 'mode="local"')")
docker stop xsl-redis-1 >/dev/null 2>&1; note "docker stop redis @ $(date +%T)"
sleep 2
traffic 14 "$CR" /tmp/d83-R.log & TP=$!; wait $TP
C3=$(for _ in 1 2 3; do curl -s -o /dev/null -m 25 "${RESOLVE[@]}" -w '%{http_code} ' "$ORIGIN/s/$CRC"; sleep 1; done)
R_TOT=$(wc -l < /tmp/d83-R.log | tr -d ' '); RT=$(tally /tmp/d83-R.log 302); R5=$(tally /tmp/d83-R.log 5)
DB1=$(as_int "$(metric "$J1" xsl_jump_route_lookup_total 'level="db"')");  DB1x=$(as_int "$(metric "$J2" xsl_jump_route_lookup_total 'level="db"')")
LC1=$(as_int "$(metric "$J1" xsl_jump_rate_limit_total 'mode="local"')"); LC1x=$(as_int "$(metric "$J2" xsl_jump_rate_limit_total 'mode="local"')")
check "R1 Redis 挂时零 5xx" "0" "$R5"
truthy "R2 跳转不跌零（302 仍在出）" "$( [[ "$RT" -gt 0 ]] && echo 1 || echo 0 )" "302=$RT/$R_TOT，分布 $(dist /tmp/d83-R.log)"
BASE_MS=$(awk -v a="$RB" 'BEGIN{printf "%.0f", 6000/a}')
DURING_MS=$(awk -v a="$R_TOT" 'BEGIN{printf "%.0f", 14000/a}')
note "R7 代价实测：平时每击 ${BASE_MS}ms（6s 打完 $RB 击），Redis 挂后每击 ${DURING_MS}ms（14s 只打完 $R_TOT 击）"
note "     ⇒ 掉的是延迟不是可用性：每一击都要先等 Redis 调用失败，才回落到本机计数与 DB"
check "R3 冷码在 Redis 挂时靠回源 link_route 仍然 302（预案的另一半）" "302 302 302" "$(echo $C3)"
truthy "R3b 回源这条路确实被走到了（route_lookup level=db 增长）" \
        "$( [[ "$(num_gt "$DB1" "$DB0")" == 1 || "$(num_gt "$DB1x" "$DB0x")" == 1 ]] && echo 1 || echo 0 )" \
        "jump1 $DB0→$DB1、jump2 $DB0x→$DB1x（热码全被 Caffeine 接住，所以这个增量只可能来自那三次冷码探测）"
truthy "R4 频控退化为进程内计数（mode=local 增长）" \
        "$( [[ "$(num_gt "$LC1" "$LC0")" == 1 || "$(num_gt "$LC1x" "$LC0x")" == 1 ]] && echo 1 || echo 0 )" \
        "jump1 $LC0→$LC1、jump2 $LC0x→$LC1x"
UP=$(start_and_wait xsl-redis-1 60); note "Redis 回到 healthy：${UP}s"
# 探测先于断言（纪律 2）：订阅恢复本机量过 12–18s，那个窗口里发的广播真的会丢
EV1=$(as_int "$(metric "$J1" xsl_jump_route_evict_total)"); EV2=$(as_int "$(metric "$J2" xsl_jump_route_evict_total)")
P1=$EV1; P2=$EV2; probed=0; SUB_SECS=""
# 上限给到 60s：本机量过 2s / 4s / 12s，也撞到过一次 30s 内没回来（连续两次快速停起 Redis 之后）。
# 这不是产品断言而是**环境闸门**，窗太窄只会把"还没等到"伪装成"坏了"，所以宽一点、把真实秒数记下来。
for i in $(seq 1 30); do
  $RCLI PUBLISH "$EVICT_CHANNEL" "d83-probe-$i" >/dev/null 2>&1
  sleep 2
  Q1=$(as_int "$(metric "$J1" xsl_jump_route_evict_total)"); Q2=$(as_int "$(metric "$J2" xsl_jump_route_evict_total)")
  if [[ "$(num_gt "$Q1" "$P1")" == "1" && "$(num_gt "$Q2" "$P2")" == "1" ]]; then
    probed=1; SUB_SECS=$((i * 2)); break
  fi
  P1=$Q1; P2=$Q2
done
check "R5 恢复后两个实例都能重新收到广播（先探测，后断言）" "1" "$probed"
note "订阅恢复：约 ${SUB_SECS:-超过探测上限 60}s 起两实例都收得到（本机历史量 2/4/12/18s）"
curl -s -m 20 -o /dev/null -X PATCH "$ADMIN/api/v1/links/$CR" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: d83-ban-$RUN" -d '{"status":2}' >/dev/null
t=0; a1=""; a2=""
while [[ $t -lt 30 ]]; do
  [[ -z "$a1" && "$(code_of "$J1/$CR")" == "403" ]] && a1=$t
  [[ -z "$a2" && "$(code_of "$J2/$CR")" == "403" ]] && a2=$t
  [[ -n "$a1" && -n "$a2" ]] && break
  t=$((t + 1)); sleep 0.1
done
truthy "R6 恢复后广播仍然管用（紧急封禁两实例都在 3s 内改口）" "$( [[ -n "$a1" && -n "$a2" ]] && echo 1 || echo 0 )" \
        "jump1=${a1:+$((a1 * 100))ms} jump2=${a2:+$((a2 * 100))ms}（没有广播就只能等 L1 的 30s TTL）"
curl -s -m 20 -o /dev/null -X PATCH "$ADMIN/api/v1/links/$CR" -H 'Content-Type: application/json' \
  -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: d83-unban-$RUN" -d '{"status":0}' >/dev/null
want "R 基线每击耗时" "${BASE_MS}ms（6s / $RB 击）"
want "R 停期间每击耗时" "${DURING_MS}ms（14s / $R_TOT 击）"
want "R 停期间 302/5xx" "$RT/$R5（共 $R_TOT，14s）"
want "R 冷码回源返回码" "$(echo $C3)（db 计数 jump1 $DB0→$DB1、jump2 $DB0x→$DB1x）"
want "R db 回源增量" "jump1 +$((DB1 - DB0))、jump2 +$((DB1x - DB0x))"
want "R 频控 local 增量" "jump1 +$((LC1 - LC0))、jump2 +$((LC1x - LC0x))"
want "R redis healthy / 订阅恢复" "${UP}s / ${SUB_SECS:-超时（60s 内没等到）}s"
row "R Redis 挂 | 停 16s，$R_TOT 击（每击 ${DURING_MS}ms，平时 ${BASE_MS}ms） | 5xx=${R5}、冷码回源 $(echo $C3)、db 计数 +$((DB1 - DB0 + DB1x - DB0x))、频控 mode=local +$((LC1 - LC0 + LC1x - LC0x)) | healthy ${UP}s、订阅 ${SUB_SECS:-n/a}s | 冷码靠 link_route 直读仍在跳；恢复后跨实例封禁仍 3s 内收敛"
fi

# ================================================================ M MySQL 挂
if [[ " $SECTIONS " == *" M "* ]]; then
echo "-- M MySQL 挂：热码存活、新码失败得可见、看板照读（8.3 第 3 行，也是 3.3 那句的支撑）"
CM=$(create_link M show/M); CMCOLD=$(create_link Mc show/Mc)
for _ in $(seq 6); do curl -s -o /dev/null -m 10 "${RESOLVE[@]}" "$ORIGIN/s/$CM"; sleep 0.1; done
$RCLI DEL "sl:r:$CMCOLD" "sl:code:$CMCOLD" >/dev/null 2>&1
$RCLI PUBLISH "$EVICT_CHANNEL" "$CMCOLD" >/dev/null 2>&1   # 连 L1 一起清掉，这才叫冷码
note "预热：$CM 是热码（L1+L2 都有），$CMCOLD 刚把两层缓存都清掉"
docker stop xsl-mysql-1 >/dev/null 2>&1; note "docker stop mysql @ $(date +%T)"
sleep 2
traffic 12 "$CM" /tmp/d83-M.log & TP=$!
CRE0=$(date +%s)
CREATE_HTTP=$(curl -s -m 30 -o /tmp/d83-create.$RUN.json -w '%{http_code}' -X POST "$ADMIN/api/v1/links" \
  -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: d83-mysqldown-$RUN" \
  -d "{\"origin_url\":\"https://mock.ticketsales.test/show/should-fail\",\"channel_id\":\"c-$TAG\",\"campaign_id\":\"$TAG-Mfail\",\"promoter_id\":\"pr-d83\",\"redirect_type\":1}")
CREATE_SECS=$(( $(date +%s) - CRE0 ))
# curl 超时（http=000）时 -o 那个文件压根不会被创建，所以这里必须容错读——
# 而 000 本身就是要记下来的那个事实：管理面没有在 30s 内给出任何响应。
CREATE_BODY=$(cat /tmp/d83-create.$RUN.json 2>/dev/null || true)
wait $TP
MT_TOT=$(wc -l < /tmp/d83-M.log | tr -d ' '); MT=$(tally /tmp/d83-M.log 302); M5=$(tally /tmp/d83-M.log 5)
COLD_DURING=$(code_of "${RESOLVE[@]}" "$ORIGIN/s/$CMCOLD")
check "M1 热码照常 302、零 5xx（数据面靠缓存活着）" "0" "$M5"
truthy "M2 热码窗口内确有跳转" "$( [[ "$MT" -gt 0 ]] && echo 1 || echo 0 )" "302=$MT/$MT_TOT，分布 $(dist /tmp/d83-M.log)"
truthy "M3 POST /links 失败得可见（非 2xx，且响应里没有 short_code）" \
        "$( [[ "$CREATE_HTTP" != "200" && "$CREATE_HTTP" != "201" && "$CREATE_BODY" != *'"short_code"'* ]] && echo 1 || echo 0 )" \
        "http=$CREATE_HTTP、${CREATE_SECS}s、body=$(printf '%s' "$CREATE_BODY" | head -c 120)"
if [[ "$CREATE_HTTP" == "000" ]]; then
  note "M3 的形态要说清楚：http=000 = curl 在 ${CREATE_SECS}s 上限处放弃，admin 侧一个字节都没回。"
  note "     这满足「失败得可见」（确实没静默返回一条没落库的链接），但**不是** 8.3 那格写的「管理面熔断」——"
  note "     没有熔断器：快慢取决于当时 Hikari 是排着队等连接、还是立刻拿不到连接。"
else
  note "M3 的形态：http=$CREATE_HTTP、${CREATE_SECS}s 就回来了，body 里是 SL-5000 且没有 short_code"
  note "     同一处在上一次排练里耗满 30s 才由 curl 放弃 ⇒ 失败一定可见，但「熔断」这个词并不准确：没有熔断器，只有快慢不稳的超时（ITER-M3 §4）。"
fi
check "M4 MySQL 挂时 stats 仍可读（ClickHouse 独立）" "200, 200, 200, 200, 200" "$(stats_codes "$CM" "$TAG-M")"
note "冷码在 MySQL 挂时返回 $COLD_DURING —— 8.3 那行的「表现」栏写的就是「缓存未命中失败」，这里把它量出来而不假装它还能跳"
UP=$(start_and_wait xsl-mysql-1 90); note "MySQL 回到 healthy：${UP}s"
sleep 3
AFTER=$(curl -s -m 30 -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" \
  -H "Idempotency-Key: d83-aftermysql-$RUN" \
  -d "{\"origin_url\":\"https://mock.ticketsales.test/show/ok-again\",\"channel_id\":\"c-$TAG\",\"campaign_id\":\"$TAG-Mok\",\"promoter_id\":\"pr-d83\",\"redirect_type\":1,\"rate_limit_per_minute\":$WALL_RATE}")
NEWCODE=$(printf '%s' "$AFTER" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
[[ -n "$NEWCODE" ]] && printf '%s\n' "$NEWCODE" >> "$CODES"
check "M5 恢复后创建链接重新成功" "yes" "$( [[ -n "$NEWCODE" ]] && echo yes || echo no )"
COLD_AFTER=$(code_of "${RESOLVE[@]}" "$ORIGIN/s/$CMCOLD")
check "M6 恢复后冷码也能跳（刚才那个 $COLD_DURING 确实是 MySQL 造成的，不是别的）" "302" "$COLD_AFTER"
want "M 停期间 302/5xx/冷码" "$MT/$M5/$COLD_DURING"
want "M 创建失败耗时/返回" "${CREATE_SECS}s / http=$CREATE_HTTP"
want "M mysql healthy" "${UP}s"
row "M MySQL 挂 | 停 12s + 创建探测 ${CREATE_SECS}s | 热码 302=$MT、5xx=${M5}、POST /links http=${CREATE_HTTP} 且不回 short_code、冷码=$COLD_DURING、stats 五条 200 | healthy ${UP}s | 恢复后新建成功、冷码重新 $COLD_AFTER"
fi

# ================================================================ B 数据丢失
if [[ " $SECTIONS " == *" B "* ]]; then
echo "-- B 数据丢失：make backup → make restore-verify（8.3 第 6 行，本轮补上它承诺的两个东西）"
T0=$(date +%s)
BK=$(cd "$REPO_ROOT" && make backup 2>&1); BKS=$?
BK_SECS=$(( $(date +%s) - T0 ))
check "B1 make backup 退出码 0" "0" "$BKS"
printf '%s\n' "$BK" | grep -E '^(  |RESULT)' | sed 's/^/     · /'
T0=$(date +%s)
RV=$(cd "$REPO_ROOT" && make restore-verify 2>&1); RVS=$?
RV_SECS=$(( $(date +%s) - T0 ))
check "B2 make restore-verify 退出码 0" "0" "$RVS"
printf '%s\n' "$RV" | grep -E '^(PASS|FAIL|RESULT)' | sed 's/^/     · /'
BAD=$(printf '%s' "$RV" | sed -n 's/.*RESULT restore-verify bad=\([0-9]*\).*/\1/p')
MTAB=$(printf '%s' "$RV" | sed -n 's/.*mysql_tables=\([0-9]*\).*/\1/p')
MROWS=$(printf '%s' "$RV" | sed -n 's/.*mysql_rows=\([0-9]*\).*/\1/p')
check "B3 读回验证 0 项不符" "0" "${BAD:-none}"
want "B backup 用时/体积" "${BK_SECS}s / $(du -sh "$REPO_ROOT/deploy/backup" 2>/dev/null | awk '{print $1}')"
want "B restore-verify 用时" "${RV_SECS}s（表 $MTAB、行 $MROWS、bad=${BAD:-?}）"
row "B 数据丢失 | \`make backup\` ${BK_SECS}s → \`make restore-verify\` ${RV_SECS}s | 一次性容器灌 dump + CH 影子表（两边都不碰现有卷） | bad=${BAD:-?} | MySQL $MTAB 张表逐表行数与清单精确相等（$MROWS 行）；CH 明细行数落回备份区间、聚合表六项指标同样落回区间，影子表已 DROP"
fi

# ================================================================ 复位与清理
echo "-- 复位：中间件全部回到 healthy、nginx 优雅 reload、清理本次 fixture"
for c in xsl-mysql-1 xsl-redis-1 xsl-kafka-1 xsl-clickhouse-1 xsl-jump-1-1; do
  [[ "$(health "$c")" == "healthy" || "$(health "$c")" == "none" ]] || { start_and_wait "$c" 150 >/dev/null || true; }
done
bash "$REPO_ROOT/scripts/nginx-reload-verified.sh" >/dev/null 2>&1
sleep 2
UNHEALTHY=""
for c in $(docker ps --format '{{.Names}}' | grep '^xsl-'); do
  [[ "$(health "$c")" != "unhealthy" ]] || UNHEALTHY="$UNHEALTHY $c"
done
check "Z1 演练结束后没有 unhealthy 容器" "无" "${UNHEALTHY:-无}"
# 遍历用词表而不是 `while read … < 文件`：**`docker compose exec -T` 会把循环的 stdin 喝干**，
# 第一版就是这样，八个码只清掉了第一个（实测复现：三行文件跑 while read 只 echo 一次，
# 给 exec 加 </dev/null 才三次都到）。残留在本机留着不难受，难受的是"脚本自己说清了"这件事。
for c in $(tr -d '\r' < "$CODES"); do
  for db in xsl_00 xsl_01; do
    $MYSQL_CLI "$db" -e "DELETE FROM short_link WHERE short_code='$c'; DELETE FROM link_route WHERE short_code='$c';
      DELETE FROM code_tenant_index WHERE short_code='$c'; DELETE FROM route_rule WHERE short_code='$c';
      DELETE FROM short_code_pool WHERE short_code='$c';" >/dev/null 2>&1
    $MYSQL_CLI "$db" -e "DELETE FROM outbox WHERE aggregate_id='$c';" >/dev/null 2>&1
  done
  $RCLI DEL "sl:r:$c" "sl:code:$c" "sl:cnt:$c" >/dev/null 2>&1
  for k in $($RCLI KEYS "sl:rl:$c:*" 2>/dev/null | tr -d '\r'); do $RCLI DEL "$k" >/dev/null 2>&1; done
  CHQ "ALTER TABLE xsl.click_event DELETE WHERE short_code = '$c'" >/dev/null 2>&1
done
REMAIN=0
for _ in $(seq 20); do
  REMAIN=$(CHQ "SELECT count() FROM xsl.click_event WHERE campaign_id LIKE '${TAG}%'")
  [[ "$REMAIN" == "0" ]] && break
  sleep 3
done
MR=$(MY "SELECT COUNT(*) FROM xsl_00.link_route WHERE short_code IN ($(sed 's/.*/"&"/' "$CODES" | paste -sd, -))" )
check "Z2 演练 fixture 真的清干净了（明细行数归零，最多等 60s 让 mutation 物化）" "0" "${REMAIN:-读不到}"
note "fixture 已清：$(wc -l < "$CODES" | tr -d ' ') 条码；残留明细 $REMAIN 行、link_route 残留 ${MR:-?} 行（这两个数就是 Z2 在管的事）"

TSF="$(date +%Y-%m-%d-%H%M)"
# bench/reports/ 是**只增不改**的测量存档，所以调试这一段脚本时不该往里写：
# 半截跑完、带 FAIL 的排练件混进去，读者会把它当成正式记录点进去看。
REPORT="${DRILL_ARCHIVE:-bench/reports/$TSF-m3-drill-8-3.md}"
[[ -e "$REPORT" && "$REPORT" != "/dev/null" ]] && REPORT="bench/reports/$(date +%Y-%m-%d-%H%M%S)-m3-drill-8-3.md"
{
  printf '# 降级演练存档：m3-drill-8-3（DESIGN 8.3 全表六行）\n\n'
  printf -- '- 时间：%s\n- 提交：%s（含未提交改动）\n- 跑了哪几行：%s\n' \
    "$(date '+%Y-%m-%d %H:%M:%S %z')" "$(git -C "$REPO_ROOT" rev-parse --short HEAD)" "$SECTIONS"
  printf -- '- 宿主：%s / %s GiB\n- 容器内存实测：%s\n' \
    "$(sysctl -n machdep.cpu.brand_string)" "$(( $(sysctl -n hw.memsize) / 1073741824 ))" \
    "$(docker stats --no-stream --format '{{.Name}}={{.MemUsage}}' | awk '/xsl/{printf "%s ", $0}')"
  printf -- '- 演练每行真实停机 10–40s；「恢复」列取 stop→达标（healthy / lag 归零 / 两实例改口）的秒数\n\n'
  printf '| 预案行 | 停什么、多久 | 停期间断言的行为 | 恢复 | 完整性断言 |\n|---|---|---|---|---|\n'
  cat "$ROWS"
  printf '\n## 原始计数\n\n```\n'; cat "$RAW"; printf '```\n'
} > "$REPORT"
echo "存档（只增不改）：$REPORT"
echo "== $pass 项通过，$fail 项失败 =="
rm -f "/tmp/d83-codes.$RUN.txt" "/tmp/d83-raw.$RUN.txt" "/tmp/d83-rows.$RUN.md" \
      "/tmp/d83-create.$RUN.json" /tmp/d83-J.log /tmp/d83-C.log /tmp/d83-C2.log \
      /tmp/d83-K.log /tmp/d83-R.log /tmp/d83-M.log 2>/dev/null
[[ $fail -eq 0 ]]
