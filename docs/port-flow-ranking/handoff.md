# 交接说明：端口转发流量排行榜（flux-panel v2.0.7-beta）

> 给下一个会话看的进度快照。读完这一页即可继续，不需要回看原对话。
> 最后更新：2026-10-21 15:35（Asia/Shanghai）

## 一句话现状

两期功能都已实现并验证：**端口流量排行** + **仪表盘实时速率**（47 个后端测试、前端构建、真实 Docker 部署与页面渲染截图）。代码已提交并推送到 **GitHub `liang0222/flux-panel`**（17 个提交，最新 `fc1db1a`），镜像已推送到 **Docker Hub `ahmadplayz721`**（`2.0.7-beta` / `latest`，amd64+arm64，已拉取核对镜像内确含新代码）；2026-10-25 做过一轮全面代码审查（`code-review.md`），无阻断缺陷。剩余工作只有服务器部署、合并到 `main` 时改安装 URL、可选的体验补充和上线观察。

## 代码位置与状态

- 仓库：`/root/v2.0.7-beta`（clone 自 `https://github.com/bqlpfy/flux-panel`）
- 分支：`feat/port-traffic-ranking`，基于 tag **`2.0.7-beta`**（`761db18`）
- GitHub：https://github.com/liang0222/flux-panel （`origin` 已指向它，`upstream` 保留原作者）
- 提交：17 个（后端 / 测试 / 前端 / 部署脚本 / 文档 / CI / 安装脚本 / 审查），最新 `fc1db1a`，工作区干净
- Docker Hub：`ahmadplayz721/flux-panel-backend:2.0.7-beta`、`ahmadplayz721/flux-panel-frontend:2.0.7-beta`（2026-10-24 已推送）
- 一键安装：`panel_install.sh` / `install.sh` 已改为本仓库地址；面板生成的节点安装命令也指向本仓库
- CI：GitHub Actions 已跑通（run 40078633991），自动构建 amd64 + arm64 并推送 `latest`/`2.0.7-beta`
- 改动规模：8 个修改 + 26 个新增（后端实体/Mapper/Service/Controller/DTO/定时任务、前端排行页、测试、`deploy/`、`docs/`、两个 `.dockerignore`）
- 目标版本号未改动（仍为 `2.0.7-beta`），依赖未变

## 环境（本机已就绪，重开容器需重做）

| 项 | 值 |
| --- | --- |
| JDK | `/opt/tools/jdk21`（Temurin 21.0.12.1），需 `export JAVA_HOME=/opt/tools/jdk21` |
| Maven | `/opt/tools/maven`（3.9.9），需加入 `PATH` |
| 系统依赖 | `fontconfig` + `fonts-dejavu`（应用启动加载验证码字体必需） |
| 前端 | `vite-frontend/node_modules` 已安装（`npm install --legacy-peer-deps`） |
| 产物 | `springboot-backend/target/admin-0.0.1-SNAPSHOT.jar`、`vite-frontend/dist` |

常用命令：

```bash
export JAVA_HOME=/opt/tools/jdk21 PATH=/opt/tools/jdk21/bin:/opt/tools/maven/bin:$PATH
cd /root/v2.0.7-beta/springboot-backend && mvn -B clean test package
cd /root/v2.0.7-beta/vite-frontend && npm run build
```

## 已完成

### 后端（`springboot-backend`）

| 文件 | 作用 |
| --- | --- |
| `src/main/resources/schema.sql`（改） | 新增 `forward_flow_statistics` 表 + 2 个索引，幂等 DDL，启动自动建表 |
| `entity/ForwardFlowStatistics.java` | 采集记录实体（计数器快照 + 增量 + 展示快照） |
| `mapper/ForwardFlowStatisticsMapper.java` + `resources/mapper/ForwardFlowStatisticsMapper.xml` | 最新记录、区间 SUM、最后采集时间、过期删除 |
| `common/utils/FlowCounterDeltaCalculator.java` | 纯函数：差额/重置/缺失/非负 |
| `common/utils/FlowStatisticsRange.java` | 纯函数：today/3d/7d/30d 起点（当地时区） |
| `service/ForwardFlowStatisticsService.java` + `impl/...ServiceImpl.java` | 采集、聚合排行、清理、最后采集时间 |
| `common/task/ForwardFlowStatisticsAsync.java` | `0 */5 * * * ?` 采集；`0 10 3 * * ?` 清理 |
| `controller/ForwardFlowController.java` | `POST /api/v1/forward-flow/ranking`（管理员） |
| `common/dto/ForwardFlow*.java`（4 个） | 查询参数、聚合行、排行条目、排行响应 |
| `pom.xml`（改） | surefire 测试环境变量（JWT_SECRET / LOG_DIR / DB_PATH），运行无影响 |

### 前端（`vite-frontend`）

