# 踩坑与解法（端口流量排行榜 / flux-panel 2.0.7-beta）

> 每条格式：**现象 → 根因 → 解法 → 是否通用**。通用解法已沉淀进 skill `legacy-app-feature-delivery`。

## A. 环境与工具链

1. **工作区是空的，`ls` 什么都没有**
   根因：任务只给了仓库地址。解法：先 `git clone <URL> .`；再看 `git log/tag` 决定基线。
   通用：是（但"基线"值要替换）。

2. **默认分支 ≠ 目标版本**
   根因：`main` 比 tag 多了 README/安装脚本等提交。若在 main 上做，交付物与目标版本不一致。
   解法：`git checkout -B feat/<x> <TARGET_TAG>`，并在总结里注明基线 tag 与 commit。
   通用：是。

3. **没有 `java` / `mvn`**
   根因：精简镜像。解法：下载 Temurin 21（`api.adoptium.net`）与 Maven 二进制到 `/opt/tools`，导出 `JAVA_HOME` + `PATH`。
   通用：是（路径可换）。

4. **Maven 3.9.9 在 `dlcdn.apache.org` 返回 404（下载到 196 字节 HTML）**
   根因：`dlcdn` 只保留最新版本，且 `curl` 默认不因 404 失败，导致 `tar` 报 "not in gzip format"。
   解法：改用 `https://archive.apache.org/dist/maven/maven-3/...`；下载后 `ls -la` 校验体积。
   通用：是。

5. **`mvn -o dependency:go-offline` 报一堆 `[ERROR] UnresolvableModelException`，但随后 `compile` 成功**
   根因：离线模式下部分插件/BOM 未缓存；真正的构建并不需要它们。
   解法：以 `mvn -B compile` / `test` 的结果为准，别被 go-offline 的噪音误导。
   通用：是。

6. **`@SpringBootTest` 启动失败：`Could not resolve placeholder 'LOG_DIR' in value "${LOG_DIR}"`**
   根因：logback 在 Spring Environment 完成装配之前读取 `logback-spring.xml` 的 `<springProperty source="log-dir">`，此时 `@DynamicPropertySource` 还没生效。
   解法：新增 `src/test/resources/logback-test.xml`（纯 logback 配置，不依赖占位符）；同时给 surefire 配 `<environmentVariables>`（`JWT_SECRET`/`LOG_DIR`/`DB_PATH`），让 `mvn test` 开箱即跑。
   通用：是（Boot 项目测试通用配方）。

7. **应用启动失败：`java.io.IOException: Problem reading font data`（`fonts/SIMSUN.TTC`）**
   根因：容器缺 **fontconfig/系统字体**，JDK 的 `Font.createFont` 直接抛错；不是代码问题（项目 Dockerfile 本来就 `apt install fontconfig fonts-dejavu`）。
   解法：`apt-get install -y --no-install-recommends fontconfig fonts-dejavu`，再启动即正常。**不要为此改业务代码或删资源。**
   通用：是（Java + AWT/验证码项目）。

8. **`pkill -f "admin-0.0.1-SNAPSHOT.jar"` 把自己的 shell 杀了（SIGTERM）**
   根因：`pkill -f` 匹配整条命令行，而当前 bash 的命令行里就含有这个字符串。
   解法：`ps -eo pid,args | grep "[a]dmin-0.0.1-SNAPSHOT.jar" | awk '{print $1}'` 取 PID 再 `kill`；或用 `[a]dmin` 技巧避免自匹配。
   通用：是。

## B. 数据与统计口径

9. **不知道该统计"端口"还是"转发"**
   根因：项目只有 `forward.in_flow/out_flow`（按转发累计），`forward_port` 只是节点↔端口的映射；gost 服务名也是 `forwardId_userId_userTunnelId_tcp`。
   解法：以转发为统计单元，展示其入口端口；多入口端口展示全部、不拆分流量，并把这个限制写进交付说明。
   通用：否（每个项目要先确认计数器粒度）。

10. **首次采集如果按"当前累计值"作为增量，会把上线前的历史全算进"今天"**
    根因：计数器是历史累计，不是区间增量。
    解法：首次只写基线（`in_flow_delta=0`），之后一律"当前 − 上次落库值"。
    通用：是。

11. **每个端口每 5 分钟无条件写一行，30 天保留会膨胀到百万行**
    根因：100 端口 × 288 行/天 × 31 天 ≈ 89 万行。
    解法：仅在「首次基线」或「总增量 > 0」时落库；采集照常每 5 分钟跑。**基线必须写**，否则下一次差额会把历史再算一遍。
    通用：是。

