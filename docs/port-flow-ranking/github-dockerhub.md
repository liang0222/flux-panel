# 小白版：把项目放到自己的 GitHub + Docker Hub 并部署

> 假设你从没做过这件事。按顺序照做即可，每条命令都说明「做什么、成功长什么样」。
> 中途卡住：把**完整报错**复制给我，或对照最后一节「常见报错」。

## 你的实际参数（2026-10-24 已确认并执行）

| 项 | 值 |
| --- | --- |
| GitHub 用户名 | `liang0222` |
| 代码仓库 | https://github.com/liang0222/flux-panel |
| 已推送分支 | `feat/port-traffic-ranking`（以分支头为准） |
| Docker Hub 用户名 | `ahmadplayz721` |
| 后端镜像 | `docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta` |
| 前端镜像 | `docker.io/ahmadplayz721/flux-panel-frontend:2.0.7-beta` |
| 镜像状态 | **已推送成功**（2026-10-24），服务器可直接 pull |

> 当前进度：第 3~9 步（提交、建仓库、推代码、构建并推送镜像）都已完成。服务器部署从**第 10 步**开始即可。

---

## 0. 先搞懂 4 个东西（30 秒）

| 名词 | 是什么 | 类比 |
| --- | --- | --- |
| GitHub | 存**代码**的网站 | 带版本记录的网盘 |
| Docker | 打包和运行程序的工具 | 把程序和依赖装进一个"箱子" |
| Docker Hub | 存**镜像**（箱子）的网站 | 应用商店 |
| 服务器（VPS） | 真正跑面板的机器 | 你租的房子 |

整体流程：

```
本机代码 ──(git push)──▶ GitHub（备份/分发）
   │
   └─(docker build/push)─▶ Docker Hub（你的镜像）
                                │
服务器 ──(docker compose pull/up)─┘  拉下来运行
```

---

## 1. 准备

### 1.1 你需要有

- GitHub 账号（https://github.com 注册）
- Docker Hub 账号（https://hub.docker.com 注册，**用户名只能小写**）
- 一台装了 Docker 的电脑（用来构建镜像），或直接在服务器上构建
- 一台服务器（VPS），能 SSH 登录

### 1.2 安装工具

**Windows**

1. 安装 Git for Windows：https://git-scm.com/download/win （一路下一步，会自带 Git Bash）
2. 安装 Docker Desktop：https://www.docker.com/products/docker-desktop/ （装完打开，等左下角变绿）
3. 以后所有命令都在 **Git Bash** 里执行（开始菜单搜 Git Bash）

**macOS**

```bash
brew install git
# Docker Desktop 从官网下载安装，打开并等待启动完成
```

**Linux（含服务器）**

```bash
sudo apt update && sudo apt install -y git
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

### 1.3 检查装好了没

```bash
git --version          # 显示 git version 2.x
docker --version       # 显示 Docker version 2x.x
docker compose version # 显示 Docker Compose version v2.x
```

> 三条都有输出才算成功。`docker compose` 中间是空格，不是 `docker-compose`。

---

## 2. 配置 Git 身份（只做一次）

```bash
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
```

这两行只是给提交记录署名，随便填，但建议和 GitHub 一致。

---

## 3. 拿到代码并提交

### 3.1 代码现在在哪？

- **如果你就在这台开发机上**：代码在 `/root/v2.0.7-beta`，直接进去。
- **如果代码在另一台机器**：整包拷过来（在你自己的电脑上执行）：

```bash
# 把整个项目拷到自己电脑（排除依赖和构建产物）
scp -r root@服务器IP:/root/v2.0.7-beta ./flux-panel
cd flux-panel
```

（或者让我打包一个 zip 给你下载。）

### 3.2 进入目录

```bash
cd /root/v2.0.7-beta     # 换成你自己的实际路径
git status               # 会列出很多红色/绿色文件，说明还没提交
```

### 3.3 分 5 次提交

逐段复制执行：

```bash
# ① 后端功能
git add springboot-backend/src/main springboot-backend/pom.xml
git commit -m "feat: 端口流量排行 + 仪表盘实时速率（后端）"

# ② 测试
git add springboot-backend/src/test
git commit -m "test: 采集/聚合/排序/重置/实时窗口与接口契约"

# ③ 前端
git add vite-frontend/src
git commit -m "feat(web): 流量排行页 + 仪表盘实时速率卡片"

# ④ 部署脚本
git add deploy springboot-backend/.dockerignore vite-frontend/.dockerignore .gitignore
git commit -m "chore(deploy): 本地构建 compose、镜像导出、dockerignore"

