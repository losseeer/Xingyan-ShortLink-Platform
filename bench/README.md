# bench/ — 单机压测存档

DESIGN 1.3 那句"简历/分享中引用的所有数字必须来自本节实测记录"就是这里兑现的：
`bench/reports/` 里每一份都是**实际跑出来的输出**，`docs/DESIGN.md` 1.3 的"实测"列只引用这些文件。

## 复现步骤

```bash
make devenv && make images && make up          # 前置：凭据 + 镜像 + 十个容器 healthy
bash bench/setup-hot-links.sh 1000             # 预置 1000 条热码并预热 L1/L2
bash bench/run-jump-bench.sh                   # 跳转吞吐/P99（wrk -t8 -c200 -d60s ×3 轮）
bash bench/run-kafka-bench.sh                  # Kafka 两段测：停 consumer 测堆积 → 放开测追赶
bash bench/run-clickhouse-bench.sh             # ClickHouse 明细量级与聚合查询基准
bash bench/run-create-bench.sh                 # 短码签发 TPS + 唯一性
```

可调环境变量：`ROUNDS` / `DURATION` / `THREADS` / `CONNS`（跳转）、`N_OUTAGE`（堆积）、`CH_ROWS`（聚合）、`N_CREATE` / `CREATORS`（签发）。

## 口径约定（写报告时必须遵守）

1. **同机竞争如实声明**：压测客户端与服务共用一台 M5（Docker VM 7.75 GiB / 10 核），
   吞吐上界包含客户端自身开销，因此一律按保守口径报告，不声称"服务端上限"。
2. **未达标就写未达标**：报告必须给实际值 + 瓶颈分析清单，禁止把目标值写进实测列。
3. **只增不改**：`bench/reports/` 历史文件不修订；结论变化就新增一份，旧的留着（可追溯）。
4. **每份报告自带四元组**：日期、JVM 参数、宿主型号、结果——由 `bench/lib-bench.sh` 的 `host_facts` 自动写头部。
5. **报告文本里的命令不可执行**：说明性命令一律写在单引号里或用文本描述。本仓曾有一条 `echo "…"` 在双引号里用反引号包住 `docker compose down -v`，生成报告时真的执行了一次——写脚本时把这条当硬性规则。
6. **变体跑要带标签**：同一基准换配置再跑时加 `RUN_LABEL`（如 `RUN_LABEL=g1 bash bench/run-jump-bench.sh`），否则存档文件名与默认跑撞名，只能靠时间戳分辨（本轮就出过一次，已把 `…-1348…` 改名 `-g1` 并同步所有引用）。
7. **计数用指标而不是日志**：命中率取 `xsl_jump_route_lookup_total{level=...}` 的轮次增量，
   事件量取 ClickHouse `count() FINAL`，不拿 nginx access log 估算。

## 目录

| 文件 | 作用 |
|---|---|
| `lib-bench.sh` | 装载凭据（`scripts/lib-devenv.sh`）、指标快照、consumer lag、宿主事实、报告头部 |
| `setup-hot-links.sh` | 经 nginx→gateway(HMAC)→admin 批量签发热码 + 预热缓存 |
| `wrk/jump-path.lua` | wrk 请求脚本：随机码、固定 Host 头、只认 302 |
| `run-*.sh` | 四类基准的跑批与存档 |
| `reports/` | 存档输出（只增不改），命名 `YYYY-MM-DD-HHMM-m1-<任务>-<对象>[-<变体>].md` |
| `.tmp/` | 中间产物（热码清单等），不入库 |
