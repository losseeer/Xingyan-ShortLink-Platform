#!/usr/bin/env bash
# M2-32 验收（UA/容器特征引擎的端到端证据）：
#   A. 13 条真实 UA 经 nginx 打进跳转，逐条在 ClickHouse 明细里对上 (device_type, os)
#   B. 空 UA / 工具类 UA 落 unknown，而不是空串（"没带 UA"与"带了但认不出"必须分得开）
#   C. 词汇表贯通到聚合层：click_stat_minute 的 wx_pv / douyin_pv / browser_pv 按同一套 key 命中
#      （MV 里的字面量是 'wechat'/'douyin'，所以特征表 key 与它不一致时这里会静默变 0——本段就是防这个）
# 前置：make images && make up（jump 为含 M2-32 分类器的镜像）。
# 口径：断言走 nginx :80 真实入口（与 accept-m2-13 一致）；样本每对的 (容器,设备) 唯一，
#       这样"逐条 count==1"就同时证明了分类正确与不重复入库。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 deploy/compose/.env 注入，脚本里不写明文

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
JUMP="${JUMP_DIRECT:-http://localhost:8020}"
CH='http://127.0.0.1:8123/'
TENANT=1001
RCLI="$COMPOSE exec -T redis redis-cli"
RUN=$(date +%s)
CAMPAIGN="p-m232-$RUN"
CODES=()

ch() { curl -s -m 15 -u "$CH_CRED" "$CH" --data-binary "$1"; }
pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
note() { echo "     · $*"; }

# 样本：<名字>|<期望容器>|<期望设备>|<UA 原文>（UA 里不含竖线，可直接按 | 切）
SAMPLES=(
  "wechat-iphone|wechat|ios|Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.40(0x18002832) NetType/WIFI Language/zh_CN"
  "wechat-android|wechat|android|Mozilla/5.0 (Linux; Android 13; M2102J2SC Build/TKQ1.220829.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/107.0.5304.141 Mobile Safari/537.36 MMWEBID/9527 MicroMessenger/8.0.40.2420(0x28002837) WeChat/arm64 Weixin NetType/WIFI ABI/arm64"
  "wechat-windows-pc|wechat|desktop|Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36 NetType/WIFI MicroMessenger/7.0.20.1781(0x6001463B) WindowsWechat(0x63090c1b)"
  "douyin-iphone|douyin|ios|Mozilla/5.0 (iPhone; CPU iPhone OS 16_6_1 like Mac OS X) AppleWebKit/604.1.3 (KHTML, like Gecko) Mobile/15E148 aweme_24.6.0 NetType/WiFi"
  "douyin-android|douyin|android|Mozilla/5.0 (Linux; U; Android 13; zh-CN; V2199A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 Mobile Safari/537.36 douyin_29.6.0"
  "mobile-qq-android|qq|android|Mozilla/5.0 (Linux; Android 13; 2211133C Build/TKQ1.221114.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/107.0.5304.141 Mobile Safari/537.36 QQ/9.0.9.4460"
  "qqbrowser-windows|qq|desktop|Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36 QQBrowser/11.0.4850.0"
  "weibo-iphone|weibo|ios|Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Weibo (iPhone15,2__weibo__13.7.0__iphone__os16.6)"
  "weibo-android|weibo|android|Mozilla/5.0 (Linux; U; Android 12; zh-CN; M2102J2SC Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 UCBrowser/15.0.1.303 Mobile Safari/537.36 Weibo (Xiaomi-M2102J2SC__weibo__13.5.2__android__os12)"
  "alipay-android|alipay|android|Mozilla/5.0 (Linux; U; Android 13; zh-CN; 2211133C Build/TKQ1.221114.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UWS/5.12.9.0 Mobile Safari/537.36 AliApp(AP/10.5.56.2000) AlipayClient/10.5.56.2000 Language/zh-Hans"
  "safari-iphone|unknown|ios|Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
  "chrome-windows|unknown|desktop|Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
  "curl-no-browser|unknown|unknown|curl/8.4.0"
)

create_link() {
  local body resp
  # rate_limit_per_minute 给到 10 万：本脚本要在同一分钟内对同一 code 打十几击，
  # 不给这个字段就会从第 61 击起变 429（这里测的是分类不是频控）。
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/m232?seat=A","channel_id":"c-m232","campaign_id":"%s","promoter_id":"pr-1","redirect_type":1,"rate_limit_per_minute":100000}' "$CAMPAIGN")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m232-$RUN" -d "$body")
  if ! code=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_code'])" "$resp" 2>/dev/null); then
    echo "FATAL 创建链接失败: $resp" >&2; exit 1
  fi
  echo "$code"
}

