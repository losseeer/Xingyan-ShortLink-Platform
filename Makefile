# Xingyan ShortLink Platform — 工程入口（DESIGN 3.4 / DEVELOPMENT_PLAN 工程约定）

JAVA21_CASK := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# 按 javac 实存选择 JDK：macOS java_home 找不到目标版本时会回落输出 Applet JRE（无 javac 却 exit 0），
# 因此候选路径必须逐个 test -x bin/javac。覆盖：make JAVA_HOME=/path/to/jdk verify
export JAVA_HOME := $(shell for c in "$(JAVA21_CASK)" "$$(/usr/libexec/java_home -v 21 2>/dev/null)" "$$(/usr/libexec/java_home -v 17 2>/dev/null)"; do if test -x "$$c/bin/javac"; then echo "$$c"; break; fi; done)

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
SETTINGS := $(wildcard deploy/maven/settings-aliyun.xml)
MVN := mvn -q $(if $(SETTINGS),-s $(SETTINGS),)

.PHONY: print-java verify up down ps logs demo db-init seed images nginx-reload

MYSQL_EXEC := $(COMPOSE) exec -T mysql mysql -uroot -pxsl-dev
REDIS_EXEC := $(COMPOSE) exec -T redis redis-cli

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

up:
	$(COMPOSE) up -d --wait

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

demo:
	@echo "TODO M1-12: up -> seed -> create -> click -> stats 一键复现"

print-java:
	@echo "JAVA_HOME=$(JAVA_HOME)"
