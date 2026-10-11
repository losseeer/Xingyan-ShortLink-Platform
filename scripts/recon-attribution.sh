#!/usr/bin/env bash
# 端到端归因对账（DESIGN 6.4 的两条判定式，M3-04）——一个可反复跑的**核对工具**，不是验收脚本。
#
#   ① 传输完整性 = |平台收到的 Σpv − ClickHouse 明细同窗口 pv| ÷ 明细 pv     默认要求 < 2%
#   ② 归因可 join = 订单里能在明细找到 click_id 的条数 ÷ 订单总数            默认要求 ≥ 98%
#   观测项（不进阈值）：click_stat_minute 聚合表与明细的差——async_insert 未合并 part 会带来已知偏差，
#                      混进阈值就会掩盖真问题（6.4 那条），所以只打印、只记数。
#
# 用法：
#   scripts/recon-attribution.sh <campaign_id> [from "YYYY-MM-DD HH:MM"] [to 同上，左闭右开]
#       不给 from/to 就自己按该活动在明细里的时间范围推（T+1 手工核对时省得记窗口）
#   --max-diff <pct>   改 ① 的阈值      --min-join <pct>  改 ② 的阈值
#   --keep-bad         有一条超出阈值时也只打印、不让退出码变红（只想看数的时候用）
#
# 退出码：0 = 两条都在阈值内（或该侧无数据、判 n/a）；1 = 有一条超出阈值；2 = 参数或数据读不出来。
#
# 两个时钟的约定（与 03-mock-schema.sql 顶部注释同一条，别改）：
#   · 窗口 [from, to) 走**点击侧墙上时钟**：明细的 click_time、平台的 attribution_stat.minute 都是它
#     （CH 会话时区是 UTC、列里存的就是上海墙上时钟串，DESIGN 4.2）；
#   · 订单侧因此用 first_click_time（订单自带的归因点击时刻，同一个时钟）来切窗口，
#     **不用 create_time**（那是 mock 服务器收到的时刻，MySQL 容器是 UTC，差 8 小时）——
#     拿它对点击窗口，正确的订单会被整批算成"找不到"，报出来的差异全是假的。
#
# 两侧必须取同一个**已关闭**的分钟区间：回传只推已关闭分钟（6.3-3），拿"现在"对"现在"会得到一个
# 永远不为零的假差异——这是 6.4 特意写明的执行细节。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"   # 口令从 .env 来，脚本里不落明文

MAX_DIFF=2
MIN_JOIN=98
KEEP_BAD=0
POS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --max-diff) MAX_DIFF=${2:-}; shift 2 ;;
    --min-join) MIN_JOIN=${2:-}; shift 2 ;;
    --keep-bad) KEEP_BAD=1; shift ;;
    -*) echo "未知参数: $1" >&2; exit 2 ;;
    *) POS+=("$1"); shift ;;
  esac
done
CAMPAIGN="${POS[0]:-}"
FROM="${POS[1]:-}"
TO="${POS[2]:-}"
if [[ -z "$CAMPAIGN" || -z "$MAX_DIFF" || -z "$MIN_JOIN" ]]; then
  echo "用法: $0 <campaign_id> [from] [to] [--max-diff N] [--min-join N] [--keep-bad]" >&2
  exit 2
fi

RUN_DIR=$(date +%s)
CLICKS="/tmp/recon-clicks.$$.txt"
ORDERS="/tmp/recon-orders.$$.txt"
cleanup() { set +u; rm -f "$CLICKS" "$ORDERS"; }
trap cleanup EXIT

MY() { $MYSQL_CLI -N -B -e "$1" 2>/dev/null; }
CH() { curl -s -m 60 -u "$CH_CRED" "http://127.0.0.1:8123/" --data-binary "$1" | tr -d '\r'; }
pct() { awk -v a="$1" -v b="$2" 'BEGIN{if(b==0){print "nan"}else{printf "%.2f", (a>b? a-b : b-a)/b*100}}'; }
pct_of() { awk -v a="$1" -v b="$2" 'BEGIN{if(b==0){print "nan"}else{printf "%.2f", a/b*100}}'; }
isnum() { [[ "$1" =~ ^[0-9]+$ ]]; }
note() { echo "     · $*"; }
die() { echo "$*" >&2; exit 2; }

echo "== 归因对账 campaign=$CAMPAIGN =="

# 窗口：不给就按明细里该活动的实际范围推（左闭右开，右端进一位到分钟）
if [[ -z "$FROM" || -z "$TO" ]]; then
  RANGE=$(CH "SELECT toString(toStartOfMinute(min(click_time))), toString(toStartOfMinute(max(click_time)) + INTERVAL 1 MINUTE) FROM xsl.click_event WHERE campaign_id = '$CAMPAIGN'")
  if [[ -z "$RANGE" || "$RANGE" == $'\t'* || "$RANGE" == *"1970"* ]]; then
    echo "明细里没有该活动的点击（campaign_id=$CAMPAIGN），无从对账"
    echo "RESULT transport=n/a join=n/a verdict=FAIL"
    exit 1
  fi
  FROM=$(printf '%s' "$RANGE" | cut -f1)
  TO=$(printf '%s' "$RANGE" | cut -f2)
  note "窗口由明细推导：[$FROM, $TO)"
fi

