#!/usr/bin/env bash
# M1-09 验收（DEVELOPMENT_PLAN 验收列）：
# 创建→模拟点击 N=1000→CH count() FINAL 一致；Kafka 不可达期间点击→WAL 有记录→恢复重放后总数一致。
# 前置：sl-admin(8030)/sl-jump(8020)/sl-consumer(8040) 已起，compose kafka/clickhouse 健康。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文
ADMIN="${ADMIN:-http://localhost:8030}"
JUMP="${JUMP:-http://localhost:8020}"
STATS="${STATS:-http://localhost:8040}"
CH='http://127.0.0.1:8123/'
TENANT=1001
N_MAIN=${N_MAIN:-1000}
N_OUTAGE=${N_OUTAGE:-200}
# M1-10 起 jump 容器化：WAL 落在 jump-1 的 bind mount 上（compose 文件同级的 data/ 目录），
# 用脚本自身位置解析，避免从不同 cwd 调用时找不到目录。
WAL_DIR="${WAL_DIR:-$REPO_ROOT/deploy/compose/data/jump-wal/jump-1}"
KAFKA_CTR="${KAFKA_CTR:-xsl-kafka-1}"

ch() { curl -s -m 15 -u "$CH_CRED" "$CH" --data-binary "$1"; }
pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }

# 1) 创建带归因的链接（origin 带 query+fragment，顺带证明拼接插在 # 前）
RUN=$(python3 -c 'import time;print(int(time.time()))')
BODY=$(cat <<JSON
{"origin_url":"https://mock.ticketsales.test/e2e-m109?ticket=1#top",
 "channel_id":"c-m109","campaign_id":"p-m109-$RUN","promoter_id":"pr-7","redirect_type":1}
JSON
)
CREATED=$(curl -s -X POST "$ADMIN/api/v1/links" -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m109-$RUN" -d "$BODY")
CODE=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_code'])" "$CREATED") || { echo "FATAL create: $CREATED"; exit 1; }
echo "note: code=$CODE"

# 2) 单击：302 + 归因参数（channel/campaign/promoter/xy_click_id 且 fragment 结尾原样）
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' "$JUMP/s/$CODE")
check "click-302" 302 "$(curl -s -o /dev/null -w '%{http_code}' "$JUMP/s/$CODE")"
for p in "channel_id=c-m109" "campaign_id=p-m109-$RUN" "promoter_id=pr-7" "xy_click_id=c" "ticket=1"; do
  [[ "$LOC" == *"$p"* ]] && { echo "PASS location-has-$p"; ((pass++)); } || { echo "FAIL location-has-$p: $LOC"; ((fail++)); }
done
[[ "$LOC" == *"#top" ]] && { echo "PASS fragment-at-tail"; ((pass++)); } || { echo "FAIL fragment-at-tail: $LOC"; ((fail++)); }

# 3) utm 透传（白名单内、非白名单丢弃）
LOC2=$(curl -s -o /dev/null -w '%{redirect_url}' "$JUMP/s/$CODE?utm_source=douyin&evil=1")
[[ "$LOC2" == *"utm_source=douyin"* && "$LOC2" != *"evil"* ]] && { echo "PASS utm-passthrough"; ((pass++)); } || { echo "FAIL utm-passthrough: $LOC2"; ((fail++)); }

# 4) 批量点击 N_MAIN（并发 16 线程，跳跳转记录不跟随）
clicks() { # clicks <n> <tag>
python3 - "$1" "$JUMP" "$CODE" "$2" <<'PY'
import sys, threading, urllib.request
n, base, code, tag = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a): return None
op = urllib.request.build_opener(NoRedirect)
threads = min(n, 96)
base_cnt, rem = divmod(n, threads)
ts = []
for i in range(threads):
    cnt = base_cnt + (1 if i < rem else 0)
    def work(cnt=cnt):
        for _ in range(cnt):
            try: op.open(f"{base}/s/{code}?utm_source=bench", timeout=10)
            except urllib.error.HTTPError: pass
            except Exception as e: print("ERR", tag, e)
    th = threading.Thread(target=work); ts.append(th); th.start()
for th in ts: th.join()
PY
}
clicks "$N_MAIN" main

# 5) 端到端可见 <60s：CH count FINAL 达到 N_MAIN+2（前面手工 3 击中 2 次计入 main 前？）——精确口径：
#    手工点击产生了 3 条事件（步骤 2 两次 + 步骤 3 一次）
EXPECTED=$((N_MAIN + 3))
DEADLINE=$(( $(date +%s) + 60 ))
CNT=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  CNT=$(ch "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'")
  [ "$CNT" -ge "$EXPECTED" ] && break
  sleep 2
done
check "ch-count-final" "$EXPECTED" "$CNT"
LAT=$(( $(date +%s) - DEADLINE + 60 )); echo "note: visible-delay ${LAT}s (<60 目标)"

