# 性能优化报告（PERFORMANCE_OPTIMIZATION_REPORT）

本文记录本次优化**做了什么、为什么这样做、技术原理是什么**。
不改变任何现有业务功能、API 协议、数据库结构、部署方式和客户端兼容性。

---

## 优化项总览

| # | 对象 | 类型 | 风险 | 收益方向 | 状态 |
|---|---|---|---|---|---|
| 1 | 节点代理默认日志级别 | Go | 低 | 磁盘、journald 内存、IO | ⚠️ 生产实测**无收益**（日志本就正常） |
| 2 | `getAllForwards` N+1 | Java | 低 | CPU、GC、响应时间 | ✅ 测试通过（内存无收益） |
| 3 | 修正 `max-pool-size` 键名 | Java 配置 | 无 | 配置正确性（原键从未生效） | ✅ 已改（零行为变化） |
| 4 | 修正 `max-thread` 键名 | Java 配置 | 无 | 配置正确性（原键从未生效） | ✅ 已改（零行为变化） |
| 5 | logback `scan`/`prudent` | Java 配置 | 低 | 同步 IO | ⚠️ 已改代码，**未部署**（收益未测） |
| 6 | `MaxMetaspaceSize` | JVM 参数 | 低 | 非堆内存上限护栏 | ✅ **生产已生效**（护栏，**不省内存**） |
| 7 | `http.Client` 复用 | Go | 低 | 分配次数、空闲连接 | ✅ 编译通过 |
| 8 | 死代码 map 删除 | Go | 无 | 每 5 秒无用分配 | ✅ 编译通过 |
| 9 | 容器日志轮转 | Compose | 无 | 磁盘、page cache | ⚠️ 已改代码，**需容器重建才生效** |
| 10 | journald 容量上限 | 部署配置 | — | **已证伪，无收益** | ❌ 撤回 |
| 11 | 回归测试补充 | 测试 | 无 | 变更安全性 | ✅ 5 用例通过 |
| **12** | **agent `GOMEMLIMIT` 软上限** | **Go 代码 + 环境变量** | **低** | **堆峰值护栏（关键）** | ✅ **生产已生效** |
| **13** | **`-Xms256m` → `-Xms64m`** | **JVM 参数** | **低** | **堆按需提交（关键）** | ✅ **生产已生效，−164 MiB** |
| **14** | **agent `init()` 去掉 `log.Fatal`** | **Go 代码** | **低** | **可测试性** | ✅ 已改 |

> **重要**：第 12、13 项是本次**唯一在生产上实测到真实内存下降**的两项。其余各项要么是代码质量改进，要么在真实负载下收益可忽略。第 1 项与第 10 项经生产实测**已证伪**，不再宣称收益。

---

## 1. 节点代理默认日志级别：`info` → `warn`

### 改了什么

`go-gost/x/config/loader/loader.go`，在默认 logger 创建处引入环境变量开关：

```go
logCfg := cfg.Log
if logCfg == nil {
    logCfg = &config.LogConfig{}
}
logger.SetDefault(logger.NewLogger(
    logger.LevelOption(parseLogLevel(os.Getenv("GOST_LOG_LEVEL"))),
))
```

`parseLogLevel` 语义（全部向后兼容）：

| 环境变量 | 结果 |
|---|---|
| 未设置 / 空 | `warn`（**新默认**） |
| `info` / `debug` / `error` / `warn` / `fatal` / `trace` / `all` / `off` | 对应级别 |
| 无法识别 | 回退 `warn` |

**若 `gost.json` 中显式写了 `log.level`，以文件为准**（`logger.SetDefault(logger.NewLogger(levelOption(logCfg.Level)))` 分支不变），
因此已有自定义日志配置的部署完全不受影响。

### 技术原理

gost 的 `x/handler/relay/handler.go:130` 在每条转发连接建立时执行：

```go
log.Infof("%s <> %s", conn.RemoteAddr(), conn.LocalAddr())
```

这是 **per-connection** 调用，日志量 = 连接数 × 2（建连 + 断开）。
一个承载 1400 长连接的节点，峰值每分钟可产生 500+ 行。改默认级别到 `warn` 后，
所有非 `error/warn` 日志短路在 `logger.Enabled()` 判断处，**不进入格式化与写入路径**。