| 文件 | 作用 |
| --- | --- |
| `src/pages/flow-ranking.tsx` | 「流量排行」页：范围切换、汇总卡、排行列表、口径说明、静默刷新 |
| `src/api/index.ts`（改） | `getForwardFlowRanking(range)` |
| `src/App.tsx`（改） | `/flow-ranking` 路由 |
| `src/layouts/admin.tsx`（改） | 菜单「流量排行」（`adminOnly`） |

### 部署产物（新增，2026-10-20）

| 文件 | 作用 |
| --- | --- |
| `deploy/docker-compose.yml` | 从源码构建并启动，镜像名 `local/*`，不依赖任何镜像仓库 |
| `deploy/.env.example` | JWT_SECRET / 前后端端口模板 |
| `deploy/rebuild.sh` | 一键重建并重启 |
| `deploy/export-images.sh` | 构建并导出 201MB 镜像 tar，服务器 `docker load` 即可 |
| `deploy/docker-compose.registry.yml` | 直接用 Docker Hub 镜像部署（无 build，已填 `ahmadplayz721/...:2.0.7-beta`） |
| `deploy/push-images.sh` | 构建并推送镜像到 Docker Hub（默认 `ahmadplayz721` / `2.0.7-beta`） |
| `springboot-backend/.dockerignore`、`vite-frontend/.dockerignore` | 排除 `target/`、`node_modules/`、`dist/` |
| `.gitignore`（改） | 忽略 `deploy/*.tar` |

### 测试（`springboot-backend/src/test`）

| 文件 | 用例数 | 覆盖 |
| --- | --- | --- |
| `common/utils/FlowCounterDeltaCalculatorTest` | 8 | 首次基线、差额、双向/单边回退、缺失补齐、null/负数 |
| `common/utils/FlowStatisticsRangeTest` | 4 | 当日零点、3/7/30 天、跨月、非法值 |
| `service/ForwardFlowStatisticsServiceTest` | 16 | 落库内容、跳过无增量、重置、无端口、排序/并列、已删除快照、空结果、多端口、清理截止 |
| `service/ForwardFlowStatisticsIntegrationTest` | 2 | 真实 SQLite：建表/SQL/采集→聚合→排序/重启可查/清理 + MockMvc 接口契约（200/403/401/非法/缺省） |
| `common/utils/RealtimeFlowTrackerTest` | 9 | 窗口换算、窗口过期、按用户过滤、排序、零增量、清理 |
| `service/FlowRateServiceTest` | 4 | 总量/占比、Top 端口/用户、管理员与普通用户范围、空数据 |
| `service/FlowRateIntegrationTest` | 3 | 真实上报 → 接口、用户隔离、401、非法密钥 |
| `src/test/resources/logback-test.xml` | - | 让测试不依赖 `LOG_DIR` |

验证记录：`mvn -B clean test package` → **Tests run: 47, Failures: 0, Errors: 0，BUILD SUCCESS**；`npm run build` → 通过。

### 真实运行冒烟（已完成，临时库已删）

时间线（同一进程/数据库，`Asia/Shanghai`）：

| 时间 | 操作 | 结果 |
| --- | --- | --- |
| 12:10 | 首次采集（计数器 1000/500） | 基线行，增量 0 |
| 12:15 | 计数器改 1600/700 | 增量 600/200=800 |
| 12:20 | 计数器改小 30/20 | 增量 30/20，`reset_flag=1`，无负值 |
| 12:21 | 删除 forward/forward_port | 接口仍显示 850 字节，`deleted=true`，名称/端口来自快照 |
| 12:21 | 重启服务 | 历史 850 仍在 |
| 12:25 | 计数器 50/30 | 只补 20/10，总计 880（无重复累计） |

接口同时验证：今天区间 `00:00~12:15`、近 30 天起点 `2026-09-21 00:00`、非管理员 403、未登录 401。

### Docker 部署验证（已完成，2026-10-20 16:33~16:42）

- `docker compose up -d --build`：后端 healthy、前端启动（约 5 分钟，含拉基础镜像）。
- 容器内自动建表；16:35 基线、16:40 增量 600/200=800。
- 经前端 nginx 调接口：200 / 无 token 401 / 非管理员 403；`totalFlow=800`、端口 23333。
- `docker compose restart backend`：数据与接口不变；删除 forward 后 `deleted=true`、历史保留。
- `docker save`（201MB）→ `docker rmi` → `docker load` → `up -d`：镜像恢复、healthy、数据与接口正常；SPA 路由 `/flow-ranking` 返回 200。
- 验证后已 `down -v` 清理测试数据，保留镜像与 `deploy/flux-panel-images-2.0.7-beta.tar`。

## 本轮新增：仪表盘「实时速率」（2026-10-21）

