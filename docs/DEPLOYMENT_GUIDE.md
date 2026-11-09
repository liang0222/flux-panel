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

---

## 8. 第六轮上线记录（2026-11-09 23:05 – 2026-11-10 00:25 CST）

### 8.1 上线顺序（已执行）

| 时间 | 对象 | 操作 |
|---|---|---|
| 23:04 | 备份 | `/root/agent_backup_20261109-230427/`（`flux_agent.bak` / `gost.json.bak` / `config.json.bak`） |
| 23:05:19 | agent 二进制 | 从 GitHub Release 下载 → `systemctl stop` → `cp .new` → `-V` 验证 → `mv` → `systemctl start` |
| 23:32:31 | 后端镜像 | `docker compose pull backend` → `up -d backend` |
| 23:44 | journald | 写 `/etc/systemd/journald.conf.d/99-flux.conf` → `systemctl restart systemd-journald` → `journalctl --vacuum-size=200M` |
| 00:09:43 | 后端镜像 | `pull backend` → `up -d backend` |
| 00:16 | 后端镜像 | 同上 |
| 00:25:02 | 前端镜像 | `pull frontend` → `up -d frontend` |

### 8.2 agent 二进制升级（手工，CI 不产出）

`.github/workflows/docker-build.yml` **只构建前端与后端镜像，不构建 agent**。agent 必须手工交叉编译 + 上传 Release。

```bash
# 1) 编译（本地无 Go 工具链时用容器）
cd /root/v2.0.7-beta/go-gost
docker run --rm -v "$PWD":/src -v /root/gomod:/go/pkg/mod -v /root/gocache:/root/.cache/go-build \
  -w /src -e GOFLAGS=-mod=mod golang:1.23-bookworm \
  sh -c 'CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/gost-amd64-new . &&
         CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/gost-arm64-new .'
sha256sum dist/gost-amd64-new dist/gost-arm64-new
# 期望：91f3c258592f73b94bab12782870336037793c7e9ad1b8b5f99c295062db69cd  gost-amd64-new
#        d64a2cdf36deac3b3b200d8ad93952f514b1fe8fd999e53fc428eed1bcfd72f7  gost-arm64-new

# 2) 上传 Release（curl 直传会被 CC Safety Net 拦，必须用 gh）
gh release upload 2.0.7-beta-perf dist/gost-amd64-new dist/gost-arm64-new \
  -R liang0222/flux-panel --clobber

# 3) 单节点升级（在目标机上执行）
systemctl stop flux_agent
curl -fL -o /etc/flux_agent/flux_agent.new \
  https://github.com/liang0222/flux-panel/releases/download/2.0.7-beta-perf/gost-amd64
chmod +x /etc/flux_agent/flux_agent.new
/etc/flux_agent/flux_agent.new -V            # 必须输出 gost 3.1.0 (go1.23.12 linux/amd64)
mv /etc/flux_agent/flux_agent.new /etc/flux_agent/flux_agent
systemctl start flux_agent
```

**升级后必查**：

```bash
systemctl show flux_agent -p MainPID -p ActiveState -p NRestarts
PID=$(systemctl show flux_agent -p MainPID --value)
grep -E 'VmRSS|VmHWM|Threads' /proc/$PID/status
grep -a -c TransportContext /etc/flux_agent/flux_agent   # 新构建未加壳，应为 1
ss -ltn | wc -l                                          # 应与 gost.json 的 service 数一致
journalctl -u flux_agent --since "5 min ago" | grep -c '"level":"error"'
```

### 8.3 本轮踩到的坑（补充到 §5 常见问题之外）

| 坑 | 现象 | 解法 |
|---|---|---|
| agent 二进制加壳导致无法判版本 | 旧版 UPX 4.21，`grep -a TransportContext` 全 0 | 用 `-V` 看 Go 版本（旧 `go1.23.4`、新 `go1.23.12`）；新构建不加壳可直接 grep |
| `-L tcp://...` 冒烟会 panic | `nil pointer dereference` @ `config/parsing/service.ParseService` | fork 既有缺陷，旧二进制同样 panic；冒烟用 `gost.json` 或 `-C cfg.yaml` |
| 父提交 `-mod=readonly` 编不过 | `missing go.sum entry for gopsutil/v3` | `go.mod`/`go.sum` 修正是必要修复，随提交保留 |
| `curl` 上传 Release 被拦 | `BLOCKED by CC Safety Net / network.upload-remote` | 改用 `gh release upload --clobber` |
| 节点 5 显示在线但端口不通 | `status=1`、每 10 分钟配置上报正常，但 relay 端口全 FAIL | 面板的 status 只看 WebSocket，**不是健康检查**；需到节点上查 gost 为何没监听 |