### 为什么不用「删掉那行日志」

删日志会永久失去「这条连接是谁连的」的排查能力，违反任务书「不能为了省内存删除必要的故障追踪能力」。
改级别是**可逆开关**：需要排查时 `GOST_LOG_LEVEL=info` 即可完整还原，无需重新编译。

### 实测验证

同一份 `gost.json`、同一条 relay 连接，两种模式对比：

| 模式 | `level:"info"` 行数 | 出现 `<>` / `><` 连接行 |
|---|---:|---|
| `GOST_LOG_LEVEL=info` | 4 | 是 |
| 默认（warn） | **0** | **否** |
| 默认（warn，仅失败上报） | 0 | 否，仅 `level:"error"` |

---

## 2. `getAllForwards()` 从 N+1 改为 3 次批量查询

### 改了什么

`springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java`

**改前**（每条转发 2 + P 次查询）：

```java
for (ForwardDto forward : forwardList) {
    Tunnel tunnel = tunnelService.getById(forward.getTunnelId());          // N 次
    List<ForwardPort> ports = forwardPortService.list(
        new LambdaQueryWrapper<ForwardPort>().eq(ForwardPort::getForwardId, forward.getId())); // N 次
    for (ForwardPort port : ports) {
        if (forward.getInIp() == null) {
            Node node = nodeService.getById(port.getNodeId());             // N×P 次
        }
    }
}
```

**改后**（恒定 3 次）：

```java
// 1) 一次取齐所有隧道
Set<Long> tunnelIds = ...;  Map<Long, Tunnel> tunnelMap = tunnelService.listByIds(tunnelIds) → toMap;

// 2) 一次取齐所有端口（按 forward_id IN (...)），再无分配
List<ForwardPort> allPorts = forwardPortService.list(
    new LambdaQueryWrapper<ForwardPort>().in(ForwardPort::getForwardId, forwardIds));
Map<Long, List<ForwardPort>> portsByForward = allPorts.stream().collect(groupingBy(...));

// 3) 一次取齐所有涉及的节点
Set<Long> nodeIds = ...; Map<Long, Node> nodeMap = nodeService.listByIds(nodeIds) → toMap;

// 循环内只做内存组装
```

### 技术原理

- **减少 SQL 往返**：SQLite 每次查询都有「连接获取 → 语句编译 → 执行 → 结果集映射 → 释放」的固定开销，
  这部分开销与结果行数无关。N+1 的代价主要来自固定开销的重复 N 次。
- **减少短命对象**：每次查询都会产生 MyBatis 的参数映射对象、BoundSql、结果集包装。
  700 次查询 → 数百个临时对象 → 年轻代分配速率上升 → minor GC 更频繁。
  这正是「内存数字不高但 GC 抖动」的典型来源。
- **缩短持有时间**：批量查询后立即构建不可变查找表，避免在循环中反复访问数据库连接。

### 行为兼容性

以下语义被逐一保持不变（并有测试锁定）：

1. `resolveIp` 的 **IP × 端口笛卡尔积**展开顺序与数量不变；
2. `inIp` 非空时**始终使用 `inIp`**、节点查询结果被忽略；
3. 隧道缺失时 `tunnelName` 为 `null`（不抛异常）；
4. 端口列表为空时 `ipList` 为**空列表**（`[]`，不是 `null`）；
5. 多条转发共享同一节点时，节点 IP 复用同一份；
6. 返回的 `ForwardDto` 字段集合与 JSON 结构完全不变。

### 验证

新增 `springboot-backend/src/test/java/com/admin/service/impl/ForwardServiceImplGetAllForwardsTest.java`，
5 个用例全部通过；全量 91 个测试通过。

---

## 3. 修正 `max-pool-size` → `maximum-pool-size`（配置从未生效）

> **本节结论在审计中被推翻并重写。** 最初的判断是「把连接池从 20 降到 8 省内存」，
> 生产实测证明**原键名根本无法绑定**，池子一直是 Hikari 默认值 10，所谓「20」从未存在。

### 3.1 实测证据

用「类型绑定失败法」在本地用生产同款 jar 验证四个键名是否存在：
给键传非法值 `abc`，若键真实存在，Spring Boot 启动即报绑定错误；若不存在则被静默忽略。

