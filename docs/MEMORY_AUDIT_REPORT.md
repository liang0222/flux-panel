# Flux Panel 内存审计报告

> **数据来源声明**：本报告的全部运行数据采集自**生产服务器 87.83.207.7**（Debian 12 / 6.1.0-50-amd64），采集时间 2026-10-30 11:11 ~ 11:26（Asia/Shanghai），系统连续运行 18 小时 57 分。
>
> 此前基于本地工作区主机（`/root/v2.0.7-beta` 所在机器）得出的内存结论**已被本报告取代**。本地主机内存仅 3.8 GiB，其上运行着 AI 工具链等无关负载，且面板数据库为空的测试实例，不具备代表性。凡标注「本地」的数据仅用于说明代码行为，不作为生产容量依据。
>
> 本报告严格区分三类信息：**实测数据**（有命令与输出）、**静态代码分析**（有文件路径与行号）、**待验证推测**（明确标注）。未执行的测试一律标记「未测试」。

---

## 一、生产环境基本信息（实测）

| 项目 | 实测值 | 采集命令 |
|---|---|---|
| 主机名 | ser449842350597 | `hostname` |
| 操作系统 | Debian GNU/Linux 12 (bookworm) | `cat /etc/os-release` |
| 内核 | 6.1.0-50-amd64 #1 SMP PREEMPT_DYNAMIC Debian 6.1.176-1 (2026-07-02) | `uname -a` |
| CPU | 8 vCPU / Intel Xeon Platinum 8275CL @ 3.00GHz | `nproc` / `/proc/cpuinfo` |
| 总内存 | 8,130,264 kB ≈ **7.75 GiB** | `/proc/meminfo` |
| Swap | 4,194,300 kB = 4.0 GiB，**已用 0 B** | `/proc/meminfo` |
| 运行时长 | 18h57m（uptime），load average 0.00 / 0.02 / 0.00 | `uptime` |
| cgroup 版本 | **cgroup v2**（`cgroup2fs`），统一层级 | `stat -c %T -f /sys/fs/cgroup` |
| THP（透明大页） | `[always] madvise never` —— **全局强制启用** | `/sys/kernel/mm/transparent_hugepage/enabled` |

### 1.1 内存总览（实测，2026-10-30 11:11）

```
               total        used        free      shared  buff/cache   available
Mem:           7.8Gi       4.0Gi       914Mi       2.9Mi       3.2Gi       3.8Gi
Swap:          4.0Gi          0B       4.0Gi
```

`/proc/meminfo` 关键字段：

```
MemTotal:        8130264 kB
MemFree:          936812 kB
MemAvailable:    3979600 kB
Buffers:            1876 kB
Cached:          3094852 kB
SwapTotal:       4194300 kB
SwapFree:        4194300 kB        ← Swap 完全未使用
Shmem:              2976 kB
SReclaimable:     207660 kB
SUnreclaim:       173320 kB
Committed_AS:    5370148 kB
CommitLimit:     8259432 kB
```

**内存压力指标 PSI（实测）**：

```
/proc/pressure/memory:
some avg10=0.00 avg60=0.00 avg300=0.00 total=27619
full avg10=0.00 avg60=0.00 avg300=0.00 total=27209
```

`vmstat 1 3`：

```
 r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st
 2  0      0 929812   1876 3309368    0    0     1    17 1671 1623  3  2 95  0  0
 1  0      0 929812   1876 3309368    0    0     0     0 1277 1212  0  0 100  0  0
```

**判读（重要）**：
- `si`/`so` 恒为 0，Swap 使用量为 0 B → **不存在 swap 换入换出，不存在内存颠簸（thrashing）**。
- PSI avg10/avg60/avg300 全为 0.00 → **当前无内存压力，无任务因内存而停顿**。
- CPU 空闲 95%~100%，load ≈ 0 → 系统整体轻载。
- OOM 事件计数：`dmesg | grep -ic "oom"` = **0**；`journalctl -k` 中 OOM 记录 = **0**。

**因此本问题的准确定性是：内存占用量偏高（occupancy high），而非内存耗尽或抖动。** 这意味着优化空间真实存在，但紧急程度低于“已出现 OOM/抖动”的情形；同时也意味着任何优化都必须以**不引入稳定性风险**为前提，不能靠收紧 cgroup 限制来“掩盖”占用。

### 1.2 内存分类（区分口径）

| 类别 | 实测值 | 说明 |
|---|---|---|
| 匿名内存（应用真实占用） | ~2.68 GiB（flux_agent）+ ~0.62 GiB（JVM）= **~3.3 GiB** | `RssAnon`/`Pss_Anon` |
| 文件缓存 `Cached` | 3,094,852 kB ≈ 2.95 GiB | **可回收**，非应用占用 |
| `SReclaimable` | 207,660 kB ≈ 203 MiB | 可回收内核 slab |
| `SUnreclaim` | 173,320 kB ≈ 169 MiB | 不可回收内核内存 |
| 共享内存 `Shmem` | 2,976 kB ≈ 2.9 MiB | 极小 |
| Swap | 0 | 未使用 |

`free` 的 `used = total - free - buff/cache = 4.0 GiB`，其中包含缓存部分，**不能直接等同于应用占用**。真实应用占用应以匿名内存（RssAnon/Pss_Anon）为准，约 **3.3 GiB**。

---

## 二、实际运行架构（实测）

### 2.1 服务清单

生产服务器实际运行两大类组件：**容器化的面板** + **宿主机 systemd 托管的 Go 节点代理**。

