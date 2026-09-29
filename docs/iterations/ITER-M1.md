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
| M1-04 租户与 HMAC 鉴权 | ✅ | 网关 HmacAuthFilter（签名/时间戳窗口/nonce 防重放/X-Tenant-Id 注入）+ admin IdempotencyFilter（Redis 24h 重放）；单测 11 项 + `scripts/accept-m1-04.sh` 实测 7/7 通过（正确签名→200、篡改→401、旧时间戳→401、重放 nonce→401、未知 key→401、幂等二次 POST 同响应×2） |
| M1-05 短码池服务 | ✅ | sl-common `ShortCodeGenerator`（SecureRandom Base62 定长 7）+ sl-admin `ShortCodePoolService`（Redis List 水位 + SETNX 补池锁 + LPOP 原子批量租用 + seen SET 全局去重 + DB `INSERT IGNORE` 登记 + 租用状态回写）；`make verify` 全绿（全仓 25 单测），其中集成测试 3 项对活的 compose Redis(6380)/MySQL(3307) 实测：补池 300 唯一且 DB 计数 300、20 线程×50 并发租用 1000 码零重复、空池重补后新码与旧码不相交 |

## 3. 实测数字与凭证

| 指标 | 目标 | 实测 | 凭证 |
|---|---|---|---|
| 分片路由正确性（M1-02） | 单分片键查询不广播、跨键稳定落库 | ✅ 通过 | `sql-show` 输出：tenant 1001→ds1、1002→ds0；`SELECT WHERE tenant_id=1001` 仅 Actual ds1；`WHERE short_code='spike1001'` 仅 ds0；无分片键 `LIKE` 广播 ds0+ds1。测试 `ShardingRoutingVerificationTest` 断言物理库精确落点 |
| 短码唯一性（M1-05） | 生成/租用全链路无重复 | ✅ 通过 | `ShortCodeGeneratorTest`：20 万样本撞码 <10、字符集/长度正则校验；`ShortCodePoolServiceTest`（活体 infra）：并发 20×50 租用后 Set 大小=1000（零重复）；`sql-show` 可见 pool 登记按 short_code 精确路由 ds0/ds1、租回写 UPDATE 双库各命中自身分片 |
| （待 M1-11 压测后回填） | | | |

## 4. 与设计的偏差及回写

| 偏差点 | 原因 | 处理 |
|---|---|---|
| Kafka 单 listener 无法跨容器回连 | advertised `localhost:9092` 对容器内客户端不可达 | 改双 listener（INTERNAL kafka:9092 / EXTERNAL localhost:9094），sl-consumer 默认 bootstrap 改 9094；属部署细节，DESIGN 无需改 |
| 工具链：本机仅 JDK 8 | 构建前置 | brew 安装 openjdk@21（keg-only，不改系统默认）；编译 target 仍 17，符合 DESIGN"JDK 17+"口径 |
| compose MySQL 宿主端口 3306→3307 | 本机 Homebrew mysqld 占用 127.0.0.1:3306，IPv4 回环优先于 Docker 的 `*:3306`，宿主侧 JDBC 会打到错误的本地库（Access denied 表象迷惑） | compose `ports: ["3307:3306"]`；容器网络内其余服务仍用 `mysql:3306`，仅宿主直连工具链用 3307。不动用户本机服务 |
| compose Redis 宿主端口 6379→6380 | 与 MySQL 同类：本机 Homebrew redis-server 8.8 占 127.0.0.1:6379，宿主跑的网关读到了它（租户字典恒空 → "unknown api key"） | compose `ports: ["6380:6379"]`；admin/gateway/jump 配置改 `${XSL_REDIS_PORT:6380}`（容器内 profile 显式注入 redis:6379） |
| 网关默认端口 8010 被占 | 本机另一项目容器 `argagent-backend` 发布宿主 8010（8011 亦被占） | `server.port: ${XSL_GATEWAY_PORT:8010}` 参数化，验收实测走 8110；默认值不改（容器部署无冲突） |
| Idempotency-Key 中间件落点 | 计划行未指明模块；网关侧要完整响应缓存装饰器，复杂且 M1 无必要 | 落 admin（servlet `OncePerRequestFilter` + ContentCachingResponseWrapper + Redis 24h）；响应捕获语义与 DESIGN 7.2"创建幂等"一致。M2 若网关需要再上移 |
| ShardingSphere 5.5.1 不能用 HASH_MOD 做 standard 策略 | HASH_MOD 在 5.5.x 归类 auto 算法，带 shardingColumn 的配置直接初始化报错 | 改 INLINE(groovy)：`ds$->{tenant_id % 2}`、`ds$->{Math.abs(short_code.hashCode()) % 2}`；副产品是路由结果可手算，单测得以断言精确物理落点 |
| M1-05 并发租用验证方式 | 计划写的是 shell `xargs -P` 并发 1000 次租用脚本；单机宿主上 shell 脚本要自行解决签名/鉴权，噪声大且不落测试资产 | 改 JUnit 线程池集成测试 `concurrentLeaseNeverDuplicates`（20 线程×50），验收意图（并发零重复）不变且纳入 `make verify` 常跑 |
| M1-05 补池为同步执行 | 计划含异步补池；M1 池容量 5000、生成+PERSIST 实测 <1s，同步锁保护下无可用性痛点 | 先同步（SETNX 锁防并发重复补），异步化（定时任务/水位告警驱动）留 M2；DESIGN 无需改 |
| M1-05 seen SET 无 TTL | 全局去重集合 `sl:pool:{ns}:seen` 单调增长，Redis 内存口径 DESIGN 未定义 | 个人项目规模可接受（7 位 Base62 池上限内、每码 7B）；若 M3 池扩张或命名空间增多再引入 Cuckoo filter 替代，届时回写 DESIGN 9.x |
| M1-05 无内部 REST 端点 | 计划行提到"池管理接口"；M1 唯一消费方是 M1-06 生成 API（进程内调用），先开 REST 反而扩大鉴权面 | 保持 Spring service + 测试；M1-06 若管理看板需要再补带鉴权的内部端点 |

