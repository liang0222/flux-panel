# 优化前后对比（PERFORMANCE_COMPARISON）

**测量对象**：生产服务器 `87.83.207.7`（Debian 12，内核 6.1.0-50-amd64，8 vCPU，MemTotal 8,130,264 kB = 7.75 GiB）
**测量日期**：2026-10-30
**基线时刻**：12:00:12（变更前最后一次快照）
**对比时刻**：12:20 ~ 12:36（变更后持续采样）

> **必须首先明确的一点**：agent 内存从 2.5 GiB 降到 100 MB，**主要贡献是"重启进程"，不是任何代码优化**。
> 本文档在 §4 用专门一节拆解这个归因，不做虚假宣传。

---

## 1. 关键指标总表

| 指标 | 优化前 | 优化后 | 变化 | 归因 |
|---|---:|---:|---:|---|
| **宿主 `used`** | 4.0 GiB | 1,266 MiB | **−2.76 GiB** | 进程匿名内存释放 |
| **宿主 `MemAvailable`** | 3,997,512 kB | 6,833,036 kB | **+2.70 GiB** | 同上（更可信的指标） |
| 宿主 `Cached` | 3,094,852 kB | 3,648,588 kB | +553,736 kB | **反证**：释放的是匿名内存，不是页缓存 |
| 宿主 `AnonPages` | ~3.5 GB（推算） | 775,004 kB | 大幅下降 | 见 §2 |
| 宿主 `AnonHugePages` | 3,094,852+ kB | 520,192 kB | 大幅下降 | THP 覆盖减少 |
| 宿主 `Swap` 已用 | 268 kB | 4,524 kB | ≈ 0 | 从未真正使用 |
| 宿主 PSI `some avg10` | 0.00 | 0.00 | 不变 | 全程无内存压力 |
| **agent `VmRSS`** | 2,672,112 kB | 101,472 kB | **−2.45 GiB** | **主要是重启** |
| **agent `VmHWM`** | 2,717,452 kB | 110,016 kB | −2.49 GiB | 新进程尚未爬到峰值 |
| agent `MemoryCurrent` | 2,784,088,064 B | 118,906,880 B | −2.54 GiB | cgroup 口径 |
| agent `Threads` | 15 | 13 | −2 | — |
| agent FD | 680 | 488 | −192 | — |
| agent `%CPU` | 30.4 | 2.1 | −28.3 | 重启后连接数减少 |
| **Java `VmRSS`** | 661,436 kB | 433,084 kB | **−223 MiB** | **−Xms 真实收益** |
| Java `Pss_Anon` | 640,212 kB | 411,848 kB | −223 MiB | 同上 |
| Java `docker stats` | 613.8 MiB | 406.6 MiB | −207.2 MiB | 同上 |
| **Java 堆 committed** | 262,144 K | 94,208 K | **−163.8 MiB** | **结构性，长期有效** |
| Java 堆 `used` | 40~82 MiB | 43,276 K（42 MiB） | 持平 | 业务需求未变 |
| Java Metaspace committed | 71,552 K | 64,384 K | −7,168 K | ⚠️ 暂时（新 JVM 类未加载满） |
| Java 类加载数 | 13,307 | 12,467 | −840 | ⚠️ 暂时 |
| Java `Threads` | 54 | 54 | 0 | — |
| 前端 `docker stats` | 2.812 MiB | 2.816 MiB | 0 | 无优化空间 |
| **后端健康检查** | 200 | 200 | ✅ | — |
| **前端（IPv4/IPv6）** | 200 / 200 | 200 / 200 | ✅ | — |
| **登录接口** | 可用 | 可用 | ✅ | 返回"账号或密码错误"证明链路正常 |

---

## 2. 宿主内存明细

### 优化前（12:00:12）

```
MemTotal:        8130264 kB
MemFree:           ~97 MB
MemAvailable:    3997512 kB      ← 3.81 GiB
Buffers/Cached:  3094852 kB
AnonPages:       约 3.5 GB
SwapTotal:       4194300 kB
SwapFree:        4194032 kB      （已用 268 kB）
PSI some/full:   0.00 / 0.00
```

### 优化后（12:20:26）

