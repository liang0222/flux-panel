# 服务器监控指南（SERVER_MONITORING_GUIDE）

面向 Debian 12 + Docker 的生产环境，专门针对 Flux Panel 的组件结构编写。
所有命令都是**只读**的，可以直接在生产上执行。

---

## 1. 一分钟体检

出问题先跑这五条：

```bash
free -h                                   # 内存总量/可用/swap
cat /proc/pressure/memory                 # 内存压力（最重要）
docker stats --no-stream                  # 各容器占用
ps -eo pid,comm,rss,%cpu,nlwp --sort=-rss | head -8
systemctl status flux_agent --no-pager | head -12
```

### 判读：什么才算"有问题"

| 现象 | 是否问题 | 说明 |
|---|---|---|
| `available` 少，但 `buff/cache` 大 | ❌ 不是 | Linux 会主动用空闲内存做缓存，可随时回收 |
| `free` 很小 | ❌ 不是 | 同上，看 `available` 才有意义 |
| swap 用了几百 MB 但 `si/so` = 0 | ⚠️ 观察 | 只是历史遗留，没在换页就不影响 |
| **`vmstat 1` 的 `si`/`so` 持续非 0** | ✅ **是问题** | 真的在换页，性能会崩 |
| **PSI `some avg10` 持续 > 10** | ✅ **是问题** | 有任务在等内存 |
| **PSI `full avg10` 持续 > 5** | ✅ **严重** | 全体任务被阻塞 |
| 进程 RSS 单调上涨不停 | ✅ 是问题 | 需要查泄漏 |
| 进程 RSS 高但 `HWM ≈ RSS` 且平稳 | ⚠️ 结构性 | 堆高水位，不是泄漏 |

> **本项目的真实案例**：优化前 agent RSS 2.54 GiB、PSI 全 0、Swap 0。
> 说明它"占得多"但"没有压力"。这种属于**结构性占用**，不是泄漏。

---

## 2. 组件地图（先知道该看谁）

| 组件 | 形态 | 正常 RSS 量级 | 说明 |
|---|---|---:|---|
| `flux_agent` | systemd 服务 | **100~200 MB** | Go 写的转发代理，每节点一个 |
| `springboot-backend` | Docker 容器 | **300~450 MB** | Java 控制面，含 JVM |
| `vite-frontend` | Docker 容器 | **~3 MB** | nginx 静态托管 |
| `dockerd` / `containerd` | 系统服务 | 150~350 MB | 容器运行时，与业务无关 |
| `systemd-journald` | 系统服务 | 60~150 MB | 日志，与业务无关 |

**排查顺序**：先看 `flux_agent`，再看 `springboot-backend`，其余基本不用管。

---

## 3. `flux_agent` 专项

### 3.1 基础指标

```bash
P=$(systemctl show -p MainPID --value flux_agent)
echo "pid=$P"
grep -E "VmPeak|VmSize|VmHWM|VmRSS|RssAnon|RssFile|Threads|VmSwap" /proc/$P/status
cat /sys/fs/cgroup/system.slice/flux_agent.service/memory.current
cat /sys/fs/cgroup/system.slice/flux_agent.service/memory.peak
ls /proc/$P/fd | wc -l
```

### 3.2 关键：区分"堆高水位"与"泄漏"

| 指标组合 | 含义 |
|---|---|
| `VmHWM ≈ VmRSS`，两者都很大 | **堆高水位**：曾经用到过这么多，之后没归还 OS |
| `VmHWM` 恒定，`VmRSS` 持续涨 | **可能泄漏**：新内存在持续分配 |
| `VmHWM` 和 `VmRSS` 一起持续涨 | **确定在涨**：需要抓 profile |
| `RssAnon` 占 `VmRSS` 99% | Go 堆/栈，不是文件映射 |

### 3.3 抓 Go 堆画像（需先启用 pprof）

**前提**：`/etc/flux_agent/gost.json` 顶层有

```json
"profiling": {"addr": "127.0.0.1:6060"}
```

且 `ss -ltnp | grep 6060` 显示**只监听 127.0.0.1**。

```bash
# 堆摘要
curl -s "http://127.0.0.1:6060/debug/pprof/heap?debug=1" | \
  grep -E "^# (Alloc|HeapAlloc|HeapInuse|HeapIdle|HeapReleased|Sys|HeapObjects|NumGC) = "

# goroutine 数量与分布
curl -s "http://127.0.0.1:6060/debug/pprof/goroutine?debug=1" | head -1
curl -s "http://127.0.0.1:6060/debug/pprof/goroutine?debug=1" | grep -E "^#\s+[0-9]+ @" | head -15

# 抓二进制 profile 下来分析
curl -s -o /tmp/heap1.pb.gz "http://127.0.0.1:6060/debug/pprof/heap"
```

**堆差分（定位增长源）**：

```bash
# 间隔 10 分钟抓两次
curl -s -o /tmp/h1.pb.gz "http://127.0.0.1:6060/debug/pprof/heap"
sleep 600
curl -s -o /tmp/h2.pb.gz "http://127.0.0.1:6060/debug/pprof/heap"

# 在本地（有 go 工具链的机器）分析
go tool pprof -base /tmp/h1.pb.gz /tmp/h2.pb.gz
(pprof) top -cum
(pprof) list <函数名>
```

### 3.4 关键阈值

