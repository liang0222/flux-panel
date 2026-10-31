# 部署指南（DEPLOYMENT_GUIDE）— Debian 12

本文档说明**本次已执行的生产变更**、**尚未部署的代码改动**分别怎么处理。
所有步骤保持现有部署方式不变（同一套 Docker Compose、同一套 systemd 单元、同一套数据卷）。

**生产服务器**：`87.83.207.7`（Debian 12，内核 6.1.0-50-amd64，8 vCPU，7.75 GiB 内存）
**备份目录**：`/root/backup_20261030_120012`

---

## 0. 当前生产状态（重要）

截至 2026-11-01 06:05，生产上已上线 5 轮变更，**仅剩 Go agent 侧的二进制改动未上线**：

| 类别 | 内容 | 状态 |
|---|---|---|
| ✅ 已生效 | `JAVA_OPTS` 改为 `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m` | 容器已重建 |
| ✅ 已生效 | `GOMEMLIMIT=512MiB`（systemd drop-in，2026-10-31 14:42 由 1200MiB 收紧） | agent 已重启 |
| ✅ 已生效 | pprof 监听 `127.0.0.1:6060` | agent 已重启 |
| ✅ 已上线 | `ForwardServiceImpl` 批量查询、`application.yml` 键名修正与 `busy_timeout`、`LogAspect` 日志截断、`logback` 调整 | 镜像 `sha256:0a4d617a…` 已部署 |
| ✅ 已上线（2026-11-01 06:05） | checkpoint `TRUNCATE→PASSIVE`、连接级 PRAGMA 移入 JDBC URL、`SqliteDatasourceUrlTest`、`JwtUtilTest` 修正 | 镜像 `sha256:c42978b6…` 已部署，`Up (healthy)`、`Restarts=0`、数据 109/4/1 完好 |
| ❌ 未上线 | Go agent 的 `GOMEMLIMIT` 内置默认值、日志级别、`init()` 重构 | **需要重建二进制** |
| ✅ 已生效 | 容器日志轮转 `10m×3` | 2026-11-01 复核：backend 与 frontend 的 `HostConfig.LogConfig` 均为 `{"Type":"json-file","Config":{"max-file":"3","max-size":"10m"}}` |

> **关于 agent 二进制的重要事实（2026-10-31 核实）**：生产 `/etc/flux_agent/flux_agent` 是 **8,327,088 字节、UPX 加壳**的上游版本，**不是**本次构建的 32,088,216 字节版本。因此生产 agent **不含** `applyMemoryLimit()` 代码，`GOMEMLIMIT` 完全依赖 Go 运行时原生读取环境变量生效（已用 `GODEBUG=gctrace=1` 实证）。自建二进制的部署路径**尚未在生产走过**。

**下面 §1 讲如何处理未上线的代码，§2 讲已生效变更的复核，§3 讲完整发布流程。**

---

## 1. 发布未上线的代码改动

### 1.1 前置检查

```bash
cd /root/v2.0.7-beta
git branch --show-current          # 应为 perf/memory-optimization
git status --short                 # 确认没有会被覆盖的他人改动
docker --version && docker compose version
free -m                            # 记录当前基线
```

**如果工作区有你自己未提交的修改，先 `git stash` 或另建分支，不要直接切换。**

### 1.2 后端（Java）

镜像构建是多阶段的，`Dockerfile` 内已包含 `mvn package`，无需本地装 Maven：

```bash
cd /root/v2.0.7-beta/springboot-backend
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta .
```

> ⚠️ **打同一个 tag 会覆盖原镜像**。生产正在运行的容器不受影响（它用的是本地已有镜像层），
> 但一旦 `docker compose up -d` 就会切到新镜像，且**旧镜像层可能被清理**。
> 稳妥做法是**先另打一个 tag 备份**：

```bash
# 备份当前生产镜像
docker tag ahmadplayz721/flux-panel-backend:2.0.7-beta \
           ahmadplayz721/flux-panel-backend:2.0.7-beta-preopt

# 再构建新版本
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta .
```

