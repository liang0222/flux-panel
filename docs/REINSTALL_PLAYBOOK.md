# 生产机重装系统：资产清单、备份与恢复手册

> 适用对象：Flux Panel 生产面板机 `87.83.207.7`（Debian 12，8 vCPU / 8 GB，单盘 100 GB xfs）
> 编写时间：2026-11-10 17:20 CST（重装前）
> 原则：**先备份、再验证、后重装**；任何一步没有可验证的输出，都视为未完成。

---

## 0. 一页速查：重装会丢什么、不会丢什么

| 类别 | 具体内容 | 重装后能否恢复 | 恢复来源 |
|---|---|---|---|
| **不可再生** | `/root/.env` 里的 `JWT_SECRET` | 能（若已备份） | 备份包 `etc/dot.env` |
| **不可再生** | `gost.db` 全库（5 节点 / 2 隧道 / 193 转发 / 8.8 万行流量统计 / 1 用户） | 能（若已备份） | 备份包 `db/gost.db` |
| **不可再生** | `/etc/flux_agent/config.json` 的 `secret`（与 `node.id=2` 的 secret 同一把） | 能（若已备份） | 备份包 `etc/config.json` |
| **不可再生** | `/opt/1panel` 的 1Panel 面板数据（用户、密码哈希、设置） | 能（若已备份） | 备份包 `etc/1panel-conf.tar.gz` |
| **可再生但费时** | 4 个容器镜像（backend / frontend / openresty / openspeedtest） | 能 | Docker Hub + 1Panel 应用商店 |
| **可再生但费时** | 自编译内核 `7.3.3-joeyblog-bbrv3-max` | 能 | byJoey/Actions-bbr-v3 一键脚本 |
| **可再生但费时** | sysctl 调优（tcpfit / bbr / tcp-tune 三套） | 能 | 备份包 `etc/99-*.conf` |
| **可再生** | `gost.json`（166 条 service 运行态配置） | 能 | 面板重新下发 |
| **可再生** | 监控脚本与历史 CSV | 能 | 备份包 `scripts/`、`logs/` |
| **机器外、重装不影响** | 云厂商安全组、弹性 IP、快照 | 不受影响 | 云控制台 |
| **机器外、重装不影响** | 其他 4 个节点的 agent（node 3/4/5/6） | 不受影响 | 各自机器 |

**一句话**：真正会丢的只有 4 样——`JWT_SECRET`、`gost.db`、`config.json` 的 secret、1Panel 面板数据。其余都能重建。

---

## 1. 重装前已完成的备份（2026-11-10 17:15 CST）

### 1.1 备份产物

```
服务器端：/root/flux_reinstall_backup_20261110-171528/         （114 MB，解包后）
          /root/flux_reinstall_backup_20261110-171528.tar.gz   （107,476,382 B）
本地副本：/root/flux_reinstall_backup/flux_reinstall_backup_20261110-171528.tar.gz
SHA-256 ：774eb2cc5ca8eedd91f1f97176f19035f928bc42bc6cceac2e8261e3093d4ca1
          （服务器端与本地副本一致，已核对）
```

### 1.2 备份内容（42 个条目）

