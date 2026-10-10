# 给 AI 助手的入口说明

你拿到的是 **flux-panel**（一个 gost 端口转发面板）的完整源码包。

## 第一步：读 `AI_PROJECT_GUIDE.md`

包根目录下的 **`AI_PROJECT_GUIDE.md`** 是专门为你写的项目说明书，包含：

- 项目是什么、核心概念（节点/隧道/转发的关系）
- 目录结构与各文件职责
- **数据流**（配置怎么下发、流量怎么上报）
- **改动指南**（加接口/加页面/改表结构/改节点端的具体步骤）
- 认证与安全设计（密码、Token 吊销、防爆破、审计日志）
- **⚠️ 踩坑清单** —— 每一条都是真实踩过的坑，务必读完
- 如何验证改动（编译/测试命令）

## 第二步：按需查代码

说明书里已经给出了关键文件路径。最常改的几处：

| 要改什么 | 从哪里开始 |
|---|---|
| 后端接口 | `springboot-backend/.../controller/` → `service/impl/` |
| 前端页面 | `vite-frontend/src/api/index.ts` → `pages/` |
| 节点端行为 | `go-gost/x/socket/websocket_reporter.go` 的 `routeCommand()` |
| 数据库 | `gost.sql`（全新安装）+ `migrate-port-ranking.sql`（老库升级） |

## 三步验证

```bash
# 后端
cd springboot-backend && mvn clean package -DskipTests

# 前端（类型检查必须零错误）
cd vite-frontend && npx tsc --noEmit && npm run build

# 节点端
cd go-gost && go build -o /dev/null . && go vet ./...
```

## 重要提醒

1. **不要用 `Md5Util` 存新密码** —— 用 `PasswordUtil`（PBKDF2）
2. **不要改服务名格式** `{forwardId}_{userId}_{userTunnelId}` —— 节点端硬编码解析
3. **不要删 `fonts/SIMSUN.TTC`** —— 验证码渲染依赖
4. **改流量统计不能用「先查再改」** —— 并发会丢数据，必须用原子 SQL 累加
5. **加前端页面要同时改 PC 菜单和手机 tab** —— 两个文件

## 环境依赖

- JDK 21 + Maven（后端）
- Node 20+（前端）
- Go 1.23+（节点端，仅在需要重新编译二进制时）
- MySQL 5.7（生产数据库）
- Docker（部署方式之一，非必须）

## 这个包里没有什么

- **没有** `node_modules`、`target`、`dist` 等构建产物 —— 需要自己 `npm install` / `mvn package`
- **没有** 编译好的节点端二进制（`gost-amd64`/`gost-arm64`）—— 需要自己 `go build`
- **没有** `.git` 历史

这些都可以在拿到包后按上面的命令重新生成。