| 键名 | 传 `abc` 的结果 | 结论 |
|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | `APPLICATION FAILED TO START`<br>`Failed to bind properties under 'spring.datasource.hikari.maximum-pool-size' to int` | ✅ **真实存在** |
| `spring.datasource.hikari.max-pool-size` | 正常启动 `Started AdminApplication in 5.872 seconds` | ❌ **不存在，被静默忽略** |
| `server.tomcat.threads.max` | `APPLICATION FAILED TO START`<br>`Failed to bind properties under 'server.tomcat.threads.max' to int` | ✅ **真实存在** |
| `server.tomcat.max-threads` | 正常启动 `Started AdminApplication in 5.847 seconds` | ❌ **不存在，被静默忽略** |

异常信息本身就写明了原因：

```
Could not bind properties to 'HikariDataSource' : prefix=spring.datasource.hikari,
    ignoreInvalidFields=false, ignoreUnknownFields=true
```

`ignoreUnknownFields=true` 意味着**拼错的键不会报错，只会被丢掉**。原配置的
`max-pool-size: 20` 和 `max-thread: 800` 都属于这种情况。

### 3.2 另一个佐证

生产容器启动日志稳定出现：

```
HikariCP - idleTimeout is close to or more than maxLifetime, disabling it.
```

该告警的触发条件是 `idle-timeout(600000) >= max-lifetime(500000)`。
`max-lifetime` 与 `idle-timeout` 都是**正确键名**，所以它们正常绑定；
而 `max-pool-size` 没有对应告警也没有报错，正好印证它被忽略了。

### 3.3 本次实际改了什么

```diff
-      max-lifetime: 500000
-      max-pool-size: 20
-      minimum-idle: 5
+      max-lifetime: 500000          # 不变：既有设计，用于数据库文件被替换后重开
+      maximum-pool-size: 10         # 键名修正，取值 = 当前真实生效值（Hikari 默认 10）
+      minimum-idle: 5               # 不变
```

**净效果：零行为变化。** 唯一区别是配置文件从「写了个假的 20」变成「写了个真的 10」。

### 3.4 为什么不做真正的「降池」

一度考虑把池降到 8、`minimum-idle` 降到 2。实测后放弃，理由：

1. **收益可忽略**：SQLite 每连接约 100~200 KB，10 → 8 省不到 0.5 MB，对 8 GB 内存的机器无意义。
2. **有行为风险**：`minimum-idle` 从 5 降到 2 会让突发请求需要现场建连，而任务书明确
   「不得为了减少资源占用而造成服务响应明显变慢」。
3. **`max-lifetime` 不能动**：注释显示 500000ms 是**刻意**设置，用于「数据库文件被替换后重新打开」。
   改成 30 分钟会让该机制的响应速度慢 3.6 倍，属于改变既有功能。

**真正的 Java 内存收益来自第 13 节的 `-Xms`，不来自这里。**

---

## 4. 修正 `max-thread` → `server.tomcat.threads.max`（配置从未生效）

**同样是「配置正确性」修复，零行为变化。**

```diff
-    max-thread: 800
+    threads:
+      max: 200
```

- 原键 `max-thread` 不存在（见 §3.1 实测表第 4 行），实际一直走 Tomcat 默认 200。
- 一度改成 `max-threads: 200`，**同样不存在**（实测表第 4 行），仍是无效配置。
- 最终改为正确的嵌套结构 `server.tomcat.threads.max: 200`，取值等于当前真实值。

**为什么这有价值**：任何人看到 `max-thread: 800` 都会以为线程栈占
`800 × 1 MB = 800 MB` 并据此做错误的容量规划——而实际只有 200 个线程、实测 `Threads: 54`。

---

## 5. logback：关闭 `scan`，移除 `prudent`

```diff
-<configuration scan="true" scanPeriod="60 seconds" debug="false">
+<configuration scan="false" debug="false">
```

- `scan="true"` 让 logback 每 60 秒 stat 一次配置文件。生产配置不会变，这些调用是纯浪费。
- `prudent` 从 **文件** appender（`logFile`，`RollingFileAppender`）移除，不是 console。
  `prudent=true` 会强制每次写都 `open → write → close` 且无法缓冲；单容器内只有一个 JVM 写该文件，
  不需要跨进程互斥。

