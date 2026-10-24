#!/usr/bin/env bash
# 本地一键重建并重启面板（不推镜像仓库、不依赖 GitHub）
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  cp .env.example .env
  echo "已生成 deploy/.env，请先修改 JWT_SECRET 后重新执行"
  exit 1
fi

docker compose build "$@"
docker compose up -d
docker compose ps
echo
echo "前端: http://<服务器IP>:$(grep -E '^FRONTEND_PORT=' .env | cut -d= -f2 || echo 6366)"
echo "后端健康检查: http://<服务器IP>:$(grep -E '^BACKEND_PORT=' .env | cut -d= -f2 || echo 6365)/flow/test"
