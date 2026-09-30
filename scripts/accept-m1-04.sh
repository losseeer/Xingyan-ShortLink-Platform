#!/usr/bin/env bash
# M1-04 验收脚本（DEVELOPMENT_PLAN 验收命令列）：
# 正确签名→200；改一字节签名→401；重放旧时间戳/nonce→401；同 Idempotency-Key 二次 POST→同一响应。
set -uo pipefail
GATEWAY="${GATEWAY:-http://localhost:8110}"  # M1-10 起 gateway 容器化；宿主 8010 被本机其他项目占用，映射到 8110
API_KEY="xy-key-alice-001"

sign() { # sign <secret> <method> <path> <ts> <nonce> <body>
  python3 - "$@" <<'PY'
import sys, hmac, hashlib
secret, method, path, ts, nonce, body = sys.argv[1:7]
sha = hashlib.sha256(body.encode()).hexdigest()
canonical = "\n".join([method, path, ts, nonce, sha])
print(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest())
PY
}

req() { # req <method> <path> <ts> <nonce> <body> <signature> [Idempotency-Key] [api-key]
  local method=$1 path=$2 ts=$3 nonce=$4 body=$5 sig=$6 idem=${7:-} key=${8:-$API_KEY}
  local args=(-s -o /tmp/m104-body -w '%{http_code}' -X "$method" "$GATEWAY$path"
    -H "X-Api-Key: $key" -H "X-Timestamp: $ts" -H "X-Nonce: $nonce" -H "X-Signature: $sig")
  [[ -n "$idem" ]] && args+=(-H "Idempotency-Key: $idem")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}

pass=0 fail=0
check() { # check <name> <expect> <actual>
  if [[ "$2" == "$3" ]]; then echo "PASS $1 ($3)"; ((pass++)); else echo "FAIL $1 expect=$2 actual=$3"; ((fail++)); fi
}

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }
RUN=$(now_ms)
NOW=$RUN
OLD=$(python3 -c "print($NOW - 360000)")

# 1. 正确签名 → 200
SIG=$(sign "$API_KEY" GET /api/v1/ping "$NOW" "n-$RUN-a" "")
check "valid-signature" 200 "$(req GET /api/v1/ping "$NOW" "n-$RUN-a" "" "$SIG")"

# 2. 改一字节签名 → 401
TAMPERED=$(python3 -c "s='$SIG';print(('b' if s[0]!='b' else 'c')+s[1:])")
check "tampered-signature" 401 "$(req GET /api/v1/ping "$NOW" "n-$RUN-b" "" "$TAMPERED")"

# 3. 旧时间戳（-6min，签名本身有效）→ 401
SIG_OLD=$(sign "$API_KEY" GET /api/v1/ping "$OLD" "n-$RUN-c" "")
check "stale-timestamp" 401 "$(req GET /api/v1/ping "$OLD" "n-$RUN-c" "" "$SIG_OLD")"

# 4. 重放已用 nonce（签名有效、时间戳在窗口内）→ 401
check "replayed-nonce" 401 "$(req GET /api/v1/ping "$NOW" "n-$RUN-a" "" "$SIG")"

# 5. 合法签名但未知 api_key（字典中不存在）→ 401
UK="xy-key-unknown-999"
SIG_UNKNOWN=$(sign "$UK" GET /api/v1/ping "$NOW" "n-$RUN-d" "")
check "unknown-api-key" 401 "$(req GET /api/v1/ping "$NOW" "n-$RUN-d" "" "$SIG_UNKNOWN" "" "$UK")"
# 5b. Idempotency-Key 重放 → 两次响应完全一致
TS2=$(python3 -c "import time;print(int(time.time()*1000))")
B1=$(sign "$API_KEY" POST /api/v1/ping "$TS2" "n-$RUN-e" '{}')
C1=$(req POST /api/v1/ping "$TS2" "n-$RUN-e" '{}' "$B1" "idem-$RUN-1"); R1=$(cat /tmp/m104-body)
TS3=$(python3 -c "import time;print(int(time.time()*1000))")
B2=$(sign "$API_KEY" POST /api/v1/ping "$TS3" "n-$RUN-f" '{}')
C2=$(req POST /api/v1/ping "$TS3" "n-$RUN-f" '{}' "$B2" "idem-$RUN-1"); R2=$(cat /tmp/m104-body)
check "idempotent-status" "200,200" "$C1,$C2"
if [[ "$R1" == "$R2" ]]; then echo "PASS idempotent-replay (same requestId)"; ((pass++)); else echo "FAIL idempotent-replay: $R1 vs $R2"; ((fail++)); fi

echo "== $pass passed, $fail failed =="
[[ $fail -eq 0 ]]
