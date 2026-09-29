# Xingyan ShortLink Platform — 工程入口（DESIGN 3.4 / DEVELOPMENT_PLAN 工程约定）

JAVA21_CASK := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
# 按 javac 实存选择 JDK：macOS java_home 找不到目标版本时会回落输出 Applet JRE（无 javac 却 exit 0），
# 因此候选路径必须逐个 test -x bin/javac。覆盖：make JAVA_HOME=/path/to/jdk verify
export JAVA_HOME := $(shell for c in "$(JAVA21_CASK)" "$$(/usr/libexec/java_home -v 21 2>/dev/null)" "$$(/usr/libexec/java_home -v 17 2>/dev/null)"; do if test -x "$$c/bin/javac"; then echo "$$c"; break; fi; done)

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
SETTINGS := $(wildcard deploy/maven/settings-aliyun.xml)
MVN := mvn -q $(if $(SETTINGS),-s $(SETTINGS),)

.PHONY: print-java verify up down ps logs demo

verify:
	$(MVN) verify

up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100

demo:
	@echo "TODO M1-12: up -> seed -> create -> click -> stats 一键复现"

print-java:
	@echo "JAVA_HOME=$(JAVA_HOME)"