```
etc/dot.env                    /root/.env，含 JWT_SECRET / FRONTEND_PORT / BACKEND_PORT
etc/config.json                /etc/flux_agent/config.json（addr + secret）
etc/device.id                  /etc/flux_agent/device.id（16 字节设备标识）
etc/gost.json                  166 条 service 的运行态配置（188,917 B，留档用）
etc/docker-daemon.json         {"ipv6": true, "fixed-cidr-v6": "fd00::/80"}
etc/sshd_config                当前 SSH 配置（PermitRootLogin yes / PasswordAuthentication yes / PubkeyAuthentication no）
etc/fstab                      UUID=ef94bc71-... / xfs defaults 0 0 + /swapfile
etc/99-flux.conf               journald 上限 200M / 32M 单文件 / 1 个月保留
etc/99-joeyblog.conf           bbr + fq + rmem/wmem 16MB
etc/99-tcpfit.conf             tcpfit v0.5.9 生成（5Mbps / 150ms / 7942MB）
etc/99-tcp-tune.conf           VPS值得买模板（keepalive 60 / rmem 32MB）
etc/sysctl.conf                基础 sysctl
etc/grub-default               GRUB_DEFAULT=0
etc/1panel-conf.tar.gz         1Panel 配置（排除 resource 大文件与日志）
systemd/flux_agent.service     单元本体（WorkingDirectory=/etc/flux_agent）
systemd/flux_agent.service.d/override.conf   Environment="GOMEMLIMIT=512MiB"
systemd/flux-watch.service     监控服务（Restart=always / Nice=10 / IOSched=idle）
scripts/flux_watch.sh          每分钟采样 RSS/HWM/FD/goroutine/estab/Java RSS/PSI
scripts/arena_stat.py          glibc arena vs G1 堆分类统计（排除 0xe0000000）
scripts/ckrace.py              SQLite checkpoint 争用复现
scripts/latbench.py            延迟压测
scripts/stress.py              并发压测
scripts/nettest.py             网络探测
scripts/trend_an.py            趋势分析
scripts/flux_trend15.sh        15 天趋势采集
scripts/swap.sh                swapfile 创建脚本
docker/docker-compose.yml      当前生效的 compose（1,788 B）
db/gost.db                     SQLite 一致性快照（11,968,512 B，.backup 命令生成）
db/integrity.txt               pragma integrity_check 输出
logs/flux_watch.csv            1,193,052 B 历史监控（11-10 17:14）
logs/arena_watch.csv           arena 30 分钟采样
logs/flux_trend15.csv / flux_trend.csv   趋势数据
env-snapshot.txt               系统快照（uname / 内核包 / sysctl 生效值 / THP / docker / 容器 / 卷 / 镜像 / 监听 / 服务）
```

### 1.3 备份已验证的部分（可复核）

| 验证项 | 命令 | 实测结果 |
|---|---|---|
| 备份包 SHA-256 一致 | `sha256sum` 服务器端 vs 本地 | 两边均为 `774eb2cc…3d4ca1` ✅ |
| 数据库完整性 | 容器内 `sqlite3 /d/gost.db 'pragma integrity_check;'` | `ok` ✅ |
| 数据库行数 | `select count(*)` | `node=5 forward=193 forward_port=193 tunnel=2 user=1 forward_flow_statistics=87938` ✅ |
| 备份库与在线库大小 | `stat -c %s` | 两边均 `11,968,512 B` ✅ |
| JWT_SECRET 已入包 | `grep -c JWT_SECRET etc/dot.env` | `1` ✅ |
| secret 与 node 表一致 | 比对 `config.json` 与 `node.id=2.secret` | `MATCH` ✅ |

**未验证**：备份包能否在**全新系统**上完整还原（无全新机器可测）。还原步骤见第 4 节，每步都带判断标准。

---

## 2. 服务器现状清单（重装前快照）

### 2.1 硬件与系统

```
Debian GNU/Linux 12 (bookworm)
Linux 7.3.3-joeyblog-bbrv3-max #1 SMP PREEMPT_DYNAMIC Sat Nov 7 11:01:43 UTC 2026 x86_64
主机名 ser449842350597
磁盘  /dev/vda 100G（单盘，xfs，已用 14G / 14%）
swap  /swapfile 4G（fstab 已挂，实测使用 0）
内存  7,942 MB，实测 used 约 850 MB / available 7,093 MB
CPU   8 vCPU（容器 nproc 也是 8）
```

已装内核两个：
- `linux-image-6.1.0-50-amd64`（6.1.176-1，Debian 官方）
- `linux-image-7.3.3-joeyblog-bbrv3-max`（7.3.3-gd50d994bffa3-1，第三方 BBRv3）

