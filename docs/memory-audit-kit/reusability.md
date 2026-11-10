# 通用步骤 vs 下次替换项

> 判断标准：**「换一个项目还成立吗？」** 成立 → 通用；只在本次成立 → 替换。
> 用途：下次遇到类似任务，通用部分直接照搬，只改替换项，省掉 80% 的重新摸索。

---

## 一、完全通用（换任何项目都照做）

### 1.1 事实纪律

| 通用做法 | 为什么 |
| --- | --- |
| 结论分 A/B/C/D 四级（实测 / 静态高危 / 需生产数据 / 质量非内存） | 混在一起写，读者无法判断可信度 |
| 没实测的标「未测试」，禁止推算比例 | 一次编数字，整份报告作废 |
| 「堆没涨」≠「资源没漏」 | 连接/FD 泄漏在堆外 |
| RSS 缓升 ≠ 泄漏，看 `VmHWM` 是否同步抬升 | JVM/Go 都有预热期 |
| 换内核/换 GC/加 JVM 参数后，旧基线作废 | THP 会随内核默认值改变 |
| Docker 容器内存不能相加当物理内存 | cgroup 含 page cache 与共享页 |

### 1.2 审计顺序

```
只读审计 → 输出分级诊断 → 低风险直接改 → 构建/测试 → 单变量 A/B → 上线 → 六查 → 趋势采样 → 文档
```

**绝不能跳过的两处**：
- 「只读审计」阶段不改任何一行代码（包括"顺手"改注释）；
- 「单变量 A/B」在**生产机**上做（同机同镜像同数据快照），另找环境会引入新变量。

### 1.3 判定"是不是真泄漏"的四步

| 步 | 命令/方法 | 结论 |
| --- | --- | --- |
| 1 | 对象级：`pprof heap`（Go）/ `kill -3`（JVM） | 存活字节小 → 不是对象泄漏 |
| 2 | 增长级：多日采样拟合 `goroutines = a + b×业务量`，看截距 | 截距稳定 → 无漂移 |
| 3 | 系统级：`ss -tan state established \| wc -l`、`ls /proc/$PID/fd \| wc -l` | 单调上升 → 连接/FD 泄漏 |
| 4 | 分配器级：`smaps` 按 64MB 对齐分组 + 排除堆基址 | 段数≈线程数 → arena 碎片 |

**这四步覆盖了 95% 的"内存高"投诉**，且顺序不能反：先排除对象泄漏，再查系统资源，最后才怀疑分配器。

### 1.4 A/B 四条纪律

1. 先预热再测延迟（冷 JIT 会得出反向结论）；
2. 轮转顺序（A→B、B→A）抵消漂移与缓存；
3. 每轮前确认容器存活（中途死掉那轮作废）；
4. 方向不一致即判噪声，只写「未观察到可测量劣化」。

### 1.5 上线固定六查

```bash
docker inspect -f '{{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' <c>
curl -s -o /dev/null -w '%{http_code}\n' <health_url>          # 含 IPv6
<db integrity check>                                            # 或等价业务校验
<业务计数必须与上线前相等>
<下游/节点连接全部重连成功>
docker logs --since 12h | grep -cE 'ERROR|Exception|WARN'       # 应为 0
```

### 1.6 Git 与发布纪律

| 通用 | 说明 |
| --- | --- |
| 独立分支 + 一个逻辑一个提交 | 便于单独 revert |
| 提交信息写"为什么" | 「perf(backend): 限制 arena 数量，回收约 50-120MB」比「优化内存」有用 |
| 凭据永不进仓库 | GitHub push protection 直接拒收 |
| 被安全网拦住换等效写法 | 见 `lessons.md` §24 的对照表 |
| **安装入口一律指 tag 不指分支** | 分支会漂移，新装机器会拿到未发布脚本 |
| 改了安装脚本/compose 就要重建 tag | 否则 tag 上仍是旧内容 |
| 记 manifest digest 不记 tag | tag 会漂，digest 才是回滚锚点 |

### 1.7 交付文档结构

八份主文档 + 一份 `HANDOFF.md`。`HANDOFF.md` 必须有：一句话现状 / 已完成（带提交号）/ 待办分 P0-P1-P2 / 踩坑表 / 快速恢复命令 / 回滚表。**这是"新开对话能接着用"的唯一保证。**

---

## 二、需要替换的项（下次改这里）

### 2.1 环境类

| 替换项 | 本次值 | 怎么取 |
| --- | --- | --- |
| `<REPO_URL>` / `<REPO_DIR>` | `liang0222/flux-panel` / `/root/v2.0.7-beta` | 用户给 |
| `<OS>` | Debian 12 | `cat /etc/os-release` |
| `<HOST>` / 凭据 | `87.83.207.7` / root | 用户给；**不入库** |
| 远程通道 | 无 sshpass，用 paramiko：`cat /tmp/x.sh \| python3 /tmp/rc.py` | `command -v sshpass` |
| 本机工具链 | 无原生 Go/JDK/Maven，全走 Docker | `command -v go javac mvn node` |
| 包缓存挂载 | `/root/gomod`、`/root/gocache`、`/root/.m2/repository` | 首次构建后固定 |