# 6) stats API 与 CH 一致
SV=$(curl -s "$STATS/api/v1/stats/links/$CODE" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['click_count'])")
check "stats-api-consistent" "$EXPECTED" "$SV"

# 7) Kafka 故障演练：pause broker→N_OUTAGE 击→WAL 出现记录→resume→重放→总数一致
# WAL 落盘是异步的（spring-kafka 的投递回调里才 append，delivery.timeout 2s），
# 而 15s 重放任务又会把在途文件整份摘走重发——所以"WAL 里有没有行"不是一个稳态量。
# 之前只 sleep 4 后看一次，慢一点就误判成 0 行（收口后回归实测到偶发 FAIL，
# 而同一轮的 final count 精确等于期望值，证明兜底与重放都是好的）。改成故障窗口内
# 边打边采样、取峰值：断言的是"兜底曾接住多少条"，而不是"某个瞬间还剩多少条"。
docker pause "$KAFKA_CTR" >/dev/null || { echo "FATAL pause"; exit 1; }
clicks "$N_OUTAGE" outage &
CLICK_JOB=$!
WAL_PEAK=0
while kill -0 "$CLICK_JOB" 2>/dev/null; do
  n=$(cat "$WAL_DIR"/click-event-*.log 2>/dev/null | wc -l | tr -d ' ')
  [[ "${n:-0}" -gt "$WAL_PEAK" ]] && WAL_PEAK=$n
  sleep 1
done
wait "$CLICK_JOB"
# 回调有 2s 投递超时，批次打完再补采样 8s，覆盖最后几条落盘
for _ in $(seq 8); do
  n=$(cat "$WAL_DIR"/click-event-*.log 2>/dev/null | wc -l | tr -d ' ')
  [[ "${n:-0}" -gt "$WAL_PEAK" ]] && WAL_PEAK=$n
  sleep 1
done
if [[ "$WAL_PEAK" -ge "$N_OUTAGE" ]]; then
  echo "PASS wal-captured（故障窗口峰值 $WAL_PEAK 行 ≥ $N_OUTAGE 击，边打边采样）"; ((pass++))
else
  echo "FAIL wal-captured: 峰值仅 $WAL_PEAK/$N_OUTAGE 行，dir=$WAL_DIR"; ((fail++))
fi
# 故障期间跳转本身仍 302（不阻塞响应）
check "jump-302-during-outage" 302 "$(curl -s -o /dev/null -w '%{http_code}' "$JUMP/s/$CODE")"
docker unpause "$KAFKA_CTR" >/dev/null
# 等重放调度（15s 周期）+ 消费入库
DEADLINE=$(( $(date +%s) + 120 ))
TOTAL=$(ch "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'")
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  TOTAL=$(ch "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$CODE'")
  WAL_LINES=$(cat "$WAL_DIR"/click-event-*.log 2>/dev/null | wc -l | tr -d ' ')
  [ "$TOTAL" -ge "$((EXPECTED + N_OUTAGE + 1))" ] && break
  sleep 3
done
# 故障期间点击 = N_OUTAGE + 1（步骤 7 的 jump-302 探测也计一次）
check "total-after-replay" "$((EXPECTED + N_OUTAGE + 1))" "$TOTAL"
WAL_FINAL=$(cat "$WAL_DIR"/click-event-*.log 2>/dev/null | wc -l | tr -d ' ')
if [ "${WAL_FINAL:-0}" -le 2 ]; then echo "PASS wal-drained ($WAL_FINAL lines)"; ((pass++)); else echo "FAIL wal-drained: $WAL_FINAL lines 残留"; ((fail++)); fi
# 丢失率首证：期望 vs 实际（重复由 event_id 幂等消化，FINAL 计数恰等）
echo "note: loss-rate evidence expected=$((EXPECTED + N_OUTAGE + 1)) actual=$TOTAL"

echo "== $pass passed, $fail failed =="
# 清理：CH 事件突变删除 + MySQL 三表 + Redis 缓存（jump 侧 WAL 保留最后记录供检查）
ch "ALTER TABLE xsl.click_event DELETE WHERE short_code = '$CODE'" >/dev/null
for DB in xsl_00 xsl_01; do
  $MYSQL_EXEC -uroot "$DB" -e \
    "DELETE FROM short_link WHERE short_code='$CODE'; DELETE FROM link_route WHERE short_code='$CODE'; DELETE FROM code_tenant_index WHERE short_code='$CODE'; DELETE FROM short_code_pool WHERE short_code='$CODE'; DELETE FROM outbox WHERE entity_id='$CODE';" 2>/dev/null
done
docker exec xsl-redis-1 redis-cli DEL "sl:r:$CODE" "sl:r:nx:$CODE" "sl:code:$CODE" "sl:cnt:$CODE" >/dev/null
[[ $fail -eq 0 ]]
