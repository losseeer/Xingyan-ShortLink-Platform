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
| M1-06 生成 API | ✅ | `POST/GET/PATCH /api/v1/links`：https+域名白名单准入、自定义码保留字/冲突校验（SETNX+DB 双防线）、short_link+outbox 同事务写、link_route+code_tenant_index 提交后同步、Redis `sl:r:{code}` 缓存 24h+rand、PATCH 合并语义+version 递增、code_tenant_index 越权 403；`LinkServiceIntegrationTest` 8 项（活体 infra）+ sl-common 新增 8 项单测（准入/雪花），`make verify` 全仓 41 测全绿 + `scripts/accept-m1-06.sh` 活应用实测 21/21 通过；sharding.yaml 连接参数完成环境变量注入（§6 旧待办关闭） |
| M1-07 跳转服务（纯 302） | ✅ | sl-jump `GET /s/{code}` 与裸 `/{code}`：Caffeine(L1,30s)→Redis(L2)→link_route 回源(L3) 三级读、空值标记 `sl:r:nx:{code}` 防穿透、Lua version 比较防旧值写回覆盖、status/expire/access_limit(Lua 原子扣减)检查→302；`JumpResolver` 接口即 DESIGN 第十章"纯 302 开关"扩展位；单测 6+活体集成 3，`make verify` 全仓 50 测全绿；`scripts/accept-m1-07.sh` 对活 jump(8020) 14/14 通过（302·404+标记·403·410 过期·410 超限·二查 db 计数不增） |
| M1-08 归因参数透传 | ✅ | sl-common `AttributionParamMerger`：白名单=附录 B 契约（channel_id/campaign_id/promoter_id/utm_*），`&/?` 续接（悬空 `?` 复用）、追加段插在 `#` fragment 之前、值按 RFC 3986 unreserved 严格 URL-encode、禁覆盖目标原有参数（含白名单键，编码形态键 `%6E` 亦可识别）、`xy_click_id`（c+雪花）由模块生成且拒绝外部传入、`trace_id` 随 Result 返回但不入 URL；jqwik 属性测试 3 性质（400/300/200 例）+ 确定性单测 9 项，`make verify` 全仓 62 测全绿 |

## 3. 实测数字与凭证