重建容器（约 7 秒不可用）：

```bash
cd /root
docker compose up -d backend
docker logs -f springboot-backend    # 等待 "Started AdminApplication"
curl -s http://127.0.0.1:6365/flow/test
```

**验证点**（对照《PERFORMANCE_COMPARISON.md》）：

```bash
# 1) Hikari 告警应消失（因为 maximum-pool-size 键名修正后池为 10，max-lifetime 仍 500000）
#    实际上这条告警是 idle-timeout(600000) >= max-lifetime(500000) 引起的，属既有行为，
#    若要消除需把 idle-timeout 调到 500000 以下 —— 本次未改。
docker logs springboot-backend 2>&1 | grep -i "HikariCP"

# 2) 堆应按需增长，committed 应远小于 256MB
docker run --rm --pid=container:springboot-backend \
  --network=container:springboot-backend eclipse-temurin:21-jdk \
  sh -c 'jcmd 1 GC.heap_info'

# 3) 健康检查
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:6365/flow/test   # 200
```

### 1.3 节点代理（Go）

> ⚠️ **重启代理会中断该节点上所有正在转发的连接（当前 212 个服务）。必须在维护窗口执行。**

```bash
cd /root/v2.0.7-beta/go-gost
docker run --rm -v "$PWD":/src -v /root/gomod:/go/pkg/mod -v /root/gocache:/root/.cache/go-build \
  -w /src golang:1.23-bookworm \
  sh -c 'CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o dist/flux_agent .'
ls -lh dist/flux_agent        # 预期约 32 MB
```

部署到节点：

```bash
systemctl stop flux_agent
cp /root/v2.0.7-beta/go-gost/dist/flux_agent /etc/flux_agent/flux_agent.new
chmod +x /etc/flux_agent/flux_agent.new
mv /etc/flux_agent/flux_agent.new /etc/flux_agent/flux_agent
systemctl start flux_agent
systemctl status flux_agent --no-pager
```

**验证点**：

```bash
# 1) 环境变量的值应覆盖代码内置默认值（当前生产值 512MiB）
systemctl show flux_agent -p Environment          # 应有 GOMEMLIMIT=512MiB
P=$(systemctl show -p MainPID --value flux_agent)
grep -E "VmRSS|VmHWM" /proc/$P/status

# 2) pprof 仍可用（因为我们保留了 profiling 配置）
curl -s "http://127.0.0.1:6060/debug/pprof/heap?debug=1" | head -3

# 3) 转发是否正常：从面板看节点在线、服务数仍是 212
```

### 1.4 前端

**本次没有改动前端代码**，不需要重新构建。若要重建：

```bash
cd /root/v2.0.7-beta/vite-frontend
docker build -t ahmadplayz721/flux-panel-frontend:2.0.7-beta .
cd /root && docker compose up -d frontend
```

---

## 2. 已生效变更的复核命令

### 2.1 确认 `GOMEMLIMIT` 生效

```bash
systemctl show flux_agent -p Environment
# 期望: Environment=GOMEMLIMIT=512MiB

P=$(systemctl show -p MainPID --value flux_agent)
tr '\0' '\n' < /proc/$P/environ | grep -i GOMEMLIMIT
# 期望: GOMEMLIMIT=512MiB
```

### 2.2 确认 pprof 只在回环

```bash
ss -ltnp | grep 6060
# 期望: LISTEN 0 ... 127.0.0.1:6060 ...
# ❌ 如果看到 0.0.0.0:6060 或 [::]:6060，立刻删除 gost.json 的 profiling 字段并重启
```

### 2.3 确认 JVM 参数

```bash
JP=$(docker inspect -f '{{.State.Pid}}' springboot-backend)
tr '\0' '\n' < /proc/$JP/cmdline | grep -E "^-X|^-XX"
# 期望:
#   -Xms64m
#   -Xmx512m
#   -XX:MaxMetaspaceSize=192m
```

