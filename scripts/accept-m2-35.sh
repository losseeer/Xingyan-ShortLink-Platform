#!/usr/bin/env bash
# M2-35 验收（**M2 出口标准"UA 重放脚本全绿"就落在这一份**）：
#   A. 14 条真实 UA 逐条断言：命中的 target_type、响应形态（302 / 200 中间页）、
#      中间页里的 scheme / urlLink / 引导地址、以及 no-store 头
#   B. priority 跨维度排序：容器规则先于设备规则（Windows 里的 QQ 走 QQ 页而不是 PC 页）
#   C. 全不命中落 origin_url（curl 那条），不是错误也不是白屏
#   D. 同一批 UA 的 device_type/os 落进 ClickHouse（与 accept-m2-32 同一套词汇表口径）
#   E. 中间页里的 H5 地址带着本次 xy_click_id，跟着打完确实是 mock 购票页（M2-34 的最后一里）
#   F. 回退开关（放最后）：XSL_JUMP_MODE=direct302 之后同一批 UA 全 302、body 0B、无 no-store；
#      复位后再测一条仍回 200。容器里 printenv 一起断 —— 透传缺失时"改环境变量就能回退"是假的
# 前置：make images && make up（jump 为含 M2-33 的镜像）；make tls（mock 页 443 用）。
# 口径：全部走 nginx :80 真实入口；fixture 与事件按 code 清理；停机/切换类动作一律排在最后。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
MOCK_PORT="${MOCK_PORT:-443}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
JUMP="${JUMP_DIRECT:-http://localhost:8020}"
CH='http://127.0.0.1:8123/'
TENANT=1001
RCLI="$COMPOSE exec -T redis redis-cli"
RUN=$(date +%s)
CAMPAIGN="p-m235-$RUN"
ORIGIN="https://mock.ticketsales.test/show/m235-$RUN"
CODE=""
FLIPPED=0

ch() { curl -s -m 15 -u "$CH_CRED" "$CH" --data-binary "$1"; }
pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
note() { echo "     · $*"; }

# 样本：<名字>|<期望容器>|<期望设备>|<期望形态>|<期望串>|<UA 原文>
# 期望形态：scheme / urllink / intermediate = 200 中间页；redirect:<前缀> = 302
# 期望串：中间页里必须出现的标记（scheme 串、mock urlLink 前缀、或落地地址前缀）
SAMPLES=(
  "wechat-iphone|wechat|ios|scheme|xyapp://open/order?id=9|Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.40(0x18002832) NetType/WIFI Language/zh_CN"
  "wechat-android|wechat|android|scheme|xyapp://open/order?id=9|Mozilla/5.0 (Linux; Android 13; M2102J2SC Build/TKQ1.220829.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/107.0.5304.141 Mobile Safari/537.36 MMWEBID/9527 MicroMessenger/8.0.40.2420(0x28002837) WeChat/arm64 Weixin NetType/WIFI ABI/arm64"
  "wechat-windows-pc|wechat|desktop|scheme|xyapp://open/order?id=9|Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36 NetType/WIFI MicroMessenger/7.0.20.1781(0x6001463B) WindowsWechat(0x63090c1b)"
  "douyin-iphone|douyin|ios|urllink|wxaurl.cn/xsl-mock/|Mozilla/5.0 (iPhone; CPU iPhone OS 16_6_1 like Mac OS X) AppleWebKit/604.1.3 (KHTML, like Gecko) Mobile/15E148 aweme_24.6.0 NetType/WiFi"
  "douyin-android|douyin|android|urllink|wxaurl.cn/xsl-mock/|Mozilla/5.0 (Linux; U; Android 13; zh-CN; V2199A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 Mobile Safari/537.36 douyin_29.6.0"
  "mobile-qq-android|qq|android|redirect:https://mock.ticketsales.test/qq?|/qq?|Mozilla/5.0 (Linux; Android 13; 2211133C Build/TKQ1.221114.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/107.0.5304.141 Mobile Safari/537.36 QQ/9.0.9.4460"
  "qqbrowser-windows|qq|desktop|redirect:https://mock.ticketsales.test/qq?|/qq?|Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36 QQBrowser/11.0.4850.0"
  "weibo-iphone|weibo|ios|redirect:https://mock.ticketsales.test/qq?|/qq?|Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Weibo (iPhone15,2__weibo__13.7.0__iphone__os16.6)"
  "weibo-android|weibo|android|redirect:https://mock.ticketsales.test/qq?|/qq?|Mozilla/5.0 (Linux; U; Android 12; zh-CN; M2102J2SC Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 UCBrowser/15.0.1.303 Mobile Safari/537.36 Weibo (Xiaomi-M2102J2SC__weibo__13.5.2__android__os12)"
  "alipay-android|alipay|android|intermediate|购票前请确认票种规则|Mozilla/5.0 (Linux; U; Android 13; zh-CN; 2211133C Build/TKQ1.221114.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UWS/5.12.9.0 Mobile Safari/537.36 AliApp(AP/10.5.56.2000) AlipayClient/10.5.56.2000 Language/zh-Hans"
  "safari-iphone|unknown|ios|redirect:${ORIGIN}?|m235|Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
  "chrome-windows|unknown|desktop|redirect:https://mock.ticketsales.test/pc?|/pc?|Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
  "safari-macos|unknown|desktop|redirect:https://mock.ticketsales.test/pc?|/pc?|Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
  "openharmony|unknown|unknown|redirect:${ORIGIN}?|m235|Mozilla/5.0 (Phone; OpenHarmony 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 ArkWeb/4.0.6.1 Mobile HuaweiBrowser_1.5.9.302"
  "curl|unknown|unknown|redirect:${ORIGIN}?|m235|curl/8.4.0"
)

