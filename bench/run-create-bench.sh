#!/usr/bin/env bash
# M1-11 短码签发基准（DESIGN 8.1-A「≥1000 TPS，无重复码」+ 1.3「短码生成 P99<20ms」）：
# 经真实管理面链路 nginx→gateway(HMAC)→admin 并发签发，统计 TPS、唯一性与服务端响应延迟分位。
# 工具口径如实记录：wrk 的 Lua 没有 HMAC 原语（每请求时间戳+nonce 必须重签），
# 所以这一项用 python 线程池客户端；客户端与服务同宿主，数字按保守口径读。
source "$(dirname "$0")/lib.sh"

N_CREATE=${N_CREATE:-2000}
CREATORS=${CREATORS:-16}
API_KEY="${API_KEY:-xy-key-alice-001}"
OUT="$TMP_DIR/create-result.txt"
RUN=$(date +%s)

REPORT=$(report_new "m1-11-create")
echo "报告：$REPORT"
echo "- 命令口径：$CREATORS 并发 × 共 $N_CREATE 次 POST /api/v1/links（nginx→gateway→admin，逐请求签名）" >> "$REPORT"
echo "- 短码池：capacity=$($COMPOSE exec -T redis redis-cli LLEN sl:pool:0 | tr -d '\r') 支（签发会触发补池，属被测路径）" >> "$REPORT"

python3 - "$API_KEY" "$N_CREATE" "$CREATORS" "$OUT" "$RUN" <<'PY'
import concurrent.futures as cf, hashlib, hmac, json, statistics, sys, time, urllib.request, urllib.error
key, n, workers, out, run = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
entry, path = "http://127.0.0.1", "/api/v1/links"
codes, lat, errs = [], [], {}


def create(i):
    body = json.dumps({"origin_url": f"https://mock.ticketsales.test/create/{run}-{i}",
                       "channel_id": "ch-tps", "campaign_id": f"tps-{run}", "redirect_type": 1}).encode()
    ts = str(int(time.time() * 1000))
    nonce = f"tps-{run}-{i}"
    canonical = b"\n".join([b"POST", path.encode(), ts.encode(), nonce.encode(),
                            hashlib.sha256(body).hexdigest().encode()])
    sig = hmac.new(key.encode(), canonical, hashlib.sha256).hexdigest()
    req = urllib.request.Request(entry + path, data=body, method="POST")
    for k, v in (("Host", "xy1.test"), ("X-Api-Key", key), ("X-Timestamp", ts), ("X-Nonce", nonce),
                 ("X-Signature", sig), ("Content-Type", "application/json"), ("Idempotency-Key", nonce)):
        req.add_header(k, v)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            dt = time.perf_counter() - t0
            lat.append(dt * 1000)
            codes.append(json.loads(r.read())["data"]["short_code"])
    except urllib.error.HTTPError as e:
        dt = time.perf_counter() - t0
        lat.append(dt * 1000)
        errs[str(e.code)] = errs.get(str(e.code), 0) + 1
    except Exception as e:  # 连接层异常也要归类，否则"成功率"会被静默抬高
        tag = type(e).__name__
        errs[tag] = errs.get(tag, 0) + 1


with cf.ThreadPoolExecutor(max_workers=workers) as ex:
    t0 = time.perf_counter()
    list(ex.map(create, range(n)))
    wall = time.perf_counter() - t0

lat.sort()
def pct(p):
    if not lat:
        return 0.0
    return lat[max(0, min(int(round(len(lat) * p)) - 1, len(lat) - 1))]
lines = [
    f"requests={n} ok={len(codes)} unique={len(set(codes))} errors={errs}",
    f"wall={wall:.2f}s throughput={n/wall:.1f} req/s（目标 ≥1000 TPS）",
    f"延迟 ms：p50={pct(0.50):.1f} p95={pct(0.95):.1f} p99={pct(0.99):.1f} max={lat[-1] if lat else 0:.1f}"
    f"（目标 p99<20ms；含 nginx+gateway 两跳与客户端自身耗时，按保守口径读）",
    f"唯一性断言：{'通过' if len(set(codes)) == len(codes) else '失败（出现重复码）'}",
]
open(out, "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY
[[ $? -eq 0 ]] || { echo "签发基准执行失败"; exit 1; }

{
  echo
  echo '```'
  cat "$OUT"
  echo '```'
  echo
  echo "- 服务侧观测：$(docker stats --no-stream --format '{{.Name}} CPU {{.CPUPerc}} / MEM {{.MemUsage}}' | grep -E 'admin|gateway|mysql|redis')"
  echo "- 签发后短码池余量：$($COMPOSE exec -T redis redis-cli LLEN sl:pool:0 | tr -d '\r') 支"
  echo '- 说明：本项写入的真实链接留在库里（bench 夹具），`docker compose -f deploy/compose/docker-compose.yml down -v` 可整体复位' 
} >> "$REPORT"
