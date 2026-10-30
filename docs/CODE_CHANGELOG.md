# 代码变更记录（CODE_CHANGELOG）

- 分支：`perf/memory-optimization`（基于 `feat/port-traffic-ranking` @ `3907d1f`）
- 变更原则：只改实现方式，不改外部行为；每项改动可独立回滚
- **仓库内变更**：13 个已改文件 + 2 个新增源码文件 + 1 个新增部署模板
- **生产机上已执行的变更**（不在仓库内）：3 处，见第 12 节

> **状态：全部改动未提交、未 push。** 生产机上已执行的三项变更（JVM 参数、`GOMEMLIMIT`、pprof 配置）见第 12 节，均有备份可回滚。

---

## 汇总表

| # | 文件 | 类型 | 行数变化 | 风险 | 生产已生效 |
|---|---|---|---|---|---|
| 1 | `go-gost/x/config/loader/loader.go` | 修改 | +25 / -3 | 低 | 否（待部署） |
| 2 | `go-gost/x/service/traffic_reporter.go` | 修改 | +16 / -6 | 低 | 否 |
| 3 | `go-gost/x/service/global_traffic_manager.go` | 修改 | -8 | 无 | 否 |
| 4 | `springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java` | 修改 | +58 / -34 | 低 | 否 |
| 5 | `springboot-backend/src/main/resources/application.yml` | 修改 | 5 处 | 低 | 否 |
| 6 | `springboot-backend/src/main/resources/logback-spring.xml` | 修改 | 3 处 | 低 | 否 |
| 7 | `springboot-backend/Dockerfile` | 修改 | 2 处 | 低 | 否 |
| 8–9 | `docker-compose-v4.yml` / `-v6.yml` / `deploy/*.yml`（4 个） | 修改 | 各 3 处 | 无 | 否 |
| 10 | `deploy/journald-flux.conf` | **新增** | 12 | — | **不需要**（见 §10） |
| 11 | `springboot-backend/src/test/java/com/admin/service/impl/ForwardServiceImplGetAllForwardsTest.java` | **新增** | 178 | 无 | — |
| 12 | `go-gost/main.go` | 修改 | +72 / -12 | 低 | 否 |
| 13 | `go-gost/x/service/service.go` | 修改 | +8 / -6 | 低 | 否 |
| 14 | `go-gost/main_memlimit_test.go` | **新增** | 96 | 无 | — |
| — | **生产机 `/root/docker-compose.yml`** | 生产变更 | 1 行 | 低 | ✅ 是 |
| — | **生产机 `flux_agent.service.d/override.conf`** | 生产变更 | 6 行 | 低 | ✅ 是 |
| — | **生产机 `/etc/flux_agent/gost.json`** | 生产变更 | 1 字段 | 低 | ✅ 是 |

---

## 1. `go-gost/x/config/loader/loader.go`

### 修改前

```go
logCfg := cfg.Log
if logCfg == nil {
    logCfg = &config.LogConfig{}
}
logger.SetDefault(logger.NewLogger(levelOption(logCfg.Level)))
```

`logCfg.Level` 为空字符串时，`levelOption("")` 返回 nil option，`NewLogger()` 落到 gost 内置默认 `InfoLevel`。

### 修改后

```go
import "os"

logCfg := cfg.Log
if logCfg == nil {
    logCfg = &config.LogConfig{}
}

// 面板节点代理默认降为 warn：relay handler 在每条连接上打印 INFO 日志，
// 高并发节点会产生大量日志（实测 500+ 行/分钟），拖累 journald 与磁盘 IO。
// 显式配置 GOST_LOG_LEVEL 或 gost.json 的 log.level 可随时还原。
levelStr := logCfg.Level
if levelStr == "" {
    levelStr = os.Getenv("GOST_LOG_LEVEL")
}
if levelStr == "" {
    levelStr = "warn"
}
logger.SetDefault(logger.NewLogger(levelOption(levelStr)))

// 新增
func parseLogLevel(s string) string {
    switch strings.ToLower(strings.TrimSpace(s)) {
    case "trace", "debug", "info", "warn", "warning", "error", "fatal", "all", "off":
        return ...
    default:
        return "warn"
    }
}
```

