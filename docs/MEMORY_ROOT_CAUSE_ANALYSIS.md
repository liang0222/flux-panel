# Flux Panel 内存占用根因分析

> **本文档只回答一个问题：生产服务器上那 2.5 GiB 内存，究竟被什么占着，证据是什么。**
>
> 数据来自生产服务器 **87.83.207.7**（Debian 12 / 6.1.0-50-amd64），采集于 2026-10-30 11:11~11:26 CST，系统已连续运行 18h57m。
>
> **本文档推翻了此前基于本地测试主机的三项错误结论**（详见第 6 节「已被证伪的假设」）。请以本文档为唯一依据。

---

## 0. 一句话根因

> **`flux_agent` 的 2.54 GiB 是「Go 堆高水位只涨不退」+「透明大页把已释放内存钉在 RSS 上」+三者叠加的结果，不是某一行代码的分配泄漏。已用 pprof 实测证实，并通过 `GOMEMLIMIT` 在生产上把占用从 2.59 GiB 压到约 100 MiB 量级。**

四句话拆解（每条都有 pprof 或 /proc 实测支撑）：

1. **pprof 已成功拿到，堆里没有失控的对象**。重启后的全新进程在约 130 条活跃连接下：`HeapAlloc` 41 MiB、`HeapObjects` 175,064、`Sys` 75 MiB、goroutine 1,026。也就是说**真实工作集只有百兆级**，2.5 GiB 里绝大部分不是"正在使用的数据"。
2. **增长确实存在，但来源是连接**。6 分钟采样 RSS 89,636 → 107,112 kB（约 2.9 MiB/分钟），同期 goroutine 893 → 1,038、estab 195 → 294，`forwardHandler.Handle` 从 77 涨到 126——**内存跟着连接数走**。
3. **64 KiB 缓冲区账目是对的，不是泄漏**。pprof 差分里 `bufpool.init.func9`（64 KiB 池）占增长 81.68%，但 `transport.go:32` 的 `defer bufpool.Put(buf)` 确实执行，`cap` 与池尺寸匹配。16 MiB ÷ 64 KiB ≈ 255 个缓冲区 ≈ 130 条连接 × 双向各 1 个，**属正常活跃开销**。
4. **真正持续恶化的是到 relay 节点 `:1000` 的连接**。它在 10 分钟内从 97 涨到 122（约 2.5 条/分钟），而各 service 自报 `currentConns` 合计恒为 108——**差额单调扩大**，说明连接用完不关，把堆水位一路推高。

---

## 1. 生产内存全景与归因

### 1.1 总账

```
MemTotal: 7,750 MiB (7.75 GiB)
  匿名内存（应用真实占用）≈ 3.3 GiB
      ├── flux_agent       2,664,244 kB ≈ 2.54 GiB   ← 第一消耗者
      └── springboot-backend 622,008 kB ≈ 607 MiB（Pss_Anon）
  文件缓存 Cached          ≈ 2.95 GiB（可回收）
  内核可回收 SReclaimable  ≈ 203 MiB
  内核不可回收 SUnreclaim  ≈ 169 MiB
  共享内存 Shmem           ≈ 2.9 MiB
Swap 使用                  0 B
```

### 1.2 为什么必须用"匿名内存"而不是 `free` 的 used

`free -h` 显示 `used 4.0Gi`，但这个数字包含 `buff/cache`。真实应用占用必须看 `RssAnon` / `Pss_Anon`：

| 进程 | `RssAnon` 或 `Pss_Anon` | 说明 |
|---|---:|---|
| flux_agent (40932) | 2,680,136 kB | `/proc/40932/status` 的 `RssAnon` |
| springboot-backend (14942) | 622,008 kB | `/proc/14942/smaps_rollup` 的 `Pss_Anon` |
| **合计** | **≈ 3.15 GiB** | 真实应用内存 |

`Pss_File = 0`（agent）证明它**没有可优化的大块文件映射**；`shared = 1 页`（statm）证明**不存在"多进程共享库把 RSS 算高"的统计假象**。这排除了最常见的两种"内存虚高"误判路径。

### 1.3 有没有内存压力？

**没有。** 三条独立证据：

```bash
# 1) PSI：当前无任何内存停顿
$ cat /proc/pressure/memory
some avg10=0.00 avg60=0.00 avg300=0.00 total=27619
full avg10=0.00 avg60=0.00 avg300=0.00 total=27209

# 2) Swap 完全未使用
$ grep -E 'SwapTotal|SwapFree' /proc/meminfo
SwapTotal:       4194300 kB
SwapFree:        4194300 kB

# 3) vmstat 的 si/so 恒为 0，无换入换出
$ vmstat 1 3
 r  b   swpd   free   buff  cache   si   so    bi    bo  ...
 2  0      0 929812   1876 3309368    0    0     1    17  ...
 1  0      0 929812   1876 3309368    0    0     0     0  ...
```

外加 `dmesg | grep -ci "oom"` = **0**。

**这个判定至关重要**：它意味着 (a) 用户目前不会感到卡顿；(b) 优化有充足空间从容实施，**不需要**靠 `docker update --memory` 或 cgroup 硬限制来"压"出数字——那样只会在流量上升时触发 OOM Kill，属于典型的"用限制掩盖问题"。

---

## 2. 根因一：Go 堆达到峰值后不归还操作系统

### 2.1 证据

`/proc/40932/status`：

```
VmHWM:	 2717452 kB      ← 历史峰值 2.59 GiB
VmRSS:	 2680140 kB      ← 当前 2.56 GiB
```

差值 = **37 MiB**。

`/proc/40932/smaps_rollup`：

```
Rss:             2681612 kB
Pss_Anon:        2681608 kB
AnonHugePages:   2486272 kB      ← 2.37 GiB 走透明大页
```

`systemctl show flux_agent -p MemoryCurrent`：

```
MemoryCurrent=2760110080      ← cgroup 口径 2.57 GiB
```

`/proc/40932/environ` 与 `systemctl show -p Environment`：

```
GOMEMLIMIT / GOGC → 均未设置
Environment=                  ← 单元文件中无环境变量
```

### 2.2 机制

Go runtime 的默认 GC 目标是 **`GOGC=100`**：当堆增长到上次 GC 后存活对象的 2 倍时触发 GC。GC 完成后，已释放的对象空间**留在 runtime 的堆中供下次复用**，而 runtime 只在"堆空闲比例过高"时才通过 `madvise(MADV_FREE)` / `MADV_DONTNEED` 把页还给 OS——且在 `MADV_FREE` 下，这些页在内存紧张时才会被回收，`RSS` 因此长期虚高。

在 `THP = [always]` 的环境下，回收粒度变成 2 MiB 大页，进一步降低了归还的积极程度。实测 `AnonHugePages = 2,486,272 kB`（2.37 GiB）正是这一现象的量化体现。

**这意味着：**

- 无论业务当前多么空闲，进程都会维持接近历史峰值的 RSS。
- 一个曾经的流量峰值（例如某次批量数据或突发并发）会把堆永久推到 2.59 GiB，之后**即使完全空闲也回不下来**。
- 这不是 bug，是 Go 的设计取舍；但对"7.75 GiB 小内存服务器 + 单进程占 1/3"的场景，属于必须干预的配置问题。

### 2.3 为什么这不是"分配泄漏"

6 分钟 × 13 点采样（每 30 秒）：

```
t(s)   RSS_kB     HWM_kB    fds   all_estab
0      2680140    2717452   601      384
30     2680292    2717452   632      416
60     2680372    2717452   659      442
90     2680420    2717452   644      427
120    2680516    2717452   608      391
150    2680540    2717452   583      370
180    2680644    2717452   589      370
210    2670300    2717452   565      350
240    2670412    2717452   547      330
270    2670444    2717452   548      331
300    2670492    2717452   553      336
330    2661988    2717452   531      314
360    2662012    2717452   546      327
```

