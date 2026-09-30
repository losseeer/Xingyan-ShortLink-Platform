# 开发计划（基于 DESIGN.md v2.2）

**节奏假设**：业余时间 10-15h/周；M1 ≈ 3 周。任务粒度 = 一次可提交的完整闭环（代码 + 测试 + 可执行验收命令）。
**总原则**：每个任务的验收动作必须是"跑一条命令/脚本看结果"，不接受"看起来对了"；压测与演练输出即时存档 `bench/`（1.3 口径凭证）。

---

## 一、M1 详细计划（跳转最小闭环 + 事件链路）

M1 范围裁剪说明：频控/行为评分/动态路由归 M2；`access_limit` 在 M1 只做 expire/status 检查，Lua 扣减归 M2；布隆阶段用 Guava BloomFilter + 空值缓存即可（M1 无封禁删除流程），Cuckoo 替换随 M2 停用/封禁流程一起做（DESIGN 5.2 的删除能力届时才成为硬需求）。

| # | 任务 | 内容与交付物 | 验收动作 | 依赖 | 估时 |
|---|---|---|---|---|---|
| M1-00 | **仓库骨架** | `git init` + monorepo 模块树（DESIGN 3.4：sl-common/gateway/admin/jump/consumer/console-web/mock + deploy + bench）、Java 17 + Boot 3.x BOM、`.gitignore` | `mvn -q verify` 全模块绿；`mvn dependency:tree` 验证 sl-jump 不依赖 sl-admin（Enforcer 规则 + `-ban` 断言） | — | 0.5 天 |
| M1-01 | **中间件环境** | `deploy/compose/docker-compose.yml`：mysql8、redis、kafka(KRaft)、clickhouse、nginx；healthcheck + `--profile observability`(prometheus/grafana) + `--profile redis-ha`(占位) | `make up` 后 5 分钟内 `docker compose ps` 全 healthy；Nacos 暂不引入（8G 友好，M1 用静态配置，M2 决策是否补） | M1-00 | 0.5 天 |
| M1-02 | **MySQL 数据模型 + 分片** | Flyway DDL：`xsl_00/xsl_01` 双库全套表（short_link/link_route/code_tenant_index/short_code_pool/outbox/tenant/audit_log），ShardingSphere JDBC（5.5.x，Boot3 兼容版）按 tenant_id / short_code 双策略路由 | 集成测试：插入后断言记录实际落库（`information_schema` 查询命中预期库）；同一 short_code 在两张分片键不同的表中可关联 | M1-01 | 1 天 |
| M1-03 | **ClickHouse 模型** | `click_event`(ReplacingMergeTree by event_id) + `click_stat_minute`(SummingMergeTree + 物化视图) DDL 脚本入 `deploy/` | `clickhouse-client` 执行建表 + 手动插两条同 event_id 验证去重（FINAL 查询只剩一条） | M1-01 | 0.5 天 |
| M1-04 | **租户与 HMAC 鉴权** | tenant 表 seed 3 个 mock 租户；网关 HMAC 签名过滤器（时间戳窗口 + nonce 防重放）；`Idempotency-Key` Redis 24h 中间件 | `curl` 正确签名→200；改一字节签名→401；重放旧时间戳→401；同 Idempotency-Key 二次 POST→返回同一 code | — | 1 天 |
| M1-05 | **短码池服务** | CSPRNG Base62 生成（长度 7）、批量租用（LPOP 1000/批）、DB 登记、水位 <20% 补池（Redis SETNX 锁） | 单测：字符集/长度/碰撞率；集成测：`xargs -P` 并发 1000 次租用无重复码；打空池观察补池日志且补后无重复 | M1-02 | 1 天 |
| M1-06 | **生成 API** | `POST /api/v1/links`：校验→origin_url https+域名白名单（seed 白名单含 mock 域）→事务写 3 表+outbox→写 Redis 路由缓存→布隆 add→返回短链；`GET/PATCH /links/{code}` 含 code_tenant_index 越权检查 | E2E 脚本：创建成功返回 200 + code；DB 三表 + route_json 一致；非白名单 URL→400；他租户查该 code→403 | M1-04/05 | 1.5 天 |
| M1-07 | **跳转服务（纯 302）** | sl-jump `GET /s/{code}`：布隆→Caffeine→Redis→link_route 回源（空值标记防穿透）→expire/status 检查→302；**纯 302 开关**（DESIGN 第十章要求）即默认形态，路由匹配留接口 | 单测 + E2E：正常 302 且 Location 正确；不存在→404 落地页；过期→410；冷码回源后二查命中缓存（Redis MONITOR 或 metrics 计数佐证） | M1-06 | 1.5 天 |
| M1-08 | **归因参数透传** | 白名单参数拼接模块（sl-common）：`&/?` 续接、URL-encode、禁覆盖原有参数、生成 `xy_click_id` + trace_id | 属性测试（jqwik）：任意 origin_url 带 query/编码字符/`#` 片段时，拼接结果可被 `java.net.URI` 正确解析且原参数无损；注入样本（`&`/`%26` 伪造参数）不改变语义 | M1-07 | 1 天 |
| M1-09 | **ClickEvent 链路** | jump 异步发 Kafka（失败落 WAL 文件）+ consumer 批量幂等写 ClickHouse + `GET /stats/links/{code}` 最小版 | 计数脚本：创建→模拟点击 N=1000（脚本循环）→CH `count() FINAL` 一致；`docker stop kafka` 期间点击→WAL 有记录→恢复重放后总数一致（丢失率 <0.01% 首证） | M1-03/07 | 1.5 天 |
| M1-10 | **网关与多域名** | nginx：`/s/**`→jump upstream ×2（`--scale jump=2`）、`/api/**`→admin；hosts 配 `xy1/xy2/xy3.test` 三 server_name；compose `restart: always` | `curl --resolve` 三个域同一 code 得同一 302（跨域通用首证）；`docker kill jump-1` 期间 wrk 流量 0×5xx（滚动重启零中断，DESIGN 3.3 卖点实测 #1） | M1-07 | 1 天 |
| M1-11 | **压测存档** | `bench/`：wrk 登录脚本（预置 1000 热码）、micrometer 命中率指标、报告模板（日期/JVM 参数/宿主型号/结果四元组） | 连续 3 轮 wrk：≥2000 QPS 且 P99<50ms 达标或如实记录实际值+瓶颈分析清单；结果提交入 `bench/reports/`（简历口径凭证 #1） | M1-10 | 1 天 |
| M1-12 | **里程碑收口** | tag `m1`；把 M1-11/09/10 实测数字回填 DESIGN 1.3 表格（新增"实测"列）；README 增加一键演示章节；**产出 `docs/iterations/ITER-M1.md`** | `make demo`（up→seed→创建→点击→看板 curl 出数）在干净 `docker compose down -v` 后从零可复现；ITER-M1.md 按模板填齐且凭证链接可点达 | 全部 | 0.5 天 |