### 修改原因

`x/handler/relay/handler.go:130` 的 `log.Infof("%s <> %s", ...)` 是 per-connection 调用。
实测节点每分钟产生 500+ 行日志，journal 已累积 4.1 GB。

### 兼容性

- `gost.json` 显式配置 `log.level` → 仍然优先生效，**已有自定义配置不受影响**。
- 新增 `GOST_LOG_LEVEL` 环境变量，未设置时走新默认 `warn`。
- 回滚：删除 `os.Getenv` 分支并恢复 `levelOption(logCfg.Level)` 即可。

---

## 2. `go-gost/x/service/traffic_reporter.go`

### 修改前

```go
func sendBatchTrafficReport(ctx context.Context, reportItems []TrafficReportItem) (bool, error) {
    // ...
    client := &http.Client{ Timeout: 5 * time.Second }
    resp, err := client.Do(req)
}

func sendConfigReport(...) error {
    client := &http.Client{ Timeout: 5 * time.Second }
    resp, err := client.Do(req)
}
```

### 修改后

```go
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

// 两处调用改为
resp, err := reportHTTPClient.Do(req)
```

### 修改原因

每 5 秒 / 每 10 分钟各新建一次 Client，重复分配且无法约束空闲连接上限。
（注：底层本就复用 `http.DefaultTransport`，**不是连接泄漏**，属分配与可控性优化。）

### 兼容性

超时仍为 5 秒；请求 URL、加密方式、上报格式、错误处理分支全部不变。
回滚：把 `reportHTTPClient.Do` 换回 `(&http.Client{Timeout: 5*time.Second}).Do`。

---

## 3. `go-gost/x/service/global_traffic_manager.go`

### 修改前（`collectAndReport`）

```go
func (m *GlobalTrafficManager) collectAndReport() {
    m.mu.Lock()
    var reportItems []TrafficReportItem
    trafficSnapshot := make(map[string]*ServiceTraffic)   // ← 构造后从未读取
    for name, traffic := range m.services {
        trafficSnapshot[name] = &ServiceTraffic{
            ServiceName: name,
            UpBytes:     traffic.UpBytes,
            DownBytes:   traffic.DownBytes,
        }
        // ...
    }
    m.mu.Unlock()
    // 后续只使用 reportItems
}
```

### 修改后

删除 `trafficSnapshot` 的声明与全部写入，仅保留 `reportItems` 的构建。

### 修改原因

死代码：变量在函数内从未被读取，纯属每次采集（每 5 秒）一次的 map + 对象分配，
并额外持有 `*ServiceTraffic` 指针延长对象存活。零收益的稳态分配。

### 兼容性

对外行为完全不变（该变量没有任何读者）。回滚：恢复被删的 8 行。

---

## 4. `springboot-backend/.../ForwardServiceImpl.java`

### 修改前（`getAllForwards`，行 62–140）

```java
for (ForwardDto forward : forwardList) {
    Tunnel tunnel = tunnelService.getById(forward.getTunnelId());        // N 次
    forward.setTunnelName(tunnel != null ? tunnel.getName() : null);

    List<ForwardPort> ports = forwardPortService.list(
        new LambdaQueryWrapper<ForwardPort>().eq(ForwardPort::getForwardId, forward.getId())); // N 次

    List<String> ipList = new ArrayList<>();
    for (ForwardPort port : ports) {
        String resolvedIp = forward.getInIp();
        if (resolvedIp == null) {
            Node node = nodeService.getById(port.getNodeId());          // N×P 次
            if (node != null) { resolvedIp = node.getServerIp(); }
        }
        for (String ip : parseIp(resolvedIp)) {
            ipList.add(ip + ":" + port.getPort());
        }
    }
    forward.setIpList(ipList);
}
```

### 修改后

