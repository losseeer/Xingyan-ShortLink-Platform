# Xingyan ShortLink Platform — 工程入口（DESIGN 3.4 / DEVELOPMENT_PLAN 工程约定）

JAVA21_CASK := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo $(JAVA21_CASK))
export JAVA_HOME

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

.PHONY: verify up down ps logs demo

verify:
	mvn -q verify

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
