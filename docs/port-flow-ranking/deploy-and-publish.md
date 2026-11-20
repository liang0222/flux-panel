# 部署与发布：把自己的改动跑起来（默认不碰 GitHub / Docker Hub）

> 面向本次改动（`feat/port-traffic-ranking`，端口流量排行榜）。命令以 Linux 为例，`<...>` 替换成自己的值。
>
> **本页默认路径：源码或镜像直接上服务器，一次搞定，不依赖 GitHub Actions，也不推任何镜像仓库。**

## 0. 整改分支升级检查

1. 先确认镜像或二进制确实由待部署源码构建。历史 `2.0.7-beta` 同名镜像和旧节点二进制不会自动获得本轮修复。
2. 用 SQLite `.backup` 生成一致性备份，核对 `PRAGMA integrity_check`，保留备份和原 Compose 项目、数据卷及上一个可用镜像。后端继续单实例运行，切勿将同一个数据库卷同时挂载给多个写入后端。
3. 先升级面板并验证健康，再按批次升级 Go agent。旧 agent 仍走兼容上报路径，新 agent 才会携带批次标识。升级后核对历史排行、仪表盘实时速率、正常上报与节点配置。新增表和索引由 `schema.sql` 初始化。
4. 已使用强随机 `JWT_SECRET` 的部署保留原值，避免现有令牌失效。新安装用 `openssl rand -hex 32` 生成。替换旧弱密钥时，安排用户重新登录。先在本机或可信管理网络完成首次设置和管理员改密，再开通外部访问。
5. 本地 Compose 默认绑定回环。按需要显式设置 `FRONTEND_BIND_IP`、`BACKEND_BIND_IP` 或使用 HTTPS 代理。跨域页面与代理终止 TLS 场景按浏览器实际使用的来源配置 `PANEL_ALLOWED_ORIGINS`，如 `https://panel.example.com`。用逗号分隔，使用非默认端口时带上端口。Android 内置来源已支持。仅在验证对应 iOS 壳确需 `Origin: null` 后按部署选择是否配置 `null`。所有实际代理层的访问和错误日志都要单独核对。
6. 将旧数据恢复到隔离副本后先做验证。需要恢复到原服务时，先停写入方，保留当前数据库及相关文件的副本，再使用已检查的一致性备份，避免让新旧 WAL 文件混用。

本轮本地验证与现场待验项见 [审计整改验收](audit-remediation.md)。脚本安装、升级及回滚的操作约束见 [面板部署与升级](../../deploy/README.md)。

## 1. 方式 A1：把源码放到服务器上构建（最省事）

适合：服务器能联网（拉 npm/maven 依赖、基础镜像）。

```bash
# 1) 源码上传（只传一次；node_modules/target/dist 已在 .dockerignore 里排除）
rsync -av --exclude node_modules --exclude target --exclude dist \
  ./ user@server:/opt/flux-panel/

# 2) 服务器上
cd /opt/flux-panel/deploy
# 仅首次安装，保留已有部署的 .env 和随机密钥。
if [ ! -e .env ]; then
  (umask 077; sed "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/" .env.example > .env)
fi

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

新装时面板默认监听服务器的 `127.0.0.1:6366`。在服务器本机、经 SSH 转发，或经可信 HTTPS 反代进入面板。完成首次管理账户设置与改密后再开放远程访问。

## 2. 方式 A2：本地构建 → 导出镜像 → 服务器加载（服务器可断网、无源码）

适合：服务器不装源码、不联网，或你只想传一个文件过去。

```bash
# 本地（需要 Docker）
cd deploy
./export-images.sh
# 产出 flux-panel-images-2.0.7-beta.tar（约 201 MB）

# 首次部署时传镜像和模板，升级前先核对目标 Compose 项目及现有配置。
scp flux-panel-images-2.0.7-beta.tar docker-compose.yml .env.example user@server:/opt/flux-panel/
# 服务器上
cd /opt/flux-panel
if [ ! -e .env ]; then
  (umask 077; sed "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/" .env.example > .env)