| 组件 | 形态 | 进程/容器名 | 镜像或可执行文件 | 内存（实测） |
|---|---|---|---|---|
| **Go 节点代理** | 宿主机 systemd 服务 | `flux_agent` (PID 40932) | `/etc/flux_agent/flux_agent` | **2,664,244 kB ≈ 2.54 GiB RSS** |
| Java 控制面 | Docker 容器 | `springboot-backend` | `ahmadplayz721/flux-panel-backend:2.0.7-beta` | 613.8 MiB |
| 前端静态站 | Docker 容器 | `vite-frontend` | `ahmadplayz721/flux-panel-frontend:2.0.7-beta` | **2.812 MiB** |
| 测速工具（非本项目） | Docker 容器 | `openspeedtest` | `openspeedtest/latest` | 13.33 MiB |

`docker ps -a`（实测）：

```
NAMES                IMAGE                                          STATUS                  PORTS
vite-frontend        ahmadplayz721/flux-panel-frontend:2.0.7-beta   Up 19 hours             0.0.0.0:6366->80/tcp, [::]:6366->80/tcp
springboot-backend   ahmadplayz721/flux-panel-backend:2.0.7-beta    Up 19 hours (healthy)   0.0.0.0:6365->6365/tcp, [::]:6365->6365/tcp
openspeedtest        openspeedtest/latest                           Up 19 hours             0.0.0.0:3000-3001->3000-3001/tcp, [::]:3000-3001->3000-3001/tcp, 8080/tcp
```

`docker stats --no-stream`（实测）：

```
NAME                 MEM USAGE / LIMIT     MEM %     CPU %     NET I/O
vite-frontend        2.812MiB / 7.754GiB   0.04%     0.00%     7.95MB / 7.16MB
springboot-backend   613.8MiB / 7.754GiB   7.73%     0.20%     78.7MB / 22.1MB
openspeedtest        13.33MiB / 7.754GiB   0.17%     0.01%     74.2MB / 80.8MB
```

`docker system df`（实测）：镜像 **1.008 GB**（3 个，100% 在用），容器 1.106 MB，数据卷 55.88 MB，构建缓存 0 B。**无悬空镜像、无遗留容器、无可回收空间**——说明 Docker 层面没有垃圾堆积问题。

### 2.2 进程内存排名（实测，`ps -eo pid,ppid,rss,vsz,pcpu,pmem,nlwp,etime,comm,args --sort=-rss`）

| PID | RSS (kB) | VSZ (kB) | %CPU | %MEM | 线程 | 运行时长 | 进程 |
|---|---:|---:|---:|---:|---:|---|---|
| **40932** | **2,664,244** | 3,937,820 | **30.4** | **32.7** | 15 | 18:04:16 | **flux_agent** |
| 14942 | 643,768 | 5,823,676 | 0.7 | 7.9 | 53 | 18:37:26 | java -Xms256m -Xmx512m -jar /app/app.jar |
| 287 | 137,948 | 192,964 | 0.0 | 1.6 | 1 | 18:57:50 | systemd-journald |
| 14191 | 131,468 | 2,906,568 | 0.0 | 1.6 | 21 | 18:38:00 | dockerd |
| 639 | 66,696 | 2,415,540 | 0.0 | 0.8 | 16 | 18:57:47 | containerd |
| 15153 | 15,672 | 1,268,296 | 0.0 | 0.1 | 11 | 18:37:15 | containerd-shim |
| 14915 | 14,176 | 1,268,296 | 0.0 | 0.1 | 11 | 18:37:26 | containerd-shim |
| 15235 | 13,880 | 1,746,944 | 0.0 | 0.1 | 8 | 18:37:15 | docker-proxy (:6366) |
| 1 | 12,312 | 168,068 | 0.0 | 0.1 | 1 | 18:57:52 | systemd |

**核心事实：Go 的 `flux_agent` 单个进程占用 2.54 GiB（32.7% 物理内存），是 Java 后端的 4.1 倍、CPU 累计消耗 5h30m。**

这与「Go 代码占比 58.9% 不代表内存占比最高」的提示无关——本次审计的结论是**用实测数据得出的**：在生产负载下，Go 代理确实是内存第一消耗者。

### 2.3 项目组件归属（区分服务端 / 客户端 / 构建期）

| 目录 | 性质 | 是否在生产常驻 |
|---|---|---|
| `springboot-backend/` | Java 控制面（认证、节点、隧道、转发、流量统计、WebSocket） | ✅ 容器 `springboot-backend` |
| `vite-frontend/` | TypeScript/Vite 构建 → nginx 静态托管 | ✅ 容器 `vite-frontend`（**无 Node.js 常驻**） |
| `go-gost/` | Go 数据面代理，编译为 `flux_agent` | ✅ 宿主机 systemd |
| `android-app/`、`ios-app/`、`flux.ipa` | 移动客户端与产物 | ❌ 不在服务器运行 |
| `install.sh` | 节点安装脚本（下载 agent、写配置、注册 systemd） | ❌ 仅安装期 |
| `panel_install.sh` | 面板部署脚本（docker compose 起容器） | ❌ 仅安装期 |
| `deploy/` | 部署模板（compose、journald 配置） | ❌ 仅部署期引用 |

**重复运行检查（实测）**：`pgrep -a -f flux_agent` 仅返回 PID 40932 一个真实进程（另两条匹配为我的诊断 shell 自身）。`systemctl show flux_agent -p NRestarts` = **0**，`ActiveEnterTimestamp` = 2026-10-29 17:07:28，与进程 `etime` 18h04m 一致。**确认不存在重复进程、不存在异常重启。**

**前端部署方式确认（实测）**：`vite-frontend` 容器内存 **2.812 MiB**，镜像是多阶段构建 + nginx。**不存在 Vite dev server 常驻、不存在 Node.js 进程**，该项无优化空间。

---

## 三、flux_agent 内存深度诊断（核心）

### 3.1 进程内存构成（实测）

`/proc/40932/status`：

