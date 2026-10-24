#!/usr/bin/env bash
# 在任意有 Docker 的机器上构建镜像并导出为 tar，服务器侧 docker load 即可，无需 GitHub / 镜像仓库
set -euo pipefail
cd "$(dirname "$0")"
VERSION="2.0.7-beta"

docker compose build
docker save "local/flux-panel-backend:${VERSION}" "local/flux-panel-frontend:${VERSION}" \
  -o "flux-panel-images-${VERSION}.tar"

SIZE=$(du -h "flux-panel-images-${VERSION}.tar" | cut -f1)
echo "已生成 deploy/flux-panel-images-${VERSION}.tar (${SIZE})"
echo "服务器上执行："
echo "  docker load -i flux-panel-images-${VERSION}.tar"
echo "  docker compose up -d"
