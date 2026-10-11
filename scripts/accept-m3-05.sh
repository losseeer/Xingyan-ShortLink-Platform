#!/usr/bin/env bash
# M3-08 验收（看板三页与只读统计接口，DESIGN 6.4/7.1/7.2/第十章）：
#   A. 铺一批**形状已知**的真点击：两条不同渠道的链接（一条用真实微信 UA）+ 一条极低配额链接造出「被频控拦下」那一档
#   B. 五条接口的每个字段与脚本**独立实现**的 ClickHouse 直查逐个相等（0 容差）——DESIGN 第十章那条出口标准
#   C. 边界与负向：>8 天窗口 / from>to / 粒度不认识 / 租户非数字 / 短码带引号 / 空窗口 / limit 越界 / 跨租户看不见
#   D. 页面能拿到的东西：同源路径、静态件与缓存头、以及**每条接口的字段形状**（借错接口那类失败的网兜）
#   E. 取数代价与指标：24h 档的耗时上限、histogram 桶真的暴露了、每条接口都被计过数
# 前置：make up（含 nginx 8081 那条 server）+ make web（dist 必须已构建，D 段要读它）。
# 纪律：断言先取值再 check，不写三层嵌套引号（macOS bash 3.2）；不要给这个脚本接管道（SIGPIPE 会打断收尾）。
# 刻意不动任何部署开关：看板是纯读侧，这一轮不需要像 m3-02/03/04 那样翻 consumer 的环境变量。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"

