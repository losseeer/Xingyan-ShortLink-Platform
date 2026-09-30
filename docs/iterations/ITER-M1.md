# 迭代说明：M1（已收口）

**日期**：2026-09-29 22:13 开工（`54cec97`）→ 2026-09-30 收口　**tag**：`m1`　**周期**：计划 12.5 人日；实际跨 2 个自然日的连续会话完成 12 项，M1-11（压测存档）经确认顺延（见 §4/§6）

## 1. 本期目标回顾

跳转最小闭环 + 事件链路（DEVELOPMENT_PLAN M1，13 任务）。收口结果：**12 项达成并各有可重跑的验收证据，M1-11（压测存档）经用户确认顺延到 M2 首项**。

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
| M1-07 跳转服务（纯 302） | ✅ | sl-jump `GET /s/{code}` 与裸 `/{code}`：Caffeine(L1,30s)→Redis(L2)→link_route 回源(L3) 三级读、空值标记 `sl:r:nx:{code}` 防穿透、Lua version 比较防旧值写回覆盖、status/expire/access_limit(Lua 原子扣减)检查→302；`JumpResolver` 接口即 DESIGN 第十章"纯 302 开关"扩展位；单测 6+活体集成 3，`make verify` 全仓 50 测全绿；`scripts/accept-m1-07.sh` 对活 jump(8020) 14/14 通过（302·404+标记·403·410 过期·410 超限·二查 db 计数不增）；M1-08 归因接线与 M1-10 容器化后同一脚本复跑 15/15（断言拆分见 §4 末行） |
| M1-08 归因参数透传 | ✅ | sl-common `AttributionParamMerger`：白名单=附录 B 契约（channel_id/campaign_id/promoter_id/utm_*），`&/?` 续接（悬空 `?` 复用）、追加段插在 `#` fragment 之前、值按 RFC 3986 unreserved 严格 URL-encode、禁覆盖目标原有参数（含白名单键，编码形态键 `%6E` 亦可识别）、`xy_click_id`（c+雪花）由模块生成且拒绝外部传入、`trace_id` 随 Result 返回但不入 URL；jqwik 属性测试 3 性质（400/300/200 例）+ 确定性单测 9 项，`make verify` 全仓 62 测全绿 |
| M1-09 ClickEvent 链路 | ✅ | 归因字段（channel/campaign/promoter）进 `RouteConfig` 快照→jump `Direct302Resolver` 接线 M1-08 拼接器（302 Location=目标 URL+白名单参数，utm_* 从短链请求 query 透传）；ClickEvent（sl-common DTO，event_id=UUID 幂等键、IP 加盐 SHA-256 脱敏）经 spring-kafka 异步投递 `shortlink-click`（分区键 short_code），失败落按天 JSONL WAL、15s 定时重放（DESIGN 8.3 Kafka 挂预案）；sl-consumer 批量监听+手动 ack→CH 原生 HTTP JSONEachRow 写入（零驱动依赖，3 连试不提交）+`GET /api/v1/stats/links/{code}`（count FINAL 直查）；`make verify` 全仓 63 测全绿（含 consumer 活体链路测试：重复投递去重后 FINAL 恰 2）；`scripts/accept-m1-09.sh` 14/14：1000+3 击 2s 内 CH 可见、stats 一致、pause kafka 期间 201 击 WAL 有记录且跳转仍 302、unpause 重放后总数 1204=期望、WAL 清零 |
| M1-10 网关与多域名 | ✅ | 四个应用容器化：`deploy/docker/Dockerfile.app`（JRE+宿主 jar，编译不进镜像）由 `scripts/build-images.sh`/`make images` 出 `xsl/{gateway,admin,jump,consumer}:dev`；compose 注入容器内地址（`XSL_MYSQL_HOST=mysql`、`XSL_REDIS_HOST=redis`、`XSL_KAFKA_BOOTSTRAP=kafka:9092`、`XSL_CH_URL=http://clickhouse:8123/`）+ `restart: always` + actuator 健康探针；nginx 分流：`/s/**` 与裸 `/{code}`→upstream `xsl_jump`(jump-1/jump-2 轮询)、`/api/v1/stats/**`→consumer、`/api/**`→gateway→admin、未登记 Host 一律 444，`server_name xy1.test xy2.test xy3.test` 三域同规则；`make verify` 全仓 64 测全绿；`scripts/accept-m1-10.sh` 19/19（含冷启动 `compose down`→`up -d --wait` 十容器全 healthy 后复跑） |
| M1-11 压测存档 | ⏸ 顺延（用户决定 2026-09-30） | 未跑：wrk 未安装、本机 Docker Desktop VM 仅 3.9G（全栈常驻约 3.3G，available 仅 0.4G），此状态下测得的是资源争抢而非链路吞吐，达不到"如实记录实际值+瓶颈分析"的验收质量。经确认选择先收 M1-12，压测与 `bench/reports/` 存档作为 M2 首项任务，前置条件已写入 §6 |
| M1-12 里程碑收口 | ✅ | DESIGN 1.3 增"实测（截至 m1）+凭证"两列（只登记验收脚本已产出的口径，未测项显式标注）；根 `README.md` 新建（架构速览、前置条件、一键演示、验收脚本索引、当前状态）；`scripts/demo.sh` + `make demo` 打通从零复现：`compose down -v` → `up -d --wait` → 幂等建库/seed → HMAC 签名经 nginx→gateway→admin 创建 → 三域点击 → stats 追平，实测 10/10 击即时可见；本地 tag `m1`（远程推送待用户确认） |

