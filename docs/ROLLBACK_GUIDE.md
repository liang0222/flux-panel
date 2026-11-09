# 回滚指南（ROLLBACK_GUIDE）

本次所有变更都遵循「小范围、可定位、可独立回滚」。
本文档按**变更位置**分类给出回滚步骤，每一节都可以单独执行，互不依赖。

**备份目录**：`/root/backup_20261030_120012`（路径记录在 `/root/.last_backup_dir`）

```bash
cat /root/.last_backup_dir
ls -la $(cat /root/.last_backup_dir)
```

---

## 0. 快速决策表

| 出了什么问题 | 回滚哪一节 | 需要重启什么 | 中断时长 |
|---|---|---|---|
| 面板打不开 / 报错 | §1 Java 参数 | 后端容器 | ~7 秒 |
| 转发全部不通 | §2 agent 配置 | agent | ~2 秒 |
| 转发不通且新二进制有问题 | §3 agent 二进制 | agent | ~2 秒 |
| 内存反而变高 | §1 + §2 | 两者 | — |
| pprof 端口暴露风险 | §4 单独关 pprof | agent | ~2 秒 |
| 代码改动有问题（未上线） | §5 git 回滚 | 无需（未部署） | 0 |
| 全部恢复原状 | §6 一键回滚 | 两者 | ~10 秒 |

---

## 1. 回滚 Java 启动参数

### 1.1 恢复文件

```bash
B=$(cat /root/.last_backup_dir)
cp $B/docker-compose.yml.orig /root/docker-compose.yml
grep -n "JAVA_OPTS" /root/docker-compose.yml
# 期望看到: JAVA_OPTS: "-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
```

### 1.2 重建容器

```bash
cd /root
docker compose up -d backend
docker logs -f springboot-backend     # 等 "Started AdminApplication"
```

### 1.3 验证

```bash
JP=$(docker inspect -f '{{.State.Pid}}' springboot-backend)
tr '\0' '\n' < /proc/$JP/cmdline | grep -E "^-X"
# 期望: -Xms256m / -Xmx512m

curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:6365/flow/test   # 200
```

### 1.4 只回滚 Metaspace 上限（保留 -Xms64m）

如果你只想去掉 `MaxMetaspaceSize` 护栏：

```bash
sed -i 's/ -XX:MaxMetaspaceSize=192m//' /root/docker-compose.yml
grep -n "JAVA_OPTS" /root/docker-compose.yml
cd /root && docker compose up -d backend
```

---

## 2. 回滚 agent 的 GOMEMLIMIT

### 2.1 删除 drop-in

```bash
rm -f /etc/systemd/system/flux_agent.service.d/override.conf
rmdir /etc/systemd/system/flux_agent.service.d 2>/dev/null
systemctl daemon-reload
```

### 2.2 重启并验证

```bash
systemctl restart flux_agent
sleep 3
systemctl status flux_agent --no-pager | head -8
systemctl show flux_agent -p Environment
# 期望: Environment= （空）
```

> **注意**：删除 `GOMEMLIMIT` 后，agent 会重新失去堆上限护栏。
> 历史数据显示它曾爬到 2.5 GiB。若非必要，建议保留这一项。

### 2.3 只调整数值（不删除）

```bash
systemctl edit flux_agent     # 交互式编辑 drop-in
# 改成 Environment="GOMEMLIMIT=1200MiB" 之类
systemctl daemon-reload && systemctl restart flux_agent
```

**当前生产值：`512MiB`**（2026-10-31 14:42 由 1200MiB 收紧）。

若要恢复到收紧前的状态：

```bash
cp /root/backup_20261031_memlimit/override.conf.orig \
   /etc/systemd/system/flux_agent.service.d/override.conf
systemctl daemon-reload && systemctl restart flux_agent
systemctl show flux_agent -p Environment    # 期望: GOMEMLIMIT=1200MiB
```

> **实测参考**：512MiB 相对 1200MiB，**VmHWM 310,224 → 286,712 kB（−23.5 MB，唯一稳定收益）**；
> VmRSS 214~280 MB 振荡、AnonHugePages 108~258 MB 振荡（THP 相位驱动）；
> NumGC 33 → 38 次/小时；报错 0 条。**稳态 RSS 无可归因下降**，是否保留由你判断，回滚成本极低。