12. **删除转发后历史查不到名称/端口**
    根因：`deleteForward` 会同时删 `forward` 与 `forward_port`。
    解法：统计行冗余 `forward_name / port / ports / node_id / tunnel_id / user_id / user_name` 快照；查询时"当前记录优先、快照兜底"，并返回 `deleted` 标记。
    通用：是（任何"历史统计 + 可删除主体"的场景）。

13. **排行榜尾部一堆 0 B 条目**
    根因：基线行 `flow=0` 也会被 `GROUP BY` 聚合出来。
    解法：聚合后过滤 `totalFlow <= 0`（同时修正 `forwardCount/portCount` 的口径）。
    通用：是。

14. **"最近采集时间"只在有流量时才更新，看起来像任务挂了**
    根因：零增量不落库。
    解法：服务内用 `volatile lastCollectTime` 记录每次运行时间，展示时取 `max(内存值, DB 最大值)`；重启后的空窗期以内 DB 值为准。
    通用：是。

15. **时区不能靠 `LocalDateTime.now()` 的默认值**
    根因：容器通过 `-Duser.timezone=Asia/Shanghai` 指定，测试/本地可能不同。
    解法：区间解析统一 `LocalDate/Instant.atZone(zone)`，`zone = ZoneId.systemDefault()` 只在服务入口取一次，纯函数接收 zone 参数便于测试。
    通用：是。

## C. 测试与构建

16. **MyBatis-Plus 的 `saveBatch` 在纯 Mock 单测里跑不通**
    根因：`saveBatch` 需要真实 `SqlSessionFactory` 开批处理会话。
    解法：采集用 `mapper.insert(...)` 逐条写；测试里 mock mapper 即可断言；批量优化留给后续。
    通用：是。

17. **多个 `@SpringBootTest` 共用一个 SQLite 文件会互相污染**
    根因：表数据跨测试残留，聚合断言随机失败。
    解法：`@DynamicPropertySource` 里用 UUID 生成临时 DB 路径（每次上下文创建都是新库），并让 `schema.sql` 自动建表。
    通用：是。

18. **测试断言数值算错（把 1030 写成 530）**
    根因：手工累加 3 条聚合行时漏算。解法：断言前先列表算一遍，或用变量表达预期值；不要写 `assertFalse(Boolean.FALSE)` 这类占位断言。
    通用：是。

19. **集成测试里采集用真实系统时间，和固定的 `now` 混用会污染断言**
    根因：`collectStatistics()` 内部用 `System.currentTimeMillis()`。
    解法：手动插入的样本用固定时间戳，断言按 `forwardId` 过滤；不要断言全局 totals 或列表长度。
    通用：是。

20. **`tsc` 阶段就暴露类型问题**
    根因：`npm run build` = `tsc && vite build`，`noUnusedLocals`、`isolatedModules` 都开着。
    解法：类型导入写 `import type`；未用变量删掉；联合类型显式声明（如 `ForwardFlowRange`）。
    通用：是。

## D. 前端与交互

21. **想要范围切换 Tab，但 `@heroui/tabs` 没装**
    根因：`package.json` 依赖有限。解法：用 `Button` 组（`variant` 随选中态切换），不引入新依赖（任务要求保持依赖不变）。
    通用：是（先查依赖再选组件）。

22. **快速点范围按钮时，旧响应可能覆盖新响应**
    解法：`const requestIdRef = useRef(0)`，每次请求 `++requestIdRef.current`，回来时比对，过期直接丢弃；`finally` 里也只在最新请求时清 loading。
    通用：是。

23. **eslint 全仓 `--fix` 会产生大量无关 diff**
    根因：仓库本身有数百条 prettier 告警。解法：只对改动文件跑 `npx eslint <files>`，只看 `severity=2`；确认新增 error 为 0（本次唯一 error 是 `admin.tsx` 原有的移动端遮罩 `div onClick`）。
    通用：是。

## E. 验证方法

24. **想验证 5 分钟调度，直接等等不到结果**
    解法：把等待放进单条 bash（`sleep 280`）或用后台作业；每步只查一次表/日志。总计需要 2~3 个采集边界（约 15 分钟）。
    通用：是（把 `<N>` 换成实际间隔）。

