# docs：端口流量排行 + 仪表盘实时速率（flux-panel v2.0.7-beta）

> 这里是本次改动的全部工作材料。功能代码在仓库其余目录，文档与截图在本目录。

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

## 当前状态（2026-10-21）

- 功能：端口转发流量排行（5 分钟采集 / 区间聚合 / 管理员页） + 仪表盘实时速率（10 秒内存窗口 / 2 秒轮询 / 按角色隔离）。
- 验证：后端 47 个测试全绿；前端 `npm run build` 通过；真实 Docker 部署、接口、重启、删转发、离线镜像 `save/load` 均验证过；页面已截图。
- 代码：分支 `feat/port-traffic-ranking`，基于 tag `2.0.7-beta`，**未提交**。
- 部署：`deploy/` 下有一键构建/导出脚本，不需要 GitHub 或镜像仓库。

## 下一步

1. 提交代码（拆分建议见 [handoff.md](handoff.md) 待完成）。
2. 部署到自己服务器（[deploy-and-publish.md](deploy-and-publish.md) 方式 A1 或 A2）。
3. 可选：浅色/窄屏视觉走查、H5 入口、实时速率趋势图。