RULES='{"rules":[
 {"priority":10,"condition_type":"ua_container","condition_op":"eq","condition_value":"wechat",
  "target_type":"app_scheme","target_url":null,"scheme_url":"xyapp://open/order?id=9",
  "fallback_url":"https://mock.ticketsales.test/dl"},
 {"priority":20,"condition_type":"ua_container","condition_op":"eq","condition_value":"douyin",
  "target_type":"wx_urllink","target_url":"https://mock.ticketsales.test/mp-guide",
  "scheme_url":"/pages/order/detail?id=9","fallback_url":null},
 {"priority":30,"condition_type":"ua_container","condition_op":"in","condition_value":"qq,weibo",
  "target_type":"h5","target_url":"https://mock.ticketsales.test/qq","scheme_url":null,"fallback_url":null},
 {"priority":50,"condition_type":"ua_container","condition_op":"eq","condition_value":"alipay",
  "target_type":"intermediate","target_url":"https://mock.ticketsales.test/notice","scheme_url":null,"fallback_url":null},
 {"priority":60,"condition_type":"device","condition_op":"in","condition_value":"desktop",
  "target_type":"h5","target_url":"https://mock.ticketsales.test/pc","scheme_url":null,"fallback_url":null}
]}'
# priority 30 用 in:qq,weibo —— weibo 样本因此落 /qq 而不是某个 weibo 专属页：
# 这一条同时验证"in 列表里的第二个值也算命中"（条件值必须都在词汇表内，写 facebook 会被校验器 400 挡掉）。

create_link() {
  local resp
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m235-$RUN" \
    -d "{\"origin_url\":\"$ORIGIN\",\"channel_id\":\"c-m235\",\"campaign_id\":\"$CAMPAIGN\",\"promoter_id\":\"pr-35\",\"rate_limit_per_minute\":100000}")
  if ! CODE=$(python3 -c "import json,sys;print(json.loads(sys.argv[1])['data']['short_code'])" "$resp" 2>/dev/null); then
    echo "FATAL 创建链接失败: $resp" >&2; exit 1
  fi
}

put_rules() {
  local c
  c=$(curl -s -o /tmp/m235-put -w '%{http_code}' -X PUT "$ADMIN/api/v1/links/$CODE/rules" \
    -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -d "$RULES")
  check "rules-put-200" 200 "$c"
}

hit() { # hit <UA> → "<status> <location> <cacheControl> <bodyFile>"
  local ua=$1
  curl -s -o /tmp/m235-body -D /tmp/m235-h -m 10 --resolve "xy1.test:$PORT:$IP" -H "User-Agent: $ua" \
    -w '%{http_code}' "http://xy1.test:$PORT/s/$CODE" > /tmp/m235-st
  ST=$(cat /tmp/m235-st)
  LOC=$(grep -i '^location:' /tmp/m235-h | tr -d '\r' | cut -d' ' -f2-)
  CC=$(grep -i '^cache-control:' /tmp/m235-h | tr -d '\r' | cut -d' ' -f2-)
  echo "$ST|${LOC:-}|${CC:-}"
}

wait_for_events() {
  local want=$1 got=0 i
  for i in $(seq 1 40); do
    got=$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$CODE'")
    [[ "$got" == "$want" ]] && break
    sleep 1
  done
  echo "$got"
}

