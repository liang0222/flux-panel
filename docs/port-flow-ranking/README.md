# docs：端口流量排行 + 仪表盘实时速率（flux-panel v2.0.7-beta）

> 这里是本次改动的全部工作材料。功能代码在仓库其余目录，文档与截图在本目录。

## 账号与产物（2026-10-24）

| 项 | 值 |
| --- | --- |
| GitHub | https://github.com/liang0222/flux-panel （分支 `feat/port-traffic-ranking`，最新 `b8e42ad`） |
| Docker Hub | `ahmadplayz721` |
| 后端镜像 | `docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta` |
| 前端镜像 | `docker.io/ahmadplayz721/flux-panel-frontend:2.0.7-beta` |
| 服务器部署 | `deploy/docker-compose.registry.yml`（拉镜像）或 `deploy/docker-compose.yml`（源码构建） |

## 文档索引

| 文件 | 内容 |
| --- | --- |
| [prompt.md](prompt.md) | 完整任务 Prompt：排行榜需求 + 实时速率追加需求（可直接粘给新会话） |
| [handoff.md](handoff.md) | 交接说明：现状、环境、已完成、待办、关键决策、自测、已知限制 |
| [lessons.md](lessons.md) | 踩坑与解法（44 条）+ 通用步骤与需替换项清单 |
| [deploy-and-publish.md](deploy-and-publish.md) | 部署与发布：本地源码构建 / 离线镜像 / 镜像仓库 / 裸机 |
| [github-dockerhub.md](github-dockerhub.md) | 用自己的 GitHub + Docker Hub 发布与部署（提交 → 建仓库 → 推镜像 → 服务器拉取 → CI） |
| [ranking-today.png](ranking-today.png) | 流量排行页实拍（暗色，6 个模拟端口） |
| [dashboard-realtime-card.png](dashboard-realtime-card.png) | 仪表盘「实时速率」卡片实拍 |
| [dashboard-realtime-dark.png](dashboard-realtime-dark.png) | 仪表盘整页实拍（暗色） |

> 截图均为真实 Docker 栈 + Playwright 渲染，**流量数据是脚本模拟的**，不是真实用户数据。

## 当前状态（2026-10-24）

- 功能：端口转发流量排行（5 分钟采集 / 区间聚合 / 管理员页） + 仪表盘实时速率（10 秒内存窗口 / 2 秒轮询 / 按角色隔离）。
- 验证：后端 47 个测试全绿；前端 `npm run build` 通过；真实 Docker 部署、接口、重启、删转发、离线镜像 `save/load` 均验证过；页面已截图。
- 代码：已提交 5 个 commit 并推送到 GitHub（`liang0222/flux-panel`，最新 `b8e42ad`），工作区干净。
- 镜像：已推送到 Docker Hub（`ahmadplayz721`，tag `2.0.7-beta`）。
- 部署：`deploy/` 下有拉镜像（registry）、源码构建、离线 tar 三种方式。

## 下一步

1. 在服务器上部署（[github-dockerhub.md](github-dockerhub.md) 第 10 步起，或直接用 `deploy/docker-compose.registry.yml`）。
2. 登录面板、改默认密码、设置「面板后端地址」为 `公网IP:6365`。
3. 可选：浅色/窄屏视觉走查、H5 入口、实时速率趋势图、GitHub Actions 自动构建。
