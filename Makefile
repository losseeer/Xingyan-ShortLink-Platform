# Xingyan ShortLink Platform — 工程入口（DESIGN 3.4 / DEVELOPMENT_PLAN 工程约定）

JAVA21_CASK := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# 按 javac 实存选择 JDK：macOS java_home 找不到目标版本时会回落输出 Applet JRE（无 javac 却 exit 0），
# 因此候选路径必须逐个 test -x bin/javac。覆盖：make JAVA_HOME=/path/to/jdk verify
export JAVA_HOME := $(shell for c in "$(JAVA21_CASK)" "$$(/usr/libexec/java_home -v 21 2>/dev/null)" "$$(/usr/libexec/java_home -v 17 2>/dev/null)"; do if test -x "$$c/bin/javac"; then echo "$$c"; break; fi; done)

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
SETTINGS := $(wildcard deploy/maven/settings-aliyun.xml)
MVN := mvn -q $(if $(SETTINGS),-s $(SETTINGS),)

# 凭据只来自 deploy/compose/.env（不入库）。这里 include 后 export，使 make 自身、
# compose 的 ${VAR:?} 插值、以及 mvn 起的活体测试（System.getenv）看到同一份值。
DEVENV := deploy/compose/.env
-include $(DEVENV)
export XSL_MYSQL_PASSWORD XSL_CH_PASSWORD GRAFANA_PASSWORD

.PHONY: print-java verify up down ps logs demo db-init seed ch-init images nginx-reload devenv mysql-rotate

# 首次使用：make devenv 生成随机口令（0600），或 cp .env.example .env 后手工填
devenv:
	@if [ -f $(DEVENV) ]; then echo "$(DEVENV) 已存在，不覆盖（要改口令请手工编辑，再 make mysql-rotate + make ch-init + make up）"; \
	else umask 077; printf '# 本机开发栈凭据，不入库。字符集 [A-Za-z0-9._-]。\nXSL_MYSQL_PASSWORD=%s\nXSL_CH_PASSWORD=%s\nGRAFANA_PASSWORD=%s\n' \
		"$$(openssl rand -hex 16)" "$$(openssl rand -hex 16)" "$$(openssl rand -hex 12)" > $(DEVENV); \
		echo "已生成 $(DEVENV)（随机口令，权限 0600）"; fi

MYSQL_EXEC := $(COMPOSE) exec -T -e MYSQL_PWD=$(XSL_MYSQL_PASSWORD) mysql mysql -uroot
REDIS_EXEC := $(COMPOSE) exec -T redis redis-cli

# ClickHouse 应用账号：口令经 SQL 在运行时写入，仓库里没有 users.d 明文文件
ch-init:
	@bash scripts/init-ch-user.sh

# 已有数据卷下把 MySQL root 真实账号改成 .env 里的新值（改 .env 不会自动改库）
mysql-rotate:
	@bash scripts/rotate-mysql-password.sh

db-init:
	@for f in deploy/compose/init/mysql/*.sql; do echo "--> $$f"; $(MYSQL_EXEC) < $$f; done
	$(MYSQL_EXEC) -e "SELECT tenant_id, name FROM xsl_base.tenant ORDER BY tenant_id;"

# 网关 HMAC 鉴权所需 api_key→tenant_id 字典（DESIGN 9.4；M3 改由 admin 变更后同步）
seed:
	$(REDIS_EXEC) HSET sl:tenant:api xy-key-alice-001 1001 xy-key-bob-002 1002 xy-key-carol-003 1003

verify:
	$(MVN) verify

# 应用镜像 = 宿主 mvn 产物 + JRE（M1-10）。compose 里四个服务只引用 tag，不做镜像构建，
# 所以 `up` 之前要先 `make images`（首次或改代码后）。
images:
	@bash scripts/build-images.sh --mvn

# kafka-init 是一次性容器（建 topic，restart:"no"）。`up --wait` 会把它"退出"判成失败
# （本机 compose v5.1.3 实测报 container xsl-kafka-init-1 exited (0)），所以 --wait 只等常驻服务；
# 一次性容器由第一次 up -d 带起来，topic 建完即退，不影响后续。
DAEMON_SERVICES := mysql redis kafka clickhouse gateway admin jump-1 jump-2 consumer nginx

# 两段式：先创建/重建，再 --wait 等健康。
up:
	$(COMPOSE) up -d
	$(COMPOSE) up -d --wait $(DAEMON_SERVICES)

down:
	$(COMPOSE) down

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100

# 数据面 nginx 用静态 upstream（要 LB + 同请求改投），只在启动时解析容器名；
# 单独 recreate 某个 jump 若换了 IP，就要优雅重载一次（不断流）。控制面走 resolver 自愈，不需要。
nginx-reload:
	$(COMPOSE) exec -T nginx sh -c 'nginx -t && nginx -s reload'

# 一键演示（M1-12）：up → 建库/seed → 签名创建 → 三域点击 → stats 出数，逐环打印证据。
# 从零复现口径：先 `docker compose -f deploy/compose/docker-compose.yml down -v` 再 make demo。
demo:
	@bash scripts/demo.sh

print-java:
	@echo "JAVA_HOME=$(JAVA_HOME)"