IP="${NGINX_ENTRY_IP:-127.0.0.1}"
PORT="${NGINX_PORT:-80}"
ADMIN="${ADMIN_DIRECT:-http://localhost:8030}"
CONSUMER="${CONSUMER_DIRECT:-http://localhost:8040}"
# 页面侧走 8081 那个 origin（同源反代），所以 B/D 段的接口调用都从这里发：顺带证明这条路径真的通
ORIGIN="http://xy1.test:8081"
RESOLVE=(--resolve "xy1.test:8081:$IP")
RUN=$(date +%s)
TENANT=1001
CP="p-m305-$RUN"
C1="c-m305-$RUN"
C2="c2-m305-$RUN"
N_PASS=20                 # 主链接放行点击（真实微信 UA）
N_SECOND=12               # 第二条链接（默认 UA ⇒ unknown 容器）
N_LIMIT=8                 # 低配额链接：期望 5 放行 + 3 被拦
CODE_FILE="/tmp/m305-codes.$RUN.txt"; : > "$CODE_FILE"
RECON_PY="/tmp/m305-recon.$RUN.py"; RECON_OUT="/tmp/m305-recon.$RUN.out"
FROM=""; TO=""

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi; }
check_le() { if [[ "${3:-99999}" -le "$2" ]]; then echo "PASS $1 (${3}${4:-} ≤ $2${4:-})"; ((pass++)); else echo "FAIL $1 actual=${3}${4:-} limit=$2${4:-}"; ((fail++)); fi; }
truthy() { if [[ "$2" == "1" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 ($3)"; ((fail++)); fi; }
note() { echo "     · $*"; }

MY() { $MYSQL_CLI -N -B -e "$1" 2>/dev/null; }
CHQ() { curl -s -m 60 -u "$CH_CRED" "http://127.0.0.1:8123/" --data-binary "$1" | tr -d '\r'; }
api() { # api <path> [query] → 响应体（走 8081 那个 origin）
  local url="$ORIGIN/api/v1/stats$1"
  [[ -n "${2:-}" ]] && url="$url?$2"
  curl -s -m 60 "${RESOLVE[@]}" "$url"
}
status() { # status <path> [query] → HTTP 状态码
  local url="$ORIGIN/api/v1/stats$1"
  [[ -n "${2:-}" ]] && url="$url?$2"
  curl -s -m 60 -o /dev/null -w '%{http_code}' "${RESOLVE[@]}" "$url"
}
jqp() { printf '%s' "$1" | jq -r "$2" 2>/dev/null; }
create_link() { # create_link <channel> <campaign> <rate_limit> <ua-标签>
  local body resp code
  body=$(printf '{"origin_url":"https://mock.ticketsales.test/show/%s","channel_id":"%s","campaign_id":"%s","promoter_id":"pr-m305","redirect_type":1,"rate_limit_per_minute":%s}' "$CP" "$1" "$2" "$3")
  resp=$(curl -s -X POST "$ADMIN/api/v1/links" -H 'Content-Type: application/json' \
    -H "X-Tenant-Id: $TENANT" -H "Idempotency-Key: m305-$RUN-$4-$RANDOM" -d "$body")
  code=$(printf '%s' "$resp" | sed -n 's/.*"short_code":"\([A-Za-z0-9]*\)".*/\1/p')
  [[ -z "$code" ]] && { echo "FATAL 创建失败: $resp" >&2; exit 1; }
  printf '%s\n' "$code" >> "$CODE_FILE"
  echo "$code"
}
UA_WECHAT='Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.42'
click() { # click <code> <次数> [ua]
  # 两个分支写死而不是拼数组：bash 3.2 在 `set -u` 下展开空数组会直接报 unbound variable
  #（第一版把空 UA 当成空字符串参数喂给 curl，等于那条链接一次都没点——症状是"明细里查不到"）
  local i=0
  while [[ $i -lt "$2" ]]; do
    if [[ -n "${3:-}" ]]; then
      curl -s -o /dev/null -m 10 -H "User-Agent: $3" --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/s/$1"
    else
      curl -s -o /dev/null -m 10 --resolve "xy1.test:$PORT:$IP" "http://xy1.test:$PORT/s/$1"
    fi
    i=$((i+1))
  done
}
landed_wait() { # landed_wait <code> <期望行数>：等到明细真的读得到（异步链路上"发完"不等于"查得到"）
  local got=0 tries=0
  while [[ $tries -lt 40 ]]; do
    got=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$1'")
    [[ "${got:-0}" == "$2" ]] && { echo "$((tries*2))"; return 0; }
    sleep 2; tries=$((tries+1))
  done
  echo "$((tries*2))"; return 1
}

cleanup() {
  set +u
  if [[ -s "$CODE_FILE" ]]; then
    local codes in
    codes=$(cat "$CODE_FILE"); in=$(printf "'%s'," $codes); in="(${in%,})"
    MY "DELETE FROM xsl_00.short_link WHERE short_code IN $in; DELETE FROM xsl_01.short_link WHERE short_code IN $in;
        DELETE FROM xsl_00.link_route WHERE short_code IN $in; DELETE FROM xsl_01.link_route WHERE short_code IN $in;
        DELETE FROM xsl_00.code_tenant_index WHERE short_code IN $in; DELETE FROM xsl_01.code_tenant_index WHERE short_code IN $in;
        DELETE FROM xsl_00.outbox WHERE aggregate_id IN $in; DELETE FROM xsl_01.outbox WHERE aggregate_id IN $in;
        DELETE FROM xsl_00.short_code_pool WHERE short_code IN $in; DELETE FROM xsl_01.short_code_pool WHERE short_code IN $in;
        DELETE FROM xsl_00.route_rule WHERE short_code IN $in; DELETE FROM xsl_01.route_rule WHERE short_code IN $in;" >/dev/null
    for c in $codes; do $COMPOSE exec -T redis redis-cli DEL "sl:r:$c" "sl:code:$c" >/dev/null; done
    CHQ "ALTER TABLE xsl.click_event DELETE WHERE short_code IN $in" >/dev/null
  fi
  local left
  left=$(MY "SELECT count(*) FROM xsl_00.short_link WHERE campaign_id LIKE 'p-m305-%';" | head -1)
  [[ "${left:-0}" == "0" ]] || echo "WARN 管理面仍有 m305 链接残留：$left"
  rm -f "$CODE_FILE" "$RECON_PY" "$RECON_OUT"
}
trap cleanup EXIT

echo "== M3-08 看板验收（run=$RUN 活动=$CP 窗口待定）=="

# ---------------- P0 前置 ----------------
echo "-- P0 前置：三个 origin 都在、构建产物在、指标端点可读"
H=$(curl -s -m 8 "$CONSUMER/actuator/health")
truthy "P0-consumer-up" "$( [[ "$H" == *'"status":"UP"'* ]] && echo 1 || echo 0 )" "consumer 健康"
IDX=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "${RESOLVE[@]}" "$ORIGIN/")
check "P0-console-index-200" "200" "$IDX"
if [[ ! -f sl-console-web/dist/index.html ]]; then echo "FATAL sl-console-web/dist 没有构建产物：先 make web"; exit 1; fi
check "P0-dist-present" ok ok
OTHER=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "${RESOLVE[@]}" "$ORIGIN/api/v1/links/whatever")
check "P0-console-blocks-write-side" "404" "$OTHER"
P=$(curl -s -m 8 "${RESOLVE[@]}" "$ORIGIN/api/v1/stats/tenants/$TENANT/channels?from=2019-01-01T00:00:00&to=2019-01-02T00:00:00")
truthy "P0-same-origin-proxies-stats" "$( [[ "$(jqp "$P" '.code')" == "00000" ]] && echo 1 || echo 0 )" "8081 这个 origin 能取到 stats（同源反代通了）"

# ---------------- A. 铺一批形状已知的点击 ----------------
echo "-- A 铺数据：两条渠道 + 一条低配额（造出被频控拦下那一档）"
L1=$(create_link "$C1" "$CP" 600 main)
L2=$(create_link "$C2" "$CP" 600 second)
L3=$(create_link "$C1" "$CP" 5 limited)
click "$L1" "$N_PASS" "$UA_WECHAT"
click "$L2" "$N_SECOND"
click "$L3" "$N_LIMIT"
W1=$(landed_wait "$L1" "$N_PASS"); truthy "A-land-l1" "$( [[ "$W1" -lt 80 ]] && echo 1 || echo 0 )" "$L1 的 $N_PASS 击在 ${W1}s 内落进明细"
W2=$(landed_wait "$L2" "$N_SECOND"); truthy "A-land-l2" "$( [[ "$W2" -lt 80 ]] && echo 1 || echo 0 )" "$L2 的 $N_SECOND 击落库"
W3=$(landed_wait "$L3" "$N_LIMIT"); truthy "A-land-l3" "$( [[ "$W3" -lt 80 ]] && echo 1 || echo 0 )" "$L3 的 $N_LIMIT 击（含被拦的）也全部落库"
# 被频控拦下的点击：跳转侧返回 429，但事件仍要进明细（DESIGN 5.3），这一档就是 blocked_pv 的来源
R429=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$L3' AND risk_score >= 25")
check "A-blocked-landed" 3 "$R429"
RVALID=$(CHQ "SELECT count() FROM xsl.click_event FINAL WHERE short_code='$L3' AND risk_score < 25")
check "A-passed-in-limited-link" 5 "$RVALID"
# 窗口在所有点击都落库之后才定，并往后推一分钟：此后不该再有新行进这个区间，比对才是确定性的
# 这里的时钟必须是**点击侧墙上时钟**（应用容器 Asia/Shanghai），不是 CH 的 now()——
# CH 会话时区是 UTC，拿它算窗口会整体往前偏 8 小时，读回来永远是 0 行（本轮第一轮就是这么红的）。
FROM=$(TZ=Asia/Shanghai date -v-30M '+%Y-%m-%dT%H:%M:%S')
TO=$(TZ=Asia/Shanghai date -v+1M '+%Y-%m-%dT%H:%M:%S')
note "窗口 [$FROM, $TO)（左闭右开，点击侧墙上时钟）——先点完再定窗口，否则两侧对不上是窗口在动"

# ---------------- B. 逐字段 0 容差 ----------------
echo "-- B 五条接口逐字段与直查比对（脚本独立实现一份算式）"
cat > "$RECON_PY" <<'PYRECON'
# 独立实现 DESIGN 6.4 的四个量与窗口口径：直查 ClickHouse 得到"应有值"，再和五条接口的返回逐字段比。
# 不复用服务代码是刻意的——两边同一份实现的话，比对了个寂寞。
import base64, json, os, sys, urllib.parse, urllib.request

BASE = os.environ["RECON_BASE"]
CH_URL = "http://127.0.0.1:8123/"
AUTH = "Basic " + base64.b64encode(os.environ["CH_CRED"].encode()).decode()
FROM, TO = os.environ["FROM"], os.environ["TO"]
TENANT = os.environ["TENANT"]
CODES = os.environ["CODES"].split(",")          # l1 l2 l3
CAMPAIGN = os.environ["CAMPAIGN"]
CHANNELS = os.environ["CHANNELS"].split(",")
CH_LIMIT = int(os.environ["CH_LIMIT"])           # 30 分钟窗口 + 1 分钟尾巴，够用

AGG = ("count() AS pv, uniqExact(ip_hash) AS uv, countIf(is_bot = 0 AND risk_score < 25) AS valid_pv, "
       "countIf(risk_score >= 25) AS blocked_pv, countIf(is_bot = 1) AS bot_pv")
WINDOW = "click_time >= '%s' AND click_time < '%s'" % (FROM.replace("T", " "), TO.replace("T", " "))

def ch(sql):
    req = urllib.request.Request(CH_URL, data=sql.encode(), headers={"Authorization": AUTH})
    return urllib.request.urlopen(req, timeout=CH_LIMIT).read().decode().strip()

def ch_rows(sql):
    out = []
    for line in ch(sql + " FORMAT JSONEachRow").splitlines():
        if line.strip():
            out.append(json.loads(line))
    return out

def direct(where, group=""):
    if group:
        rows = ch_rows("SELECT %s, %s FROM xsl.click_event FINAL WHERE %s AND %s GROUP BY %s"
                       % (group, AGG, where, WINDOW, group))
    else:
        rows = ch_rows("SELECT %s FROM xsl.click_event FINAL WHERE %s AND %s" % (AGG, where, WINDOW))
    return rows[0] if rows else {k: 0 for k in ("pv", "uv", "valid_pv", "blocked_pv", "bot_pv")}

def api(path, extra=""):
    q = "from=%s&to=%s&granularity=minute" % (urllib.parse.quote(FROM), urllib.parse.quote(TO))
    if extra:
        q += "&" + extra
    req = urllib.request.Request("%s/api/v1/stats%s?%s" % (BASE, path, q), headers={"Accept": "application/json"})
    body = json.loads(urllib.request.urlopen(req, timeout=CH_LIMIT).read().decode())
    if body.get("code") != "00000":
        raise RuntimeError("接口 %s 非 00000：%s" % (path, body.get("message")))
    return body["data"]

CH_PV_EXPECTED = int(os.environ["CH_PV_EXPECTED"])

bad = 0
compared = 0
def eq(label, got, want):
    global bad, compared
    compared += 1
    if str(got) == str(want):
        print("OK   %-46s %s" % (label, got))
    else:
        bad += 1
        print("BAD  %-46s api=%s direct=%s" % (label, got, want))

# 1) 单链接
for code in CODES:
    d = api("/links/" + code)
    w = direct("short_code = '%s'" % code)
    for k in ("pv", "uv", "valid_pv", "blocked_pv", "bot_pv"):
        eq("links.%s.totals.%s" % (code, k), d["totals"][k], w[k])
    eq("links.%s.click_count==pv" % code, d["click_count"], d["totals"]["pv"])
    eq("links.%s.window.from" % code, d["window"]["from"], FROM)
    eq("links.%s.source" % code, d["source"], "clickhouse:click_event FINAL")
    buckets = ch_rows("SELECT toStartOfMinute(click_time) AS b, count() AS pv FROM xsl.click_event FINAL "
                      "WHERE short_code = '%s' AND %s GROUP BY b ORDER BY b" % (code, WINDOW))
    eq("links.%s.series.len" % code, len(d["series"]), len(buckets))
    eq("links.%s.series.sum(pv)" % code, sum(x["pv"] for x in d["series"]), sum(int(x["pv"]) for x in buckets))

# 2) 租户列表：我们那三条码都要在，且每条的四档等于直查
d = api("/tenants/%s/links" % TENANT, "limit=200")
got = {x["short_code"]: x for x in d["links"]}
for code in CODES:
    if code not in got:
        bad += 1; compared += 1
        print("BAD  tenant_links.has.%s  (接口没返回这条码)" % code)
        continue
    w = direct("short_code = '%s'" % code)
    for k in ("pv", "uv", "valid_pv", "blocked_pv"):
        eq("tenant_links.%s.%s" % (code, k), got[code][k], w[k])
    eq("tenant_links.%s.channel" % code, got[code]["channel_id"],
       ch("SELECT any(channel_id) FROM xsl.click_event FINAL WHERE short_code='%s' AND %s" % (code, WINDOW)))

# 3) 渠道漏斗（含容器维度）
d = api("/tenants/%s/channels" % TENANT)
rows = {c["channel_id"]: c for c in d["channels"]}
for chn in CHANNELS:
    w = direct("channel_id = '%s' AND campaign_id = '%s'" % (chn, CAMPAIGN))
    for k in ("pv", "valid_pv", "blocked_pv", "bot_pv"):
        eq("channels.%s.%s" % (chn, k), rows[chn][k], w[k])
cons = {c["device_type"]: c for c in d["containers"]}
wechat_direct = ch("SELECT count() FROM xsl.click_event FINAL WHERE device_type='wechat' AND tenant_id=%s AND %s"
                   % (TENANT, WINDOW))
eq("containers.wechat.pv", cons.get("wechat", {}).get("pv", 0), int(wechat_direct))
eq("containers.empty_or_unknown_covered", sum(int(c["pv"]) for c in d["containers"]),
   direct("tenant_id = %s" % TENANT)["pv"])
eq("channels.conversion.not_available", d["conversion"]["available"], False)

# 4) 租户时间序列（双指标页读的就是这条）
d = api("/tenants/%s/timeseries" % TENANT)
w = direct("tenant_id = %s" % TENANT)
for k in ("pv", "uv", "valid_pv", "blocked_pv", "bot_pv"):
    eq("timeseries.totals.%s" % k, d["totals"][k], w[k])
n = int(ch("SELECT uniqExact(toStartOfMinute(click_time)) FROM xsl.click_event FINAL WHERE tenant_id=%s AND %s"
           % (TENANT, WINDOW)))
eq("timeseries.buckets", len(d["series"]), n)
eq("timeseries.series.sum(pv)==totals.pv", sum(x["pv"] for x in d["series"]), d["totals"]["pv"])

# 5) 活动趋势
d = api("/campaigns/%s" % urllib.parse.quote(CAMPAIGN))
w = direct("campaign_id = '%s'" % CAMPAIGN)
for k in ("pv", "uv", "valid_pv", "blocked_pv", "bot_pv"):
    eq("campaign.totals.%s" % k, d["totals"][k], w[k])
eq("campaign.pv.equals_our_clicks", d["totals"]["pv"], CH_PV_EXPECTED)

print("RESULT compared=%d bad=%d" % (compared, bad))
sys.exit(1 if bad else 0)
PYRECON
# 主组三条链接合计的期望值（在 bash 侧独立算一遍给 python 当交叉校验：20 + 12 + 5 = 37，被拦的 3 击不进 valid）
CH_PV_EXPECTED=$(( N_PASS + N_SECOND + N_LIMIT ))
# python 侧不解析 xy1.test（那是 curl 用 --resolve 指过去的），所以给它 IP 形态的同一个 origin
RECON_BASE="http://$IP:8081" FROM="$FROM" TO="$TO" TENANT="$TENANT" CH_CRED="$CH_CRED" \
CODES="$L1,$L2,$L3" CAMPAIGN="$CP" CHANNELS="$C1,$C2" CH_LIMIT=90 \
CH_PV_EXPECTED="$CH_PV_EXPECTED" python3 "$RECON_PY" > "$RECON_OUT" 2>&1
RECON_RC=$?
BAD=$(sed -n 's/^RESULT compared=\([0-9]*\) bad=\([0-9]*\)$/\2/p' "$RECON_OUT" | tail -1)
CMP=$(sed -n 's/^RESULT compared=\([0-9]*\) bad=\([0-9]*\)$/\1/p' "$RECON_OUT" | tail -1)
echo "$RECON_OUT" | grep -E '^BAD|RESULT' | sed 's/^/  | /'
check "B-no-field-mismatch" 0 "${BAD:-none}"
check "B-compared-enough-fields" 1 "$( [[ "${CMP:-0}" -ge 55 ]] && echo 1 || echo 0 )"
truthy "B-recon-exit-zero" "$( [[ $RECON_RC -eq 0 ]] && echo 1 || echo 0 )" "逐字段比对：共同步 ${CMP:-?} 个字段，0 容差"

# ---------------- C. 边界与负向 ----------------
echo "-- C 边界：护栏必须真的起作用，空数据不许伪装成故障、也不许让故障伪装成空数据"
LONG_FROM=$(CHQ "SELECT formatDateTime(now() - INTERVAL 9 DAY, '%Y-%m-%dT%H:%i:%s')")
check "C-window-over-cap-400" "400" "$(status "/tenants/$TENANT/links" "from=$LONG_FROM&to=$TO")"
BODY=$(api "/tenants/$TENANT/links" "from=$LONG_FROM&to=$TO")
truthy "C-cap-reason-mentions-window" "$( [[ "$(jqp "$BODY" '.message')" == *"窗口"* ]] && echo 1 || echo 0 )" "拒收理由指向窗口上限：$(jqp "$BODY" '.message')"
D7=$(CHQ "SELECT formatDateTime(now() - INTERVAL 7 DAY, '%Y-%m-%dT%H:%i:%s')")
check "C-7d-window-accepted" "00000" "$(jqp "$(api "/tenants/$TENANT/channels" "from=$D7&to=$TO&granularity=day")" '.code')"
check "C-from-after-to-400" "400" "$(status "/tenants/$TENANT/links" "from=$TO&to=$FROM")"
check "C-unknown-granularity-400" "400" "$(status "/tenants/$TENANT/timeseries" "from=$FROM&to=$TO&granularity=week")"
check "C-non-numeric-tenant-400" "400" "$(status "/tenants/1001x/links" "")"
check "C-quoted-code-400" "400" "$(status "/links/bad%20code" "")"
check "C-sqlish-code-400" "400" "$(status "/links/x%27%3B%20DROP%20TABLE%20click_event" "")"
check "C-limit-zero-400" "400" "$(status "/tenants/$TENANT/links" "from=$FROM&to=$TO&limit=0")"
check "C-limit-too-big-400" "400" "$(status "/tenants/$TENANT/links" "from=$FROM&to=$TO&limit=500")"
check "C-negative-offset-400" "400" "$(status "/tenants/$TENANT/links" "from=$FROM&to=$TO&offset=-1")"
EMPTY=$(api "/links/$L1" "from=2019-01-01T00:00:00&to=2019-01-02T00:00:00")
check "C-empty-window-200" "00000" "$(jqp "$EMPTY" '.code')"
check "C-empty-window-pv-zero" 0 "$(jqp "$EMPTY" '.data.totals.pv')"
check "C-empty-window-series-empty" 0 "$(jqp "$EMPTY" '.data.series | length')"
OTHER=$(api "/tenants/1002/links" "from=$FROM&to=$TO&limit=200")
HIDDEN=$(printf '%s' "$OTHER" | jq -r "[.data.links[] | select(.short_code==\"$L1\" or .short_code==\"$L2\" or .short_code==\"$L3\")] | length" 2>/dev/null)
truthy "C-other-tenant-does-not-see-our-codes" "$( [[ "${HIDDEN:-1}" == "0" ]] && echo 1 || echo 0 )" "1002 的列表里没有本轮的码（命中 ${HIDDEN:-?} 条）"
AFTER=$(status "/links/$L1" "from=$FROM&to=$TO")
check "C-still-working-after-bad-inputs" "200" "$AFTER"
# 短码不在明细里也不该 500：那是"没人点过"，不是后端坏了
GHOST=$(api "/links/zznotexist" "from=$FROM&to=$TO")
check "C-unknown-code-is-zero-not-error" "00000" "$(jqp "$GHOST" '.code')"
check "C-unknown-code-pv-zero" 0 "$(jqp "$GHOST" '.data.totals.pv')"

# ---------------- D. 页面拿得到的东西 ----------------
echo "-- D 交付面：静态件、缓存头、页面绑定的字段与接口路径"
ASSET=$(sed -n 's/.*src="\(\/assets\/index-[^"]*\.js\)".*/\1/p' sl-console-web/dist/index.html | head -1)
check "D-index-references-bundle" 1 "$( [[ -n "$ASSET" ]] && echo 1 || echo 0 )"
ABODY=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "${RESOLVE[@]}" "$ORIGIN$ASSET")
check "D-bundle-served" "200" "$ABODY"
ACACHE=$(curl -s -m 8 -D - -o /dev/null "${RESOLVE[@]}" "$ORIGIN$ASSET" | grep -i '^cache-control' | tr -d '\r')
truthy "D-bundle-cacheable" "$( [[ "$ACACHE" == *"max-age"* ]] && echo 1 || echo 0 )" "$ACACHE"
IHEAD=$(curl -s -m 8 -D - -o /dev/null "${RESOLVE[@]}" "$ORIGIN/" | grep -i '^cache-control' | tr -d '\r')
truthy "D-index-no-store" "$( [[ "$IHEAD" == *"no-store"* ]] && echo 1 || echo 0 )" "$IHEAD"
DEEP=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "${RESOLVE[@]}" "$ORIGIN/#/funnel")
check "D-spa-fallback-200" "200" "$DEEP"
# 形状契约：页面绑定的字段路径必须真的在响应里。
# 这一条就是为 M3-07 那次"页面借了一条没有 series 的接口"补的网——接口 200、单测全绿，
# 只有页面给出一个假事实；形状在这里钉住，借错接口就会红。
W="from=$FROM&to=$TO&granularity=minute"
SH1=$(api "/links/$L1" "$W" | jq -r '.data | (has("click_count") and (.totals | has("blocked_pv") and has("bot_pv")) and ((.series | length) == 0 or (.series[0] | has("bucket"))))')
check "D-shape-links-fields" "true" "$SH1"
SH2=$(api "/tenants/$TENANT/links" "$W&limit=5" | jq -r '.data | ((.links | length) == 0 or (.links[0] | has("last_click_time") and has("promoter_id"))) and (.page | has("has_more"))')
check "D-shape-tenant-links-fields" "true" "$SH2"
SH3=$(api "/tenants/$TENANT/channels" "$W" | jq -r '.data | ((.channels | length) > 0 and (.channels[0] | has("blocked_pv"))) and ((.containers | length) > 0 and (.containers[0] | has("device_type"))) and (.conversion | has("available") and has("reason"))')
check "D-shape-channels-fields" "true" "$SH3"
SH4=$(api "/tenants/$TENANT/timeseries" "$W" | jq -r '.data | ((.series | length) > 0) and (.series[0] | has("valid_pv") and has("blocked_pv") and has("bot_pv"))')
check "D-shape-timeseries-fields" "true" "$SH4"
BUNDLE="sl-console-web/dist${ASSET%/*}/$(basename "$ASSET")"
for NEEDLE in "/api/v1/stats" "/tenants/" "/links/" "/campaigns/" "/timeseries" "/channels"; do
  HIT=$(grep -c -F -- "$NEEDLE" "$BUNDLE" 2>/dev/null || true)
  truthy "D-bundle-uses$NEEDLE" "$( [[ "${HIT:-0}" -ge 1 ]] && echo 1 || echo 0 )" "打包产物里有 $NEEDLE（${HIT:-0} 处）"