| 指标 | 正常 | 告警 | 严重 |
|---|---:|---:|---:|
| `VmRSS` | < 300 MB | 300~800 MB | > 800 MB |
| `Threads` | 12~16 | 20~50 | > 50 |
| FD 数量 | 400~700 | 1000~3000 | > 3000 |
| goroutine | 900~1200 | 2000~5000 | > 5000 |
| `%CPU` | < 5% | 5~30% | > 30% |

> 优化前实测：RSS 2,680,140 kB、Threads 15、FD 680、%CPU 30.4。
> **RSS 和 CPU 都超标，但 Threads/FD 正常**——这说明问题在"内存持有"和"转发量"，不在句柄泄漏。

---

## 4. `springboot-backend` 专项

### 4.1 容器内没有 JDK 工具，怎么办

生产镜像是 JRE，**没有 `jcmd`/`jstat`/`jmap`**。用 `--pid=container` 挂一个 JDK 容器进去：

```bash
docker run --rm \
  --pid=container:springboot-backend \
  --network=container:springboot-backend \
  eclipse-temurin:21-jdk \
  sh -c 'jcmd 1 GC.heap_info'
```

**这个方法不需要重启容器、不需要改镜像、不需要装任何东西。**

### 4.2 常用诊断命令

```bash
J() { docker run --rm --pid=container:springboot-backend \
        --network=container:springboot-backend \
        eclipse-temurin:21-jdk sh -c "jcmd 1 $*"; }

J GC.heap_info            # 堆大小与使用量
J VM.metaspace            # Metaspace 用量、类加载器数
J VM.flags                # 实际生效的 JVM 参数
J GC.class_histogram      # 堆里对象最多的类（会触发 Full GC，慎用）
J Thread.print            # 线程栈
J VM.native_memory summary # 需要启动时加 -XX:NativeMemoryTracking=summary
```

### 4.3 判读示例（本项目的真实数据）

优化前：
```
garbage-first heap   total 262144K, used 82156K     ← committed 256MB，只用了 80MB
Metaspace  used 70947K, committed 71552K
```

**`total` 远大于 `used` 就是 `-Xms` 设太大了。** 这 256 MB 是启动时就向 OS 提交的，
在 `THP=always` 下会被大页覆盖并实际占用物理内存。

优化后：
```
garbage-first heap   total 94208K, used 43276K       ← committed 92MB
Metaspace  used 63838K, committed 64384K
```

### 4.4 关键阈值

| 指标 | 正常 | 告警 | 严重 |
|---|---:|---:|---:|
| 堆 `used` | < 300 MB | 300~450 MB | 接近 512 MB |
| 堆 `used` / `total` | 30~80% | < 15%（浪费） | > 95%（要 OOM） |
| Metaspace committed | < 100 MB | 100~180 MB | > 192 MB（撞上限） |
| `Threads` | 50~60 | 100~200 | > 200 |
| 容器 RSS | 300~450 MB | 450~600 MB | > 700 MB |

> 若堆 `used` 长期贴近 `-Xmx512m`，说明**业务真的需要更多堆**，
> 应该调大 `-Xmx` 而不是继续压 `-Xms`。

---

## 5. 趋势采样（判断慢泄漏的唯一方法）

**单点快照无法判断泄漏。** 必须连续采样。

### 5.1 现成脚本

生产上已部署 `/root/flux_trend.sh`，由 `systemd-run` 托管，每 300 秒写一行到 `/root/flux_trend.csv`。

```bash
systemctl status flux-trend --no-pager
tail -20 /root/flux_trend.csv
head -1 /root/flux_trend.csv        # 看字段名
```

字段：`ts, agent_rss_kb, agent_hwm_kb, agent_fds, agent_threads, agent_estab,
estab_to_1000, svc_currentconns, agent_utime, java_rss_kb, java_pss_anon_kb,
java_threads, mem_available_kb, swap_used_kb, psi_some_avg10`

### 5.2 高频采样（短窗口，看分钟级行为）

```bash
cat > /tmp/sample.sh <<'EOF'
P=$(systemctl show -p MainPID --value flux_agent)
while true; do
  printf "%s rss=%s hwm=%s fd=%s th=%s est=%s t1000=%s\n" \
    "$(date +%H:%M:%S)" \
    "$(awk '/VmRSS/{print $2}' /proc/$P/status)" \
    "$(awk '/VmHWM/{print $2}' /proc/$P/status)" \
    "$(ls /proc/$P/fd 2>/dev/null | wc -l)" \
    "$(awk '/Threads/{print $2}' /proc/$P/status)" \
    "$(ss -tn state established 2>/dev/null | tail -n +2 | wc -l)" \
    "$(ss -tn state established 2>/dev/null | awk '$4 ~ /:1000$/' | wc -l)"
  sleep 60
done
EOF
bash /tmp/sample.sh
```

### 5.3 怎么判读趋势

| 形态 | 结论 |
|---|---|
| 数值在固定区间内来回摆动 | **正常**（GC 在工作） |
| 缓慢单调上升，`HWM` 跟着涨 | **泄漏**，抓 profile |
| 上升到某点后长期持平 | **堆高水位**，不是泄漏 |
| 随请求量涨落，请求结束后回落 | **正常** |
| 只涨不落，且 `HeapReleased` 不涨 | **GC 不归还**，需要 `GOMEMLIMIT` |

**观察时长要求**：