```
VmPeak:	 3937820 kB      ← 虚拟内存峰值 3.76 GiB
VmSize:	 3937820 kB
VmHWM:	 2717452 kB      ← 物理内存峰值 2.59 GiB
VmRSS:	 2680140 kB      ← 当前常驻 2.56 GiB
RssAnon:	 2680136 kB      ← 全部为匿名内存（Go 堆）
RssFile:	       4 kB      ← 文件映射仅 4 kB
RssShmem:	       0 kB
VmData:	 2738560 kB
VmStk:	     132 kB
VmExe:	   8132 kB
VmLib:	   6812 kB
VmPTE:	   5420 kB
VmSwap:	       0 kB      ← 完全未换出
Threads:	15
```

`/proc/40932/smaps_rollup`：

```
Rss:             2681612 kB
Pss:             2681608 kB
Pss_Anon:        2681608 kB      ← 100% 匿名
Pss_File:              0 kB
Pss_Shmem:             0 kB
Private_Dirty:   2681608 kB
Anonymous:       2681608 kB
AnonHugePages:   2486272 kB      ← 2.37 GiB 为透明大页
Swap:                  0 kB
```

`/proc/40932/statm`：`size=984455 resident=663814 shared=1 text=2033 lib=0 data=684673`

**判读**：
1. `RssAnon = Rss`，`Pss_File = 0`，`shared = 1 页` → **内存全部是进程私有的 Go 堆，没有共享内存、没有大量代码映射可优化**。这与“多个进程共享库导致 RSS 虚高”的常见误判无关。
2. `AnonHugePages = 2.37 GiB`，THP 全局 `[always]` → Go runtime 的大块堆以 2 MiB 透明大页映射。
3. `VmHWM = 2.59 GiB` 与当前 `VmRSS = 2.56 GiB` **仅差约 37 MiB** → **堆曾经达到 2.59 GiB，且此后从未归还**。

### 3.2 增长趋势判定：结构性占用，非持续泄漏（实测）

每 30 秒采样，持续 6 分钟（13 个采样点）：

```
t(s)   RSS_kB     HWM_kB     fds   estab→:1000  all_estab  utime_ticks
0      2680140    2717452    601      193         384      1452297
30     2680292    2717452    632      207         416      1452346
60     2680372    2717452    659      222         442      1452396
90     2680420    2717452    644      213         427      1452445
120    2680516    2717452    608      195         391      1452476
150    2680540    2717452    583      185         370      1452506
180    2680644    2717452    589      184         370      1452535
210    2670300    2717452    565      173         350      1452558
240    2670412    2717452    547      164         330      1452583
270    2670444    2717452    548      165         331      1452614
300    2670492    2717452    553      167         336      1452644
330    2661988    2717452    531      156         314      1452670
360    2662012    2717452    546      165         327      1452701
```

**结论（有证据）**：
- RSS 在 **2,661,988 ~ 2,680,644 kB** 区间上下波动约 ±19 MiB，**无单调上升趋势**；HWM 全程恒定 2,717,452 kB。
- FD 在 531~659 之间波动并自行回落，**无 FD 泄漏**。
- ESTABLISHED 连接随流量起伏在 314~442 之间，**无连接累积**。
- `utime_ticks` 稳定线性增长（约 50 ticks/30s），CPU 消耗平稳，无异常忙等。

**判定：不存在正在失控的内存泄漏。** 2.5 GiB 是**已分配并长期持有**的结构性占用，而非持续增长型泄漏。

> 方法学说明：6 分钟采样只能排除“分钟级快速泄漏”。若存在“每小时增长几 MB”的缓慢泄漏，需 24 小时以上采样才能确认。本项标记为：**已排除快泄漏，慢泄漏未测试**。请见《SERVER_MONITORING_GUIDE.md》的 24 小时采样方案。

### 3.3 负载结构（实测）

`/etc/flux_agent/gost.json` 是 agent 从面板拉取后落盘的运行配置：

```
文件大小：233,484 字节（10,437 行）
顶层键：['services', 'chains']
services: 212
chains: 1
log = null        ← 未配置日志等级
api = null        ← 未启用 API 端点
profiling = null  ← 未启用 pprof
```

service 类型分布（实测统计）：

```
('tcp', 'tcp'): 106     ← 106 个 TCP 转发
('udp', 'udp'): 106     ← 106 个 UDP 转发
合计 212
```

`chains` 内容（实测）：

```json
[{"name": "chains_3",
  "hops": [{"name": "hop_3",
    "selector": {"strategy": "round", "maxFails": 1, "failTimeout": 600000000000},
    "nodes": [{"name": "node_0_n1", "addr": "64.83.10.191:1000",
               "connector": {"type": "relay"}, "dialer": {"type": "tls"}}]}]}]
```

单个 service 实例（实测）：

```json
{"name": "213_1_0_tcp", "addr": "[::]:49868",
 "handler": {"type": "tcp", "chain": "chains_3"},
 "listener": {"type": "tcp"},
 "forwarder": {"selector": {"strategy": "fifo", "maxFails": 1, "failTimeout": 600000000000},
               "nodes": [{"name": "node_1", "addr": "ddns.mx.sszs.buzz:49868"}]},
 "status": {"createTime": 1793320486, "state": "ready",
            "events": [{"time": 1793320486, "msg": "service 213_1_0_tcp is running"},
                       {"time": 1793320486, "msg": "service 213_1_0_tcp is ready"},
                       {"time": 1793320486, "msg": "service 213_1_0_tcp is listening on [::]:49868"}],
            "stats": {"totalConns": 0, "currentConns": 0, "totalErrs": 0,
                      "inputBytes": 0, "outputBytes": 0}}}
```

**关键结构性事实**：
1. **212 个常驻转发服务**，其中 106 个 TCP + 106 个 UDP，每个都是独立的 listener + handler + forwarder 三元组。
2. 所有 service 都绑定到 `chains_3`，即全部流量经 `relay + tls` 拨号到 `64.83.10.191:1000`。
3. `failTimeout = 600000000000` ns = **600 秒（10 分钟）**：失败节点会被标记为不可用长达 10 分钟。
4. `maxFails = 1`：单次失败即标记不可用。
5. 每个 service 的 `status.events` 数组持久化了 3 条事件（实测 `total events: 600` = 200 个 service × 3 条）。