## 3. 实测数字与凭证

| 指标 | 目标 | 实测 | 凭证 |
|---|---|---|---|
| 分片路由正确性（M1-02） | 单分片键查询不广播、跨键稳定落库 | ✅ 通过 | `sql-show` 输出：tenant 1001→ds1、1002→ds0；`SELECT WHERE tenant_id=1001` 仅 Actual ds1；`WHERE short_code='spike1001'` 仅 ds0；无分片键 `LIKE` 广播 ds0+ds1。测试 `ShardingRoutingVerificationTest` 断言物理库精确落点 |
| 短码唯一性（M1-05） | 生成/租用全链路无重复 | ✅ 通过 | `ShortCodeGeneratorTest`：20 万样本撞码 <10、字符集/长度正则校验；`ShortCodePoolServiceTest`（活体 infra）：并发 20×50 租用后 Set 大小=1000（零重复）；`sql-show` 可见 pool 登记按 short_code 精确路由 ds0/ds1、租回写 UPDATE 双库各命中自身分片 |
| 生成链路一致性（M1-06） | 三表+outbox+缓存一致、越权拒绝 | ✅ 通过 | `accept-m1-06.sh` 对活 sl-admin(8030) 21 项断言：创建 200+7 位码+short_url、双分片库求和后 short_link/code_tenant_index/outbox 各 1 行、route_json 与 `sl:r:{code}` 缓存一致（version 1）、evil.test/http→400 SL-4001、他租户 GET→403 SL-4030、自定义码冲突→409、保留字→400、PATCH 后缓存与 link_route version=2、同 Idempotency-Key 二次 POST 返回同一 code |
| 跳转正确性与缓存层级（M1-07） | 302/404/403/410 语义 + 冷码回源二查命中缓存 | ✅ 通过 | `accept-m1-07.sh` 对活 sl-jump(8020) 断言：正常码 302 且 Location 含 origin_url（`/s/` 与裸路径同义）；不存在码 404 且 `sl:r:nx:` 标记生成；status=1→403；过期→410；access_limit=2 第 3 击→410；冷码首查后 `xsl_jump_route_lookup_total{level="db"}` 1.0→2.0、二查保持 2.0 不增（指标计数佐证回源仅一次），`sl:r:{code}` 写回存在。断言数随契约演进：M1-08 接线后 Location 每条多 `?xy_click_id=c<雪花>`，原"逐字符等于 origin_url"一条按 §4 末行拆成两条（14→15 项），容器化栈复跑 15/15 |
| 归因拼接健壮性（M1-08） | 任意 query/编码/`#` 片段下结果可被 `java.net.URI` 解析且原参数逐字节无损；注入值不改语义 | ✅ 通过 | `AttributionParamMergerPropertyTest` 3 性质（tries 400/300/200）：生成 URL 覆盖 空/悬空`?`/多参/尾`&`/编码片段/任意 fragment 六形态，断言原 query 前缀逐字节保留、fragment 原样、追加对数=Result.appended 数；注入样本（`a&b=1`、`%26evil=1`、`x#y`、中文、200 字符长串）后追加段恒为 2 对且解码还原原值；`surefire-reports`：PropertyTest `Tests run: 3, Failures: 0`，确定性 `Tests run: 9, Failures: 0` |
| ClickEvent 端到端一致性与丢失率首证（M1-09） | 创建→1000 击→CH `count() FINAL` 一致；Kafka 不可达期间 WAL 有记录、恢复重放后总数一致（丢失率 <0.01% 口径） | ✅ 通过 | `accept-m1-09.sh` 14 项断言（活体 admin/jump/consumer+kafka+CH）：批量 1000+3 击后 `xsl.click_event FINAL` =1003、端到端可见延迟 2s（目标 <60s）；`/api/v1/stats/links/{code}` =1003 与 CH 一致；`docker pause xsl-kafka-1` 期间 201 击 WAL 落盘 200+ 行且跳转仍 302（不阻塞）；`docker unpause` 重放后 FINAL=1204 恰等于期望值（0 丢失，重复由 event_id 幂等消化）、WAL 清零。活体测试 `ClickPipelineLiveTest`：同 event_id 双投递 FINAL 恰 2 |
| 跨域通用与滚动重启零中断（M1-10） | 三域同一 code 得同一 302；重启单个 jump 实例期间 0×5xx | ✅ 通过 | `accept-m1-10.sh` 19 项断言：三域（xy1/xy2/xy3.test）Location 去掉 `xy_click_id` 后逐字符相同、归因参数插在 `#p` 之前、裸 `/{code}` 同义；60 击的 upstream 分布 35/29（两实例都在承载，非单点）；`docker restart xsl-jump-1-1` + 8 并发 20s 共 3760 击 → 全 302、**0 次 5xx**、存活实例 `xsl_jump_requests_total{instance="jump-2",outcome="found"}` 7548→10997；被摘实例自动回轮询；`compose stop gateway admin` 期间 `/s/` 仍 302（同刻 `/api/**` 502 证明确实不可用），起回后**免 reload** 复位 401；未登记 Host→444；`/api/v1/stats/**` 经 nginx 命中 consumer（click_count 3865 与压测量级一致）。容器化后 M1-04/06/07/09 四套验收复跑 7/7、21/21、15/15、14/14 |
| 单机吞吐与延迟基线（M1-11） | ≥2000 QPS、P99<50ms、Kafka 1 万 events/s、短码 1000 TPS | ⏸ **未跑**（不填任何数字，避免以目标值冒充实测） | `bench/reports/` 保持空目录；顺延原因与前置条件见 §4 末两行、§6 首行 |
| 从零端到端复现（M1-12） | `make demo` 在 `compose down -v` 后一次跑通：创建→三域点击→看板出数 | ✅ 通过 | 实跑输出（全新卷）：`up -d --wait` 十容器 healthy → 幂等重放 init/mysql → HMAC 签名经 nginx→gateway→admin 创建得 `code=APrURI5` → xy1/xy2/xy3.test 各 3 击全 302（Location 含 `channel_id/campaign_id/promoter_id/utm_source` 且插在 `#buy` 之前）+ `/s/` 形态 1 击 = 期望 10 → `stats/links/APrURI5` 首查即 `click_count:10`（0s 追平）；未登记 Host `evil.test` → 000（444）。凭证脚本 `scripts/demo.sh`，README「一键演示」章节同口径 |
| 收口全量回归（M1-12） | 五套验收在全新数据卷上重跑，结果与 §3 各行一致 | ✅ 通过 | 同一台机器、`down -v` 重建后的栈上复跑：M1-04 **7/7**、M1-06 **21/21**、M1-07 **15/15**、M1-09 **14/14**（含 pause kafka 的 WAL 演练，`expected=1204 actual=1204`、WAL 清零）、M1-10 **19/19**（滚动重启窗口零 5xx 与三域同一 Location 在新环境复现）；`make verify` 全仓 64 测绿（M1-10 收口时口径，代码未变） |

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
| M1-09 ClickHouse 宿主侧访问需专用账号 | 镜像 entrypoint 把 default 用户锁在容器内回环；宿主连接经 Docker Desktop VM NAT，源 IP 不固定（ip_range 白名单全部不命中），且只读 bind mount 下 users.d 热重载不生效 | 新增 `xsl_app/xsl-dev` 账号（users.d/xsl-app-user.xml，::/0+口令口径：8123 只发布在宿主回环，边界=端口不出机器+口令），改文件后需 `compose up -d` 重建生效；DESIGN 无需改（部署细节） |
| M1-09 事件字段 M1 简化 | DESIGN 6.3/4.2 含 UA 容器识别、IP 地理、风控评分 | device/os/province/city 空串、risk_score/is_bot=0 入表（列已就位），UA 解析属 M2 动态路由、风控属 M2/M3，届时只是填充值不改链路 |
| M1-09 WAL 简化为整文件摘取 | 严格逐行确认需写入偏移/重试行号管理 | 重放=同步摘取删除整文件→逐条 send，再失败由回调重新 append；崩溃在重放中途可能重复投递（at-least-once+event_id 幂等覆盖，与 DESIGN 口径一致）；逐行水位留 M2 与 outbox 补偿一起做 |
| M1-09 stats 落点 sl-consumer | 计划未指定模块；看板查询统一走 CH（DESIGN 6.4），与管理面 MySQL 隔离 | consumer 起 web 端口 8040 提供 `GET /api/v1/stats/links/{code}`；M1-10 nginx 路由 `/stats/**` 时接线；鉴权（租户越权查询）留 M2 与配额一起 |
| M1-10 `jump ×2` 用两个显式服务而非 `--scale jump=2` | 计划行写的是 scale；nginx upstream 要按稳定容器名逐个摘除，而 scale 实例的 per-container DNS alias 依赖 compose 版本，验收 `docker kill xsl-jump-1-1` 也无从固定编号 | compose 里 `jump-1`/`jump-2` 两服务共用一份镜像与 env 模板；DESIGN 3.3 的语义（×2、轮询、逐个摘除观察零中断）不变。弹性实例数留 M2（届时配 OpenResty/注册发现动态 upstream）|
| M1-10 `/api/**` 经 gateway 而非直连 admin | 计划字面是 `/api/**`→admin，但 M1-04 的 HMAC 鉴权层就在 gateway，直连等于把鉴权面裸露；admin 只信任网关注入的 `X-Tenant-Id` | nginx `/api/**`→gateway:8010→admin:8030；`/api/v1/stats/**` 以更长前缀优先命中 consumer |
| M1-10 数据面不挂 sl-gateway | DESIGN 3.3 要求"管理面宕机不影响存量跳转"，跳转链路上每多一跳就多一个可用性依赖与一份 P99 | nginx `/s/**` 与裸 `/{code}` 直连 `upstream xsl_jump`；限流/防刷按 IP 属 jump 侧（DESIGN 7.x 的 Sentinel 落点随 M2 风控一起）。验收 D2 实测：`compose stop gateway admin` 时 `/s/` 仍 302 |
| M1-10 nginx 缓存 upstream IP，容器 stop/start 后地址会换 | 本机实测：`compose stop gateway admin` 再 start，Docker 把原属 gateway 的 172.18.0.6 分给了 admin，nginx 仍连旧地址 → `/api/**` 持续 502 | 控制面（单实例、不需要同请求改投）改用 `resolver 127.0.0.11 valid=2s` + 变量 `proxy_pass` 自愈，验收 D3 明确断言"免 reload 复位"；数据面保留 upstream 块（要 LB+`proxy_next_upstream`），代价是 recreate 换 IP 需 `make nginx-reload`（优雅重载不断流），已记入 §6 |
| M1-10 未写宿主 /etc/hosts | 改系统文件要 sudo，且验收要证明的是 nginx 按 `server_name` 分流，不是本机解析能力 | 全程 `curl --resolve xyN.test:80:127.0.0.1`；README 的演示章节给可选 hosts 一行命令（M1-12）|
| M1-10 内存预算与 DESIGN 3.3 表不符 | 3.3 按 16G 宿主设计，而本机 Docker Desktop VM 只分配 3.9G（其中还要跑用户自己的 argagent 容器） | mem_limit 收到 admin 640M、jump/gateway/consumer 512M，JVM 用 `-XX:MaxRAMPercentage=55 -XX:+UseSerialGC`；实测常驻 jump≈254M、gateway≈190M、admin≈261M、consumer≈165M，全栈十容器 3.3G/3.9G、available 仅约 0.4G → **M1-11 压测前须把 Docker Desktop 内存调到 ≥8G**，否则压测数字测的是资源争抢 |
| M1-10 基镜像用 jammy 不用 alpine | `eclipse-temurin:17-jre-alpine` 无 arm64 manifest，Apple Silicon 直接拉不动 | `Dockerfile.app` 的 `BASE_IMAGE` 提为可覆盖 ARG（`BASE_IMAGE=... scripts/build-images.sh`），离线/内网可换成本地已有 JRE 镜像 |
| M1-10 三套旧验收脚本默认值随容器化调整 | 应用从宿主进程变容器后，宿主端口与文件路径口径变了；且 M1-07 的 Location 断言写作于 M1-08 拼接器接入之前，M1-09 收尾时漏跑回归（当时起每次跳转都会多 `?xy_click_id=`） | M1-04 网关默认 `http://localhost:8110`；M1-07 把 Location 断言按契约拆成"origin 前缀 + `xy_click_id=c<雪花>`"两条（14→15 项）；M1-09 `WAL_DIR` 改指 jump-1 的 bind mount 并按脚本自身位置解析。容器化栈上复跑：M1-04 7/7、M1-06 21/21、M1-07 15/15、M1-09 14/14 |
| M1-11 未执行即收 M1（计划顺序被打断） | 两项硬前置不满足：本机 Docker Desktop VM 只有 3.9G（全栈常驻 3.3G，余 0.4G 连压测客户端一起跑必然测到资源争抢），且 wrk 未安装、装第三方包需用户确认——此时产出的 QPS/P99 是"错误的数字"而非"保守的数字"，比空缺更有害（1.3 明令不得以目标值冒充） | 经用户确认（2026-09-30）跳过压测直接收口：`bench/reports/` 保持空、DESIGN 1.3 对应两行显式写"未测 + 前置条件"、8.1-A 增实测进度段；M1-11 作为 M2 首项任务，工具选型（`brew install wrk` vs 用现成 `ab`）届时一并定 |
| M1-12 demo 的点击地址用 `http://xy1.test/{code}` 而 API 返回 `https://` | `short_url` 按 DESIGN 5.1 是生产口径 https，本机 nginx 只监听 80，硬造一个自签证书链或加 `XSL_LINK_SCHEME` 开关都是为演示而改产品行为 | demo 打印两个地址并说明等价关系（脚本第 3 步），README 同样标注；nginx TLS 与 mock 购票页容器一并留 M2（§6） |
| M1-12 demo 不清理 fixture | 验收脚本必须自净（`accept-m1-10.sh` 有 `trap cleanup EXIT`），但演示的用途是让人接着手工复看（再点几次、查 CH、看 nginx 日志），删掉就没得看了 | 保留每次运行新建的 code（短码池足够大，不冲突）；`docs/DESIGN.md` 之外的脏数据用 `compose down -v` 一次清零，demo 末行给出该命令 |