| 时长 | 能得出什么结论 |
|---|---|
| 5 分钟 | 只能排除"秒级崩溃"，**不能**判断泄漏 |
| 30 分钟 | 能看出分钟级快速增长 |
| 6 小时 | 能看出小时级慢泄漏 |
| **24 小时** | **能判断日常周期（定时任务、流量高峰）的影响** |
| 72 小时 | 能判断周级增长 |

---

## 6. 连接与文件句柄

```bash
# 全系统（注意：这不是某个进程的）
ss -s

# agent 自己的连接
P=$(systemctl show -p MainPID --value flux_agent)
ss -tnp 2>/dev/null | grep "pid=$P" | wc -l
ss -tnp 2>/dev/null | grep "pid=$P" | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -rn | head

# 监听端口数（应等于 gost.json 里的 TCP 服务数）
ss -ltn | tail -n +2 | wc -l

# FD 构成
ls -l /proc/$P/fd 2>/dev/null | awk '{print $NF}' | sed 's/\[.*//' | sort | uniq -c | sort -rn | head
```

### 连接数观察点（结论：无滞留，此项仅作健康监控）

> ⚠️ **早期曾把这里误判为"连接滞留"，已证伪。**
> `svc_currentconns` 是面板侧恒定上报值（恒 108），与 `ss` 的 socket 计数不是同一口径，
> 不能相减。真实 `to:1000` 在 91~207 间随流量波动，无累积；
> pprof 的 relay goroutine 组数与连接数严格 1:1。

agent 到 relay 节点（示例 `64.83.10.191:1000`）的 ESTAB 数量，与 `gost.json` 里
各 service 自报的 `currentConns` 总和之间，**优化前存在持续扩大的差额**：

```
11:43  to1000=158  svc_current=108   差 50
11:53  to1000=182  svc_current=108   差 74
12:00  to1000=231  svc_current=108   差 123
```

**这是本次审计发现的、唯一真正持续恶化的问题**，但根因尚未定位、尚未修复。
监控时应当关注这个差额是否重新开始扩大。

---

## 7. 日志与磁盘

```bash
journalctl --disk-usage
journalctl -u flux_agent --since '1 hour ago' | wc -l
journalctl -u flux_agent -p warning --since '1 day ago' | tail -20

# 容器日志
for c in springboot-backend vite-frontend; do
  f=$(docker inspect -f '{{.LogPath}}' $c 2>/dev/null)
  [ -n "$f" ] && echo "$c: $(du -h "$f" 2>/dev/null | cut -f1)"
done

# 日志轮转是否生效
docker inspect -f '{{json .HostConfig.LogConfig}}' springboot-backend
```

### 本项目的真实基线

| 项 | 实测值 | 判断 |
|---|---:|---|
| `journalctl --disk-usage` | **84.1 MB** | 正常，远低于默认上限 |
| agent 日志速率 | **每 10 分钟 1 行** | 正常，无刷屏 |
| 后端容器 json 日志 | 55 MB（运行 18 小时） | 无轮转，会一直涨 |
| journald 配置 | 全注释默认 | 无需调整 |

> **不要相信"journald 刷屏"这类结论，一定要实测。**
> 本次审计早期曾误判为 4 GiB 刷屏，那是本地测试机的数据，生产完全不同。

---

## 8. 定时巡检清单

### 每日

```bash
free -h; cat /proc/pressure/memory
docker stats --no-stream
tail -5 /root/flux_trend.csv
journalctl -u flux_agent -p err --since yesterday | tail
```

### 每周

```bash
# 内存趋势（用 awk 算首尾差）
awk -F, 'NR==2{a=$2} END{print "agent RSS 首:",a," 尾:",$2," 变化:",$2-a}' /root/flux_trend.csv

# 连接差额
ss -tn state established | awk '$4 ~ /:1000$/' | wc -l

# 磁盘
df -h /; docker system df
```

### 每月

```bash
# 容器镜像清理（先确认没有需要保留的）
docker image prune -a --filter "until=720h"

# 数据库体积
ls -lh /var/lib/docker/volumes/sqlite_data/_data/
```

---

## 9. 故障速查

| 症状 | 先查什么 | 常见原因 |
|---|---|---|
| 转发全部不通 | `systemctl status flux_agent`、`ss -ltn \| wc -l` | agent 挂了 / gost.json 解析失败 |
| 面板打不开 | `docker ps`、`curl localhost:6365/flow/test` | 容器退出 / 端口冲突 |
| 面板能开但数据不刷新 | 浏览器 Network、`docker logs` | WebSocket 断 / 后端异常 |
| 内存突然涨 | `ps` 排序、`docker stats` | 某个容器重启后重新加载 |
| 内存缓慢涨 | 趋势 CSV | 泄漏，抓 profile |
| CPU 打满 | `top -H -p <pid>` | 转发量激增 / 死循环 |
| 磁盘满 | `du -sh /var/log/journal`、`docker system df` | 日志 / 镜像堆积 |
| 容器反复重启 | `docker inspect -f '{{.RestartCount}}'`、`docker logs --tail 50` | OOMKilled / 启动异常 |

### 确认是否被 OOM Kill

```bash
docker inspect -f '{{.State.OOMKilled}}' springboot-backend
dmesg -T | grep -i "out of memory" | tail
cat /sys/fs/cgroup/system.slice/flux_agent.service/memory.events
```

`memory.events` 里 `oom` 和 `oom_kill` 都是 0 才算没被 OOM。

