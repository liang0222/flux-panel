# 部署指南（DEPLOYMENT_GUIDE）— Debian 12

本文档说明**本次已执行的生产变更**、**尚未部署的代码改动**分别怎么处理。
所有步骤保持现有部署方式不变（同一套 Docker Compose、同一套 systemd 单元、同一套数据卷）。

**生产服务器**：`87.83.207.7`（Debian 12，内核 6.1.0-50-amd64，8 vCPU，7.75 GiB 内存）
**备份目录**：`/root/backup_20261030_120012`

---

## 0. 当前生产状态（重要）

截至本文档编写时，生产上已经做了 3 处变更，**还有一批代码改动没有上线**：

| 类别 | 内容 | 状态 |
|---|---|---|
| ✅ 已生效 | `JAVA_OPTS` 改为 `-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m` | 容器已重建 |
| ✅ 已生效 | `GOMEMLIMIT=1200MiB`（systemd drop-in） | agent 已重启 |
| ✅ 已生效 | pprof 监听 `127.0.0.1:6060` | agent 已重启 |
| ❌ 未上线 | `ForwardServiceImpl` 批量查询、`application.yml` 键名修正、`logback` 调整 | **需要重建镜像** |
| ❌ 未上线 | Go agent 的 `GOMEMLIMIT` 内置默认值、日志级别、`init()` 重构 | **需要重建二进制** |
| ❌ 未生效 | 容器日志轮转 `10m×3` | 已写入 compose，**需容器重建** |

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
# 1) 新代码的默认值应被环境变量的同名值覆盖（GOMEMLIMIT=1200MiB 优先生效）
systemctl show flux_agent -p Environment          # 应有 GOMEMLIMIT=1200MiB
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
# 期望: Environment=GOMEMLIMIT=1200MiB

P=$(systemctl show -p MainPID --value flux_agent)
tr '\0' '\n' < /proc/$P/environ | grep -i GOMEMLIMIT
# 期望: GOMEMLIMIT=1200MiB
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
| 把 `GOMEMLIMIT` 设到 200MiB 以下 | 会引发 GC 抖动，降低转发吞吐 |
| 把 pprof 改成 `0.0.0.0:6060` | 等于把性能剖析接口暴露到公网 |
| `git reset --hard` / `git clean -fd` | 会丢失未提交改动 |
| 在生产直接 `go test` | `x/service` 包的 `init()` 历史上会 `log.Fatal` 让测试进程退出 |