## 5. 本期决策记录

- **M1-02 迁移方式**：不用 Flyway（其历史表经 ShardingSphere 路由繁琐），采用 `deploy/compose/init/mysql/*.sql` 幂等脚本：全新环境 entrypoint 自动执行，已有数据卷 `make db-init` 补建。单一来源、双路径可重放。
- **M1-02 分片方案**：ShardingSphere-JDBC 5.5.1 spike 成功（Boot 3.3.4 兼容，`mvn verify` 全绿），无需退化为"双库手工路由"；配置落 `sl-admin/src/main/resources/sharding.yaml`，M1-06 起连接参数改环境变量注入。
- **M1-04 签名口径**：规范化串 `METHOD\npath\ntimestamp\nnonce\nsha256hex(body)`，HMAC-SHA256 hex；MVP 阶段 api_key 即共享密钥（DESIGN 9.4 的 key/secret 分离留到 M2 轮换机制一起做）。租户字典走 Redis `sl:tenant:api`（`make seed` 灌入），网关保持不依赖 MySQL——与管理面数据源隔离的原则从第一天成立。
- **Reactor 陷阱记录**：网关过滤器成功链路 `chain.filter()` 返回 `Mono<Void>`（空完成），尾随 `switchIfEmpty(拒绝)` 会在放行后误触发写 401——表现为"合法签名也得 401"。修复用 `thenReturn(TRUE)` 哨兵隔离；单测补"成功路径不得设置响应状态"断言防回归。
- **M1-05 池模型**：Redis List 为租用唯一活性来源（`LPOP count` 原子批量出池保证并发零重复），`short_code_pool` 表仅作登记账本（`INSERT IGNORE` + 租用状态回写），用于 Redis 数据丢失后按 status=0 重建池与审计；全局唯一性第一道防线是 seen SET（SADD 去重），表主键冲突是兜底。补水位阈值 `capacity×refill-ratio`，补池临界区用 SETNX 60s 锁串行化。
- **M1-06 双分片轴写入口径**：short_link+outbox 同 tenant_id 分片轴 → 一个本地事务真原子；link_route+code_tenant_index 在 short_code 轴（另一库），主事务提交后同步补写——失败时 API 如实报错但 outbox(status=0) 留痕，M3 补偿重放收敛。跨轴不做 XA：与 DESIGN 8.4"outbox+最终一致"一致，M1 同步写只是把常态延迟降到 0。短码唯一性 = Redis SETNX(`sl:code:{code}`) 第一道 + index/route 主键兜底。
- **M1-07 三级读与负反馈**：L1 Caffeine(30s) 含空结果缓存（新建链接最长 30s 内可能仍 404，个人项目口径可接受，M2 可加 admin→jump 广播失效）；L2 Redis 命中直路；L3 回源后 Lua `version 比较`写回防旧覆盖新（DESIGN 8.4 缓存回写口径落地）。access_limit 扣减用 Lua（首次以配额初始化→DECR），返回 -1 判超限；与 DB 定期对账留 M2。`JumpResolver` 接口 + `xsl.jump.mode=direct302` 即 DESIGN 第十章纯 302 开关。
- **M1-08 拼接语义**：以"追加段插在首个 `#` 之前"为规范（query 注入止于 fragment 边界）；禁覆盖判定同时匹配原文与百分号解码后的键，防 `%6E` 变形绕过；`xy_click_id` 只信模块生成（attrs 中同名键丢弃）；编码采用 RFC 3986 unreserved 白名单而非 `URLEncoder`（后者把空格编成 `+`、保留 `*`，与 query 语义混叠）。jqwik 作为 test-only 依赖进 sl-common，不污染运行时。
- **M1-09 事件链路口径**：ClickEvent 幂等键=event_id(UUID v4)，Kafka at-least-once+CH ReplacingMergeTree 读时 FINAL 去重（验证脚本以 FINAL 计数为准）；WAL 兜底为按天 JSONL、`pendingFiles/take/append` 全部同步互斥，重放由 15s 定时+启动触发；IP 只落加盐 SHA-256 前 16 位（DESIGN 8.5 脱敏）；CH 写入走原生 HTTP JSONEachRow（宿主侧零 JDBC 依赖，容器内换 `http://clickhouse:8123` 同一实现）；归因三字段进 route_json 快照而非回查 short_link（保持 jump 只读单表、跨分片轴零额外查询）。
- **M1-10 入口分层**：nginx 是唯一对外入口，只做「按 Host/path 分流 + 数据面 LB」；sl-gateway 是管理面内部的鉴权/限流层，跳转链路不过它（数据面独立性优先于「统一网关」的形式整齐）。应用镜像刻意不做多阶段构建：宿主 `mvn package` 产物 + JRE 一层，换来离线可复现与秒级重建（`make images`）；四个应用共用同一份 `XSL_*` 环境变量注入，各自只读自己用到的键。`server_name xy1/xy2/xy3.test` 共用同一组 location 规则——域名只是 Host 匹配的一个值，链路里没有任何"域名归属"状态，这就是 DESIGN 10.2「code 与域名无绑定」的物理表达。
- **M1-11 顺延（不凑数）**：资源不到位时产出的 QPS/P99 是"错误的数字"而不是"保守的数字"——它会进 DESIGN 1.3、进 README、将来进简历，而 1.3 自己规定不得以目标值冒充实测。故选择空着 `bench/reports/` 并显式标注未测，比先跑一轮再回头修正更诚实、成本更低（用户 2026-09-30 确认）。
- **M1-12 演示与验收的分工**：断言只长在 `scripts/accept-m1-*.sh` 里，`scripts/demo.sh` 不做校验、只做串演并打印每一环的原始输出——两者重复会导致契约变更时要改两处。demo 刻意走**真实管理面链路**（HMAC 签名经 nginx→gateway→admin），不再是 M1-06 那种注入 `X-Tenant-Id` 的捷径，于是签发链路第一次以生产形态被覆盖到。"归因参数看得见"这一环用 302 的 Location 原文作为证据（CLI 即可证），浏览器侧的 mock 购票页容器不阻塞收口，留 M2。demo 保留 fixture 不清理，理由见 §4。
- ~~（待定）M1-02 是否真用 Flyway~~ 已决，见上。