DETAIL_PV=$(CH "SELECT count() FROM xsl.click_event FINAL WHERE campaign_id = '$CAMPAIGN' AND click_time >= '$FROM' AND click_time < '$TO'")
isnum "$DETAIL_PV" || die "明细 pv 读不出来（ClickHouse 响应：$DETAIL_PV）"
PLATFORM_PV=$(MY "SELECT COALESCE(SUM(pv), 0) FROM xsl_mock.attribution_stat WHERE campaign_id='$CAMPAIGN' AND minute >= '$FROM' AND minute < '$TO';" | head -1)
isnum "$PLATFORM_PV" || die "平台侧 pv 读不出来（MySQL 响应：$PLATFORM_PV）——xsl_mock 的表建了吗（make db-init）"
AGG_PV=$(CH "SELECT sum(pv) FROM (SELECT sum(pv) AS pv FROM xsl.click_stat_minute WHERE campaign_id = '$CAMPAIGN' AND minute >= '$FROM' AND minute < '$TO' GROUP BY tenant_id, campaign_id, channel_id, minute)")
isnum "${AGG_PV:-0}" || AGG_PV=0     # 无数据时 CH 返回 NULL（空串），观测项按 0 记
DIFF_PCT=$(pct "$PLATFORM_PV" "$DETAIL_PV")

# ① 传输完整性
if [[ "$DETAIL_PV" -eq 0 ]]; then
  if [[ "$PLATFORM_PV" -gt 0 ]]; then
    T_VERDICT=FAIL
    note "① 明细 pv=0 但平台收到 $PLATFORM_PV——凭空多出来的数，判 FAIL"
  else
    T_VERDICT=n/a
    note "① 该窗口两侧都没有点击（明细 pv=0、平台 Σpv=0）→ 判 n/a"
  fi
else
  T_OK=$(awk -v d="$DIFF_PCT" -v m="$MAX_DIFF" 'BEGIN{print (d+0<m)?1:0}')
  T_VERDICT=$([[ $T_OK -eq 1 ]] && echo PASS || echo FAIL)
  note "① 传输完整性：明细 pv=$DETAIL_PV、平台收到 Σpv=$PLATFORM_PV、差异 ${DIFF_PCT}% (阈值 ${MAX_DIFF}%) → $T_VERDICT"
fi

# ② 归因可 join：把明细侧的 click_id 集合拉下来，再逐个订单比对
: > "$CLICKS"; : > "$ORDERS"
CH "SELECT DISTINCT click_id FROM xsl.click_event FINAL WHERE campaign_id = '$CAMPAIGN' AND click_time >= '$FROM' AND click_time < '$TO' AND click_id != ''" | sort -u | grep -v '^$' > "$CLICKS"
MY "SELECT click_id FROM xsl_mock.mock_order WHERE campaign_id='$CAMPAIGN' AND first_click_time >= '$FROM' AND first_click_time < '$TO';" > "$ORDERS"
N_ORDERS=$(grep -c "" "$ORDERS")
# 明细侧一个 click_id 都没有时不喂空模式文件：不同 grep 实现对空模式文件的行为不一致
# （有的当成"没有模式"，有的当成"匹配一切"），这里要的是"全都算没命中"。
if [[ -s "$CLICKS" ]]; then
  N_MATCHED=$(grep -F -x -f "$CLICKS" "$ORDERS" | grep -c "" || true)
else
  N_MATCHED=0
fi
N_MISSING=$((N_ORDERS - N_MATCHED))
JOIN_PCT=$(pct_of "$N_MATCHED" "$N_ORDERS")
if [[ "$N_ORDERS" -eq 0 ]]; then
  J_VERDICT=n/a
  note "② 该活动在窗口内没有订单（订单侧按 first_click_time 切窗口，见文件头）→ 判 n/a"
else
  J_OK=$(awk -v j="$JOIN_PCT" -v m="$MIN_JOIN" 'BEGIN{print (j+0>=m)?1:0}')
  J_VERDICT=$([[ $J_OK -eq 1 ]] && echo PASS || echo FAIL)
  note "② 归因可 join：订单 $N_ORDERS 条、命中 $N_MATCHED、缺失 $N_MISSING、命中率 ${JOIN_PCT}% (阈值 ${MIN_JOIN}%) → $J_VERDICT"
fi

# 观测项：聚合表与明细的差（不进阈值，见文件头）
AGG_DIFF=$(awk -v a="$AGG_PV" -v d="$DETAIL_PV" 'BEGIN{if(d==0){print "nan"}else{printf "%.2f", (a>d? a-d : d-a)/d*100}}')
note "观测：click_stat_minute Σpv=$AGG_PV 与明细差 ${AGG_DIFF}%（不判，6.4 写明它有自己的已知偏差）"

VERDICT=PASS
[[ "$T_VERDICT" == FAIL || "$J_VERDICT" == FAIL ]] && VERDICT=FAIL
[[ $KEEP_BAD -eq 1 && "$VERDICT" == FAIL ]] && { note "--keep-bad：只报数不改退出码"; VERDICT=PASS; }
# RESULT 是机器可读行：一个字段一个值，所以窗口写成 ISO 的 'T' 形式——带空格的 '2026-10-11 15:42:00'
# 会把 from/to 各切成两段，任何按空格取数的调用方都会读错（验收脚本第一轮就撞上了）。
echo "RESULT transport_diff_pct=$DIFF_PCT transport=$T_VERDICT join_pct=$JOIN_PCT join=$J_VERDICT orders=$N_ORDERS matched=$N_MATCHED missing=$N_MISSING detail_pv=$DETAIL_PV platform_pv=$PLATFORM_PV agg_pv=$AGG_PV agg_drift_pct=$AGG_DIFF from=$(printf '%s' "$FROM" | tr ' ' 'T') to=$(printf '%s' "$TO" | tr ' ' 'T') verdict=$VERDICT"
[[ "$VERDICT" == PASS ]] || exit 1
