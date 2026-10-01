#!/usr/bin/env bash
# 预置热码：经真实管理面链路（nginx→gateway HMAC→admin）批量签发 N 条短链，供 wrk 打跳转用。
# 产物：bench/.tmp/hot-codes.txt（每行一个 short_code）；预热阶段每个 code 打一击，
# 把 link_route 灌进 Redis + jump 的 L1，让压测衡量的是"缓存命中路径"而不是回源。
# 用法：bash bench/setup-hot-links.sh [N]（默认 1000）
source "$(dirname "$0")/lib-bench.sh"

N=${1:-1000}
API_KEY="${API_KEY:-xy-key-alice-001}"
CODES_FILE="$TMP_DIR/hot-codes.txt"
RUN=$(date +%s)
CAMPAIGN="cp-$RUN"                       # 事件按 campaign 打标，后续 Kafka/CH 基准用它对账
printf 'CAMPAIGN=%s\n' "$CAMPAIGN" > "$TMP_DIR/bench-meta.env"

step() { printf '\n== %s ==\n' "$1"; }
step "1/3 签发 $N 条热码（HMAC 逐请求签名，8 并发）"

python3 - "$REPO_ROOT" "$N" "$API_KEY" "$RUN" "$CODES_FILE" "$CAMPAIGN" <<'PY'
import concurrent.futures as cf, hashlib, hmac, json, sys, time, urllib.request
root, n, key, run, out, campaign = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6]
entry = "http://127.0.0.1"           # nginx :80，Host 头决定落到哪个 server_name


def signed(i):
    body = json.dumps({
        "origin_url": f"https://mock.ticketsales.test/bench/{run}-{i}",
        "channel_id": "ch-bench", "campaign_id": campaign, "promoter_id": f"pr-{i % 50}",
        "redirect_type": 1}).encode()
    ts = str(int(time.time() * 1000))
    nonce = f"bench-{run}-{i}"
    canonical = b"\n".join([b"POST", b"/api/v1/links", ts.encode(), nonce.encode(),
                            hashlib.sha256(body).hexdigest().encode()])
    sig = hmac.new(key.encode(), canonical, hashlib.sha256).hexdigest()
    req = urllib.request.Request(entry + "/api/v1/links", data=body, method="POST")
    req.add_header("Host", "xy1.test")
    for k, v in (("X-Api-Key", key), ("X-Timestamp", ts), ("X-Nonce", nonce),
                 ("X-Signature", sig), ("Content-Type", "application/json"),
                 ("Idempotency-Key", nonce)):
        req.add_header(k, v)
    return req


codes, errs = [], []
with cf.ThreadPoolExecutor(max_workers=8) as ex:
    for r in ex.map(lambda i: urllib.request.urlopen(signed(i), timeout=15), range(n)):
        d = json.loads(r.read())["data"]
        codes.append(d["short_code"])
        if len(codes) % 200 == 0:
            print(f"   {len(codes)}/{n}", file=sys.stderr)
with open(out, "w") as f:
    f.write("\n".join(codes) + "\n")
print(f"created={len(codes)} unique={len(set(codes))} file={out}")
sys.exit(0 if len(codes) == len(set(codes)) == n else 1)
PY
[[ $? -eq 0 ]] || { echo "签发失败或码重复"; exit 1; }

step "2/3 预热缓存（每码 1 击，走 nginx→jump 真实入口）"
python3 - "$CODES_FILE" <<'PY'
import sys, urllib.request
codes = open(sys.argv[1]).read().split()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a): return None
op = urllib.request.build_opener(NoRedirect)
ok = 0
for c in codes:
    req = urllib.request.Request(f"http://127.0.0.1/s/{c}", headers={"Host": "xy1.test"})
    try:
        op.open(req, timeout=10); ok += 1
    except urllib.error.HTTPError as e:
        if e.code == 302: ok += 1
print(f"warmed={ok}/{len(codes)}")
PY

step "3/3 就位状态"
echo "   码文件：$CODES_FILE（$(wc -l < "$CODES_FILE" | tr -d ' ') 行）"
echo "   $(snap_routes)"
echo "下一步：bash bench/run-jump-bench.sh"
