# 部署与发布：把自己的改动跑起来（默认不碰 GitHub / Docker Hub）

> 面向本次改动（`feat/port-traffic-ranking`，端口流量排行榜）。命令以 Linux 为例，`<...>` 替换成自己的值。
>
> **本页默认路径：源码或镜像直接上服务器，一次搞定，不依赖 GitHub Actions，也不推任何镜像仓库。**

## 0. 先明确三件事

1. **改动只在面板**（后端 + 前端），节点端 gost 二进制未变 → 节点继续用官方 `2.0.7-beta` 安装脚本。
2. **官方预构建镜像 `bqlpfy/*:2.0.7-beta` 不包含你的改动** → 必须用你自己的构建产物（当前产物含「端口流量排行」与「仪表盘实时速率」两期功能）。
3. **不需要数据库迁移**：新表随 `schema.sql` 启动时自动创建；数据在 `sqlite_data` 卷里，升级不丢。

## 1. 方式 A1：把源码放到服务器上构建（最省事）

适合：服务器能联网（拉 npm/maven 依赖、基础镜像）。

```bash
# 1) 源码上传（只传一次；node_modules/target/dist 已在 .dockerignore 里排除）
rsync -av --exclude node_modules --exclude target --exclude dist \
  ./ user@server:/opt/flux-panel/

# 2) 服务器上
cd /opt/flux-panel/deploy
cp .env.example .env
sed -i "s/change-me-to-a-random-string/$(openssl rand -hex 16)/" .env

# 3) 一键构建并启动（首次约 5~10 分钟）
docker compose up -d --build
docker compose ps
```

仓库里已经准备好：

| 文件 | 作用 |
| --- | --- |
| [deploy/docker-compose.yml](../../deploy/docker-compose.yml) | 用 `build:` 从源码构建，镜像名 `local/*`，不需要任何仓库 |
| [deploy/.env.example](../../deploy/.env.example) | JWT_SECRET 与前后端端口 |
| [deploy/rebuild.sh](../../deploy/rebuild.sh) | 以后更新只需 `./rebuild.sh`（build + up + 状态） |
| `springboot-backend/.dockerignore`、`vite-frontend/.dockerignore` | 避免把 `target/`、`node_modules/`、`dist/` 打进构建上下文 |

访问 `http://<服务器IP>:6366`，默认账号 `admin_user / admin_user`（登录后立即改）。

## 2. 方式 A2：本地构建 → 导出镜像 → 服务器加载（服务器可断网、无源码）

适合：服务器不装源码、不联网，或你只想传一个文件过去。

```bash
# 本地（需要 Docker）
cd deploy
./export-images.sh
# 产出 flux-panel-images-2.0.7-beta.tar（约 201 MB）

# 传到服务器
scp flux-panel-images-2.0.7-beta.tar docker-compose.yml .env user@server:/opt/flux-panel/
# 服务器上
cd /opt/flux-panel
docker load -i flux-panel-images-2.0.7-beta.tar
docker compose up -d          # 注意：不要加 --build，直接用已加载的镜像
```

> `.env` 里必须有 `JWT_SECRET`（`openssl rand -hex 16`）。以后更新：本地重新 `./export-images.sh` → 传 tar → `docker load` → `docker compose up -d`，数据卷不变。

## 3. 方式 B：镜像仓库 / GitHub Actions（可选）

> 完整的 GitHub + Docker Hub 分步教程（账号、提交、建仓库、推镜像、服务器 compose、CI 必改项）见 [github-dockerhub.md](github-dockerhub.md)。下面只是速查。

只有想多机分发或公开镜像时才需要。构建并推送：

```bash
docker buildx build --platform linux/amd64 -t docker.io/<USER>/flux-panel-backend:2.0.7-beta --push ./springboot-backend
docker buildx build --platform linux/amd64 -t docker.io/<USER>/flux-panel-frontend:2.0.7-beta --push ./vite-frontend
```

本项目已经构建并推送过一份可直接用的镜像（2026-10-24）：

- `docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta`
- `docker.io/ahmadplayz721/flux-panel-frontend:2.0.7-beta`

服务器侧直接用 [deploy/docker-compose.registry.yml](../../deploy/docker-compose.registry.yml)（无 `build:`，只拉镜像）：