---

## 附：生产上已部署的两个采样器（2026-10-31）

| 服务 | 间隔 | 输出 | 列数 | 用途 |
|---|---|---|---|---|
| `flux-trend15.service` | 300 秒 | `/root/flux_trend15.csv` | 24 | 15 小时趋势（已完成） |
| `flux-watch.service` | 60 秒 | `/root/flux_watch.csv` | 11 | 收紧 GOMEMLIMIT 后复核 |

`flux_watch.csv` 列定义：
`ts, agent_rss_kb, agent_hwm_kb, agent_huge_kb, agent_fds, agent_goroutines, agent_estab, to1000, java_rss_kb, mem_avail_kb, psi_some`

**已知解析坑**：`curl .../debug/pprof/goroutine?debug=1 | head -1` 的输出是
`goroutine profile: total 1234`，goroutine 数在 **第 4 个字段**（`awk '{print $4}'`），
不是第 3 个。写成 `$3` 会得到字符串 `total`。

### 判断是否泄漏的正确方法

- **不要**用 `goroutine / 连接数` 的比值判定。该比值随负载变化
  （连接 400 时约 4.7，连接 925 时约 2.2），比值下降不代表 goroutine 泄漏。
- **应**对 goroutine 与 estab 做线性回归，看**截距**是否稳定在约 424
  （212 service + 106 TCP listener + 106 UDP listener）。
  截距稳定即无泄漏，截距单调上升才是泄漏。
- RSS 与连接数的相关性：3 小时数据实测 Pearson **r = 0.920**，
  但 15 小时数据显示连接数下降 68% 时 RSS 反升 17% ——
  **RSS 主要由 THP 相位与 Go arena 保留决定，不能单看连接数**。


---

## 10. SQLite 争用专项监控（2026-11-01 新增）

上报超时的根因是 SQLite checkpoint 与上报写争用同一把写锁。日常巡检只需要盯三个量。

### 10.1 上报失败数（最直接的业务指标）

```bash
# 近 24 小时的失败条数（正常应为 0，重启窗口内允许 1~3 条 connection refused）
journalctl -u flux_agent --since "24 hours ago" | grep -c "❌ 全局流量上报失败"

# 看具体原因
journalctl -u flux_agent --since "24 hours ago" | grep "❌ 全局流量上报失败" \
  | sed -E 's/.*(context deadline exceeded|connection refused|SQLITE_BUSY|服务器响应).*/\1/' \
  | sort | uniq -c
```

判读：

| 现象 | 含义 |
|---|---|
| `context deadline exceeded` 成簇出现 | 大概率是 checkpoint 或长事务持写锁（本轮已改为 PASSIVE，若复现需查是否有新的持锁源） |
| `connection refused` 只出现在容器重建前后几秒 | 正常，不是问题 |
| `服务器响应: {"code":-2,...SQLITE_BUSY...}` | 服务端拿到请求但写库失败，需看后端日志 |

### 10.2 后端 SQLITE_BUSY 计数

```bash
# 注意：容器时区是 UTC，--since 不带 Z 会按宿主 CST 解析，必须写 Z
docker logs --since 24h springboot-backend 2>&1 | grep -c 'Error updating database'
```

> 计数要点：`grep -c SQLITE_BUSY` 会把同一异常的堆栈多行都算进去（实测 63 行 = 27 次
> 真实异常）。**用 `Error updating database` 才是异常次数**。

### 10.3 WAL 文件大小（判断 checkpoint 是否正常工作）

```bash
docker exec springboot-backend ls -l /app/data/
```

| WAL 大小 | 判读 |
|---|---|
| 几十 KB ~ 几百 KB | 正常 |
| 接近 4 MB | `wal_autocheckpoint=1000` 页阈值附近，会自动回写 |
| 持续 > 10 MB 且不回落 | checkpoint 失效，需查是否有长读快照一直不释放 |

手动触发一次观测（不影响生产）：

```bash
docker exec springboot-backend sqlite3 /app/data/gost.db "PRAGMA wal_checkpoint(PASSIVE);"
# 返回 busy=0 log=N done=N，且 WAL 大小下降到接近 0 即为正常
```

### 10.4 连接级 PRAGMA 是否按预期生效

`sqlite3` CLI 看到的是**它自己新开连接**的默认值，不是应用连接池里的值，
不能用来判断应用配置。正确做法是核对镜像里的 `application.yml`：

```bash
cid=$(docker create ahmadplayz721/flux-panel-backend:2.0.7-beta)
docker cp "$cid":/app/app.jar /tmp/v.jar && docker rm "$cid"
python3 -c '
import zipfile
y = zipfile.ZipFile("/tmp/v.jar").read("BOOT-INF/classes/application.yml").decode()
print([l.strip() for l in y.splitlines() if l.strip().startswith("url:")][0])
'
```

期望值：
`jdbc:sqlite:${DB_PATH:/app/data/gost.db}?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&cache_size=-8000&temp_store=MEMORY`

### 10.5 新增护栏：应用启动后 5 分钟内的自检清单

