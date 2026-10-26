# flux-panel 性能优化报告（功能保持不变）

- 基线提交：`cdaf49c`（分支 `feat/port-traffic-ranking`）
- 优化范围：后端流量上报热路径、鉴权解析、节点密钥校验、前端首屏体积
- 原则：**只改实现方式，不改接口、表结构、返回字段与统计口径**；所有优化项都有对应测试或实测数据

---

## ① 热点清单

按「实际影响 × 触发频率」排序。触发频率来自代码路径分析：节点每 5 秒上报一次 `/flow/upload`，一次上报是一个 JSON 数组（生产环境通常几十到几百条转发）。

| # | 位置 | 成因 | 影响 |
|---|------|------|------|
| 1 | `springboot-backend/src/main/java/com/admin/controller/FlowController.java` `processFlowData` | 每条上报串行执行 3 条 `UPDATE`（forward / user / user_tunnel），每条都是「读-改-写」式 `setSql`，且**同一批次内同一转发/用户被重复更新 N 次** | 200 条转发的批次要发 600 条 UPDATE，SQLite 单写者下直接串行排队，实测 535 ms/批 |
| 2 | 同上，`forwardService.getById` / `tunnelService.getById` | 每条上报都查一次转发和隧道，同批次内同一转发重复查询 | 200 条批次额外 400 次查询 |
| 3 | 同上，`log.info("节点上报流量数据{}", flowDataList)` | 每 5 秒把整批 JSON 序列化后写日志（无论是否需要） | 持续磁盘 IO + 字符串分配，日志文件膨胀 |
| 4 | 同上，`USER_LOCKS` / `TUNNEL_LOCKS` / `FORWARD_LOCKS` | 按 ID 往 `ConcurrentHashMap` 里 `computeIfAbsent` 塞锁对象，**永不清理** | 转发/用户删除重建后内存单调增长（长跑实例的隐性泄漏） |
| 5 | 同上，`isValidNode` | 用 `count(*)` 判断密钥是否存在 | 每次上报一次计数查询；无索引时退化为全表扫描 |
| 6 | `springboot-backend/src/main/java/com/admin/common/utils/JwtUtil.java` | 每个请求对同一 token 重复 `split("\\.")`（正则）+ Base64 解码 + JSON 解析 2~4 次，且每次都 `Mac.getInstance` + `new SecretKeySpec` + `init` | 微基准 5.9 µs/轮（一次校验+取 userId+取 roleId），是所有 `/api/**` 请求的固定成本 |
| 7 | `vite-frontend/src/App.tsx` + `vite-frontend/vite.config.ts` | 11 个业务页面全部同步 `import`，且 `build.minify=false`、`treeshake=false` | 首屏单个 `index.js` **7.48 MB（gzip 1.49 MB）**，首屏必须下完+解析完才能渲染 |
| 8 | `vite-frontend/src/pages/dashboard.tsx` | 实时速率 2 秒轮询 | 已在上一轮实现里做了「页面隐藏暂停 + inFlight 去重 + 失败保留旧值」，本次不再改 |

---

## ② 优化方案

### 后端 1：上报批次内聚合 + 一次性写库（对应热点 1、2）

`uploadFlowData` 拆成三段：**采集（collect）→ 落库（flush）→ 检查（check）**。

- `collectFlowData`：只做查询缓存、倍率换算、实时速率记录、增量累加，不写库。同批次内转发/隧道查询结果（含「查不到」的结果）缓存在 `BatchContext`。
- `flushFlows`：把同一批次里同一转发/用户/用户隧道的增量合并成一条 `UPDATE ... in_flow = in_flow + N`。增量为 0 的实体直接跳过（原来是每条都执行 `+ 0`）。
- `checkLimits`：限额/到期/状态检查仍然**逐条消息**判断，用「批次末值 − 本批增量」反推批次起点再按消息顺序累加，保证「由跨过限额的那条消息触发暂停」的原有语义不变（暂停命令里带的服务名也不变）。

兼容性：写库顺序仍是「先累加、后检查」，与原来一致；`pauseService` 里的 `updateById` 不会被批次落库覆盖。

### 后端 2：锁对象改为固定条带（对应热点 4）

三张 `ConcurrentHashMap<String, Object>` 换成 `Object[64]` 条带数组，按 `hash % 64` 取锁。相同 ID 仍落在同一把锁上（并发语义不变），内存恒定。

### 后端 3：日志降级（对应热点 3）

`log.info` → `log.isDebugEnabled()` 包裹的 `log.debug`。生产 INFO 级别下不再序列化整批 JSON。