25. **接口冒烟需要 JWT，但没有现成登录密码**
    解法：用 Python 复刻签名：`base64url(header) + "." + base64url(payload)`，`HMAC-SHA256(secret=JWT_SECRET)` 再 base64url；payload 需含 `sub/iat/exp/role_id`。role_id=0 为管理员。
    通用：是（换成项目的签名算法）。

26. **每日清理任务无法即时验证**
    解法：用集成测试覆盖 `cleanupExpiredStatistics` 的截止时间与删除效果；真实环境只在日志里确认首次执行（03:10）。
    通用：是。

27. **重启验证必须真的重启进程**
    根因：内存里的 `lastCollectTime`、连接池、缓存都可能掩盖问题。解法：kill 进程 → 重启 → 查接口/表 → 再等一个采集边界确认只补差额。
    通用：是。

## F. 部署与镜像

28. **`docker build` 前端镜像时把本地 447MB `node_modules` 打进去**
    根因：仓库没有 `.dockerignore`，`COPY . .` 覆盖容器内安装结果；跨平台构建还会因原生二进制不兼容失败。
    解法：加 `vite-frontend/.dockerignore`（`node_modules/`、`dist/`）与 `springboot-backend/.dockerignore`（`target/`）；或从干净 clone 构建。
    通用：是。

29. **官方 compose 固定 `172.20.0.0/16` 子网，和已有容器网络可能冲突**
    根因：`gost-network` 写死了 ipam。
    解法：自用部署用普通 bridge 网络（`deploy/docker-compose.yml` 已去掉 subnet）；需要固定子网再显式加。
    通用：是（多容器宿主机）。

30. **`docker compose build` 并行构建后端+前端，小内存机器容易 OOM**
    根因：Maven 与 Node 同时跑。
    解法：`docker compose build backend && docker compose build frontend` 逐个构建，或临时停掉其他容器。
    通用：是。

31. **镜像离线传递：`docker save` 出来的 tar 只有 201MB**
    根因：层去重后远小于 `docker images` 显示的 590MB+109MB。
    解法：`docker save` → 传 tar → `docker rmi` → `docker load` → `docker compose up -d`（**不要** `--build`，否则会重新构建）。
    通用：是。

32. **健康检查依赖容器内 `wget`**
    根因：官方 compose 的 healthcheck 用 `wget --spider`；`eclipse-temurin:21-jre` 镜像里通常有。
    本次实测 healthy；若基础镜像换 alpine/精简版，改成 `curl` 或 `nc`。
    通用：是（换基础镜像时注意）。

## G. 哪些是通用的，下次要换什么

| 步骤/内容 | 通用性 | 下次替换为 |
| --- | --- | --- |
| 侦察顺序（schema→定时任务→统计实现→计数器写入点→controller→页面） | 通用 | — |
| 幂等 DDL 建表 + 索引 | 通用 | 表名、字段 |
| "首次基线 + 相邻差额 + 单边回退 + 缺失补齐"算法 | 通用 | 计数器字段名（`<in>/<out>`） |
| 零增量不落库 + 基线必写 | 通用 | — |
| 快照字段防删除 | 通用 | 需要快照的展示字段 |
| 区间解析纯函数 + 时区入参 | 通用 | 区间枚举值、天数 |
| 三类测试分层 | 通用 | 类名、场景阈值 |
| MockMvc 接口契约（200/403/401/非法/缺省） | 通用 | 路径、角色码 |
| 真实冒烟六步（基线→差额→回退→删除→重启→接口） | 通用 | 采集间隔 `<N>`、JWT 算法 |
| Docker 本地闭环（build → up → 容器内造数 → 等采集 → 接口 → save/load） | 通用 | 镜像名、端口、健康检查路径 |
| Playwright 截图取证（登录态注入、暗色、CJK 字体、裁剪） | 通用 | token 生成方式、页面路径、截图区域 |
| 仓库地址 / 目标 tag / 分支名 | **替换** | `<REPO_URL>` `<TARGET_TAG>` `feat/<FEATURE>` |
| 统计单元与端口语义 | **替换** | 先读代码确认（转发/端口/用户…） |
| API 路径、页面路径、菜单文案 | **替换** | `/api/v1/<x>/<y>`、`/<page>` |
| 采集间隔、保留天数、清理时间 | **替换** | `<N>` 分钟、`<D>` 天、`<CRON>` |
| 流量方向与倍率口径 | **替换** | 以项目现有计费实现为准 |
| 环境路径（JDK/Maven 位置） | **替换** | 目标机器的实际安装路径 |

## H. 实时指标（新增）

