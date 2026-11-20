# 面板部署与升级

## 首次部署

面板脚本需要支持 `config --format json` 和 `up --wait` 的 Docker Compose，以及 Python 3 和 OpenSSL。在计划存放项目配置的空目录运行 `panel_install.sh` 并选择安装。脚本以 `0600` 权限保存新生成的 JWT 密钥。默认使用 IPv4 回环监听，确认 Docker 网络支持 IPv6 后可用 `FLUX_PANEL_VARIANT=v6` 新装双栈版本。双栈模板默认监听 `127.0.0.1` 与 `::1`。

直接使用 Compose 时，用 `deploy/.env.example` 创建自己的 `.env`（权限 `0600`），用 `openssl rand -hex 32` 在可信终端生成 JWT 密钥后填入。启动前用 `docker compose config --quiet` 校验。完整 `docker compose config` 输出含密钥，应留在本机，避免复制至工单或日志。本地构建用 `deploy/rebuild.sh`，仓库镜像部署使用 `deploy/docker-compose.registry.yml`；仓库模板支持为前后端指定经核验的镜像 digest。

远程访问优先通过带 TLS 的反向代理。远程节点需直连时，将后端绑定到节点可达的可信接口，并限制防火墙来源。检查 `BACKEND_BIND_IP`、`FRONTEND_BIND_IP` 及对应端口。双栈模板使用 `BACKEND_BIND_IPV6` 和 `FRONTEND_BIND_IPV6`，值里无方括号。`PANEL_ALLOWED_ORIGINS` 仅在需要允许额外跨域客户端时配置，使用完整 Origin，逗号分隔。浏览器同源访问无需配置。只有客户端实测发送 `null` 时才显式放行相应 Origin。

## 旧部署迁移与回滚

升级前，应备份部署配置、镜像版本和可恢复的 SQLite 数据。使用 SQLite 一致性备份，或在停写后备份数据卷；避免只复制活跃数据库的主文件。升级时保持原 Compose 项目与项目目录，在现有 `docker-compose.yml` 和 `.env` 所在目录执行新版脚本，选择更新。脚本从容器标签核验归属并核对当前发布端口，保留原端口、绑定和数据卷。旧部署的全网监听会原样保留，需要收紧时请在确认节点连通条件后单独调整。若旧密钥不符合最低要求，升级成功时会提示重新登录。

标准拓扑包含 `springboot-backend` 和 `vite-frontend` 两个容器。使用多个 Compose 文件、外部注入令运行配置与文件不一致、特殊端口映射或已删除容器的部署，需要人工核对实际配置后迁移。保留现有数据库卷。卸载只删除经核验的面板容器；卷、镜像、配置保留。重新启用卸载后保留的部署时，使用原 Compose 项目名和原配置文件启动。

更新会预拉取镜像，前后端均通过健康检查后才算完成。健康检查失败时，脚本用旧镜像内容 ID 对应的本地 `flux-panel-rollback/` 标签及旧配置恢复容器。自动恢复失败时输出私有备份目录，内含旧文件与回滚镜像记录，应限制访问并避免分享其内容。自动恢复后如需在旧版本运行，保留回滚标签；原 Compose 文件仍指向原有镜像引用（该引用可能已被本次拉取更新），再次运行 Compose 前先核对镜像。容器回滚保留同一个 SQLite 卷，涉及应用数据库变更时还需按备份流程恢复数据。

节点脚本在停机前校验下载的 SHA-256，更新时保留现有节点配置和 GOST 数据。更换发布资产时同步维护脚本中的按架构固定摘要，或使用独立核验的 `FLUX_AGENT_SHA256`。节点启动门禁检查 systemd 主进程持续存活，面板连通性另行核对。

## SQLite 实例约束

每份 SQLite 数据文件只能有一个后端实例。模板声明 `scale: 1`，固定命名卷 `sqlite_data` 跨升级保留。禁止通过 `--scale`、跨 Compose 项目、宿主机路径别名或手动启动第二个后端来共享同一 SQLite 数据文件。面板脚本也检查同一 Docker daemon 上的卷挂载占用。维护、备份和恢复任务应避免创建另一个写入者。需要多实例时，先迁移到支持调度互斥和多实例访问的数据库方案。

## 本地校验

`bash deploy/test-installers.sh` 使用临时文件与隔离命令替身验证安装、迁移、错误和回滚。只有 `docker compose config` 通过真实 Compose CLI 运行。