fi
docker load -i flux-panel-images-2.0.7-beta.tar
docker compose up -d --no-build --pull never
```

升级时保留服务器的 `.env`、Compose 项目名和现有数据卷。先按第 0 节做一致性备份，再加载新镜像并验证。

## 3. 方式 B：镜像仓库 / GitHub Actions（可选）

> 完整的 GitHub + Docker Hub 分步教程（账号、提交、建仓库、推镜像、服务器 compose、CI 必改项）见 [github-dockerhub.md](github-dockerhub.md)。下面只是速查。

只有想多机分发或公开镜像时才需要。构建并推送：

```bash
docker buildx build --platform linux/amd64 -t docker.io/<USER>/flux-panel-backend:2.0.7-beta --push ./springboot-backend
docker buildx build --platform linux/amd64 -t docker.io/<USER>/flux-panel-frontend:2.0.7-beta --push ./vite-frontend
```

以下是 2026-10-24 推送的历史镜像。整改版本的安装入口以仓库首页为准：

- `docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta`
- `docker.io/ahmadplayz721/flux-panel-frontend:2.0.7-beta`

服务器侧直接用 [deploy/docker-compose.registry.yml](../../deploy/docker-compose.registry.yml)（无 `build:`，只拉镜像）：

```bash
cd deploy
if [ ! -e .env ]; then
  (umask 077; sed "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/" .env.example > .env)
fi
docker compose -f docker-compose.registry.yml pull
docker compose -f docker-compose.registry.yml up -d
```

要自己重新推送：`cd deploy && ./push-images.sh`（默认用户 `ahmadplayz721`、版本 `2.0.7-beta`、amd64，可用 `VERSION=` / `PLATFORM=` 覆盖）。CI 的注意事项见第 6.4 节。

## 4. 方式 C：不用 Docker（jar + nginx）

```bash
cd springboot-backend && mvn -B clean verify
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

### 6.1 历史提交示例（仅供旧排行分支参考）

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
# 分支推送用于验证，发布由独立版本标签触发。
git push -u origin feat/port-traffic-ranking
```

认证：SSH 公钥，或 HTTPS + PAT（勾 `repo`）。

### 6.3 版本发布与 README 切换

1. 在发布候选提交上确认 README、面板安装器、Compose 默认镜像和面板内生成的节点安装命令使用同一版本。使用 [deploy/check-release.py](../../deploy/check-release.py) 核对构建产物、安装器和模板的 SHA-256。
2. 在 `liang0222/flux-panel` 推送指向该提交的新版本标签，例如 `2.0.7-beta-r1`。在 GitHub Actions 查看对应标签运行，并确认前后端镜像均有 `linux/amd64` 和 `linux/arm64`，GitHub prerelease 的 `gost-amd64`、`gost-arm64` 和 `SHA256SUMS` 均已公开且校验匹配。
3. 两类产物都验证成功后，再将含新版安装命令的提交更新到默认分支。保留历史标签供已有部署使用。若临时发布失败，先核对已产生的镜像和草稿 Release，再重跑同一标签上的失败任务。

### 6.4 当前 CI 与发布条件

[.github/workflows/docker-build.yml](../../.github/workflows/docker-build.yml) 对各分支的代码变更推送、PR、标签和手动运行执行 Maven `verify`、双 Go 模块竞态测试和安装器测试。Linux amd64/arm64 Agent 与 `SHA256SUMS` 上传为 Actions artifacts。原仓库的 `2.0.7-beta-rN` 版本标签通过校验后，工作流使用 `DOCKER_HUB_USERNAME` / `DOCKER_HUB_TOKEN` 向 `ahmadplayz721/flux-panel-*` 推送同名版本镜像，其后校验 GitHub Release 资产并公开预发布。普通分支与 PR 只做验证。分叉仓库应独立配置镜像命名空间和发布凭据。

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

发布面板和 Agent 前，核对以下位置引用的是本次通过验收的产物：

| 位置 | 当前行为 |
| --- | --- |
| [面板安装器](../../panel_install.sh) 与 [IPv4 模板](../../docker-compose-v4.yml)、[双栈模板](../../docker-compose-v6.yml) | 默认选用 `ahmadplayz721/flux-panel-*:2.0.7-beta-r1`，也支持显式指定前后端镜像 |
| [Agent 安装器](../../install.sh) | 选用 `2.0.7-beta-r1` 对应架构的发布资产并核对固定 SHA-256 |
| [节点安装命令](../../springboot-backend/src/main/java/com/admin/service/impl/NodeServiceImpl.java#L124) | 面板生成指向 `2.0.7-beta-r1` 的安装命令，与 README 一致 |

只升级面板时，旧 Agent 仍可兼容上报。批次去重、退出补发及 Agent 生命周期修复需要更新节点二进制。先升级并验证面板，再分批升级节点。