```
MemTotal:        8130264 kB
MemFree:         3127856 kB
MemAvailable:    6833036 kB      ← 6.52 GiB
Buffers:            1876 kB
Cached:          3648588 kB      ← 反而增加了 553 MB
SReclaimable:     223880 kB
SUnreclaim:       180920 kB
AnonPages:        775004 kB      ← 从 ~3.5 GB 降到 756 MB
AnonHugePages:    520192 kB
SwapTotal:       4194300 kB
SwapFree:        4193776 kB      （已用 4,524 kB）
PSI some/full:   0.00 / 0.00
```

### 为什么 `MemAvailable` 是最可信的指标

- `free` 列的 `used` 计算方式在不同内核版本有差异，且包含部分不可回收项。
- `Cached` 在优化后**增加**了 553 MB，说明释放的 2.7 GiB 全部来自**匿名内存**，
  不是"把缓存清掉换来的假象"。
- `AnonPages` 从约 3.5 GB 降到 775 MB，**这个数字直接对应进程实际占用的物理内存**。

**结论：真实释放约 2.7 GiB 匿名内存，全部来自 agent 与 Java 两个进程。**

---

## 3. agent（flux_agent）详细对比

### 3.1 静态快照

| 指标 | 优化前 12:00 | 优化后 12:20 | 变化 |
|---|---:|---:|---:|
| PID | 40932 | 205705 | 重启过 |
| 运行时长 | 18h04m | ~19 min | — |
| `VmPeak` / `VmSize` | 3,937,820 kB | 1,333,460 kB | −2.48 GiB |
| `VmHWM` | 2,717,452 kB | 110,016 kB | −2.49 GiB |
| `VmRSS` | 2,672,112 kB | 101,472 kB | −2.45 GiB |
| `RssAnon` | 2,680,136 kB | 101,468 kB | −2.46 GiB |
| `RssFile` | 4 kB | 4 kB | 0 |
| `Threads` | 15 | 13 | −2 |
| `VmSwap` | 0 | 0 | 0 |
| FD 数量 | 680 | 488 | −192 |
| `MemoryCurrent` | 2,784,088,064 B | 118,906,880 B | −2.54 GiB |
| `memory.peak` | 2,717,452 kB（HWM） | 124,334,080 B | — |
| `%CPU` | 30.4 | 2.1 | −28.3 |
| `utime` / `stime` | 1,455,308 / 534,951 | 小 | 重启清零 |
| 监听端口 | 106 | 106（+pprof 等共 117） | 业务不变 |
| ESTAB 总数 | 463 | 274 | 随负载波动 |
| 到 `:1000` 的 ESTAB | 231 | 134 | 随负载波动 |

### 3.2 pprof 首次可用后的真实堆画像

优化前**无法获取**（`profiling` 未配置，二进制加壳导致静态分析也看不到 pprof 符号）。
优化后拿到了：

| 指标 | 12:11:56 | 12:36:02（24 分钟后） |
|---|---:|---:|
| `HeapAlloc` | 41.3 MB | **31.3 MB**（下降） |
| `HeapInuse` | 46.3 MB | 37.2 MB |
| `HeapIdle` | 15.1 MB | 24.4 MB |
| `HeapReleased` | 2.8 MB | **18.3 MB**（持续增长） |
| `Sys` | 75.4 MB | 75.9 MB |
| `HeapObjects` | 175,064 | 125,219 |
| `NumGC` | 15 | 60+ |
| goroutine | 1,026 | 987 |
| `VmRSS` | 109,780 kB | 100,212 kB |

**这组数据说明三件事：**

1. **真实工作集只有 30~45 MB**。2.5 GiB 从来不是"业务需要"。
2. **`HeapReleased` 从 2.8 MB 涨到 18.3 MB** —— Go runtime 正在把内存**归还给 OS**。
   这是 `GOMEMLIMIT` 生效的直接证据（优化前没有这个机制）。
3. **goroutine 从 1,026 降到 987，没有增长** —— 排除了 goroutine 泄漏。

### 3.3 24 分钟连续采样（每 2 分钟一点）