| 检查项 | 命令 | 通过标准 |
|---|---|---|
| 容器健康 | `docker ps --filter name=springboot-backend --format '{{.Status}}'` | `Up ... (healthy)` |
| 无重启 | `docker inspect --format '{{.RestartCount}}' springboot-backend` | `0` |
| 数据行数 | `docker exec springboot-backend sqlite3 /app/data/gost.db "SELECT COUNT(*) FROM forward;"` | `109` |
| 无怪文件 | `docker exec springboot-backend ls /app/data/` | 不含带 `?` 的文件名 |
| 上报节拍 | `docker logs --since 2m springboot-backend 2>&1 \| grep -c uploadFlowData` | 约 24（每 5 秒一条） |
| 写库无错 | `docker logs --since 5m springboot-backend 2>&1 \| grep -c 'Error updating database'` | `0` |

---

## 11. 三个「一眼看穿」的判定方法（2026-11-01 补）

前几节都是命令清单，这一节是判读方法 —— 拿到数字后怎么下结论。

### 11.1 判「有没有泄漏」看 HWM，不看末态 RSS

```bash
grep VmHWM /proc/$(systemctl show -p MainPID --value flux_agent)/status
```

`VmHWM` 是内核记录的历史峰值 RSS，只有真的超过才会更新。

- **16 小时 HWM 完全没变** → 这期间从没占用过更多物理内存，可以排除持续泄漏。
- 末态 RSS 比初态低 → **不能**作为证据，因为采样时刻正好落在 GC 前后会差几十 MB。

本项目实测：`agent_hwm` 在 1,679 个样本中恒为 286,712 kB。

### 11.2 判「goroutine 泄漏」看回归截距，不看总数或比值

```bash
# 采样时同时记 goroutine 数与 estab 连接数，然后做线性回归
# goroutine = 斜率 × estab + 截距
```

- goroutine **总数**随连接数变化，直接看总数会把正常波动当成泄漏。
- goroutine/连接 **比值**也会漂移（连接多时分母大，比值反而变小），同样不可靠。
- **正确做法**：把时间窗切段分别回归，看**截距**是否随时间上涨。
  泄漏的 goroutine 会全部落进截距，截距必然逐段抬高。

本项目实测四段截距：672.7 / 677.0 / 674.6 / 676.7 —— 波动 ±2（0.3%），无漂移。

### 11.3 判「Java 内存是否异常」必须扣掉 THP

```bash
pid=$(docker inspect --format '{{.State.Pid}}' springboot-backend)
grep -E '^(Rss|AnonHugePages):' /proc/$pid/smaps_rollup
# 拿堆摘要（非破坏性，只打印到 stdout，进程不会退出）
docker exec springboot-backend kill -3 1
docker logs --tail 300 springboot-backend 2>&1 | grep -A4 '^Heap$'
```

判读公式：

```
真实提交 ≈ 堆 total + Metaspace committed + 其它（代码缓存/线程栈/GC 结构）
THP 放大 = AnonHugePages / RSS
```

本项目实测：提交约 160 MB，RSS 368 MB，AnonHugePages 占 **72%**，放大 2.3 倍。

> 宿主 `/sys/kernel/mm/transparent_hugepage/enabled` 为 `[always]` 时，
> 碰到 2 MB 大页里的任意 4 KB，整个 2 MB 都会计入 RSS。
> **这是统计口径问题，不是应用问题。** 只要 PSI 全 0、无 swap、无 OOM，
> 就不该为了压低这个数字去改 THP 或砍堆上限。

### 11.4 常见误判对照表

| 现象 | 错误结论 | 正确判读 |
|---|---|---|
| RSS 比昨天低 | 「优化成功」 | 可能是采样撞上 GC，看 HWM |
| RSS 台阶式上涨后持平 | 「泄漏」 | 看堆 total 是否同步上涨；THP 相位也会造成台阶 |
| goroutine 总数从 1000 涨到 2000 | 「泄漏」 | 看同期连接数；做回归看截距 |
| `docker stats` 内存比 `free` 算出来的多 | 「统计错了」 | 容器含缓存、共享页、大页，口径不同，不能相加 |
| WAL 文件一直不缩小 | 「checkpoint 失效」 | PASSIVE 模式本就不收缩，看 `log/done` 是否相等 |

---

## 12. 连接生命周期监控（2026-11-09 新增）

前面十一节的监控都围绕「内存」。这一节补上**「删掉的东西是否真的消失了」**——它是本次事故的直接判据。

### 12.1 核心指标

```bash
# 1) 已建立连接按目标 IP 分组（看是否有已弃用的 relay 还在被连）
ss -tn state established | awk 'NR>1 {print $4}' | cut -d: -f1 | sort | uniq -c | sort -rn | head

# 2) 某目标 IP 的连接数随时间变化（正常应稳定或随业务波动，不应单调堆积）
for i in $(seq 1 10); do
  echo "$(date +%H:%M:%S) $(ss -tn dst 136.0.197.135 | grep -c ESTAB)"
  sleep 6
done

# 3) 这些连接是否还在传数据（关键：僵尸连接 vs 活连接）
ss -tni dst 64.83.10.191 | grep -E 'bytes_sent|bytes_acked' | head

# 4) 空闲时长分布（lastsnd 越大越可能是僵尸）
ss -tn dst 64.83.10.191 | awk 'NR>1 {print $5}' | sort -n | uniq -c | tail
```

### 12.2 agent 侧的连接/资源