| 指标 | 目标 | 实测 | 凭证 |
|---|---|---|---|
| 分片路由正确性（M1-02） | 单分片键查询不广播、跨键稳定落库 | ✅ 通过 | `sql-show` 输出：tenant 1001→ds1、1002→ds0；`SELECT WHERE tenant_id=1001` 仅 Actual ds1；`WHERE short_code='spike1001'` 仅 ds0；无分片键 `LIKE` 广播 ds0+ds1。测试 `ShardingRoutingVerificationTest` 断言物理库精确落点 |
| 短码唯一性（M1-05） | 生成/租用全链路无重复 | ✅ 通过 | `ShortCodeGeneratorTest`：20 万样本撞码 <10、字符集/长度正则校验；`ShortCodePoolServiceTest`（活体 infra）：并发 20×50 租用后 Set 大小=1000（零重复）；`sql-show` 可见 pool 登记按 short_code 精确路由 ds0/ds1、租回写 UPDATE 双库各命中自身分片 |
| 生成链路一致性（M1-06） | 三表+outbox+缓存一致、越权拒绝 | ✅ 通过 | `accept-m1-06.sh` 对活 sl-admin(8030) 21 项断言：创建 200+7 位码+short_url、双分片库求和后 short_link/code_tenant_index/outbox 各 1 行、route_json 与 `sl:r:{code}` 缓存一致（version 1）、evil.test/http→400 SL-4001、他租户 GET→403 SL-4030、自定义码冲突→409、保留字→400、PATCH 后缓存与 link_route version=2、同 Idempotency-Key 二次 POST 返回同一 code |
| 跳转正确性与缓存层级（M1-07） | 302/404/403/410 语义 + 冷码回源二查命中缓存 | ✅ 通过 | `accept-m1-07.sh` 对活 sl-jump(8020) 14 项断言：正常码 302 且 Location=origin_url（`/s/` 与裸路径同义）；不存在码 404 且 `sl:r:nx:` 标记生成；status=1→403；过期→410；access_limit=2 第 3 击→410；冷码首查后 `xsl_jump_route_lookup_total{level="db"}` 1.0→2.0、二查保持 2.0 不增（指标计数佐证回源仅一次），`sl:r:{code}` 写回存在 |
| 归因拼接健壮性（M1-08） | 任意 query/编码/`#` 片段下结果可被 `java.net.URI` 解析且原参数逐字节无损；注入值不改语义 | ✅ 通过 | `AttributionParamMergerPropertyTest` 3 性质（tries 400/300/200）：生成 URL 覆盖 空/悬空`?`/多参/尾`&`/编码片段/任意 fragment 六形态，断言原 query 前缀逐字节保留、fragment 原样、追加对数=Result.appended 数；注入样本（`a&b=1`、`%26evil=1`、`x#y`、中文、200 字符长串）后追加段恒为 2 对且解码还原原值；`surefire-reports`：PropertyTest `Tests run: 3, Failures: 0`，确定性 `Tests run: 9, Failures: 0` |
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
| M1-06 origin_url 白名单来源 | DESIGN 9.2 口径是"备案域名白名单+人工审核"（DB 化），M1 无审核队列与备案表 | 走配置 `xsl.admission.allowed-hosts`（env 可覆盖，默认含 mock 域）；白名单 DB 化+审核队列留 M2，与 api_key 轮换同批 |
| M1-06 验收直连 sl-admin | 8010/8011 被 argagent 占用是已知环境态；本任务要验的是生成语义而非签名链路（M1-04 已 7/7 验过网关） | `accept-m1-06.sh` 注入 X-Tenant-Id 直打 admin:8030；M1-10 nginx 就位后并入端到端 |
| ShardingSphere 5.5.1 占位符语法两轮试错 | `$${VAR:::default}`+`;placeholder-type` 是旧文档口径：`:::` 会把默认值解析成空串（Port "" 报错）、`;` 分隔符不被 classpath URL 解析（resource 找不到 NPE） | 正确姿势：`$${VAR::default}` + `jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=environment`；已活体回归（路由/池/链路三套 SS 测试全绿） |
| M1-07 布隆/Cuckoo 前置检查缺席 | 计划含"布隆→Caffeine→Redis→回源"；Cuckoo filter 结构本身排在 M2/M3，M1 引入只会增加半成品 | 以空值标记 `sl:r:nx:{code}`(5min) + L1 负缓存(30s) 作为 M1 的穿透防线（DESIGN 5.2 步骤 1 的兜底路径先行）；M3 补 Cuckoo 时此层可退役 |
| M1-07 jump 独立持有分片配置 | Enforcer 禁 jump→admin 依赖（DESIGN 3.4），无法复用 admin 的 sharding.yaml | `sl-jump/sharding-jump.yaml` 只声明 link_route 单表最小配置、连接参数同款环境变量注入；两份配置的同步性由本迭代脚本 phys_db 与 INLINE 表达式互校保障，M2 若配置中心化再消重 |
| M1-07 验收直连 sl-jump:8020 | nginx `/s/**`→jump upstream 属 M1-10 交付 | 脚本对 jump 裸实例断言语义；M1-10 起并入 nginx+多实例链路 |
| M1-08 只交付纯函数模块，jump 尚未调用 | DESIGN 5.2 的拼接点在跳转链路，但归因值（channel/campaign/promoter）此时只存在于 short_link 行，RouteConfig 未携带，且 M1-09 ClickEvent 才是消费方 | 模块+属性测试先行收口验收口径；M1-09 把归因字段并入 route_json/ClickEvent 时在 Direct302Resolver 接线（Location=merge 结果） |
| M1-08 `trace_id` 不追加到 URL | 计划行写"生成 xy_click_id + trace_id"，但附录 B 跳转参数契约不含 trace_id，追加即违反"只允许白名单参数" | trace_id 经 `Result.traceId()` 返回，随 M1-09 ClickEvent/日志贯通（DESIGN 8.5 口径），URL 保持最小契约 |

## 5. 本期决策记录