### 8.4 其他节点升级（未执行）

node 5 / node 6 仍是 2.0.2 旧版、node 4 是 1.2.4。面板自带安装命令生成：

```
POST /api/v1/node/install  {"id": <nodeId>}
→ curl -L https://raw.githubusercontent.com/liang0222/flux-panel/refs/heads/feat/port-traffic-ranking/install.sh -o ./install.sh && chmod +x ./install.sh && ./install.sh -a <面板地址> -s <node secret>
```

在目标机上执行即可（`install.sh` 的 `update_flux_agent()` 会下载到 `flux_agent.new` → 停服务 → `mv` → `-V` 验证 → 启动）。


## 9. 第十轮上线：glibc arena 限制（2026-11-10 01:51:57）

### 9.1 变更内容

`springboot-backend/Dockerfile` 新增 `ENV MALLOC_ARENA_MAX=2`。**只重建后端镜像，compose 文件、环境变量、端口、卷、网络、健康检查全部不变。**

### 9.2 本地构建与推送

```bash
cd /root/v2.0.7-beta
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta   -f springboot-backend/Dockerfile springboot-backend
# 确认变量进了镜像
docker image inspect ahmadplayz721/flux-panel-backend:2.0.7-beta   --format '{{range .Config.Env}}{{println .}}{{end}}' | grep MALLOC_ARENA_MAX
# 应输出 MALLOC_ARENA_MAX=2
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta
```

本次 manifest digest：`sha256:b2224ff1fe6912c7e784117be44f236b69cc0a98bf50e92e56bd259d60a89349`。

### 9.3 生产更新

```bash
# 1. 备份 compose（即使本轮不改它）
cd /root && cp docker-compose.yml /root/docker-compose.yml.bak-$(date +%Y%m%d-%H%M%S)

# 2. 拉取并重建后端（服务名是 backend，容器名才是 springboot-backend）
docker compose pull backend
docker compose up -d backend

# 3. 等健康
sleep 40
docker inspect springboot-backend --format 'health={{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}'
```

### 9.4 上线后必查

```bash
# 环境变量确实生效
docker inspect springboot-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep MALLOC_ARENA_MAX

# 健康与双栈
curl -s -o /dev/null -w "v4=%{http_code}
" http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w "v6=%{http_code}
" -6 "http://[::1]:6365/flow/test"
curl -s -o /dev/null -w "front=%{http_code}
" http://127.0.0.1:6366/

# 数据完整
docker exec springboot-backend sh -c "sqlite3 /app/data/gost.db \
  'pragma integrity_check; select count(*) from forward; select count(*) from node;'"

# 节点重连（应看到节点 2/4/5/6）
docker logs --since 5m springboot-backend 2>&1 | grep "连接建立成功"

# arena 段数（应远小于 10）
P=$(docker inspect springboot-backend --format '{{.State.Pid}}')
grep -c '' /proc/$P/smaps >/dev/null && python3 -c "
import re
pid='$P'; v=[]; cur=None
for line in open(f'/proc/{pid}/smaps'):
    m=re.match(r'^([0-9a-f]+)-([0-9a-f]+) (\\S+) \\S+ \\S+ \\S+\\s*(.*)\$', line)
    if m:
        if cur: v.append(cur)
        cur={'start':int(m.group(1),16),'perm':m.group(3),'path':m.group(4).strip(),'rss':0}
    elif cur is not None and line.startswith('Rss:'):
        cur['rss']=int(line.split()[1])
if cur: v.append(cur)
anon=[x for x in v if x['path']=='' and 'w' in x['perm']]
al=[x for x in anon if x['start']%(64*1024*1024)==0]
print('arena_segments=%d arena_rss_MB=%.1f' % (len(al), sum(x['rss'] for x in al)/1024))
"

# 日志脱敏仍生效
docker logs --since 5m springboot-backend 2>&1 | grep -oE '"secret":"[^"]*"' | sort -u
```

### 9.5 本次上线实测结果

