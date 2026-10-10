# flux-panel 项目说明书（面向 AI 助手）

> **这份文档的目的**：让另一个 AI 在**不读完全部源码**的情况下，快速建立正确的项目认知，
> 并能安全地做二次开发。
>
> **读法建议**：先读第 1-3 章建立全局观，再按需读第 4 章（改动指南）。
> 第 8 章「踩坑清单」务必读完 —— 里面每一条都是真实踩过的坑。

---

## 1. 项目是什么

**flux-panel** 是一个基于 [gost](https://github.com/go-gost/gost) 的**端口转发 / 隧道转发的管理面板**。

一句话架构：

```
用户浏览器 ──► 面板(Spring Boot + React) ──WebSocket下发配置──► 节点(gost, Go)
                    │                                              │
                    └────────HTTP上报流量(每N秒)──────────────────┘
```

**核心概念**：

| 概念 | 含义 |
|---|---|
| **节点 (Node)** | 一台装了 gost 的服务器，负责实际转发流量。面板通过 WebSocket 给它下发配置 |
| **隧道 (Tunnel)** | 定义「从哪个节点进、从哪个节点出」的通道。分端口转发(type=1)和隧道转发(type=2) |
| **转发 (Forward)** | 一条具体的端口映射规则，属于某个用户 + 某个隧道，有入口端口和出口端口 |
| **用户隧道权限 (UserTunnel)** | 用户对某条隧道的使用配额（流量上限、转发数量、到期时间） |

**关键关系**：
- 一个 Tunnel 关联两个 Node（入口节点 in_node_id、出口节点 out_node_id）
- 一个 Forward 属于一个 User + 一个 Tunnel
- 用户能用哪些隧道由 UserTunnel 决定

---

## 2. 技术栈与目录结构

### 技术栈

| 模块 | 技术 | 说明 |
|---|---|---|
| `springboot-backend/` | Java 21 + Spring Boot 2.7.18 + MyBatis-Plus 3.4.1 + MySQL 5.7 | 面板后端 |
| `vite-frontend/` | React 18 + TypeScript + Vite 5 + HeroUI + TailwindCSS | 面板前端 |
| `go-gost/` | Go 1.23 + 官方 gost 库 | 节点端程序（**编译成单个二进制**） |
| `ios-app/` | SwiftUI + WKWebView | **纯壳**，加载前端产物 |
| `android-app/` | Kotlin + WebView | **纯壳**，加载前端产物 |

### 目录结构与职责

```
flux-panel/
├── springboot-backend/                 # 面板后端
│   ├── src/main/java/com/admin/
│   │   ├── controller/                 # 接口层（12个Controller）
│   │   ├── service/impl/               # 业务逻辑（重点看这里）
│   │   ├── entity/                     # 数据库实体
│   │   ├── mapper/                     # MyBatis Mapper 接口
│   │   ├── common/dto/                 # 请求/响应对象
│   │   ├── common/utils/               # 工具类（认证、加密、gost交互）
│   │   ├── common/task/                # 定时任务
│   │   ├── common/aop/                 # 日志/审计切面
│   │   ├── common/interceptor/         # JWT 拦截器
│   │   └── config/                     # Spring 配置
│   └── src/main/resources/
│       ├── application.yml             # 配置（数据源、端口）
│       ├── mapper/*.xml                # MyBatis SQL（复杂查询在这里）
│       └── fonts/SIMSUN.TTC            # 验证码字体（10MB，别删）
│
├── vite-frontend/                      # 面板前端
│   └── src/
│       ├── pages/                      # 页面（12个）
│       ├── layouts/                    # 布局（admin=PC侧边栏, h5=手机底部tab）
│       ├── api/index.ts                # ★ 所有后端接口的封装，改接口先看这里
│       ├── components/                 # 通用组件
│       └── utils/                      # 工具函数
│
├── go-gost/                            # 节点端（Go）
│   ├── main.go                         # 入口：读 config.json，启动上报
│   ├── config.go                       # 配置结构（支持多面板）
│   └── x/                              # gost 库（含本项目定制代码）
│       ├── socket/websocket_reporter.go # ★ WebSocket 上报与命令处理（核心）
│       └── service/traffic_reporter.go  # ★ 流量上报（核心）
│
├── gost.sql                            # 全新安装的建表脚本
├── migrate-port-ranking.sql            # 增量迁移（老库升级用，可重复执行）
├── docker-compose-v4.yml               # 官方编排（用官方镜像）
├── docker-compose-local.yml            # 本地源码构建编排
└── panel_install.sh / install.sh       # 官方安装脚本
```

---

## 3. 数据流（理解这个才能改对地方）

### 3.1 配置下发（面板 → 节点）

1. 管理员在面板创建/修改转发
2. `ForwardServiceImpl` 调用 `GostUtil` 把配置**通过 WebSocket 发给对应节点**
3. 节点端 `websocket_reporter.go` 的 `routeCommand()` 按命令类型分发：
   - `AddService` / `UpdateService` / `DeleteService`
   - `PauseService` / `ResumeService`
   - `AddChains` / `AddLimiters`（链与限速）
   - `TcpPing`（转发诊断）、`SetProtocol`
4. 节点调用 gost 库真正建立转发

**服务命名规则（关键）**：面板生成的服务名格式是

```
{forwardId}_{userId}_{userTunnelId}
```

节点端**就靠这个名字**识别是哪个转发。

### 3.2 流量上报（节点 → 面板）

节点端**每几秒**上报一次流量，格式极度精简：

```go
type TrafficReportItem struct {
    N string `json:"n"` // 服务名，即 {forwardId}_{userId}_{userTunnelId}
    U int64  `json:"u"` // 上行字节
    D int64  `json:"d"` // 下行字节
}
```

POST 到 `/flow/upload?secret=节点密钥`

面板端 `FlowController.uploadFlowData()` 处理：
- 按 `_` 切分 `n`，解析出 forwardId / userId / userTunnelId
- 用 `setSql("in_flow = in_flow + N")` **累加**到 forward / user / user_tunnel 三张表
- 检查流量是否超限，超限则暂停服务

**⚠️ 重要**：`forward.in_flow` / `out_flow` 是**累计值**，没有时间戳。
想知道「今天用了多少」必须靠差值计算（见定时任务 `StatisticsFlowAsync`）。

### 3.3 认证流程

1. 登录 `POST /api/v1/user/login` → 返回 JWT token
2. 前端把 token 存 localStorage，每次请求带 `Authorization` 头
3. `JwtInterceptor` 校验：**签名 + 过期时间 + token_version + 账号状态**
4. token 中携带 `sub`(用户ID) / `role_id` / `token_version`

**角色**：`role_id = 0` 是管理员，`1` 是普通用户。代码里到处用这个判断。

---

## 4. 改动指南（最常见需求）

### 4.1 加一个新接口

1. `dto/` 加请求/响应对象
2. `controller/` 加方法（记得加 `@LogAnnotation` 才会记审计日志）
3. `service/` + `service/impl/` 加业务逻辑
4. 前端 `api/index.ts` 加封装，再在页面调用

**注意**：`/api/**` 全部走 JWT 拦截器；只有少数白名单路径例外，列表在 `WebMvcConfig.java`：

```
/flow/**                 节点上报流量与配置（用 secret 鉴权，不走 JWT）
/api/v1/open_api/**      对外开放接口
/api/v1/config/get       前端读取面板配置（免登录，如站点名称）
/api/v1/user/login       登录
/api/v1/captcha/**       验证码
```

**新接口默认受保护**。要做免登录接口必须显式加进白名单。

`@LogAnnotation` 目前加在 **46 个**控制器方法上。新接口加上它才会进审计日志
（且只有方法名匹配写操作模式的才真正落库）。

### 4.2 加一个新页面

1. `pages/` 新建 tsx
2. `App.tsx` 注册路由（用 `<ProtectedRoute>` 包裹）
3. `layouts/admin.tsx` 的 `menuItems` 加菜单项 → **PC 端生效**
4. `layouts/h5.tsx` 的 `tabItems` 加 tab → **手机端生效**

**只加一处的话，另一个端看不到入口**（这是最容易漏的地方）。

`menuItems` 支持 `adminOnly: true` 标记，只有管理员能看到。

### 4.3 改数据库表结构

需要**同时改两处**：
1. `gost.sql` —— 全新安装用
2. `migrate-port-ranking.sql` —— 老库升级用

**⚠️ MySQL 5.7 不支持 `ADD COLUMN IF NOT EXISTS`**，所以迁移脚本里用了
存储过程查 `information_schema` 再决定是否添加，保证可重复执行。改的时候要保持这个模式。

### 4.4 改节点端行为

改 `go-gost/` 下的代码后，交叉编译：

```bash
cd go-gost
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o gost-amd64 .
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o gost-arm64 .
# 可选：UPX 压缩（32MB -> 8MB）
upx --best --lzma gost-amd64
```

**注意**：`node_modules`/`vendor` 不需要；`go-gost/x` 是通过 `replace` 引用的本地模块，
`go.mod` 里有 `replace github.com/go-gost/x => ./x`。

### 4.5 改前端客户端的构建方式

**iOS/Android 是 WebView 壳**，加载 `index.html`。

- **面板**用 `npm run build` → 输出 `dist/`，`base='/'`，资源在 `assets/` 子目录
- **客户端**用 `npm run build:client` → 输出 `dist-client/`，`base='./'`，资源**平铺**

**为什么必须分开**：App 通过 `file://` 加载，用绝对路径 `/assets/xx.js` 会 404 白屏。
这是实测出来的坑（见第 8 章）。

---

## 5. 认证与安全（本项目已加固，改动时勿破坏）

### 5.1 密码存储

用 **`PasswordUtil`**（PBKDF2-HMAC-SHA256），**不要再用 `Md5Util`**。

- 新密码：`PasswordUtil.hash(rawPassword)`
- 校验：`PasswordUtil.verify(rawPassword, storedPassword)` —— **兼容历史 MD5**
- 升级：`PasswordUtil.needsUpgrade(stored)` → true 则需重新 hash

**历史包袱**：老库中密码是**无盐 MD5**。`verify()` 会自动识别并校验通过，
登录成功后由 `UserServiceImpl.upgradeLegacyPasswordIfNeeded()` **透明升级**。
**不要**写批量改写密码的脚本 —— 会破坏这个机制。

### 5.2 Token 吊销

`user.token_version` 字段：

- 签发 token 时写入版本号
- 改密码 / 管理员重置密码 → 版本 +1
- `JwtInterceptor` 每次请求比对，不一致则拒绝

**加新的「敏感操作」时**（如强制下线），也要把 `token_version` +1。

### 5.3 登录防爆破

`user.login_fail_count` + `user.locked_until`：

- 连续失败 5 次 → 锁定 15 分钟
- 登录成功清零

阈值在 `UserServiceImpl` 的 `MAX_LOGIN_FAILURES` / `LOGIN_LOCK_DURATION_MS`。

### 5.4 审计日志

`LogAspect` 会把**增删改类操作**写入 `audit_log` 表。

- 只记录方法名匹配 `create|update|delete|remove|reset|assign|pause|resume|import|upload|force|changePassword|updatePassword|login` 的操作
- **自动脱敏**：`pwd`/`password`/`secret`/`token`/`encryptkey`/`appsecret`/`webhook` 会被替换为 `******`
- 登录接口只记录事件，不记录参数

**加新接口时**：如果参数含敏感字段，确认字段名能命中脱敏关键字，否则会明文入库。

---

## 6. 定时任务

`StatisticsFlowAsync` 里有两个 `@Scheduled(cron = "0 0 * * * ?")`（每小时整点）：

| 方法 | 作用 | 保留期 |
|---|---|---|
| `statistics_flow()` | 用户级流量快照，供仪表板 24 小时折线图 | 48 小时 |
| `statistics_flow_forward()` | **转发(端口)级**流量快照，供端口流量排行榜 | 8 天 |

**两个任务是独立方法**，故意拆开的：早先版本把它们写在一起，
前面的用户统计一旦抛异常，后面的端口采集就完全不执行。

**增量计算逻辑**：`本次累计值 - 上次快照累计值`；若为负（用户重置流量）则按「本次全部计入」处理。

---

## 7. 数据库表

| 表 | 说明 |
|---|---|
| `user` | 用户（含 `token_version`/`login_fail_count`/`locked_until` 三个安全字段） |
| `node` | 节点（`secret` 是节点密钥，`port_sta`/`port_end` 是可用端口范围） |
| `tunnel` | 隧道（`in_node_id`/`out_node_id`/`type`/`flow`计费方式/`traffic_ratio`倍率） |
| `forward` | 转发规则（`in_port`/`out_port`/`in_flow`/`out_flow`累计值/`inx`排序） |
| `user_tunnel` | 用户隧道权限（流量配额、转发数配额、到期时间） |
| `speed_limit` | 限速规则 |
| `statistics_flow` | 用户级小时快照（**只存 48 小时**） |
| `statistics_flow_forward` | 端口级小时快照（**存 8 天**，排行榜数据源） |
| `audit_log` | 操作审计日志 |
| `vite_config` | 面板配置项（如 `captcha_enabled`、`app_name`） |

**注意**：`MyBatis-Plus` 默认驼峰转下划线，实体 `userId` ↔ 字段 `user_id`。

---

## 8. ⚠️ 踩坑清单（每条都是真实踩过的）

### 8.1 数据库相关

- **`user` 是 SQL 保留字**。MyBatis 生成的 SQL 里 `WHERE (user = ?)` 在 MySQL 下正常，
  但 **H2 会把它解析成 `USER()` 函数**导致查不到数据。用 H2 做测试时会撞上，
  **生产 MySQL 无此问题**（不要为此改代码）。

- **MySQL 5.7 无 `ADD COLUMN IF NOT EXISTS`**，迁移脚本必须用存储过程判断。

- **`statistics_flow` 只保留 48 小时**。如果新功能需要更长时间的历史，必须自己建表
  （参考 `statistics_flow_forward` 的 8 天保留），不要指望改这个值 —— 它和仪表板图表耦合。

### 8.2 前端相关

- **`forward.tsx` 超过 2000 行**，是最大的文件。改之前先搜索定位，不要通读。

- **`getSortedForwards()` 曾在 render 路径被调用两次**，且内部有 O(n²) 的 `includes` 查找。
  现已用 `useMemo` 缓存（`sortedForwards` / `groupedForwards` / `sortableForwardIds`）。
  **新增派生数据时，请用 `useMemo` 并复用已缓存的结果**，不要重新计算。

- **`App.tsx` 的 `ProtectedRoute` 已经提供了布局**，页面组件**不要**再用
  `PageWrapper`（那是遗留组件，会导致双层侧边栏）。

- **PC 菜单和手机 tab 是两个文件**（`layouts/admin.tsx` 和 `layouts/h5.tsx`），
  加页面要同时改，否则某一端没有入口。

### 8.3 节点端相关

- **iOS/Android 用 `file://` 加载页面**，必须用 `base='./'` 的相对路径构建产物，
  绝对路径会 404 白屏。用 `npm run build:client`。

- **`forwardId` 会重名**：两个面板的 forwardId 都从 1 自增。
  如果做多面板支持，必须给服务名加**面板前缀**，否则 A 面板删转发会误删 B 面板的。
  参考 `config.go` 的 `isolatePrefix()` 和 `websocket_reporter.go` 的 `isolate()`。

- **流量上报时要把前缀剥掉**：面板按 `{forwardId}_{userId}_{userTunnelId}` 解析，
  带前缀会导致解析失败、流量统计全废。参考 `traffic_reporter.go` 的 `routeTarget()`。

- **`config.json` 要防 UTF-8 BOM**：用 Windows 记事本编辑会带 BOM，
  Go 的 JSON 解析器会报 `invalid character 'ï'`，极难排查。`LoadConfig` 里已做处理。

- **`updateLocalConfigJSON` 曾会覆盖整个 config.json**（用只认识单面板的结构重写），
  导致多面板配置丢失。现已改为读原始 map 再改字段。**改这个方法要小心**。

### 8.4 构建/部署相关

- **npm 有 peer 依赖冲突**：`@heroui/accordion` 与 `@heroui/button` 对
  `@heroui/theme` 的版本要求冲突，不加 `legacy-peer-deps` 会 `ERESOLVE` 失败。
  已在 `.npmrc` 里配置。

- **镜像地址与 tag 必须同步抬升**：`docker-compose-v4/v6.yml` 用的是预编译镜像
  （现为 `liang0222/xxx:1.4.4`）。CI 只在 tag **不存在**时才构建镜像，因此每次发版
  都必须把 tag 往上抬，否则推代码不会更新镜像（此前 `1.4.3` 就踩过这个坑）。
  要用本地未发布的改动验证，请用**从源码构建**的 `docker-compose-local.yml`。

- **安装脚本地址可配置**：`NodeServiceImpl.buildInstallCommand()` 默认使用
  `DEFAULT_INSTALL_SCRIPT_URL`，并优先读取 `vite_config` 中的 `install_script_url`。
  fork 后若改过 `install.sh`（安装目录/服务名），务必在「网站配置」里填自己的地址，
  否则节点会被装成上游原版。

- **shell 脚本必须是 LF 换行**：`install.sh` / `panel_install.sh` 曾在 Windows 下被改成
  CRLF，导致 Linux 上直接报 `syntax error near unexpected token '{\r'`。
  提交前请确认 `bash -n install.sh` 能通过。

---

## 9. 如何验证改动

### 后端

```bash
cd springboot-backend
mvn clean package -DskipTests          # 编译
mvn test -Dtest=PasswordUtilTest       # 跑指定测试
```

现有测试：`PasswordUtilTest`(11) / `LogAspectMaskTest`(7) / `JwtUtilTest`(7)

### 前端

```bash
cd vite-frontend
npx tsc --noEmit                       # 类型检查（必须零错误）
npm run build                          # 面板构建
npm run build:client                   # 客户端构建
npm test                               # 单元测试（Node 内置 runner，无需装框架）
```

### 节点端

```bash
cd go-gost
go build -o /dev/null .                # 编译检查
go vet ./...                           # 静态检查
```

---

## 10. 二次开发建议

**推荐的改动顺序**（降低风险）：

1. 先读 `application.yml` 和 `gost.sql` 了解配置与表结构
2. 要改接口 → 从 `controller/` 顺着看 `service/impl/`
3. 要改前端 → 从 `api/index.ts` 找到对应封装，再定位页面
4. 要改节点行为 → 先看 `websocket_reporter.go` 的 `routeCommand()`，它列出了所有命令类型

**不要做的事**：

- 不要删除 `fonts/SIMSUN.TTC`（验证码渲染依赖它）
- 不要改服务名格式 `{forwardId}_{userId}_{userTunnelId}`（节点端硬编码解析）
- 不要在 `FlowController` 里用「先查再改」更新流量（并发会丢数据，必须用 `setSql` 原子累加）
- 不要给 `md5` 相关的旧代码加新用法（新代码一律用 `PasswordUtil`）

**性能注意**：

- 流量上报是**高频接口**，处理逻辑要尽量轻
- `FlowController` 里用了 `ConcurrentHashMap` 做锁，按 userId/tunnelId/forwardId 分段
- 大表查询注意加索引（`created_time`、`user_id`、`forward_id` 都有索引）

---

## 11. 默认凭据

- 管理员账号：`admin_user`
- 管理员密码：`admin_user`

> 首次登录后应立即修改。密码存储在 `gost.sql` 里是 MD5 值，
> 登录成功后会自动升级为 PBKDF2。

---

## 12. 文档之外的未知项

以下内容本说明书**未覆盖**，需要时请直接读代码：

- 验证码（tianai-captcha）的具体配置参数
- 飞书通知的完整实现（如果这个版本包含）
- gost 库内部的转发实现细节（`go-gost/x/` 下 500+ 文件）
- OpenAPI 接口（`/api/v1/open_api/**`）的完整定义