**保留了**所有 appender、pattern、级别、输出路径。

> ⚠️ **重要**：这项改动**尚未部署到生产**。生产镜像构建于 2026-10-26，
> 不含本次任何 `application.yml` / `logback-spring.xml` 修改（见 §6.3）。
> 因此它目前是「已改代码、未生效」，实际收益为 0 且未测量。

---

## 6. JVM 参数：`-Xms256m → -Xms64m` + `MaxMetaspaceSize=192m`

### 6.1 关键前提：镜像里的配置是旧的

生产镜像是 `ahmadplayz721/flux-panel-backend:2.0.7-beta`，jar 时间戳 `Oct 26 04:29`，
而本次修改发生在 `Oct 30`。**镜像内打包的是旧版 `application.yml` 与 `logback-spring.xml`。**

实测确认容器内 `application.yml` 无法直接读到（在 `BOOT-INF/classes` 内），
但用 `git show HEAD:` 比对可知镜像内是 `max-lifetime: 500000 / max-pool-size: 20 / max-thread: 800` 这一版。

**结论：Java 侧本次唯一真正生效的改动是 compose 里的 `JAVA_OPTS`**，
因为环境变量优先级高于 jar 内配置。

### 6.2 实际生效的参数

```
$ tr '\0' '\n' < /proc/<java_pid>/cmdline | grep -E "^-X|^-XX"
-Xms64m
-Xmx512m
-XX:MaxMetaspaceSize=192m
-Dfile.encoding=UTF-8
-Duser.timezone=Asia/Shanghai
```

`-Xms` 从 256m 降到 64m 是本次 Java 内存下降的**唯一原因**，详见第 13 节。

### 6.3 `MaxMetaspaceSize=192m` 是护栏，不是省内存手段

- 实测 Metaspace committed 71,552 K（≈70 MB），192m 是 2.7 倍余量。
- 它只在失控时把「RSS 无声蚕食」变成「带堆栈的 `OutOfMemoryError: Metaspace`」。
- **本次实测并未因为加上它而下降 1 字节**，不要把它计入收益。

---

## 7. Go 上报器复用单个 `http.Client`

`go-gost/x/service/traffic_reporter.go`

```go
// 新增包级单例
var reportHTTPClient = &http.Client{
    Timeout: 5 * time.Second,
    Transport: &http.Transport{
        Proxy:                 http.ProxyFromEnvironment,
        MaxIdleConns:          8,
        MaxIdleConnsPerHost:   4,
        IdleConnTimeout:       60 * time.Second,
        TLSHandshakeTimeout:   10 * time.Second,
        ExpectContinueTimeout: 1 * time.Second,
    },
}
```

替换 `sendBatchTrafficReport`（每 5 秒）与 `sendConfigReport`（每 10 分钟）中的
`client := &http.Client{Timeout: 5 * time.Second}`。

**技术原理**：原写法虽复用 `http.DefaultTransport`（**不是连接泄漏**），
但每次上报都新建 Client 结构体，且无法约束空闲连接上限。
显式 Transport 后空闲连接最多 8 条、单 host 4 条、60 秒回收，可控。

**超时保持 5 秒不变**，未缩短。

---

## 8. 删除全局流量管理器中的死代码 map

`go-gost/x/service/global_traffic_manager.go` `collectAndReport()`：

```go
// 改前：构造后从未读取
trafficSnapshot := make(map[string]*ServiceTraffic)
for name, traffic := range m.services {
    trafficSnapshot[name] = &ServiceTraffic{UpBytes: ..., DownBytes: ...}
}
// ... 后续只使用 reportItems，trafficSnapshot 再未出现

// 改后：直接删掉上面的 map，只保留上报项的构建
```

**技术原理**：每 5 秒一次的 map 分配 + 每个服务一个 `ServiceTraffic` 对象 + 一个指针
在 map 中的槽位。虽然单次很小，但这是 24×7 的常驻后台循环，属于零收益的稳态分配。
同时移除了它在上报期间对 `ServiceTraffic` 指针的额外持有，减少 GC 扫描面。

---

## 9. 容器日志轮转