```bash
cd deploy && cp .env.example .env    # 填 JWT_SECRET
docker compose -f docker-compose.registry.yml pull
docker compose -f docker-compose.registry.yml up -d
```

要自己重新推送：`cd deploy && ./push-images.sh`（默认用户 `ahmadplayz721`、版本 `2.0.7-beta`、amd64，可用 `VERSION=` / `PLATFORM=` 覆盖）。CI 的注意事项见第 6.6 节。

## 4. 方式 C：不用 Docker（jar + nginx）

```bash
cd springboot-backend && mvn -B clean package -DskipTests
mkdir -p /opt/flux-panel/{data,logs}
cp target/admin-0.0.1-SNAPSHOT.jar /opt/flux-panel/app.jar

cd ../vite-frontend && (npm ci || npm install --legacy-peer-deps) && npm run build
mkdir -p /var/www/flux-panel && cp -r dist/* /var/www/flux-panel/
```

`/etc/systemd/system/flux-panel.service`：

```ini
[Unit]
Description=flux-panel backend
After=network.target

[Service]
Environment=DB_PATH=/opt/flux-panel/data/gost.db
Environment=JWT_SECRET=<随机串>
Environment=LOG_DIR=/opt/flux-panel/logs
ExecStart=/usr/bin/java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -Xms256m -Xmx512m -jar /opt/flux-panel/app.jar
Restart=always
User=root

[Install]
WantedBy=multi-user.target
```

nginx 用仓库自带 [nginx.conf](../../vite-frontend/nginx.conf)，`root` 指到 `/var/www/flux-panel`，所有 `proxy_pass http://backend:6365...` 改成 `http://127.0.0.1:6365...`。前端、`/api/v1/`、`/flow/upload`、`/flow/config`、`/system-info`（WebSocket）缺一不可。

## 5. 部署后自检

1. 等一个采集周期（≤5 分钟）：
   ```bash
   docker logs springboot-backend | grep -E "端口流量采集完成|SQLite WAL"
   ```
   应出现 `端口流量采集完成：转发 N 条，写入 M 条…`。
2. 表自动建好：
   ```bash
   docker exec springboot-backend sqlite3 /app/data/gost.db ".tables"
   docker exec springboot-backend sqlite3 /app/data/gost.db \
     "select count(*), max(created_time) from forward_flow_statistics;"
   ```
3. 管理员登录 → 左侧「流量排行」→ 切换「今天 / 近 3 天 / 近 7 天 / 近 30 天」。
4. 首次为空正常：首次采集只建基线，端口有流量后才上榜；基线行不进入排行。
5. 时区：`docker exec springboot-backend date` 应为 `CST +0800`。
6. 实时速率：管理员登录仪表盘，卡片应显示速率；无流量时显示 `0 bit/s`，节点恢复上报后 5 秒内出现数值。接口自检：
   ```bash
   curl -s -X POST http://127.0.0.1:6365/api/v1/flow-rate/current -H "Authorization: <你的token>"
   ```
   返回 `totalRate/inRate/outRate`（字节/秒）、`inPercent/outPercent`、`activeForwardCount`、`topForward`。

### 本次已在真实 Docker 环境验证（2026-10-20 ~ 10-21）

| 步骤 | 结果 |
| --- | --- |
| `docker compose up -d --build`（源码构建） | 后端 `healthy`，前端启动，耗时约 5 分钟 |
| 前端 nginx 代理 `/api/v1/forward-flow/ranking` | 200；无 token 401；非管理员 403 |
| 容器内建表 | `forward_flow_statistics` 存在 |
| 16:35 首次采集 | 基线行，`created_time` 正确 |
| 16:40 计数器 1000/500→1600/700 | 增量 600/200=800 |
| 恢复接口 | `totalFlow=800`，端口 23333，采样点 2 |
| `docker compose restart backend` | 数据仍在，接口仍返回 800 |
| 删除 forward 后 | 历史保留，`deleted=true`，流量仍为 800 |
| `docker save` → `docker rmi` → `docker load` → `up -d` | 两个镜像从 201MB tar 恢复，容器 healthy，数据/接口正常 |
| SPA 路由 `/flow-ranking` | 200，返回应用外壳 |
| 实时速率接口 `/api/v1/flow-rate/current` | 模拟上报后返回 62400 B/s；管理员 `scope=all`、普通用户 `scope=self`、其他用户 0 |
| 仪表盘实时速率卡片 | Playwright 真实渲染截图，2 秒刷新、入/出站占比与 Top 正常 |
| 流量排行页面 | Playwright 真实渲染截图，6 个模拟端口、降序、已暂停/已删除标记正常 |