### 2.2 服务与端口

| 替换项 | 本次值 | 怎么取 |
| --- | --- | --- |
| compose 服务名 | `backend` / `frontend` | `docker compose config --services` |
| 容器名 | `springboot-backend` / `vite-frontend` | `docker ps` |
| 业务端口 | 6365 / 6366 | `ss -lntp` |
| pprof 端口 | 6060（`127.0.0.1`） | 查框架配置 |
| 健康检查路径 | `/flow/test` | 查 controller 的 `@GetMapping` |
| 数据文件 | `/app/data/gost.db`（SQLite） | 查 `DB_PATH` env |

### 2.3 内存机制（按项目类型选）

| 项目类型 | 先查什么 | 本次是否命中 |
| --- | --- | --- |
| Go | goroutine 斜率、`pprof heap` inuse、连接/FD 数 | ✅ 连接泄漏 |
| JVM（容器内无 jcmd） | `kill -3` 堆摘要 + `smaps` 分组 | ✅ arena 碎片 + 空闲页 |
| JVM（有 jcmd） | `GC.heap_info`、NMT | 未用（无 jcmd） |
| Node | `--max-old-space-size`、是否有 dev server 常驻 | ✅ 无 Node 常驻（nginx 静态） |
| 前端 | 是否多阶段构建、是否 SSR | ✅ 已是 nginx 静态 |

**注意**：语言占比 ≠ 内存占比。本次 Go 占 58.9% 代码，但内存最大的是 Java（原 373 MB）和已泄漏的连接（内核侧）。

### 2.4 参数值

| 参数 | 本次值 | 依据 | 下次怎么定 |
| --- | --- | --- | --- |
| `MALLOC_ARENA_MAX` | `2` | A/B 实测：ctl 235 MB / =2 165 MB / =4 187 MB | 别选 4（反而更高）；小内存机优先 2 |
| `-XX:TrimNativeHeapInterval` | `15000`（ms） | 实测常驻 −83 MB（−23%），延迟无劣化 | JDK ≥ 21 才有；按 GC 周期取 10–30 s |
| `-Xms` / `-Xmx` | `64m` / `512m` | 堆 used 46.5 MB，远未打满 | **不要往下调**，除非有压测证明并发余量够 |
| `-XX:MaxMetaspaceSize` | `192m` | 实测 used 61 MB | 按实测 used × 2–3 倍 |
| `GOMEMLIMIT` | `512MiB` | HeapAlloc 约 59 MB，8 倍余量 | 实测 HeapAlloc × 4–8 倍；**上限高于历史峰值时 scavenger 不触发** |
| `@Async` 池 | core 2 / max 4 / queue 100 | 10 分钟一次 × 5 节点 | 峰值频率 × 节点数 × 2，队列给 100 即可 |
| Hikari | pool 8 / minIdle 2 / idle 300000 / maxLife 500000 | `idleTimeout ≥ maxLifetime` 会被静默禁用 | **idle 必须 < maxLifetime** |
| journald | 200M 总量 / 32M 单文件 / 1 月 / 2000 msg per 30s | 实测从 4.1 GiB 降到 180 MB | 按磁盘 1–2% 定 |
| Docker 日志 | json-file 10m × 3 | — | 按排障窗口定，别关 |

### 2.5 代码位置

| 替换项 | 本次位置 | 怎么找 |
| --- | --- | --- |
| 连接拷贝函数 | `go-gost/x/internal/net/transport.go` | `grep -rn "io.CopyBuffer" --include=*.go` |
| `defer` 顺序 | `go-gost/x/service/service.go` 的 `Serve()` | `grep -rn "defer cancel()" --include=*.go` |
| 无界缓存 | `FlowController` / `WebSocketServer` / `EncryptionConfig` | `grep -rn "static final .*Map<" --include=*.java` |
| 空方法 | `TunnelServiceImpl.isError` | `grep -rnE "private void \w+\([^)]*\) *\{ *\}" --include=*.java` |
| 循环内查库 | `CheckGostConfigAsync` | 启发式扫描后**逐条核对频率×次数** |
| 日志脱敏 | `LogAspect.maskSensitive` | `grep -rn "getRequestParams" --include=*.java` |

### 2.6 判据阈值（可微调）