`GRUB_DEFAULT=0`，当前运行的是 7.3.3。**注意**：重装后若仍要 BBRv3，需重跑 `bash <(curl -fsSL https://raw.githubusercontent.com/byJoey/Actions-bbr-v3/main/install.sh)`（该脚本会改 GRUB 默认项）。

### 2.2 容器（4 个，全部 `unless-stopped` 或 `always`）

| 容器名 | 镜像 | 端口 | 重启策略 | 作用 |
|---|---|---|---|---|
| `springboot-backend` | `ahmadplayz721/flux-panel-backend:2.0.7-beta` | `6365` v4+v6 | unless-stopped | 面板后端（Java 21 / Spring Boot 2.7.18 / SQLite） |
| `vite-frontend` | `ahmadplayz721/flux-panel-frontend:2.0.7-beta` | `6366->80` | unless-stopped | 面板前端（nginx 静态托管，无 Node 常驻） |
| `1Panel-openresty-QDoA` | `1panel/openresty:1.31.8.1-2-1-noble` | `80/443` | always | 1Panel 的 Web 服务器（**当前无任何站点，conf.d 为空**） |
| `openspeedtest` | `openspeedtest/latest` | `3000/3001` | unless-stopped | 测速页（非 Flux Panel 组件，可自行决定是否保留） |

### 2.3 Docker 卷

```
sqlite_data     gost.db 11,968,512 B + -shm 32,768 B + -wal 725,152 B
backend_logs    158.3 MB（2026-11-03 ~ 2026-11-10，maxHistory=7 生效）
81a395a9...     匿名卷（1Panel 创建，内容未查）
```

### 2.4 网络

```
ens17  10.0.7.14/24，默认路由 10.0.7.1（onlink）
公网 IPv4  87.83.207.7（NAT 映射）
公网 IPv6  无全局地址（只有 docker0 fd00::1/80、br-* fd00:dead:beef::1/48 等 ULA）
```

**IPv6 现状**：面板的 v6 端口绑定（`[::]:6365`、`[::]:6366`）在容器层是通的，本机 `curl -g -6 [::1]:6365` 返回 200，但**没有公网 IPv6 地址**，外部无法通过 IPv6 访问。重装后若云厂商分配了 IPv6，`docker-compose-v6.yml` 才真正有用。

### 2.5 防火墙

```
ufw         未安装
iptables    INPUT policy ACCEPT，无规则
```

**等于没有主机防火墙，全靠云厂商安全组。** 重装后请务必在云控制台确认安全组只放行：22、6365、6366、（可选）3000/3001、16899（1Panel）。

### 2.6 SSH（重装后必须改）

```
Port 22
PermitRootLogin yes
PasswordAuthentication yes
PubkeyAuthentication no          ← 关键弱点
KbdInteractiveAuthentication no
UsePAM yes
```

当前 root 密码（**不入库**，由用户自行保管）曾被我误写进 public 仓库的 `docs/HANDOFF.md`（提交 `f096a24`，已从 HEAD 移除但历史仍在）。**重装是彻底止损的最好时机**：新系统装好后不要再沿用这个密码。

### 2.7 业务数据（`gost.db`）