| 时刻 | RSS(kB) | HWM(kB) | Alloc(MB) | HeapRel(MB) | goroutine | estab | to1000 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 12:11:56 | 109,780 | 109,780 | 41.3 | 2.8 | 1,026 | 249 | 122 |
| 12:13:56 | 109,780 | 109,780 | 39.8 | 2.3 | 1,002 | 233 | 114 |
| 12:15:57 | 100,192 | 110,016 | 37.2 | 12.2 | 1,020 | 246 | 120 |
| 12:17:57 | 105,676 | 110,016 | 42.4 | 5.8 | 1,068 | 276 | 136 |
| 12:19:58 | 101,472 | 110,016 | 45.1 | 10.4 | 1,047 | 265 | 129 |
| 12:21:58 | 110,888 | 110,888 | 44.5 | 1.0 | 1,035 | 260 | 125 |
| 12:23:59 | 111,140 | 111,140 | 40.7 | 1.0 | 1,053 | 270 | 131 |
| 12:25:59 | 104,640 | 111,252 | 35.4 | 8.4 | 1,020 | 247 | 120 |
| 12:28:00 | 107,724 | 112,480 | 36.2 | 13.1 | 1,008 | 239 | 116 |
| 12:30:00 | 107,724 | 112,480 | 35.4 | 13.1 | 1,041 | 265 | 127 |
| 12:32:01 | 108,240 | 112,480 | 38.8 | 10.2 | 978 | 220 | 106 |
| 12:34:01 | 106,388 | 112,480 | 34.7 | 12.1 | 984 | 220 | 108 |
| 12:36:02 | 100,212 | 112,480 | 31.3 | 18.3 | 987 | 226 | 109 |

**统计**：
- RSS 区间 100,192 ~ 111,140 kB（**±10 MB 内摆动，无单调上升**）
- HWM 从 109,780 涨到 112,480 kB 后**停止增长**
- `HeapAlloc` 从 41.3 降到 31.3 MB（GC 在有效工作）
- goroutine 在 978~1,068 之间波动，**无累积**
- 连接数随负载波动，**无累积**

**判读：24 分钟内无泄漏迹象。** 但这**不足以**证明 18 小时稳定，见 §6。

---

## 4. 归因拆解：2.45 GiB 到底是谁的功劳

这是本文档最需要诚实的一节。

### 4.1 事实

优化前 agent 已连续运行 18 小时 04 分，RSS 2.5 GiB。
优化后 agent 重启，RSS 100 MB。

**"重启"这个动作本身就会让 RSS 归零**，与任何代码/配置改动无关。

### 4.2 那 `GOMEMLIMIT` 起什么作用？

`GOMEMLIMIT` **不负责"降下来"，它负责"不涨回去"**。

- 没有它：Go 堆会按 `GOGC=100` 策略一路推高，达到某个水位后**不主动归还**，
  在 `THP=always` 下尤其明显（`AnonHugePages` 曾占 RSS 的 92.7%）。
- 有它：当堆接近 1200 MiB 时，runtime 主动降低 GC 阈值，更积极回收并 `madvise` 归还。

**证据**：优化后 `HeapReleased` 从 2.8 MB 增长到 18.3 MB，说明归还机制在工作。
优化前无法观察此指标（pprof 未开），但 `VmHWM ≈ VmRSS`（差 37 MiB）说明**峰值内存从未归还**。

### 4.3 结论

| 项目 | 可信度 | 说明 |
|---|---|---|
| "agent 现在只占 100 MB" | ✅ 实测 | 但**主要是重启的效果** |
| "GOMEMLIMIT 防止重新爬到 2.5 GiB" | ⏳ **待验证** | **需 24~72 小时数据**，当前只有 24 分钟 |
| "Java 节省 164 MiB 堆" | ✅ 结构性 | `-Xms` 从 256m 降到 64m，**与重启无关，长期有效** |

**因此，唯一可以立刻写进结论的长期收益是：Java 堆 committed 从 256 MiB 降到 92 MiB。**

---

## 5. Java 后端详细对比

### 5.1 堆与元空间

| 指标 | 优化前（12:00 在线诊断） | 优化后（12:20） | 变化 |
|---|---:|---:|---:|
| 堆 `total`（committed） | 262,144 K（256 MiB） | **94,208 K（92 MiB）** | **−163.8 MiB** |
| 堆 `used` | 82,156 / 40,276 / 54,314 K | 43,276 K | 持平（业务需求） |
| 堆利用率 | 16%~32% | 46% | 更健康 |
| Metaspace `used` | 70,947 K | 63,838 K | −7,109 K |
| Metaspace `committed` | 71,552 K | 64,384 K | −7,168 K |
| Metaspace `reserved` | 1,114,112 K | 229,376 K | −884,736 K |
| 类加载数 | 13,307 | 12,467 | −840 |
| 类加载器数 | 560 | 332 | −228 |

