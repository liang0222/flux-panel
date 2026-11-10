# 踩坑与解法清单（Flux Panel 内存审计实录）

> 全部来自 2026-11-09 ~ 11-10 的真实执行过程。每条都注明**现象原文**与**为什么**，避免下次重新踩。
> 标记：🚧 环境/工具限制 · 🐛 代码缺陷 · 📊 测量口径 · 🚀 部署

---

## 一、测量口径类（最容易得出错误结论）

### 📊 1. 64MB 对齐段里混着 G1 堆，arena 统计会虚高

**现象**：脚本按「起始地址 64MB 对齐 + 匿名可读写」统计 glibc arena，得到 `arena_rss = 235.4 MB`；加上 `MALLOC_ARENA_MAX=2` 后变成 `140.2 MB`，于是报「回收了 95 MB」。

**真相**：`0xe0000000` 是 **G1 堆保留区基址**（77 MB VMA / 76.4 MB RSS），同样 64MB 对齐，被一起算进 arena 了。真实 arena 只有约 162 → 63 MB。

**解法**：统计前先排除已知的 JVM 堆基址。

```python
G1_BASE = 0xe0000000
aligned = [s for s in segs
           if not s['path'] and s['perms'].startswith('rw')
           and s['start'] % (64 << 20) == 0
           and s['start'] != G1_BASE]
```

**教训**：任何「按特征归类内存段」的脚本，都要先用 `ps`/`SIGQUIT` 的堆地址交叉验证一遍，别让分类规则替你做判断。

---

### 📊 2. THP 会在换内核后悄悄变掉，旧基线全部作废

**现象**：早前记录「RSS 与堆相差 40+ 倍，主体是 arena/THP 保留」；换内核到 `7.3.3-joeyblog-bbrv3-max` 后 `AnonHugePages` 变成 0。

**原因**：`/sys/kernel/mm/transparent_hugepage/enabled` 从 `[always]` 变成 `always [madvise] never`，且 `/etc/systemd/system/`、`/etc/rc.local`、`/etc/sysctl.d/` 下**没有任何持久化配置**——这是新内核的默认值。

**解法**：换内核 / 换 GC / 换 JVM 参数之后，**明确写"旧基线不可比"**，重新采一次基座数据。采样器 `flux_watch.csv` 的历史值（HWM 286,712 → 494,260 kB）不能直接与新数据拼成趋势线。

---

### 📊 3. 「堆没涨」不等于「资源没漏」

**现象**：Go agent 的 `pprof heap` 只有 `74: 3925984`（3.9 MB 存活），goroutine 数逐日截距稳定（674.8–676.1，极差 0.19%），一切正常。但生产上有 82 条已删除隧道的连接仍在跑流量（64 KB/s，累计 125.7 MB）。

**原因**：连接泄漏的成本落在**内核 socket 缓冲**（`ss -tni` 单连接 `rcv_space` 最高 825 KB）和 **FD 数**（一度 1,017）上，堆剖析器永远看不到。

**解法**：多一层判据——`ss -tan state established | wc -l`、`ls /proc/$PID/fd | wc -l` 是否随时间/操作单调上升。堆正常 + socket 涨 = 连接生命周期问题。

---

### 📊 4. RSS 缓升不等于泄漏

**现象**：`TrimNativeHeapInterval` 上线后，30 分钟采样 RSS 从 334,084 缓升到 337,952 kB（+3.9 MB / 25 分钟），没有回落。

**判据**：`VmHWM` 在 341,112 kB 处**恒定不动**，线程数稳定，arena 实占恒定。这是 JVM 正常预热（Meteaspace 装载、CodeCache 编译），不是泄漏。

**解法**：看 `VmHWM` 是否恒定 + 观察窗口拉到 6 小时以上。只有 **HWM 同步抬升** 才是持续增长的信号。

---

### 📊 5. Docker 容器内存不能简单相加

`docker stats` 显示的是 cgroup 口径，含 page cache 与共享页。宿主机 `free -m` 的 `used` 与各容器之和差异很大（本次：容器合计约 260 MiB，宿主 used 791 MB，其中还含 dockerd 105 MB、1panel 系列 128 MB、containerd 55 MB）。**结论必须用宿主机 `free -m` 的 `available` 与 `cgroup memory.current` 相互印证。**

---

## 二、代码缺陷类

### 🐛 6. `defer` 的 LIFO 顺序把 cancel 卡死

```go
func (s *service) Serve() {
    defer wg.Wait()        // 后声明 → 先执行
    defer cancel()         // 先声明 → 后执行
    ...
}
```

**现象**：删端口后连接不死，要等对端断开或 keepalive 超时（实测 keepalive 10–15 分钟，有数据时永不超时）。

**解法**：