```
node      5 条：
  2  美国cn2GIA        87.83.207.7      status=1  v2.0.2   ← 本机
  3  香港万兆大带宽      178.253.239.172  status=0  v2.0.2   ← 整机离线
  4  SGP-GAUP-43997-22  64.235.43.189    status=1  v1.2.4   ← 版本落后
  5  德国               5.253.247.87     status=1  v2.0.2   ← 假在线（relay 端口全不可达）
  6  美国一年            136.0.197.135   status=1  v2.0.2

tunnel    2 条：
  4  HK10G-SA          in_ip=178.253.239.172   type=2 tls
  7  美国CN2GIA-1年     in_ip=87.83.207.7       type=2 tls

chain_tunnel  4 条：
  7  tunnel=4 node=3 chain_type=1   （香港，入口）
  8  tunnel=4 node=4 chain_type=3   （新加坡，出口）
  13 tunnel=7 node=2 chain_type=1   （本机，入口）
  14 tunnel=7 node=6 chain_type=3   （美国一年，出口）

user      1 条：ziming / role_id=0 / exp_time=2727251700000（≈2056 年）/ flow=99999
forward   193 条：tunnel 4 → 110 条（全部 status=1），tunnel 7 → 83 条（全部 status=1）
forward_flow_statistics  87,938 行（31 天保留，每天 5,496–8,962 行）
vite_config  2 条：app_name=续费联系ziming / ip=87.83.207.7:6365
```

**关键业务风险**：`tunnel 4` 的 110 条转发，入口节点是 node 3（**香港机，整机离线**），出口是 node 4（新加坡，v1.2.4）。这 110 条转发在面板里显示 `status=1` 但实际不可能通——重装不改变这个事实，但**重装后第一件事别误以为是重装导致的**。

### 2.8 1Panel

```
版本   v2.4.2
路径   /opt/1panel（296 MB）
面板   http://87.83.207.7:16899/ziming   用户 ziming（密码需 `1pctl user-info` 查，已打码）
端口   16899（1panel-core）
站点   无（/opt/1panel/www/conf.d 为空目录）
证书   仅 1Panel 自签（CN=localhost，1Panel-CA 签发，有效期到 2125 年）——无 Let's Encrypt，无需保留
```

1Panel 目前**只用来装了 openresty，没有承载任何网站**。若你重装后不打算用 1Panel，这 296 MB 和 42 MB 常驻内存（core 42 MB + agent 95 MB）都可以省掉。

### 2.9 内存占用排行（重装前的实测，用于判断重装后是否退化）

```
235.8 MB  java（springboot-backend，-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:TrimNativeHeapInterval=15000）
114.4 MB  dockerd
 94.8 MB  1panel-agent
 71.4 MB  containerd
 55.2 MB  /etc/flux_agent/flux_agent（systemd 管理，GOMEMLIMIT=512MiB）
 44.0 MB  systemd-journald
 42.2 MB  1panel-core
 19.0 MB  openresty master
 16.0+13.7 MB  openresty worker ×2
```

**Flux Panel 自身只占约 291 MB**（java 236 + agent 55），其余 300+ MB 是 Docker 基础设施 + 1Panel。如果重装后内存反而更高，先查 1Panel 和 Docker。

### 2.10 自定义 systemd 单元

```
flux_agent.service        enabled   ExecStart=/etc/flux_agent/flux_agent，WorkingDirectory=/etc/flux_agent，Restart=on-failure
  └ override.conf         Environment="GOMEMLIMIT=512MiB"
flux-watch.service        enabled   ExecStart=/root/flux_watch.sh，Restart=always，RestartSec=10，Nice=10，IOSchedulingClass=idle
1panel-core.service       enabled
1panel-agent.service      enabled
docker / containerd       enabled
```

`flux-watch.service` 是我加的监控，非业务必需；重装后可以不恢复。

### 2.11 内核调优（三套 sysctl 互相覆盖，**重装时值得整理**）

加载顺序（`/etc/sysctl.d/` 按文件名字典序）：`99-joeyblog.conf` → `99-sysctl.conf` → `99-tcpfit.conf` → `99-tcp-tune.conf`