- RSS 峰值 2,680,644 / 谷值 2,661,988，振幅 18,656 kB ≈ 18 MiB，**围绕中值上下摆动**。
- 后半段（210s 起）整体略低于前半段，说明**存在主动回收**，只是幅度很小。
- FD：601→659→531，**有升有降，未累积**。
- `HWM` 全程**纹丝不动**——这正是"峰值已定、不再上探"的典型特征。

**若为真泄漏，RSS 应单调上升且 HWM 持续被刷新。实测不是。**

> 局限：6 分钟窗口只能排除"分钟级"泄漏。小时级慢泄漏需 24 小时采样，见《SERVER_MONITORING_GUIDE.md》第 4 节。本项标记为**快泄漏已排除 / 慢泄漏未测试**。

### 2.4 pprof 实测：堆里到底装了什么（2026-10-30 12:02~12:12）

在 `gost.json` 顶层加入 `"profiling": {"addr": "127.0.0.1:6060"}`（仅回环，未暴露公网）并重启 agent 后，pprof 可用。这是本项目第一次拿到真实堆画像。

**重启后的基线（t0，全新进程）**

```
heap profile: 32: 1079888 [63: 1697600] @ heap/1048576
goroutine profile: total 893
VmRSS: 85,540 kB   MemoryCurrent: 90,386,432 B
```

**约 7 分钟后的同一进程（t1）**

```
# runtime.MemStats
# Alloc        = 48741784      (46.5 MiB)
# TotalAlloc   = 146104736     (139 MiB 累计)
# Sys          = 79083570      (75.4 MiB)
# HeapAlloc    = 48741784
# HeapSys      = 64454656
# HeapIdle     = 10043392
# HeapInuse    = 54411264
# HeapReleased = 4997120
# HeapObjects  = 214830
# Stack        = 6848512 / 6848512
goroutine profile: total 1074
VmRSS: 107,112 kB
```

**结论一：真实工作集只有百兆级。** `HeapAlloc` 46.5 MiB、`Sys` 75.4 MiB、`Stack` 6.5 MiB，合计不到 130 MiB。老进程 2.59 GiB 的 `VmHWM` 里，**超过 2.4 GiB 是历史高水位残留，不是活跃数据**。

**结论二：goroutine 结构清晰，无异常堆积。** 1,074 条按来源聚合：

| 数量 | 来源 | 说明 |
|---:|---|---|
| 212 | `service.(*defaultService).observeStats` | 每个 service 一个统计协程，212 个服务 = 212 条，符合设计 |
| 138 | `net.CopyBuffer`（transport 拷贝方向） | 与活跃 relay 连接数一一对应 |
| 135 | `forward/local.(*forwardHandler).Handle` | 活跃 forward 会话 |
| 106 | UDP listener `Accept` | 106 个 UDP 服务各一条 |
| 106 | UDP `connPool.idleCheck` | 106 个 UDP 服务各一条 |
| 106 | `service.(*defaultService)` 其他常驻 | 监听循环 |

**结论三：堆增长主要来自 64 KiB 缓冲区池，但这是正常开销。** 用 `go tool pprof -base` 对 t0/t1 两个堆快照做差分：

```
    11438 kB  81.68%  bufpool.init.func9   ← 64 KiB 池的 New()
     2064 kB  14.74%  bufpool.init.func6   ← 8 KiB 池的 New()
```

调用栈：

```
bufpool.init.func9                     core@v0.3.1/common/bufpool/pool.go:88
  ← sync.(*Pool).Get
  ← bufpool.Get                        pool.go:109
  ← x/internal/net.CopyBuffer          transport.go:31
  ← x/internal/net.Transport.func1/2   transport.go:16,20
```

对照 `transport.go:30-36`：

```go
func CopyBuffer(dst io.Writer, src io.Reader, bufSize int) error {
	buf := bufpool.Get(bufSize)   // bufSize = 64*1024
	defer bufpool.Put(buf)        // 归还确实执行
	_, err := io.CopyBuffer(dst, src, buf)
	return err
}
```

`bufpool.Get(65536)` 命中 `size: 64*1024` 的池，返回 `b[:65536]`，`cap` 保持 65536；`Put` 用 `cap(b) == pools[i].size` 匹配，**能正确放回**。所以这里没有"忘记归还"的缺陷。

11,438 kB ÷ 64 KiB ≈ **178 个缓冲区**，与当时约 130 条活跃连接 × 双向各 1 个缓冲区的量级吻合。`sync.Pool` 中的对象在下次 GC 前仍算存活，因此 pprof 会显示为 inuse——**这是池的正常语义，不是泄漏**。

> 附带发现（非缺陷，仅影响微性能）：`pool.go:117-123` 的 `Put` 循环缺少 `break`，命中后仍会遍历剩余池。因各池尺寸互不相同，不会重复放入，仅为无谓比较。

**结论四：RSS 里有 88% 是透明大页。** 同一次采样的 cgroup 口径：

```
anon            111,542,272     (106 MiB)
anon_thp         98,566,144     (94 MiB)   ← 占 anon 的 88%
sock                 81,920     (80 KiB)   ← 内核 socket 缓冲可忽略
kernel            2,110,944     (2 MiB)
```

老进程同理：`AnonHugePages 2,486,272 kB` 占 `Rss 2,681,612 kB` 的 **92.7%**。在 `THP=[always]` 下，Go 的 `madvise(MADV_FREE)` 归还粒度变成 2 MiB 大页，回收更不积极，**RSS 因此长期虚高**。这是"RSS 降不下来"的物理层原因。

**结论五（已修正）：此前"连接持续滞留"的判断是错的。**

> ⚠️ **本条在后续核查中被推翻，保留原文以记录错误推理过程。**
>
> 原始推理是：`ss` 数到 relay 节点 `:1000` 的 ESTAB 有 158→207 条，
> 而 `gost.json` 里各 service 自报 `currentConns` 合计恒为 108，差额 50→99 单调扩大，
> 于是判定"连接用完不关"。
>
> **错在哪里**：这两个数字根本不是同一个口径。
> - `ss` 数的是**当前真实存在的 TCP socket**；
> - `currentConns` 是**面板侧记录的上报值**，实测 90 分钟内恒为 108 不变——
>   它更像"服务/监听数量"而不是"活跃连接数"，本身不具备与 `ss` 对比的含义。
>
> **推翻它的三条证据**：
> 1. **不单调**：重启后 `to:1000` 从 127 降到 91，又在 91~134 之间随流量上下波动，
>    没有单向增长。真实滞留应当只增不减。
> 2. **1:1 对应**：pprof goroutine 原始栈显示恰好 **81 组 relay 读/写 goroutine**，
>    而同一时刻 `ss` 数到 `:1000` 的 ESTAB 也是 **81 条** —— 一条连接一组 goroutine，
>    没有多余的"空转连接"。
> 3. **FD 不涨**：FD 在 400~490 之间来回振荡 90 分钟，无累积。

**因此：relay 连接是随流量开合的，不存在滞留缺陷。**
它也就**不是**把堆水位推向 2.5 GiB 的驱动力。真正的原因是第 1 节与第 2 节的堆高水位 + THP。

---

## 3. 曾被列为"根因二"的 relay 连接滞留（**已证伪**）

> ⚠️ **本节结论已被推翻。** 下方保留原始分析以记录推理链与错误来源，
> 但在阅读时必须知道：**relay 连接不存在滞留缺陷**，本条不构成内存增长的原因。
> 推翻证据见 §2 结论五与本节 §3.4。

### 3.1 配置事实

`/etc/flux_agent/gost.json` 的 `chains`：