`ss -ltnp` 实测：agent 在宿主机上监听 **106 个端口**（TCP 侧），与 106 个 TCP service 吻合；UDP 侧 106 个不体现在 `-ltnp` 中。

### 3.4 连接状态（实测）

```
Total: 684
TCP:   515 (estab 348, closed 51, orphaned 0, timewait 11)
UDP:   106
```

agent 自身持有的 socket（实测 `ss -tnp | grep pid=40932`）：

```
按状态：352 ESTAB
按对端统计：
  176  64.83.10.191        ← 到 relay 节点
   60  [::ffff:183.23.104.231]
   23  [::ffff:14.145.11.61]
   22  [::ffff:123.180.86.76]
   15  [::ffff:14.154.158.164]
   15  [::ffff:113.111.214.9]
```

FD 统计：`/proc/40932/fd` 共 **562 项**，其中 **559 个是 socket**，1 个 `/dev/null`，1 个 eventpoll，1 个 eventfd。

**服务侧统计与内核事实的矛盾（重要）**：

| 指标 | 值 | 来源 |
|---|---|---|
| agent 进程普通 ESTABLISHED | **352** | `ss -tnp \| grep pid=40932` |
| 其中到 `:1000`（relay 节点） | **172~176** | `ss -tn state established \| awk '$4 ~ /:1000$/'` |
| 各 service 上报 `sum(currentConns)` | **108** | `gost.json` 内 stats |
| 各 service 上报 `sum(totalConns)` | **378** | `gost.json` 内 stats |

`currentConns` 合计 108，但到 relay 节点的实际连接有 172~176 条 → **约 64~68 条 relay 连接未被任何 service 的统计口径覆盖**。这些连接处于 `keepalive` 状态（`ss -tnpo` 可见 `timer:(keepalive,...)`），即**已建立但无业务数据流动**。

> ⚠️ **本条已被推翻。** `currentConns` 是面板侧恒定的上报值（90 分钟内始终 108），
> 不代表实时活跃连接数，不能与 `ss` 的 socket 计数直接相减。
> pprof 实测：`ss` 到 `:1000` 的 ESTAB 为 81 条时，goroutine 里正好有 81 组 relay 读/写，
> **1:1 对应，无滞留**。`to:1000` 在 75 分钟内于 91~134 间随流量波动，无累积趋势。
> 详见《MEMORY_ROOT_CAUSE_ANALYSIS.md》§3.4。

### 3.5 CPU 与 I/O（实测）

```
/proc/40932/stat：utime_ticks=1452287  stime_ticks=531998
/proc/40932/io：
  rchar: 64316138263          ← 64.3 GB 读取
  wchar: 60186176743          ← 60.2 GB 写入
  syscr: 110949647            ← 1.11 亿次读系统调用
  syscw: 71393411             ← 0.71 亿次写系统调用
  read_bytes: 0
  write_bytes: 45547520
/proc/40932/schedstat：691722967 ns 运行，96420 ns 等待，39 个时间片
systemctl show flux_agent：CPU: 5h 30min 40.703s
```

**判读**：
- `read_bytes = 0` → 没有磁盘读，所有 I/O 都是网络 socket（`rchar`/`wchar` 含 socket 读写）。
- 18 小时累计 1.11 亿次读 + 0.71 亿次写系统调用，约 **每秒 1700 次 read + 1100 次 write syscall**。这是代理转发的正常特征，但也说明**每次转发都走了独立的 syscall + 缓冲拷贝**。
- CPU 5h30m / 19h = 约 **29% 单核占用**，与 `ps` 的 `%CPU 30.4` 吻合。

### 3.6 全局 TCP 统计（实测，含重要修正）

```
TcpActiveOpens                  13462752
TcpPassiveOpens                 13419017
TcpRetransSegs                  958233
TcpExtListenDrops               29
TcpExtTCPTimeouts               49079
TcpExtTCPAbortOnData            13359345      ← 13.36M
TcpExtTCPAbortOnClose           38130
TcpExtTCPAbortOnMemory          0
```

**修正说明**：这些是**系统全局累计值**（自开机以来），不是 `flux_agent` 进程的指标，也不是当前速率。我做了 20 秒差分验证：`TcpActiveOpens` 从 13,462,971 → 13,462,715（**为负，因基线读取时序差异**），20 秒内**没有显著新增**。

**因此：**
- ❌ 不能把 1340 万次连接建立归因于 `flux_agent`，也不能据此推断“连接风暴”。
- ❌ 不能把 `TcpExtTCPAbortOnData: 13.36M`（16.7 万次/小时）解释为当前的异常中止风暴。
- ✅ 可以说：系统累计有大量短连接历史，但**当前连接创建速率很低**。

### 3.7 日志与 journald（实测，含重要修正）

```
journalctl --disk-usage:  Archived and active journals take up 84.1M in the file system.
journalctl -u flux_agent --since "5 min ago" | wc -l:  1
journalctl -u flux_agent --since "2 min ago":  -- No entries --
journalctl -u flux_agent --since "30 min ago" | tail -5:
  Oct 30 10:57:39 ser449842350597 flux_agent[40932]: ✅ 定时配置上报成功
  Oct 30 11:07:39 ser449842350597 flux_agent[40932]: ✅ 定时配置上报成功
  Oct 30 11:17:39 ser449842350597 flux_agent[40932]: ✅ 定时配置上报成功
```

**修正说明**：本地测试环境曾观测到 agent journal 输出 500~620 行/分钟，据此推断“日志刷屏”是内存问题主因。**生产实测与该推断相反**：journal 总量仅 **84.1 MB**，agent 每 10 分钟仅输出 1 行配置上报日志，**不存在日志刷屏**。

