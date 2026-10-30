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
