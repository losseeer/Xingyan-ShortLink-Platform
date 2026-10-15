#!/usr/bin/env bash
# M3-09（DESIGN 8.3 第六行）：证明 deploy/backup/<ts>/ 里那堆字节**真能读回来**。
#
# 为什么要有这个脚本，而不是相信 mysqldump 退出码 0：一份从没被读回来过的备份等于没有备份——
# 这是运维里最贵的一类错觉。本脚本断言的就是那句验收口径：
# 读回来的行数 == 备份那一刻清单里的行数。
#
# 两边的做法都刻意避开现有数据（DEVELOPMENT_PLAN「M3-09 落地口径」第 7 条）：
#   MySQL        dump 灌进一次性容器（数据目录 = tmpfs、不发布端口、--rm 用完即销毁）
#   ClickHouse   备份目录拷回 allowed_path，`RESTORE … AS xsl.*_vfy` 建**影子表**，比完 count 立刻 DROP
# 跑完，现有卷与现有表一行都没被动过——这是破坏性最小的能测法，而 `live-untouched` 那条断言就是它的证据。
#
# 用法：make restore-verify [deploy/backup/<ts>]（不给参数取最新一份）
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"

BACKUP_ROOT="$REPO_ROOT/deploy/backup"
LATEST="$(ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sed 's:/$::' | sort | tail -1)"
DEST="${1:-$LATEST}"
[[ -n "$DEST" ]] || { echo "FATAL deploy/backup/ 里没有备份，先 make backup"; exit 1; }
DEST="${DEST%/}"
MANIFEST="$DEST/manifest.txt"
[[ -f "$MANIFEST" ]] || { echo "FATAL $MANIFEST 不存在"; exit 1; }

VFY_MYSQL="xsl-vfy-mysql"
CH_IN="/var/lib/clickhouse/backups/vfy-$(date +%s)"
bad=0

ok() { echo "PASS $1 ($2)"; }
no() { echo "FAIL $1 expect=$2 actual=$3"; bad=$((bad + 1)); }
check() { if [[ "$2" == "$3" ]]; then ok "$1" "$3"; else no "$1" "$2" "$3"; fi; }
step() { echo "  → $*"; }
mval() { grep "^$1=" "$MANIFEST" | tail -1 | cut -d= -f2-; }
MYVALS() { grep '^table\.' "$MANIFEST" | sed 's/^table\.//; s/=/ /'; }

DUMP="$DEST/$(mval mysql_dump)"
CHSRC="$DEST/$(mval clickhouse_backup)"
[[ -f "$DUMP" ]] || { echo "FATAL 缺 $DUMP"; exit 1; }
[[ -d "$CHSRC" ]] || { echo "FATAL 缺 $CHSRC"; exit 1; }
echo "== make restore-verify（备份 ${DEST##*/}）"

# ======================= 1. MySQL：dump → 一次性容器 =======================
docker rm -f "$VFY_MYSQL" >/dev/null 2>&1   # 这名字归本脚本所有；上次中途失败留下的先清掉
step "起一次性 MySQL（tmpfs 数据目录、不发布端口、--rm）"
docker run -d --name "$VFY_MYSQL" --rm --tmpfs /var/lib/mysql \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=1 mysql:8.4 >/dev/null || { echo "FATAL 一次性容器起不来"; exit 1; }
cleanup_mysql() { docker stop "$VFY_MYSQL" >/dev/null 2>&1 || true; }
trap cleanup_mysql EXIT

# SQL 走 `-e "$1"`：少了 -e，`docker exec … sh -c '… mysql "$@"' _ "SELECT 1"` 会把整条语句
# 当成**数据库名**递给 mysql（ERROR 1049 Unknown database 'SELECT 1'），症状长得像服务器连不上。
VQ() { docker exec "$VFY_MYSQL" sh -c 'exec mysql -uroot -N -B -e "$1"' _ "$1" 2>&1 | tr -d '\r'; }
# 就绪判据不能用 "ready for connections" 计数：正式服务器一起动，X Plugin 与 mysqld 各打一行同样
# 的字样，两次就凑满了 —— 而那时还在 entrypoint 的**临时** initdb 服务器阶段，dump 灌进去会被
# 连同临时服务器一起关掉（第一次跑就是这么把 16 张表全读成 0 张的）。改看 initdb 结束那一行，
# 它只在临时服务器被关闭、正式服务器即将起来时出现一次。
READY=0
for _ in $(seq 60); do
  docker logs "$VFY_MYSQL" 2>&1 | grep -q 'init process done' && { READY=1; break; }
  sleep 2
done
[[ $READY -eq 1 ]] || { echo "FATAL 一次性 MySQL 60×2s 内没走完 initdb（最后 20 行日志）"; docker logs --tail 20 "$VFY_MYSQL" 2>&1; exit 1; }
ALIVE=0
for _ in $(seq 30); do VQ "SELECT 1" | grep -qx 1 && { ALIVE=1; break; }; sleep 1; done
[[ $ALIVE -eq 1 ]] || { echo "FATAL initdb 走完但正式服务器连不上"; docker logs --tail 10 "$VFY_MYSQL" 2>&1; exit 1; }
echo "  一次性 MySQL 就绪（镜像自带的空实例，socket 可达）"