33. **实时速率不能靠"查最近两条采集记录"算**
    根因：5 分钟采样太粗，除以间隔得到的是平均值而非实时值。
    解法：在流量上报入口做内存滑动窗口（窗口取上报间隔 2 倍，本项目 10 秒），按窗口内字节数 / 窗口时长换算；不落库。
    通用：是。

34. **轮询接口加了访问日志注解会刷爆日志**
    根因：仪表盘 2 秒轮询一次，`@LogAnnotation` 每次都记。
    解法：轮询型只读接口不挂日志注解；只给写操作/低频操作挂。
    通用：是。

35. **前端 2 秒轮询的三个必要细节**
    根因：慢请求会叠加、切后台仍打请求、失败清空界面会闪。
    解法：`inFlight` 去重、`document.visibilityState` 暂停、失败保留上一次数值。
    通用：是。

36. **实时窗口测试不要用 sleep**
    根因：等 10 秒太慢且不稳定。解法：给 tracker 提供带 `timestamp` 参数的重载（包内可见），单测直接构造时间线。
    通用：是。

37. **实时数据要有清理**
    根因：转发删除后内存桶会一直留着。
    解法：`@Scheduled(fixedDelay = 60s)` 清理超过保留期（窗口 12 倍）未上报的桶；测试用可注入时间的清理重载。
    通用：是。

## I. 截图与视觉验证（新增）

38. **Playwright 版本与浏览器缓存不匹配**
    现象：`chromium.executablePath()` 指向 `chromium-1129`，但机器缓存里只有 `chromium-1148`，启动报找不到可执行文件。
    解法：`chromium.launch({ executablePath: '/root/.cache/ms-playwright/chromium-1148/chrome-linux/chrome', args: ['--no-sandbox'] })`；或 `npx playwright install chromium` 下对应版本。
    通用：是。

39. **设置 localStorage 主题不生效，截图还是浅色**
    根因：项目的 ThemeProvider **始终跟随系统偏好**（`prefers-color-scheme`），会覆盖 localStorage 的 theme。
    解法：Playwright `newContext({ colorScheme: 'dark' })`；不要指望只写 localStorage。
    通用：是（跟随系统的主题实现）。

40. **中文全部变成方块**
    根因：环境只有 DejaVu，没有 CJK 字体。解法：`apt-get install -y --no-install-recommends fonts-noto-cjk`，`fc-list | grep -ci cjk` 应 > 0。
    通用：是。

41. **截图需要登录态，但登录有验证码**
    解法：用 `JWT_SECRET` 在 Python 里复刻 HMAC-SHA256 token，Playwright `context.addInitScript` 写入 `token/role_id/admin/name`；不要为了截图去关闭验证码。
    通用：是（按项目的签名算法替换）。

42. **改了代码但截图还是旧的**
    根因：源码修改晚于 `docker build` 开始，镜像里是旧代码；或 `npm run build` 没重跑。
    解法：对比 `源文件 mtime`、`dist mtime`、`docker images --format '{{.CreatedAt}}'`，确认后重建镜像并 `up -d` 重建容器。
    通用：是。

43. **工具调用被中断（结果未知）时先核状态，不要盲重跑**
    核什么：源文件是否已改（grep/mtime）、产物是否重建、容器/镜像是否更新。
    通用：是。

44. **截图数据必须标注来源**
    本次截图里的流量/端口/用户都是脚本造的，文档里明确写“模拟数据”，避免被当成线上真实数据。
    通用：是。

45. **嵌套 heredoc 被 shell 提前截断，后半段被当成命令执行**
    现象：用 `bash -c "python3 - <<EOF ..."` 改文档，脚本里又写了 `cat > .env <<EOF ... EOF`；内层 `EOF` 提前结束了外层 heredoc，剩余内容被 shell 执行（本次误跑了上游 `install.sh`、覆盖了仓库里的 `install.sh`、生成了一个 `docker-compose.yml`）。
    解法：外层用唯一分隔符（`PYEOF` / `DOC`）或**把脚本写成文件再执行**；执行前用 `grep -n '^EOF$' 文件` 自检。
    通用：是。

46. **误执行外部脚本后的恢复流程**
    先 `git status` 找被覆盖/新增的文件 → `git checkout -- <file>` 还原、`rm` 掉生成物 → 检查常驻服务是否被重启（`ps -o lstart -p <pid>`、`systemctl status <svc>`）→ 确认配置 mtime 未变。
    通用：是。

