#!/usr/bin/env bash
# M3-09（DESIGN 8.3 第六行「数据丢失」）：产出一份**真能被读回来**的备份。
#
# 这一行此前在文档里的验证方式写的是 `make restore-verify`，而仓库里既没有那个目标、也没有任何
# 备份脚本——等于「每日备份」只是一句愿望。本脚本负责写，scripts/restore-verify.sh 负责证明读得回：
# 两半合起来才算数，单独跑哪一半都只是在猜测。
#
# 覆盖：MySQL 三个库（字典库 xsl_base + 两个分片库 xsl_00/xsl_01）mysqldump；ClickHouse 两张表
# （明细 click_event 与聚合 click_stat_minute）BACKUP 到 File，再 `docker cp` 取到宿主机。
# 刻意不做的事见 DEVELOPMENT_PLAN「M3-09/M3-10 的落地口径」第 7 条：不装 cron、不做异地、不删旧备份。
#
# 口径：备份产物落 deploy/backup/<ts>/（0600 目录、不入库）；口令一律来自 deploy/compose/.env，
# 经容器自己的环境变量传给 mysqldump，命令行与落盘文件里都不出现明文。
set -uo pipefail
source "$(dirname "$0")/lib-devenv.sh"

TS="${BACKUP_TS:-$(date +%Y%m%d-%H%M%S)}"
DEST="$REPO_ROOT/deploy/backup/$TS"
DBS=(xsl_base xsl_00 xsl_01)
# CH 侧 BACKUP 的落点必须在 config.d/backups.xml 的 allowed_path 里面，否则 Code 36
CH_IN_CTR="/var/lib/clickhouse/backups/xsl-$TS"
CH_TABLES=(click_event click_stat_minute)
MYSQL_DUMP="$DEST/mysql/xsl-dbs.sql.gz"
MANIFEST="$DEST/manifest.txt"

MY() { $MYSQL_CLI -N -B -e "$1" 2>/dev/null | tr -d '\r'; }
CHQ() { docker compose -f "$COMPOSE_FILE" exec -T clickhouse clickhouse-client --query "$1" 2>&1 | tr -d '\r'; }
step() { echo "  → $*"; }

