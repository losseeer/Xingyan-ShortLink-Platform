#!/usr/bin/env bash
# M3-03 验收（归因回传 + mock 宣发平台，DESIGN 6.3-3 / 7.3）：
#   A. 端到端闭环：真点击 → ClickHouse 明细 → 回传 → mock 台账与桶值 == 同窗口明细现算值（pv/uv/valid_pv/bot_pv）
#   B. 幂等：同一轮再推一次 → 同 batch_id 被判重复、桶值不重复计入、times_received 记下吸收次数
#   C. 校验和：脚本按 DESIGN 7.3 的算法**独立**造一个自洽批次（顺带钉住 Java 两端没跑偏）；篡改 pv → 400 且台账不留行
#   D. 身份：错签 / 越窗时间戳 / 冒充别的租户 / 没有签名头 → 401 / 401 / 403 / 401
#   E. 迟到吸收：往 CH 插一条落在"已推过的那一分钟"的迟到点击 → 内容变全 → 新 batch_id 覆盖旧值（不是被幂等丢掉）
#   F. 取数代价实测（ITER-M3 §3 的数字）：ClickHouse 自己的 read_rows/read_bytes/elapsed + 服务自报 query_millis
#   G. 开关透传与定时本身：XSL_PUSH_ENABLED=false 时定时不投；复位后 ≤90s 内跑过一轮（xsl_push_seconds_count>0）
# 前置：make images && make up && make db-init（xsl_mock 的两张表来自 init/mysql/03-mock-schema.sql）。
# 口径：A–F 在"定时关闭"的窗口里跑，并把回看拉到 10 个分钟（E 的迟到才不会因时间推移落圈外）；
#       短码与批次号一律记在**文件**里——命令替换是子 shell，数组在那边加不回来（ITER-M3 §4 记过）；
#       所有断言都先取值再 check，不写三层嵌套引号（macOS 的 bash 3.2 会解析错位）。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
CONSUMER="${CONSUMER_DIRECT:-http://localhost:8040}"
MOCK="${MOCK_DIRECT:-http://localhost:8050}"
PUSH_PATH="/mock/attribution/batches"
PUSH_KEY="${XSL_PUSH_API_KEY:-xy-key-push-mock-001}"   # 设计内的演示密钥（DESIGN 9.4），不是环境凭据
TENANT=1001
RCLI="$COMPOSE exec -T redis redis-cli"   # 与 m3-02 一样由脚本自己定义：lib-devenv 只给 mysql/clickhouse 那两个
RUN=$(date +%s)
CP="p-m303-$RUN"          # campaign 用唯一串：桶值定位与清理都靠它
CH="c-m303-$RUN"
CODE_FILE="/tmp/m303-codes.$RUN.txt"
BATCH_FILE="/tmp/m303-batches.$RUN.txt"
SYN_FILE="/tmp/m303-syn.$RUN.txt"
: > "$CODE_FILE"; : > "$BATCH_FILE"
PUSH_FLIPPED=0

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
check_le() { if [[ "$3" -le "$2" ]]; then echo "PASS $1 (${3}${4:-} ≤ ${2}${4:-})"; ((pass++)); else echo "FAIL $1 actual=${3}${4:-} limit=${2}${4:-}"; ((fail++)); fi; }
truthy() { if [[ "$2" == "1" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 ($3)"; ((fail++)); fi; }
note() { echo "     · $*"; }

MY() { $MYSQL_CLI -N -B -e "$1"; }
CHQ() { curl -s -u "$CH_CRED" "http://127.0.0.1:8123/" --data-binary "$1" | tr -d '\r'; }
CH_SUMMARY() {
  curl -s -u "$CH_CRED" "http://127.0.0.1:8123/?wait_end_of_query=1" -D /tmp/m303-h.txt -o /tmp/m303-r.txt --data-binary "$1"
  grep -i '^X-ClickHouse-Summary' /tmp/m303-h.txt | tr -d '\r' | sed 's/^[^:]*: //'
}
jf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\([0-9-]*\).*/\1/p"; }     # 整数（不嵌 python：M3-01 的引号教训）
sum_field() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\\([0-9]*\\)\".*/\1/p"; }
metric() { # metric <base-url> <指标名> [标签子串]
  # 名称后面可以是结尾（无标签的指标 $1 就是光秃秃的名字）、一个空格或 { ——
  # 少了"结尾"这一支，xsl_push_seconds_count 这种无标签指标会永远读成 0（本机踩过）。
  local v
  v=$(curl -s -m 5 "$1/actuator/prometheus" \
      | awk -v n="$2" -v l="${3:-}" '$1 ~ ("^" n "($|[[:space:]]|\\{)") && (l == "" || index($1,l)>0) {print $2; exit}')
  echo "${v:-0}"
}

sign() { # sign <secret> <method> <path> <ts> <nonce> <body> → hex hmac-sha256（与 accept-m1-04 同一份写法）
  python3 - "$@" <<'PYSIGN'
import sys, hmac, hashlib
secret, method, path, ts, nonce, body = sys.argv[1:7]
canonical = "\n".join([method, path, ts, nonce, hashlib.sha256(body.encode()).hexdigest()])
print(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest())
PYSIGN
}

post_batch() { # post_batch <body> <ts> <nonce> <sig> → HTTP 状态码（响应体留在 /tmp/m303-resp）
  curl -s -o /tmp/m303-resp -w '%{http_code}' -X POST "$MOCK$PUSH_PATH" \
    -H 'Content-Type: application/json' -H "X-Api-Key: $PUSH_KEY" \
    -H "X-Timestamp: $2" -H "X-Nonce: $3" -H "X-Signature: $4" -d "$1"
}
post_signed() { # post_signed <body> <nonce> → 状态码（ts 取当下、签名与头同源，避免两侧串不一致）
  local ts sig
  ts=$(date +%s)000
  sig=$(sign "$PUSH_KEY" POST "$PUSH_PATH" "$ts" "$2" "$1")
  post_batch "$1" "$ts" "$2" "$sig"
}
post_signed_at() { # post_signed_at <body> <ts> <nonce> → 状态码（用于越窗时间戳这种"签名有效但时间不对"的用例）
  local sig
  sig=$(sign "$PUSH_KEY" POST "$PUSH_PATH" "$2" "$3" "$1")
  post_batch "$1" "$2" "$3" "$sig"
}
manual_push() { curl -s -m 60 -XPOST "$CONSUMER/internal/attribution/push"; }

create_link() {
  local body resp code
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/%s","channel_id":"%s","campaign_id":"%s","promoter_id":"pr-m303","redirect_type":1,"rate_limit_per_minute":600}' "$CP" "$CH" "$CP")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m303-$RUN-$RANDOM" -d "$body")
  code=$(printf '%s' "$resp" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
  if [[ -z "$code" ]]; then echo "FATAL 创建失败: $resp" >&2; exit 1; fi
  printf '%s\n' "$code" >> "$CODE_FILE"        # 子 shell 里改数组带不出来，所以走文件
  echo "$code"
}
clicks() { local i; for i in $(seq 1 "$2"); do curl -s -o /dev/null -m 10 --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/s/$1"; done; }
minute_closed_wait() { # 等某个分钟（'YYYY-MM-DD HH:MM:SS'，点击侧墙上时钟）真正过去，回报睡了多久
  local remain
  remain=$(python3 - "$1" <<'PYWAIT'
import sys, datetime
m = datetime.datetime.strptime(sys.argv[1], "%Y-%m-%d %H:%M:%S")
# CH 会话时区是 UTC 而 click_time 存的是上海墙上时钟（约定见 DESIGN 4.2 与 03-mock-schema.sql 的注释）：
# 两侧按同一条约定读写，所以这里只算"这一分钟结束还差几秒"，不做时区换算。
print(max(0, int((m + datetime.timedelta(seconds=65) - datetime.datetime.now()).total_seconds())))
PYWAIT
)
  [[ "$remain" -gt 0 ]] && sleep "$remain"
  echo "$remain"
}
mock_row() { MY "SELECT pv, uv, valid_pv, bot_pv, batch_id FROM xsl_mock.attribution_stat WHERE campaign_id='$CP' AND channel_id='$CH' AND minute='$1';" | head -1; }
mock_field() { mock_row "$1" | awk "{print \$$2}"; }
ledger_times() { MY "SELECT times_received FROM xsl_mock.attribution_batch WHERE batch_id='$1';" | head -1; }
ledger_count() { MY "SELECT count(*) FROM xsl_mock.attribution_batch;" | head -1; }
set_push() { # set_push <enabled> <lookback>：改环境变量重启 consumer（顺带证明 compose 真的透传）
  XSL_PUSH_ENABLED=$1 XSL_PUSH_LOOKBACK=$2 $COMPOSE up -d --wait consumer >/dev/null 2>&1
  [[ "$1" == "false" ]] && PUSH_FLIPPED=1 || PUSH_FLIPPED=0
  docker exec xsl-consumer-1 printenv XSL_PUSH_ENABLED 2>/dev/null
}

cleanup() {
  set +u   # 收尾要跑完：中途一个未定义变量就 exit，等于前面所有断言的证据都留在校库里
  # 被中断也必须把定时开关复位，否则下一轮的"关闭定时"根本不是它以为的那样（ITER-M3 §4 那条教训）
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
    CHQ "ALTER TABLE xsl.click_event DELETE WHERE short_code IN $in" >/dev/null   # 明细沿用既有测试的 mutation 清法
  fi
  # mock 侧要按 campaign 前缀扫，不能只删"本轮那一串"：前几轮失败/中断时留下的行会一直挂在台账里，
  # 让下一次看 mock 的人分不清哪是本轮证据、哪是历史垃圾（本轮实测就攒出了 9 个孤儿批次）。
  local seen ids
  seen=$(MY "SELECT DISTINCT batch_id FROM xsl_mock.attribution_stat WHERE campaign_id LIKE 'p-m303-%';")
  ids="$(printf '%s\n%s' "$seen" "$( [[ -s "$BATCH_FILE" ]] && cat "$BATCH_FILE" )")"
  ids=$(printf '%s' "$ids" | tr ' ' '\n' | grep -v '^$' | sort -u)
  MY "DELETE FROM xsl_mock.attribution_stat WHERE campaign_id LIKE 'p-m303-%';" >/dev/null
  if [[ -n "$ids" ]]; then
    local in; in=$(printf "'%s'," $ids); in="(${in%,})"
    MY "DELETE FROM xsl_mock.attribution_batch WHERE batch_id IN $in;" >/dev/null
  fi
  rm -f "$CODE_FILE" "$BATCH_FILE" "$SYN_FILE" /tmp/m303-h.txt /tmp/m303-r.txt /tmp/m303-resp
}
trap cleanup EXIT

echo "== M3-03 归因回传（run=$RUN campaign=$CP）=="
HEALTH=$(curl -s -m 5 "$MOCK/actuator/health")
truthy "P0-mock-up" "$( [[ "$HEALTH" == *'"status":"UP"'* ]] && echo 1 || echo 0 )" "mock 宣发平台健康"
T_TABLES=$(MY "SELECT count(*) FROM information_schema.tables WHERE table_schema='xsl_mock';" | head -1)
if [[ "${T_TABLES:-0}" != "2" ]]; then echo "FATAL xsl_mock 的表还没建（当前 $T_TABLES 张）：先 make db-init"; exit 1; fi
check "P0-mock-schema" 2 "$T_TABLES"

# ---------------- A. 端到端闭环 ----------------
echo "-- A 端到端：真点击 → CH → 回传 → 平台落账"
OFF=$(set_push false 10)
check "A-switch-off" "false" "$OFF"
CODE=$(create_link)
clicks "$CODE" 12
# 先等这 12 击真的落进 ClickHouse：Kafka 侧攒批 + fetch.max.wait.ms=2000 + CH async_insert，
# 点击"发完"到"查得到"中间有个几秒的窗口。不等它就取 max(click_time) 会拿到 epoch 0，整条链路一起塌。
landed=0; tries=0
while [[ $tries -lt 30 ]]; do
  CH_PV=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'")
  [[ "${CH_PV:-0}" == "12" ]] && { landed=1; break; }
  sleep 2; tries=$((tries+1))
done
truthy "A-clicks-landed-in-ch" "$landed" "12 击在 $((tries*2))s 内落进 ClickHouse（查到的是 ${CH_PV:-0} 行）"
MINUTE=$(CHQ "SELECT toStartOfMinute(max(click_time)) FROM xsl.click_event WHERE short_code='$CODE'")
LEFT=$(minute_closed_wait "$MINUTE")
check "A-clicks-in-ch" 12 "$CH_PV"
note "点击所在分钟 $MINUTE，等它关闭睡了 ${LEFT}s（回传只推已关闭的分钟）"
CH_UV=$(CHQ "SELECT uniqExact(ip_hash) FROM xsl.click_event FINAL WHERE short_code='$CODE'")

RESP=$(manual_push)
ITEMS=$(jf "$RESP" items); SENT=$(jf "$RESP" sent)
note "本轮：tenants=$(jf "$RESP" tenants) items=$ITEMS sent=$SENT duplicated=$(jf "$RESP" duplicated) rejected=$(jf "$RESP" rejected) skipped=$(jf "$RESP" skipped) errors=$(jf "$RESP" errors)"
truthy "A-window-had-data" "$( [[ ${ITEMS:-0} -ge 1 ]] && echo 1 || echo 0 )" "回看窗口取到了数据（items=$ITEMS）"
truthy "A-batch-delivered" "$( [[ ${SENT:-0} -ge 1 ]] && echo 1 || echo 0 )" "至少一批新计入（sent=$SENT）"
BATCH_A=$(mock_field "$MINUTE" 5)
printf '%s\n' "$BATCH_A" >> "$BATCH_FILE"
check "A-ledger-pv" 12 "$(mock_field "$MINUTE" 1)"
check "A-ledger-uv" "$CH_UV" "$(mock_field "$MINUTE" 2)"
check "A-ledger-valid-pv" 12 "$(mock_field "$MINUTE" 3)"
check "A-ledger-bot-pv" 0 "$(mock_field "$MINUTE" 4)"
case "$BATCH_A" in
  xsl-1001-????????????-????????????-??????????) check "A-batch-id-shape" ok ok ;;
  *) check "A-batch-id-shape" "xsl-{租户}-{起}-{止}-{内容hash前10}" "$BATCH_A" ;;
esac
ONE=$(MY "SELECT count(*) FROM xsl_mock.attribution_batch WHERE batch_id='$BATCH_A';" | head -1)
check "A-ledger-one-row" 1 "$ONE"

# ---------------- B. 幂等 ----------------
echo "-- B 幂等：同内容再推一次不得把数加两遍"
RESP=$(manual_push)
check "B-reported-duplicated" 1 "$(jf "$RESP" duplicated)"
check "B-not-double-counted" 12 "$(mock_field "$MINUTE" 1)"
check "B-times-received" 2 "$(ledger_times "$BATCH_A")"
note "times_received 就是「幂等吸收了几次」的观测点；桶上只保留最终值，没有累加"

# ---------------- C. 校验和 ----------------
echo "-- C 校验和：接收端重算，不信发送端"
# 按 DESIGN 7.3 的算法在脚本里独立造一个自洽批次（规范化串 = campaign|channel|分钟epochUTC|pv|uv|valid|bot，行间逗号）。
# 独立算有两层意义：接收端确实在重算而不是照抄发送端给的串；Java 两端与文档没跑偏。
# 批次号由内容算出来，所以窗口与 items 都在脚本里定——不能借用真实链路那个 id（它盖的是整个窗口的所有桶）。
CP="$CP" CHN="$CH" MIN="$MINUTE" PV=12 UV="$CH_UV" VA=12 BO=0 TEN="$TENANT" python3 - > "$SYN_FILE" <<'PYSYN'
import os, json, hashlib, datetime
minute = datetime.datetime.strptime(os.environ["MIN"], "%Y-%m-%d %H:%M:%S")
frm, to = minute, minute + datetime.timedelta(minutes=1)
# items.minute 与信封的 from/to 一律 ISO（'T'）：Jackson 的 LocalDateTime 反序列化不认空格形式，
# 混用会让一个内容完全正确的批次被判成"请求体不合法"——报的还是解析错，看不出是格式约定不一致。
iso = "%Y-%m-%dT%H:%M:%S"
epoch = int(minute.replace(tzinfo=datetime.timezone.utc).timestamp())
row = "%s|%s|%d|%s|%s|%s|%s" % (os.environ["CP"], os.environ["CHN"], epoch,
                                os.environ["PV"], os.environ["UV"], os.environ["VA"], os.environ["BO"])
checksum = hashlib.sha1(row.encode()).hexdigest()
fmt = "%Y%m%d%H%M"
batch_id = "xsl-%s-%s-%s-%s" % (os.environ["TEN"], frm.strftime(fmt), to.strftime(fmt), checksum[:10])
body = {"batch_id": batch_id, "tenant_id": int(os.environ["TEN"]),
        "from": frm.strftime(iso), "to": to.strftime(iso),
        "checksum": checksum,
        "items": [{"campaign_id": os.environ["CP"], "channel_id": os.environ["CHN"],
                   "minute": frm.strftime(iso), "pv": int(os.environ["PV"]), "uv": int(os.environ["UV"]),
                   "valid_pv": int(os.environ["VA"]), "bot_pv": int(os.environ["BO"])}]}
print(json.dumps(body, separators=(",", ":")))
print(batch_id)
PYSYN
BODY_OK=$(head -1 "$SYN_FILE")
BATCH_S=$(tail -1 "$SYN_FILE")
printf '%s\n' "$BATCH_S" >> "$BATCH_FILE"
ST=$(post_signed "$BODY_OK" "m303-c-$RUN")
check "C-selfconsistent-batch-accepted" "200" "$ST"
ROWS=$(MY "SELECT count(*) FROM xsl_mock.attribution_batch WHERE batch_id='$BATCH_S' AND item_count=1;" | head -1)
check "C-ledger-records-it" 1 "$ROWS"
BEFORE=$(ledger_count)
BODY_BAD=$(printf '%s' "$BODY_OK" | sed 's/"pv":12/"pv":99/')
ST=$(post_signed "$BODY_BAD" "m303-c2-$RUN")
check "C-tampered-400" "400" "$ST"
RESPOK=$(cat /tmp/m303-resp)
truthy "C-reason-is-checksum" "$( [[ "$RESPOK" == *"checksum"* ]] && echo 1 || echo 0 )" "拒收理由指向校验和"
check "C-no-ledger-row-for-bad-batch" "$BEFORE" "$(ledger_count)"
MET=$(metric "$MOCK" xsl_mock_batch_total 'result="rejected_checksum"')
truthy "C-metric-rejected-checksum" "$( [[ "$MET" != "0" ]] && echo 1 || echo 0 )" "指标 rejected_checksum 有数（$MET）"
check "C-bucket-untouched" 12 "$(mock_field "$MINUTE" 1)"

# ---------------- D. 身份 ----------------
echo "-- D 身份：错签 / 越窗 / 越权 / 无签名头"
TS=$(date +%s)000; NONCE="m303-d-$RUN"
SIG=$(sign "$PUSH_KEY" POST "$PUSH_PATH" "$TS" "$NONCE" "$BODY_OK")
BADSIG="${SIG:0:12}deadbeef${SIG:20}"
ST=$(post_batch "$BODY_OK" "$TS" "$NONCE" "$BADSIG")
check "D-tampered-signature-401" "401" "$ST"
TS_OLD=$(( $(date +%s) - 600 ))000
ST=$(post_signed_at "$BODY_OK" "$TS_OLD" "m303-d-old-$RUN")
check "D-stale-timestamp-401" "401" "$ST"
BODY_OTHER=$(printf '%s' "$BODY_OK" | sed 's/"tenant_id":1001/"tenant_id":1002/')
ST=$(post_signed "$BODY_OTHER" "m303-d2-$RUN")
check "D-other-tenant-403" "403" "$ST"
ST=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$MOCK$PUSH_PATH" -H 'Content-Type: application/json' -d "$BODY_OK")
check "D-missing-headers-401" "401" "$ST"
check "D-no-extra-ledger-rows" "$BEFORE" "$(ledger_count)"

# ---------------- E. 迟到吸收 ----------------
echo "-- E 迟到：已推过的分钟又来一击，要覆盖而不是被丢掉"
LATE_ID="m303-late-$RUN"
LATE_SQL="INSERT INTO xsl.click_event (event_id, short_code, click_id, click_time, ip_hash, user_agent, referer, device_type, os, province, city, channel_id, campaign_id, promoter_id, utm_params, risk_score, is_bot, tenant_id)"
LATE_SQL="$LATE_SQL VALUES ('$LATE_ID', '$CODE', '$LATE_ID', '$MINUTE', 'late-ip-$RUN', '', '', 'unknown', 'unknown', '', '', '$CH', '$CP', 'pr-m303', '{}', 0, 0, $TENANT)"
CHQ "$LATE_SQL" >/dev/null
check "E-late-row-in-ch" 13 "$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'")"
RESP=$(manual_push)
BATCH_E=$(mock_field "$MINUTE" 5)
printf '%s\n' "$BATCH_E" >> "$BATCH_FILE"
check "E-bucket-overwritten" 13 "$(mock_field "$MINUTE" 1)"
truthy "E-new-batch-id" "$( [[ -n "$BATCH_E" && "$BATCH_E" != "$BATCH_A" ]] && echo 1 || echo 0 )" "内容变全 → id 也变：$BATCH_A → $BATCH_E"
check "E-old-batch-untouched" 2 "$(ledger_times "$BATCH_A")"
ROWS=$(MY "SELECT count(*) FROM xsl_mock.attribution_batch WHERE batch_id='$BATCH_E';" | head -1)
check "E-new-batch-in-ledger" 1 "$ROWS"
note "旧批次留在台账里（那是那一刻的真实值），桶上只保留最终值——「迟到被吸收」而不是「迟到被丢掉」"