# ⑤ 文档（不想放进仓库就跳过这条）
git add docs/port-flow-ranking
git commit -m "docs: 需求、交接、部署与踩坑记录"
```

检查：

```bash
git status                 # 应该显示 nothing to commit, working tree clean
git log --oneline -5       # 应该看到 5 条提交
```

> 如果报 `Author identity unknown`：回到第 2 步配置 `user.name/user.email`。

---

## 4. 在 GitHub 建一个空仓库

1. 打开 https://github.com/new
2. **Repository name**：填 `flux-panel`
3. **不要勾** "Add a README file"、"Add .gitignore"、"Choose a license"（勾了会导致第一次推送冲突）
4. 点 **Create repository**
5. 新页面会显示仓库地址，例如 `https://github.com/liang0222/flux-panel.git`

---

## 5. 把本地代码连到你的仓库并推送（本次已完成，可跳过）

### 5.1 换 remote

你现在这个仓库的 `origin` 指向原作者，先改名保留（以后同步上游用）：

```bash
git remote -v
# 应该看到 origin  https://github.com/bqlpfy/flux-panel.git

git remote rename origin upstream
git remote add origin https://github.com/liang0222/flux-panel.git
git remote -v
# 现在 origin=你的仓库，upstream=原作者
```

> 如果提示 `remote origin already exists`：改成 `git remote set-url origin https://github.com/liang0222/flux-panel.git`

### 5.2 创建访问 Token（代替密码）

GitHub 不允许用登录密码推送，要用 Token：

1. 打开 https://github.com/settings/tokens
2. **Generate new token** → **Generate new token (classic)**
3. Note 填 `flux-panel`，Expiration 选 90 days
4. 勾选 **repo**（这一项就够）
5. 拉到底点 **Generate token**
6. 复制那串 `ghp_xxxx...`（**只显示一次**，先粘到记事本）

### 5.3 推送

```bash
git push -u origin feat/port-traffic-ranking
```

弹出输入时：

- `Username for 'https://github.com'` → 填 GitHub 用户名
- `Password for 'https://...'` → **粘贴 Token**（输入时屏幕不显示，正常，粘完回车）

成功会看到 `branch 'feat/port-traffic-ranking' set up to track 'origin/...'`。

回到 GitHub 网页刷新，能看到分支和文件就成功了。

> 可选：把主分支也推上去 `git push origin main beta`；推送 tag：`git push origin 2.0.7-beta:refs/tags/2.0.7-beta`。

### 5.4 认证失败怎么办

| 报错 | 原因 | 解决 |
| --- | --- | --- |
| `Authentication failed` | 密码位置填了登录密码 | 填 Token |
| `Permission denied (publickey)` | 用了 SSH 地址但没配密钥 | 改用 https 地址（上面的写法） |
| `remote: Repository not found` | 地址错了或没权限 | 核对用户名/仓库名 |
| `Updates were rejected` | 远程仓库不是空的 | 建仓库时勾了 README；删掉仓库重建，或 `git pull --rebase origin feat/port-traffic-ranking` 后重推 |

---

## 6. 注册 Docker Hub 并建仓库

1. https://hub.docker.com 注册（用户名记下来，**只能小写**，下文假设 `ahmadplayz721`）
2. 登录后点 **Create repository**
3. Name 填 `flux-panel-backend`，Visibility 选 **Public**，Create
4. 再建一个 `flux-panel-frontend`，同样 Public
5. 点头像 → **Account settings** → **Security** → **New Access Token**
   - Description：`flux-panel`
   - Access permissions：**Read & Write**
   - 生成后复制，只显示一次

> 为什么不用密码：Docker Hub 开了两步验证后必须用 Token；Token 也能随时吊销。

---

## 7. 登录 Docker（在本机）

```bash
docker login -u ahmadplayz721
# Password: 粘贴刚才的 Token
```

成功显示 `Login Succeeded`。

---

## 8. 构建镜像（本次已完成，可跳过）

**必须在项目根目录**（有 `springboot-backend` 和 `vite-frontend` 两个文件夹的地方）执行：

```bash
cd /root/v2.0.7-beta

docker build -t ahmadplayz721/flux-panel-backend:2.0.7-beta  ./springboot-backend
docker build -t ahmadplayz721/flux-panel-frontend:2.0.7-beta ./vite-frontend
```

你会看到很多下载和编译日志，**第一次 5~15 分钟**，最后出现：

```
naming to docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta done
```