需求：在仪表盘展示实时速率（参考截图：总速率、入站/出站占比、最忙端口、最高用户、每 2 秒刷新），口径沿用面板现有（入站 + 出站，倍率已在上报时乘过）。

**后端**

| 文件 | 作用 |
| --- | --- |
| `common/utils/RealtimeFlowTracker.java` | 内存 10 秒滑动窗口，按转发记录增量；每 60 秒清理冷桶；不落库 |
| `service/FlowRateService.java` | 汇总总量/占比、最忙端口（补名称与端口）、最高用户；按角色过滤 |
| `controller/FlowRateController.java` | `POST /api/v1/flow-rate/current`（登录即可，管理员看全部、普通用户只看自己） |
| `controller/FlowController.java`（改） | 在流量换算之后、写库之前记录实时增量（与累计口径一致） |
| `common/dto/FlowRateDto.java`、`FlowRateTopDto.java` | 响应结构 |

**前端**：[dashboard.tsx](../../vite-frontend/src/pages/dashboard.tsx) 在 4 张统计卡下方新增「实时速率」卡（总速率 Mbit/s、入站/出站条与占比、最忙端口、最高用户、最近上报时间），2 秒轮询、页面隐藏时暂停、失败保留上次值；[api/index.ts](../../vite-frontend/src/api/index.ts) 增加 `getCurrentFlowRate()`。

**测试**：`RealtimeFlowTrackerTest`(9)、`FlowRateServiceTest`(4)、`FlowRateIntegrationTest`(3，真实 `/flow/upload` → 接口 → 角色隔离)。后端合计 **47** 个测试。

**注意**：实时速率只存在内存，服务重启后从零重新累积；节点每 5 秒上报一次，10 秒窗口足以覆盖最近两次上报。

**截图**：[dashboard-realtime-card.png](dashboard-realtime-card.png)（卡片）、[dashboard-realtime-dark.png](dashboard-realtime-dark.png)（整页）、[ranking-today.png](ranking-today.png)（流量排行页，模拟 6 个端口的数据）。采集方式：真实 Docker 栈 + 两个端口模拟上报（31.2 + 8.0 Mbit/s）+ Playwright 无头 Chromium 注入管理员 token；图中数字为**模拟流量**，不是真实用户数据。

**排行页排版修复（2026-10-21 14:07）**：「统计区间」卡片原来用 `break-all` 把结束时间折成 `14:0 / 5`，改为两行「开始 … / 结束 …」。

**Docker 冒烟（2026-10-21 11:31~11:33）**：容器内造一次 `/flow/upload`（入站 620000、出站 4000）→ 接口返回 `totalRate=62400 B/s`、入站 62000（99.36%）、出站 400（0.64%）；管理员 `scope=all`、用户 2 `scope=self` 同值、用户 3 为 0；轮询接口无访问日志；镜像已重新导出为 201MB tar。

## 待完成

### 必做（交给用户决定）

1. ~~提交代码~~ 已完成（全部提交已推送到 GitHub，最新 `fc1db1a`）。
1b. **合并到 `main` 后同步改一键安装 URL**：`panel_install.sh:11-12` 现在写死 `refs/heads/feat/port-traffic-ranking`，分支删除/改名会让一键安装 404（详见 `code-review.md` M2）。
1c. ~~CI 标签陷阱~~ 已修复：`docker-build.yml` 改为按面板源码相对标签是否有改动决定构建，不再因标签存在而静默跳过（详见 `code-review.md` M1）。
2. **服务器部署**：直接跑 README 里的「面板端（开发版）」命令，或用 `deploy/docker-compose.registry.yml` 拉取镜像启动。
3. **视觉走查补充**：已用 Playwright 在真实 Docker 栈上截了暗色「仪表盘实时速率」和「流量排行」（见截图一节），仍建议补看**浅色主题、窄屏/手机布局、空态与错误态、按钮交互**。
4. ~~GitHub Actions~~ 已完成：工作流镜像名已改为 `ahmadplayz721/*`，secrets（`DOCKER_HUB_USERNAME` / `DOCKER_HUB_TOKEN`）已配置，推送即构建双架构并推送；纯文档改动已跳过重建（`paths-ignore`），见 `github-dockerhub.md` 第 15 节。

### 可选增强（按需）