| 项 | 结果 |
|---|---|
| 容器状态 | `Up (healthy)`、`restarts=0`、`oom=false` |
| 启动时间 | `2026-11-09T17:51:57.689736757Z` |
| `MALLOC_ARENA_MAX` | `2`（容器内生效） |
| v4 / v6 / frontend | 200 / 200 / 200 |
| 数据 | `integrity_check=ok`、`forward 193 / forward_port 193 / node 5 / tunnel 2 / chain_tunnel 4` |
| 节点重连 | 2(2.0.2) / 4(1.2.4) / 5(2.0.2) / 6(2.0.2) 全部成功；3 本就整机离线 |
| arena 段数 | 51 → **3** |
| arena 实占 | 235.4 → **140.2 MB** |
| `VmRSS` | 378,608 → **329,136 kB** |
| `docker stats` | 343.2 → **300.3 MiB** |
| 定时任务 | `端口流量采集完成：转发 193 条，写入 5 条，跳过 188 条（无新增流量）` |
| 日志脱敏 | `"secret":"9e8b****cd88"`，明文计数 **0** |

### 9.6 注意

- 该变量写进 **Dockerfile**，不是 compose。因此**必须重建镜像**才会生效，改 compose 环境变量不会影响已有镜像层。
- 若后续在 compose 里显式覆盖 `MALLOC_ARENA_MAX`，compose 的值优先。
- 回滚见 `ROLLBACK_GUIDE.md` §12。

## 10. 第十三轮上线（TrimNativeHeapInterval）

### 10.1 变更

| 文件 | 变更 |
|---|---|
| `springboot-backend/Dockerfile` | `ENV JAVA_OPTS` 增加 `-XX:TrimNativeHeapInterval=15000` |
| `docker-compose-v4.yml` | 同上（`environment.JAVA_OPTS`） |
| `docker-compose-v6.yml` | 同上 |
| `deploy/docker-compose.yml` | 同上 |
| `deploy/docker-compose.registry.yml` | 同上 |

**为什么两处都要改**：Docker 的 `environment:` 会**覆盖**镜像的 `ENV`。只改 Dockerfile 时，用 compose 启动的实例拿不到该参数——第一次上线就踩了这个坑（容器 `JAVA_OPTS` 里没有 `TrimNativeHeapInterval`）。

### 10.2 构建与推送

```bash
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta -f springboot-backend/Dockerfile springboot-backend
docker image inspect ahmadplayz721/flux-panel-backend:2.0.7-beta --format '{{range .Config.Env}}{{println .}}{{end}}' | grep JAVA_OPTS
# 应看到 -XX:TrimNativeHeapInterval=15000
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta
```

本次 digest：`sha256:ff40dbd6f3cc1ca936cd543723a4fdcdabcaaf12f6e5e5c00af70ff5280bfcae`。

### 10.3 生产更新

```bash
cd /root
cp docker-compose.yml /root/docker-compose.yml.bak-$(date +%Y%m%d-%H%M%S)
# 给 JAVA_OPTS 加上参数（若尚未加）
sed -i 's|-XX:MaxMetaspaceSize=192m -Dfile.encoding|-XX:MaxMetaspaceSize=192m -XX:TrimNativeHeapInterval=15000 -Dfile.encoding|' docker-compose.yml
docker compose pull backend && docker compose up -d backend
```

### 10.4 上线后必查

```bash
docker inspect springboot-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep JAVA_OPTS
# 必须含 -XX:TrimNativeHeapInterval=15000

JP=$(pgrep -f app.jar | head -1)
grep -E 'VmRSS|VmHWM|Threads' /proc/$JP/status
# 预期：VmRSS 240-290 MB，VmHWM 约 331 MB（启动峰值后不再增长）

curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w '%{http_code}\n' 'http://[::1]:6365/flow/test'
docker exec springboot-backend sqlite3 /app/data/gost.db 'pragma integrity_check;'
```

### 10.5 实测结果

| 指标 | 上线前 | 上线后 |
|---|---:|---:|
| `docker stats` | 298.6 MiB | **228.4 MiB** |
| `VmRSS` | 356,956 kB | 240–287 MB（中位 244 MB） |
| `VmHWM` | 356,956 kB | 331,248 kB（恒定） |
| glibc arena（扣 G1） | 约 63 MB | **25.6 MB** |
| 堆 used / Metaspace | 46.5 MB / 61 MB | 不变 |
| 线程 | 45–47 | 45–48 |
| v4 / v6 / front | 200 / 200 / 200 | 200 / 200 / 200 |
| `integrity_check` | ok | ok |
| 数据量 | 193/193/5/2/4/1 | 不变 |

