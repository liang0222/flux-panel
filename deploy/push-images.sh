#!/usr/bin/env bash
# 构建并推送镜像到 Docker Hub（需先 docker login）
# 用法：
#   ./push-images.sh                  # 默认 ahmadplayz721 / 2.0.7-beta / linux/amd64
#   VERSION=2.0.8-beta ./push-images.sh
#   PLATFORM=linux/amd64,linux/arm64 ./push-images.sh   # 多架构（需 buildx builder）
set -euo pipefail
cd "$(dirname "$0")/.."

DOCKERHUB_USER="${DOCKERHUB_USER:-ahmadplayz721}"
VERSION="${VERSION:-2.0.7-beta}"
PLATFORM="${PLATFORM:-}"

if [ -n "$PLATFORM" ]; then
  docker buildx build --platform "$PLATFORM" \
    -t "docker.io/${DOCKERHUB_USER}/flux-panel-backend:${VERSION}" --push ./springboot-backend
  docker buildx build --platform "$PLATFORM" \
    -t "docker.io/${DOCKERHUB_USER}/flux-panel-frontend:${VERSION}" --push ./vite-frontend
else
  docker build -t "docker.io/${DOCKERHUB_USER}/flux-panel-backend:${VERSION}" ./springboot-backend
  docker build -t "docker.io/${DOCKERHUB_USER}/flux-panel-frontend:${VERSION}" ./vite-frontend
  docker push "docker.io/${DOCKERHUB_USER}/flux-panel-backend:${VERSION}"
  docker push "docker.io/${DOCKERHUB_USER}/flux-panel-frontend:${VERSION}"
fi

echo "完成：docker.io/${DOCKERHUB_USER}/flux-panel-{backend,frontend}:${VERSION}"