### 2.4 停止趋势采样器（若不需要继续观测）

```bash
systemctl disable --now flux-watch      # 1 分钟采样，输出 /root/flux_watch.csv
systemctl disable --now flux-trend15    # 5 分钟采样，输出 /root/flux_trend15.csv
```

两个采样器均为只读采集（`/proc`、`ss`、`curl` 回环 pprof），不修改任何业务状态。
`flux-trend15` 已于 2026-10-31 07:01 完成 15 小时任务，可安全停用。

---

## 3. 回滚 agent 二进制（若已部署新版本）

```bash
B=$(cat /root/.last_backup_dir)

systemctl stop flux_agent
cp $B/flux_agent.bin /etc/flux_agent/flux_agent
chmod +x /etc/flux_agent/flux_agent
systemctl start flux_agent

sleep 3
systemctl status flux_agent --no-pager | head -8
ss -ltn | wc -l        # 应恢复为 106 个 TCP 监听（+pprof 等）
journalctl -u flux_agent -n 30 --no-pager
```

> 如果备份里没有 `flux_agent.bin`，说明备份时没有执行二进制备份步骤。
> 此时可从旧镜像/旧发布包恢复，或用 `git stash` 回到未修改的 `go-gost` 重新编译。

---

## 4. 单独关闭 pprof（安全优先）

**如果发现 6060 端口监听在 `0.0.0.0` 或公网可达，立即执行：**

```bash
# 方式 A：精确删除 profiling 字段（保留原格式，推荐）
python3 - <<'PY'
import re
p = "/etc/flux_agent/gost.json"
s = open(p, encoding="utf-8").read()
new = re.sub(r'"profiling"\s*:\s*\{[^{}]*\}\s*,?', '', s, count=1)
if new == s:
    print("未找到 profiling 字段，无需修改")
else:
    open(p + ".tmp", "w", encoding="utf-8").write(new)
    import json
    json.load(open(p + ".tmp", encoding="utf-8"))   # 校验 JSON 合法
    import os
    os.replace(p + ".tmp", p)
    print("已移除 profiling")
PY

# 方式 B：直接从备份恢复整个文件
# B=$(cat /root/.last_backup_dir); cp $B/gost.json.orig /etc/flux_agent/gost.json

systemctl restart flux_agent
ss -ltnp | grep 6060        # 应无输出
```

### 防火墙兜底（不改配置也能挡）

```bash
# 如果系统有 ufw
ufw deny 6060/tcp
# 或 iptables（仅回环放行）
iptables -I INPUT -p tcp --dport 6060 ! -i lo -j DROP
```

---

## 5. 回滚仓库代码改动（未上线的部分）

### 5.1 查看改了什么

```bash
cd /root/v2.0.7-beta
git status --short
git diff --stat
```

### 5.2 逐文件回滚

```bash
# 只回滚某一个文件
git checkout 3907d1f -- springboot-backend/src/main/java/com/admin/service/impl/ForwardServiceImpl.java

# 回滚配置类改动
git checkout 3907d1f -- \
  springboot-backend/src/main/resources/application.yml \
  springboot-backend/src/main/resources/logback-spring.xml

# 回滚 Go 改动
git checkout 3907d1f -- \
  go-gost/main.go \
  go-gost/x/service/service.go \
  go-gost/x/service/traffic_reporter.go \
  go-gost/x/service/global_traffic_manager.go \
  go-gost/x/config/loader/loader.go

# 回滚 compose
git checkout 3907d1f -- \
  docker-compose-v4.yml docker-compose-v6.yml \
  deploy/docker-compose.yml deploy/docker-compose.registry.yml
```

### 5.3 删除新增文件

```bash
rm -f go-gost/main_memlimit_test.go
rm -f springboot-backend/src/test/java/com/admin/service/impl/ForwardServiceImplGetAllForwardsTest.java
rm -f deploy/journald-flux.conf
```

### 5.4 全部回滚到基线

```bash
cd /root/v2.0.7-beta
git checkout 3907d1f -- .          # 恢复所有已跟踪文件
git status --short                 # 只剩未跟踪的新文件
git clean -nd                      # 先干跑，看清楚要删什么
# 确认无误后再删（⚠️ 会删除所有未跟踪文件，包括你的其他新增文件）
# git clean -fd
```