```bash
PID=$(systemctl show flux_agent -p MainPID --value)
echo "fds: $(ls /proc/$PID/fd | wc -l)"
echo "threads: $(grep Threads /proc/$PID/status)"
echo "estab: $(ss -tnp state established | grep -c flux_agent)"
curl -s 'http://127.0.0.1:6060/debug/pprof/goroutine?debug=1' | head -1   # total N
curl -s 'http://127.0.0.1:6060/debug/pprof/heap?debug=1' | head -1        # heap profile: a: b [c: d]
```

**判读**：goroutine 数与 `estab` 做回归，**看截距**（正常约 675），不看总数。

### 12.3 「删除后是否立即释放」的验收方法

这是本次修复的验收标准，可随时复现：

```bash
# 1) 建一条长连接并保持（另开一个终端）
exec 3<>/dev/tcp/127.0.0.1/<端口>
while true; do echo ping >&3; head -c 4 <&3; sleep 1; done

# 2) 在面板删除该端口对应的转发（或调 gost REST API DELETE /config/services/<name>）

# 3) 观察
ss -ltn | grep <端口>              # 应立刻无输出
ss -tn state established | grep <端口>   # 应立刻为 0
# 步骤 1 的终端应在 3 秒内报错退出（新版），旧版会一直跑
```

### 12.4 常见误判

| 现象 | 错误结论 | 正确判读 |
|---|---|---|
| `ss` 里旧 relay 还有几十条连接 | 「删除没生效」 | 看 `lastsnd` 和字节增量：若持续有数据流动，是**连接没被主动关**（旧版 Transport 缺陷）；若只是 `keepalive` 计时器，等 10–15 分钟会自然消失 |
| 面板显示节点在线但端口不通 | 「面板坏了」 | 面板的 `status` 只看 WebSocket 连接，**不是健康检查**。要去节点上查 gost 是否真的在监听 |
| agent RSS 从 115 MB 掉到 57 MB | 「Transport 修复省了 58 MB」 | 复合归因：未加壳 + THP 相位重置 + 连接数下降 + 残留连接清除。见《PERFORMANCE_COMPARISON.md》§9.4 |
| `DeleteServiceResponse success:true` 但连接还在 | 「删除接口有 bug」 | 删除**命令**成功了，是**已建立的连接**没被关。两件事 |

### 12.5 journald 上限（已安装）

```bash
cat /etc/systemd/journald.conf.d/99-flux.conf
# SystemMaxUse=200M / SystemMaxFileSize=32M / MaxRetentionSec=1month
# Compress=yes / RateLimitIntervalSec=30s / RateLimitBurst=2000
journalctl --disk-usage
```

实测：379.3 MB → 180.1 MB。

### 12.6 请求日志脱敏验收

```bash
# 应为 0（明文 secret 不再出现）
docker logs --since 10m springboot-backend 2>&1 | grep -oE '"secret":"[^"*]{6,}"' | wc -l
# 应能看到脱敏后的形式
docker logs --since 10m springboot-backend 2>&1 | grep -oE '"secret":"[^"]*"' | sort -u
# 期望：9e8b****cd88
```

## 13. Java 堆外内存监控（glibc arena）

### 13.1 为什么普通监控看不出来

`docker stats` 与 `ps` 只给 RSS 总量。Java 的 RSS = 堆 + Metaspace + **glibc arena** + 线程栈 + DirectBuffer + 映射的 jar。堆没涨不代表 RSS 不涨；THP 关掉后 RSS 不降也不代表没有别的原因。**必须把 RSS 拆到 VMA 级别。**

### 13.2 拆解 RSS 的标准流程

```bash
# 1) 取 PID
P=$(docker inspect springboot-backend --format '{{.State.Pid}}')

# 2) 总量与匿名/文件分布
grep -E 'VmRSS|VmHWM|RssAnon|RssFile|VmData|Threads' /proc/$P/status
cat /proc/$P/smaps_rollup

# 3) 堆与 Metaspace（容器内无 jcmd，用 SIGQUIT）
docker exec springboot-backend sh -c 'kill -3 1' >/dev/null 2>&1
sleep 2
docker logs --since 1m springboot-backend 2>&1 | grep -E 'garbage-first heap|Metaspace|class space'
```

SIGQUIT 会把 45+ 个线程栈写进容器日志（约 100–200 KB），偶尔用可以，不要写进定时任务。

### 13.3 识别 glibc arena 的判据

**核心特征：匿名可写、起始地址按 64 MB（0x4000000）对齐。**

```bash
P=$(docker inspect springboot-backend --format '{{.State.Pid}}')
python3 /root/arena_stat.py "$P"
```

`arena_stat.py` 内容见 §13.7，输出示例：

```
vma_total   = 258
anon_rw     = 100
arena_segs  = 3
arena_rss   = 140.2 MB
non_arena   = 87.4 MB
```

**判读**：

| `arena_segs` | 判断 |
|---|---|
| ≤ 5 | 正常（已设 `MALLOC_ARENA_MAX=2`） |
| 10 – 30 | 偏高，注意趋势 |
| > 40 | 异常，说明上限未生效或被人改回 |

**注意 G1 堆保留区也会命中 64 MB 对齐**，所以 `arena_segs` 比真实 arena 数多 1–2；看**趋势**比看绝对值更有意义。

### 13.4 四个必须区分的概念