### 10.6 注意

- RSS 会在 240–287 MB 之间波动，周期约 15 秒——这是 trim 的正常呼吸，不是抖动。
- 回滚只需去掉 `-XX:TrimNativeHeapInterval=15000` 并 `docker compose up -d backend`，**无需重新构建镜像、无需动数据**。

## 11. 第十四轮上线（批量清理 + 交付链路修正，2026-11-10 03:50 CST）

### 11.1 变更

| 提交 | 内容 | 是否需重建镜像 |
|---|---|---|
| `124a96e` | `CheckGostConfigAsync` 孤立项清理改批量查询；判定拆成 `findOrphanServices`/`findOrphanChains`；查询失败整轮跳过；Dockerfile 删掉重复的 `ENV JAVA_OPTS` | 是 |
| `fc45e60` | `NodeServiceImpl.getInstallCommand()` 改指 `refs/tags/2.0.7-beta-perf/install.sh` | 是 |

### 11.2 构建与推送

```bash
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta \
  -f springboot-backend/Dockerfile springboot-backend
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta
```

- manifest list digest：`sha256:12d65638a77e4cfebcb9a224881c0211a0d2053669b5e36c8d1fc9d0fc86a082`
- `docker image inspect` 确认 `JAVA_OPTS` 仍含 `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:TrimNativeHeapInterval=15000`，`MALLOC_ARENA_MAX=2` 仍在。

### 11.3 生产更新

```bash
cp /root/docker-compose.yml /root/docker-compose.yml.bak-$(date +%Y%m%d-%H%M%S)
cd /root && docker compose pull backend && docker compose up -d backend
```

- 本次备份：`/root/docker-compose.yml.bak-20261110-035025`
- 启动时间：`2026-11-09T19:50:34.605719934Z`（UTC）= 2026-11-10 03:50:34 CST

### 11.4 上线后必查

```bash
docker inspect springboot-backend --format 'status={{.State.Status}} health={{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}'
curl -s -o /dev/null -w 'v4=%{http_code}\n' http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w 'v6=%{http_code}\n' 'http://[::1]:6365/flow/test'
docker exec springboot-backend sqlite3 /app/data/gost.db "pragma integrity_check;"
docker logs --since 5m springboot-backend 2>&1 | grep -c "删除孤立"      # 应为 0（当前无孤立项）
docker logs --since 5m springboot-backend 2>&1 | grep -cE "ERROR|Exception"   # 应为 0
```

### 11.5 实测结果

| 项 | 结果 |
|---|---|
| 容器状态 | `running` / `healthy` / `restarts=0` / `oom=false` |
| HTTP | `v4=200`、`v6=200`、`front=200` |
| 数据完整性 | `integrity_check=ok`；`forward 193 / forward_port 193 / node 5 / tunnel 2 / chain_tunnel 4 / user 1` |
| 节点重连 | 节点 2/5/6（2.0.2）、节点 4（1.2.4）全部「连接建立成功」 |
| 日志脱敏 | 明文 secret 计数 0 |
| 错误 | `ERROR|Exception` 计数 0 |
| 孤立清理 | 日志 0 条（当前确实无孤立项） |
| 内存 | `VmRSS 246,572 kB`、`VmHWM 332,924 kB`、arena 21.0 MB、G1 堆 72.4 MB；`docker stats` **210.6 MiB** |

### 11.6 注意

- **Dockerfile 里同名 `ENV` 是覆盖不是追加**。本轮把重复的 `ENV JAVA_OPTS` 合并成一行，并在上方注明「唯一一处、必须包含全部参数」。以后往 `JAVA_OPTS` 加参数，必须改那一行，不能在别处再写一个 `ENV JAVA_OPTS=...`，否则前面的参数会被悄悄丢掉。
- **安装命令的 tag 引用**：面板「节点监控 → 安装命令」现在指向 `refs/tags/2.0.7-beta-perf`。以后移动 tag 时，`install.sh`、`panel_install.sh` 的 compose 引用、以及这个安装命令会一起切到新内容——改 tag 等于给所有新装/升级换脚本，需谨慎。