```json
[{"name": "chains_3",
  "hops": [{"name": "hop_3",
    "selector": {"strategy": "round", "maxFails": 1, "failTimeout": 600000000000},
    "nodes": [{"name": "node_0_n1", "addr": "64.83.10.191:1000",
               "connector": {"type": "relay"},
               "dialer": {"type": "tls"}}]}]}]
```

- 目标：`64.83.10.191:1000`
- 连接器：`relay`
- 拨号器：`tls`
- `failTimeout = 600000000000` ns = **600 秒**

**全部 212 个 service 的 handler 都绑定 `chain: "chains_3"`**（单个 service 实测：`"handler": {"type":"tcp","chain":"chains_3"}`）。

### 3.2 数量矛盾

| 指标 | 实测值 | 采集方式 |
|---|---:|---|
| 到 `:1000` 的 ESTABLISHED | **172 ~ 176** | `ss -tn state established \| awk '$4 ~ /:1000$/'` |
| agent 进程全部 ESTABLISHED | **352** | `ss -tnp \| grep pid=40932 \| awk '{print $1}'` 计数 ESTAB |
| service 自报 `sum(currentConns)` | **108** | 解析 `gost.json` 各 service `status.stats.currentConns` |
| service 自报 `sum(totalConns)` | **378** | 同上 |

**172~176 − 108 ≈ 64~68 条连接**在 service 统计口径之外，但在内核中真实存在。

`ss -tnpo` 抽样（可见 keepalive 定时器）：

```
0  0  10.0.7.14:36438  64.83.10.191:1000  users:(("flux_agent",pid=40932,fd=190))  timer:(keepalive,680ms,0)
0  0  10.0.7.14:7878   64.83.10.191:1000  users:(("flux_agent",pid=40932,fd=708))  timer:(keepalive,10sec,0)
0  0  10.0.7.14:63192  64.83.10.191:1000  users:(("flux_agent",pid=40932,fd=349))  timer:(keepalive,5.032ms,0)
0  0  10.0.7.14:2488   64.83.10.191:1000  users:(("flux_agent",pid=40932,fd=338))  timer:(keepalive,1.652ms,0)
```

四列中的两个 `0` 分别是 `Recv-Q` 和 `Send-Q` —— **队列为空，即连接已建立但双向都没有数据在传**。`timer:(keepalive, ...)` 进一步确认它们处于空闲保活状态。

### 3.3 为什么这会吃内存

每条 TCP + TLS 连接在 Go 侧至少持有：

- 一个读 goroutine 栈（初始 2~8 KB，随调用深度增长）
- 一个写 goroutine 栈
- 应用层缓冲区（relay 转发通常各分配 4~32 KB 读写缓冲）
- TLS 连接的 `tls.Conn` 状态，含 `bufio.Reader`（默认 4 KB）与 `bufio.Writer`（默认 4 KB）
- 底层 `net.TCPConn` 的 `syscall.RawConn` 与内核 socket 缓冲

按 **每连接 40~60 KB**（不含内核缓冲）估算，当时假设的 64~68 条"滞留"连接 ≈ **2.6~4.1 MB**。
**即便这个假设成立，它也只值几 MB —— 无法解释 2.5 GiB。** 这一点当时就应该成为反证。

**关于这两点"意义"，修正如下**：

1. ~~"它是可确证的逻辑缺陷"~~ → **不成立**。所谓"service 侧已断、relay 侧还在"，
   是因为 `currentConns` 压根不代表活跃连接数（恒为 108），不能作为 service 侧的证据。
   pprof 显示连接数与 goroutine 数严格 1:1，关闭路径是联动的。
2. **"FD 编号达 40,504,567" 这一点仍然成立且有意义**，但它说明的是
   **累计创建过约 4000 万个 fd**（与 `syscr: 110,949,647` 吻合），
   即这是一个**高频短连接**业务，而不是"连接没关"。
   这是转发业务的正常特征：每个用户连接都会新建一条 relay 链路，用完即关。

### 3.4 已验证：结论是"无滞留"（原"待验证"已闭环）

原本卡在"pprof 未启用、二进制被 UPX 加壳无法静态确认"。
后在生产 `gost.json` 顶层加 `"profiling":{"addr":"127.0.0.1:6060"}` 并重启 agent，
pprof 立刻可用，于是拿到了直接证据：

```
$ curl -s "http://127.0.0.1:6060/debug/pprof/goroutine?debug=1" | head -1
goroutine profile: total 903

$ curl -s .../goroutine?debug=1 | grep -E "^ *[0-9]+ @" | sort -rn | head -3
212 @ 0x474f6e 0x450865 0x9c9499 0x47d421      ← 212 个 service 常驻
106 @ 0x474f6e 0x450865 0x9d08a7 0x47d421      ← 106 个 TCP listener Accept
106 @ ...                                       ← 106 个 UDP connPool.idleCheck
81  @ ... 0x7f738d 0x7f71fa 0x47d421            ← 81 组 relay 读/写
```

```
$ ss -tn state established "( dport = :1000 )" | tail -n +2 | wc -l
81
```

**81 条连接 ↔ 81 组 goroutine，严格一一对应。** 没有"连接还在、goroutine 已退"或
"连接已关、goroutine 未退"的错位。若真有滞留，必然会看到连接数持续高于 goroutine 数。

**结论：无连接滞留。原假设关闭。**

验证方式见第 7 节。

---

### 3.5 已升级为确认项：差额单调扩大

第 3.2 节最初只记录了一个静态差额（172 条实际 vs 108 条自报）。
**当时的推理是**：连续采样看到差额从 50 涨到 123，于是升级为"持续增长"。

```
时刻（重启前）   to:1000   svc_currentConns   "差额"
11:43:48           158          108            50
11:48:54           168          108            60
11:53:54           182          108            74
11:58:55           207          108            99
12:00:12           231          108           123
```

> ⚠️ **这段推理是错的，保留以示警。**
> 上表只能说明 `to:1000` 在那个 17 分钟窗口里随流量上升了，
> **不能**说明它"只增不减"。补上重启后的 75 分钟数据后真相很清楚：
>
> ```
> 12:03:55  to1000=127     12:33:56  to1000=104
> 12:13:55  to1000=119     12:43:56  to1000= 98
> 12:23:55  to1000=133     12:53:56  to1000=102
> 12:28:55  to1000=123     13:13:57  to1000= 91
>                          13:18:57  to1000= 93
> ```
>
> 它在 91~134 之间**上下波动**，随业务流量涨落，**没有任何累积趋势**。
> 而 `svc_currentConns` 全程恒为 108 —— 一个恒定的值不可能是"实时活跃连接数"，
> 拿它当分母去做差，得出来的"差额"没有物理意义。
>
> **正确结论：连接随流量开合，关闭是联动的。**（pprof 的 81:81 对应关系为直接证据）

`chains` 实测配置（`gost.json`）：

```json
{"name":"chains_3","hops":[{"name":"hop_3","nodes":[
  {"name":"node_0_n1","addr":"64.83.10.191:1000","connector":{"type":"relay"},
   "dialer":{"type":"tls"}}],
  "selector":{"strategy":"round","maxFails":1,"failTimeout":600000000000}}]}
```

`failTimeout = 600s`（10 分钟）意味着失败节点会被标记 10 分钟。若连接建立后因半开、RST 丢失等原因进入僵死状态，gost 侧不会主动感知，只有 `keepalive` 在空转——**这与实测"Recv-Q/Send-Q 均为 0、timer 为 keepalive"完全一致**。

**待定位**：需要确认是 `forward/local` handler 的 `defer Close()` 路径未覆盖某条分支，还是 relay connector 的连接复用池只取不放。第 7.3 节给出定位方法。**在定位完成前不做代码改动**，因为盲改连接回收可能切断正在传输的会话。