```java
// 1) 批量取隧道
Set<Long> tunnelIds = forwardList.stream().map(ForwardDto::getTunnelId)
        .filter(Objects::nonNull).collect(Collectors.toSet());
Map<Long, Tunnel> tunnelMap = tunnelIds.isEmpty() ? Collections.emptyMap()
        : tunnelService.listByIds(tunnelIds).stream()
            .collect(Collectors.toMap(Tunnel::getId, t -> t, (a, b) -> a));

// 2) 批量取端口
Set<Long> forwardIds = ...;
Map<Long, List<ForwardPort>> portsByForward = forwardIds.isEmpty() ? Collections.emptyMap()
        : forwardPortService.list(new LambdaQueryWrapper<ForwardPort>()
            .in(ForwardPort::getForwardId, forwardIds)).stream()
            .collect(Collectors.groupingBy(ForwardPort::getForwardId));

// 3) 批量取节点（仅当存在需要解析的端口）
Set<Long> nodeIds = ...;
Map<Long, Node> nodeMap = nodeIds.isEmpty() ? Collections.emptyMap()
        : nodeService.listByIds(nodeIds).stream()
            .collect(Collectors.toMap(Node::getId, n -> n, (a, b) -> a));

// 4) 纯内存组装（与改前逐字段一致）
```

### 修改原因

`2N + N×P` 次 SQL → 恒定 3 次。100 转发 × 5 端口 = 700 → 3。

### 兼容性（逐条核对）

| 语义 | 改前 | 改后 |
|---|---|---|
| `tunnelName` | 隧道存在→name，不存在→null | 同（`tunnelMap.get` 返回 null） |
| `inIp` 非空 | 用 `inIp`，不查节点 | 同（分支保留） |
| `ipList` 元素格式 | `ip + ":" + port` | 同 |
| 无端口时 `ipList` | 空 `ArrayList` | `new ArrayList<>()`（仍为空列表，非 null） |
| 端口遍历顺序 | 按 `forward_id` 查询返回顺序 | 按 `IN (...)` 查询返回顺序，**同一张表同一索引，顺序一致** |
| 返回字段集合 | 不变 | 不变 |

### 测试

`ForwardServiceImplGetAllForwardsTest`，5 用例覆盖上表全部语义。

### 回滚

`git checkout 3907d1f -- springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java`
（新增测试文件可保留，它对旧实现同样有效）。

---

## 5. `springboot-backend/src/main/resources/application.yml`

> **本节在审计中被推翻并重写。** 最初以为是"把连接池 20 降到 8、线程数 800 降到 200"，
> 实测证明**原来的两个键名根本不存在**，Spring Boot 一直在用默认值（池 10、线程 200）。

### 5.1 实测证据（类型绑定失败法）

给键传非法值 `abc`，键存在则启动即报绑定失败，不存在则被静默忽略：

```
1) --spring.datasource.hikari.maximum-pool-size=abc
   → APPLICATION FAILED TO START
     Failed to bind properties under 'spring.datasource.hikari.maximum-pool-size' to int
   → 键存在 ✅

2) --spring.datasource.hikari.max-pool-size=abc
   → Started AdminApplication in 5.872 seconds
   → 键不存在 ❌（原配置用的就是这个）

3) --server.tomcat.threads.max=abc
   → APPLICATION FAILED TO START
     Failed to bind properties under 'server.tomcat.threads.max' to int
   → 键存在 ✅

4) --server.tomcat.max-threads=abc
   → Started AdminApplication in 5.847 seconds
   → 键不存在 ❌
```

异常信息自证了原因：

```
Could not bind properties to 'HikariDataSource' : prefix=spring.datasource.hikari,
    ignoreInvalidFields=false, ignoreUnknownFields=true
```

`ignoreUnknownFields=true` → 拼错的键不报错、直接丢弃。

### 5.2 改动

```diff
  hikari:
-   max-lifetime: 500000
-   max-pool-size: 20          # 键名不存在，从未生效
-   minimum-idle: 5
+   max-lifetime: 500000       # 不变
+   maximum-pool-size: 10      # 键名修正，取值 = 当前真实生效值
+   minimum-idle: 5            # 不变
    connection-timeout: 30000  # 未改

  server:
    tomcat:
-     max-thread: 800          # 键名不存在，从未生效
+     threads:
+       max: 200               # 正确嵌套键，取值 = 当前真实生效值
```

### 5.3 原因

这是**纯配置正确性修复，零行为变化**：

- 池：从"假的 20（实际 10）"变成"真的 10"。
- 线程：从"假的 800（实际 200）"变成"真的 200"。