4. H5 底部 Tab 增加入口（当前只有桌面端管理菜单可达）。
5. Dashboard 增加「流量排行」快捷入口卡片。
6. README/CHANGELOG 增加功能说明（项目 README 标注暂停更新，可不动）。
7. 版本号 bump 到下一个 beta（按任务要求保持 `2.0.7-beta`，未动）。
8. 「最近采集时间」的持久化心跳：当前为「内存运行时间 ∪ DB 最大采样时间」，重启后无流量时可能偏旧。
9. 多入口节点（不同端口）的流量拆分：需要节点侧按服务/端口上报，当前按转发汇总。
10. 采集/清理时间点、保留天数改为配置项（当前为常量 + 固定 cron）。
11. 时区改为显式配置（当前依赖 JVM `-Duser.timezone`）。
12. 长时间运行后的表体积观察（`forward_flow_statistics` 行数/文件大小）与索引效果复核。
13. 前端范围按钮的键盘可达性/无障碍（项目已有同类 a11y 告警，未新增）。
14. 实时速率的 60 秒迷你趋势图 / 按节点（最忙机器）拆分；需要节点侧扩展上报维度。
15. 实时速率窗口、轮询间隔、显示单位（Mbit/s ↔ MB/s）做成可配置。

## 关键决策（不要重开讨论）

- **统计单元 = 转发规则**，因为计数器与 gost 服务名都以转发为单位；页面展示入口端口，多端口只展示不拆分。
- **总流量 = 入站 + 出站**，取自现有计数器，倍率与计费已在上报时乘过，排行不再乘。
- **「今天」= 当地 00:00 起**；「近 N 天」= 含今天在内的 N 个自然日，接口返回区间文本。
- **首次只建基线**，避免把功能上线前的历史算进今天。
- **零增量不落库**（基线除外），把 30 天保留期的存储从百万行压到"有流量才有行"。
- **删除转发后历史仍参与排行**，用快照字段展示并标记「已删除」。
- **管理员专用**：接口 `@RequireRole`、菜单 `adminOnly`、页面 `isAdmin()` 守卫。
- **删除端口/转发不清理历史（已确认方案 A，2026-10-20）**：历史记录保留到 31 天过期，排行用快照展示并标记「已删除」；删除前最后 ≤5 分钟的流量不补记，接受这一精度损失。除非用户改口，不要再改成"删除即清历史"或"删除前补收尾样本"。
- 表随 `schema.sql` 自动创建，**无手工迁移**、无新增环境变量。

## 数据模型速查

`forward_flow_statistics`：`id, forward_id, forward_name, port, ports, node_id, tunnel_id, user_id, user_name, in_flow, out_flow, in_flow_delta, out_flow_delta, flow, total_flow, reset_flag, created_time, time`；索引 `(created_time)`、`(forward_id, created_time)`。
聚合口径：`SUM(in_flow_delta/out_flow_delta/flow) GROUP BY forward_id WHERE created_time BETWEEN start AND now`。

## 自测步骤（3 分钟复现核心结论）

```bash
# 1. 全量测试
export JAVA_HOME=/opt/tools/jdk21 PATH=/opt/tools/jdk21/bin:/opt/tools/maven/bin:$PATH
cd /root/v2.0.7-beta/springboot-backend && mvn -B test

# 2. 起服务（临时库）
mkdir -p /tmp/smoke/logs
DB_PATH=/tmp/smoke/gost.db JWT_SECRET=smoke-secret LOG_DIR=/tmp/smoke/logs \
  java -Duser.timezone=Asia/Shanghai -jar target/admin-0.0.1-SNAPSHOT.jar > /tmp/smoke/server.log 2>&1 &

# 3. 用 python3 sqlite3 造 forward / forward_port / tunnel 与计数器（见 lessons.md 的冒烟配方）
# 4. 等下一个 5 分钟边界，查表确认基线与增量
# 5. curl 接口（Python 复刻 JWT，secret=smoke-secret）
#    排行：POST /api/v1/forward-flow/ranking  {"range":"today"}
#    实时：POST /api/v1/flow-rate/current（管理员 scope=all，普通用户 scope=self）
```

## 已知限制

- 统计粒度 = 转发规则，非"每节点每端口"。
- 区间归属按采样点，最多 5 分钟误差；停机期间的流量补记到恢复后的采集点。
- 基线行（无增量）不进入排行；因此新部署后到第一个有流量的采集点之间，页面为空。
- 删除转发/端口前的最后 ≤5 分钟流量不会补记（方案 A 已确认，属已知精度损失）。
- 采集任务不支持多实例并发（项目本身是单实例 SQLite 部署）。
- 非管理员不可见；移动端 H5 无入口。
- 实时速率只存在内存：服务重启后从零累积；节点每 5 秒上报一次，10 秒窗口只能反映最近两次上报，数值每 5 秒跳一次。
- 实时速率的范围：管理员看全部用户，普通用户只看自己的转发；不落库，因此不能回溯历史速率。

## 交接检查清单

- [ ] `git status` 确认改动范围与本文一致
- [ ] `mvn -B test` 全绿（47）
- [ ] `npm run build` 通过
- [ ] 确认没有把 `/tmp`、`target/`、`dist/` 提交进去
- [ ] 确认 `docs/port-flow-ranking/` 是否随代码提交
- [ ] 上线后观察一个采集周期的日志与表数据