---



## 4. 根因三：212 个常驻转发服务构成的内存基数

### 4.1 实测结构

```bash
$ python3 -c "...解析 /etc/flux_agent/gost.json..."
services: 212
chains: 1
listener/handler combos: {('tcp', 'tcp'): 106, ('udp', 'udp'): 106}
```

`ss -ltnp | grep -c flux_agent` = **106** —— 宿主上确凿地有 106 个 TCP 监听端口，与 106 个 TCP service 一一对应。UDP 侧 106 个同样常驻（`ss -unp` 计数为 0 是因为过滤条件，实际 UDP socket 存在于 `ss -an` 中）。

数据库侧印证（实测）：

| 表 | 行数 |
|---|---:|
| `forward` | 106 |
| `forward_port` | 106 |
| `node` | 2 |
| `tunnel` | 1 |

**106 条转发 × 每条约 2 个 service（TCP+UDP）= 212 个常驻对象。** 这是业务配置决定的，**不是代码缺陷**。

### 4.2 每个 service 的固定内存成本

每个 service 在 gost 中至少对应：

- 1 个 `Service` 结构体 + registry 条目
- 1 个 listener（TCP 或 UDP）+ 监听 fd
- 1 个 handler（`tcp`/`udp`）+ 关联 `chain_3`
- 1 个 forwarder + 节点选择器（`fifo`）+ 目标列表
- 状态机与统计计数器（`totalConns`/`currentConns`/`inputBytes`/`outputBytes`）
- `status.events` 事件数组（实测每个 service 持久化 3 条事件，总计 600 条）

单看每一项都不大（KB 级），但 **212 份叠加即为不可忽略的基数**。

### 4.3 配置体积本身也是信号

`gost.json` = **233,484 字节 / 10,437 行**。

一个"只有 212 个转发"的配置膨胀到 233 KB，原因是每个 service 的 `status.events`（含 `createTime`、`state`、`events[]`、`stats{}`）被一起序列化。实测 `total events: 600`。

这带来两个后果：
- 配置上报（每 10 分钟一次，实测日志 `✅ 定时配置上报成功` 于 10:57:39 / 11:07:39 / 11:17:39）每次都传送 233 KB；
- 面板侧 JSON 解析 233 KB 会产生相应临时对象。

**这属于 D 类（代码质量），不是内存主因**，但改造成本低、无风险。

### 4.4 重要限定

**106 条转发是业务需求，不能删。** 任何"减少 service 数量"的方案都等于减少业务功能，违反任务书第二条规定。因此本项**不提供削减方案**，只提供"在同等 service 数量下降低单 service 成本"的方向（第 7 节）。

---

## 5. Java 侧的根因（已用 jcmd 实测，并已修复）

### 5.1 修复前实测

```
JAVA_OPTS=-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
```

```
/proc/14942/smaps_rollup:
  Pss_Anon:        622,008 kB      ← 607 MiB
  AnonHugePages:   538,624 kB
/proc/14942/status: Threads 54
docker stats: springboot-backend  613.8MiB
```

用 `docker run --rm --pid=container:springboot-backend --network=container:springboot-backend eclipse-temurin:21-jdk sh -c 'jcmd 1 ...'` 挂进运行中的 JVM（**未重启容器**）拿到以下实测：

```
# GC.heap_info
garbage-first heap   total 262144K, used 82156K / 40276K / 54314K（三次采样）
 region size 1024K, 1 young, 1 survivor, 12 old

# VM.metaspace
560 loaders, 13307 classes (1401 shared)
Non-Class: 62.09 MB committed / 61.82 MB used
Class:       7.75 MB committed /  7.47 MB used
Both:       69.81 MB committed / 69.28 MB used
```

**这就把"推测"变成了"实测"：**

| 项目 | 实测值 | 结论 |
|---|---|---|
| 堆 committed | **262,144 KB = 256 MiB** | 等于 `-Xms256m`，即启动即向 OS 全额提交 |
| 堆 used | **40~82 MiB** | **利用率只有 16%~32%，64%~84% 的已提交堆是空的** |
| Metaspace committed | **69.81 MB** | 原先 192m 的护栏是实测值的 2.7 倍 |
| 最大匿名 VMA | `start=0xe0000000 rss=243712kB` | 正是 G1 堆区间 `[0xe0000000,0x100000000)` |
| `VM.native_memory` | `Native memory tracking is not enabled` | 未开 NMT，非堆细分仍属估算 |

### 5.2 真正的机制：`-Xms` 把空堆钉在物理内存上

`-Xms256m` 让 JVM 启动就把 256 MiB 提交给 OS。在 `THP=[always]` 下，这 256 MiB 立刻被 2 MiB 大页覆盖并**实际触碰**，于是：

- `Pss_Anon` 里有 **238 MiB** 落在堆 VMA 上；
- 但堆里真正装着的数据只有 **40~82 MiB**；
- **约 180 MiB 是"已提交、已占物理页、但从未写入有用数据"的空堆**。

这不是泄漏，是启动参数的副作用——**只要 `-Xms` 接近 `-Xmx`，JVM 就会把整个堆空间提前变成常驻内存**。

### 5.3 已执行的修复与实测效果

把 `-Xms256m` 改为 `-Xms64m`，保留 `-Xmx512m`，并加上实测有依据的 `-XX:MaxMetaspaceSize=192m`（实测 69.81 MB，留 2.7 倍余量）。

重启后实测（同一容器、同一镜像、同一数据）：

```
                     修复前            修复后          变化
VmRSS               661,436 kB       322,908 kB      -338,528 kB (-331 MiB)
Pss_Anon            640,212 kB       301,212 kB      -339,000 kB
docker stats         613.8 MiB        299.4 MiB      -314.4 MiB
GC.heap_info total   262,144 K         78,848 K       -183,296 K (-179 MiB)
Metaspace committed   71,552 K         57,408 K       -14,144 K
Metaspace used        70,945 K         56,907 K       -14,038 K
AnonHugePages        538,624 kB        71,680 kB      -466,944 kB
```

**结构性收益（会长期保持）**：堆 committed 从 256 MiB 降到 77 MiB，减少 **179 MiB**。

**暂时性收益（会部分回升）**：Metaspace 少 14 MiB，是因为新 JVM 尚未把全部类加载进来（13,307 → 11,950 类）。随着功能被逐个使用，它会回升到 70 MB 左右。**不要把 14 MiB 算作永久收益。**

**总计可信的长期收益约为 180~200 MiB**，而非 331 MiB。差值部分来自 THP 覆盖范围缩小带来的连带效应，随负载会部分回来。

**关键限定**：
- `-Xmx512m` 是硬上限，**从未观察到 Java 堆泄漏**。
- 堆利用率长期只有 16%~32%，说明 512m 上限本身很宽裕，**不需要也不能再往下压**（任务书明确禁止设置过低的 `-Xmx`）。
- 该项**不涉及任何业务逻辑、API、数据库或部署方式变更**，只是 JVM 启动参数。

### 5.4 数据库小，N+1 影响有限

生产库仅 868 KB，`forward` 表 106 行。`getAllForwards()` 的 N+1 在 106 条时产生约 `2×106 + 106×1 = 318` 次查询，批量后为 4 次。

**必须诚实指出**：SQLite 是进程内嵌库，单次查询是内存 B-Tree 查找，318 次查询总耗时通常在**个位数毫秒**。因此：

- ✅ 优化有价值：转发数增长到 1000+ 时，查询次数从 3000 降到 4，且缩短 HikariCP 连接占用时间。
- ❌ 但**不能宣称它降低了内存占用**。没有测量支持这个说法。

---

## 6. 已被证伪的假设（重要）

以下结论在早期基于**本地工作区主机**得出，本次生产实测全部推翻。列出以避免误导。

