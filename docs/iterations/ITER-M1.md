# 迭代说明：M1（进行中，收口时定稿）

**日期**：2026-09-29 开work　**tag**：待定（m1）　**周期**：计划 12.5 人日 / 实际统计中

## 1. 本期目标回顾

跳转最小闭环 + 事件链路（DEVELOPMENT_PLAN M1，13 任务）。

## 2. 任务完成对照

| 任务 | 状态 | 说明 |
|---|---|---|
| M1-00 仓库骨架 | 🟡 | 骨架与 Enforcer 约束已提交（43f991c）；`mvn verify` 等 JDK 21 安装完成后补跑 |
| M1-01 中间件环境 | ✅ | e0fdefa；全栈 healthy，topic `shortlink-click` 就绪 |

## 3. 实测数字与凭证

| 指标 | 目标 | 实测 | 凭证 |
|---|---|---|---|
| （待 M1-11 压测后回填） | | | |

## 4. 与设计的偏差及回写

| 偏差点 | 原因 | 处理 |
|---|---|---|
| Kafka 单 listener 无法跨容器回连 | advertised `localhost:9092` 对容器内客户端不可达 | 改双 listener（INTERNAL kafka:9092 / EXTERNAL localhost:9094），sl-consumer 默认 bootstrap 改 9094；属部署细节，DESIGN 无需改 |
| 工具链：本机仅 JDK 8 | 构建前置 | brew 安装 openjdk@21（keg-only，不改系统默认）；编译 target 仍 17，符合 DESIGN"JDK 17+"口径 |

## 5. 本期决策记录

- （待定）M1-02 是否真用 Flyway：Flyway 历史表经 ShardingSphere 路由较繁琐，备选 init SQL 双库直建 + 表结构单一来源脚本。执行 M1-02 前在本节记录拍板结果。

## 6. 下期待办与风险

- M1-00 验收补跑 `make verify`（等 JDK）。
- M1-02 启动前先做 Flyway 取舍决策（第 5 节）。