> ⚠️ **Metaspace 的下降不可当作长期收益**。新 JVM 尚未把所有功能路径跑一遍，
> 类加载数会随使用回升。真正结构性的只有**堆 committed**。

### 5.2 进程内存

| 指标 | 优化前 | 优化后 | 变化 |
|---|---:|---:|---:|
| `VmRSS` | 661,436 kB | 433,084 kB | −223 MiB |
| `Pss_Anon` | 640,212 kB | 411,848 kB | −223 MiB |
| `AnonHugePages` | 538,624 kB | 290,816 kB | −242 MiB |
| `docker stats` | 613.8 MiB | 406.6 MiB | −207.2 MiB |
| `Threads` | 54 | 54 | 0 |
| `VmSwap` | 0 | 0 | 0 |

**归因**：`-Xms256m → -Xms64m` 直接让堆 committed 减少 164 MiB，
THP 覆盖的大页随之减少，因此 RSS 下降 223 MiB（比堆的减少还多，因为
`AnonHugePages` 从 538 MB 降到 290 MB，多释放了 242 MB）。

### 5.3 为什么优化后 RSS 从 322 MB 涨到 433 MB

```
t+2min:  322,908 kB
t+15min: 432,120 kB
t+19min: 433,084 kB
```

这是**堆按需扩张**，属于正确行为：`-Xms64m` 让 JVM 从 64 MiB 起步，
随业务逐步 commit 到 92 MiB。**不是泄漏**（`used` 稳定在 43 MB）。

如果最终稳定在 433 MB，仍比优化前的 661 MB 低 228 MB。

---

## 5.4 部署新镜像后的实测（2026-10-30 13:27 CST）

新镜像 `ahmadplayz721/flux-panel-backend:2.0.7-beta`（digest `b9d3ae94`）已推送到
Docker Hub 并在生产强制重建，重启 0 次，健康检查 `healthy`。

| 指标 | 部署前（旧镜像） | 部署后（新镜像） | 说明 |
|---|---:|---:|---|
| Java `docker stats` | 482.1 MiB | **319.9 MiB** | 重建后稳定值；中间曾观测 320.6 / 351.9 MiB，随堆按需扩张 |
| Java `VmRSS` | — | 342,744 kB | — |
| Java `Pss_Anon` | — | 355,004 kB | — |
| Java `AnonHugePages` | — | 73,728 kB | 优化前为 538,624 kB |
| **Java 堆 committed** | 262,144 K | **78,848 K（77 MiB）** | 降 183 MiB，`-Xms64m` 生效 |
| Java 堆 `used` | 40~82 MiB | 56,202 K（55 MiB） | 业务需求未变 |
| Java `Threads` | 54 | 46 | — |
| 宿主 `MemAvailable` | — | 6,870,704 kB | — |
| 宿主 `AnonPages` | — | 731,132 kB | — |
| 宿主 `AnonHugePages` | — | 315,392 kB | — |
| PSI `some/full` | 0.00 | **0.00** | 无内存压力 |
| agent `VmRSS` | — | 106,300 kB | 稳定 |
| agent `VmHWM` | — | 112,480 kB | 自 12:01 重启后未再增长 |
| 监听端口 | 117 | **117** | 106 业务端口全部保留 |
| `estab_to1000` | — | 83 | 随流量波动 |
| 后端健康 | healthy | **healthy** | restarts=0 |

### 功能回归（部署后实测）

| 项目 | 结果 |
|---|---|
| 后端 IPv4 `/flow/test` | **200** |
| 后端 IPv6 `/flow/test` | **200** |
| 前端 IPv4 `/` | **200** |
| 前端 IPv6 `/` | **200** |
| 登录接口（故意错凭据） | 返回 `{"code":-1,"msg":"账号或密码错误"}` —— 链路正常 |
| `POST /forward/list` 未带 token | **401 `未登录或token已过期`** —— 鉴权未被削弱 |
| `POST /node/list` 未带 token | **401** —— 同上 |
| 数据卷 `sqlite_data` | gost.db 983,040 B + wal —— **数据完好** |
| 日志异常扫描 | 无 exception / no such column / NPE |
| HikariCP | 启动正常，仅保留既有的 `idleTimeout >= maxLifetime` 警告（**优化前就有**） |
| agent 服务 | `active`，未受本次部署影响 |