cleanup() {
  [[ -z "$CODE" ]] && return 0
  $MYSQL_EXEC -N -uroot xsl_00 -e "DELETE FROM short_link WHERE short_code='$CODE'; DELETE FROM link_route WHERE short_code='$CODE';
    DELETE FROM code_tenant_index WHERE short_code='$CODE'; DELETE FROM outbox WHERE aggregate_id='$CODE';
    DELETE FROM route_rule WHERE short_code='$CODE'; DELETE FROM short_code_pool WHERE short_code='$CODE';" >/dev/null 2>&1
  $MYSQL_EXEC -N -uroot xsl_01 -e "DELETE FROM short_link WHERE short_code='$CODE'; DELETE FROM link_route WHERE short_code='$CODE';
    DELETE FROM code_tenant_index WHERE short_code='$CODE'; DELETE FROM outbox WHERE aggregate_id='$CODE';
    DELETE FROM route_rule WHERE short_code='$CODE'; DELETE FROM short_code_pool WHERE short_code='$CODE';" >/dev/null 2>&1
  $RCLI DEL "sl:r:$CODE" "sl:code:$CODE" "sl:cnt:$CODE" >/dev/null 2>&1
  $RCLI --scan --pattern "sl:rl:$CODE:*" 2>/dev/null | xargs -r -n 50 $RCLI DEL >/dev/null 2>&1
  ch "ALTER TABLE xsl.click_event DELETE WHERE short_code='$CODE'" >/dev/null 2>&1
  if [[ "$FLIPPED" == "1" ]]; then
    note "复位跳转形态：make up（默认 dynamic）"
    (cd "$REPO_ROOT" && make up >/tmp/m235-restore.log 2>&1)
  fi
  note "cleanup: fixture $CODE 与 Redis 键、CH 事件已删除"
}
trap cleanup EXIT

curl -s -m 5 -o /dev/null "$JUMP/actuator/health" || { echo "sl-jump 不可达，先 make up"; exit 1; }

create_link
note "fixture $CODE / campaign $CAMPAIGN"
put_rules

# ── A/B/C. 逐条 UA 重放：形态、串、头
HITS=0
for row in "${SAMPLES[@]}"; do
  IFS='|' read -r name cval oval kind marker ua <<<"$row"
  out=$(hit "$ua"); HITS=$((HITS + 1))
  st=${out%%|*}; rest=${out#*|}; loc=${rest%%|*}; cc=${rest#*|}
  case "$kind" in
    scheme)
      check "A-$name-status" 200 "$st"
      check "A-$name-nostore" "no-store" "$cc"
      grep -qF "$marker" /tmp/m235-body && { echo "PASS A-$name-scheme ($marker)"; ((pass++)); } || { echo "FAIL A-$name-scheme expect=$marker"; ((fail++)); }
      grep -qF "dl?channel_id=c-m235" /tmp/m235-body && { echo "PASS A-$name-fallback-attributed"; ((pass++)); } || { echo "FAIL A-$name-fallback-attributed"; ((fail++)); }
      ;;
    urllink)
      check "A-$name-status" 200 "$st"
      check "A-$name-nostore" "no-store" "$cc"
      grep -qF "$marker" /tmp/m235-body && { echo "PASS A-$name-urllink"; ((pass++)); } || { echo "FAIL A-$name-urllink expect=$marker"; ((fail++)); }
      grep -qF "mp-guide?channel_id=c-m235" /tmp/m235-body && { echo "PASS A-$name-guide-attributed"; ((pass++)); } || { echo "FAIL A-$name-guide-attributed"; ((fail++)); }
      ;;
    intermediate)
      check "A-$name-status" 200 "$st"
      check "A-$name-nostore" "no-store" "$cc"
      grep -qF "$marker" /tmp/m235-body && { echo "PASS A-$name-text"; ((pass++)); } || { echo "FAIL A-$name-text expect=$marker"; ((fail++)); }
      grep -qF "window.location" /tmp/m235-body && { echo "FAIL A-$name-should-not-auto-jump"; ((fail++)); } || { echo "PASS A-$name-should-not-auto-jump"; ((pass++)); }
      ;;
    redirect:*)
      want=${kind#redirect:}
      check "A-$name-status" 302 "$st"
      [[ "$loc" == "$want"* ]] && { echo "PASS A-$name-location (${loc:0:58}…)"; ((pass++)); } || { echo "FAIL A-$name-location want=$want got=$loc"; ((fail++)); }
      [[ "$loc" == *"xy_click_id="* ]] && { echo "PASS A-$name-click-id"; ((pass++)); } || { echo "FAIL A-$name-click-id"; ((fail++)); }
      ;;
  esac
  # B：容器规则先于设备规则（qqbrowser-windows 走 /qq 而不是 /pc）
  [[ "$name" == "qqbrowser-windows" ]] && check "B-priority-across-dimensions" "/qq?" "$(echo "$loc" | grep -o '/[a-z]*?' | head -1)"
  # C：全不命中落 origin_url
  [[ "$name" == "curl" ]] && check "C-no-rule-falls-to-origin" "$ORIGIN?" "${loc:0:${#ORIGIN}+1}"
done

# ── D. 同一批 UA 的 device_type/os 入库（词汇表与规则条件值同源）
GOT=$(wait_for_events "$HITS")
check "D-events-landed" "$HITS" "$GOT"
for row in "${SAMPLES[@]}"; do
  IFS='|' read -r name cval oval kind marker ua <<<"$row"
  n=$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$CODE' AND device_type='$cval' AND os='$oval'")
  case "$name" in
    chrome-windows|safari-macos|openharmony|curl) want=2 ;;   # (unknown,desktop) 与 (unknown,unknown) 各有两条
    *) want=1 ;;
  esac
  check "D-$name-typed" "$want" "$n"