- **M1-02 迁移方式**：不用 Flyway（其历史表经 ShardingSphere 路由繁琐），采用 `deploy/compose/init/mysql/*.sql` 幂等脚本：全新环境 entrypoint 自动执行，已有数据卷 `make db-init` 补建。单一来源、双路径可重放。
- **M1-02 分片方案**：ShardingSphere-JDBC 5.5.1 spike 成功（Boot 3.3.4 兼容，`mvn verify` 全绿），无需退化为"双库手工路由"；配置落 `sl-admin/src/main/resources/sharding.yaml`，M1-06 起连接参数改环境变量注入。
- **M1-04 签名口径**：规范化串 `METHOD\npath\ntimestamp\nnonce\nsha256hex(body)`，HMAC-SHA256 hex；MVP 阶段 api_key 即共享密钥（DESIGN 9.4 的 key/secret 分离留到 M2 轮换机制一起做）。租户字典走 Redis `sl:tenant:api`（`make seed` 灌入），网关保持不依赖 MySQL——与管理面数据源隔离的原则从第一天成立。
- **Reactor 陷阱记录**：网关过滤器成功链路 `chain.filter()` 返回 `Mono<Void>`（空完成），尾随 `switchIfEmpty(拒绝)` 会在放行后误触发写 401——表现为"合法签名也得 401"。修复用 `thenReturn(TRUE)` 哨兵隔离；单测补"成功路径不得设置响应状态"断言防回归。
- **M1-05 池模型**：Redis List 为租用唯一活性来源（`LPOP count` 原子批量出池保证并发零重复），`short_code_pool` 表仅作登记账本（`INSERT IGNORE` + 租用状态回写），用于 Redis 数据丢失后按 status=0 重建池与审计；全局唯一性第一道防线是 seen SET（SADD 去重），表主键冲突是兜底。补水位阈值 `capacity×refill-ratio`，补池临界区用 SETNX 60s 锁串行化。
- **M1-06 双分片轴写入口径**：short_link+outbox 同 tenant_id 分片轴 → 一个本地事务真原子；link_route+code_tenant_index 在 short_code 轴（另一库），主事务提交后同步补写——失败时 API 如实报错但 outbox(status=0) 留痕，M3 补偿重放收敛。跨轴不做 XA：与 DESIGN 8.4"outbox+最终一致"一致，M1 同步写只是把常态延迟降到 0。短码唯一性 = Redis SETNX(`sl:code:{code}`) 第一道 + index/route 主键兜底。
- **M1-07 三级读与负反馈**：L1 Caffeine(30s) 含空结果缓存（新建链接最长 30s 内可能仍 404，个人项目口径可接受，M2 可加 admin→jump 广播失效）；L2 Redis 命中直路；L3 回源后 Lua `version 比较`写回防旧覆盖新（DESIGN 8.4 缓存回写口径落地）。access_limit 扣减用 Lua（首次以配额初始化→DECR），返回 -1 判超限；与 DB 定期对账留 M2。`JumpResolver` 接口 + `xsl.jump.mode=direct302` 即 DESIGN 第十章纯 302 开关。
- **M1-08 拼接语义**：以"追加段插在首个 `#` 之前"为规范（query 注入止于 fragment 边界）；禁覆盖判定同时匹配原文与百分号解码后的键，防 `%6E` 变形绕过；`xy_click_id` 只信模块生成（attrs 中同名键丢弃）；编码采用 RFC 3986 unreserved 白名单而非 `URLEncoder`（后者把空格编成 `+`、保留 `*`，与 query 语义混叠）。jqwik 作为 test-only 依赖进 sl-common，不污染运行时。
- ~~（待定）M1-02 是否真用 Flyway~~ 已决，见上。

## 6. 下期待办与风险

- M1-09 ClickEvent 链路（jump 异步 Kafka+WAL、consumer→CH、`GET /stats/links/{code}`）；归因字段进 route_json 后在 Direct302Resolver 接线 `AttributionParamMerger`（M1-08 遗留集成点）。M1-10 nginx+jump×2 多域名。
- sharding.yaml 环境变量注入已完成（`?placeholder-type=environment`）；容器内 profile 注入 `XSL_MYSQL_HOST=mysql` 留 M1-10 compose 化时接线。
- 生成 API 尚未接 `xsl_base.tenant.quota_*` 配额校验（DESIGN 5.1 步骤 1）；与白名单 DB 化、outbox 补偿任务（`POST /internal/outbox/replay`）一并在 M2/M3 落地，M1 越权与一致性路径已按口径实现。
- outbox.id 目前为分片内 AUTO_INCREMENT，跨分片不唯一——M3 outbox 实现时改 ShardingSphere key-generator(SNOWFLAKE) 或应用侧雪花。