step "source dump（$(du -h "$DUMP" | awk '{print $1}') gz）"
IMPORT_ERR="/tmp/xsl-restore-import.err"
if gunzip -c "$DUMP" | docker exec -i "$VFY_MYSQL" sh -c 'exec mysql -uroot' >/dev/null 2>"$IMPORT_ERR"; then
  if [[ -s "$IMPORT_ERR" ]]; then
    no mysql-import "stderr 为空" "$(head -2 "$IMPORT_ERR" | tr '\n' ' ')"
  else
    ok mysql-import "退出码 0 且 stderr 为空"
  fi
else
  no mysql-import "退出码 0" "非零；见 $IMPORT_ERR"
fi
check mysql-database-count "3" "$(VQ "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('xsl_base','xsl_00','xsl_01')" | tail -1)"

NTAB_MANIFEST=$(MYVALS | grep -c .)
NTAB_RESTORED=$(VQ "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('xsl_base','xsl_00','xsl_01') AND table_type='BASE TABLE'" | tail -1)
check mysql-table-count "$NTAB_MANIFEST" "$NTAB_RESTORED"

# 逐表行数：一次性容器里除了这份 dump 没有任何别的来源，所以必须**精确相等**。
# 不等只有两种解释：dump 少写了行，或读回来的不是备份那一刻的那份数据——两者都是备份不可信。
MISMATCH=""
while read -r name n; do
  [[ -n "${name:-}" ]] || continue
  got=$(VQ "SELECT COUNT(*) FROM \`${name%%.*}\`.\`${name#*.}\`" | tail -1)
  [[ "$got" == "$n" ]] || MISMATCH="$MISMATCH ${name}(清单=$n 读回=${got:-ERR})"
done <<< "$(MYVALS)"
if [[ -z "$MISMATCH" ]]; then
  ok mysql-rows-equal "$NTAB_MANIFEST 张表逐表行数与备份清单相等"
else
  no mysql-rows-equal "全部相等" "$MISMATCH"
fi
ROWS=$(MYVALS | awk '{s+=$2} END {print s+0}')
echo "  读回来：$NTAB_RESTORED 张表 / 清单合计 $ROWS 行（三库）"
cleanup_mysql
trap - EXIT
docker rm -f "$VFY_MYSQL" >/dev/null 2>&1

# ======================= 2. ClickHouse：File 备份 → 影子表 =======================
CHQ() { docker compose -f "$COMPOSE_FILE" exec -T clickhouse clickhouse-client --query "$1" 2>&1 | tr -d '\r'; }
LEFT0=$(CHQ "SELECT count() FROM system.tables WHERE database='xsl' AND name LIKE '%_vfy'")
[[ "$LEFT0" == "0" ]] || { echo "FATAL 已有 xsl.*_vfy 影子表（上次没清干净）：$LEFT0 张，先手工 DROP 再重跑"; exit 1; }

step "把备份目录拷回容器 allowed_path（$(du -sh "$CHSRC" | awk '{print $1}')）"
docker cp "$CHSRC" "xsl-clickhouse-1:$CH_IN" >/dev/null || { echo "FATAL docker cp 进容器失败"; exit 1; }
# docker cp 进去的文件属主是 root，而 clickhouse 服务器以 uid 101 读它们——不给读权就是一条
# "Access denied"，看起来像备份坏了，其实是取回去的路径没设权限。
docker exec -u root xsl-clickhouse-1 sh -c "chown -R clickhouse:clickhouse '$CH_IN' && chmod -R u+rwX,go+rX '$CH_IN'" \
  || { echo "FATAL 无法把拷回的备份目录交给 clickhouse 用户"; exit 1; }

step "RESTORE → xsl.click_event_vfy / xsl.click_stat_minute_vfy"
RES_START=$(date +%s)
CHQ "RESTORE TABLE xsl.click_event AS xsl.click_event_vfy, TABLE xsl.click_stat_minute AS xsl.click_stat_minute_vfy FROM File('$CH_IN')" \
  > "$DEST/restore-clickhouse.out" 2>&1
RES_SECS=$(( $(date +%s) - RES_START ))
if grep -q 'RESTORED' "$DEST/restore-clickhouse.out"; then
  ok clickhouse-restore "${RES_SECS}s 完成"
else
  no clickhouse-restore "RESTORED" "$(tail -2 "$DEST/restore-clickhouse.out" | tr '\n' ' ')"
fi
# 影子表一落地就先停合并：否则后台合并会一边折叠行一边被我们数（第一次跑就撞上
# click_stat_minute 读回 384 行 vs 清单 387 行——数据没错，是 SummingMergeTree 在合并）。
CHQ "SYSTEM STOP MERGES xsl.click_event_vfy" >/dev/null
CHQ "SYSTEM STOP MERGES xsl.click_stat_minute_vfy" >/dev/null

