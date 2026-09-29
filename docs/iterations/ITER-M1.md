# 迭代说明：M1（进行中，收口时定稿）

**日期**：2026-09-29 开work　**tag**：待定（m1）　**周期**：计划 12.5 人日 / 实际统计中

## 1. 本期目标回顾

跳转最小闭环 + 事件链路（DEVELOPMENT_PLAN M1，13 任务）。

## 2. 任务完成对照

| 任务 | 状态 | 说明 |
|---|---|---|
| M1-00 仓库骨架 | ✅ | 43f991c + d15b422；`mvn verify` 全绿（JDK21 编译），Enforcer 禁依赖经"注入 sl-admin 触发构建失败"负向验证 |
| M1-01 中间件环境 | ✅ | e0fdefa；全栈 healthy，topic `shortlink-click` 就绪 |
| M1-02 数据模型 + 分片路由 | ✅ | 368ee32 + 本次提交；幂等 schema（xsl_00/xsl_01 各 7 表 + xsl_base 2 表，`make db-init` 重复执行零报错、两库列数 diff=0）；ShardingSphere-JDBC 5.5.1 读写拆表双分片轴路由经单测断言实测通过 |
| M1-03 ClickHouse 模型 | ✅ | 提前完成（纯 SQL 不依赖 JDK）；ReplacingMergeTree 去重 + 物化视图分钟聚合已实测 |

## 3. 实测数字与凭证

| 指标 | 目标 | 实测 | 凭证 |
|---|---|---|---|
| 分片路由正确性（M1-02） | 单分片键查询不广播、跨键稳定落库 | ✅ 通过 | `sql-show` 输出：tenant 1001→ds1、1002→ds0；`SELECT WHERE tenant_id=1001` 仅 Actual ds1；`WHERE short_code='spike1001'` 仅 ds0；无分片键 `LIKE` 广播 ds0+ds1。测试 `ShardingRoutingVerificationTest` 断言物理库精确落点 |
| （待 M1-11 压测后回填） | | | |

## 4. 与设计的偏差及回写

| 偏差点 | 原因 | 处理 |
|---|---|---|
| Kafka 单 listener 无法跨容器回连 | advertised `localhost:9092` 对容器内客户端不可达 | 改双 listener（INTERNAL kafka:9092 / EXTERNAL localhost:9094），sl-consumer 默认 bootstrap 改 9094；属部署细节，DESIGN 无需改 |
| 工具链：本机仅 JDK 8 | 构建前置 | brew 安装 openjdk@21（keg-only，不改系统默认）；编译 target 仍 17，符合 DESIGN"JDK 17+"口径 |
| compose MySQL 宿主端口 3306→3307 | 本机 Homebrew mysqld 占用 127.0.0.1:3306，IPv4 回环优先于 Docker 的 `*:3306`，宿主侧 JDBC 会打到错误的本地库（Access denied 表象迷惑） | compose `ports: ["3307:3306"]`；容器网络内其余服务仍用 `mysql:3306`，仅宿主直连工具链用 3307。不动用户本机服务 |
| ShardingSphere 5.5.1 不能用 HASH_MOD 做 standard 策略 | HASH_MOD 在 5.5.x 归类 auto 算法，带 shardingColumn 的配置直接初始化报错 | 改 INLINE(groovy)：`ds$->{tenant_id % 2}`、`ds$->{Math.abs(short_code.hashCode()) % 2}`；副产品是路由结果可手算，单测得以断言精确物理落点 |

## 5. 本期决策记录

- **M1-02 迁移方式**：不用 Flyway（其历史表经 ShardingSphere 路由繁琐），采用 `deploy/compose/init/mysql/*.sql` 幂等脚本：全新环境 entrypoint 自动执行，已有数据卷 `make db-init` 补建。单一来源、双路径可重放。
- **M1-02 分片方案**：ShardingSphere-JDBC 5.5.1 spike 成功（Boot 3.3.4 兼容，`mvn verify` 全绿），无需退化为"双库手工路由"；配置落 `sl-admin/src/main/resources/sharding.yaml`，M1-06 起连接参数改环境变量注入。
- ~~（待定）M1-02 是否真用 Flyway~~ 已决，见上。

## 6. 下期待办与风险

- M1-04 租户/HMAC 鉴权（表已就绪：xsl_base.tenant）；M1-05 短码池。
- sharding.yaml 的 3307/root/xsl-dev 为单机开发硬编码，M1-06 接管数据源时改环境变量注入 + compose 内部主机名 `mysql:3306`（容器内免端口绕路）。
- outbox.id 目前为分片内 AUTO_INCREMENT，跨分片不唯一——M3 outbox 实现时改 ShardingSphere key-generator(SNOWFLAKE) 或应用侧雪花。