| 现象 | 含义 | 是否问题 |
|---|---|---|
| 堆 used 高但 RSS 不高 | 对象多但页已回收 | 否 |
| 堆 used 低但 RSS 高 | 堆外占用（arena / 线程栈 / DirectBuffer） | **要看 arena 段数** |
| RSS 涨但 `Pss_Anon` 不涨 | 共享库映射变化 | 否 |
| RSS 缓慢爬升、arena 段数也涨 | arena 碎片累积 | **是** |
| RSS 缓慢爬升、arena 段数不变 | 堆内缓存 / 类加载 | 查堆 |

### 13.5 常设监控命令

```bash
P=$(docker inspect springboot-backend --format '{{.State.Pid}}')
echo "$(date +%H:%M:%S) rss=$(awk '/VmRSS/{print $2}' /proc/$P/status)kB hwm=$(awk '/VmHWM/{print $2}' /proc/$P/status)kB thr=$(awk '/Threads/{print $2}' /proc/$P/status) arena=$(python3 /root/arena_stat.py $P | awk '/arena_segs|arena_rss/{printf "%s ", $3}')"
```

建议加进 crontab 每小时一条，写 CSV 便于画趋势。

### 13.6 已知基线（2026-11-10 01:56 起）

| 指标 | 基线值 |
|---|---|
| Java `VmRSS` | 329–336 MB（空闲，稳定期） |
| Java `VmHWM` | 约 331–341 MB |
| arena 段数 | 3 |
| arena 实占 | 140.2 MB |
| 堆 used | 46–72 MB |
| Metaspace used | 61 MB |
| 线程数 | 46–47 |
| `docker stats` | 300–305 MiB |
| agent `VmRSS` | 52–54 MB |
| agent `Threads` | 12–13 |

**与 01:51:57 之前的基线不可比**：之前 arena 段 51、实占 235.4 MB、`docker stats` 343.2 MiB。

### 13.7 `arena_stat.py`（可直接部署）

```python
#!/usr/bin/env python3
# 用法: python3 arena_stat.py <java_pid>
# 统计 glibc malloc arena 的实占内存（已排除 G1 堆保留区）
import sys, re

pid = sys.argv[1]
vmas, cur = [], None
for line in open('/proc/%s/smaps' % pid):
    m = re.match(r'^([0-9a-f]+)-([0-9a-f]+) (\S+) \S+ \S+ \S+\s*(.*)$', line)
    if m:
        if cur:
            vmas.append(cur)
        cur = {'start': int(m.group(1), 16), 'end': int(m.group(2), 16),
               'perm': m.group(3), 'path': m.group(4).strip(), 'rss': 0}
    elif cur is not None and line.startswith('Rss:'):
        cur['rss'] = int(line.split()[1])
if cur:
    vmas.append(cur)

anon = [v for v in vmas if v['path'] == '' and 'w' in v['perm']]
# 64MB 对齐 + 至少 32MB，是 glibc arena 与 G1 堆保留区的共同特征
cand = [v for v in anon
        if v['start'] % (64 * 1024 * 1024) == 0 and (v['end'] - v['start']) >= 32 * 1024 * 1024]

# 0xe0000000 是 JDK 在 Linux x86-64 上的默认 G1 堆基址，不是 arena，必须排除。
# 若 JVM 改了堆基址（-XX:HeapBaseMinAddress），这里要同步调整。
G1_BASE = 0xe0000000
arena = [v for v in cand if v['start'] != G1_BASE]
g1 = [v for v in cand if v['start'] == G1_BASE]

print('vma_total   = %d' % len(vmas))
print('anon_rw     = %d' % len(anon))
print('arena_segs  = %d' % len(arena))
print('arena_rss   = %.1f MB' % (sum(v['rss'] for v in arena) / 1024))
print('g1_heap_rss = %.1f MB' % (sum(v['rss'] for v in g1) / 1024))
print('non_arena   = %.1f MB' % ((sum(v['rss'] for v in anon) - sum(v['rss'] for v in arena) - sum(v['rss'] for v in g1)) / 1024))
for v in cand:
    tag = 'G1 堆(排除)' if v['start'] == G1_BASE else 'arena'
    print('  0x%x  vma=%d kB  rss=%d kB  %s' % (v['start'], (v['end'] - v['start']) // 1024, v['rss'], tag))
```

部署：`docker cp` 不需要，直接在宿主机放 `/root/arena_stat.py`，用容器 PID 调用即可（`/proc/<pid>/smaps` 宿主可见）。

### 13.8 四条误判提醒

1. **`docker stats` 是 cgroup 口径，含 page cache**，与 `VmRSS` 不等，差几十 MB 正常。
2. **`VmHWM` 只增不减**，是历史峰值，不能用来判断"现在是否泄漏"。
3. **arena 段数包含 G1 堆保留区**，绝对值为 3 时真实 arena 是 1–2 个，不要按"应该等于 2"去核。
4. **换内核 / 改 THP 策略后旧基线全部作废**，必须重新采基线再谈涨跌。

## 14. 原生内存归还（TrimNativeHeapInterval）的观察方法

### 14.1 判断参数是否生效

```bash
docker inspect springboot-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep JAVA_OPTS
# 必须含 -XX:TrimNativeHeapInterval=15000

docker exec springboot-backend java -XX:+PrintFlagsFinal -XX:TrimNativeHeapInterval=15000 -version 2>/dev/null | grep TrimNativeHeapInterval
# 应输出 = 15000 {product} {command line}
```

### 14.2 正确的 arena 统计（扣除 G1 堆）

```bash
JP=$(pgrep -f app.jar | head -1)
python3 /root/arena_stat.py "$JP"
```