四个 compose 文件（`docker-compose-v4.yml`、`docker-compose-v6.yml`、
`deploy/docker-compose.yml`、`deploy/docker-compose.registry.yml`）的 `backend` / `frontend` 服务统一加：

```yaml
logging:
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"
```

上限 30 MB/服务，**保留 3 个历史文件**，故障追踪能力不受影响。
（`json-file` 是当前已在用的驱动，这里只加大小限制，不改驱动类型。）

**没有**改 `restart` 策略、`depends_on`、健康检查、端口映射、数据卷、环境变量。

---

## 10. journald 容量上限 drop-in（新增，**可选防护，非必装**）

```ini
[Journal]
SystemMaxUse=200M
SystemMaxFileSize=32M
MaxRetentionSec=1month
```

### 原假设

本地测试机 `/var/log/journal` 达 4.1 GB、agent 日志 620 行/分钟，
推测生产也在刷屏并频繁 vacuum。

### 生产实测（推翻）

```
journalctl --disk-usage                              → 84.1 MB
journalctl -u flux_agent --since '5 min ago' | wc -l → 1 行
/etc/systemd/journald.conf.d 目录不存在
journald.conf 全为注释默认值（未做任何自定义）
```

**该节点 journal 完全正常，本项无收益。**

### 结论

- **生产未应用**，且不建议应用。
- 模板保留在 `deploy/journald-flux.conf`，作为 relay 高流量节点的可选防护。
- 该文件是宿主机全局配置，会影响所有服务，应用前需评估其他服务的排障需求。
- **不要把它算进任何内存/磁盘收益。**

---

## 11. 补充回归测试

新增 `ForwardServiceImplGetAllForwardsTest`，5 个用例覆盖：

1. 单转发多端口 → 笛卡尔积顺序与数量；
2. `inIp` 非空 → 用 `inIp`，忽略节点；
3. 隧道不存在 → `tunnelName` 为 null，不抛异常；
4. 无端口 → `ipList` 为空列表；
5. 多转发共享节点 → 节点 IP 正确复用。

**为什么必须有**：`getAllForwards` 被前端仪表盘与转发列表依赖，
改内部实现而不加测试，等于「没有测试用例就默认功能没受影响」——这是任务书明确禁止的。

---

## 12. agent `GOMEMLIMIT` 软上限（本次最关键的一项）

### 12.1 问题

生产实测 `flux_agent`：

```
VmHWM:  2,717,452 kB      ← 历史峰值
VmRSS:  2,680,140 kB      ← 当前
MemoryCurrent: 2,760,110,080 B
AnonHugePages: 2,486,272 kB   ← 占 RSS 的 92.7%
GOMEMLIMIT / GOGC: 均未设置
```

而重启后的同一进程：`HeapAlloc 41.3 MiB`、`Sys 75.4 MiB`、`goroutine 1,026`。

**结论：真实工作集只有百兆级，2.56 GiB 是堆高水位残留。**

### 12.2 技术原理

Go 的默认 GC 策略是 `GOGC=100`：堆涨到上次 GC 后存活量的 2 倍就触发 GC。GC 后释放的对象空间**留在 runtime 堆中复用**，只在"空闲比例过高"时才 `madvise` 归还 OS；`MADV_FREE` 下这些页在内存紧张前**仍计入 RSS**。

在 `THP=[always]` 下，归还粒度变成 2 MiB 大页，回收更不积极，**RSS 因此长期虚高**。

`GOMEMLIMIT` 是 Go 1.19+ 的**软内存上限**：当堆接近该值时，runtime 会**降低 GC 触发阈值**（等效动态调小 GOGC），从而更频繁 GC、更积极归还。它是**软限制**——不会像 `-Xmx` 那样 OOM Kill，只会让 GC 更努力。

### 12.3 实施

两层保障：

1. **代码层**（`go-gost/main.go`，待部署）：内置默认 1200 MiB，支持 `GOMEMLIMIT` / `FLUX_AGENT_MEMLIMIT` 覆盖、`off` 关闭。
2. **运维层**（生产已生效）：`/etc/systemd/system/flux_agent.service.d/override.conf`

```ini
[Service]
Environment="GOMEMLIMIT=1200MiB"
```