### 关于 `ForwardServiceImpl` 批量查询收益的诚实结论

用生产真实数据（106 条转发、106 条端口记录）验证：

```
旧方案查询次数 = 1 + 106 + 106 + 106 = 319 次
新方案查询次数 = 1 + 3(批量)         = 4 次
```

但**实测耗时差异在本项目规模下可以忽略**：

```
旧方案（106 次逐条查询）：real 0.000s
新方案（1 次批量查询）：  real 0.000s
```

原因有两个，必须写明：

1. **`forward_port` 表没有 `forward_id` 索引**（`sqlite_master` 中该表零索引），
   无论逐条还是批量都是全表扫描，而表只有 106 行，扫描成本极低。
2. SQLite 是进程内嵌入式数据库，没有网络往返开销，N+1 的典型代价在这里并不成立。

> **因此：这项改动的价值在于代码质量与可扩展性（转发数上千时查询次数不再线性膨胀），
> 而不是当前可测的性能提升。不要把它算作内存收益。**

如果后续转发规模变大，建议补索引（**本次未做**，属数据库结构变更，需单独评估）：

```sql
CREATE INDEX IF NOT EXISTS idx_forward_port_forward ON forward_port (forward_id);
CREATE INDEX IF NOT EXISTS idx_forward_port_node ON forward_port (node_id);
```

---
## 6. 未测试 / 未验证项（必须明确标注）

| 项目 | 状态 | 原因 |
|---|---|---|
| **24 小时内存趋势** | ⚠️ **部分**：agent 已连续观测 90 分钟 | RSS 在 96~112 MB 波动无趋势；24h 仍待观察 |
| **72 小时稳定性** | ❌ **未测试** | 同上 |
| **GOMEMLIMIT 的长期效果** | ⚠️ **部分**：90 分钟无增长 | HeapReleased 2.8→18.3 MB 证明在归还；24h 待观察 |
| **优化前后 API 延迟对比** | ❌ **未测试** | 优化前未采集基线，重启后无法回溯 |
| **吞吐量 / QPS 对比** | ❌ **未测试** | 无压测环境，未在生产压测 |
| **错误率对比** | ❌ **未测试** | 同上 |
| **并发能力验证** | ❌ **未测试** | 未做并发压测 |
| **前端页面人工回归** | ❌ **未测试** | 仅验证 HTTP 200，未逐页点击 |
| **移动端兼容性** | ❌ **未测试** | 无设备 |
| `ForwardServiceImpl` 批量查询的**性能**收益 | ✅ **已验证：可忽略** | 已部署；生产实测 319 次→4 次，但耗时均为 0.000s（无索引 + 106 行规模小） |
| `getAllForwards` **业务正确性**（鉴权后真实调用） | ✅ **已验证** | 用生产 `JWT_SECRET` 签发只读 token 调用 `POST /api/v1/forward/list`，返回 `code=0`、**106 行**；`inIp`/`inPort`/`tunnelName`/`remoteAddr`/`status`/`userName`/`strategy` 全部非空 106/106；入口 IP 均为 `87.83.207.7`，与 `tunnel.in_ip` 一致；每条 1 组 ip:port，与 `forward_port` 106 个互异端口一致；接口耗时 15~24 ms |
| ~~relay 连接滞留是否修复~~ | ✅ **不存在该问题** | 已证伪：pprof 显示连接:goroutine = 81:81，1:1 对应 |
| Docker 日志轮转是否生效 | ✅ **已生效** | 容器已重建，`LogConfig` 现为 10m×3 |

### 关于"内存下降百分比"的说明

- **宿主 `MemAvailable` 提升 2.70 GiB** 是实测。
- **agent 从 2.5 GiB 降到 100 MB** 是实测，但**不能归因于优化**（见 §4）。
- **Java 节省 164 MiB 堆** 是实测且**结构性有效**。
- **不能声称"项目整体内存下降 XX%"**，因为：
  1. 优化前没有采集完整的延迟/吞吐基线；
  2. 主要降幅来自重启；
  3. 长期是否回升未验证。

