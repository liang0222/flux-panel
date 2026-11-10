# 内存审计复用包（memory-audit-kit）

> 把这次 Flux Panel 的「内存过高 → 审计 → A/B 验证 → 上线 → 文档」全过程沉淀成可复用资产。
> 下次遇到同类任务，先读本文件，再按需读其余四份。

---

## 文件索引

| 文件 | 内容 | 什么时候读 |
| --- | --- | --- |
| `prompt.md` | 完整任务书（20 节，含占位符替换表） | 要**开一个新任务**时，整段复制给 Agent |
| `../HANDOFF.md` | 进度交接（已完成 / 待办 P0-P2 / 恢复命令 / 回滚） | **接手继续做**时先读这个 |
| `lessons.md` | 33 条踩坑与解法（测量口径 / 代码缺陷 / 配置环境 / 工具安全网） | 动手前扫一遍，能省掉大半返工 |
| `reusability.md` | 通用步骤 vs 下次替换项 + 决策树 + 可复用脚本 | 判断"哪些能照搬、哪些要换"时 |
| `README.md` | 本索引 | 第一次进来 |

另有一份 Skill：`production-memory-audit`（已提交待确认），装进技能库后可被自动匹配。

---

## 这次的结论（一句话版）

服务器内存高的真实原因是**三个独立机制叠加**，且**没有一个是对象泄漏**：

| # | 机制 | 证据 | 处置 | 效果 |
| --- | --- | --- | --- | --- |
| 1 | 已删除隧道的连接不释放（`io.CopyBuffer` 不接 ctx + `defer` 顺序颠倒） | 生产 82 条残留、66 条 `lastsnd>3600s`、仍跑 64 KB/s、累计 125.7 MB；A/B：旧 `STILL ALIVE after 20s` vs 新 `EOF at 3.0s` | 新增 `TransportContext` + 修 `defer` 顺序 | 连接数 800+ → 200；agent RSS 110–123 MB → **53 MB** |
| 2 | glibc 每线程 arena 碎片 | `smaps` 51 个 64MB 对齐段、实占约 162 MB（已扣除 G1 堆基址 73 MB）；47 线程 / 8 CPU | `MALLOC_ARENA_MAX=2` | arena 段 51 → 3、RSS 378 → **329 MB** |
| 3 | arena 空闲页从不归还操作系统 | 加参数后 RSS 仍 356 MB 而堆 used 仅 46.5 MB | `-XX:TrimNativeHeapInterval=15000` | 常驻 −83 MB（−23%）、延迟无劣化 → **235 MB** |

**排除项（重要）**：Go agent 的 `pprof heap` 存活仅 0.64 MB、goroutine 逐日截距极差 0.19% → **无堆泄漏**；Java 堆 used 46.5 MB ≪ `-Xmx512m` → **无堆泄漏**。前端是 nginx 静态托管、**无 Node 常驻**。

最终：Java RSS **373 → 235 MB**，agent RSS **110–123 → 53 MB**，宿主 used **约 1,500 → 791 MB**（含 journald 4.1 GB → 180 MB）。

---

## 五分钟上手

```bash
# 1. 看进度
sed -n '1,40p' docs/HANDOFF.md

# 2. 看踩坑（至少看「测量口径」一节，能避免得出反向结论）
grep -A4 '## 一、测量口径' docs/memory-audit-kit/lessons.md

# 3. 判断要改哪里
sed -n '/## 三、决策树/,/## 四/p' docs/memory-audit-kit/reusability.md
```

---

## 三条最容易犯的错（提前警告）

1. **把「堆没涨」当成「没泄漏」** —— 连接泄漏的代价在内核 socket 缓冲与 FD 上，堆剖析器看不见。
2. **把 JVM 堆基址算进 glibc arena** —— 64MB 对齐段里混着 `0xe0000000`（G1 堆），不排除会虚报 70+ MB。
3. **用冷 JIT 的容器做延迟对照** —— 会得出"慢了 7 倍"的假结论；必须预热 3 轮 + 轮转顺序。