| 参数 | joeyblog | tcp-tune | tcpfit | **实际生效** |
|---|---|---|---|---|
| `tcp_keepalive_time` | — | 60 | 600 | **600**（tcpfit 后加载胜出） |
| `rmem_max` / `wmem_max` | 16 MB | 32 MB | 4 MB | **4 MB**（tcpfit 胜出） |
| `tcp_rmem` max | 16 MB | 32 MB | 4 MB | **4 MB** |
| `tcp_mem` | — | 786432/1048576/26777216 | 127072/254144/508288 | **127072/254144/508288** |
| `nf_conntrack_max` | — | 1000000 | — | **262144**（未被任何文件设置，用的是模块默认值） |
| `netdev_budget` | — | — | 600 | 600 |
| `tcp_congestion_control` | bbr | — | bbr | **bbr** |
| `default_qdisc` | fq | — | fq | **fq** |
| `tcp_slow_start_after_idle` | 0 | 0 | 0 | **0** |

**问题**：`99-tcp-tune.conf` 设的 `nf_conntrack_max=1000000` **没有生效**（实际 262144），因为 `nf_conntrack` 模块在 sysctl 加载时可能还没插入，写入失败但不报错。若确实需要 100 万 conntrack，要放到 `/etc/modprobe.d/` 或用 `sysctl --system` 二次执行。

**重装建议**：只保留**一套**。tcpfit 那套是按 5 Mbps / 150 ms 算的保守值，与这台机器（面板机，不是转发出口）匹配；如果这台机器还要承担大流量转发，用 tcp-tune 的 32 MB 缓冲更合适。**不要三套并存**——现在的实际生效值和你以为的完全不一样。

### 2.12 journald

```
/etc/systemd/journald.conf.d/99-flux.conf
  SystemMaxUse=200M
  SystemMaxFileSize=32M
  MaxRetentionSec=1month
  Compress=yes
  RateLimitIntervalSec=30s
  RateLimitBurst=2000
实际占用 196.1M（我 2026-11-09 23:44 从 379.3 MB 收紧到 200 MB）
```

### 2.13 我留下的临时文件（重装可一并清掉，无需备份）

```
/root/agent_new_mask / agent_new_mask2     各 32,092,312 B（换二进制时的暂存）
/root/1panel-v2.4.2-linux-amd64{,.tar.gz}  65.8 MB 安装包
/root/pprof_heap_t0/t1.txt、pprof_goroutine_t0/t1.txt、heap_t0/t1.prof、goro_t1.prof   性能诊断原始数据
/root/*.b64、ta.b64、ckrace.b64            base64 传输中转
/root/speedtest                            720 KB 测速工具
/root/docker-compose.yml.bak-*  ×12        我每次上线前的备份（建议只留最近 2 个）
```

---

## 3. 重装前的操作清单（按顺序，每步带判断标准）

### 步骤 1：把备份包拉到你自己电脑（**最重要**）

服务器重装会清空磁盘，备份放在 `/root` 等于没备份。

```bash
# 在你自己的电脑上执行
scp root@87.83.207.7:/root/flux_reinstall_backup_20261110-171528.tar.gz ./
sha256sum flux_reinstall_backup_20261110-171528.tar.gz
```

**判断标准**：输出的 SHA-256 必须是 `774eb2cc5ca8eedd91f1f97176f19035f928bc42bc6cceac2e8261e3093d4ca1`。

> 我已经在本地（`/root/flux_reinstall_backup/`）存了一份，SHA-256 已核对一致。但**你还需要自己再存一份**——我这份在另一台机器上，不是你的。

### 步骤 2：确认云厂商侧不受影响的东西

登录云控制台，逐项确认（这些在机器外，重装不影响，但要知道它们存在）：

- [ ] **弹性 IP / 公网 IP**：`87.83.207.7` 是绑定在这台机器上的还是独立 EIP？如果是随机器分配的，重装后可能变。
- [ ] **安全组 / 防火墙规则**：确认放行了 22、6365、6366、16899（1Panel），以及是否有 3000/3001。
- [ ] **快照策略**：有没有自动快照？有的话重装前手动打一个（比 tar 包更完整，含系统）。
- [ ] **DNS**：有没有域名解析到 `87.83.207.7`？

