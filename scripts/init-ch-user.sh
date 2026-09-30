#!/usr/bin/env bash
# ClickHouse 应用账号幂等初始化：口令只在运行时进 SQL，仓库里不留明文。
# 取代原先的 users.d/xsl-app-user.xml —— 那种写法只能把口令写死在文件里。
# 全新卷与已有卷同一条路径：CREATE IF NOT EXISTS 建号，ALTER 同步口令，GRANT 赋权。
source "$(dirname "$0")/lib-devenv.sh"

$COMPOSE exec -T clickhouse clickhouse-client --query "SELECT 1" >/dev/null \
  || { echo "clickhouse 容器不可达，先 make up"; exit 1; }

# 注意用「具体权限清单」而不是 GRANT ALL：容器内 default 自身是按具体权限授权的
# （见 init/clickhouse/users.d/local-admin.xml），拿不到 ALL 别名就无法转授 ALL，
# 会报 Code 497 "necessary to have the grant ALL ON *.* WITH GRANT OPTION"。
if ! $COMPOSE exec -T clickhouse clickhouse-client <<SQL
CREATE USER IF NOT EXISTS ${XSL_CH_USER} IDENTIFIED WITH plaintext_password BY '${XSL_CH_PASSWORD}';
ALTER USER ${XSL_CH_USER} IDENTIFIED WITH plaintext_password BY '${XSL_CH_PASSWORD}';
GRANT SELECT, INSERT, ALTER, CREATE, DROP, TRUNCATE, OPTIMIZE, SHOW ON *.* TO ${XSL_CH_USER};
SQL
then
  echo "建号/授权失败（若报 ACCESS MANAGEMENT 权限不足，检查 users.d/local-admin.xml 是否已挂载并 make up 重建）"
  exit 1
fi

# 自证：用刚写入的口令经宿主 8123 真读一次业务表，认证与 SELECT 授权一起验掉
READ=$(curl -s -m 15 -u "$CH_CRED" 'http://127.0.0.1:8123/' --data-binary 'SELECT count() FROM xsl.click_event')
[[ "$READ" =~ ^[0-9]+$ ]] || { echo "xsl_app 经 8123 读 xsl.click_event 失败：$READ"; exit 1; }
echo "OK ClickHouse 账号 $XSL_CH_USER 就位（口令来自 deploy/compose/.env，click_event 现有 $READ 行）"