### 2.4 确认后端容器日志路径（日志轮转是否生效）

```bash
docker inspect -f '{{json .HostConfig.LogConfig}}' springboot-backend
# 变更前: {"Type":"json-file","Config":{}}
# 重建后: {"Type":"json-file","Config":{"max-file":"3","max-size":"10m"}}
```

> 当前仍是 `Config={}`，因为 compose 的 `logging` 段**只在容器创建时生效**，改配置不会影响已存在容器。

---

## 3. 完整发布流程（推荐顺序）

```
① 备份（必须）
   ↓
② 重建后端镜像 + 重建容器（约 7 秒中断）
   ↓
③ 观察 10 分钟：健康检查、日志、内存
   ↓
④ 维护窗口：重建 agent 二进制 + 重启 agent（中断全部转发）
   ↓
⑤ 观察 30 分钟：转发连通性、连接数、内存
   ↓
⑥ 重建 frontend 容器（让日志轮转生效，约 1 秒中断）
```

### ① 备份

```bash
TS=$(date +%Y%m%d_%H%M%S)
mkdir -p /root/backup_$TS
cp /root/docker-compose.yml /root/backup_$TS/
cp /etc/flux_agent/config.json /etc/flux_agent/gost.json /root/backup_$TS/
cp /etc/flux_agent/flux_agent /root/backup_$TS/flux_agent.bin
systemctl cat flux_agent > /root/backup_$TS/flux_agent.service.txt
docker inspect springboot-backend > /root/backup_$TS/backend_inspect.json
docker inspect vite-frontend > /root/backup_$TS/frontend_inspect.json
docker tag ahmadplayz721/flux-panel-backend:2.0.7-beta \
           ahmadplayz721/flux-panel-backend:2.0.7-beta-preopt
docker tag ahmadplayz721/flux-panel-frontend:2.0.7-beta \
           ahmadplayz721/flux-panel-frontend:2.0.7-beta-preopt
echo "/root/backup_$TS" > /root/.last_backup_dir
ls -la /root/backup_$TS
```

### ② 数据安全

**本次没有任何数据库结构变更**，`sqlite_data` 卷不需要备份也可以。
但若想更保险：

```bash
docker run --rm -v sqlite_data:/d -v /root/backup_$TS:/b alpine \
  sh -c 'cp /d/gost.db /b/gost.db.bak 2>/dev/null; ls -lh /b'
```

> 数据库用的是 WAL 模式，`gost.db` 单独复制可能不含最新事务；要精确备份应同时复制
> `gost.db-wal` 与 `gost.db-shm`，或先 `PRAGMA wal_checkpoint(TRUNCATE)`。

---

## 4. 部署后监控（前 24 小时）

```bash
# 每 2 分钟自动采样（已在生产上运行过，可复用）
cat /root/flux_trend.sh
systemctl status flux-trend --no-pager

# 手动看一眼
tail -20 /root/flux_trend.csv
```

判读标准见《SERVER_MONITORING_GUIDE.md》。

**关键观察窗口**：

| 时间点 | 关注什么 |
|---|---|
| 5 分钟 | 服务起得来、健康检查 200、无异常日志 |
| 30 分钟 | agent RSS 是否仍在 100 MB 量级、连接数是否稳定 |
| 6 小时 | 是否出现缓慢上涨 |
| 24 小时 | **这是判断 GOMEMLIMIT 是否有效的第一个真实节点** |
| 72 小时 | 长期稳定性 |

---

## 5. 常见问题

### Q1: `docker compose up -d` 报 `JWT_SECRET` 未设置

根目录的 `docker-compose-v4.yml` / `-v6.yml` 引用了 `${JWT_SECRET}` 等变量。
生产实际使用的是 `/root/docker-compose.yml`（不引变量）。**不要在根目录直接 `up`**。

### Q2: 后端起来后 Hikari 仍报 `idleTimeout is close to or more than maxLifetime`