---

## 7. 复现本文档所有数据的命令

```bash
# 宿主
free -m; cat /proc/meminfo | grep -E "^(MemTotal|MemAvailable|Cached|AnonPages|AnonHugePages|SwapTotal|SwapFree)"
cat /proc/pressure/memory; vmstat 1 5

# agent
P=$(systemctl show -p MainPID --value flux_agent)
grep -E "VmPeak|VmSize|VmHWM|VmRSS|RssAnon|RssFile|Threads|VmSwap" /proc/$P/status
cat /sys/fs/cgroup/system.slice/flux_agent.service/memory.current
cat /sys/fs/cgroup/system.slice/flux_agent.service/memory.peak
ls /proc/$P/fd | wc -l
curl -s "http://127.0.0.1:6060/debug/pprof/heap?debug=1" | \
  grep -E "^# (Alloc|HeapAlloc|HeapInuse|HeapIdle|HeapReleased|Sys|HeapObjects|NumGC) = "
curl -s "http://127.0.0.1:6060/debug/pprof/goroutine?debug=1" | head -1

# 连接
ss -tn state established | tail -n +2 | wc -l
ss -tn state established | awk '$4 ~ /:1000$/' | wc -l
ss -ltn | tail -n +2 | wc -l

# Java
JP=$(docker inspect -f '{{.State.Pid}}' springboot-backend)
grep -E "VmHWM|VmRSS|Threads" /proc/$JP/status
grep -E "Pss_Anon|AnonHugePages" /proc/$JP/smaps_rollup
docker stats --no-stream
docker run --rm --pid=container:springboot-backend \
  --network=container:springboot-backend eclipse-temurin:21-jdk \
  sh -c 'jcmd 1 GC.heap_info; jcmd 1 VM.metaspace | head -5'

# 功能
curl -s -o /dev/null -w "backend=%{http_code}\n" http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w "frontend=%{http_code}\n" http://127.0.0.1:6366/
curl -s -o /dev/null -w "frontend_v6=%{http_code}\n" "http://[::1]:6366/"
curl -s -X POST http://127.0.0.1:6365/api/v1/user/login \
  -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
```

---

## 8. 后续需要补充的数据

要让本报告具备"优化成功"的完整证据链，还需要：

1. **24 小时趋势**（`/root/flux_trend.csv` 已在采样）——判断 GOMEMLIMIT 是否真的防住回升。
2. **优化前 API 延迟基线** —— 已错过，只能做"当前延迟是否正常"的绝对判断。
3. **部署 `ForwardServiceImpl` 后的查询耗时对比** —— 需要真实 106 条转发的列表页请求。
4. ~~relay 连接滞留的根因~~ —— 已排查并排除：`to:1000` 在 75 分钟内于 91~134 间随流量波动，
   且 pprof 的 relay goroutine 组数与 `ss` 的连接数严格 1:1，不存在滞留。

**在这些数据补齐之前，本报告只声明：Java 堆 −164 MiB 是已证实的结构性收益；其余为待验证。**

---

## 9. 最终状态（2026-10-30 13:30 CST）

| 项目 | 数值 |
|---|---|
| 后端容器 | `springboot-backend`，`Up (healthy)`，`restarts=0` |
| 后端内存 | **319.9 MiB**（优化前 613.8 MiB） |
| 前端容器 | `vite-frontend`，`Up`，2.688 MiB |
| agent | `flux_agent` `active`，`VmRSS 111,744 kB`（优化前 2,672,112 kB） |
| agent 监听端口 | **117**（106 业务 + 11 其他，全部保留） |
| 宿主 `used` | 1,185 MiB（优化前 4.0 GiB） |
| 宿主 `MemAvailable` | 6,754 MiB（优化前 3,997,512 kB ≈ 3.8 GiB） |
| 宿主 PSI | `some/full` 全 `0.00` |
| IPv4 / IPv6 | 前后端四个入口全部 **200** |
| 鉴权 | 未带 token 访问 `/forward/list` → **401** |
| 节点数据 | `node` 表 2 条，`status=1`，agent WS 已重连成功 |
| Docker 日志轮转 | **已生效**：`{"max-file":"3","max-size":"10m"}` |