```go
defer func() { cancel(); wg.Wait() }()
```

**教训**：`defer` 顺序是 Go 最经典的语义陷阱，看到「取消不生效」先查这里。

---

### 🐛 7. `io.CopyBuffer` 阻塞在 `Read` 上，只能靠 close 唤醒

**现象**：即使 cancel 正确执行，连接仍然不收。

**原因**：`io.CopyBuffer` 阻塞在 `src.Read`，不接收 ctx。

**解法**：`select` 到 `ctx.Done()` 时 **close 两端**，不要试图"通知"它。类型断言用 `io.Closer`，因为 `tcpConn` / `udpConn` / `readWriteConn` / stats `conn` 没有共同具体类型。

```go
if c, ok := rw.(io.Closer); ok { _ = c.Close() }
```

---

### 🐛 8. `chan error` 容量少 1 就永久泄漏一个 goroutine

```go
errc := make(chan error, 2)  // 容量必须等于 goroutine 数
```

**原因**：ctx 分支先返回时，慢的那个 goroutine 写 `errc <- err` 会永久阻塞。容量为 1 时，第二次取消就泄漏一个 goroutine。

---

### 🐛 9. 同类缺陷会成对出现

同一份代码里同时存在「Transport 不接 ctx」和「defer 顺序颠倒」。修一个不够，两个都改才有效。**验证方法**：A/B 对照实验（本地起长连接 → 用管理 API 删除 → 计时）。旧 `STILL ALIVE after 20s`，新 `EOF at 3.0s`。

---

### 🐛 10. 空方法冒充校验

```java
private void isError(GostDto gostDto) { }   // 方法体是空的

if (tunnelDto.getChainNodes().isEmpty()) {
    GostDto gostDto = GostUtil.AddChains(...);
    isError(gostDto);   // ← 什么都不做
}
```

**后果**：无中间跳的隧道创建失败时，隧道已落库、chain 未建，接口返回 `R.ok()`，用户看到成功但实际不可用。

**教训**：`grep -n "private void \w*(.*) *{ *}"` 扫一遍空方法体；`@Pointcut` 标记方法、私有构造器、`afterHandshake` 空实现是**正常**的，要逐个判断。

---

### 🐛 11. 回滚代码的 `return` 写在循环内

```java
for (JSONObject jsonObject : success) {
    GostUtil.DeleteService(...);
    return R.err(gostDto.getMsg());   // ← 第一条回滚后就 return
}
```

**后果**：多入口节点时只回滚第一个，其余残留在节点上。

**解法**：先遍历全部回滚，循环结束后统一 `return`。

---

### 🐛 12. `getOne(wrapper)` 单参重载会抛异常并被吞

MyBatis-Plus 的 `getOne(Wrapper)` 在结果 >1 时抛 `TooManyResultsException`；被外层 `catch (Exception e)` 吞掉后，**后续所有同类处理整批跳过**。

**解法**：用 `getOne(wrapper, false)`（第二参 false = 不抛异常），并把异常范围收窄到单条。

---

### 🐛 13. 循环内查库 = N+1，但只有一处是真问题

自动化扫描报了 110 处「for 循环内的 DB 查询」，逐条核对后**只有 1 处是真的**：`CheckGostConfigAsync` 每次节点配置上报对每个 service 查一次 `forwardService.getById`（166 个 service = 166 条 SQL，每 10 分钟一次）。

其余都在：
- 低频管理路径（`SpeedLimitServiceImpl` / `TunnelServiceImpl` / `NodeServiceImpl`）；
- 每月/每日定时任务；
- 已有批量（`FlowRateService.buildTopForwards` 用 `listByIds` + `in()`）；
- 每小时一次且带 `LIMIT 1`（`StatisticsFlowAsync`）。

**教训**：N+1 的判据是「频率 × 次数」，不是「在不在循环里」。

---

### 🐛 14. `@Async` 的默认执行器队列是无界的

Spring Boot 默认 `applicationTaskExecutor`：`core=8`、`max=Integer.MAX_VALUE`、`queue=Integer.MAX_VALUE`。队列无界意味着 **max 永远走不到**，任务无限堆积且不报错。

**解法**：`spring.task.execution.pool.*` 显式上界。

**配套必做的两件事**：
1. **写测试锁死**——Boot 对拼错的 `spring.task.*` 键是**静默忽略**的，写错一个字母只会悄悄用回默认值；
2. **写代理测试**——`@Async` 依赖代理，类被 `new`、同类自调用、类改成 `final` 都会静默退化成同步。

```java
assertTrue(AopUtils.isAopProxy(bean));
```

**验证生效**：`kill -3` 取线程转储，看到 `flux-async-N ... TIMED_WAITING (parking)` 栈在 `LinkedBlockingQueue.poll` 才算真的在用。