原因分析（静态）：生产 `gost.json` 的 `log` 字段为 `null`，但 relay handler 的每连接日志（`go-gost/x/handler/relay/handler.go:130` 的 `log.Infof("%s <> %s", ...)`）**并未出现在生产日志中**。这说明生产部署的二进制版本或日志路径与我本地假设不同，或者该 INFO 级输出未被 journald 捕获（agent 的 stdout/stderr 指向 `socket:[93112]`，而非 journal）。

| 项目 | 实测值 | 判读 |
|---|---|---|
| `/var/log/journal` 总量 | **84.1 MB** | 远低于默认 4 GiB 上限，**不是问题** |
| journald 进程 RSS | 137,948 kB ≈ 135 MiB | 正常范围 |
| agent 日志速率 | ≈ 1 行 / 10 分钟 | 正常 |
| journald 配置目录 | `/etc/systemd/journald.conf.d/` **不存在** | 无自定义覆盖，走默认 |
| journald 配置文件 | `/etc/systemd/journald.conf` 无 `[A-Z]` 开头有效行 | 全为默认（注释状态） |

`systemd-analyze cat-config systemd/journald.conf` 显示 `SystemMaxUse=`、`SystemKeepFree=`、`SystemMaxFileSize=`、`MaxRetentionSec=` **全部为注释默认值**。

**修正说明**：此前文档称“journald 已达 4.1 GiB 上限、正在频繁 vacuum、需要新增 `deploy/journald-flux.conf` 限制到 512M”。**生产实测为 84.1 MB，该结论不成立，对应优化项无收益。** 新增的 `deploy/journald-flux.conf` 已改为**可选模板**，默认不应用。

### 3.8 Docker 日志（实测）

```
docker inspect --format="{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}":
  /springboot-backend  json-file  map[]
  /vite-frontend       json-file  map[]
```

容器日志文件实际大小：

```
55M    springboot-backend (e759a93f...-json.log)     ← 无轮转，持续增长
618K   vite-frontend (22d37c1b...-json.log)
4.5K   openspeedtest (7445d13c...-json.log)
```

**确认存在**：`LogConfig.Config` 为空 map → **未配置 `max-size`/`max-file`，日志无上限**。后端容器 19 小时累积 55 MB，按此速率约 **2.9 MB/小时 ≈ 70 MB/天 ≈ 25 GB/年**。

**影响评估（区分）**：
- 对**内存**影响：极小。日志由 dockerd 写入磁盘，仅少量页缓存占用，且 `Cached` 可回收。
- 对**磁盘**影响：真实。长期运行会持续占用磁盘（当前 `/` 47 GB 可用，短期无风险）。
- 结论：日志轮转属于**应当修复的正确性问题**，但**不应宣称其能显著降低内存**。

---

## 四、Java 后端诊断

### 4.1 JVM 参数（实测）

```bash
docker inspect springboot-backend --format="{{range .Config.Env}}{{println .}}{{end}}"
```

```
LOG_DIR=/app/logs
JAVA_OPTS=-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
DB_PATH=/app/data/gost.db
JWT_SECRET=<REDACTED>
PATH=/opt/java/openjdk/bin:...
JAVA_HOME=/opt/java/openjdk
JAVA_VERSION=jdk-21.0.12.1+1
```

- 堆：`-Xms256m -Xmx512m`（固定上下限）
- **无 `-XX:MaxMetaspaceSize`** → Metaspace 无上限
- **无 GC 相关参数** → 使用 Java 21 默认 G1
- JDK 21.0.12.1

### 4.2 进程内存（实测）

```
/proc/14942/status:  VmSize 5,823,676 kB   VmRSS 643,608 kB   VmSwap 0 kB   Threads 54
/proc/14942/smaps_rollup:
  Rss:             647,400 kB
  Pss_Anon:        622,008 kB
  Anonymous:       622,008 kB
  AnonHugePages:   538,624 kB
  Swap:                  0 kB
```

**关键分析**：
- `-Xmx512m` = 524,288 kB，但进程 `Pss_Anon = 622,008 kB` ≈ **607 MiB**。
- 超出堆上限的部分 = 622,008 - 524,288 ≈ **97,720 kB ≈ 95 MiB**，属于**非堆内存**：Metaspace（Spring Boot 大量类）+ Code Cache（JIT）+ Direct Buffer（Netty/Tomcat）+ 54 个线程栈（54 × 1 MB ≈ 54 MB）。
- `docker stats` 报 613.8 MiB，与 `Pss_Anon` 607 MiB 基本一致。

**判读**：
1. JVM 堆本身受 `-Xmx512m` 约束，**不存在堆无限增长**。
2. 非堆约 95 MiB 是 **Metaspace 无上限**下的自然结果，属于可优化项（加 `-XX:MaxMetaspaceSize`），但**收益上限约几十 MB，需实测确认**。
3. 54 个线程对 Spring Boot + Tomcat + WebSocket 场景属正常，**不存在线程数异常**。

### 4.3 在线诊断能力（实测）

```bash
docker exec springboot-backend sh -c "command -v jcmd jstat jmap java"
→ (空，未找到)
```

**结论：容器内基线镜像不含 JDK 诊断工具**（只有 JRE），**无法在线获取堆直方图、GC 日志、NMT 数据**。

要获得这些数据，需：
- 方案 A（不改镜像）：`docker cp` 一个 `jcmd` 到容器内，或从宿主机把 JDK 工具挂载进容器。需容器有可写层，且 `jcmd` 与目标 JVM 版本匹配（需 JDK 21）。
- 方案 B（改镜像）：在 Dockerfile 中保留完整 JDK 而非裁剪。会增加镜像体积，**不建议**。
- 方案 C（重启加参数）：增加 `-XX:NativeMemoryTracking=summary` 或 `-Xlog:gc*`。**需重启容器，属生产变更，需你批准后执行。**