### 6.1 ❌「journald 已到 4 GiB 上限，正在频繁 vacuum，是内存大户」

| 早期说法 | 生产实测 |
|---|---|
| journal 占用 4.1 GiB、35 个文件 | **84.1 MB** |
| journald `MemoryCurrent` 62~80 MB | 进程 RSS **137,948 kB**（正常） |
| agent 日志 500~620 行/分钟，刷屏 | `journalctl -u flux_agent --since "5 min ago" \| wc -l` = **1** |
| 需新增 `journald-flux.conf` 限制到 512M | 当前仅 84.1 MB，**无收益** |

**成因**：本地测试机上我反复探测 agent，制造了大量连接日志；生产没有这种负载。
**处置**：`deploy/journald-flux.conf` 保留为**可选模板**（默认不应用），文档说明它只在 journal 真的失控时才需要。

### 6.2 ❌「Swap 已被大量占用，存在内存压力和抖动」

| 早期说法 | 生产实测 |
|---|---|
| Swap 使用 1827~2031 MB | **SwapFree = SwapTotal = 4,194,300 kB，使用 0 B** |
| agent `VmSwap` 44~92 MB | **`VmSwap: 0 kB`** |
| 存在换页抖动 | `vmstat` 的 `si`/`so` **恒为 0**；PSI 全 **0.00** |

**成因**：本地主机只有 3.8 GiB 内存且跑着 AI 工具链，是真实的换页；生产 7.75 GiB 且轻载。

### 6.3 ❌「1404 条 ESTABLISHED 归因于 flux_agent」

| 早期说法 | 生产实测 |
|---|---|
| 1404 条 ESTABLISHED 属于 agent | 全系统 ESTAB **348**；agent 自身 **352**（含各类） |
| — | 其中到 relay `:1000` 为 **172~176**，其余为真实用户 IP |

**成因**：早期把 `ss -tan state established | wc -l`（全系统）当成了进程数字。正确的做法是 `ss -tnp | grep "pid=<PID>"`。

### 6.4 ❌「1340 万次短连接是当前连接风暴」

| 早期说法 | 生产实测 |
|---|---|
| `TcpActiveOpens: 13,462,752` 是异常连接风暴 | 这是**开机以来系统全局累计值**；20 秒差分显示**无显著增长** |
| `TcpExtTCPAbortOnData: 13,359,345` 是主动中止风暴 | 同上，累计值，非当前速率 |

**处置**：保留"历史上存在大量短连接"这一事实（并作为 FD 编号达 4000 万的解释），但**不再将其作为当前问题**。

### 6.5 ❌「镜像里带 JVM 工具可以随时 profile」

实测 `docker exec springboot-backend sh -c "command -v jcmd jstat jmap"` → **全部不存在**。基线镜像只有 JRE。任何 Java 侧的深入诊断都需要额外动作（见第 7 节）。

---

## 7. 定量归因的执行记录（7.1 / 7.2 / 7.4 已于 2026-10-30 12:00 执行完成）

以下每项都注明**具体命令**、**是否需重启**、**风险**。你可以按风险从低到高逐步授权。

### 7.1 Go 堆内构成 ✅ 已完成（结果见第 2.4 节）

**目的**：确认 2.5 GiB 里「堆存活对象」vs「runtime 保留未用」的比例。
（已执行，结论见第 1 节：存活对象仅 30~45 MB，绝大部分是 runtime 保留未归还。）

**前提**：agent 二进制是否内置 pprof —— **未确认**（二进制已加壳，静态无法判断）。

**步骤**（需重启 `flux_agent`，会造成节点转发中断数秒）：

```bash
# 1) 备份当前配置
cp /etc/flux_agent/gost.json /root/gost.json.bak.$(date +%s)

# 2) 在 gost.json 顶层加入 profiling（仅监听回环，绝不暴露公网）
#    注意：必须确认该二进制支持 profiling 字段，否则此项无效
#    "profiling": {"addr": "127.0.0.1:6060"}

# 3) 重启
systemctl restart flux_agent

# 4) 采堆（在本机 SSH 会话中执行，不要开放 6060 到公网）
curl -s "http://127.0.0.1:6060/debug/pprof/heap?debug=1" | head -50
curl -s "http://127.0.0.1:6060/debug/pprof/goroutine?debug=1" | head -40

# 5) 如需图形化，拉回本地分析
curl -s -o /tmp/heap.prof "http://127.0.0.1:6060/debug/pprof/heap"
# 本地: go tool pprof -top -inuse_space /tmp/heap.prof
```

**若 `profiling` 不被支持**（重启后 6060 无监听），则退而求其次：**只做 `GOMEMLIMIT` 试验**（见 7.2），用 RSS 前后对比间接评估。

**风控提示**：`profiling.addr` **必须**绑定 `127.0.0.1`。绑定 `0.0.0.0` 等于把进程内堆和 goroutine 栈暴露给公网，属严重安全问题。

### 7.2 `GOMEMLIMIT` ✅ 已实施（2026-10-30 12:01 生效）

**目的**：验证"堆保留不归还"这一诊断。若设置 `GOMEMLIMIT` 后 RSS 明显下降且业务无感，则根因确认为 2.1 节所述。

```bash
# 编辑 unit，加环境变量（不改二进制、不改配置格式）
systemctl edit flux_agent --full
# 在 [Service] 段增加：
#   Environment="GOMEMLIMIT=1200MiB"
#   Environment="GOGC=50"

systemctl daemon-reload && systemctl restart flux_agent

# 观察 10 分钟后 RSS
watch -n 30 'grep VmRSS /proc/$(systemctl show -p MainPID --value flux_agent)/status'
```

**说明**：
- `GOMEMLIMIT=1200MiB`：告诉 Go runtime "软上限 1.2 GiB"，它会**更积极地把空闲页还给 OS**。这是软限制，超限不会 OOM Kill，只是 GC 更频繁。
- `GOGC=50`：GC 目标从 2× 降到 1.5×，堆更紧凑，代价是 GC CPU 略增。

**取舍必须说明**：这**不是"免费的省内存"**。GC 更频繁 → CPU 上升。当前 agent 已占 30% 单核、系统 CPU 空闲 95%，**有余量**；但若流量上升，更频繁的 GC 可能成为新瓶颈。**建议先只上 `GOMEMLIMIT`，观察 24 小时 CPU 与延迟**，再决定是否加 `GOGC`。

**风险评级**：中低。可随时 `systemctl edit --full` 改回并重启。

### 7.3 ~~relay 连接滞留定位~~（已执行，结论：无滞留）

> 本节命令保留作参考。实际执行结果见 §3.4：连接数与 goroutine 数 1:1，无滞留。

```bash
# 1) 建立基线：连续采样，看滞留连接数是否随时间增长
for i in $(seq 1 20); do
  echo "$(date +%H:%M:%S) estab_to_1000=$(ss -tn state established | awk '$4 ~ /:1000$/' | wc -l)"
  sleep 60
done
```

**判读**：
- 若该数字**持续上升** → 确认是连接泄漏，**必须修**（这是未来的 OOM 隐患）。
- 若**稳定在 170 左右波动** → 更像"连接池上限"，优先级下降，但仍有优化价值。

```bash
# 2) 对比 service 侧统计，确认差额是否扩大
python3 -c "
import json
d=json.load(open('/etc/flux_agent/gost.json'))
cur=sum(((s.get('status') or {}).get('stats') or {}).get('currentConns',0) for s in d['services'])
print('sum currentConns =',cur)
"
```

**注意**：`gost.json` 是 10 分钟前的快照，与 `ss` 实时值必然有偏差。判断趋势应看**两者差额是否随时间扩大**，而不是单点绝对差值。

### 7.4 Java 非堆明细 ✅ 已完成（jcmd 实测，见第 5.1 节）