> ⚠️ **`git clean -fd` 会删除所有未跟踪文件**，包括 `docs/` 里刚写的报告。
> 建议先把 `docs/` 复制出去，或用 `git clean -fd -- <指定路径>` 限定范围。

### 5.5 分支本身

```bash
git branch -a                      # 查看当前分支
git checkout feat/port-traffic-ranking
git branch -D perf/memory-optimization    # ⚠️ 会丢弃该分支上所有未合并提交
```

---

## 6. 一键全量回滚（回到本次优化前的生产状态）

```bash
set -e
B=$(cat /root/.last_backup_dir)
echo "使用备份: $B"

# 1) Java 参数
cp $B/docker-compose.yml.orig /root/docker-compose.yml
cd /root && docker compose up -d backend

# 2) agent GOMEMLIMIT
rm -f /etc/systemd/system/flux_agent.service.d/override.conf
rmdir /etc/systemd/system/flux_agent.service.d 2>/dev/null
systemctl daemon-reload

# 3) agent pprof 配置
cp $B/gost.json.orig /etc/flux_agent/gost.json

# 4) 重启 agent
systemctl restart flux_agent

# 5) 验证
sleep 8
echo "--- 后端 ---"
docker ps --filter name=springboot-backend --format "{{.Status}}"
curl -s -o /dev/null -w "health=%{http_code}\n" http://127.0.0.1:6365/flow/test
echo "--- agent ---"
systemctl is-active flux_agent
ss -ltn | wc -l
ss -ltnp | grep -c 6060 || echo "pprof 已关闭"
echo "--- 内存 ---"
free -m
```

**预期结果**：服务全部正常，agent 内存回到 ~100 MB（因为重启了），
但会随时间重新爬升到 2.5 GiB 量级（这是优化前的既有行为）。

---

## 7. 回滚后的必查项

无论回滚哪一部分，都要确认这四项：

```bash
# 1) 后端健康
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:6365/flow/test        # 200

# 2) 前端可访问（含 IPv6）
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:6366/                  # 200
curl -s -o /dev/null -w "%{http_code}\n" "http://[::1]:6366/"                    # 200

# 3) agent 在线、监听数正常
systemctl is-active flux_agent
ss -ltn | tail -n +2 | wc -l      # 应在 106 以上

# 4) 登录接口可用
curl -s -X POST http://127.0.0.1:6365/api/v1/user/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"x","password":"y"}'
# 期望: {"code":-1,"msg":"账号或密码错误",...}  ← 说明认证链路正常
```

---

## 8. 紧急情况

### 8.1 面板完全不可用，需要立刻恢复服务

```bash
# 用备份镜像直接重建（比重新构建快得多）
docker rm -f springboot-backend
docker run -d --name springboot-backend \
  --restart unless-stopped \
  -p 6365:6365 \
  -e DB_PATH=/app/data/gost.db \
  -e JWT_SECRET="$(grep -oP 'JWT_SECRET: \K.*' /root/docker-compose.yml | head -1)" \
  -e LOG_DIR=/app/logs \
  -v sqlite_data:/app/data \
  -v backend_logs:/app/logs \
  ahmadplayz721/flux-panel-backend:2.0.7-beta-preopt
```

### 8.2 agent 反复重启

```bash
journalctl -u flux_agent -n 100 --no-pager
# 常见原因：
#   - gost.json JSON 语法错误 → 用 python3 -m json.tool 校验
#   - GOMEMLIMIT 值非法 → 移除 drop-in
#   - 端口被占用 → ss -ltnp | grep <端口>
```

### 8.3 数据库文件损坏

```bash
docker compose -f /root/docker-compose.yml stop backend
ls -lh /var/lib/docker/volumes/sqlite_data/_data/
# 从备份恢复（见 DEPLOYMENT_GUIDE §3.2）
docker compose -f /root/docker-compose.yml start backend
```

> 本次**没有做任何数据库结构变更**，所以正常情况下不需要数据库回滚。

---

## 9. 回滚记录模板

执行回滚后建议记录：

```
时间：
回滚范围：（哪几节）
触发原因：
回滚前状态：（RSS / 健康检查 / 错误日志）
回滚后状态：
遗留问题：
```


---

## 10. 回滚第五轮：SQLite checkpoint 与 JDBC URL（2026-11-01）