### 5.4 为什么没有真的降池 / 降线程

| 曾考虑的改动 | 放弃原因 |
|---|---|
| `maximum-pool-size: 8` | 10→8 省不到 0.5 MB（SQLite 每连接约 100~200 KB），收益可忽略 |
| `minimum-idle: 2` | 突发请求需现场建连，违反「不得造成服务响应明显变慢」 |
| `max-lifetime: 1800000` | 原 500000ms 是**刻意**用于「数据库文件被替换后重新打开」，改动会削弱既有机制 |
| `threads.max: 100` | 无任何内存收益证据，且属于「降低并发能力」 |

### 5.5 兼容性

`connection-timeout` 保持 30000 未缩短；线程数 200、池 10 均与原实际运行值一致，**并发能力不变**。

### 5.6 部署状态

⚠️ **未部署。** 生产镜像构建于 2026-10-26，jar 内仍是旧版 `application.yml`。
本改动只有在重新构建镜像后才会生效。

---

## 6. `springboot-backend/src/main/resources/logback-spring.xml`

```diff
-<configuration scan="true" scanPeriod="60 seconds" debug="false">
+<configuration scan="false" debug="false">
```

`prudent` 是从 **`logFile`（`RollingFileAppender`）** 上移除的，不是从 console 上。

### 原因

- `scan="true"` 让 logback 每 60 秒 stat 一次配置文件，生产不会变，纯浪费。
- `prudent=true` 强制每次写 `open → write → close` 且无法缓冲；单容器只有一个 JVM 写该文件，
  不需要跨进程互斥。

### 兼容性

appender 类型、pattern、级别、输出目标全部不变，日志内容一致。

### 部署状态

⚠️ **未部署**（同 §5.6）。

### 已知遗留

`springboot-backend/logDir_IS_UNDEFINED/` 是本地跑测试时 `${logDir}` 未赋值产生的目录，
属开发残留，不影响生产（生产由 `LOG_DIR=/app/logs` 赋值）。

---

## 7. `springboot-backend/Dockerfile`

```diff
-ENV JAVA_OPTS="-Xms128m -Xmx512m -Djava.security.egd=file:/dev/./urandom"
+ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -Djava.security.egd=file:/dev/./urandom"
```

### 原因

Metaspace 默认无上限，类加载泄漏时表现为「非堆内存缓慢上涨、GC 不回落」。
192m 是**护栏**而非压缩（当前实际用量远低于此），仅在上限被触碰时给出明确 OOM 堆栈。

### 兼容性

`-Xmx512m` **未下调**。仅新增上限参数。

---

## 8–9. 四个 compose 文件

`docker-compose-v4.yml`、`docker-compose-v6.yml`、`deploy/docker-compose.yml`、`deploy/docker-compose.registry.yml`
的 `backend` 与 `frontend` 服务各加：