合计 ≈ 12.5 人日 ≈ 3 周业余时间，含约 20% 缓冲。

**执行顺序**：M1-00→01→02/03（并行）→04→05→06→07→08→09→10→11→12。04 可与 02 并行（不碰分片表）。

---

## 二、M2 / M3 粗粒度任务（进入前按 M1 同格式细化）

**M2（+2-3 周）动态路由与防刷**：
Cuckoo 替换（含停用/封禁移除流程）｜route_rule 表 + 编译 route_json 双轨 + 管理接口｜UA 容器特征表 + 一级路由引擎｜中间页 HTML + Scheme 降级 JS + mock UA 重放测试集｜频控 Lua（滑动窗口 + access_limit 原子扣减）+ 对账任务｜行为评分规则表 + IDC/代理库离线更新脚本（free 库 + 手工样本）｜MyBatis 租户拦截器 + 三级配额｜Sentinel 网关限流 + 429。
出口标准（DESIGN 第十章）：UA 重放脚本全绿；bot 分类正确。

**M3（+3 周）归因闭环与看板**：
outbox 同步器 + `version` 强校验通道｜归因回传 mock 宣发平台（batch_id + 校验和幂等）｜**Vue 3 看板**：链接列表 / 渠道漏斗 / 双指标三页（Element Plus + ECharts，同源反代）｜8.3 降级演练全表（逐条 docker stop 存档）｜多域名切换演练｜端到端对账脚本 <2%。
出口标准 + tag `m2`/`m3` 同 M1-12 模式。

**P2**：裂变批量签发、模型化反作弊、开源化（CI Badge、seed 数据脱敏、license）。

---

## 三、工程约定（跨里程碑生效）

- 版本钉选：Boot 3.3.x、ShardingSphere-JDBC 5.5.x（Boot3 兼容需开工首日验证，风险高则降为"双库手工路由 + 文档推演"，4.1 设计价值不受损）、Guava 33（Bloom）、Vavr 不引入。
- 测试分层：sl-common 属性测试（jqwik）；服务层 Testcontainers（复用 compose 镜像）；E2E 用 bash 脚本存 `bench/e2e/`，`make demo` 即 M1-12 产物。
- Commit 纪律：每任务一 squash 提交，附验收命令输出；里程碑 tag；`bench/reports/` 只增不改。
- **回归纪律（M1-12 收口补，跨里程碑生效）**：凡改公共契约（跳转 Location、ClickEvent 字段、`route_json` 结构）的任务，收口前必须重跑受影响链路的全部旧验收脚本，并把复跑结果写进当期 ITER 的实测表。起因：M1-09 给 jump 接归因拼接后没重跑 M1-07，那条"Location 逐字符等于 origin_url"当场失效却仍以绿记录在册，直到 M1-10 容器化整套复跑才暴露（ITER-M1 §5）。
- **迭代说明文档（硬性要求）**：每个里程碑收口必须产出一份 `docs/iterations/ITER-<里程碑>.md`（模板 `ITER-TEMPLATE.md`），内容含：本期做了什么/任务完成对照表（含砍掉与顺延项及原因）/实测数字与 `bench/` 凭证链接/对 DESIGN 的偏差与回写/下一期待办。**里程碑中途发生范围或设计变更时，先增改当期迭代文档再动代码**；文档与 tag 一起提交，缺一不算收口。
- **凭据纪律（m1 收口补，跨里程碑生效）**：仓库内不得出现任何可用凭据的明文。开发栈口令一律放 `deploy/compose/.env`（gitignore，`make devenv` 生成），compose 用 `${VAR:?}` 强制注入、不给默认值；脚本经 `scripts/lib-devenv.sh` 取值，活体测试读环境变量（缺则跳过，绝不静默回落成别的口令）。中间件账号用幂等脚本在运行时建（`make ch-init`），不要写进只能落明文的配置文件（原 ClickHouse `users.d/xsl-app-user.xml` 因此撤下）。**例外**：`xy-key-alice-001` 这类 mock 租户 api_key 是设计内的演示密钥（DESIGN 9.4），不是环境凭据，留在 seed 里；等 M2 落地 key/secret 分离与轮换后再一并参数化。
- 当前仓库尚非 git 库：M1-00 第一步即 `git init`（属计划内动作，执行前不再另行确认）。