# ---------------- F. 取数代价 ----------------
echo "-- F 每轮扫明细到底多贵（ITER-M3 §3 的数字从这里读）"
FROM_TS=$(CHQ "SELECT formatDateTime(toStartOfMinute(now()) - INTERVAL 10 MINUTE, '%Y-%m-%d %H:%i:%s')")
TO_TS=$(CHQ "SELECT formatDateTime(toStartOfMinute(now()), '%Y-%m-%d %H:%i:%s')")
AGG="SELECT tenant_id, campaign_id, channel_id, toStartOfMinute(click_time) AS minute, count() AS pv, uniqExact(ip_hash) AS uv, countIf(is_bot = 0 AND risk_score < 25) AS valid_pv, countIf(is_bot = 1) AS bot_pv"
AGG="$AGG FROM xsl.click_event FINAL WHERE click_time >= '$FROM_TS' AND click_time < '$TO_TS' AND campaign_id != '' GROUP BY tenant_id, campaign_id, channel_id, minute ORDER BY tenant_id, minute, campaign_id, channel_id"
SUM=$(CH_SUMMARY "$AGG")
READ_ROWS=$(sum_field "$SUM" read_rows); READ_BYTES=$(sum_field "$SUM" read_bytes); ELAPSED_NS=$(sum_field "$SUM" elapsed_ns)
TABLE_ROWS=$(CHQ "SELECT count() FROM xsl.click_event")
MS=$(( ${ELAPSED_NS:-0} / 1000000 ))
truthy "F-query-actually-ran" "$( [[ ${READ_ROWS:-0} -ge 1 ]] && echo 1 || echo 0 )" "10 分钟窗口：read_rows=$READ_ROWS、read_bytes=$READ_BYTES、耗时 ${MS}ms（表内 $TABLE_ROWS 行）"
RESP=$(manual_push)
QMS=$(jf "$RESP" query_millis)
check_le "F-service-window-query" 5000 "${QMS:-99999}" ms
note "服务自报本轮取数 ${QMS}ms——这个数字决定「明细 FINAL 还是聚合表」要不要改（DESIGN 6.3-3 留的出口）"

# ---------------- G. 复位与定时 ----------------
echo "-- G 复位：XSL_PUSH_ENABLED=true 后 @Scheduled 自己会跑"
BACK=$(set_push true 3)
check "G-switch-restored" "true" "$BACK"
waited=0; RAN=0
while [[ $waited -lt 90 ]]; do
  CNT=$(metric "$CONSUMER" xsl_push_seconds_count)
  [[ "$(printf '%s' "$CNT" | awk '{print ($1>0)?1:0}')" == "1" ]] && { RAN=1; break; }
  sleep 3; waited=$((waited+3))
done
truthy "G-scheduled-round-ran" "$RAN" "复位后 ${waited}s 内定时任务跑过一轮（xsl_push_seconds_count>0，进程是新起的）"
TAIL_CODE=$(create_link)
clicks "$TAIL_CODE" 5
note "顺手留了 5 击给下一轮定时投递；G 只验「定时会跑」这一件事，不等它投完"

echo
echo "== 结果 PASS=$pass FAIL=$fail =="
[[ $fail -eq 0 ]] && echo "M3-03 验收全绿" || exit 1