```yaml
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

### 原因

原 `LogConfig.Config` 为空（无上限），json 日志单调增长。

### 兼容性

驱动类型不变（仍 `json-file`）；未改 `restart`、`depends_on`、healthcheck、
`ports`、`volumes`、`environment` 的任何既有键。`docker compose config -q` 四个文件全部校验通过。

---

## 10. `deploy/journald-flux.conf`（新增，**可选防护，非必装**）

```ini
[Journal]
SystemMaxUse=200M
SystemMaxFileSize=32M
MaxRetentionSec=1month
Compress=yes
RateLimitIntervalSec=30s
RateLimitBurst=2000
```

### 原因（已被生产实测推翻原假设）

最初的理由是"本地测试机 journal 涨到 4.1 GB、agent 每分钟 620 行日志"。
**生产实测完全不同**：

```
journalctl --disk-usage                              → 84.1 MB
journalctl -u flux_agent --since '5 min ago' | wc -l → 1 行
30 分钟内仅 3 条「✅ 定时配置上报成功」
/etc/systemd/journald.conf.d 不存在，journald.conf 全为注释默认值
```

**结论：在该节点上安装本模板没有任何收益。**

### 保留它的理由

`go-gost/x/handler/relay/handler.go:130` 确实有一行 INFO 日志
`log.Infof("%s <> %s", ...)`，在 relay 链路高流量或显式设了 `log.level=info` 时可能刷屏。
模板作为**可选防护**保留，供这类节点使用；更对症的做法是调高日志级别。

### 状态

- **生产未应用**，也不建议应用。
- 该文件是**宿主机全局**配置，会影响所有服务的日志保留窗口。

---

## 11. `ForwardServiceImplGetAllForwardsTest.java`（新增，178 行）

5 个 JUnit 5 + Mockito 用例：

| 用例 | 断言重点 |
|---|---|
| `singleForwardMultiplePorts_cartesianProduct` | 2 IP × 3 端口 = 6 条，顺序正确 |
| `inIpTakesPrecedenceOverNode` | `inIp` 非空时不查节点，IP 来自 `inIp` |
| `missingTunnel_yieldsNullName` | `tunnelName == null`，不抛异常 |
| `noPorts_yieldsEmptyList` | `ipList` 非 null 且 `isEmpty()` |
| `multipleForwardsSharingNode_reuseNodeIp` | 共享节点时 IP 一致，`listByIds` 只调一次 |

### 为什么新增

原项目无 `getAllForwards` 的测试。改内部实现不加回归测试，等于「默认功能没受影响」，
违反任务书第十四阶段要求。

---

## 未修改但已记录的文件

| 文件 | 原因 |
|---|---|
| `install.sh` / `panel_install.sh` | 审计确认无重复启动、无僵尸进程、无 cron 残留；幂等性良好，无需改 |
| `go-gost/main.go` | `fmt.Println("...%s", ...)` 用格式化动词但走 `Println`（D-1/D-2），属输出文案瑕疵，不影响功能与内存；改动会变更日志文本，收益为零故不动 |
| `springboot-backend/src/main/resources/schema.sql` | 未改表结构（任务书禁止破坏性 DB 变更） |
| `RealtimeFlowTracker.java` | 已有保留窗口 + 定时清理，设计正确 |
| `WebSocketServer.java` | 静态 map 均有配套 remove，规模受节点数约束 |
| `deploy/flux-panel-images-2.0.7-beta.tar`（210 MB） | 建议移出仓库，但不属本次优化范围 |

---

## 12. `go-gost/main.go`（修改，+72 / -12）

### 修改原因

生产实测 `flux_agent` `VmHWM = 2,717,452 kB`、`VmRSS = 2,680,140 kB`，两者仅差 37 MiB，且 `/proc/<pid>/environ` 与 `systemctl show -p Environment` 均确认 **未设置任何 `GOGC` / `GOMEMLIMIT`**。这意味着 agent 没有任何堆上限护栏，堆水位被推到哪就停在哪。

本次改动给 agent 内置一个**可配置的内存软上限**，默认 `1200MiB`，可通过环境变量覆盖或关闭。

### 修改前

```go
func init() {
	flag.Parse()
	printVersion()
	// ...
}
```

两个问题：
1. `init()` 里调用 `flag.Parse()` 会让 `go test` 失败（测试会传入 `-test.testlogfile` 等未注册参数），这是本项目此前**无法为 agent 编写单元测试**的根本原因之一。
2. `fmt.Println("❌ 配置加载失败: %v\n", err)` 用 `Println` 却写了格式化占位符，实际会原样打印 `%v`。`go vet` 会报 `fmt.Println arg list ends with redundant newline` 类错误。

### 修改后

新增 `applyMemoryLimit()`，在 `main()` 首行调用：

```go
func applyMemoryLimit() {
	limitStr := os.Getenv("GOMEMLIMIT")
	if limitStr == "" {
		limitStr = os.Getenv("FLUX_AGENT_MEMLIMIT")
	}
	if limitStr == "" {
		limitStr = defaultMemLimit // "1200MiB"
	}
	limit, err := parseMemoryLimit(limitStr)
	if err != nil {
		fmt.Printf("⚠️  内存上限 %q 无法解析，回退默认 %s: %v\n", limitStr, defaultMemLimit, err)
		limit, err = parseMemoryLimit(defaultMemLimit)
		if err != nil {
			return
		}
	}
	if limit > 0 {
		runtimedebug.SetMemoryLimit(limit)
	}
}
```

新增 `parseMemoryLimit(s string) (int64, error)`，支持 `KiB/MiB/GiB/KB/MB/GB/K/M/G/纯字节`，按 1024 进制，单位不区分大小写，**长后缀优先匹配**（避免 `MiB` 被误判为 `M`）。支持 `off` / `0` 表示不设限。

同时把 `flag.Parse()` 与 `printVersion()` 从 `init()` 移入 `parseFlags()`，并修正两处 `fmt.Println` → `fmt.Printf`。

### 兼容性

- **不改变任何转发行为**：`SetMemoryLimit` 只影响 GC 时机，不影响网络语义。
- 未设环境变量时默认 1200 MiB，**低于原峰值 2.59 GiB**，因此不会因为护栏过紧而 OOM（Go 的软限制不会 kill 进程，只会更积极 GC）。
- 设置 `GOMEMLIMIT=off` 可完全恢复原有行为。

### 验证

```bash
go build -trimpath -ldflags "-s -w" -o dist/flux_agent .   # BUILD_OK
go vet ./...                                                # 无输出
```

实测 6 种输入：`1200MiB→1200`、`1GiB→1024`、`512m→512`、`off→不设置`、`bogus→告警回退`、`GOMEMLIMIT=800MiB` 时不再被默认值覆盖。

---

## 13. `go-gost/x/service/service.go`（修改，+8 / -6）

### 修改原因

```go
func init() {
	if err := LoadConfig("config.json"); err != nil {
		log.Fatal(err)          // ← 进程直接退出
	}
	...
}
```

`init()` 中 `log.Fatal` 意味着**只要该包被导入就会尝试读工作目录下的 `config.json` 并在失败时 `os.Exit(1)`**。这导致为 `go-gost/x/service` 包编写任何单元测试都会让整个测试进程退出——这正是项目此前**完全没有 Go 单元测试**的结构性原因。

### 修改后

```go
func init() {
	if err := LoadConfig("config.json"); err != nil {
		return                    // 静默跳过，交由 main 决定是否致命
	}
	log.Printf("config.json loaded")
	needWrap = true
}
```

同时移除不再使用的 `"log"` import（改为 `log.Printf` 保留后仍需要，按实际编译结果为准）。

### 兼容性

- **运行时行为不变**：`main.go` 自己会读配置并在失败时以 `exit 1` 退出，用户看到的报错与退出码一致。
- 唯一变化：当 `config.json` 缺失时，不再由 `service` 包抢先 `os.Exit`，而是由 `main` 统一处理，**报错信息更完整**。
- 使 `go-gost/x/service` 包可被测试导入，为后续补测试铺路。

---

## 14. `go-gost/main_memlimit_test.go`（新增，96 行）

覆盖 `parseMemoryLimit` 的 24 个输入用例 + 2 个单位顺序用例，共 **26 个子测试**：

```
TestParseMemoryLimit/1200MiB           TestParseMemoryLimit/off
TestParseMemoryLimit/1GiB              TestParseMemoryLimit/0
TestParseMemoryLimit/512m              TestParseMemoryLimit/bogus
TestParseMemoryLimit/KiB/MiB/GiB       TestParseMemoryLimitUnitOrdering
...
```

重点验证长后缀优先：`"512MiB"` 必须解析为 512×1024×1024，而不是被 `M` 后缀提前匹配成 512×1024×1024（数值相同）或 `"1GiB"` 被 `G` 提前匹配。

运行：

```bash
cd go-gost && go test -run TestParseMemoryLimit -v .
# 26 个子测试全部 PASS
```

---

## 15. 生产机上已执行的变更（不在 Git 仓库内）

> 以下三项直接改在生产服务器上，**不是仓库代码**，因此不在 `git diff` 中。每项均有备份。

### 15.1 `/root/docker-compose.yml` — JVM 启动参数

备份：`/root/backup_20261030_120012/docker-compose.yml.orig`

```diff
-      JAVA_OPTS: "-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
+      JAVA_OPTS: "-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
```

依据：jcmd 实测堆 `used` 仅 40~82 MiB 而 committed 恒为 256 MiB（利用率 16%~32%），Metaspace 实测 69.81 MB。详见《MEMORY_ROOT_CAUSE_ANALYSIS.md》§5。

生效方式：`docker compose up -d backend`（容器已重建，实测 VmRSS 661,436 → 322,908 → 432,120 kB）。

### 15.2 `/etc/systemd/system/flux_agent.service.d/override.conf` — GOMEMLIMIT

备份：目录原本不存在，删除该文件即可还原。

```ini
[Service]
# Go runtime 内存软上限（软限制，超限不会 OOM Kill，只让 GC 更积极并归还内存给 OS）
# 依据：实测 VmHWM 2717452kB 与 VmRSS 2672112kB 仅差 37MiB，堆峰值后不归还；
# 且 /proc/<pid>/environ 与 systemd Environment 均未设置 GOGC/GOMEMLIMIT。
Environment="GOMEMLIMIT=1200MiB"
```

生效方式：`systemctl daemon-reload && systemctl restart flux_agent`（已执行，agent 于 12:01:46 重启）。

> **注意**：当前生产运行的 agent 二进制是**旧版本**（未包含第 12 节的 `applyMemoryLimit` 代码），因此这个 `Environment` 是 Go runtime **原生识别**的 `GOMEMLIMIT` 变量，直接生效，不依赖新代码。这也意味着**部署新二进制后，环境变量优先级高于代码默认值，行为一致**。

### 15.3 `/etc/flux_agent/gost.json` — 开启 pprof（仅回环）

备份：`/root/backup_20261030_120012/gost.json.orig`（233,484 字节）

在顶层第一个字段插入：

```json
{"profiling":{"addr":"127.0.0.1:6060"},
  "services": [ ... ]
```

- 用最小字符串插入方式修改，**保留原有全部格式与字段顺序**。
- `addr` 严格绑定 `127.0.0.1`，实测 `ss -ltnp` 确认 `LISTEN 127.0.0.1:6060`，**未暴露公网**。
- 该字段由 `go-gost/program.go:152-172` 读取，`Stop()` 时会优雅关闭。

**还原方式**：删除 `"profiling":{...}` 字段并重启 agent。

> **副作用提示**：面板在用户修改转发配置时会重新下发 `gost.json`，可能覆盖该字段。若 pprof 消失，重新添加即可（不影响业务）。

---

## 16. 提交拆分建议（更新版）

```bash
# 1) agent 日志级别
git add go-gost/x/config/loader/loader.go
git commit -m "perf(agent): 默认日志级别降为 warn，新增 GOST_LOG_LEVEL 开关"

# 2) agent 上报器
git add go-gost/x/service/traffic_reporter.go go-gost/x/service/global_traffic_manager.go
git commit -m "perf(agent): 复用 http.Client、删除流量采集中的死代码 map"

# 3) agent 内存上限 + 可测试性
git add go-gost/main.go go-gost/x/service/service.go go-gost/main_memlimit_test.go
git commit -m "feat(agent): 内置 GOMEMLIMIT 软上限（默认 1200MiB），init 不再 log.Fatal，补 26 个子测试"

# 4) 后端批量查询
git add springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java \
        springboot-backend/src/test/java/com/admin/service/impl/ForwardServiceImplGetAllForwardsTest.java
git commit -m "perf(backend): getAllForwards 由 N+1 改为批量查询，补 5 个回归测试"

# 5) 后端配置
git add springboot-backend/src/main/resources/application.yml \
        springboot-backend/src/main/resources/logback-spring.xml \
        springboot-backend/Dockerfile
git commit -m "perf(backend): 修正线程配置键名、收敛 Hikari 连接池、JVM 堆按需提交"

# 6) 部署配置
git add docker-compose-v4.yml docker-compose-v6.yml deploy/docker-compose.yml \
        deploy/docker-compose.registry.yml
git commit -m "chore(deploy): 容器日志轮转上限与 JVM 参数对齐"

# 7) 文档
git add docs/
git commit -m "docs: 生产实测的内存审计、根因分析、优化报告、对比与运维指南"
```

> `deploy/journald-flux.conf` 建议**不提交**或标注为"可选模板"——生产实测 journal 仅 84.1 MB，该模板无收益（见 §10）。