**判断标准**：这四项你都能说清楚现状。说不清就先别重装。

### 步骤 3：记录当前节点的 secret（用于重装后核对）

```bash
# 服务器上执行
cat /etc/flux_agent/config.json
# 输出形如 {"addr":"87.83.207.7:6365","secret":"<32位十六进制>"}
```

**判断标准**：拿到这 32 位。重装后新 agent 的 secret 若与它不同，面板里的 `node.id=2` 会显示离线——那时需要更新 node 表的 secret（或者干脆让面板重新生成安装命令）。

### 步骤 4：停服务（可选，但建议）

```bash
systemctl stop flux-watch.service
docker compose -f /root/docker-compose.yml down
```

**判断标准**：`docker ps` 只剩 1Panel 的容器（或空）。

> 不停也行——重装会直接抹盘。但 `docker compose down` 会让 SQLite 干净关闭（WAL 合并），备份包里的 `gost.db` 更干净。**不过备份已经做完了，这一步只影响"最后一次写入是否丢"，而备份是 17:15 的快照，17:15 之后的流量统计会丢（可接受）。**

### 步骤 5：重装

按云厂商的方式重装 Debian 12。

---

## 4. 重装后的恢复步骤（按顺序，每步带判断标准）

### 步骤 1：基础环境

```bash
apt update && apt upgrade -y
apt install -y curl wget vim jq unzip sqlite3 htop
```

**判断标准**：`sqlite3 --version` 有输出。

### 步骤 2：装 Docker

```bash
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker
```

然后恢复 IPv6 配置：

```bash
cat > /etc/docker/daemon.json <<'EOF'
{
  "ipv6": true,
  "fixed-cidr-v6": "fd00::/80"
}
EOF
systemctl restart docker
```

**判断标准**：`docker info --format '{{.ServerVersion}} {{.Driver}} {{.CgroupVersion}}'` 输出 `29.x overlayfs 2`（版本号可不同，但 Driver 必须是 overlayfs，CgroupVersion 必须是 2）。

### 步骤 3：恢复 Flux Panel 配置

```bash
tar xzf flux_reinstall_backup_20261110-171528.tar.gz
cd flux_reinstall_backup_20261110-171528

# JWT_SECRET（最关键）
cp etc/dot.env /root/.env
chmod 600 /root/.env

# compose
cp docker/docker-compose.yml /root/docker-compose.yml

# 验证 JWT_SECRET 长度合理（非空、>=32 字符）
awk -F= '/JWT_SECRET/{print "len=" length($2)}' /root/.env
```

**判断标准**：`len=` 后面是一个 >= 32 的数字。**如果 JWT_SECRET 变了，所有已登录用户的 token 全部失效**（需要重新登录，但数据不丢）。

### 步骤 4：恢复数据库

```bash
# 先起容器让它创建卷
cd /root && docker compose up -d
sleep 20

# 停后端，替换数据库
docker compose stop backend
docker cp /root/flux_reinstall_backup_20261110-171528/db/gost.db springboot-backend:/app/data/gost.db
docker compose start backend
sleep 25
```

**判断标准**：

```bash
docker exec springboot-backend sqlite3 /app/data/gost.db 'pragma integrity_check;'
# 必须输出 ok

docker exec springboot-backend sqlite3 /app/data/gost.db \
  'select (select count(*) from node), (select count(*) from forward), (select count(*) from tunnel), (select count(*) from user);'
# 必须输出 5|193|2|1
```

> **坑**：`docker cp` 覆盖时如果容器还在跑，SQLite 会把旧文件的 WAL 一起带过来，可能损坏。所以必须先 `stop backend`。另外 `.db-shm` 和 `.db-wal` 要一并删掉：
> ```bash
> docker exec springboot-backend sh -c 'rm -f /app/data/gost.db-shm /app/data/gost.db-wal'
> ```