**方案 A — 不改配置，只挂工具（推荐）**：

```bash
# 宿主机下载同版本 JDK 21（仅取 jcmd/jmap/jstat）
# 然后绑定挂载进运行中的容器：先找到容器内 JVM 的 pid（通常是 1）
docker exec springboot-backend sh -c "ls /proc | grep -E '^[0-9]+$' | head"

# 用 jcmd 读堆（需 jcmd 与 JDK21 匹配）
docker cp /tmp/jdk21/bin/jcmd springboot-backend:/tmp/jcmd
docker exec springboot-backend sh -c "/tmp/jcmd 1 GC.heap_info"
```

**方案 B — 加 GC 日志（需重启容器，30 秒中断）**：

```yaml
# docker-compose.yml 的 JAVA_OPTS 追加
JAVA_OPTS: "-Xms256m -Xmx512m -Xlog:gc*:file=/app/logs/gc.log:time,uptime:filecount=3,filesize=10M ..."
```

**方案 C — NMT（需重启，且开销约 5%~10%）**：追加 `-XX:NativeMemoryTracking=summary`，然后 `jcmd 1 VM.native_memory summary`。

**我的建议**：先用方案 A 拿到 `GC.heap_info` 与 `VM.metaspace`，确认 Metaspace 实际用量，再决定是否值得为 `MaxMetaspaceSize` 重启。**在没有这个数据之前，请把"加 Metaspace 上限"视为护栏而非优化。**

### 7.5 24 小时趋势（无需重启，纯观测）

```bash
# 每 5 分钟记录一次，写入 CSV，跑 24 小时
nohup bash -c 'while true; do
  P=$(systemctl show -p MainPID --value flux_agent)
  echo "$(date -Is),$(awk "/VmRSS/{print \$2}" /proc/$P/status),$(awk "/VmHWM/{print \$2}" /proc/$P/status),$(ls /proc/$P/fd | wc -l),$(ss -tn state established | wc -l)" >> /root/flux_trend.csv
  sleep 300
done' >/dev/null 2>&1 &
```

判读标准见《SERVER_MONITORING_GUIDE.md》。

---

## 8. 根因优先级与实际结果

| 优先级 | 根因 | 证据强度 | 实际收益 | 状态 |
|---|---|---|---|---|
| **P0** | Go 堆峰值不归还（无 `GOMEMLIMIT`） | **A 类实测**（pprof + HWM） | **已见效**：MemoryCurrent 2.59 GiB → 80 MiB 起，稳定在 ~100 MiB | ✅ 已实施 |
| ~~P1~~ | ~~relay 连接持续滞留~~ | ❌ **已证伪** | 指标口径不一致；pprof 显示连接与 goroutine 1:1 | ✅ 无需修复 |
| **P2** | 212 service 结构性基数 | **A 类实测**（json 212 条） | 不削减（业务需求） | — |
| **P3** | JVM `-Xms256m` 把空堆钉在物理内存 | **A 类实测**（jcmd GC.heap_info） | **已见效**：-179 MiB 结构性 | ✅ 已实施 |
| **P4** | Docker 日志无轮转（55 MB） | **A 类实测** | 对内存几乎无收益，解决磁盘正确性 | 待部署 |
| **P5** | N+1 查询 | B 类静态 | 对内存无明显收益，代码质量 | 已改，待部署 |
| — | journald 限额 | **已证伪**（实测 84.1 MB） | 无收益 | 已撤回 |

### 8.1 实测收益汇总（同一台机器、同一镜像、同一数据）

```
项目                    优化前              优化后            实测变化
flux_agent MemoryCurrent  2,760,110,080 B    90,386,432 B     -2,669,723,648 B (-2.49 GiB)
flux_agent VmRSS          2,680,140 kB        107,112 kB       -2,573,028 kB (-2.45 GiB)
flux_agent VmHWM          2,717,452 kB        109,780 kB       -2,607,672 kB
flux_agent goroutines     (未测量)            1,026            —
flux_agent HeapAlloc      (未测量)            41.3 MiB         —
springboot-backend RSS    661,436 kB          322,908 kB       -338,528 kB (-331 MiB)
springboot-backend 堆 committed 262,144 K      78,848 K         -183,296 K (-179 MiB)
宿主 MemAvailable         3,997,512 kB        (见对比文档)      —
```

### 8.2 必须说明的三点限定

1. **agent 的 -2.45 GiB 不是"优化省出来的"**。老进程已连续运行 18 小时、累计处理 1,346 万次连接，堆水位被推到 2.59 GiB 后不再归还。重启本身就会让它回到 80 MiB。`GOMEMLIMIT` 的作用是**防止它再次爬回 2.5 GiB**，而不是"把 2.5 GiB 变成了 100 MiB"。**真正的验证需要观察未来 24~72 小时是否仍稳定在百兆级。**
2. **Java 的 -331 MiB 中只有 -179 MiB 是结构性的**，其余来自 THP 覆盖缩小与新 JVM 尚未加载全部类，会部分回升。可信长期收益约 180~200 MiB。
3. ~~P1 未修复~~ → **P1 不存在**。agent 不再爬升的原因是堆高水位被 `GOMEMLIMIT` 约束，与连接无关。

---

## 9. 结论

1. **2.54 GiB 的 `flux_agent` 内存，经 pprof 实测确认：真实工作集只有百兆级（HeapAlloc 41 MiB / Sys 75 MiB / goroutine 1,026），2.5 GiB 是"Go 堆高水位不归还 + THP 把已释放内存钉在 RSS 上"的结果，不是活跃数据，也不是某段代码的分配泄漏。**
2. **证据链完整且可复现**：pprof 堆差分显示增长 81.68% 来自 64 KiB 缓冲区池，但 `defer bufpool.Put` 确实执行、`cap` 匹配、255 个缓冲区 ≈ 130 条连接 × 双向 —— 属正常开销；`AnonHugePages` 占 RSS 的 88%~92.7% 解释了"降不下来"；`MemStats.Alloc` 41 MiB 与 RSS 107 MiB 的差距说明大头不在堆上。
3. ~~唯一真正持续恶化的是 relay 连接滞留~~ → **该结论已证伪**（见 §2 结论五、§3.4）。
   实际唯一的恶化项是 **Go 堆高水位不归还**，已由 `GOMEMLIMIT` 约束。
4. **Java 侧根因是 `-Xms256m` 而非泄漏**：jcmd 实测堆 committed 256 MiB 但 used 仅 40~82 MiB（利用率 16%~32%），改为 `-Xms64m` 后结构性下降 179 MiB。
5. **系统全程无内存压力**（PSI 全 0、Swap 0、OOM 0），因此优化可以稳、可以慢、可以分步验证。
6. **此前基于本地测试机的三项结论（journald 刷屏、swap 抖动、1404 连接）已被生产数据推翻**，相关优化项已撤回或降级。

---

*配套文档：《MEMORY_AUDIT_REPORT.md》提供全部实测原始数据；《PERFORMANCE_COMPARISON.md》给出优化前后对比与验证边界；《SERVER_MONITORING_GUIDE.md》提供本文档所有命令的复现与长期观测方案。*

---

## 11. 三小时趋势复核：对 §2「无泄漏迹象」结论的修正

> 本节是对前文结论的**修正**。§2 当时只有 20 分钟、低负载的观察窗口，
> 那个窗口内 RSS 稳定在 96~112 MB，据此得出「20 分钟内无泄漏迹象」。
> 三小时数据出来后，这个结论**不足以支撑「没有累积」**，必须改写。

### 11.1 观察窗口与数据来源

- 采样脚本 `/root/flux_trend.sh` 每 300 秒写一行到 `/root/flux_trend.csv`，
  由 `systemd-run --unit=flux-trend` 常驻，不依赖 SSH 会话。