wait_for_events() { # wait_for_events <code> <期望行数> → 实际行数（最多等 40s，事件链是异步的）
  local code=$1 want=$2 got=0 i
  for i in $(seq 1 40); do
    got=$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$code'")
    [[ "$got" == "$want" ]] && break
    sleep 1
  done
  echo "$got"
}

cleanup() {
  [[ ${#CODES[@]} -eq 0 ]] && return 0
  local in; in=$(printf "'%s'," "${CODES[@]}"); in="(${in%,})"
  for db in xsl_00 xsl_01; do
    $MYSQL_EXEC -N -uroot "$db" -e \
      "DELETE FROM link_route WHERE short_code IN $in;
       DELETE FROM code_tenant_index WHERE short_code IN $in;
       DELETE FROM short_link WHERE short_code IN $in;
       DELETE FROM short_code_pool WHERE short_code IN $in;
       DELETE FROM route_rule WHERE short_code IN $in;
       DELETE FROM outbox WHERE aggregate_id IN $in;" >/dev/null 2>&1
  done
  for c in "${CODES[@]}"; do
    $RCLI DEL "sl:r:$c" "sl:code:$c" "sl:cnt:$c" >/dev/null 2>&1
    $RCLI --scan --pattern "sl:rl:$c:*" 2>/dev/null | xargs -r -n 50 $RCLI DEL >/dev/null 2>&1
    curl -s -m 20 -u "$CH_CRED" "$CH" \
      --data-binary "ALTER TABLE xsl.click_event DELETE WHERE short_code='$c'" >/dev/null 2>&1
  done
  note "cleanup: ${#CODES[@]} 个 fixture、Redis 键与 CH 事件已删除"
}
trap cleanup EXIT

curl -s -m 5 -o /dev/null "$JUMP/actuator/health" || { echo "sl-jump 不可达，先 make up"; exit 1; }
ch "SELECT 1" >/dev/null || { echo "ClickHouse 不可达，先 make up"; exit 1; }

CODE=$(create_link); CODES+=("$CODE")
note "fixture code=$CODE campaign=$CAMPAIGN，样本 ${#SAMPLES[@]} 条"

# ── A. 逐条 UA 重放 → 明细里对上 (device_type, os)
statuses=""
for row in "${SAMPLES[@]}"; do
  IFS='|' read -r name want_container want_os ua <<<"$row"
  st=$(curl -s -o /dev/null -m 10 --resolve "xy1.test:$PORT:$IP" -H "User-Agent: $ua" \
    -w '%{http_code}' "http://xy1.test:$PORT/s/$CODE")
  statuses+="$st "
done
# 频控已给到 10 万/分钟，这里全部应当是 302（若出现 429/404 说明测的不是分类而是别的东西）
N302=$(echo "$statuses" | tr ' ' '\n' | grep -c '^302$')
check "all-requests-302" "${#SAMPLES[@]}" "$N302"

GOT=$(wait_for_events "$CODE" "${#SAMPLES[@]}")
check "all-events-landed" "${#SAMPLES[@]}" "$GOT"

for row in "${SAMPLES[@]}"; do
  IFS='|' read -r name want_container want_os ua <<<"$row"
  n=$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$CODE' AND device_type='$want_container' AND os='$want_os'")
  check "ua-$name" 1 "$n"
done

# ── B. 空 UA 与"认不出"都不留空串
EMPTY=$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$CODE' AND (device_type='' OR os='')")
check "no-empty-device-type-or-os" 0 "$EMPTY"
# 真·空 UA（curl -H 'User-Agent;' 发一个空值头）也要落 unknown
curl -s -o /dev/null -m 10 --resolve "xy1.test:$PORT:$IP" -H 'User-Agent;' "http://xy1.test:$PORT/s/$CODE"
BLANK_GOT=$(wait_for_events "$CODE" "$(( ${#SAMPLES[@]} + 1 ))")
check "blank-ua-landed" "$(( ${#SAMPLES[@]} + 1 ))" "$BLANK_GOT"
check "blank-ua-is-unknown-not-empty" 1 \
  "$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$CODE' AND device_type='unknown' AND os='unknown' AND user_agent=''")"

# ── C. 词汇表贯通到聚合层（MV 的字面量与特征表 key 同一个字符串才成立）
STATS=$(ch "SELECT sum(wx_pv), sum(douyin_pv), sum(browser_pv), sum(pv) FROM xsl.click_stat_minute WHERE campaign_id='$CAMPAIGN' FORMAT TSV" | tr '\t' ' ')
check "stat-minute-vocabulary" "3 2 9 14" "$STATS"

echo "== $pass passed, $fail failed =="
[[ $fail -eq 0 ]]