本轮只改了后端镜像，**没有动数据库结构、没有动数据文件**，因此回滚是纯配置回退，
不需要任何数据恢复。

### 10.1 最快回滚：换回上一个镜像

生产上「上一个镜像」是 `sha256:0a4d617a30bede60af56339bc916037d1ec9c5e8c38583d2deebf2c2b68183c0`。

```bash
cd /root
# 1) 先备份当前 compose（每次改动都备份，文件名带时间戳）
cp docker-compose.yml docker-compose.yml.bak-rollback-$(date +%Y%m%d-%H%M%S)

# 2) 把 backend 的 image 改成旧 digest
#    注意：compose 里 image 写的是 tag，用 digest 需要临时改成本地已有的旧 tag，
#    或者直接 docker run 旧镜像。最稳妥的方式是：
docker images --digests | grep flux-panel-backend
#    找到旧 digest 对应的 image ID，然后：
#    方式 A：docker tag <旧ImageID> ahmadplayz721/flux-panel-backend:rollback-tmp
#            sed -i 's#ahmadplayz721/flux-panel-backend:2.0.7-beta#ahmadplayz721/flux-panel-backend:rollback-tmp#' docker-compose.yml
#    方式 B（推荐，若本地还留有旧镜像层）：直接改回旧 tag 并 docker compose up -d backend

docker compose up -d backend
docker ps --filter name=springboot-backend --format '{{.Status}}'   # 应为 Up (healthy)
```

> 若旧镜像已被 `docker image prune` 清掉，则无法换回旧镜像，
> 只能走 10.2 的「代码级回退 + 重新构建」。

### 10.2 代码级回退（只回退 checkpoint 模式，保留其余改动）

```bash
cd /root/v2.0.7-beta
git revert --no-edit a4d2f85      # 只回退这一个提交
# 或者手工改回：
#   SQLiteConfig.java  performCheckpoint() 里 PASSIVE → TRUNCATE
#   application.yml    url 里去掉 journal_mode/synchronous/cache_size/temp_store，
#                      只保留 ?busy_timeout=5000
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta ./springboot-backend
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta
cd /root && docker compose pull backend && docker compose up -d backend
```

### 10.3 单独回退 PRAGMA（不回退 checkpoint 模式）

如果只是想恢复「驱动默认提交语义」，把 URL 改回 `?busy_timeout=5000` 即可，
`PASSIVE` checkpoint 可以保留 —— 两者互相独立，**推荐保留 PASSIVE**。

### 10.4 绝不能做的事

| ❌ 不要 | 原因 |
|---|---|
| 往 URL 里加 `wal_autocheckpoint` 或其他未验证参数 | 会让 sqlite-jdbc 静默打开空库，面板数据全部「消失」（原库仍在，但应用读不到） |
| 回滚时删掉 `/app/data/gost.db` 或 `sqlite_data` 卷 | 那是唯一的数据源，删了无法恢复 |
| 用 `docker compose down -v` | `-v` 会连 `sqlite_data` 卷一起删掉 |
| 把 `PASSIVE` 也改回 `TRUNCATE` 后不再观察 | TRUNCATE 会重新引入上报超时（实测 10022ms 持锁） |

### 10.5 回滚后的必查项

```bash
docker exec springboot-backend sqlite3 /app/data/gost.db \
  "SELECT COUNT(*) FROM forward; SELECT COUNT(*) FROM node; SELECT COUNT(*) FROM user;"
#   应为 109 / 4 / 1

docker exec springboot-backend ls -l /app/data/
#   不得出现文件名带 ? 的怪文件

journalctl -u flux_agent --since "-10 minutes" | grep -c "❌ 全局流量上报失败"
#   回滚后若该数字开始上涨，说明 TRUNCATE 争用问题回来了
```

### 10.6 数据库备份（回滚前建议先做一次）

```bash
docker exec springboot-backend sqlite3 /app/data/gost.db '.backup /tmp/pre-rollback.db'
docker cp springboot-backend:/tmp/pre-rollback.db /root/pre-rollback-$(date +%Y%m%d-%H%M%S).db
ls -l /root/pre-rollback-*.db
```

> 注意必须用 `.backup`，**不能**直接 `docker cp` 复制 `gost.db`：
> 该库处于 WAL 模式，未合并的 WAL 里有最新数据，直接拷主库文件会丢掉这部分。