done
check "D-no-empty-columns" 0 "$(ch "SELECT count() FROM xsl.click_event WHERE short_code='$CODE' AND (device_type='' OR os='')")"

# ── E. 最后一里：中间页里的 H5 地址跟着打完是 mock 购票页（M2-34）
# click_id 本身不入库（click_event 表没有这一列，trace_id 也没落 CH），所以这里只断到
# "页面里带的是本次的 id、两次点击的 id 不同、终点打得开"；端到端用 click_id 对账是 M3 的活儿。
hit "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) MicroMessenger/8.0.40" > /dev/null
FALLBACK=$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' /tmp/m235-body | head -1 | sed 's/&amp;/\&/g')
CID1=$(printf '%s' "$FALLBACK" | sed -n 's/.*xy_click_id=\([A-Za-z0-9]*\).*/\1/p')
check "E-page-carries-click-id" "1" "$([[ -n "$CID1" ]] && echo 1 || echo 0)"
END=$(curl -sk -m 10 -o /tmp/m235-end -w '%{http_code}' --resolve "mock.ticketsales.test:$MOCK_PORT:$IP" "$FALLBACK")
check "E-mock-page-opens" 200 "$END"
grep -qF '归因参数（本次点击）' /tmp/m235-end && { echo "PASS E-mock-page-is-the-ticket-page"; ((pass++)); } || { echo "FAIL E-mock-page-is-the-ticket-page"; ((fail++)); }
hit "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) MicroMessenger/8.0.40" > /dev/null
CID2=$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' /tmp/m235-body | head -1 | sed 's/&amp;/\&/g' | sed -n 's/.*xy_click_id=\([A-Za-z0-9]*\).*/\1/p')
check "E-click-id-is-per-click" "1" "$([[ -n "$CID2" && "$CID1" != "$CID2" ]] && echo 1 || echo 0)"

# ── F. 回退开关（最后做，涉及重建 jump 容器）
echo
note "切换形态：XSL_JUMP_MODE=direct302 make up"
(cd "$REPO_ROOT" && XSL_JUMP_MODE=direct302 make up >/tmp/m235-direct.log 2>&1)
FLIPPED=1
MODE_SEEN=$(docker exec xsl-jump-1-1 printenv XSL_JUMP_MODE 2>/dev/null)
check "F-container-env-is-direct302" "direct302" "$MODE_SEEN"
for row in "wechat-iphone|Mozilla/5.0 (iPhone) MicroMessenger/8.0.40" "douyin-android|Mozilla/5.0 (Linux; Android 13) douyin_29.6.0" "alipay-android|Mozilla/5.0 (Linux; Android 13) AlipayClient/10.5.56"; do
  IFS='|' read -r name ua <<<"$row"
  out=$(hit "$ua"); st=${out%%|*}; rest=${out#*|}; loc=${rest%%|*}; cc=${rest#*|}
  check "F-$name-back-to-302" 302 "$st"
  [[ "$loc" == "$ORIGIN?"* ]] && { echo "PASS F-$name-to-origin"; ((pass++)); } || { echo "FAIL F-$name-to-origin got=$loc"; ((fail++)); }
  check "F-$name-no-cache-control" "" "$cc"
done

note "复位：make up（默认 dynamic）"
(cd "$REPO_ROOT" && make up >/tmp/m235-restore2.log 2>&1)
MODE_SEEN=$(docker exec xsl-jump-1-1 printenv XSL_JUMP_MODE 2>/dev/null)
check "F-restored-to-dynamic" "dynamic" "$MODE_SEEN"
out=$(hit "Mozilla/5.0 (iPhone) MicroMessenger/8.0.40"); st=${out%%|*}
check "F-page-returns-after-restore" 200 "$st"
FLIPPED=0   # 已复位，cleanup 不必再 up 一次

echo "== $pass passed, $fail failed =="
[[ $fail -eq 0 ]]