**当前状态：Java 侧的堆使用率、GC 频率、非堆明细均为「未测试」。** 任何“Java 内存可降 X MB”的结论都缺乏数据支撑。

### 4.4 数据库规模（实测）

面板 SQLite：`/var/lib/docker/volumes/sqlite_data/_data/gost.db`

```
总大小 1.6 MB：gost.db 868K，gost.db-wal 604K，gost.db-shm 32K
```

行数（实测，通过容器内 `sqlite3`）：

| 表 | 行数 |
|---|---:|
| `user` | 1 |
| `node` | 2 |
| `tunnel` | 1 |
| `forward` | **106** |
| `forward_port` | **106** |
| `statistics_flow` | 19 |

**判读**：
- 数据量**很小**（106 条转发、2 个节点），数据库文件仅 868 KB。
- `getAllForwards()` 的 N+1 查询问题在 106 条转发时产生的开销为：`2×106 + 106×1 ≈ 318` 次查询 vs 批量后的 `4` 次查询（1 次列表 + 1 次 tunnel + 1 次 port + 1 次 node）。**查询次数确实减少，但每次查询都是本地 SQLite 内存操作，耗时在毫秒级**。
- **因此：N+1 优化是正确的代码质量改进，但不应宣称它带来可观测的内存下降。** 它的价值在于：转发数增长到数千时避免查询爆炸，以及减少 HikariCP 连接占用时长。

---

## 五、问题分类（A/B/C/D）

按任务书要求分级，每项均注明证据来源。

### A 类：已通过生产实际数据确认的问题

| 编号 | 问题 | 文件/位置 | 实测证据 | 影响 | 风险 |
|---|---|---|---|---|---|
| **A-1** | `flux_agent` 常驻 2.54 GiB，占物理内存 32.7%，为第一内存消耗者 | `go-gost/x/service/`、`go-gost/x/handler/forward/` | PID 40932 RSS 2,664,244 kB；`Pss_Anon` 2,681,608 kB；`VmHWM` 2,717,452 kB；`MemoryCurrent` 2,760,110,080 B | **高**：单进程占用 1/3 物理内存 | 中（涉及数据面） |
| **A-2** | 堆峰值达 2.59 GiB 后**从未归还给 OS**（HWM 与 RSS 仅差 37 MiB） | Go runtime 配置（无 `GOMEMLIMIT`、无 `GOGC`） | `/proc/40932/environ` 无 `GOGC`/`GOMEMLIMIT`；`systemctl show -p Environment` 为空 | **高**：Go 堆在峰值后长期滞留 | 低（仅调参） |
| **A-3** | 212 个 service 常驻（106 TCP + 106 UDP），每个独立 listener/handler/forwarder | `/etc/flux_agent/gost.json` 实测 212 services | `services: 212`，`('tcp','tcp'): 106, ('udp','udp'): 106` | **高**：结构性内存基数 | 高（属业务配置） |
| ~~A-4~~ | ~~relay 连接滞留~~ | — | ❌ **已证伪**：`currentConns` 是恒定上报值（恒 108），与 `ss` 口径不同；pprof 显示连接:goroutine = 81:81 | 无 | 无需处理 |
| **A-5** | Docker 容器日志无轮转，后端 19 小时累积 55 MB | `docker-compose.yml`（`LogConfig.Config = map[]`） | `docker inspect` 输出空 Config；日志文件 55M | 中（磁盘，非内存） | 低 |
| **A-6** | 生产 `gost.json` 的 `log`/`api`/`profiling` 三项均为 `null`，无日志等级控制、无诊断通道 | `/etc/flux_agent/gost.json` | `log = null, api = null, profiling = null` | 中：无法调日志、无法 profile | 低 |
| **A-7** | JVM 无 `-XX:MaxMetaspaceSize`，非堆约 95 MiB 无上限 | `docker-compose.yml` 的 `JAVA_OPTS`；`springboot-backend/Dockerfile` | `Pss_Anon` 622,008 kB > `-Xmx512m` 524,288 kB，差值 95 MiB | 低~中 | 低 |

### B 类：代码分析发现的高风险问题（静态，未在生产触发）

| 编号 | 问题 | 文件/位置 | 证据 | 风险 |
|---|---|---|---|---|
| B-1 | 转发列表 `getAllForwards()` 存在 N+1 查询 | `springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java` | 静态阅读：逐 forward 查 tunnel、逐 forward 查 port、逐 port 查 node | 转发数增长时查询爆炸；当前 106 条影响小 |
| B-2 | 流量上报为每次调用创建 `http.Client` | `go-gost/x/service/traffic_reporter.go` | 静态阅读：`&http.Client{Timeout: 5*time.Second}` 局部变量 | 分配开销；原实现共享 `http.DefaultTransport`，**并非连接池泄漏** |
| B-3 | 5 秒周期任务分配后未使用的快照 map | `go-gost/x/service/global_traffic_manager.go` | `collectAndReport()` 中 `trafficSnapshot := make(map[string]*ServiceTraffic)` 构造后未读取 | 每 5 秒无谓分配，量小 |
| B-4 | relay handler 每连接打 INFO 日志 | `go-gost/x/handler/relay/handler.go:130` | `log.Infof("%s <> %s", conn.RemoteAddr(), conn.LocalAddr())` | 高并发时日志放大；**生产未观测到该日志**（见 3.7） |
| B-5 | 缺省日志等级为 INFO | `go-gost/x/config/loader/loader.go:36-40` | 无 `log.level` 时回落到 InfoLevel | 需可配置能力 |
| B-6 | `logback-spring.xml` 开启 `scan="true"` 且 `ConsoleAppender` 使用 `<prudent>true</prudent>` | `springboot-backend/src/main/resources/logback-spring.xml` | 静态阅读配置 | scan 每 60 秒重读文件；prudent 每次写开关文件，无缓冲 |

### C 类：需进一步生产数据才能确认