这是 `idle-timeout: 600000 >= max-lifetime: 500000` 的既存告警，**不是本次引入的**。
本次未改动这两个值（`max-lifetime` 的 500000ms 是刻意用于"数据库文件被替换后重开"）。
若要消除告警，把 `idle-timeout` 调到 500000 以下即可，但需评估连接回收节奏。

### Q3: 重启 agent 后转发不通

```bash
journalctl -u flux_agent -n 50 --no-pager
ss -ltn | wc -l                  # 应仍有 106 个 TCP 监听
systemctl status flux_agent --no-pager
```

若是 `GOMEMLIMIT` 值非法导致启动失败：
```bash
# 临时移除限制
systemctl edit flux_agent        # 删掉 Environment 行
systemctl daemon-reload && systemctl restart flux_agent
```

### Q4: pprof 6060 端口不见了

面板在用户修改转发配置时会重新下发 `gost.json`，可能覆盖 `profiling` 字段。
重新添加并重启即可（不影响业务）：

```bash
python3 - <<'PY'
import json
p="/etc/flux_agent/gost.json"
d=json.load(open(p))
d["profiling"]={"addr":"127.0.0.1:6060"}
json.dump(d,open(p,"w"),separators=(",",":"))
print("ok")
PY
systemctl restart flux_agent
```

> 注意：用 `json.load/json.dump` 重写会改变缩进格式，但字段内容不变，agent 能正常解析。
> 若要保留原格式，用字符串插入法（见《ROLLBACK_GUIDE.md》§4）。

---

## 6. 不要做的事

| ❌ 不要 | 原因 |
|---|---|
| 在根目录 `docker compose up -d` | 会因 `${JWT_SECRET}` 未定义而失败 |
| 给容器设 `mem_limit: 400m` 之类 | 会 OOMKilled，且是"用低限制掩盖问题" |
| 把 `-Xmx512m` 调到 128m | 实测堆已用到 43~82 MB，128m 余量不足 |
| 把 `GOMEMLIMIT` 设到 200MiB 以下 | 会引发 GC 抖动，降低转发吞吐（512MiB 实测无副作用，见 §2.1 注） |
| 把 pprof 改成 `0.0.0.0:6060` | 等于把性能剖析接口暴露到公网 |
| `git reset --hard` / `git clean -fd` | 会丢失未提交改动 |
| 在生产直接 `go test` | `x/service` 包的 `init()` 历史上会 `log.Fatal` 让测试进程退出 |


---

## 7. 第五轮上线记录（2026-11-01 06:05 CST）

### 7.1 上线内容

| 文件 | 改动 |
|---|---|
| `springboot-backend/src/main/java/com/admin/config/SQLiteConfig.java` | `performCheckpoint()` 的 `wal_checkpoint(TRUNCATE)` → `wal_checkpoint(PASSIVE)`；`onShutdown()` 仍保留 TRUNCATE |
| `springboot-backend/src/main/resources/application.yml` | URL 追加 `journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&cache_size=-8000&temp_store=MEMORY` |
| `springboot-backend/src/test/java/com/admin/config/SqliteDatasourceUrlTest.java` | 新增，4 个护栏用例 |
| `springboot-backend/src/test/java/com/admin/common/utils/JwtUtilTest.java` | 修正 `cachedTokenStillExpires` 的间歇失败 |

### 7.2 上线步骤（本次实际执行的命令）

```bash
# 1) 构建并核对镜像内容 —— 不要只看构建成功
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta ./springboot-backend
cid=$(docker create ahmadplayz721/flux-panel-backend:2.0.7-beta)
docker cp "$cid":/app/app.jar /tmp/verify/app.jar && docker rm "$cid"
python3 -c '
import zipfile
z = zipfile.ZipFile("/tmp/verify/app.jar")
y = z.read("BOOT-INF/classes/application.yml").decode()
url = [l for l in y.splitlines() if l.strip().startswith("url:")][0]
assert "wal_autocheckpoint" not in url, "URL 含未知参数，会静默打开空库"
d = z.read("BOOT-INF/classes/com/admin/config/SQLiteConfig.class")
assert b"wal_checkpoint(PASSIVE)" in d
print("镜像核对通过:", url.strip())
'

# 2) 推送
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta

# 3) 生产：备份 compose 后拉取并重建
#    注意 compose 里的服务名是 backend，不是 springboot-backend
cd /root
cp docker-compose.yml docker-compose.yml.bak-$(date +%Y%m%d-%H%M%S)
docker compose pull backend
docker compose up -d backend
```