---

## 11. 回滚第六轮：Transport 修复与前后端两批缺陷修复（2026-11-09）

### 11.1 涉及对象

| 对象 | 当前值 | 回滚到 |
|---|---|---|
| agent 二进制 | `sha256:91f3c258…`（32,092,312 B，未加壳，`go1.23.12`） | `/root/agent_backup_20261109-230427/flux_agent.bak`（8,327,088 B，UPX，`go1.23.4`） |
| 后端镜像 | `sha256:b0ee363d…` | `sha256:580f0784…`（本轮第一个上线版本）→ `sha256:c42978b6…`（第五轮） |
| 前端镜像 | `sha256:02d1ee31…` | 本轮之前的前端镜像 |
| journald | `/etc/systemd/journald.conf.d/99-flux.conf` 已存在 | 删除该文件 |
| 代码 | `859225a` | `939fc55`（本轮起点） |

### 11.2 回滚 agent（风险最高，先做备份校验）

```bash
# 0) 确认备份存在且可执行
ls -l /root/agent_backup_20261109-230427/flux_agent.bak
/root/agent_backup_20261109-230427/flux_agent.bak -V   # 应输出 gost 3.1.0 (go1.23.4 linux/amd64)

# 1) 回滚
systemctl stop flux_agent
cp /root/agent_backup_20261109-230427/flux_agent.bak /etc/flux_agent/flux_agent
chmod +x /etc/flux_agent/flux_agent
systemctl start flux_agent

# 2) 验证
systemctl show flux_agent -p ActiveState -p NRestarts
ss -ltn | wc -l          # 应与 gost.json 的 service 数一致
journalctl -u flux_agent --since "2 min ago" | grep -c '"level":"error"'
```

> **回滚代价**：旧二进制不响应 ctx，**删除端口后连接会继续转发**（本次修复的正是这个）。回滚等于接受该缺陷。

### 11.3 回滚后端 / 前端

```bash
cd /root
cp docker-compose.yml.bak-20261110-002444 docker-compose.yml   # 或 .bak-20261109-233154
docker compose pull backend frontend
docker compose up -d backend frontend
```

如果 `docker-compose.yml` 里用的是固定 tag（`2.0.7-beta`），`pull` 会拉到**当前 latest**，而不是旧版。要真正回退必须显式指定 digest：

```bash
docker pull ahmadplayz721/flux-panel-backend@sha256:580f0784f25f1a1c9f3e2a0b469ee2b64011e0c5db01a8b2db82a559774d4d0c
docker tag  ahmadplayz721/flux-panel-backend@sha256:580f0784… ahmadplayz721/flux-panel-backend:2.0.7-beta
docker compose up -d --force-recreate backend
```

### 11.4 回滚 journald

```bash
rm /etc/systemd/journald.conf.d/99-flux.conf
systemctl restart systemd-journald
journalctl --disk-usage     # 回到默认上限（约 4 GB 的 10%，取决于磁盘）
```

### 11.5 回滚代码

```bash
cd /root/v2.0.7-beta
git revert --no-edit 859225a d7e63e8 d843617 7473a73 cc61535 e34660d 556b96d
```

六个提交相互独立，可单独回滚：

| 提交 | 内容 | 单独回滚的影响 |
|---|---|---|
| `556b96d` | Transport ctx 修复 | 删除端口后连接继续转发（缺陷回归） |
| `e34660d` | 测试竞态修正 | 无运行时影响 |
| `cc61535` | failTimeout 30s + 日志脱敏 + NPE 修复 | `failTimeout` 回到 600s；握手日志重新打印 secret |
| `7473a73` | 隧道静默失败 + 回滚不全 + LRU + 死代码 | 隧道创建失败重新变静默；多入口转发回滚重新变部分 |
| `d843617` | 请求日志脱敏 | `uploadFlowData` 重新打印 secret 明文 |
| `d7e63e8` | 前端 24 个 lint error | 条件 Hook 的崩溃风险回归 |

### 11.6 回滚后必须复查

| 项 | 命令 | 期望 |
|---|---|---|
| 数据完好 | `sqlite3 gost.db "pragma integrity_check; select count(*) from forward;"` | `ok` + 193 |
| IPv4 / IPv6 | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:6365/flow/test`、`curl -g -s -o /dev/null -w '%{http_code}' 'http://[::1]:6365/flow/test'` | `200` / `200` |
| 节点在线 | 后端日志 `连接建立成功，状态更新为在线` | 4 个节点 |
| 前端可访问 | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:6366/` | `200` |