47. **统一响应包装会把 HTTP 状态码"抹平"，用 `%{http_code}` 判鉴权会误判**
    现象：本项目所有异常由 `@RestControllerAdvice` 包成 `R{code,msg,ts,data}`，**HTTP 状态恒为 200**；未登录/无效 token 只是响应体 `code=401`。用 `curl -o /dev/null -w '%{http_code}'` 探测，会得到 200 而误以为"接口没鉴权"。
    解法：校验时看**响应体**（`python3 -c 'json.load(...)["code"]'`）或断言 `$.code == 401`；只有"路由不存在"才是真的 HTTP 404，可拿它做对照。
    通用：是（任何统一返回体的后端）。

48. **CI 用"标签是否存在"决定要不要构建 = 静默发布旧镜像**
    现象：`if git rev-parse "$VERSION"; then should_build=false`，一旦标签推到仓库，后续 push 全部跳过构建，流水线仍显示 success，`docker pull` 拿到的是旧镜像，且没有任何报错。
    解法：判定条件改为"源码相对标签是否有差异"（`git diff --quiet $TAG HEAD -- <源码目录>`），差异存在就重建；本地用 `tag==HEAD` 与 `tag!=HEAD` 两条分支各验证一次。验证线上时别只看绿灯，要 `docker pull` 后核对产物（类/JS 片段/路由/建表）。
    通用：是。

49. **纯文档提交也会触发镜像重建，白烧 CI 时间**
    现象：多架构构建约 10 分钟，改一行 README 就重建一次。
    解法：`on.push.paths-ignore: ['docs/**', '**.md']`，需要时用 `workflow_dispatch` 手动跑。注意：**本次若改了 workflow 文件本身，paths-ignore 不会生效**，仍会跑一轮。
    通用：是。

50. **给容器做临时验证时，别忘了 compose 里挂的卷**
    现象：`docker run` 起后端镜像直接 `SQLException: path to '/app/data/gost.db': '/app/data' does not exist`——因为 compose 用 `sqlite_data:/app/data` 建了目录，裸 `docker run` 没有。
    解法：验证时显式 `-v /tmp/xxx:/app/data -e DB_PATH=/app/data/gost.db -e LOG_DIR=/app/logs -e JWT_SECRET=...`；用完 `docker rm -f` + 删临时目录。
    通用：是。

51. **只写 `[::]:PORT:PORT` 的端口映射是"纯 IPv6"，不是双栈**
    现象：`docker run -p "[::]:18080:80"` 后 `ss` 只有 `[::]:18080`，`curl http://127.0.0.1:18080/` 返回 000（连不上），而 `curl http://[::1]:18080/` 正常。
    解法：双栈要写两条映射 —— `0.0.0.0:PORT:容器端口` + `[::]:PORT:容器端口`；验证用 `ss -lntp | grep PORT` 看是否同时出现 `0.0.0.0:` 与 `[::]:`。
    通用：是。

52. **自定义 nginx.conf 覆盖主配置后，基础镜像的 `listen [::]:80` 就失效了**
    现象：镜像里 `/etc/nginx/conf.d/default.conf` 明明有 `listen [::]:80;`，但容器内 `cat /proc/net/tcp6` 无监听、`wget http://[::1]:80/` 被拒；宿主 IPv6 访问 `Connection reset by peer`，IPv4 正常。
    根因：`COPY nginx.conf /etc/nginx/nginx.conf` 换掉了主配置，而它**没有 `include /etc/nginx/conf.d/*.conf;`**，所以 conf.d 里的 IPv6 监听根本没加载；自己的 `server { listen 80; }` 只绑定 IPv4。
    解法：在自有 server 块里补 `listen [::]:80;`，`nginx -t && nginx -s reload` 验证（容器内 `wget http://[::1]:80/` 返回 200）。
    通用：是（任何用自定义主配置覆盖 nginx 镜像的场景）。

53. **判断"是不是 IPv6 通了"要分层验证，不要只看宿主端口**
    分层：① 宿主 `ss -lntp` 有 `[::]:端口` → ② 宿主 `curl http://[::1]:端口/` 通 → ③ 容器内 `wget http://[::1]:容器端口/` 通 → ④ 面板里填 `[IPv6]:端口` 生成节点命令。
    本次故障正是卡在 ③：宿主映射有了，容器内 nginx 没监听 IPv6，表现为 reset 而不是 timeout，容易误判成防火墙。
    通用：是。

## K. 转发链与诊断的坑（2026-10-25 补）