# 明细表（ReplacingMergeTree）用**行数区间**：BACKUP 执行期间 consumer 一直在追加，
# 清单因此记了语句前/语句后两个计数，落回区间即「备份点的数据一行不少、也不多」。
lo=$(mval ch_click_event_before); hi=$(mval ch_click_event_after)
[[ "$lo" -le "$hi" ]] || { t=$lo; lo=$hi; hi=$t; }     # 期间若发生去重合并，区间两端会颠倒
got=$(CHQ "SELECT count() FROM xsl.click_event_vfy")
if [[ "$got" =~ ^[0-9]+$ ]] && [[ "$got" -ge "$lo" ]] && [[ "$got" -le "$hi" ]]; then
  ok "ch-click_event-rows" "$got ∈ [$lo,$hi]"
else
  no "ch-click_event-rows" "[$lo,$hi]" "${got:-读不到}"
fi
check "ch-click_event-columns" "$(CHQ "SELECT count() FROM system.columns WHERE database='xsl' AND table='click_event'")" \
  "$(CHQ "SELECT count() FROM system.columns WHERE database='xsl' AND table='click_event_vfy'")"

# 聚合表（SummingMergeTree）改比**指标**：同键行被折叠时 pv/bot_pv/… 相加，行数会变而指标恒定，
# 所以这张表的"读回来对不对"只能看 sum——恰好也是看板真正读的那六个量。
AGG_CH='sum(pv), sum(bot_pv), sum(wx_pv), sum(douyin_pv), sum(browser_pv), uniq(uv_state)'
NAMES="pv bot_pv wx_pv douyin_pv browser_pv uv"
SB=$(mval ch_stat_agg_before); SA=$(mval ch_stat_agg_after)
GOT_AGG=$(CHQ "SELECT $AGG_CH FROM xsl.click_stat_minute_vfy" | tr '\t' ' ')
i=1; AGG_BAD=""
for v in $GOT_AGG; do
  n=$(printf '%s' "$NAMES" | awk -v i="$i" '{print $i}')
  x=$(printf '%s' "$SB" | awk -v i="$i" '{print $i}'); y=$(printf '%s' "$SA" | awk -v i="$i" '{print $i}')
  [[ -n "${n:-}" ]] || { AGG_BAD="$AGG_BAD 第 $i 个数没有名字（清单与查询的列数不一致）"; break; }
  [[ "$x" -le "$y" ]] || { t=$x; x=$y; y=$t; }
  [[ "$v" =~ ^[0-9]+$ && "$v" -ge "$x" && "$v" -le "$y" ]] || AGG_BAD="$AGG_BAD $n=$v(应落在 [$x,$y])"
  i=$((i + 1))
done
if [[ -z "$AGG_BAD" ]]; then
  ok "ch-click_stat_minute-aggregates" "$((i - 1)) 项指标各落回备份前后区间：$GOT_AGG"
else
  no "ch-click_stat_minute-aggregates" "pv/bot_pv/wx_pv/douyin_pv/browser_pv/uv 全部落回区间" "${AGG_BAD# }"
fi
check "ch-click_stat_minute-columns" "$(CHQ "SELECT count() FROM system.columns WHERE database='xsl' AND table='click_stat_minute'")" \
  "$(CHQ "SELECT count() FROM system.columns WHERE database='xsl' AND table='click_stat_minute_vfy'")"
echo "  影子表行数：click_stat_minute=$(CHQ "SELECT count() FROM xsl.click_stat_minute_vfy")（清单记的是指标而非行数，见上）"

step "DROP 影子表 + 删容器内的备份副本"
CHQ "DROP TABLE IF EXISTS xsl.click_event_vfy" >/dev/null
CHQ "DROP TABLE IF EXISTS xsl.click_stat_minute_vfy" >/dev/null
docker exec -u root xsl-clickhouse-1 rm -rf "$CH_IN" >/dev/null 2>&1
check ch-shadow-dropped "0" "$(CHQ "SELECT count() FROM system.tables WHERE database='xsl' AND name LIKE '%_vfy'")"
check ch-staging-removed "0" "$(docker exec xsl-clickhouse-1 sh -c "ls -d '$CH_IN' 2>/dev/null | wc -l" | tr -d ' ')"

# 「不碰现有数据」不能只是嘴上说：现有明细表此刻只可能比备份后更多，不可能变少
NOW_E=$(CHQ "SELECT count() FROM xsl.click_event")
if [[ "$NOW_E" =~ ^[0-9]+$ ]] && [[ "$NOW_E" -ge "$(mval ch_click_event_after)" ]]; then
  ok live-untouched "现有 click_event=$NOW_E ≥ 备份后 $(mval ch_click_event_after)"
else
  no live-untouched "≥$(mval ch_click_event_after)" "${NOW_E:-读不到}"
fi

echo "RESULT restore-verify bad=$bad backup=${DEST##*/} mysql_tables=$NTAB_RESTORED mysql_rows=$ROWS restore_seconds=${RES_SECS:-n/a}"
if [[ $bad -gt 0 ]]; then echo "读回验证未通过：$bad 项"; exit 1; fi
echo "MySQL 逐表行数与备份清单精确相等；ClickHouse 明细行数落回备份区间、聚合表的六项指标同样落回区间，影子表已 DROP"