## 6. 下期待办与风险

- **M1-11 压测存档 → M2 首项**：交付物是 `bench/reports/`（报告模板：日期/JVM 参数/宿主型号/结果四元组）+ 预置 1000 热码的压测脚本 + Kafka 两段测（先停 consumer 测堆积，再放开测追赶收敛）。**两个硬前置**：① Docker Desktop 虚拟机内存 3.9G→≥8G（要用户操作并重启 Docker，会连带重启其 argagent 容器，属需事先确认的动作）；② 压测工具选型——本机无 wrk、只有 `/usr/sbin/ab`，`brew install wrk` 需用户同意装第三方包（M1-10 验收用过 8 并发 curl，量级约 190 QPS，够回归不够达标口径）。
- **收口纪律（本期学到，M2 起执行）**：凡改公共契约（跳转 Location、ClickEvent 字段、`route_json` 结构）的任务，收口前必须重跑受影响链路的**全部旧验收脚本**并把结果记进 §3。本期 M1-09 接线后没重跑 M1-07，那条按旧契约写的 Location 断言就此失效，直到 M1-10 容器化整套复跑才暴露（§4「三套旧验收脚本默认值随容器化调整」行）；M1-12 收口按此口径在全新数据卷上重跑五套，7/7、21/21、15/15、14/14、19/19 全绿（§3 末行），并已把该纪律写进 `docs/DEVELOPMENT_PLAN.md` 工程约定。
- **M1-10 遗留的两件运维小事**：① 数据面 nginx 用静态 upstream，容器 `--force-recreate` 换 IP 后要 `make nginx-reload`（已提供 target）；② `xy*.test` 未写宿主 /etc/hosts——README「一键演示」章节已给出可选的 `sudo tee -a /etc/hosts` 一行命令（M1-12 关闭"文档缺失"，是否执行由用户决定；验收与 demo 走 `--resolve`，不依赖它）。
- 短链 `short_url` 由 admin 生成的是 `https://{主域}/{code}`（DESIGN 5.1 步骤 6），而本机 nginx 只监听 80：M1-12 的处理是 README/demo 同时打印等价的 `http://xy1.test/{code}`，不动产品行为（见 §4）。留给 M2 的是真正闭环的两件事：nginx 自签证书（或 `XSL_LINK_SCHEME` 开关），以及 DESIGN 3.3 预算表里的**mock 购票页容器**（静态回显归因 query）——没有它，浏览器点 302 之后是 DNS 错误，"主办方看到参数被带过去"这一环只能靠 CLI 证据。
- 生成 API 尚未接 `xsl_base.tenant.quota_*` 配额校验（DESIGN 5.1 步骤 1）；与白名单 DB 化、outbox 补偿任务（`POST /internal/outbox/replay`）一并在 M2/M3 落地，M1 越权与一致性路径已按口径实现。
- outbox.id 目前为分片内 AUTO_INCREMENT，跨分片不唯一——M3 outbox 实现时改 ShardingSphere key-generator(SNOWFLAKE) 或应用侧雪花。