done

# ---------------- E. 取数代价与指标 ----------------
echo "-- E 实测：窗口档位与指标可见性"
T24=$(api "/tenants/$TENANT/timeseries" "from=$(CHQ "SELECT formatDateTime(now() - INTERVAL 24 HOUR, '%Y-%m-%dT%H:%i:%s')")&to=$TO&granularity=hour")
MS24=$(printf '%s' "$T24" | jq -r '.data.meta.query_millis')
[[ "$MS24" =~ ^[0-9]+$ ]] || MS24=99999
check_le "E-timeseries-24h-millis" 1000 "$MS24" ms
CH_MS=$(printf '%s' "$(api "/tenants/$TENANT/channels" "$W")" | jq -r '.data.meta.query_millis')
note "本轮实测：24h 档 timeseries=${MS24}ms、分钟档 channels=${CH_MS}ms（窗口上限 8 天的依据见 ITER-M3 §3）"
METRICS=$(curl -s -m 30 "$CONSUMER/actuator/prometheus")
truthy "E-metrics-readable" "$( [[ "${#METRICS}" -gt 10000 ]] && echo 1 || echo 0 )" "prometheus 正文 ${#METRICS} 字节"
for EP in links tenant_links tenant_channels tenant_timeseries campaign; do
  CNT=$(printf '%s' "$METRICS" | awk -v e="$EP" '$1 ~ /^xsl_stats_query_total/ && index($1, "endpoint=\"" e "\"")>0 && index($1,"result=\"ok\"")>0 {print $2; exit}')
  truthy "E-metric-counted-$EP" "$( [[ "$(printf '%s' "${CNT:-0}" | awk '{print ($1>0)?1:0}')" == "1" ]] && echo 1 || echo 0 )" "$EP 的 ok 计数 ${CNT:-0}"
done
BADC=$(printf '%s' "$METRICS" | awk '$1 ~ /^xsl_stats_query_total/ && index($1,"result=\"bad_request\"")>0 {print $2; exit}')
truthy "E-metric-sees-bad-request" "$( [[ "$(printf '%s' "${BADC:-0}" | awk '{print ($1>0)?1:0}')" == "1" ]] && echo 1 || echo 0 )" "C 段的坏输入被计成 bad_request（${BADC:-0}）——CH 挂了不该长得像没人访问"
# 用 awk 数行，不用 `grep -q`：-q 命中就立刻退出，printf 那边收到 SIGPIPE ⇒ 在 `set -o pipefail` 下
# 整条管道是非 0，于是"明明有 966 行桶"被判成失败（本轮第一轮就红在这里）
HB=$(printf '%s' "$METRICS" | awk '$1 ~ /^xsl_stats_seconds_bucket/ {n++} END{print n+0}')
truthy "E-histogram-buckets-exposed" "$( [[ "$HB" -gt 0 ]] && echo 1 || echo 0 )" "histogram 桶 ${HB} 行，P95 有得算"

echo
echo "== 结果 PASS=$pass FAIL=$fail =="
[[ $fail -eq 0 ]] && echo "M3-08 验收全绿：三页数据与 ClickHouse 直查逐字段一致" || exit 1