**踩坑记录**：`docker compose pull springboot-backend` 会报 `no such service`。
生产 `/root/docker-compose.yml` 的服务名是 `backend` / `frontend`，
容器名才是 `springboot-backend` / `vite-frontend`，两者不是一回事。

### 7.3 上线后必查（本次全部通过）

```bash
docker ps --filter name=springboot-backend --format '{{.Status}}'          # Up (healthy)
docker inspect --format 'Restarts={{.RestartCount}}' springboot-backend    # 0
docker exec springboot-backend sqlite3 /app/data/gost.db \
  "SELECT COUNT(*) FROM forward; SELECT COUNT(*) FROM node; SELECT COUNT(*) FROM user;"
#   期望 109 / 4 / 1（与上线前一致）
docker exec springboot-backend ls -l /app/data/
#   只应有 gost.db / gost.db-wal / gost.db-shm，不得出现文件名带 ? 的怪文件
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6365/flow/test    # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6366/            # 200
curl -s -o /dev/null -w '%{http_code}\n' 'http://[::1]:6366/'              # 200（IPv6）
docker logs --since 10m springboot-backend 2>&1 | grep -c SQLITE_BUSY      # 0
```

### 7.4 已知的正常现象

- 重建窗口内 agent 会出现 1~3 条 `❌ ... connection refused`（本次为
  06:05:42、06:05:43、06:05:47 三条）。容器起来后自动恢复，**不是回归**。
- `docker stats` 里新容器内存会明显低于旧容器（本次 320 MiB vs 459 MiB），
  但这是 JVM 刚重启未预热导致的，**不能当作内存优化成绩**。

### 7.5 `/app/data/` 里的实验残留必须清掉

在生产卷上做基准测试会留下 `_latbench.db`、`_ckrace.db?busy_timeout=5000` 这类文件。
后者是 python `sqlite3.connect()` 不带 `uri=True` 时误建的（见
`docs/evidence/checkpoint-race-2026-11-01.md` 的「复现脚本」一节）。
本次已删除，清理后目录只剩 `gost.db` / `gost.db-shm` / `gost.db-wal`（共约 3 MB）。

清理命令：

```bash
cd /var/lib/docker/volumes/sqlite_data/_data
ls -la                      # 先看清楚再删
rm -f "_latbench.db" _ckrace.db* _dbg.db* "_ckrace.db?busy_timeout=5000" "_dbg.db?busy_timeout=5000"
ls -la                      # 应只剩 gost.db / -wal / -shm
```

### 7.6 下一步：Go agent 侧仍未上线

本轮**没有**改动 agent 二进制。生产 `/etc/flux_agent/flux_agent` 仍是
8,327,088 字节的 UPX 上游版本，因此：

- agent 的 `applyMemoryLimit()`（内置 1200 MiB 默认值、`FLUX_AGENT_MEMLIMIT` 开关、
  日志来源标注）**在生产不生效**，但 `GOMEMLIMIT=512MiB` 这个环境变量仍由
  Go 运行时原生读取并生效（已用 `GODEBUG=gctrace=1` 实证）。
- 若要启用内置默认值与日志级别修改，需按 §1 用自建二进制
  （Release `2.0.7-beta-perf` 的 `gost-amd64`，32,088,216 字节）替换。
- 前端容器的 `HostConfig.LogConfig.Config` 仍为 `{}`，需再次重建才应用日志轮转。
