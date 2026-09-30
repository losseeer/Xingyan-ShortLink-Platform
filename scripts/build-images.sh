#!/usr/bin/env bash
# M1-10 应用镜像构建：宿主 mvn 产物 → JRE 镜像（编译刻意不进镜像层，见 deploy/docker/Dockerfile.app）。
# 用法：
#   scripts/build-images.sh            # 用现有 target/ 产物构建 4 个镜像
#   scripts/build-images.sh --mvn      # 先 mvn -DskipTests package 再构建
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${VERSION:-0.1.0}"
TAG="${TAG:-dev}"
MODULES=(sl-gateway sl-admin sl-jump sl-consumer)

if [[ "${1:-}" == "--mvn" ]]; then
  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/javac" ]]; then
    # 宿主默认 JAVA_HOME 常是 JDK 8（无 --release 17 支持），必须显式指向 17+
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  fi
  echo "==> mvn package ($JAVA_HOME)"
  mvn -q -DskipTests package
fi

# 每个镜像单独一个极小 context（只放 Dockerfile + app.jar）：
# 直接用仓库根当 context 会把 target/ 下四个 fat jar 全部上传给 daemon，白等几十秒。
CTX="$(mktemp -d)"
trap 'rm -rf "$CTX"' EXIT

for m in "${MODULES[@]}"; do
  jar="$m/target/$m-$VERSION.jar"
  [[ -f "$jar" ]] || { echo "缺少 $jar —— 先跑 scripts/build-images.sh --mvn" >&2; exit 1; }
  dir="$CTX/$m"
  mkdir -p "$dir"
  cp deploy/docker/Dockerfile.app "$dir/Dockerfile"
  cp "$jar" "$dir/app.jar"
  image="xsl/${m#sl-}:$TAG"
  # BASE_IMAGE 可覆盖：内网/离线时换成已本地存在的 JRE 镜像（见 ITER-M1 环境记录）
  docker build -q --build-arg MODULE="$m" \
    ${BASE_IMAGE:+--build-arg BASE_IMAGE=$BASE_IMAGE} \
    -t "$image" "$dir" >/dev/null
  echo "built $image  (jar $(du -h "$jar" | cut -f1))"
done