## 5. 本期决策记录

- **M1-02 迁移方式**：不用 Flyway（其历史表经 ShardingSphere 路由繁琐），采用 `deploy/compose/init/mysql/*.sql` 幂等脚本：全新环境 entrypoint 自动执行，已有数据卷 `make db-init` 补建。单一来源、双路径可重放。
- **M1-02 分片方案**：ShardingSphere-JDBC 5.5.1 spike 成功（Boot 3.3.4 兼容，`mvn verify` 全绿），无需退化为"双库手工路由"；配置落 `sl-admin/src/main/resources/sharding.yaml`，M1-06 起连接参数改环境变量注入。
- **M1-04 签名口径**：规范化串 `METHOD\npath\ntimestamp\nnonce\nsha256hex(body)`，HMAC-SHA256 hex；MVP 阶段 api_key 即共享密钥（DESIGN 9.4 的 key/secret 分离留到 M2 轮换机制一起做）。租户字典走 Redis `sl:tenant:api`（`make seed` 灌入），网关保持不依赖 MySQL——与管理面数据源隔离的原则从第一天成立。
- **Reactor 陷阱记录**：网关过滤器成功链路 `chain.filter()` 返回 `Mono<Void>`（空完成），尾随 `switchIfEmpty(拒绝)` 会在放行后误触发写 401——表现为"合法签名也得 401"。修复用 `thenReturn(TRUE)` 哨兵隔离；单测补"成功路径不得设置响应状态"断言防回归。
- **M1-05 池模型**：Redis List 为租用唯一活性来源（`LPOP count` 原子批量出池保证并发零重复），`short_code_pool` 表仅作登记账本（`INSERT IGNORE` + 租用状态回写），用于 Redis 数据丢失后按 status=0 重建池与审计；全局唯一性第一道防线是 seen SET（SADD 去重），表主键冲突是兜底。补水位阈值 `capacity×refill-ratio`，补池临界区用 SETNX 60s 锁串行化。
- ~~（待定）M1-02 是否真用 Flyway~~ 已决，见上。

## 6. 下期待办与风险

- M1-06 生成 API（`POST /api/v1/links`：https+域名白名单校验 → 事务写 short_link/link_route/code_tenant_index+outbox → Redis 路由缓存 → 返回短链）；M1-07 纯 302 跳转。
- sharding.yaml 的 3307/root/xsl-dev 为单机开发硬编码，M1-06 接管数据源时改环境变量注入 + compose 内部主机名 `mysql:3306`（容器内免端口绕路）。
- outbox.id 目前为分片内 AUTO_INCREMENT，跨分片不唯一——M3 outbox 实现时改 ShardingSphere key-generator(SNOWFLAKE) 或应用侧雪花。