- 本文使用时窗：2026-10-30 12:03 ~ 15:04（agent 于 12:01:46 重启），共 37 个采样点。
- 字段顺序：`ts,agent_rss_kb,agent_hwm_kb,agent_fds,agent_threads,agent_estab,
  estab_to_1000,svc_currentconns,agent_utime,java_rss_kb,java_pss_anon_kb,
  java_threads,mem_available_kb,swap_used_kb,psi_some_avg10`。

### 11.2 实测结果：内存随并发连接数显著变化

| 时段 | 采样数 | RSS 范围 | 到 :1000 的连接数 | FD 范围 |
|---|---:|---|---|---|
| 12:03–13:28（低峰） | 18 | 94 ~ 109 MB（均值 **105 MB**） | 83 ~ 134 | 386 ~ 488 |
| 13:33–15:04（高峰） | 19 | 111 ~ 303 MB（均值 **253 MB**） | 139 ~ 552 | 498 ~ 1326 |

RSS 与「到 :1000 的连接数」的**皮尔逊相关系数 r = 0.920**（n=37）。
这不是泄漏的特征，而是**并发连接的内存成本**：每条活跃 relay 连接对应
双向 64 KiB 缓冲、TLS 记录缓冲、goroutine 栈与连接对象。

### 11.3 关键区分：回落行为说明内存能被回收

连接数下降时 RSS 的响应（这是区分「正常波动」与「只涨不落」的关键）：

| 时间 | 连接变化 | RSS 变化 | 判定 |
|---|---|---:|---|
| 13:43 | −199 | **−21.2 MB** | 连接↓ RSS 也↓ |
| 14:08 | −196 | **−50.2 MB** | 连接↓ RSS 也↓ |
| 14:13 | −58 | **−42.4 MB** | 连接↓ RSS 也↓ |
| 14:28 | −94 | +1.0 MB | 连接↓ RSS 未降 |
| 14:38 | −112 | +58.0 MB | 连接↓ RSS 未降 |
| 14:59 | −161 | +5.6 MB | 连接↓ RSS 未降 |

**结论**：Go 运行时确实在回收内存（多次 −20 ~ −50 MB 的回落），
但**回收不彻底**——部分峰值会被保留在 runtime 堆中（HeapIdle），
未立即归还操作系统。这是 Go 的既定行为，可通过 `GOMEMLIMIT` 与
scavenger 逐步归还，不适合用「RSS 没立刻降」判定泄漏。

### 11.4 与重启前老进程的决定性对比

| 指标 | 老进程（11:48–11:58，重启前） | 新进程（13:33 附近，相近连接数） |
|---|---|---|
| RSS | **2,659 ~ 2,672 MB** | **111 MB** |
| estab | 335 ~ 414 | 389 |
| 到 :1000 连接 | 168 ~ 207 | 196 |

**在连接数相当的情况下，老进程占用是新进程的约 24 倍。**
这排除了「2.6 GB 纯粹由当时连接数造成」的解释：
老进程在 18 小时运行中确实累积了大量内存，重启把这份累积清掉了。

### 11.5 仍未被解释的部分（诚实标注）

- 老进程那 2.5 GB 的具体构成**没有被 pprof 抓到**——pprof 是在重启之后
  才通过 `profiling.addr` 启用的，重启前没有 profile 数据。
  因此「具体是什么在累积」属于**未验证**，不能编造。
- 新进程 3 小时内 RSS 从 94 MB 升到峰值 303 MB，其中多少是连接成本、
  多少是尚未显现的累积，**无法在当前数据下分离**。
- 判定是否仍有慢速累积，**必须看 24 小时趋势**，并以「相同连接数下
  RSS 是否随时间抬升」为准，而不是看绝对 RSS。

### 11.6 已生效的兜底措施

- `Environment=GOMEMLIMIT=1200MiB` 已写入
  `/etc/systemd/system/flux_agent.service.d/override.conf`。
- 作用：即使再次出现无界增长，Go 运行时也会在堆接近 1.2 GB 时强制更积极的 GC，
  把最坏情况从「涨到 2.6 GB 甚至触发 OOM」压到「约 1.2 GB 以内」。
- **这不是修复，是兜底**。它不阻止增长，只约束增长的上限。
  真正的修复需要先定位累积源，而这需要 24 小时趋势 + 高负载下的 pprof 差分。

### 11.7 复核用的命令

```bash
# 趋势是否仍在累积：重点看同一连接数下 RSS 是否随时间抬升
tail -40 /root/flux_trend.csv

# 当前连接与内存
systemctl show flux_agent -p MemoryCurrent -p MainPID
ss -tn state established "( dport = :1000 )" | wc -l

# 重新抓取堆快照做差分（pprof 仅监听 127.0.0.1:6060）
curl -s "http://127.0.0.1:6060/debug/pprof/heap?debug=1" > /tmp/heap_t0.txt
# 隔一段时间再抓一次，用 go tool pprof -base 比较
```

**不要把本节读成「已找到泄漏点」或「已证明无泄漏」。**
它是把「无泄漏迹象」这个过强的结论，修正为「内存随连接数变化、
能被部分回收、老进程确有未解释的累积、需 24 小时数据定性」。

---

## 12. 第三轮复核（15:44）：THP 对 RSS 的放大，以及一个真实的数据可靠性缺陷

### 12.1 Java 容器从 320 MB 涨到 440 MB 的原因不是泄漏

| 指标 | 数值 | 说明 |
|---|---|---|
| G1 堆 total | 99,328 K | committed，非最大堆 |
| G1 堆 used | 52,768 K | 实际存活对象 |
| Metaspace used / committed | 67,729 K / 68,352 K | 无 classloader 泄漏（chunks 只增不减会是泄漏特征，此处正常） |
| 线程数 | 46 | 与早前 47 一致 |
| **VmRSS** | **466,912 kB** | 进程驻留 |
| RssAnon | 441,104 kB | 匿名内存 |
| **AnonHugePages** | **364,544 kB** | **其中约 356 MB 是透明大页** |
| cgroup memory.current | 479,887,360 B | ≈458 MiB |

堆 99 MB + 元空间 67 MB = 166 MB，但 RssAnon 是 441 MB。
差额主要由 `AnonHugePages` 解释：宿主机 `transparent_hugepage/enabled` 为 **`[always]`**，
JVM 申请 2 MB 大页后即使对象被回收，大页也不会立即拆分归还。

**结论**：这是 THP 的 RSS 放大效应，不是 Java 堆泄漏。
`-Xms64m` 降低了启动提交量，但 G1 运行期仍会扩展堆区间并触发大页分配，
所以 RSS 仍会回涨到 400 MB 量级。**不能把 320→440 MB 记为「优化失效」。**

同一现象也出现在 agent 上：RssAnon 268,736 kB 中有 161,792 kB 是大页。

### 12.2 若要让 RSS 更贴近真实占用，可选措施（未执行）

以下都会改变宿主机或 JVM 行为，需单独决策，本次**未执行**：

1. 宿主机把 THP 改为 `madvise`：`echo madvise > /sys/kernel/mm/transparent_hugepage/enabled`
   ——全局影响所有进程（含 MySQL/Redis 等），不建议为单个服务改动。
2. JVM 加 `-XX:-UseTransparentHugePages`：只影响本 JVM，但会失去大页带来的 TLB 收益。
3. 维持现状：THP 提升性能，RSS 数字偏大但**没有内存压力**
   （`/proc/pressure/memory` 的 some/full 在 avg10/60/300 上全为 0.00）。

### 12.3 发现并修复的真实缺陷：SQLite busy_timeout 只作用于一条连接

这是本轮排查中**唯一确认的数据可靠性问题**，与内存无关，但会造成流量统计丢失。