> 生产当前跑的是旧二进制，但 `GOMEMLIMIT` 是 **Go runtime 原生识别**的环境变量，**不依赖新代码**，因此立即生效。

### 12.4 为什么是 1200 MiB 而不是更低

| 候选值 | 风险 |
|---|---|
| 300 MiB | 过于接近工作集（实测 100~110 MiB + 峰值波动），会引发 GC 抖动，降低转发吞吐 |
| 600 MiB | 可行，但大流量峰值时容易频繁触发强制 GC |
| **1200 MiB** | **工作集的 10 倍余量，只在真正失控时才介入**；同时远低于原峰值 2.59 GiB |
| 不设 | 重演 2.5 GiB |

任务书第十二条明确"不能盲目提高 Go GC 频率"，因此选择**宽松护栏**而非激进压制。

### 12.5 实测效果

```
                        优化前              优化后（t+15min）
MemoryCurrent          2,760,110,080 B     115,949,568 B
VmRSS                  2,680,140 kB        100,192 kB
VmHWM                  2,717,452 kB        110,016 kB
HeapReleased           (不可见)            12.2 MiB（在增长，说明在归还）
```

**但必须诚实说明**：这 −2.44 GiB 的**主要贡献者是"重启"**，不是 `GOMEMLIMIT`。护栏的真实价值是**防止再次爬回 2.5 GiB**，需要 24~72 小时观察才能证明。

判断护栏是否有效的标志：`/root/flux_mem.csv` 中 `rss_kb` 长期不超过 1,200,000（≈1172 MiB），且 `heaprel_mb` 在增长。

---

## 13. JVM `-Xms256m` → `-Xms64m`（实测 −164 MiB）

### 13.1 问题

jcmd 实测（未重启容器，用 `--pid=container` 挂载诊断）：

```
garbage-first heap   total 262144K, used 82156K / 40276K / 54314K
```

**堆 committed 恒为 256 MiB，但 used 只有 40~82 MiB，利用率 16%~32%。**

```
最大匿名 VMA: start=0xe0000000 rss=243712kB
```

这个 238 MiB 的 VMA 正是 G1 堆区间 `[0xe0000000, 0x100000000)`。

### 13.2 技术原理

`-Xms` 是**初始堆大小**，JVM 启动时即向 OS 提交这么多内存。在 `THP=[always]` 下，这 256 MiB 立刻被 2 MiB 大页覆盖并**实际触碰**（G1 会 pretouch），于是全部变成常驻物理页。

**即使堆里只有 50 MiB 数据，另外 200 MiB 也实实在在占着物理内存。**

这是"JVM 看起来比实际需要大很多"的常见原因，且**与泄漏无关**。

### 13.3 实施

```diff
-      JAVA_OPTS: "-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
+      JAVA_OPTS: "-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
```

- `-Xms` 降到 64 MiB：让堆**按需增长**，而不是启动即占满。
- `-Xmx512m` **保持不变**：这是硬上限，任务书禁止设置过低堆上限，且实测工作集远小于它。
- `-XX:MaxMetaspaceSize=192m`：实测 Metaspace 69.81 MB，192m 是 2.7 倍余量，只做护栏。

### 13.4 实测效果

```
                        优化前        优化后(t+2min)   优化后(t+15min)   结构性变化
VmRSS                  661,436 kB     322,908 kB      432,120 kB       −229,316 kB
堆 committed           262,144 K       78,848 K        94,208 K        −167,936 K
堆 used                 40~82 MiB         —            52,786 K         持平
Metaspace committed     71,552 K       57,408 K        64,256 K         −7,296 K
docker stats            613.8 MiB      299.4 MiB       405.6 MiB        −208.2 MiB
```

**结构性收益 = 堆 committed 从 256 MiB 降到 92 MiB，约 −164 MiB。**
Metaspace 的 −7 MiB 属暂时（新 JVM 类加载未满），不计入长期收益。

**可信长期收益：约 −160 MiB。**

> t+2min 的 322 MiB 与 t+15min 的 432 MiB 说明**堆在按需扩张**，这是正确行为。最终稳定值取决于业务实际需要（当前 used 51.5 MiB）。若长期贴近 512 MiB，说明业务确实需要，**不应再压 `-Xmx`**。

---