就是成功。检查：

```bash
docker images | grep flux-panel
# 应该有两行，大小约 590MB 和 109MB
```

> 注意：镜像名前缀必须是你自己的 Docker Hub 用户名，全小写；写成 `bqlpfy/...` 会推送失败。

---

## 9. 推送镜像到 Docker Hub（本次已完成，可跳过）

```bash
docker push ahmadplayz721/flux-panel-backend:2.0.7-beta
docker push ahmadplayz721/flux-panel-frontend:2.0.7-beta
```

上传量约 700MB，看网速。完成后去 https://hub.docker.com/u/ahmadplayz721 能看到两个仓库和 tag `2.0.7-beta`。

---

## 10. 服务器安装 Docker

SSH 登录服务器后：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker compose version     # 有输出即可
```

> 如果提示 `docker compose` 不存在，执行 `sudo apt install -y docker-compose-plugin`。

---

## 11. 服务器上写两个文件

```bash
mkdir -p /opt/flux-panel
cd /opt/flux-panel

# 生成一个随机密钥，复制输出的那串，下一步要粘贴
openssl rand -hex 16
```

### 11.1 写 `.env`

```bash
nano .env
```

粘贴（把密钥换成上一步生成的）：

```bash
JWT_SECRET=把刚才那串粘贴到这里
FRONTEND_PORT=6366
BACKEND_PORT=6365
```

保存：`Ctrl+O` → 回车 → `Ctrl+X`。

### 11.2 写 `docker-compose.yml`

```bash
nano docker-compose.yml
```

粘贴下面全部内容（已按 `ahmadplayz721` 填好）：

```yaml
services:
  backend:
    image: docker.io/ahmadplayz721/flux-panel-backend:2.0.7-beta
    container_name: springboot-backend
    restart: unless-stopped
    environment:
      DB_PATH: /app/data/gost.db
      JWT_SECRET: ${JWT_SECRET}
      LOG_DIR: /app/logs
      JAVA_OPTS: "-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
    ports:
      - "${BACKEND_PORT:-6365}:6365"
    volumes:
      - backend_logs:/app/logs
      - sqlite_data:/app/data
    networks: [gost-network]
    stop_grace_period: 30s
    stop_signal: SIGTERM
    healthcheck:
      test: ["CMD", "sh", "-c", "wget --no-verbose --tries=1 --spider http://localhost:6365/flow/test || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

  frontend:
    image: docker.io/ahmadplayz721/flux-panel-frontend:2.0.7-beta
    container_name: vite-frontend
    restart: unless-stopped
    ports:
      - "${FRONTEND_PORT:-6366}:80"
    depends_on:
      backend:
        condition: service_healthy
    networks: [gost-network]

volumes:
  sqlite_data:
    name: sqlite_data
  backend_logs:
    name: backend_logs

networks:
  gost-network:
    name: gost-network
    driver: bridge
```

保存退出同上。

---

## 12. 启动

```bash
docker compose pull      # 拉取你的两个镜像
docker compose up -d     # 启动
docker compose ps        # 看状态
```

成功标志：`springboot-backend` 显示 `Up ... (healthy)`，`vite-frontend` 显示 `Up`。

### 12.1 放行端口

云服务器（阿里云/腾讯云/AWS 等）在**安全组**里放行：

- `6366`：浏览器访问面板用
- `6365`：节点上报流量用（**必须**要对节点服务器开放）

系统防火墙（如果开了 ufw）：

```bash
sudo ufw allow 6366/tcp
sudo ufw allow 6365/tcp
```

### 12.2 登录并设置

1. 浏览器打开 `http://服务器IP:6366`
2. 默认账号 `admin_user`，密码 `admin_user`
3. **立刻改密码**（右上角用户名 → 修改密码）
4. 网站配置 → **面板后端地址** 填 `服务器公网IP:6365`（节点安装命令会用到它）

---

## 13. 验证新功能

```bash
# 采集日志（启动后最多 5 分钟出现）
docker logs springboot-backend | grep 端口流量采集完成

# 新表自动建好
docker exec springboot-backend sqlite3 /app/data/gost.db ".tables"
```

页面上：

1. **仪表盘**：4 张统计卡下面应该有「实时速率」卡片（没流量时显示 0 bit/s，节点上报后 5 秒内出数字）
2. 左侧 **流量排行**：可切换 今天 / 近 3 天 / 近 7 天 / 近 30 天
3. 第一次部署排行榜为空是正常的：第一次采集只建基线，端口有流量后才上榜

---

## 14. 以后怎么更新

```bash
# 本机：改代码 → 提交 → 推 GitHub
git add -A && git commit -m "fix: ..." && git push

# 本机：构建并推新版本镜像（版本号自己递增）
docker build -t ahmadplayz721/flux-panel-backend:2.0.8-beta ./springboot-backend
docker push ahmadplayz721/flux-panel-backend:2.0.8-beta
# 前端同理

# 服务器：改 compose 里的版本号 → 拉取 → 重启
nano docker-compose.yml     # 2.0.7-beta 改成 2.0.8-beta
docker compose pull && docker compose up -d
```

- 数据在 `sqlite_data` 卷里，升级不影响；表结构启动时自动创建。
- **回滚**：把版本号改回旧的，再 `pull && up -d`。
- **绝对不要**执行 `docker compose down -v`（会删数据卷）。

---

## 15. GitHub Actions 自动构建镜像（已配置并跑通）

> 本仓库已完成：镜像名改为 `ahmadplayz721/*`、secrets 已配置、移除了自动发版 job；推送到 `feat/port-traffic-ranking` 或 `main` 会自动构建 amd64 + arm64 并推送 `latest` 与 `2.0.7-beta`。以下为原理说明。

仓库里自带 `.github/workflows/docker-build.yml`，改三处才能用：

1. 把 `bqlpfy/springboot-backend`、`bqlpfy/vite-frontend` 改成 `ahmadplayz721/...`
2. 仓库 **Settings → Secrets and variables → Actions** 添加：
   - `DOCKER_HUB_USERNAME` = `ahmadplayz721`
   - `DOCKER_HUB_TOKEN` = 你生成的 Token
3. 已推过 `2.0.7-beta` tag 的话，先删掉 `create-release` 这个 job，或把 `env.VERSION` 改成 `2.0.8-beta`

之后 `git push` 到 main（或打 tag）就会自动构建并推送。

---

## 16. 常见报错对照表

| 报错/现象 | 原因 | 解决 |
| --- | --- | --- |
| `git: command not found` | 没装 Git | 第 1.2 节安装 |
| `Author identity unknown` | 没配置身份 | 第 2 节 |
| `Authentication failed` | 用了登录密码 | 密码位置填 Token |
| `remote origin already exists` | 已存在 origin | `git remote set-url origin 新地址` |
| `Repository not found` | 地址/用户名写错 | 核对后重试 |
| `denied: requested access to the resource is denied` | 镜像名前缀不是你的用户名，或没登录 | 改成 `ahmadplayz721/...`，重新 `docker login` |
| `unauthorized: authentication required` | 没登录或 Token 无写权限 | 重新生成 Read & Write Token |
| `pull access denied` | 仓库是私有 | Docker Hub 里改成 Public，或服务器先登录 |
| `exec format error` | 服务器是 arm64，镜像是 amd64 | 用 buildx 构建 `linux/arm64` |
| `port is already allocated` | 端口被占用 | 停掉旧容器，或改 `.env` 端口 |
| `no such file or directory: docker-compose.yml` | 不在 `/opt/flux-panel` 目录 | `cd /opt/flux-panel` |
| 面板打不开 | 安全组/防火墙没放行，或服务没起来 | 检查 `docker compose ps` 与安全组 |
| 节点连不上 | 6365 没放行，或「面板后端地址」填错 | 放行 6365，网站配置填 `IP:6365` |
| 时间差 8 小时 | 没设时区 | compose 里 `-Duser.timezone=Asia/Shanghai`（已带） |
| 升级后数据没了 | 改过卷名或执行了 `down -v` | 恢复卷；以后别删 |

---

## 17. 安全清单（上线前过一遍）

- [ ] 已修改默认管理员密码
- [ ] `JWT_SECRET` 是随机串，没有泄露给他人
- [ ] Token 没有写进代码、截图、聊天记录
- [ ] 不需要公网访问时，6365 只对节点服务器开放
- [ ] 定期备份 `sqlite_data` 卷
- [ ] 不再使用的 Docker Hub Token 及时删除

---

## 18. 两种部署方式怎么选

| 场景 | 用哪种 |
| --- | --- |
| 只自己用、懒得折腾仓库 | [deploy-and-publish.md](deploy-and-publish.md) 的方式 A1（源码构建）或 A2（`docker save/load`） |
| 想备份、给别人用、多台机器 | 本教程（GitHub + Docker Hub） |

两种方式跑起来的面板完全一样，数据卷通用，随时可以换。