| 判据 | 本次阈值 | 含义 |
| --- | --- | --- |
| arena 段数异常 | ≈ 线程数（47 线程 / 51 段） | 每线程一个 arena |
| 对齐段识别 | 起始地址 `% (64<<20) == 0` + 匿名 rw | glibc arena 特征（**必须排除 JVM 堆基址**） |
| goroutine 泄漏 | 多日截距极差 < 1% | 674.8–676.1 即无漂移 |
| FD 泄漏 | 随时间单调上升 | 377–1,114 波动不算泄漏，1,017 且不降才算 |
| 连接残留 | `lastsnd > 3600s` | 生产 82 条中 66 条命中 |

---

## 三、决策树：下次从哪下手

```
用户说"内存高"
├─ 先问：是持续高，还是持续涨？
│  ├─ 持续高（稳定）：→ 查"配置放大"（日志级别/journald/缓存上限/线程池/连接池）
│  └─ 持续涨：→ 进下面四步
├─ 四步判泄漏（对象 → 增长 → 系统资源 → 分配器）
│  ├─ 对象涨 → 真泄漏，修引用链 + 加容量上限
│  ├─ objects 稳但 socket/FD 涨 → 连接生命周期，查 ctx 取消 + defer 顺序
│  ├─ 都不涨但 RSS 大 → 分配器碎片，查 arena / TrimNativeHeap / GOMEMLIMIT
│  └─ HWM 恒定、RSS 小幅波动 → 正常，别改
└─ 单服务还是全机？
   ├─ 单服务 → 按上面
   └─ 全机 → 先看 free -m 的缓存占比、journald、dockerd、其他无关进程（本次 1panel 占 128 MB、AI 工具占 974 MB）
```

---

## 四、可直接复用的脚本片段

### 4.1 JVM 内存分类（排除 G1 堆基址）

```python
import re, sys
pid = sys.argv[1]
G1_BASE = 0xe0000000          # 从 SIGQUIT 的 heap 基址取，或先跑一次看最大对齐段
segs, cur = [], None
for line in open(f'/proc/{pid}/smaps'):
    m = re.match(r'^([0-9a-f]+)-([0-9a-f]+) (\S+)', line)
    if m:
        if cur: segs.append(cur)
        cur = {'start': int(m.group(1), 16), 'perms': m.group(3), 'rss': 0, 'path': ''}
    elif line.startswith('Rss:') and cur: cur['rss'] = int(line.split()[1])
    elif line.startswith('/') and cur: cur['path'] = line.strip()
if cur: segs.append(cur)
arena = [s for s in segs if not s['path'] and s['perms'].startswith('rw')
         and s['start'] % (64 << 20) == 0 and s['start'] != G1_BASE]
g1    = [s for s in segs if s['start'] == G1_BASE]
other = [s for s in segs if not s['path'] and s not in arena and s not in g1]
print(f"arena_segs={len(arena)} arena_rss={sum(s['rss'] for s in arena)/1024:.1f} MB")
print(f"g1_heap_rss={sum(s['rss'] for s in g1)/1024:.1f} MB")
print(f"other_anon={sum(s['rss'] for s in other)/1024:.1f} MB")
print(f"total_rss={sum(s['rss'] for s in segs)/1024:.1f} MB")
```

### 4.2 连接残留检测

```bash
# 按目标 IP 统计已建立连接
ss -tan state established | awk 'NR>1{print $5}' | cut -d: -f1 | sort | uniq -c | sort -rn | head
# 单连接是否还在传数据
ss -tni state established | grep -A1 <TARGET_IP>
# 最久未发数据的连接
ss -tni state established | grep -oE 'lastsnd:[0-9]+' | sort -t: -k2 -rn | head -5
```

### 4.3 幂等的 systemd 内存限制写入

见 `lessons.md` §18 的 `ensure_service_memory_limit()` 片段。

---

## 五、下次开新对话时，把这段贴进去

```
上一轮已完成 Flux Panel 的内存审计与优化，全部上线。现在继续。

请先读 docs/HANDOFF.md（进度快照 / 待办 P0-P2 / 踩坑 / 恢复命令 / 回滚表），
再读 docs/memory-audit-kit/lessons.md（测量口径陷阱）与 reusability.md（通用 vs 替换项）。
不要重复已完成部分，直接从 HANDOFF 的 P0 开始。

关键约束：不改功能与接口、不动数据、不换技术栈；结论必须有实测证据；
未实测的标「未测试」；改安装脚本或 compose 后必须重建 tag。
```

---

## 六、这次的通用资产清单

| 资产 | 位置 | 可复用性 |
| --- | --- | --- |
| 审计法 | Skill `production-memory-audit` | 换项目直接用 |
| 任务书 | `docs/memory-audit-kit/prompt.md` | 改占位符即可 |
| 踩坑表 | `docs/memory-audit-kit/lessons.md` | 34 条，多数跨项目成立 |
| 进度交接 | `docs/HANDOFF.md` | 结构可复用，内容需重写 |
| 八份交付文档 | `docs/*.md` | 结构可复用 |
| 监控脚本 | 生产 `/root/arena_stat.py` | 改 `G1_BASE` 即可 |
