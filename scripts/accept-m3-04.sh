#!/usr/bin/env bash
# M3-04 验收（假订单接口 + 端到端归因对账，DESIGN 6.4 / 附录 B）：
#   A. 扮演购票方：抓 302 Location 上的 xy_click_id → 点击落 CH → 分钟关闭 → 回传 → 用抓到的 click_id 下单
#   B. 订单幂等：同一 order_id 重发不重复计入（分母虚高会伪装成「转化率莫名偏低」）
#   C. 订单校验：缺 order_id / 缺 click_id → 400 且不落半行
#   D. 对账主组：两条判定式都过，且 recon 读到的三个基数与脚本手算的一模一样；不给窗口时自动推同一区间
#   E. 负向样本：另一批活动注入 3 条引用**不存在** click_id 的订单 → 工具必须报 missing=3 且退出码变红，
#      而主组两条判定式不受影响（分成两组的意义就在这里）
#   E2. 传输侧负样本：再一批活动只点击、不回传 → ① 必须报 100.00% 差异并变红（n/a 只留给"两侧都没数"）
#   F. 阈值真的被读：--max-diff 0 / --min-join 101 会把主组判成 FAIL（否则「PASS」可能只是写死的）
#   G. 观测项与复位：聚合表偏差只打印不判；XSL_PUSH_ENABLED 复位后定时恢复
# 前置：make images && make up && make db-init（xsl_mock 三张表）；make tls（购票页 443）。
# 口径：A–F 在「定时关闭」的窗口里跑并把回看拉到 10 个分钟（沿用 m3-03 的确定性做法）；
#       短码/订单号/批次号一律记在**文件**里；断言先取值再 check，不写三层嵌套引号（macOS bash 3.2）。
# 不要把这个脚本管道给 head/tail：SIGPIPE 会打断脚本，trap 之外的环境变量改动就留在脏值上（ITER-M3 §4 记过）。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
CONSUMER="${CONSUMER_DIRECT:-http://localhost:8040}"
MOCK="${MOCK_DIRECT:-http://localhost:8050}"
LANDING_PORT="${MOCK_TLS_PORT:-443}"
RCLI="$COMPOSE exec -T redis redis-cli"   # lib-devenv 只给 mysql/clickhouse 那两个，清理要用到第三个
RECON="$(dirname "$0")/recon-attribution.sh"
TENANT=1001
RUN=$(date +%s)
CP="p-m304-$RUN"            # 主组活动：全部订单引用真实 click_id
BC="d-m304-$RUN"            # 负向样本活动：注入假 click_id，故意让 ② 报缺失
TC="t-m304-$RUN"            # 传输侧负样本活动：只点击、不回传，故意让 ① 报缺失
CHN="c-m304-$RUN"
N_CLICKS=20
N_ORDERS=15                 # 不是每击都下单——② 的分母是订单数，不是点击数
N_BAD=3                     # 注入几条假 click_id 订单（E 段断言工具报出的缺失数恰好等于它）
N_DUP=3                     # 重发几笔已存在的订单（B 段）——和 N_BAD 是两件事，不共用一个数
N_NOCARRY=5                 # E2 段：这个活动只点击、不回传，用来验 ① 的报警路径
CODE_FILE="/tmp/m304-code.$RUN.txt"
LOC_FILE="/tmp/m304-loc.$RUN.txt"
CID_FILE="/tmp/m304-cid.$RUN.txt"
MAP_FILE="/tmp/m304-map.$RUN.txt"
ORD_FILE="/tmp/m304-ord.$RUN.txt"
BATCH_FILE="/tmp/m304-batch.$RUN.txt"
RESP_FILE="/tmp/m304-resp.$RUN.txt"
PAGE_FILE="/tmp/m304-page.$RUN.txt"
: > "$CODE_FILE"; : > "$LOC_FILE"; : > "$CID_FILE"; : > "$MAP_FILE"
: > "$ORD_FILE"; : > "$BATCH_FILE"; : > "$RESP_FILE"

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
truthy() { if [[ "$2" == "1" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 ($3)"; ((fail++)); fi; }
check_delta() { # check_delta <名> <before> <after> <期望增量>：计数器要的是「这一轮加了几」，不是绝对值
  local d; d=$(awk -v a="$2" -v b="$3" 'BEGIN{printf "%d", b-a}')
  check "$1" "$4" "$d"
}
note() { echo "     · $*"; }
check_same() { # check_same <名> <多行值1> <多行值2> <报数标签>：整集合逐个相同，但不往输出里铺几十行
  if [[ "$2" == "$3" ]]; then echo "PASS $1 ($4)"; ((pass++));
  else echo "FAIL $1 两个集合不一致"; diff -u <(printf '%s\n' "$2") <(printf '%s\n' "$3") | sed 's/^/      /'; ((fail++)); fi
}

MY() { $MYSQL_CLI -N -B -e "$1" 2>/dev/null; }
CHQ() { curl -s -m 60 -u "$CH_CRED" "http://127.0.0.1:8123/" --data-binary "$1" | tr -d '\r'; }
jf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\([0-9-]*\).*/\1/p"; }      # JSON 整数
rf() { printf '%s' "$1" | sed -n "s/.*[[:space:]]$2=\([^ ]*\).*/\1/p" | tail -1; }  # recon 的 RESULT 字段
metric() { # metric <base-url> <指标名> [标签子串]（与 m3-03 同一份写法：无标签指标也要能读到）
  local v
  v=$(curl -s -m 5 "$1/actuator/prometheus" \
      | awk -v n="$2" -v l="${3:-}" '$1 ~ ("^" n "($|[[:space:]]|\\{)") && (l == "" || index($1,l)>0) {print $2; exit}')
  echo "${v:-0}"
}
order_body() { # order_body <order_id> <click_id> <campaign> <first_click_time(空格形式)>
  local iso; iso=$(printf '%s' "$4" | tr ' ' 'T')   # Jackson 的 LocalDateTime 只认 ISO 的 'T' 形式
  printf '{"order_id":"%s","click_id":"%s","channel_id":"%s","campaign_id":"%s","promoter_id":"pr-m304","first_click_time":"%s","last_click_time":"%s"}' \
    "$1" "$2" "$CHN" "$3" "$iso" "$iso"
}
post_order() { # post_order <body> → 状态码（响应体留在 $RESP_FILE）
  curl -s -o "$RESP_FILE" -w '%{http_code}' -X POST "$MOCK/mock/orders" \
    -H 'Content-Type: application/json' -d "$1"
}
order_rows() { MY "SELECT count(*) FROM xsl_mock.mock_order WHERE campaign_id='$1';" | head -1; }
run_recon() { # run_recon <campaign> [窗口] [开关…]：输出与退出码留在 RECON_OUT / RECON_RC
  RECON_OUT=$(bash "$RECON" "$@" 2>&1); RECON_RC=$?
}
manual_push() { curl -s -m 90 -XPOST "$CONSUMER/internal/attribution/push"; }

create_link() { # create_link <campaign_id> <幂等标签>
  local body resp code
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/show/%s","channel_id":"%s","campaign_id":"%s","promoter_id":"pr-m304","redirect_type":1,"rate_limit_per_minute":600}' "$1" "$CHN" "$1")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m304-$RUN-$2-$RANDOM" -d "$body")
  code=$(printf '%s' "$resp" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
  if [[ -z "$code" ]]; then echo "FATAL 创建失败: $resp" >&2; exit 1; fi
  printf '%s\n' "$code" >> "$CODE_FILE"        # 子 shell 里改数组带不出来，所以走文件
  echo "$code"
}
minute_closed_wait() { # 等某个分钟（'YYYY-MM-DD HH:MM:SS'，点击侧墙上时钟）真正过去
  local remain
  remain=$(python3 - "$1" <<'PYWAIT'
import sys, datetime
m = datetime.datetime.strptime(sys.argv[1], "%Y-%m-%d %H:%M:%S")
# CH 会话时区是 UTC 而 click_time 存的是上海墙上时钟（DESIGN 4.2）：两侧按同一条约定读写，
# 这里只算「这一分钟结束还差几秒」，不做时区换算。
print(max(0, int((m + datetime.timedelta(seconds=65) - datetime.datetime.now()).total_seconds())))
PYWAIT
)
  [[ "$remain" -gt 0 ]] && sleep "$remain"
  echo "$remain"
}
set_push() { # set_push <enabled> <lookback>：改环境变量重启 consumer（顺带证明 compose 真的透传）
  XSL_PUSH_ENABLED=$1 XSL_PUSH_LOOKBACK=$2 $COMPOSE up -d --wait consumer >/dev/null 2>&1
  docker exec xsl-consumer-1 printenv XSL_PUSH_ENABLED 2>/dev/null
}

cleanup() {
  set +u   # 收尾要跑完：中途一个未定义变量就 exit，等于前面所有断言的证据都留在校库里
  XSL_PUSH_ENABLED=true XSL_PUSH_LOOKBACK=3 $COMPOSE up -d consumer >/dev/null 2>&1
  if [[ -s "$CODE_FILE" ]]; then
    local codes in
    codes=$(cat "$CODE_FILE"); in=$(printf "'%s'," $codes); in="(${in%,})"
    MY "DELETE FROM xsl_00.short_link WHERE short_code IN $in; DELETE FROM xsl_01.short_link WHERE short_code IN $in;
        DELETE FROM xsl_00.link_route WHERE short_code IN $in; DELETE FROM xsl_01.link_route WHERE short_code IN $in;
        DELETE FROM xsl_00.code_tenant_index WHERE short_code IN $in; DELETE FROM xsl_01.code_tenant_index WHERE short_code IN $in;
        DELETE FROM xsl_00.outbox WHERE aggregate_id IN $in; DELETE FROM xsl_01.outbox WHERE aggregate_id IN $in;
        DELETE FROM xsl_00.short_code_pool WHERE short_code IN $in; DELETE FROM xsl_01.short_code_pool WHERE short_code IN $in;" >/dev/null
    for c in $codes; do $RCLI DEL "sl:r:$c" "sl:code:$c" >/dev/null; done
    CHQ "ALTER TABLE xsl.click_event DELETE WHERE short_code IN $in" >/dev/null
  fi
  # mock 侧按 campaign 后缀扫（主组与负向组一起），不能只删「本轮记下的那几个」：
  # 前几轮失败或中断留下的行会一直挂在台账里，下一次看 mock 的人分不清哪是证据、哪是垃圾。
  local seen ids bin left
  seen=$(MY "SELECT DISTINCT batch_id FROM xsl_mock.attribution_stat WHERE campaign_id LIKE '%m304-%';")
  ids="$seen"
  if [[ -s "$BATCH_FILE" ]]; then ids=$(printf '%s\n%s' "$ids" "$(cat "$BATCH_FILE")"); fi
  ids=$(printf '%s' "$ids" | tr ' ' '\n' | grep -v '^$' | sort -u)
  MY "DELETE FROM xsl_mock.mock_order WHERE campaign_id LIKE '%m304-%';
      DELETE FROM xsl_mock.attribution_stat WHERE campaign_id LIKE '%m304-%';" >/dev/null
  if [[ -n "$ids" ]]; then
    bin=$(printf "'%s'," $ids); bin="(${bin%,})"
    MY "DELETE FROM xsl_mock.attribution_batch WHERE batch_id IN $bin;" >/dev/null
  fi
  left=$(MY "SELECT (SELECT count(*) FROM xsl_mock.mock_order WHERE campaign_id LIKE '%m304-%')
                   + (SELECT count(*) FROM xsl_mock.attribution_stat WHERE campaign_id LIKE '%m304-%');" | head -1)
  [[ "${left:-0}" == "0" ]] || echo "WARN mock 侧仍有 m304 残留行：$left（请手工核对清理条件）"
  rm -f "$CODE_FILE" "$LOC_FILE" "$CID_FILE" "$MAP_FILE" "$ORD_FILE" "$BATCH_FILE" "$RESP_FILE" "$PAGE_FILE"
}
trap cleanup EXIT

echo "== M3-04 假订单 + 端到端归因对账（run=$RUN 主组=$CP 负向组=$BC）=="

# ---------------- P0 前置 ----------------
echo "-- P0 前置：mock 活着、三张表在、订单路由真的装配了"
HEALTH=$(curl -s -m 5 "$MOCK/actuator/health")
truthy "P0-mock-up" "$( [[ "$HEALTH" == *'"status":"UP"'* ]] && echo 1 || echo 0 )" "mock 宣发平台健康"
# 同样按表名要，不数"库里一共几张"：将来 mock 侧再加表不该把这一轮拦在 P0 上（m3-03 这轮就是这么红的，见 ITER-M3 §4）
T_TABLES=$(MY "SELECT count(*) FROM information_schema.tables WHERE table_schema='xsl_mock' AND table_name IN ('attribution_batch','attribution_stat','mock_order');" | head -1)
if [[ "${T_TABLES:-0}" != "3" ]]; then echo "FATAL xsl_mock 缺对账要用的表（三张当前 $T_TABLES 张）：先 make db-init"; exit 1; fi
check "P0-mock-schema" 3 "$T_TABLES"
# 路由存在性单独钉一发：这具 body 缺 click_id，正确行为是 400。
# 读到 404 说明容器里跑的还是旧镜像（没有这个 controller），而 404 会被后面误读成「校验生效」——本轮就先撞上过。
PROBE=$(post_order '{"order_id":"p-m304-probe"}')
check "P0-order-route-live" "400" "$PROBE"
if [[ "$PROBE" == "404" ]]; then echo "FATAL /mock/orders 不存在：make images && make up 后重跑"; exit 1; fi
check "P0-probe-left-no-row" 0 "$(MY "SELECT count(*) FROM xsl_mock.mock_order WHERE order_id='p-m304-probe';" | head -1)"
OFF=$(set_push false 10)
check "P0-push-scheduled-off" "false" "$OFF"

# ---------------- A. 扮演购票方 ----------------
echo "-- A 抓 xy_click_id → 落 CH → 分钟关闭 → 回传 → 用它下单"
CODE=$(create_link "$CP" main)
i=1
while [[ $i -le $N_CLICKS ]]; do
  ONE=$(curl -s -o /dev/null -m 10 -w '%{http_code} %{redirect_url}' --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/s/$CODE")
  printf '%s\n' "$ONE" >> "$LOC_FILE"
  i=$((i+1))
done
check "A-302-count" "$N_CLICKS" "$(grep -c "^302 " "$LOC_FILE")"
# click_id 的形态是 c<snowflake>（M1-07/M1-08 的契约，DESIGN 6.2），所以取的是"到 & 或行尾为止"整段：
# 按纯数字取会匹配到空串（\([0-9]*\) 允许零个数字），于是抓出来的是 20 行空值——第一轮就是这样。
sed -n 's/.*xy_click_id=\([^&]*\).*/\1/p' "$LOC_FILE" | sort -u > "$CID_FILE"
check "A-clickid-unique-count" "$N_CLICKS" "$(grep -c "" "$CID_FILE")"
FIRST_LOC=$(grep -m1 "^302 " "$LOC_FILE" | sed 's/^302 //')
truthy "A-location-carries-clickid" "$( [[ "$FIRST_LOC" == *"xy_click_id="* ]] && echo 1 || echo 0 )" "Location 带着对账键：$FIRST_LOC"
# 跟着 302 走到底：购票页能打开，才对得上「用户真的带着 click_id 落到了购票页」
FINAL=$(curl -sk -m 10 -o "$PAGE_FILE" -w '%{http_code}' --resolve "mock.ticketsales.test:$LANDING_PORT:$IP" "$FIRST_LOC")
check "A-landing-page-200" "200" "$FINAL"
ECHO_MARK=$(grep -c '归因参数' "$PAGE_FILE")
truthy "A-landing-echoes-attribution" "$( [[ "$ECHO_MARK" -ge 1 ]] && echo 1 || echo 0 )" "购票页回显归因参数（页内 JS 读 location.search，M2-34）"

# 先等这 N_CLICKS 击真的落进 ClickHouse（Kafka 攒批 + CH async_insert 中间有窗口，不等会读到旧值）
landed=0; tries=0
while [[ $tries -lt 30 ]]; do
  CH_PV=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'")
  [[ "${CH_PV:-0}" == "$N_CLICKS" ]] && { landed=1; break; }
  sleep 2; tries=$((tries+1))
done
truthy "A-clicks-landed-in-ch" "$landed" "$N_CLICKS 击在 $((tries*2))s 内落进 ClickHouse（查到 ${CH_PV:-0} 行）"
check "A-clicks-in-ch" "$N_CLICKS" "${CH_PV:-0}"
# URL 上抓到的 click_id 与明细里的必须是同一批值：对账键不是两边各造一个，而是同一个（附录 B 的前提）
CH_IDS=$(CHQ "SELECT click_id FROM xsl.click_event FINAL WHERE short_code='$CODE' AND click_id != ''" | sort -u)
URL_IDS=$(cat "$CID_FILE")
check_same "A-clickid-same-value-on-both-sides" "$URL_IDS" "$CH_IDS" "$N_CLICKS 个值两侧逐个相同"

MAXMIN=$(CHQ "SELECT toString(toStartOfMinute(max(click_time))) FROM xsl.click_event WHERE short_code='$CODE'")
FROM=$(CHQ "SELECT toString(toStartOfMinute(min(click_time))) FROM xsl.click_event WHERE short_code='$CODE'")
TO=$(CHQ "SELECT toString(toStartOfMinute(max(click_time)) + INTERVAL 1 MINUTE) FROM xsl.click_event WHERE short_code='$CODE'")
LEFT=$(minute_closed_wait "$MAXMIN")
note "窗口 [$FROM, $TO)（点击跨了哪几分钟就取哪几分钟，两侧同一个区间），等最后一分钟关闭睡了 ${LEFT}s"
# recon 的 RESULT 行按空格取数，所以那边把窗口写成 ISO 的 'T' 形式；这里比的就是那个形态
FROM_ISO=$(printf '%s' "$FROM" | tr ' ' 'T')
TO_ISO=$(printf '%s' "$TO" | tr ' ' 'T')

RESP=$(manual_push)
note "本轮回传：tenants=$(jf "$RESP" tenants) items=$(jf "$RESP" items) sent=$(jf "$RESP" sent) duplicated=$(jf "$RESP" duplicated) rejected=$(jf "$RESP" rejected) errors=$(jf "$RESP" errors)"
PLATFORM_PV=$(MY "SELECT COALESCE(SUM(pv),0) FROM xsl_mock.attribution_stat WHERE campaign_id='$CP' AND minute >= '$FROM' AND minute < '$TO';" | head -1)
check "A-platform-pv-equals-clicks" "$N_CLICKS" "$PLATFORM_PV"
BID=$(MY "SELECT batch_id FROM xsl_mock.attribution_stat WHERE campaign_id='$CP' LIMIT 1;" | head -1)
printf '%s\n' "$BID" >> "$BATCH_FILE"
# 明细里每个 click_id 的点击时刻：下单时带回 first_click_time，与点击侧同一个时钟（见 recon 文件头）
CHQ "SELECT click_id, toString(click_time) FROM xsl.click_event FINAL WHERE short_code='$CODE' AND click_id != ''" > "$MAP_FILE"
check "A-map-has-all-clicks" "$N_CLICKS" "$(grep -c "" "$MAP_FILE")"

k=1; CREATED=0
while [[ $k -le $N_ORDERS ]]; do
  CID=$(sed -n "${k}p" "$MAP_FILE" | cut -f1)
  CT=$(sed -n "${k}p" "$MAP_FILE" | cut -f2)
  OID="o-m304-$RUN-$k"
  BODY=$(order_body "$OID" "$CID" "$CP" "$CT")
  ST=$(post_order "$BODY")
  RES=$(cat "$RESP_FILE")
  [[ "$ST" == "200" && "$RES" == *'"result":"created"'* ]] && CREATED=$((CREATED+1))
  printf '%s\n' "$OID" >> "$ORD_FILE"
  k=$((k+1))
done
check "A-orders-created" "$N_ORDERS" "$CREATED"
check "A-order-rows" "$N_ORDERS" "$(order_rows "$CP")"
MET=$(metric "$MOCK" xsl_mock_order_total 'result="created"')
truthy "A-metric-created" "$( [[ "$MET" != "0" ]] && echo 1 || echo 0 )" "计数器 xsl_mock_order_total{result=created} 有数（$MET）"
GETCNT=$(curl -s -m 10 "$MOCK/mock/orders?campaign_id=$CP" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')
check "A-orders-visible-via-get" "$N_ORDERS" "$GETCNT"

# ---------------- B. 订单幂等 ----------------
echo "-- B 幂等：同一 order_id 重发不得再插一行"
BEFORE=$(metric "$MOCK" xsl_mock_order_total 'result="duplicated"')
ROWS_B=$(order_rows "$CP")
DUP_OK=0
for oid in $(head -"$N_DUP" "$ORD_FILE"); do
  CID=$(MY "SELECT click_id FROM xsl_mock.mock_order WHERE order_id='$oid';" | head -1)
  CT=$(MY "SELECT DATE_FORMAT(first_click_time,'%Y-%m-%d %H:%i:%s') FROM xsl_mock.mock_order WHERE order_id='$oid';" | head -1)
  BODY=$(order_body "$oid" "$CID" "$CP" "$CT")
  ST=$(post_order "$BODY")
  RES=$(cat "$RESP_FILE")
  [[ "$ST" == "200" && "$RES" == *'"duplicated":true'* ]] && DUP_OK=$((DUP_OK+1))
done
check "B-reported-duplicated" "$N_DUP" "$DUP_OK"
check "B-row-count-unchanged" "$ROWS_B" "$(order_rows "$CP")"
OID1=$(head -1 "$ORD_FILE")
check "B-times-submitted-observed" 2 "$(MY "SELECT times_submitted FROM xsl_mock.mock_order WHERE order_id='$OID1';" | head -1)"
AFTER=$(metric "$MOCK" xsl_mock_order_total 'result="duplicated"')
check_delta "B-metric-duplicated-delta" "$BEFORE" "$AFTER" "$N_DUP"
note "times_submitted 就是「重复提交被吸收了几次」的观测点；② 的分母仍按订单条数算，没有虚高"

# ---------------- C. 订单校验 ----------------
echo "-- C 校验：缺对账键 / 缺订单号 → 400 且不留半行"
ROWS_C=$(order_rows "$CP")
BEFORE=$(metric "$MOCK" xsl_mock_order_total 'result="rejected_validation"')
CID1=$(head -1 "$CID_FILE")
ST=$(post_order "{\"click_id\":\"$CID1\",\"campaign_id\":\"$CP\"}")
check "C-missing-order-id-400" "400" "$ST"
RES=$(cat "$RESP_FILE")
truthy "C-reason-mentions-order-id" "$( [[ "$RES" == *"order_id"* ]] && echo 1 || echo 0 )" "拒收理由指向 order_id"
ST=$(post_order "{\"order_id\":\"o-m304-$RUN-nocid\",\"campaign_id\":\"$CP\"}")
check "C-missing-click-id-400" "400" "$ST"
check "C-no-partial-row" "$ROWS_C" "$(order_rows "$CP")"
AFTER=$(metric "$MOCK" xsl_mock_order_total 'result="rejected_validation"')
check_delta "C-metric-rejected-delta" "$BEFORE" "$AFTER" 2

# ---------------- D. 对账主组 ----------------
echo "-- D 对账：两条判定式 + recon 读到的基数与手算一致"
run_recon "$CP" "$FROM" "$TO"
echo "$RECON_OUT" | sed 's/^/  | /'
check "D-exit-zero" 0 "$RECON_RC"
check "D-transport-pass" "PASS" "$(rf "$RECON_OUT" transport)"
check "D-transport-diff" "0.00" "$(rf "$RECON_OUT" transport_diff_pct)"
check "D-join-pass" "PASS" "$(rf "$RECON_OUT" join)"
check "D-join-pct" "100.00" "$(rf "$RECON_OUT" join_pct)"
check "D-missing-zero" 0 "$(rf "$RECON_OUT" missing)"
check "D-orders-count" "$N_ORDERS" "$(rf "$RECON_OUT" orders)"
HAND_DETAIL=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE campaign_id = '$CP' AND click_time >= '$FROM' AND click_time < '$TO'")
HAND_PLATFORM=$(MY "SELECT COALESCE(SUM(pv),0) FROM xsl_mock.attribution_stat WHERE campaign_id='$CP' AND minute >= '$FROM' AND minute < '$TO';" | head -1)
HAND_ORDERS=$(MY "SELECT count(*) FROM xsl_mock.mock_order WHERE campaign_id='$CP' AND first_click_time >= '$FROM' AND first_click_time < '$TO';" | head -1)
check "D-detail-pv-matches-hand" "$HAND_DETAIL" "$(rf "$RECON_OUT" detail_pv)"
check "D-platform-pv-matches-hand" "$HAND_PLATFORM" "$(rf "$RECON_OUT" platform_pv)"
check "D-orders-matches-hand" "$HAND_ORDERS" "$(rf "$RECON_OUT" orders)"
check "D-window-echoed-from" "$FROM_ISO" "$(rf "$RECON_OUT" from)"
check "D-window-echoed-to" "$TO_ISO" "$(rf "$RECON_OUT" to)"
# 不给窗口时它自己按明细推：主组应当推出同一个区间（T+1 手工核对走的就是这条路径）
run_recon "$CP"
check "D-auto-window-exit" 0 "$RECON_RC"
check "D-auto-window-from" "$FROM_ISO" "$(rf "$RECON_OUT" from)"
check "D-auto-window-to" "$TO_ISO" "$(rf "$RECON_OUT" to)"
check "D-auto-window-missing" 0 "$(rf "$RECON_OUT" missing)"
check "D-auto-window-orders" "$N_ORDERS" "$(rf "$RECON_OUT" orders)"

# ---------------- E. 负向样本 ----------------
echo "-- E 负向样本：注入 $N_BAD 条引用不存在 click_id 的订单，工具必须完整抓出来"
BAD_OK=0
j=1
while [[ $j -le $N_BAD ]]; do
  FAKE="zz$RUN$j"                     # 非雪花形态，明细里必然没有
  BODY=$(order_body "o-m304bad-$RUN-$j" "$FAKE" "$BC" "$FROM")
  ST=$(post_order "$BODY")
  [[ "$ST" == "200" ]] && BAD_OK=$((BAD_OK+1))
  j=$((j+1))
done
check "E-bad-orders-submitted" "$N_BAD" "$BAD_OK"
check "E-bad-campaign-rows" "$N_BAD" "$(order_rows "$BC")"
IN_CH=$(CHQ "SELECT count() FROM xsl.click_event WHERE click_id LIKE 'zz$RUN%'")
check "E-fake-clickids-really-absent" 0 "$IN_CH"
run_recon "$BC" "$FROM" "$TO"
echo "$RECON_OUT" | sed 's/^/  | /'
check "E-exit-nonzero" 1 "$RECON_RC"
check "E-missing-exactly-injected" "$N_BAD" "$(rf "$RECON_OUT" missing)"
check "E-orders-counted" "$N_BAD" "$(rf "$RECON_OUT" orders)"
check "E-join-fail" "FAIL" "$(rf "$RECON_OUT" join)"
check "E-join-pct-zero" "0.00" "$(rf "$RECON_OUT" join_pct)"
check "E-transport-na" "n/a" "$(rf "$RECON_OUT" transport)"
run_recon "$BC" "$FROM" "$TO" --keep-bad
check "E-keep-bad-exit-zero" 0 "$RECON_RC"
check "E-keep-bad-still-reports" "$N_BAD" "$(rf "$RECON_OUT" missing)"

echo "-- E2 传输侧负样本：明细有点击、平台一颗都没收到 → ① 必须报差异，不能报 n/a"
# 这一条补的是 ① 的**报警路径**：E 段只证了 ② 会红。回传整批丢光时差异应当是 100.00%，
# 而 n/a 只该出现在"两侧都没数"——把这两个形态分开，工具才不会在真丢数时给出一个看起来无害的值。
m=1; OK2=0
CODE2=$(create_link "$TC" nocarry)
while [[ $m -le $N_NOCARRY ]]; do
  ST=$(curl -s -o /dev/null -m 10 -w '%{http_code}' --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/s/$CODE2")
  [[ "$ST" == "302" ]] && OK2=$((OK2+1))
  m=$((m+1))
done
check "E2-302-count" "$N_NOCARRY" "$OK2"
landed2=0; tries2=0
while [[ $tries2 -lt 30 ]]; do
  CH_PV2=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE2'")
  [[ "${CH_PV2:-0}" == "$N_NOCARRY" ]] && { landed2=1; break; }
  sleep 2; tries2=$((tries2+1))
done
truthy "E2-clicks-landed-in-ch" "$landed2" "$N_NOCARRY 击在 $((tries2*2))s 内落进 ClickHouse（查到 ${CH_PV2:-0} 行）"
FROM2=$(CHQ "SELECT toString(toStartOfMinute(min(click_time))) FROM xsl.click_event WHERE short_code='$CODE2'")
TO2=$(CHQ "SELECT toString(toStartOfMinute(max(click_time)) + INTERVAL 1 MINUTE) FROM xsl.click_event WHERE short_code='$CODE2'")
note "这一段故意**不回传**（此刻定时仍是关闭的、也没手动触发），窗口 [$FROM2, $TO2) 就是为了让平台侧空着"
run_recon "$TC" "$FROM2" "$TO2"
echo "$RECON_OUT" | sed 's/^/  | /'
check "E2-exit-nonzero" 1 "$RECON_RC"
check "E2-transport-fail" "FAIL" "$(rf "$RECON_OUT" transport)"
check "E2-diff-is-total-loss" "100.00" "$(rf "$RECON_OUT" transport_diff_pct)"
check "E2-detail-pv" "$N_NOCARRY" "$(rf "$RECON_OUT" detail_pv)"
check "E2-platform-pv-zero" 0 "$(rf "$RECON_OUT" platform_pv)"
check "E2-join-na" "n/a" "$(rf "$RECON_OUT" join)"
check "E2-orders-na" 0 "$(rf "$RECON_OUT" orders)"

# 分组没白分：主组的两条判定式不受注入影响
run_recon "$CP" "$FROM" "$TO"
check "E-main-still-clean" 0 "$RECON_RC"
check "E-main-orders-untouched" "$N_ORDERS" "$(rf "$RECON_OUT" orders)"
check "E-main-missing-still-zero" 0 "$(rf "$RECON_OUT" missing)"
note "假订单若混进主组，①不受影响但 ② 的分母被污染——分成两组，主组才保持「该有的样子」，"
note "而负向组专门证明这把键真的在比对两个集合，不是把同一个集合算了两遍"

# ---------------- F. 阈值真的被读 ----------------
echo "-- F 阈值：把门槛调到不可能满足，PASS 必须跟着变红"
run_recon "$CP" "$FROM" "$TO" --max-diff 0
check "F-max-diff-0-fails" 1 "$RECON_RC"
check "F-max-diff-0-transport" "FAIL" "$(rf "$RECON_OUT" transport)"
check "F-max-diff-0-join-still-pass" "PASS" "$(rf "$RECON_OUT" join)"
run_recon "$CP" "$FROM" "$TO" --min-join 101
check "F-min-join-101-fails" 1 "$RECON_RC"
check "F-min-join-101-join" "FAIL" "$(rf "$RECON_OUT" join)"
check "F-min-join-101-transport-still-pass" "PASS" "$(rf "$RECON_OUT" transport)"
run_recon "$CP" "$FROM" "$TO" --max-diff 5 --min-join 50
check "F-looser-thresholds-pass" 0 "$RECON_RC"

# ---------------- G. 观测项与复位 ----------------
echo "-- G 观测项（不进阈值）与定时复位"
run_recon "$CP" "$FROM" "$TO"
AGG_PV=$(rf "$RECON_OUT" agg_pv)
AGG_DIFF=$(rf "$RECON_OUT" agg_drift_pct)
truthy "G-agg-drift-printed" "$( [[ "$AGG_DIFF" =~ ^[0-9]+\.[0-9]+$ ]] && echo 1 || echo 0 )" "click_stat_minute Σpv=$AGG_PV、与明细差 ${AGG_DIFF}%（只打印：async_insert 未合并 part 的已知偏差，6.4）"
BACK=$(set_push true 3)
check "G-switch-restored" "true" "$BACK"
# 计数器随进程重启归零，所以"刚复位就读到 0"是对的而不是坏了：等第一轮定时真的跑过再读（沿用 m3-03 G 的等法）
waited=0; RAN=0
while [[ $waited -lt 90 ]]; do
  CNT=$(metric "$CONSUMER" xsl_push_seconds_count)
  [[ "$(printf '%s' "$CNT" | awk '{print ($1>0)?1:0}')" == "1" ]] && { RAN=1; break; }
  sleep 3; waited=$((waited+3))
done
truthy "G-scheduled-round-ran" "$RAN" "复位后 ${waited}s 内定时任务又跑了一轮（xsl_push_seconds_count=$CNT）——m3-03 的回传链路没被这轮改坏"

echo
echo "== 结果 PASS=$pass FAIL=$fail =="
[[ $fail -eq 0 ]] && echo "M3-04 验收全绿" || exit 1