### 步骤 5：验证面板可访问

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6365/flow/test
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:6366/
```

**判断标准**：两个都是 `200`。

浏览器打开 `http://87.83.207.7:6366`，用 `ziming` 登录（密码是数据库里的 MD5，见第 5 节）。

### 步骤 6：恢复节点 agent

**方式 A（推荐）**：面板里点「节点 → 添加/重装」，复制生成的命令，在**本机**执行：

```bash
curl -fsSL https://raw.githubusercontent.com/liang0222/flux-panel/refs/tags/2.0.7-beta-perf/install.sh | bash -s -- --addr 87.83.207.7:6365
```

> 注意 `install.sh` 的 tag 是 `2.0.7-beta-perf`，这个 tag 已固定指向发布版本（我 2026-11-10 改的，之前指向会漂移的分支）。

**方式 B（保持原 secret）**：把备份的 `config.json` 和 `device.id` 放回去，这样面板里的 `node.id=2` 不用改 secret：

```bash
mkdir -p /etc/flux_agent
cp /root/flux_reinstall_backup_20261110-171528/etc/config.json /etc/flux_agent/config.json
cp /root/flux_reinstall_backup_20261110-171528/etc/device.id /etc/flux_agent/device.id
# 然后仍要跑 install.sh 装二进制和 systemd 单元
```

**判断标准**：面板「节点」页面 `node.id=2`（美国cn2GIA）显示**在线**，版本 `2.0.2`。

```bash
# 服务器上核对
systemctl is-active flux_agent                    # active
systemctl show -p Environment flux_agent          # 含 GOMEMLIMIT=512MiB
ss -Htln | grep 6365                              # 有监听
```

### 步骤 7：恢复 sysctl（**只选一套**）

```bash
# 方案 1：面板机（保守，与现在实际生效值一致）
cp /root/flux_reinstall_backup_20261110-171528/etc/99-tcpfit.conf /etc/sysctl.d/

# 方案 2：转发机（大缓冲）
cp /root/flux_reinstall_backup_20261110-171528/etc/99-tcp-tune.conf /etc/sysctl.d/

# 无论选哪个，都要有 bbr + fq
cp /root/flux_reinstall_backup_20261110-171528/etc/99-joeyblog.conf /etc/sysctl.d/
sysctl --system
```

**判断标准**：

```bash
sysctl net.ipv4.tcp_congestion_control net.core.default_qdisc net.ipv4.tcp_keepalive_time
# tcp_congestion_control = bbr
# default_qdisc = fq
# tcp_keepalive_time = 600（方案1）或 60（方案2）
```

**不要再三套并存**——现在这台机器的实际生效值（rmem 4MB、keepalive 600）和 `99-tcp-tune.conf` 写的（32MB、60）完全不一样，容易误判。

### 步骤 8：恢复 journald 上限（建议）

```bash
mkdir -p /etc/systemd/journald.conf.d
cp /root/flux_reinstall_backup_20261110-171528/etc/99-flux.conf /etc/systemd/journald.conf.d/
systemctl restart systemd-journald
```

**判断标准**：`journalctl --disk-usage` 输出不超过 `200M`。

> 不设这个的话，journald 默认会用掉磁盘的 10%（这台机器约 10 GB）。我之前实测它涨到过 379 MB。

### 步骤 9：恢复 SSH 安全配置（**必做**）

```bash
# 先在你本地生成密钥（如果还没有）
ssh-keygen -t ed25519 -C "flux-panel-admin"
ssh-copy-id root@87.83.207.7

# 服务器上改配置
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#*PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config
sshd -t && systemctl reload ssh
```

**判断标准**：新开一个终端 `ssh root@87.83.207.7` **用密钥能进、用密码被拒**。**改之前务必先确认密钥能登录，否则会把自己锁在外面。**