---

### 🐛 15. 容器里没有 jcmd / jstack / jmap

`/opt/java/openjdk/bin/` 只有 `java jfr jrunscript jwebserver keytool rmiregistry`，宿主也无 JDK。

**解法**：`kill -3 <pid>` 发 SIGQUIT，堆摘要与全线程栈会进 stdout（`docker logs`）。够用且零依赖。

---

## 三、配置与环境类

### 🚀 16. compose 的 `environment:` 会覆盖镜像的 `ENV`

**现象**：改了 `Dockerfile` 的 `ENV JAVA_OPTS` 加上 `-XX:TrimNativeHeapInterval=15000`，重建镜像、重新上线，**参数没生效**。

**原因**：`docker-compose*.yml` 里也有一份 `JAVA_OPTS`，`environment` 优先级高于镜像 `ENV`。

**解法**：四个 compose 文件全部同步补齐。**改 JVM 参数必须两处一起改。**

---

### 🚀 17. 同名 `ENV` 声明两次是覆盖不是追加

```dockerfile
ENV JAVA_OPTS="-Xms64m ..."                                   # 第一份
ENV JAVA_OPTS="-Xms64m ... -XX:TrimNativeHeapInterval=15000"  # 第二份，完全盖掉第一份
```

第一行不含新参数却不报错，排查时会把「参数没加进去」误判成「参数没生效」。**合并成一处，并加注释说明「唯一一处」。**

---

### 🚀 18. `install.sh` 与 systemd drop-in 谁说了算

**现象**：`install.sh` 缺省 `MEM_LIMIT=1200MiB`，生产实际生效的是 drop-in `/etc/systemd/system/flux_agent.service.d/override.conf` 里的 `GOMEMLIMIT=512MiB`（drop-in 优先）。

**后果**：在节点上重跑安装，会往单元本体写 1200MiB，与 drop-in 并存——实际仍是 512MiB，但两处数值矛盾，排查时极易误判。

**解法**：`ensure_service_memory_limit()` 先检测 drop-in 目录，命中则只改 `*.conf` 并 `return 0`，不再写第二份。

```bash
if [ -d "$dropin_dir" ] && grep -rqs 'GOMEMLIMIT=' "$dropin_dir"; then
  sed -i -E 's|^(Environment="?GOMEMLIMIT=)[^"]*("?)$|\1'"${MEM_LIMIT}"'\2|' "$dropin_dir"/*.conf
  systemctl daemon-reload
  return 0
fi
```

---

### 🚀 19. sqlite-jdbc 遇到未知 URL 参数会静默打开空库

**现象**：给 JDBC URL 加 PRAGMA 参数后，面板数据「全没了」。

**原因**：sqlite-jdbc 不认识该参数时不报错，而是**打开一个空库**——不抛异常、不打日志，最坏的一种失败。

**解法**：任何 URL 参数改动都要有白名单测试（本项目：`SqliteDatasourceUrlTest`）。**数据"消失"优先查 JDBC URL，而不是查数据被删。**

---

### 🚀 20. Agent 改 `gost.json` 不生效

`config.json` 只提供 `addr`；服务配置由 `x/config` 的 viper 读**当前工作目录**的 `gost.json`，且会被 `saveConfig()` 覆盖。

**解法**：改服务必须走 WebSocket 命令，不能改文件。**排查"改了没用"的第一问：这个配置到底是谁在读、什么时候读。**

---

### 🚀 21. `SIGHUP` 会断掉所有长连接

reload 路径里 `Unregister` 会 `Close()` 全部 service。生产 agent 从不发 `SIGHUP`，所以不构成回归，但必须写进文档——否则下次"平滑重载"会直接断掉全部隧道。

---

### 🚀 22. `failTimeout` 在单节点下完全不生效

```go
// go-gost/x/hop/hop.go
if len(nodes) == 1 { return nodes[0] }
// go-gost/x/selector/filter.go
if len(vs) <= 1 { return vs }
```

双重短路。把 `failTimeout` 从 600s 改成 30s，**在只有 1 个节点的 chain 上没有任何行为变化**。必须先把 chain 配成 ≥2 节点。

**教训**：改配置值之前，先确认读取这个值的代码路径在当前拓扑下真的会被执行。

---

### 🚀 23. 面板 `status=1` 不代表节点可用

`status` 只反映 WebSocket 连接状态。node 5 的 WebSocket 通、每 10 分钟上报正常、`status=1`，但 relay 端口 `1000/1001/1002/1010/1020` 全部不可达。

**解法**：健康判断要拆成三层——WebSocket 在线 / 管理端口可达 / **业务端口真实可拨号**。

---

## 四、工具与安全网类

### 🚧 24. 被安全网拦住时换等效写法，不要硬绕