## 6. 上传到自己的 GitHub（可选，仅用于备份/分发/协作）

### 6.1 先提交（当前改动还未提交）

```bash
cd /root/v2.0.7-beta
git add springboot-backend/src springboot-backend/pom.xml springboot-backend/.dockerignore \
        vite-frontend/src vite-frontend/.dockerignore deploy .gitignore
git commit -m "feat(flow): 端口转发流量排行榜 + 本地化部署脚本"

git add springboot-backend/src/test
git commit -m "test(flow): 采集、聚合、排序与异常计数器测试"

git add docs/port-flow-ranking
git commit -m "docs: 排行榜任务说明、部署与交接"
```

`target/`、`dist/`、`node_modules/`、`deploy/*.tar` 都已被忽略。

### 6.2 建空仓库并换 remote

网页新建空仓库（不要勾 README/gitignore/license）：

```bash
git remote rename origin upstream
git remote add origin git@github.com:<YOU>/flux-panel.git
git push -u origin feat/port-traffic-ranking   # 先只推功能分支，避免触发 CI
```

认证：SSH 公钥，或 HTTPS + PAT（勾 `repo`）。

### 6.3 想推 main / tag

```bash
git push origin 2.0.7-beta:refs/tags/2.0.7-beta
git push --all origin && git push --tags origin
```

### 6.4 CI 必改项（否则会失败或推到原作者命名空间）

[.github/workflows/docker-build.yml](../../.github/workflows/docker-build.yml) 里硬编码了：

- 触发：`on.push.branches: [main, beta]`
- 版本：`env.VERSION: "2.0.7-beta"`
- 镜像：`bqlpfy/springboot-backend`、`bqlpfy/vite-frontend`
- 登录：`secrets.DOCKER_HUB_USERNAME` / `DOCKER_HUB_TOKEN`
- `create-release` 会 `git tag 2.0.7-beta && git push` 并创建 Release

若不走镜像仓库：把该工作流改名停用（`docker-build.yml.disabled`），或先不推 main/beta。
若要走：改镜像名为自己的命名空间、配 secrets、删掉或改版本号避免 tag 冲突。

### 6.5 与上游同步

```bash
git fetch upstream && git checkout main && git merge upstream/main
```

## 7. 常见问题

| 现象 | 原因/解决 |
| --- | --- |
| 6366 打不开 | 安全组/防火墙；`ss -lntp` 看占用 |
| 登录后没有「流量排行」 | 非管理员（`role_id != 0`），接口也返回 403 |
| 页面一直空 | 未到有流量的采集点；看第 5 节日志与表 |
| 时间少 8 小时 | JVM 缺 `-Duser.timezone=Asia/Shanghai` |
| 升级后数据没了 | 换了卷名或执行了 `down -v` |
| `docker compose build` 很慢/内存不足 | 逐服务构建：`docker compose build backend` 再 `frontend`；服务器 ≥2GB 内存 |
| 首次构建把 447MB node_modules 传进上下文 | 已在 `.dockerignore` 排除；用干净 clone 更稳 |
| 后端 unhealthy | 缺 `JWT_SECRET`、数据库权限，看 `docker logs` |

## 8. 如果要把面板发给别人用

官方脚本与后端里有多处 `bqlpfy` 硬编码，自建分发时需要改：

| 位置 | 内容 |
| --- | --- |
| `panel_install.sh` | 下载 `bqlpfy/flux-panel` 的 compose；镜像名在 compose 里 |
| `install.sh` | 节点端从 `bqlpfy/flux-panel/releases/download/2.0.7-beta/gost-*` 下载 |
| `springboot-backend/.../NodeServiceImpl.java:129` | 面板展示的节点安装命令硬编码上游 release 地址 |
| `docker-compose-v4/v6.yml` | `image: bqlpfy/*` |

只给自己用可以不改：面板用你自己的构建产物，节点继续用官方二进制（本功能不依赖节点改动）。