| # | 现象 | 根因 | 处理 | 通用性 |
|---|---|---|---|---|
| 47 | 面板「转发诊断/隧道诊断」全部显示成功、延迟正常，但实际端口不通 | `performTcpPingDiagnosis` 在节点只回 `OK` 却没带探测数据、或数据解析失败时，直接 `setSuccess(true)`；诊断测的是**数据库里的端口**，不是节点上实际生效的端口 | 抽 `TcpPingDiagnosisRunner`：只有节点明确回 `success=true` 才算连通，其余如实报失败 | 是——任何"面板显示正常但实际不通"都要先怀疑诊断本身在说谎 |
| 48 | 节点上残留旧转发链，面板改端口后节点仍拨旧端口（日志 `hop_15 → [2400:...]:58781 connection refused`） | `GostUtil.AddChains` 把 gost 返回的 `chain xxx already exists` 直接改写成 `OK` 吞掉，节点侧旧链不覆盖 | 改为：检测到 `already exists` 就下发 `UpdateChains` 覆盖；返回 `not found` 则退回 `AddChains` | 是——"幂等写"接口里把 exists 当成功，等于放弃了更新 |
| 49 | 同一跳多个节点名重复 | `node.put("name", "node_" + chainTunnel.getInx())`，入口/出口的 `inx` 为 null → 全部叫 `node_null` | 节点名改为 `node_<下标>_<c{inx}\|n{nodeId}>` | 是——用可空字段拼唯一键，必然踩 |
| 50 | 节点日志 `dial tcp x.x.x.x:6365: connect: no route to host` | 不是端口拒绝（那是 `connection refused`），是网络层不可达：安全组/防火墙 reject、或该 IP 已不指向面板 | 先确认面板公网 IP 是否变化，再查安全组 6365、`ufw`/`iptables` | 是——`refused` 查服务，`no route to host` 查网络与 IP |

## J. 下次的 30 分钟快速通道

1. 钉输入（仓库/tag/单元/口径）→ clone + 建分支（5 分钟）
2. 读 6 类文件，写下一句"口径结论"（10 分钟）
3. 先写纯函数 + 单测（差额、区间），跑通（5 分钟）
4. 建表 → mapper → service → task → controller，`mvn compile`（5 分钟）
5. 集成测试 + MockMvc，`mvn test`（5 分钟）
6. 前端 api/页面/路由/菜单，`npm run build`
7. 真实冒烟（等 2 个采集边界）→ 写交付说明
8. 用 Playwright 在真实栈上截 1~2 张图（暗色 + 关键页面），随交付附上

## L. 实时速率排行与前端渲染的坑（2026-10-26 补）

| # | 现象 | 根因 | 处理 | 通用性 |
|---|---|---|---|---|
| 51 | 实时速率卡只能看到「最忙端口」一条，看不到前几名 | 接口只返回 `topForward` 单条 | 新增 `topForwards` 列表（保留 `topForward` 兼容旧前端），前端渲染 Top10 | 是——增量字段优于改字段 |
| 52 | 给 Top10 补名称/端口时，若沿用 `getById` + `list(eq(forward_id))`，2 秒轮询会变成每轮 20 次查询 | 逐条查询 = N+1 | 改成一次 `listByIds` + 一次 `in("forward_id", ids)` 批量查，再在内存里 group | 是——**轮询接口里任何按条查询都要先看会不会 ×N** |
| 53 | 端口排行里同一转发有多个入口端口时，排名重复出现 | 每条转发取第一个端口即可代表 | 端口用 `putIfAbsent` 取最小 id 的那个 | 是 |
| 54 | Playwright 用 `page.locator('div').filter({hasText})` 截图，只截到标题那一行（2076×32） | `filter` 命中的是最小包裹层，`.last()` 往往是最内层文本容器 | 用 `page.evaluate` 找出**同时包含两个特征文本、且面积最小**的祖先元素，取 `getBoundingClientRect()` 再 `page.screenshot({clip})` | 是——组件截图别靠层级猜，靠内容+面积算 |
| 55 | `npm run build` / `mvn package` 这类长命令在前台被中断，且结果未知 | 会话/工具中断，前台进程随之被杀 | 用 `setsid nohup ... > /tmp/x.log 2>&1 < /dev/null &` 起后台进程，再轮询日志与产物 | 是——长构建一律脱离前台 |
| 56 | 截图时实时速率是 0，接口返回空排行 | 10 秒滑动窗口早过了，喂进去的样本已过期 | 起一个**持续喂数据**的循环（每 2 秒上报一次）再截图 | 是——窗口型指标截图必须先起喂数循环 |