### 后端 4：节点密钥校验改为存在性查询（对应热点 5）

新增 `NodeMapper.existsBySecret`（`SELECT 1 FROM node WHERE secret = #{secret} LIMIT 1`）与 `NodeService.existsBySecret`，替代 `count(*)`。

### 后端 5：JWT 解析缓存（对应热点 6）

- `PARSED_CACHE`：token → payload，LRU 512 条。
- `VERIFIED_CACHE`：token → exp，仅在**签名与有效期都通过后**才写入（伪造 token 污染不了缓存），命中时仍每次比较有效期。
- `splitToken` 用 `indexOf` 替代正则 `split`；`Mac` 用 `ThreadLocal` 复用，`SecretKeySpec` 复用。
- 安全性不变：签名校验逻辑、过期判断、非法格式拒绝全部保留，新增 7 个单测覆盖篡改/过期/格式错误/多用户隔离。

### 前端 1：路由级懒加载（对应热点 7）

`App.tsx` 里 11 个业务页面改成 `React.lazy(() => import(...))`，用 `LazyPage`（内部 `Suspense`）包住，占位是布局内的一个转圈，**不闪整页**。登录页保持同步加载，保证首屏最快。

### 前端 2：开启压缩（对应热点 7）

`vite.config.ts`：`minify: false` → `minify: 'esbuild'`。`treeshake: false` 保持不动（上游显式关闭，避免改变副作用判定）。

---

## ③ 前后对比（实测）

环境：同一台机器、同一 JDK 21、SQLite 临时库、`-Xmx512m`；两次测量背靠背执行，中间无其他负载。

### 后端：流量上报（30 批 × 200 条转发 = 6000 条消息）

| 指标 | 优化前 | 优化后 | 变化 |
|------|--------|--------|------|
| 每批耗时 | 535.53 ms | 207.53 ms | **−61.2%（2.58×）** |
| 每条消息耗时 | 2.678 ms | 1.038 ms | −61.2% |
| 30 批总耗时 | 16.07 s | 6.23 s | −61.3% |
| 每批 UPDATE 条数 | 600 | 202 | **−66.3%** |
| 每批 SELECT 条数（转发+隧道） | 400 | 201 | −49.8% |

> UPDATE / SELECT 条数由测试 `FlowControllerBatchTest.countsUpdateStatementsPerBatch` 直接统计 Mockito 调用次数得出：UPDATE 200 转发 + 1 用户 + 1 用户隧道 = 202（优化前 600）；SELECT 200 次转发（200 个不同转发，缓存只能省掉重复）+ 1 次隧道 = 201（优化前 400）。

### 后端：鉴权接口（3000 次 `POST /api/v1/flow-rate/current`）

| 指标 | 优化前 | 优化后 | 变化 |
|------|--------|--------|------|
| 单请求耗时 | 2.921 ms | 2.907 ms | ≈0（噪声内） |
| 吞吐 | 342.3 rps | 344.0 rps | ≈0 |

端到端没有变化是**符合预期**的：这个接口的时间主要花在 HTTP 与业务查询上，JWT 解析只占其中约 3 µs。JWT 的收益要用微基准看：

| 指标 | 优化前 | 优化后 | 变化 |
|------|--------|--------|------|
| 单轮解析（校验 + 取 userId + 取 roleId） | 5 917 ns | 309 ns | **−94.8%（19.1×）** |
| 每秒可解析轮数 | 168 998 | 3 225 830 | 19.1× |

### 前端：产物体积

| 指标 | 优化前 | 优化后 | 变化 |
|------|--------|--------|------|
| 首屏 JS（`index.js`） | 7 480.30 kB | 1 191.29 kB | **−84.1%** |
| 首屏 JS gzip | 1 485.73 kB | 359.00 kB | −75.8% |
| 首屏 JS + CSS gzip | 1 523.99 kB | ≈396 kB | **−74.0%** |
| dist 总大小 | 7 835 654 B | 3 014 960 B | −61.5% |
| 页面分包 | 无（单包） | 11 个路由分包（dashboard 613 kB、user 310 kB、forward 88 kB…） | — |

---

## ④ 测试与验证

### 单元/集成测试（`mvn -B test`）