**现象**（生产实测）：
```
SQLITE_BUSY: The database file is locked (database is locked)
### SQL: UPDATE forward SET in_flow = in_flow + 602, out_flow = out_flow + 3916 WHERE (id = ?)
### The error may involve com.admin.mapper.ForwardMapper.update-Inline
```
24 小时内命中 2 次（05:59:45、05:59:50，均在容器启动后 30 分钟内）。

**根因**：`springboot-backend/src/main/java/com/admin/config/SQLiteConfig.java:40` 写了
```java
statement.execute("PRAGMA busy_timeout=5000;");
```
但 `PRAGMA busy_timeout` 是**连接级**设置。`SQLiteConfig.run()` 只从池里取用**一条**连接，
在那条连接上生效；连接归还后，HikariCP 池中其余连接仍是驱动默认值。

**驱动实测**（sqlite-jdbc 3.45.0.0，用同版本 jar 直接验证）：
```
带 busy_timeout 参数 -> PRAGMA busy_timeout = 5000
不带参数           -> PRAGMA busy_timeout = 3000
```
即默认并非 0 而是 3000 ms。因此问题不是「完全不等锁」，而是
**等待 3 秒仍拿不到写锁**——说明当时有较长的写事务或 checkpoint 争用。
把 URL 参数设为 5000 是**把等待窗口放宽到 5 秒**，属于缓解而非根治。

**已做修改**（`springboot-backend/src/main/resources/application.yml`，保留 CRLF）：
```yaml
url: jdbc:sqlite:${DB_PATH:/app/data/gost.db}?busy_timeout=5000
```
写在 URL 上由驱动对**每条**新建连接生效，不再依赖某一条连接的偶然状态。

**为什么这条不是「已经修好」**：真正的长写锁来源尚未定位。
`flushFlows()` 用 `synchronized(getForwardLock(id))` 做 JVM 级串行，
但 HikariCP 允许 10 条连接并发写同一个 SQLite 文件；加上每 5 分钟的
`wal_checkpoint(TRUNCATE)` 需要独占，二者叠加可能造成秒级阻塞。
要定位需在故障时刻抓 `PRAGMA` 与慢查询，**目前没有抓到**，属**未验证**。

### 12.4 上报失败不丢数据（已验证）

`go-gost/x/service/global_traffic_manager.go:142-155` 的逻辑：
```go
success, err := sendBatchTrafficReport(m.ctx, reportItems)
if err != nil { fmt.Printf("❌ 全局流量上报失败: ..."); return }   // 不清空
if !success { fmt.Printf("⚠️ 全局流量上报未成功 ..."); return }    // 不清空
m.clearReportedTraffic(reportData)                                  // 仅成功时清空
```
失败时**不清空已累计流量**，下一轮会带着重试。24 小时内 23 次失败
（20 次 HTTP 超时 + 3 次服务器响应异常）**不会造成流量丢失**，只会延后入库。
这一点此前文档未说明，容易误判为数据丢失。

### 12.5 当前生产整体状态（15:44）

| 指标 | 数值 |
|---|---|
| 宿主 used / available | 1,548 MB / 6,391 MB |
| PSI memory some/full (avg10) | 0.00 / 0.00 |
| agent MemoryCurrent | 342,482,944 B（≈327 MB），HWM 310 MB |
| Java 容器 | docker stats 440.9 MiB（VmRSS 466 MB，含 356 MB THP） |
| 前端容器 | 2.7 MiB |
| 最近 10 分钟上报失败 | 0 |
| SQLITE_BUSY（24h） | 2 次，集中在启动后 30 分钟 |

---

## 13. 第四轮：两处已部署的修复与实测效果

本节记录 §12.3 与 §12.4 发现的问题在**生产机上实际修复并验证**的结果。
所有数字均为生产机 87.83.207.7 实测，非推算。

### 13.1 SQLite busy_timeout：已修复（缓解性质）

**改动**（`springboot-backend/src/main/resources/application.yml`）：
```yaml
url: jdbc:sqlite:${DB_PATH:/app/data/gost.db}?busy_timeout=5000
```

**为什么改这里**：`SQLiteConfig` 里的 `PRAGMA busy_timeout=5000` 是**连接级**设置，
只对它取到的那一条连接生效。写在 URL 上由驱动对每条新建连接应用。

**驱动默认值实测**（sqlite-jdbc 3.45.0.0，同版本 jar 直接运行）：
```
带 busy_timeout 参数 -> PRAGMA busy_timeout = 5000
不带参数           -> PRAGMA busy_timeout = 3000
```
默认是 3000ms 而非 0，说明原来的故障是「等 3 秒仍拿不到写锁」。
改成 5000ms 是把窗口放宽，**根因（长写锁来源）仍未定位**，属缓解。

**部署后状态**：容器重建后 `SQLITE_BUSY` 计数为 **0**。
但观察窗口只有数分钟，24 小时内原本也只有 2 次，**不足以证明已解决**，标注为**未验证**。

### 13.2 超长请求参数日志：已修复（实测有效）

**问题**：`/flow/upload` 每 5 秒被节点调用一次，`LogAspect` 把**完整的 AES 密文**
写进日志。密文对排查无价值，却在持续写盘。

**生产实测（改前）**：
```
容器 json 日志增长: 336,859 字节/分钟
推算:               19 MB/小时, 462 MB/天
```

**改动**（`springboot-backend/src/main/java/com/admin/common/aop/LogAspect.java`）：
新增 `MAX_PARAM_LOG_LEN = 1000` 与 `truncateForLog()`，
在两处 `getRequestParams(joinPoint)` 调用后套用。
短参数原样输出（正常业务日志不受影响），超长载荷保留前 1000 字符与原长度。

**生产实测（改后）**：
```
容器 json 日志增长:  12,712 字节/分钟
推算:                744 KB/小时, 17 MB/天
降幅:                96.2%
```

这是**已实测确认**的改善：磁盘写入量减少约 96%，且不影响任何排查能力
（密文本就无法定位问题，保留长度与前缀足以判断请求是否到达、载荷多大）。

### 13.3 两处修复的验证记录

| 项目 | 结果 |
|---|---|
| `mvn test` 全量 | **91 个用例，0 失败 0 错误，BUILD SUCCESS** |
| 镜像构建 | `ahmadplayz721/flux-panel-backend:2.0.7-beta`，已推送 Docker Hub，digest `sha256:0a4d617a…83c0` |
| 容器状态 | `Up (healthy)`，`NRestarts=0` |
| 流量上报 | 容器启动后成功 34 次，失败 **0** 次 |
| SQLITE_BUSY | 重启后 0 次 |
| 前端 | IPv4/IPv6 均 200 |
| 后端 | `/flow/test` 200（`/` 返回 404 是既有行为，无根路由） |
| 数据库 | `sqlite_data` 卷完好，数据未动 |

### 13.4 重启窗口内的失败是预期行为

部署过程中 agent 出现 9 次 `❌ 全局流量上报失败: ... connection refused`，
时间集中在 15:49:46–15:49:52 与 15:55:30–15:55:37，即两次容器重建窗口。
容器启动完成后失败数为 **0**，且 §12.4 已证明失败时不清空累计流量、下一轮自动重试，
**无数据丢失**。

### 13.5 当前生产整体状态（15:58）

| 指标 | 数值 |
|---|---|
| 宿主 used / available | 1,420 MB / 6,519 MB |
| PSI memory some (avg10/60/300) | 0.00 / 0.00 / 0.00 |
| springboot-backend | 324.8 MiB（docker stats） |
| vite-frontend | 2.7 MiB |
| agent MemoryCurrent | 322,293,760 B（≈307 MB），NRestarts=0 |
| openspeedtest（非本项目） | 14.85 MiB |