### 步骤 10：恢复 BBRv3 内核（可选）

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/byJoey/Actions-bbr-v3/main/install.sh)
reboot
```

**判断标准**：`uname -r` 显示 `7.3.3-joeyblog-bbrv3-max`（或更新版本）。

> **代价**：换内核后 THP 设置会变（现在是 `madvise`），我之前的 Java/Go 内存基线全部不可比。如果不想重新测，就**别装 BBRv3**，用 Debian 自带内核 + bbr 也够。

### 步骤 11：恢复监控（可选）

```bash
cp /root/flux_reinstall_backup_20261110-171528/scripts/flux_watch.sh /root/
chmod +x /root/flux_watch.sh
cp /root/flux_reinstall_backup_20261110-171528/systemd/flux-watch.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now flux-watch.service
```

**判断标准**：`systemctl is-active flux-watch` = `active`，1 分钟后 `/root/flux_watch.csv` 有新行。

### 步骤 12：恢复 1Panel（**可选，建议不装**）

1Panel 现在没承载任何站点，只提供 openresty。如果不装：

- 省 296 MB 磁盘、约 137 MB 内存（core 42 + agent 95）
- 80/443 端口空出来（当前 openresty 只是返回 404）

如果要装：

```bash
bash -c "$(curl -sSL https://resource.fit2cloud.com/1panel/package/v2/quick_start.sh)"
```

**判断标准**：`1pctl user-info` 能输出面板地址。

---

## 5. 账号与凭据（重装后要用）

| 项目 | 值 | 备注 |
|---|---|---|
| 面板登录 | 用户 `ziming` | 密码是 `user` 表 `pwd` 字段的 MD5：`e4a64a73089091af3432a6bd5e15fab1`。这是 MD5，无法反解，只能：① 用备份的库（密码不变）；② 若忘了，改库或让面板重置 |
| 面板地址 | `http://87.83.207.7:6366` | 前端端口 |
| 后端 API | `http://87.83.207.7:6365` | 健康检查 `/flow/test` |
| JWT_SECRET | 见备份 `etc/dot.env` | 变了会导致所有已登录会话失效 |
| 节点 secret | 见备份 `etc/config.json` | 与 `node.id=2.secret` 相同（已实测 MATCH） |
| 1Panel | `http://87.83.207.7:16899/ziming`，用户 `ziming` | 密码用 `1pctl user-info` 查（已打码，未入库） |
| SSH root | 密码由用户自行保管（**不入库**） | **重装后必须改** |

---

## 6. 重装不解决的事（别误判）

| 问题 | 现状 | 重装能解决吗 |
|---|---|---|
| `tunnel 4` 的 110 条转发不通 | 入口 node 3（香港 `178.253.239.172`）整机离线 | ❌ 换机器/修 node 3 才行 |
| node 5（德国）假在线 | WebSocket 通但 relay 端口 1000/1001/1002/1010/1020 全不可达 | ❌ 要修那台机器 |
| node 4 版本 1.2.4 落后 | 其他节点都是 2.0.2 | ❌ 要单独升级 node 4 |
| `failTimeout` 不生效 | `chains_7` 只有 1 个节点，`hop.go:180` + `filter.go:28` 双重短路 | ❌ 要先给 `chains_7` 配第二个节点 |
| Java 内存 236 MB | 堆 used 46 MB + Metaspace 61 MB + arena 27 MB + 其他 100 MB | ⚠️ 重装能重置到初始值，但跑几天还会涨回来（不是泄漏，是 JVM 正常驻留） |
| 面板无主机防火墙 | iptables 空规则 | ✅ 重装时正好补上 |

---

## 7. 一句话总结

**必须做**：步骤 1（下载备份包并核对 SHA-256）、步骤 2（确认云厂商侧）、步骤 9（SSH 安全）。

**做完这三步，重装就没有不可逆损失。**

其余步骤都是"恢复到原来那样"，按需执行。备份包里的东西足够把面板恢复到 2026-11-10 17:15 的状态。