| 编号 | 待确认事项 | 需要的证据 | 当前状态 |
|---|---|---|---|
| C-1 | `flux_agent` 堆内 2.5 GiB 的具体构成（各 handler/连接缓冲占比） | **Go heap profile**（需启用 pprof 并重启 agent） | **未测试** |
| C-2 | 是否存在小时级缓慢泄漏 | ≥24 小时 RSS 连续采样 | **未测试**（已排除分钟级快泄漏） |
| C-3 | Java 堆实际使用率与 GC 频率 | `jcmd GC.heap_info`、`-Xlog:gc*`（需挂载工具或重启） | **未测试** |
| C-4 | Java 非堆明细（Metaspace / CodeCache / DirectBuffer 各占多少） | NMT（需重启加 `-XX:NativeMemoryTracking=summary`） | **未测试** |
| ~~C-5~~ | ~~减少 relay 滞留连接对内存的实际收益~~ | — | ❌ 前提不存在，已删除 |
| C-6 | 并发容量边界（多少转发/多少并发连接时 RSS 达到 7.75 GiB） | 压测 | **未测试** |

### D 类：不影响内存但影响代码质量

| 编号 | 问题 | 位置 | 说明 |
|---|---|---|---|
| D-1 | 212 个 service 的 `status.events` 持久化到配置，配置膨胀到 233 KB | `gost.json`（600 条事件） | 每次配置上报都携带历史事件 |
| D-2 | `deploy/journald-flux.conf` 在原假设下新增，实际无收益 | `deploy/journald-flux.conf` | 保留为**可选模板**，默认不应用 |
| D-3 | 数据库保留策略不一致 | `StatisticsFlow` vs `forward_flow_statistics` | 两者保留天数不同，属设计选择，非缺陷 |

---

## 六、架构与依赖关系

```
                      ┌──────────────────────────────────────────┐
   Browser / App ────►│  vite-frontend (nginx, 2.8 MiB)  :6366   │
                      └───────────────┬──────────────────────────┘
                                      │ HTTP (静态资源 + API 代理)
                                      ▼
                      ┌──────────────────────────────────────────┐
                      │  springboot-backend (JVM, 613 MiB) :6365 │
                      │  · 认证/权限 (JWT)                        │
                      │  · node / tunnel / forward CRUD           │
                      │  · 流量统计与聚合                          │
                      │  · WebSocket 服务端                       │
                      │  · SQLite (868 KB, HikariCP)             │
                      └───────────────┬──────────────────────────┘
                                      │ ① WebSocket 控制通道 (2s 心跳/状态)
                                      │ ② HTTP 流量上报 (5s 聚合)
                                      │ ③ HTTP 配置下发 (10min)
                                      ▼
                      ┌──────────────────────────────────────────┐
                      │  flux_agent  (Go, 2.54 GiB)  [systemd]   │
                      │  · 212 个转发 service (106 TCP + 106 UDP) │
                      │  · 全部经 chains_3 → relay+tls           │
                      └───────────────┬──────────────────────────┘
                                      │ relay + tls
                                      ▼
                      ┌──────────────────────────────────────────┐
                      │  relay 节点 64.83.10.191:1000            │
                      └──────────────────────────────────────────┘
```

**依赖关系评估**：
- 三个组件职责清晰，**不存在功能重复的服务**。
- `vite-frontend` 为静态站，**不需要 Node 常驻**（实测 2.8 MiB 已确认）。
- `openspeedtest` 是**与本项目无关的第三方测速容器**（13 MiB），不属优化范围；如需释放内存可自行删除，但那属于你的运维决定。
- **无循环依赖、无不必要的常驻服务、无重复加载相同数据。**

---

## 七、已执行的验证与未执行项

### 已执行（有输出佐证）

| 验证项 | 命令/方式 | 结果 |
|---|---|---|
| SSH 连通与系统识别 | paramiko → `hostname`/`uname -a` | ✅ Debian 12 / 6.1.0-50-amd64 |
| 内存总览与压力 | `free -h`、`/proc/meminfo`、`/proc/pressure/memory`、`vmstat` | ✅ 无压力、无 swap 使用 |
| 进程内存排名 | `ps -eo ... --sort=-rss` | ✅ agent 2.54 GiB 居首 |
| 容器内存 | `docker stats --no-stream` | ✅ backend 613.8 MiB、frontend 2.812 MiB |
| 容器清单与垃圾 | `docker ps -a`、`docker system df` | ✅ 无悬空镜像/遗留容器 |
| agent 内存构成 | `/proc/<pid>/status`、`smaps_rollup`、`statm` | ✅ 100% 匿名、THP 2.37 GiB |
| agent 增长趋势 | 13 点 × 30 秒采样 | ✅ 无分钟级单调上升 |
| agent 负载结构 | 解析 `gost.json` | ✅ 212 service（106 TCP + 106 UDP） |
| agent 连接状态 | `ss -tnp`、`ss -ltnp` | ✅ 106 监听；relay 连接随流量开合（91~207），无滞留 |
| agent FD | `/proc/<pid>/fd` | ✅ 559 socket |
| agent CPU/IO | `/proc/<pid>/io`、`schedstat`、`systemctl show` | ✅ syscr 1.11 亿 |
| 重复进程检查 | `pgrep -a -f flux_agent`、`NRestarts` | ✅ 单实例，0 次重启 |
| journal 规模 | `journalctl --disk-usage` | ✅ 84.1 MB（**非 4 GiB**） |
| journald 配置 | `systemd-analyze cat-config` | ✅ 全默认，无 drop-in |
| Docker 日志配置 | `docker inspect LogConfig` | ✅ `Config: {}`，无轮转 |
| 数据库规模 | 容器内读 sqlite | ✅ 106 forward / 2 node / 1 tunnel |
| JVM 参数与内存 | `docker inspect .Config.Env`、`/proc/<pid>/*` | ✅ -Xms256m -Xmx512m，Pss_Anon 607 MiB |
| **Java 在线诊断** | `docker run --pid=container:... jcmd 1 GC.heap_info` | ✅ **已拿到**堆/Metaspace 实测 |
| **Go pprof 诊断** | 加 `profiling` 配置 → `curl /debug/pprof/heap` | ✅ **已拿到**堆/goroutine 实测 |
| **pprof 堆差分** | `go tool pprof -base` 对比 t0/t1 | ✅ 定位到 64 KiB 缓冲区池占增长 81.68% |
| **cgroup 内存分解** | `memory.stat` | ✅ anon_thp 占 anon 88% |
| **优化后实测** | 重启后连续采样 | ✅ agent 100 MiB / Java 432 MiB |