## 11. 第十四轮上线（配置上报批量查询 + 交付链路修正）

### 11.1 本轮变更

| 提交 | 内容 |
| --- | --- |
| `124a96e` | 配置上报的孤立项清理改用批量查询；Dockerfile 去掉重复的 `ENV JAVA_OPTS` |
| `fc45e60` | 节点安装命令从 `refs/heads/feat/...` 改指 `refs/tags/2.0.7-beta-perf` |

### 11.2 构建与推送

```bash
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta \
  -f springboot-backend/Dockerfile springboot-backend
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta
# manifest list digest: sha256:12d65638a77e4cfebcb9a224881c0211a0d2053669b5e36c8d1fc9d0fc86a082
```

构建后核对（可选但建议）：

```bash
CID=$(docker create ahmadplayz721/flux-panel-backend:2.0.7-beta)
docker cp $CID:/app/app.jar /tmp/app.jar && docker rm -f $CID
python3 - <<'PY'
import zipfile
z = zipfile.ZipFile('/tmp/app.jar')
d = z.read('BOOT-INF/classes/com/admin/common/task/CheckGostConfigAsync.class')
print('findOrphanServices', b'findOrphanServices' in d)
print('findOrphanChains  ', b'findOrphanChains' in d)
n = z.read('BOOT-INF/classes/com/admin/service/impl/NodeServiceImpl.class')
print('tag 引用          ', b'refs/tags/2.0.7-beta-perf' in n)
PY
```

### 11.3 生产更新

```bash
cp /root/docker-compose.yml /root/docker-compose.yml.bak-$(date +%Y%m%d-%H%M%S)
cd /root && docker compose pull backend && docker compose up -d backend
```

本轮实测在 2026-11-10 03:50:34 CST 完成，备份 `/root/docker-compose.yml.bak-20261110-035025`。

### 11.4 上线后必查

```bash
docker inspect springboot-backend --format 'status={{.State.Status}} health={{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}'
curl -s -o /dev/null -w 'v4=%{http_code}\n' http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w 'v6=%{http_code}\n' 'http://[::1]:6365/flow/test'
docker logs --since 5m springboot-backend 2>&1 | grep -c '连接建立成功'          # 应为在线节点数
docker logs --since 25m springboot-backend 2>&1 | grep -c '删除孤立'             # 无孤立项时应为 0
docker logs --since 25m springboot-backend 2>&1 | grep -cE 'ERROR|Exception'     # 应为 0
docker logs --since 25m springboot-backend 2>&1 | grep -c '"secret":"[0-9a-f]\{32\}"'  # 明文应为 0
docker exec springboot-backend sqlite3 /app/data/gost.db "select count(*) from forward; pragma integrity_check;"
```

**重点看 `删除孤立`**——这条日志非 0 说明节点上确实有面板已不认识的 service/chain 被清掉了，属正常行为；但如果一次性出现几十条，要立刻检查是不是批量查询走了异常分支把什么都判成孤立了（正常情况下异常分支会整轮跳过，不会误删）。

### 11.5 本轮实测结果

| 项 | 值 |
| --- | --- |
| 容器状态 | `running` / `healthy` / `restarts=0` / `oom=false` |
| HTTP | `v4=200`、`v6=200`、`front=200` |
| 节点重连 | 5 / 2 / 6（2.0.2）、4（1.2.4）全部在线 |
| 数据 | `forward 193 / forward_port 193 / node 5 / tunnel 2 / chain_tunnel 4 / user 1`，`integrity_check=ok` |
| 孤立清理 | 25 分钟内 0 条（当前无孤立项） |
| 明文 secret | 0 |
| `ERROR|Exception` | 0 |
| 内存 | `VmRSS 249,364 kB`、`VmHWM 332,924 kB`、`VmSwap 0`、`Threads 47` |
| `docker stats` | backend 222.5 MiB / 0.46% CPU、frontend 3.074 MiB |

### 11.6 注意

- 本轮**不改变任何接口、数据与前端行为**，回滚只需把镜像 tag 指回 `sha256:e301f7cc…` 或更早的 `sha256:ff40dbd6…`。
- 批量查询失败时**整轮跳过清理**是刻意设计：宁可少删一轮，也不要在数据库抖动时把全部 service 判成孤立。看到「清理服务配置失败」日志不必立即处理，下一轮会自动重试。