## 14. agent `init()` 去掉 `log.Fatal`（可测试性）

### 14.1 问题

```go
// go-gost/x/service/service.go
func init() {
	if err := LoadConfig("config.json"); err != nil {
		log.Fatal(err)      // os.Exit(1)
	}
}
```

任何导入该包的测试都会因为工作目录没有 `config.json` 而**让整个测试进程退出**。这是本项目此前**零 Go 单元测试**的结构性原因。

同理 `go-gost/main.go` 的 `init()` 里调用 `flag.Parse()` 会让 `go test` 因 `-test.testlogfile` 未注册而失败。

### 14.2 实施

- `service.go`：`log.Fatal(err)` → `return`（静默跳过，由 `main` 统一处理并给出完整报错）。
- `main.go`：`flag.Parse()` 从 `init()` 移入 `parseFlags()`，由 `main()` 首行调用。

### 14.3 效果

- **运行时行为不变**：缺 `config.json` 时仍退出码 1，报错信息更完整。
- 新增 `go-gost/main_memlimit_test.go`，26 个子测试全部通过。
- 为后续给 `x/service`、`x/socket` 等包补测试铺平道路。

---

## 15. 已被生产实测推翻的优化项

### 15.1 ❌ 第 1 项「agent 日志级别 info → warn」

**原假设**：agent 每建立一条 relay 连接就打印一条 INFO 日志，导致 journald 刷屏并占用内存。

**生产实测**：

```
journalctl --disk-usage              → 84.1M（不是 4 GiB）
journalctl -u flux_agent --since '5 min ago' | wc -l → 1 行
30 分钟内仅 3 条「✅ 定时配置上报成功」（10:57:39 / 11:07:39 / 11:17:39）
```

**结论：生产环境 agent 每 10 分钟才 1 行日志，根本不存在刷屏。** 该优化项**对生产无收益**。

保留该改动的唯一理由：若未来某条转发路径出现异常，`warn` 默认能减少噪声。但这属于偏好，**不能包装成省内存**。

### 15.2 ❌ 第 10 项「journald 容量上限 drop-in」

**原假设**：journald 已到 4 GiB 上限并在频繁 vacuum。

**生产实测**：journal 仅 84.1 MB，`/etc/systemd/journald.conf.d` 目录原本不存在，`journald.conf` 全为注释默认值。

**结论：无收益，已撤回。** `deploy/journald-flux.conf` 降级为"可选模板"，不纳入交付必需项。

### 15.3 ❌ 「Swap 抖动导致转发延迟」

**生产实测**：`Swap: 4095 total, 0 used`，`vmstat si/so` 恒为 0，PSI memory 全 0.00。

**结论：生产从未发生换页，此假设不成立。**

### 15.4 ❌ 「1404 条 ESTABLISHED 是 agent 的连接风暴」

**生产实测**：那是**全系统** socket 计数，不是 agent 进程的。agent 自身 ESTAB 在 314~442 之间波动。

**结论：归因错误，已更正。**

---

## 16. 优化原则自查

| 任务书要求 | 是否遵守 | 说明 |
|---|---|---|
| 不改业务功能 | ✅ | 全部改动为参数、实现方式、可测试性 |
| 不改 API 协议 | ✅ | 无接口签名变化 |
| 不改数据库结构 | ✅ | 无 DDL/DML 变更 |
| 不改部署方式 | ✅ | 仍是 Docker Compose + systemd |
| 保留 IPv4/IPv6 | ✅ | 未触碰任何网络配置，`[::]` 双绑保持 |
| 保留多节点 | ✅ | `gost.json` 212 个 service 一个未动 |
| 不降低认证安全性 | ✅ | 未触碰 JWT、密码、权限 |
| 不设置过低 JVM 堆 | ✅ | `-Xmx512m` 未动，只降 `-Xms` |
| 不盲目提高 GC 频率 | ✅ | `GOMEMLIMIT=1200MiB` 是宽松护栏，非激进压制 |
| 不用 Docker 低内存限制掩盖泄漏 | ✅ | 未设置任何 `mem_limit` |
| 不编造下降比例 | ✅ | 明确区分"重启贡献"与"优化贡献" |
| 改动可回滚 | ✅ | 每项都有独立还原方式，生产变更均有备份 |

---