### 11.7 禁止事项

- **不要** `git reset --hard` / `git clean -fd`（会丢工作区）。
- **不要**用 `docker compose pull` 期望拉到旧版本（tag 指向 latest）。
- **不要**在 agent 回滚后保留新 `gost.json`（格式兼容，但行为差异会让人误判）。
- **不要**删除 `/root/agent_backup_20261109-230427/`，这是唯一的旧二进制来源。

## 12. 回滚第十轮：glibc arena 限制

### 12.1 回滚对象与目标值

| 对象 | 当前值 | 回滚目标 |
|---|---|---|
| `springboot-backend/Dockerfile` | `ENV MALLOC_ARENA_MAX=2` | 删除该行 |
| 生产后端镜像 | `sha256:b2224ff1…` | `sha256:5e83ee24…`（Hikari + get_port 守卫版） |

**这次改动不涉及数据库、接口、compose、环境变量、卷、网络、端口，回滚不会动任何业务数据。**

### 12.2 回滚方式 A：代码回滚后重建（推荐）

```bash
cd /root/v2.0.7-beta
git revert --no-edit 264a035          # 只回退 Dockerfile 那一行
docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta \
  -f springboot-backend/Dockerfile springboot-backend
docker image inspect ahmadplayz721/flux-panel-backend:2.0.7-beta \
  --format '{{range .Config.Env}}{{println .}}{{end}}' | grep MALLOC_ARENA_MAX
# 应无输出
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta

# 生产
cd /root && cp docker-compose.yml /root/docker-compose.yml.bak-$(date +%Y%m%d-%H%M%S)
docker compose pull backend && docker compose up -d backend
```

### 12.3 回滚方式 B：直接指回旧 digest（最快，不改代码）

**注意 `docker compose pull` 会拉到 `latest`/tag 当前指向的镜像，不是旧版本。真正回退必须显式指定 digest。**

```bash
# 1) 拉旧 digest
docker pull ahmadplayz721/flux-panel-backend@sha256:5e83ee2452e2d85e570f2a1e45913e944112c81254e0093cf0b59a8a9c83d15a

# 2) 打回 tag
docker tag ahmadplayz721/flux-panel-backend@sha256:5e83ee24... \
           ahmadplayz721/flux-panel-backend:2.0.7-beta

# 3) 强制重建（必须 --force-recreate，否则 compose 认为镜像没变）
cd /root
docker compose up -d --force-recreate backend
```

### 12.4 回滚后必查

```bash
docker inspect springboot-backend --format 'health={{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}'
curl -s -o /dev/null -w "v4=%{http_code}\n" http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w "v6=%{http_code}\n" -6 "http://[::1]:6365/flow/test"
curl -s -o /dev/null -w "front=%{http_code}\n" http://127.0.0.1:6366/
docker exec springboot-backend sh -c "sqlite3 /app/data/gost.db 'pragma integrity_check; select count(*) from forward;'"
docker logs --since 5m springboot-backend 2>&1 | grep "连接建立成功"
```

预期：`healthy`、`restarts=0`、三个 200、`ok`、`193`、节点 2/4/5/6 重连。

回滚后内存会回到 **arena 51 段 / 235 MB / RSS 373–378 MB / docker stats 343 MiB**，属预期。

### 12.5 代价说明

- 回滚后 Java RSS 会**回升约 50 MB**（空闲态）到 **约 120 MB**（压力态）。
- 不会影响功能、性能与稳定性；arena 碎片只是内存占用问题。
- 若回滚是因为"怀疑 arena 限制导致变慢"，请先按 `PERFORMANCE_COMPARISON.md` 第十轮的方法复测——历史上三次压测方向不一致，单次变慢很可能是噪声或 JIT 冷启动。

### 12.6 禁止事项

- **不要**用 `docker compose down` 再 `up`（会中断全部节点连接，且不解决版本问题）。
- **不要**在没有备份 `docker-compose.yml` 的情况下修改镜像 tag。
- **不要**通过 `mem_limit` 压低容器内存来"解决" arena 问题——那只会把问题变成 OOMKilled。
