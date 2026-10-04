#!/usr/bin/env bash
# M2-34：为本地 mock 购票页生成自签证书（DESIGN 6.2「mock 购票页」那条）。
#
# 为什么需要证书：短链 302 的终点是 https://mock.ticketsales.test/…（9.2 强制 https，
# 不为了演示省事把它降成 http），要真能在浏览器里点开，nginx 就得在 443 上应答这个域。
# 自签 = 浏览器会警告、手动继续即可；这里不宣称"证书受信"，也不接真实 CA/域名。
#
# 幂等：已存在就跳过（换发请删掉这两个文件再跑）。证书/私钥只落本机 deploy/compose/tls/，
# 那个目录在 .gitignore 里，不入库、不推送。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TLS_DIR="$ROOT/deploy/compose/tls"
CRT="$TLS_DIR/mock.crt"
KEY="$TLS_DIR/mock.key"
HOSTS=(mock.ticketsales.test ticketsales.test xy1.test xy2.test xy3.test)

if [[ -f "$CRT" && -f "$KEY" ]]; then
  echo "证书已存在，跳过：$CRT"
  exit 0
fi

mkdir -p "$TLS_DIR"
chmod 700 "$TLS_DIR"

# 用显式配置文件而不是 `-addext`：macOS 自带的是 LibreSSL 的 openssl，对 -addext 的支持不一致，
# 而 [req] + x509_extensions 这套写法在 OpenSSL 1.x/LibreSSL 上都能跑。
CNF="$TLS_DIR/mock.cnf"
{
  printf '[req]\ndistinguished_name=dn\nx509_extensions=v3\nprompt=no\n[dn]\n[pubkey]\n'
  printf '[v3]\nbasicConstraints=CA:TRUE\nsubjectAltName='
  first=1
  for h in "${HOSTS[@]}"; do
    [[ $first -eq 1 ]] && { printf 'DNS:%s' "$h"; first=0; } || printf ',DNS:%s' "$h"
  done
  printf '\n'
} > "$CNF"

umask 077
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$KEY" -out "$CRT" \
  -days 825 -config "$CNF" -subj "/CN=mock.ticketsales.test" >/dev/null 2>&1 || {
    echo "openssl 生成失败（检查本机 openssl 是否在 PATH）" >&2; exit 1; }
chmod 600 "$KEY" "$CRT"

echo "已生成本地自签证书：$CRT（SAN: ${HOSTS[*]}，825 天）"
echo "浏览器里打开 https://mock.ticketsales.test/… 还需要一条 hosts 记录：127.0.0.1  mock.ticketsales.test"