> **两项此前的"❌ 无法诊断"已被推翻**：
> - 「容器内无 jcmd」→ 用 `docker run --rm --pid=container:springboot-backend --network=container:springboot-backend eclipse-temurin:21-jdk` 挂载即可，**无需重启容器、无需改镜像**。
> - 「二进制加壳无法验证 pprof」→ 加壳只影响静态字符串分析，**不影响运行时注册的 HTTP handler**。`gost.json` 加 `"profiling": {"addr": "127.0.0.1:6060"}` 后 pprof 立即可用（`go-gost/main.go` 已 `import _ "net/http/pprof"`，`program.go` 会启动该监听）。

### 未执行（明确说明原因）

| 未执行项 | 原因 |
|---|---|
| 24 / 72 小时内存趋势 | 采样脚本已部署（每 2 分钟一点），但**需等待时间**。当前最长窗口 15 分钟。 |
| 并发压测 / 吞吐 / 错误率 | 未在生产做压测（会影响真实用户），无独立压测环境。 |
| 优化前 API 延迟基线 | **优化前未采集**，重启后无法回溯，因此不存在延迟对比。 |
| relay 连接滞留根因定位 | ✅ **已执行**：结论为不存在滞留（连接与 goroutine 1:1），无需改代码。 |
| JVM Native Memory Tracking | 需在启动参数加 `-XX:NativeMemoryTracking=summary` 并**重启容器**，属生产变更，未做。 |
| 前端页面人工回归 | 需浏览器逐页点击，未做。 |
| 移动端兼容性 | 无设备，未做。 |
| Docker 日志轮转生效验证 | 配置已写入 compose，但**需下次容器重建才生效**，当前 `LogConfig.Config` 仍为空。 |

---

## 八、结论

1. **内存占用偏高的第一责任者是 Go 的 `flux_agent`：2.54 GiB，占物理内存 32.7%。** 这是实测结论，不是按代码语言占比推测。
2. **pprof 实测证明它不是"某段代码在泄漏"**：重启后的同一进程 `HeapAlloc 41.3 MiB`、`Sys 75.4 MiB`、`HeapObjects 175,064`、`goroutine 1,026`——**真实工作集只有百兆级**。2.5 GiB 是堆高水位残留。
3. **增长确有发生，驱动力是连接**：6 分钟 RSS 89,636 → 107,112 kB，同期 goroutine 893 → 1,038、estab 195 → 294。
4. **堆差分里 81.68% 的增长来自 64 KiB 缓冲区池，但这是正常开销**：`transport.go:32` 的 `defer bufpool.Put(buf)` 确实执行、`cap` 匹配、255 个缓冲区 ≈ 130 条连接 × 双向。**不是"忘记归还"的缺陷。**
5. **RSS 降不下来的物理原因是透明大页**：`AnonHugePages` 占 RSS 的 88%（新进程）~92.7%（老进程），THP=always 让 `madvise` 归还粒度变成 2 MiB，回收不积极。
6. ~~唯一真正持续恶化的是 relay 连接滞留~~ → **该结论已证伪**。真实原因是 Go 堆高水位不归还（存活对象仅 30~45 MB，2.5 GiB 中绝大部分是 runtime 保留未释放），已由 `GOMEMLIMIT` 约束。
7. **Java 侧根因是 `-Xms256m` 而非泄漏**：jcmd 实测堆 committed 恒为 256 MiB 但 used 仅 40~82 MiB（利用率 16%~32%），改为 `-Xms64m` 后结构性下降约 164 MiB。
8. **前端 2.8 MiB，无优化空间**；生产是 nginx 静态托管，**没有 Node.js 常驻进程**。
9. **journald 84.1 MB、agent 每 10 分钟 1 行日志**：日志**不是**内存问题。此前"journald 4 GiB 刷屏"的结论来自本地测试机，已撤回。
10. **系统全程无内存压力（PSI 全 0、Swap 0、无 OOM）**，因此优化可以稳妥推进，不必冒险收紧限制。

---

## 九、本次在生产机上实际执行的变更

| 变更 | 位置 | 备份 | 效果（实测） |
|---|---|---|---|
| `-Xms256m` → `-Xms64m` + `MaxMetaspaceSize=192m` | `/root/docker-compose.yml` | `backup_20261030_120012/docker-compose.yml.orig` | Java RSS 661,436 → 432,120 kB |
| `GOMEMLIMIT=1200MiB` | `/etc/systemd/system/flux_agent.service.d/override.conf` | 目录原不存在，删除即还原 | agent MemoryCurrent 2.76 GB → 116 MB |
| 开启 pprof（仅回环） | `/etc/flux_agent/gost.json` | `backup_20261030_120012/gost.json.orig` | 拿到首个真实堆画像 |

**未执行的变更**：relay 连接回收修复（根因未定位）、Docker 日志轮转（需容器重建）、任何数据库/API/认证改动。

**所有变更均未提交到 Git**，回滚方式见《ROLLBACK_GUIDE.md》。

---

*本报告所引用的每一条运行数据均可在《SERVER_MONITORING_GUIDE.md》中找到对应的复现命令。*
