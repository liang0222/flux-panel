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

## 6. 未测试 / 未验证项（必须明确标注）

| 项目 | 状态 | 原因 |
|---|---|---|
| **24 小时内存趋势** | ❌ **未测试** | 变更后仅观察 24 分钟 |
| **72 小时稳定性** | ❌ **未测试** | 同上 |
| **GOMEMLIMIT 的长期效果** | ❌ **未验证** | 只能证明"24 分钟内没涨回去" |
| **优化前后 API 延迟对比** | ❌ **未测试** | 优化前未采集基线，重启后无法回溯 |
| **吞吐量 / QPS 对比** | ❌ **未测试** | 无压测环境，未在生产压测 |
| **错误率对比** | ❌ **未测试** | 同上 |
| **并发能力验证** | ❌ **未测试** | 未做并发压测 |
| **前端页面人工回归** | ❌ **未测试** | 仅验证 HTTP 200，未逐页点击 |
| **移动端兼容性** | ❌ **未测试** | 无设备 |
| **`ForwardServiceImpl` 批量查询的性能收益** | ❌ **未验证** | 代码未部署；单测只验证逻辑正确性 |
| ~~relay 连接滞留是否修复~~ | ✅ **不存在该问题** | 已证伪：pprof 显示连接:goroutine = 81:81，1:1 对应 |
| **Docker 日志轮转是否生效** | ❌ **未生效** | 需容器重建 |

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
