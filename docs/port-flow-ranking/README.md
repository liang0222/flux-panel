# docs：端口流量排行 + 仪表盘实时速率（flux-panel v2.0.7-beta）

> 这里是本次改动的全部工作材料。功能代码在仓库其余目录，文档与截图在本目录。

## 账号与产物（2026-10-24）

| 项 | 值 |
| --- | --- |
| GitHub | https://github.com/liang0222/flux-panel （分支 `feat/port-traffic-ranking`，以分支头为准） |
| Docker Hub | `ahmadplayz721` |
| 后端镜像 | `docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta` |
| 前端镜像 | `docker.io/ahmadplayz721/flux-panel-frontend:2.0.7-beta` |
| 一键安装 | `curl -L https://raw.githubusercontent.com/liang0222/flux-panel/refs/heads/feat/port-traffic-ranking/panel_install.sh -o panel_install.sh && chmod +x panel_install.sh && ./panel_install.sh` |
| 服务器部署 | `deploy/docker-compose.registry.yml`（拉镜像）或 `deploy/docker-compose.yml`（源码构建） |
| CI | GitHub Actions 已配置并跑通：推送分支自动构建 amd64 + arm64，产出 `latest` 与 `2.0.7-beta` |

## 文档索引

| 文件 | 内容 |
| --- | --- |
| [prompt.md](prompt.md) | 完整任务 Prompt：排行榜需求 + 实时速率追加需求（可直接粘给新会话） |
| [handoff.md](handoff.md) | 交接说明：现状、环境、已完成、待办、关键决策、自测、已知限制 |
| [lessons.md](lessons.md) | 踩坑与解法（53 条）+ 通用步骤与需替换项清单 |
| [code-review.md](code-review.md) | 全面代码审查报告（HEAD 9ce14d6）：结论、2 项中等风险、6 项轻微、4 项上游继承风险 |
| [deploy-and-publish.md](deploy-and-publish.md) | 部署与发布：本地源码构建 / 离线镜像 / 镜像仓库 / 裸机 |
| [github-dockerhub.md](github-dockerhub.md) | 用自己的 GitHub + Docker Hub 发布与部署（提交 → 建仓库 → 推镜像 → 服务器拉取 → CI） |
| [ranking-today.png](ranking-today.png) | 流量排行页实拍（暗色，6 个模拟端口） |
| [dashboard-realtime-card.png](dashboard-realtime-card.png) | 仪表盘「实时速率」卡片实拍 |
| [dashboard-top10.png](dashboard-top10.png) | 仪表盘「实时速率」前十个端口速率排行实拍（12 个模拟端口） |
| [dashboard-realtime-dark.png](dashboard-realtime-dark.png) | 仪表盘整页实拍（暗色） |

> 截图均为真实 Docker 栈 + Playwright 渲染，**流量数据是脚本模拟的**，不是真实用户数据。

## 当前状态（2026-10-26 01:30）

- 功能：端口转发流量排行（5 分钟采集 / 区间聚合 / 管理员页） + 仪表盘实时速率（10 秒内存窗口 / 2 秒轮询 / 按角色隔离，管理员可见含自己在内的全部用户；卡片内含**前十个端口速率排行**） + 面板 IPv6 双栈访问。
- 验证：后端 66 个测试全绿；前端 `npm run build` 通过；真实 Docker 部署、接口、重启、删转发、离线镜像 `save/load` 均验证过；页面已截图。
- 代码：相对 tag `2.0.7-beta` 共 18+ 个 commit 已推送到 GitHub（`liang0222/flux-panel`，`feat/port-traffic-ranking`），工作区干净。
- 审查：2026-10-25 完成全面代码审查，见 [code-review.md](code-review.md)；无阻断缺陷。CI 标签陷阱（M1）已修复并线上验证；仅剩 M2（安装脚本写死分支）待合并到 `main` 时处理。
- 镜像：已推送到 Docker Hub（`ahmadplayz721`，tag `2.0.7-beta` + `latest`，amd64/arm64 双架构），并拉取核对过镜像内确含新代码（见 code-review.md「镜像一致性校验」）。
- 部署：`deploy/` 下有拉镜像（registry）、源码构建、离线 tar 三种方式；仓库根目录的 `panel_install.sh` / `install.sh` 已改为本仓库地址。
- CI：`.github/workflows/docker-build.yml` 构建 `ahmadplayz721/*` 镜像，secrets 已配置；推送到 `feat/port-traffic-ranking` 或 `main` 自动构建（纯文档改动已跳过，需要时用 `workflow_dispatch` 手动触发）。

## 下一步

1. 在服务器上部署（[github-dockerhub.md](github-dockerhub.md) 第 10 步起，或直接用 `deploy/docker-compose.registry.yml`）。
2. 登录面板、改默认密码、设置「面板后端地址」为 `公网IP:6365`。
3. 可选：浅色/窄屏视觉走查、H5 入口、实时速率趋势图、GitHub Actions 自动构建。
