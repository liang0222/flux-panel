#!/usr/bin/env bash
# 本地一键重建并重启面板（不推镜像仓库、不依赖 GitHub）
set -euo pipefail
cd "$(dirname "$0")"

if [[ -L .env || ( -e .env && ! -f .env ) ]]; then
  echo '部署环境文件必须是普通文件。' >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  (umask 077; cp -- .env.example .env)
  echo "已生成 deploy/.env，请先修改 JWT_SECRET 后重新执行"
  exit 1
fi

chmod 600 .env
# config --quiet 不回显插值后的 JWT_SECRET。
docker compose config --quiet
docker compose build "$@"
docker compose up -d --no-build --wait --wait-timeout 270
docker compose ps
echo
echo '默认监听本机回环地址。实际绑定以 .env 与 Compose 配置为准。'