mysql_counts() { # → 每行 "库.表 行数"（清单与竞态检查共用，避免两处各抄一份 SQL）
  local t
  for t in $(MY "SELECT CONCAT(table_schema,'.',table_name) FROM information_schema.tables
                 WHERE table_schema IN ('xsl_base','xsl_00','xsl_01') AND table_type='BASE TABLE'
                 ORDER BY table_schema, table_name"); do
    printf '%s %s\n' "$t" "$(MY "SELECT COUNT(*) FROM \`${t%%.*}\`.\`${t#*.}\`")"
  done
}

# 清单里每张表一行、键形如 table.<库>.<表>=<行数>：读回验证按同一个前缀取，
# 不能用 `^mysql_` —— 那个前缀会把 mysql_dump / mysql_dump_bytes 两条元数据一起捞进去。
write_manifest() {
  {
    printf 'kind=xsl-backup\nversion=1\n'
    printf 'created=%s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
    printf 'mysql_dump=mysql/xsl-dbs.sql.gz\nmysql_dump_bytes=%s\n' "$MYSQL_BYTES"
    printf 'clickhouse_backup=clickhouse/xsl-%s\nclickhouse_bytes=%s\n' "$TS" "${CH_BYTES:-0}"
    printf 'ch_click_event_before=%s\nch_click_event_after=%s\n' "$BEFORE_E" "$AFTER_E"
    printf 'ch_stat_agg_before=%s\nch_stat_agg_after=%s\n' "$AGG0" "$AGG1"
    while read -r name n; do printf 'table.%s=%s\n' "$name" "$n"; done <<< "$C1"
  } > "$MANIFEST"
}

for c in xsl-mysql-1 xsl-clickhouse-1; do
  [[ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" == "true" ]] || {
    echo "FATAL $c 没在跑——备份不是恢复演练，先 make up"; exit 1; }
done

umask 077
mkdir -p "$DEST/mysql" "$DEST/clickhouse"
echo "== make backup（$TS）→ ${DEST#$REPO_ROOT/}"

# ---------- 1. MySQL ----------
# 行数清单在 dump 前后各取一次并要求相等：`--single-transaction` 的快照点就在 mysqldump 启动那一刻，
# 只有前后两次计数一致，才能确定「备份文件里的行数」是唯一的那一个值。不相等说明当口有写入，
# 此时写出来的清单会让 restore-verify 的精确相等断言变成一个假故障，所以直接停在这里。
step "取备份前快照行数"
C0="$(mysql_counts)"
step "mysqldump ${DBS[*]}"
docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c \
  'exec env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --routines --triggers --databases xsl_base xsl_00 xsl_01' \
  2>"$DEST/mysql/mysqldump.err" | gzip -n > "$MYSQL_DUMP" || { echo "FATAL mysqldump 失败：见 $DEST/mysql/mysqldump.err"; exit 1; }
step "取备份后快照行数"
C1="$(mysql_counts)"
if [[ "$C0" != "$C1" ]]; then
  echo "FATAL 备份期间 MySQL 有写入，行数清单不唯一（dump 的快照点无法确定）"
  diff <(printf '%s\n' "$C0") <(printf '%s\n' "$C1") | head -10
  echo "稍后再跑（或先停掉创建链接的流量）"
  exit 2
fi
[[ -s "$MYSQL_DUMP" ]] || { echo "FATAL dump 是空的"; exit 1; }
MYSQL_BYTES=$(wc -c < "$MYSQL_DUMP" | tr -d ' ')
echo "  mysql dump=${MYSQL_BYTES} bytes  tables=$(printf '%s\n' "$C1" | wc -l | tr -d ' ')"

# ---------- 2. ClickHouse ----------
# 两张表的"读回来对不对"要用**不同的量**，因为它们的引擎不一样：
#   click_event        ReplacingMergeTree：合并会折叠重复行 ⇒ 行数不是稳定量，只能给区间
#   click_stat_minute  SummingMergeTree：合并把同键行折叠、指标相加 ⇒ 行数会掉（实测 387→384），
#                      但 sum(pv) 这类指标恒定 ⇒ 聚合值才是这张表的可靠锚点（也正是看板读的东西）
# 所以清单里明细表记行数区间，聚合表记五项指标的备份前后两个值。BACKUP 不是原子的对照点：
# consumer 一直在追加，因此一律记语句前/语句后两个数，验证时要求落回区间。
step "clickhouse BACKUP → $CH_IN_CTR"
AGG_CH='sum(pv), sum(bot_pv), sum(wx_pv), sum(douyin_pv), sum(browser_pv), uniq(uv_state)'
BEFORE_E=$(CHQ "SELECT count() FROM xsl.click_event")
AGG0=$(CHQ "SELECT $AGG_CH FROM xsl.click_stat_minute" | tr '\t' ' ')
BACKUP_SQL="BACKUP TABLE xsl.click_event, TABLE xsl.click_stat_minute TO File('$CH_IN_CTR')"
docker compose -f "$COMPOSE_FILE" exec -T clickhouse clickhouse-client --query "$BACKUP_SQL" \
  > "$DEST/clickhouse/backup-statement.out" 2>&1 || { echo "FATAL BACKUP 失败："; cat "$DEST/clickhouse/backup-statement.out"; exit 1; }
step "取出到宿主机（证明文件确实离开了数据库容器）"
docker cp "xsl-clickhouse-1:$CH_IN_CTR" "$DEST/clickhouse/" >/dev/null || { echo "FATAL docker cp 失败"; exit 1; }
AFTER_E=$(CHQ "SELECT count() FROM xsl.click_event")
AGG1=$(CHQ "SELECT $AGG_CH FROM xsl.click_stat_minute" | tr '\t' ' ')
docker compose -f "$COMPOSE_FILE" exec -T clickhouse clickhouse-client --query \
  "SELECT id, name, status, error FROM system.backups ORDER BY start_time DESC LIMIT 1" | tail -1 \
  > "$DEST/clickhouse/system-backups.txt"
docker compose -f "$COMPOSE_FILE" exec -T clickhouse rm -rf "$CH_IN_CTR" >/dev/null 2>&1
CH_BYTES=$(du -sk "$DEST/clickhouse/xsl-$TS" 2>/dev/null | awk '{print $1*1024}')

# ---------- 3. 清单 ----------
write_manifest

echo "  clickhouse 明细行数 ${BEFORE_E}→${AFTER_E}；聚合指标 pv/bot/wx/douyin/browser/uv：${AGG0} → ${AGG1}"
echo "  （聚合表是 SummingMergeTree：合并会折叠同键行，所以行数不是稳定量，指标 sum 才是——清单记的是后者）"
echo "  合计 $(du -sh "$DEST" | awk '{print $1}')，清单 $MANIFEST"
echo "RESULT backup=ok dest=${DEST#$REPO_ROOT/} mysql_bytes=$MYSQL_BYTES clickhouse_bytes=${CH_BYTES:-0}"
echo "下一步：make restore-verify（读回验证，不碰现有卷）"