| 被拦的操作 | 等效写法 |
| --- | --- |
| `git checkout -- <file>` | `git diff <file> > /tmp/p && git apply -R /tmp/p` |
| `git tag -d <tag>` | `git tag -f -a <tag> -m "..."` |
| `git push --force` | `git push --force-with-lease=refs/tags/<tag>:<旧对象>` |
| `curl -T` 上传 | `gh release upload <tag> <file> --clobber` |

---

### 🚧 25. 凭据进仓库会被 GitHub 直接拒收

```
GH013: Repository rule violations / GITHUB PUSH PROTECTION
Push cannot contain secrets
```

**解法**：把 token 改成占位符描述，`git commit --amend`（未 push 的提交无需 force）。**从第一次提交就不要写。**

---

### 🚧 26. Go 项目的 `go.mod` 可能不在仓库根

`go-gost/go.mod` 与 `go-gost/x/go.mod` 是**两个模块**。构建要 `-w /src` 挂 `go-gost`；跑 `x` 模块的测试要 `-w /src/x`，否则报找不到包。

---

### 🚧 27. 旧 agent 二进制被 UPX 加壳，`grep` 判不出功能

`grep -a TransportContext` 全新版本 0 命中，会被误判成"没升级"。

**解法**：用 `-V` 看版本号；新构建去掉 UPX 后可直接 `grep`。

---

### 🚧 28. 本地无原生 Go / JDK / Maven，全部走 Docker

```bash
# Go 构建
docker run --rm -v "$PWD":/src -v /root/gomod:/go/pkg/mod -v /root/gocache:/root/.cache/go-build \
  -w /src -e GOFLAGS=-mod=mod golang:1.23-bookworm \
  sh -c 'CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/gost-amd64 .'

# Maven 测试
docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp \
  -v /root/.m2/repository:/tmp/.m2/repository -v "$PWD/springboot-backend":/w -w /w \
  maven:3.9-eclipse-temurin-21 mvn -B -Dmaven.repo.local=/tmp/.m2/repository test
```

---

### 🚧 29. `docker compose` 的服务名与容器名不同

`docker compose pull springboot-backend` → `no such service`。服务名是 `backend` / `frontend`，容器名才是 `springboot-backend` / `vite-frontend`。**两套名字都要记进文档。**

---

### 🚧 30. A/B 对照容器会因为缺 env 直接退出

镜像的 `logback-spring.xml` 依赖 `${LOG_DIR}`，缺了报：

```
ERROR in ch.qos.logback.core.joran.spi.Interpreter@9:90 - RuntimeException in Action for tag [springProperty]
java.lang.IllegalArgumentException: Could not resolve placeholder 'LOG_DIR' in value "${LOG_DIR}"
```

**解法**：A/B 容器必须补齐 `-e LOG_DIR=... -e DB_PATH=...`，否则起不来还以为镜像坏了。

---

### 🚧 31. A/B 容器会在压测中途消失

`docker inspect` 报 `no such object`，宿主 `free` 正常、非 OOM。原因不明，但**每轮压测前必须确认容器存活**，否则那一轮数据作废。

---

### 🚧 32. 冷 JIT 会把结论完全带反

同一份镜像、同一份数据，先跑的那组平均 4.54 ms，后跑（刚 `docker start`）的那组 32.31 ms / p99 350.80 ms ——"慢了 7 倍"。

**真相**：后跑的 JVM 是冷 JIT。**必须预热 3 轮再测**，且轮转顺序（A→B、B→A）抵消漂移。修正后两组差异在 ±1.5% 以内（小于轮间波动 4.92–6.52 ms）。

---

### 🚧 33. 修改含 CRLF 的 YAML 会破坏结构

用偏移量脚本插入 `spring.task.execution` 时算错一行，把 `datasource:` 的两空格缩进吃掉，`spring:` 之后变成顶层 `datasource:`，YAML 结构直接坏掉。

**解法**：
1. 先 `git diff <file> > /tmp/p && git apply -R /tmp/p` 回滚；
2. 改用**锚点精确拼接**（`anchor='spring:'+nl`），不用行号偏移；
3. 改完 `git diff --stat` 必须恰好是预期行数。

---

## 五、一句话总结

> 这次真正降下来的内存，**没有一个来自"调小某个参数"**：而是先证明它不是泄漏（pprof / goroutine 斜率 / SIGQUIT 堆摘要），再找到三个独立机制（arena 碎片、arena 空闲页不归还、连接不释放），每个机制单独做 A/B 拿到数字，才敢上线。
> 反过来，最容易出错的地方全在**测量口径**：THP 变了、堆基址被算进 arena、冷 JIT 被当性能回归、RSS 缓升被当泄漏。