脚本内容见 §13.4。输出形如：

```
0xe0000000       VMA 75,776 kB   RSS 73,024 kB   <- G1 堆，排除
0x7c1cd0000000   VMA 63,780 kB   RSS 25,600 kB   <- glibc arena，这才是要看
```

关键：`0xe0000000` 是 **G1 堆的保留区基址**（JDK 在 Linux x86-64 上固定映射），不是 glibc arena。做 arena 归因时必须把它排除，否则会凭空多出 73 MB。

### 14.3 观察 RSS 呼吸

```bash
JP=$(pgrep -f app.jar | head -1)
for i in $(seq 1 20); do
  ts=$(date +%H:%M:%S)
  rss=$(awk '/VmRSS/{print $2}' /proc/$JP/status)
  hwm=$(awk '/VmHWM/{print $2}' /proc/$JP/status)
  echo "$ts rss=${rss}kB hwm=${hwm}kB"
  sleep 15
done
```

正常形态：`VmRSS` 在 240-290 MB 之间上下波动（周期约 15 秒，与 trim 间隔一致），`VmHWM` 恒定在启动峰值（约 331 MB）。

异常形态：

| 现象 | 可能原因 | 下一步 |
|---|---|---|
| `VmRSS` 单调上升且 `VmHWM` 跟着涨 | 真泄漏 | SIGQUIT 看堆，`pprof` 看 Go 侧 |
| `VmRSS` 恒定在 350 MB 以上 | 参数没生效 | 先查 `docker inspect` 的 `JAVA_OPTS` |
| 波动超过 100 MB | trim 间隔过短或堆频繁扩缩 | 调大间隔或看 GC 日志 |

### 14.4 三个常见误判

1. 看到 RSS 在动就以为有问题——trim 的存在就是为了让它动。只要 `VmHWM` 不涨，就是健康的。
2. 用 `docker stats` 判断泄漏——它读的是 cgroup `memory.current`，含 page cache，波动比进程 RSS 更大。判泄漏看 `VmHWM` 与长期趋势。
3. 把 `0xe0000000` 算进 arena——会凭空多出 73 MB，导致「限制 arena 没效果」的错误结论。

## 15. 配置上报链路的观察方法（2026-11-10 新增）

节点每 10 分钟通过 `POST /flow/config` 上报一次完整 gost 配置，面板据此清理「节点上还有、但数据库里已删」的孤立 service/chain/limiter。这条链路本身不涉及内存，但它是排查「删了转发端口还在」的第一现场。

### 15.1 确认上报是否正常

```bash
docker logs --since 30m springboot-backend 2>&1 | grep "配置数据接收成功"
# 期望：每个在线节点每 10 分钟一条，例如
#   🔓 节点 5 配置数据接收成功（已解密）
```

节点不上报 = 面板永远看不到该节点上多出来的 service。此时先在节点上 `systemctl status flux_agent`，再看 `journalctl -u flux_agent -n 50` 有没有 `❌ WebSocket读取消息错误` 或 `配置上报失败`。

### 15.2 确认有没有孤立项被清理

```bash
docker logs --since 24h springboot-backend 2>&1 | grep "删除孤立"
```

- **0 条**：说明节点上的 service/chain 与数据库完全一致，这是常态。
- 出现 `删除孤立的服务: ["<forwardId>_<userId>_<userTunnelId>_tcp", ...]`：说明该转发在数据库里已删、但节点上还留着。这类日志是**修复生效的证据**，不是故障。
- 如果**大量**出现（几十上百条集中在同一时刻）：先怀疑是不是刚做过批量删除；如果没做，要立刻查数据库是否可读（见 15.3）。

### 15.3 批量清理的安全边界（排查时必看）

2026-11-10 起，孤立项判定改为「先批量查 id 是否存在，再逐条判定」。**批量查询失败时整轮跳过清理**，不会误删。因此：

- 若日志里出现 `批量查询 id 是否存在失败，本轮跳过清理以避免误删` → 数据库当时不可用，本轮什么都没删，等下一轮即可。
- **不应该**看到「数据库报错的同时，日志里还有大量 `删除孤立`」——如果真出现这种组合，说明有人把失败路径改回了「返回空集合」，属于严重回归，因为那等于把一次抖动放大成大规模误删。

### 15.4 判断「节点上到底多不多」

面板只上报、不查询节点当前配置。要直接看节点上实际有什么：

```bash
# 节点上
ls -l /etc/flux_agent/gost.json
python3 - <<'PY'
import json
d = json.load(open('/etc/flux_agent/gost.json'))
print('services', len(d.get('services') or []))
print('chains  ', len(d.get('chains') or []))
print('limiters', len(d.get('limiters') or []))
PY
```

把 `services` 数量与面板数据库的 `select count(*) from forward;` 对比：正常情况下 service 数 ≈ 转发数 × 2（`_tcp` + `_udp`）+ 隧道数（`_tls`）。

### 15.5 数据库侧的对应查询

```bash
docker exec springboot-backend sqlite3 /app/data/gost.db \
  "select 'forward',count(*) from forward union all
   select 'tunnel',count(*) from tunnel union all
   select 'chain_tunnel',count(*) from chain_tunnel;"
```

数据量与孤立清理日志要能对上：如果 `forward` 从 193 掉到 190，随后出现 3 组 `删除孤立` 日志，那是正常的级联清理。