```
Tests run: 86, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

本轮新增/加强的用例：

- `FlowControllerBatchTest`（13 个）：同批次同用户/同隧道只产生一条 UPDATE；不同转发同用户仍只写一次；转发与隧道查询各自只查一次；已删除转发只查一次；增量按实体累加；倍率与单双向仍生效；限额仍由跨线的那条消息触发；管理员转发（`userTunnelId=0`）不检查限额；密钥不存在时忽略；零增量不产生 UPDATE；`web_api` 跳过；200 条消息 = 202 条 UPDATE。
- `JwtUtilTest`（7 个，新增）：正常解析、重复解析稳定、篡改 payload 失败、过期 token 失败、缓存不延长过期、格式错误拒绝、多用户不串号。

### 真实栈验证（Docker + nginx + 真实登录）

`deploy/docker-compose.yml` 重新构建镜像后：

- 后端容器 `healthy`，前端 nginx 200；
- Playwright 真实登录（admin_user）后逐页访问 `/dashboard`、`/flow-ranking`、`/forward`、`/node`、`/tunnel`、`/user`：全部渲染，**0 console error、0 HTTP 4xx/5xx**，懒加载分包按需加载；
- `POST /flow/upload?secret=perfsecret` 上报 `u=100000,d=200000`（隧道 flow=2 双向）→ 转发计数器 `in_flow=400000, out_flow=200000`（与旧实现换算一致）；
- `POST /api/v1/flow-rate/current` → `totalRate=60000, inRate=40000, outRate=20000, inPercent=66.67`；
- `POST /api/v1/forward-flow/ranking {"range":"today"}`（请求字段名是 `range`，不是 `rangeCode`）→ `code=0`，`range=today`、`startTime=2026-10-26 00:00`、`sampleIntervalMinutes=5`、`retentionDays=31`、`trafficScope=in+out`。

---

## ⑤ 风险与回滚

| 风险 | 说明 | 缓解 |
|------|------|------|
| 限额触发时机偏移 | 批次化后若直接按「批次末值」判断，暂停会提前到第 1 条消息 | 已用「批次末值 − 本批增量」还原逐条语义，并有专门用例锁定 |
| 同批次同实体多消息的增量方向 | 入站/出站分别累加，互不影响 | 用例 `appliesTrafficRatioAndFlowDirection` + 真实栈实测 |
| JWT 缓存被伪造 token 污染 | 只有签名+有效期都通过才写缓存，键是完整 token 字符串 | 用例 `rejectsTamperedPayload`、`rejectsExpiredToken` |
| 缓存内存 | 512 条 LRU，单条约 1 KB 量级 | 上限硬编码，超出自动淘汰 |
| 前端懒加载首屏白屏 | `Suspense` 放在布局内部，只占内容区 | 真实栈 6 个路由逐页验证无闪烁、无报错 |
| `treeshake:false` 未改动 | 保持上游行为，避免副作用被裁掉 | 本次只开 minify |
| 回滚 | 改动集中在 4 个后端文件 + 2 个前端文件，无表结构/接口变更 | `git revert` 对应提交即可，无需数据迁移 |

**部署影响**：无数据库迁移、无配置项变更、无接口变更。升级只需替换后端 jar 与前端 dist（或重新拉镜像）。

---

## 附：发布镜像端到端验证（2026-10-26 12:40–12:53）

用 Docker Hub 上的镜像（`ahmadplayz721/flux-panel-*:2.0.7-beta`，CI 构建的 amd64+arm64 双架构）在干净卷上全新部署，验证发布产物本身可用：

| 检查项 | 结果 |
|--------|------|
| 拉取并启动 | 后端 `healthy`，前端 nginx 200，`forward_flow_statistics` 表自动建好 |
| 真实登录 + 页面 | `admin_user` 登录后 `/dashboard`、`/flow-ranking`、`/forward`、`/node`、`/tunnel`、`/user` 全部渲染，**0 console error / 0 HTTP 4xx-5xx** |
| 镜像内代码 | 后端 jar 含 `JwtUtil.PARSED_CACHE/VERIFIED_CACHE`、`FlowController.BatchContext/STRIPES`、`NodeMapper.existsBySecret`；前端 22 个分包、首屏 `index.js` 1.19 MB |
| 5 分钟采集 | 12:45 写基线（计数器 1200000/600000，增量 0，被排行过滤）；12:50 写增量 **in 1000000 / out 500000**，`total_flow=3300000` |
| 排行接口 | `today` → 总量 **1500000**；`3d` 起点 2026-10-24、`7d` 起点 2026-10-20、`30d` 起点 2026-09-27，均为 00:00（Asia/Shanghai） |
| 排行页 | 显示 1.43 MB（入站 976.56 KB / 出站 488.28 KB）、采集点 2、最近采集 12:50，见 [ranking-published-image.png](ranking-published-image.png) |

> 请求字段名是 `range`（`{"range":"3d"}`），不是 `rangeCode`。