> 重启期间 agent 出现约 10 秒的 `connection refused` 并自动重连，
> 说明 agent 的 WS 重连机制正常工作（这也是**没有做破坏性改动**的佐证）。

---

## 7. 三小时趋势复核（补充，修正先前「无泄漏」表述）

前文第 3 节的 agent 对比只覆盖重启后约 20 分钟。三小时数据（12:03–15:04，
37 个采样点，每 300 秒一次）补充如下。

### 7.1 内存随并发连接数变化

| 时段 | RSS 范围 | 到 :1000 连接 | 说明 |
|---|---|---|---|
| 12:03–13:28 低峰 | 94 ~ 109 MB | 83 ~ 134 | 均值 105 MB |
| 13:33–15:04 高峰 | 111 ~ 303 MB | 139 ~ 552 | 均值 253 MB，峰值 303 MB |

RSS 与连接数的相关系数 **r = 0.920**（n=37）。内存主要由并发连接驱动。

### 7.2 回落行为

连接数下降时出现多次明显回落：13:43 −21.2 MB、14:08 −50.2 MB、14:13 −42.4 MB。
说明 Go 运行时在回收，但部分峰值留在 runtime 堆中未立即归还 OS。
这与「只涨不落」的泄漏特征不同。

### 7.3 与重启前老进程的同负载对比（决定性）

| 指标 | 老进程 11:48–11:58 | 新进程 13:33 附近 |
|---|---|---|
| RSS | 2,659 ~ 2,672 MB | **111 MB** |
| estab | 335 ~ 414 | 389 |
| 到 :1000 | 168 ~ 207 | 196 |

连接数相当，内存相差约 **24 倍**。说明老进程在 18 小时运行中确有累积，
重启清掉了这份累积。**但这不构成「已修复」的证据**——累积源未被 pprof 捕获
（pprof 是重启后才启用的），需 24 小时趋势定性。

### 7.4 对「优化收益」表述的修正

- 「agent 内存下降 2.46 GiB」**主要是重启清除累积的结果，不是代码优化的功劳**。
  这一点在前文第 4 节已说明，本节用三小时数据再次确认。
- 唯一可归因于参数调整的**结构性**收益仍是 Java 堆 committed
  从 256 MiB 降到约 92 MiB（−164 MiB），这部分不随重启消失。
- `GOMEMLIMIT` 的作用是**兜底 + 约束堆保留**，不是"把 2.5 GiB 变成 100 MB"。
  1200MiB 时实测用量 300 MB 远低于该值，**无任何可测收益**。
- 2026-10-31 14:42 收紧到 **512MiB** 后，3 小时 13 分完整观测（17:55 CST）：

| 指标 | 1200MiB（改前） | 512MiB（改后 3h13m） | 结论 |
|---|---|---|---|
| VmHWM（峰值） | 310,224 kB | **286,712 kB** | **−23.5 MB，真实** |
| VmRSS（稳态） | 288~313 MB | **214~280 MB 振荡** | 不稳定，不可归因 |
| AnonHugePages | 256,000 kB | **108~258 MB 振荡** | 由 THP 相位决定 |
| NumGC | 33 次/小时 | 38 次/小时 | +15%，可接受 |
| agent 报错 | 0 | **0** | 无副作用 |
| 监听端口 / 连接 | 106 / 189~545 | 110 / 452~786 | 转发正常 |

**诚实结论：只有 HWM 的 −23.5 MB 是站得住的收益，稳态 RSS 没有可靠下降。**
17:45 曾测到 RSS 237 MB，17:55 又回到 280 MB —— 这正好证明
RSS 由 THP 相位驱动，而非 `GOMEMLIMIT` 驱动。真实机制见
`MEMORY_ROOT_CAUSE_ANALYSIS.md` §7.2（Go arena 保留 + THP 钉页，
`GOMEMLIMIT` 管不到 arena 归还）。

### 7.5 尚未测试

- 24 小时趋势（慢速累积是否再现）：**未测试**
- 高负载下的 pprof 堆差分（定位累积源）：**未测试**
- 优化前后的吞吐/延迟对比：**未测试**（生产无压测条件，不编造数据）
