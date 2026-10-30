#!/bin/bash

# 获取系统架构
get_architecture() {
    ARCH=$(uname -m)
    case $ARCH in
        x86_64)
            echo "amd64"
            ;;
        aarch64|arm64)
            echo "arm64"
            ;;
        *)
            echo "amd64"  # 默认使用 amd64
            ;;
    esac
}

# ---- 下载源配置 ----
# 主下载源：本仓库发布的自建二进制。
# 相对上游的差异：内置 GOMEMLIMIT 堆软上限（缺省 1200MiB，可用 GOMEMLIMIT 或
# FLUX_AGENT_MEMLIMIT 覆盖，off/0 关闭）、service 包不再 log.Fatal（可单元测试）、
# -V 版本输出可用、修正两处 fmt.Println 格式化动词误用。业务逻辑与上游一致。
AGENT_REPO="liang0222/flux-panel"
AGENT_RELEASE_TAG="2.0.7-beta-perf"
# 备用下载源：上游同版本二进制。本仓库 Release 不可达时自动回退，
# 保证安装不因单一下载源故障而中断（此时不含上述自建改进）。
UPSTREAM_REPO="bqlpfy/flux-panel"
UPSTREAM_RELEASE_TAG="2.0.7-beta"

# 构建主下载地址（可用 FLUX_AGENT_RELEASE_BASE 指向自建镜像）
build_download_url() {
    local ARCH=$(get_architecture)
    local BASE="${FLUX_AGENT_RELEASE_BASE:-https://github.com/${AGENT_REPO}/releases/download}"
    echo "${BASE}/${AGENT_RELEASE_TAG}/gost-${ARCH}"
}

# 构建备用下载地址
build_fallback_url() {
    local ARCH=$(get_architecture)
    echo "https://github.com/${UPSTREAM_REPO}/releases/download/${UPSTREAM_RELEASE_TAG}/gost-${ARCH}"
}

# 下载地址
DOWNLOAD_URL=$(build_download_url)
FALLBACK_URL=$(build_fallback_url)
INSTALL_DIR="/etc/flux_agent"
# Go 运行时堆软上限，写入 systemd 单元的 Environment=GOMEMLIMIT。
# 作用：超过该值后 GC 会更积极回收，避免长时间运行后 RSS 无界增长。
# 注意：主源二进制已内置同样的缺省值（1200MiB），此处显式设置是为
#       (1) 回退到上游二进制时仍然生效；(2) 让 systemctl show 能直接看到当前值。
# 可用环境变量覆盖：FLUX_AGENT_MEMLIMIT=900MiB ./install.sh
MEM_LIMIT="${FLUX_AGENT_MEMLIMIT:-1200MiB}"
COUNTRY=$(curl -s --max-time 10 https://ipinfo.io/country)
if [ "$COUNTRY" = "CN" ]; then
    # 国内网络直连 GitHub Releases 常失败，统一走加速前缀（主/备地址都加）
    DOWNLOAD_URL="https://ghfast.top/${DOWNLOAD_URL}"
    FALLBACK_URL="https://ghfast.top/${FALLBACK_URL}"
fi

# 按顺序尝试的下载地址：主源 -> 备源 -> 主源直连（仅国内代理场景下作为兜底）
DOWNLOAD_CANDIDATES="$DOWNLOAD_URL $FALLBACK_URL"
if [ "$COUNTRY" = "CN" ]; then
    DOWNLOAD_CANDIDATES="$DOWNLOAD_CANDIDATES $(build_download_url)"
fi

# 下载节点二进制到指定路径。
# 依次尝试各候选地址，任一成功即返回 0；全部失败返回 1。
# 使用 curl -f，使 404/5xx 直接判为失败，避免把错误页当成二进制保存。
download_agent() {
    local dest="$1"
    local url
    for url in $DOWNLOAD_CANDIDATES; do
        [ -z "$url" ] && continue
        echo "⬇️ 尝试下载: $url"
        if curl -fL --connect-timeout 15 --max-time 300 "$url" -o "$dest" 2>/dev/null; then
            if [ -s "$dest" ]; then
                echo "✅ 下载成功"
                return 0
            fi
        fi
        echo "⚠️ 该地址不可用，尝试下一个"
        rm -f "$dest"
    done
    return 1
}

# 显示菜单
show_menu() {
  echo "==============================================="
  echo "              管理脚本"
  echo "==============================================="
  echo "请选择操作："
  echo "1. 安装"
  echo "2. 更新"  
  echo "3. 卸载"
  echo "4. 退出"
  echo "==============================================="
}

# 删除脚本自身
delete_self() {
  echo ""
  echo "🗑️ 操作已完成，正在清理脚本文件..."
  SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  sleep 1
  rm -f "$SCRIPT_PATH" && echo "✅ 脚本文件已删除" || echo "❌ 删除脚本文件失败"
}

# 检查并安装 tcpkill
check_and_install_tcpkill() {
  # 检查 tcpkill 是否已安装
  if command -v tcpkill &> /dev/null; then
    return 0
  fi
  
  # 检测操作系统类型
  OS_TYPE=$(uname -s)
  
  # 检查是否需要 sudo
  if [[ $EUID -ne 0 ]]; then
    SUDO_CMD="sudo"
  else
    SUDO_CMD=""
  fi
  
  if [[ "$OS_TYPE" == "Darwin" ]]; then
    if command -v brew &> /dev/null; then
      brew install dsniff &> /dev/null
    fi
    return 0
  fi
  
  # 检测 Linux 发行版并安装对应的包
  if [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO=$ID
  elif [ -f /etc/redhat-release ]; then
    DISTRO="rhel"
  elif [ -f /etc/debian_version ]; then
    DISTRO="debian"
  else
    return 0
  fi
  
  case $DISTRO in
    ubuntu|debian)
      $SUDO_CMD apt update &> /dev/null
      $SUDO_CMD apt install -y dsniff &> /dev/null
      ;;
    centos|rhel|fedora)
      if command -v dnf &> /dev/null; then
        $SUDO_CMD dnf install -y dsniff &> /dev/null
      elif command -v yum &> /dev/null; then
        $SUDO_CMD yum install -y dsniff &> /dev/null
      fi
      ;;
    alpine)
      $SUDO_CMD apk add --no-cache dsniff &> /dev/null
      ;;
    arch|manjaro)
      $SUDO_CMD pacman -S --noconfirm dsniff &> /dev/null
      ;;
    opensuse*|sles)
      $SUDO_CMD zypper install -y dsniff &> /dev/null
      ;;
    gentoo)
      $SUDO_CMD emerge --ask=n net-analyzer/dsniff &> /dev/null
      ;;
    void)
      $SUDO_CMD xbps-install -Sy dsniff &> /dev/null
      ;;
  esac
  
  return 0
}


# 获取用户输入的配置参数
get_config_params() {
  if [[ -z "$SERVER_ADDR" || -z "$SECRET" ]]; then
    echo "请输入配置参数："
    
    if [[ -z "$SERVER_ADDR" ]]; then
      read -p "服务器地址: " SERVER_ADDR
    fi
    
    if [[ -z "$SECRET" ]]; then
      read -p "密钥: " SECRET
    fi
    
    if [[ -z "$SERVER_ADDR" || -z "$SECRET" ]]; then
      echo "❌ 参数不完整，操作取消。"
      exit 1
    fi
  fi

  # 规范化：去掉 http(s)://、路径和末尾斜杠，只保留 host:port
  SERVER_ADDR="${SERVER_ADDR#http://}"
  SERVER_ADDR="${SERVER_ADDR#https://}"
  SERVER_ADDR="${SERVER_ADDR%%/*}"
  SERVER_ADDR="${SERVER_ADDR%/}"

  # 校验格式：IPv4/域名:端口 或 [IPv6]:端口
  if ! [[ "$SERVER_ADDR" =~ ^(\[[^]]+\]|[^:[:space:]]+):[0-9]+$ ]]; then
    echo "❌ 面板后端地址格式错误: $SERVER_ADDR"
    echo "   正确格式是 IP:端口，例如 1.2.3.4:6365"
    echo "   不要带 http://、https://、路径或末尾斜杠；端口是后端端口，不是面板网页端口"
    exit 1
  fi
}

# 解析命令行参数
while getopts "a:s:" opt; do
  case $opt in
    a) SERVER_ADDR="$OPTARG" ;;
    s) SECRET="$OPTARG" ;;
    *) echo "❌ 无效参数"; exit 1 ;;
  esac
done

# 安装功能
install_flux_agent() {
  echo "🚀 开始安装 flux_agent..."
  get_config_params

    # 检查并安装 tcpkill
  check_and_install_tcpkill
  

  mkdir -p "$INSTALL_DIR"

  # 停止并禁用已有服务
  if systemctl list-units --full -all | grep -Fq "flux_agent.service"; then
    echo "🔍 检测到已存在的flux_agent服务"
    systemctl stop flux_agent 2>/dev/null && echo "🛑 停止服务"
    systemctl disable flux_agent 2>/dev/null && echo "🚫 禁用自启"
  fi

  # 删除旧文件
  [[ -f "$INSTALL_DIR/flux_agent" ]] && echo "🧹 删除旧文件 flux_agent" && rm -f "$INSTALL_DIR/flux_agent"

  # 下载 flux_agent
  echo "⬇️ 下载 flux_agent 中..."
  if ! download_agent "$INSTALL_DIR/flux_agent"; then
    echo "❌ 下载失败，请检查网络或下载链接。"
    exit 1
  fi
  chmod +x "$INSTALL_DIR/flux_agent"
  echo "✅ 下载完成"

  # 打印版本
  echo "🔎 flux_agent 版本：$($INSTALL_DIR/flux_agent -V)"

  # 写入 config.json (安装时总是创建新的)
  CONFIG_FILE="$INSTALL_DIR/config.json"
  echo "📄 创建新配置: config.json"
  cat > "$CONFIG_FILE" <<EOF
{
  "addr": "$SERVER_ADDR",
  "secret": "$SECRET"
}
EOF

  # 写入 gost.json
  GOST_CONFIG="$INSTALL_DIR/gost.json"
  if [[ -f "$GOST_CONFIG" ]]; then
    echo "⏭️ 跳过配置文件: gost.json (已存在)"
  else
    echo "📄 创建新配置: gost.json"
    cat > "$GOST_CONFIG" <<EOF
{}
EOF
  fi

  # 加强权限
  chmod 600 "$INSTALL_DIR"/*.json

  # 创建 systemd 服务
  SERVICE_FILE="/etc/systemd/system/flux_agent.service"
  cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Flux_agent Proxy Service
After=network.target

[Service]
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/flux_agent
# 堆软上限：防止长时间运行后内存无界增长（Go 运行时原生支持，无需改二进制）
Environment="GOMEMLIMIT=$MEM_LIMIT"
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

  # 启动服务
  systemctl daemon-reload
  systemctl enable flux_agent
  systemctl start flux_agent

  # 检查状态
  echo "🔄 检查服务状态..."
  if systemctl is-active --quiet flux_agent; then
    echo "✅ 安装完成，flux_agent服务已启动并设置为开机启动。"
    echo "📁 配置目录: $INSTALL_DIR"
    echo "🔧 服务状态: $(systemctl is-active flux_agent)"
  else
    echo "❌ flux_agent服务启动失败，请执行以下命令查看日志："
    echo "journalctl -u flux_agent -f"
    return 1
  fi

  # 连通性自检：节点必须能访问面板后端的 /flow/test
  echo "🔍 测试面板连通性: http://$SERVER_ADDR/flow/test"
  HTTP_CODE=$(curl -s -m 6 -o /dev/null -w "%{http_code}" "http://$SERVER_ADDR/flow/test" || true)
  if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 面板可达，节点会在几秒内上线（面板「节点监控」里刷新查看）"
  else
    echo "⚠️ 无法连接面板后端：http://$SERVER_ADDR/flow/test （返回: ${HTTP_CODE:-超时}）"
    echo "   请依次检查："
    echo "   1) 面板服务器安全组/防火墙是否放行该端口（默认 6365/TCP，节点→面板方向）"
    echo "   2) 地址是否为 IP:端口（不能带 http://，不要填面板网页端口 6366）"
    echo "   3) 面板容器是否运行：docker compose ps（springboot-backend 应为 healthy）"
    echo "   4) 是否套了 CDN/反向代理（节点连接不支持）"
    echo "   查看节点日志：journalctl -u flux_agent -n 50 --no-pager"
  fi
}

# 更新功能
# 确保 systemd 单元中存在 GOMEMLIMIT（供老节点升级时补上，幂等）
ensure_service_memory_limit() {
  local svc="/etc/systemd/system/flux_agent.service"
  [[ -f "$svc" ]] || return 0
  if grep -q '^Environment="GOMEMLIMIT=' "$svc"; then
    # 已存在：按当前 MEM_LIMIT 刷新数值
    sed -i "s|^Environment=\"GOMEMLIMIT=.*\"$|Environment=\"GOMEMLIMIT=$MEM_LIMIT\"|" "$svc"
  else
    # 不存在：插到 ExecStart 之后（用 awk 避免 sed a\ 的转义问题）
    awk -v lim="$MEM_LIMIT" '
      /^ExecStart=/ {
        print
        print "# 堆软上限：防止长时间运行后内存无界增长（Go 运行时原生支持，无需改二进制）"
        print "Environment=\"GOMEMLIMIT=" lim "\""
        next
      }
      { print }
    ' "$svc" > "$svc.tmp" && mv "$svc.tmp" "$svc"
  fi
  systemctl daemon-reload
}

update_flux_agent() {
  echo "🔄 开始更新 flux_agent..."
  
  if [[ ! -d "$INSTALL_DIR" ]]; then
    echo "❌ flux_agent 未安装，请先选择安装。"
    return 1
  fi
  
  echo "📥 使用下载地址: $DOWNLOAD_URL"
  
  # 检查并安装 tcpkill
  check_and_install_tcpkill
  
  # 先下载新版本
  echo "⬇️ 下载最新版本..."
  if ! download_agent "$INSTALL_DIR/flux_agent.new"; then
    echo "❌ 下载失败。"
    return 1
  fi

  # 停止服务
  if systemctl list-units --full -all | grep -Fq "flux_agent.service"; then
    echo "🛑 停止 flux_agent 服务..."
    systemctl stop flux_agent
  fi

  # 替换文件
  mv "$INSTALL_DIR/flux_agent.new" "$INSTALL_DIR/flux_agent"
  chmod +x "$INSTALL_DIR/flux_agent"
  
  # 打印版本
  echo "🔎 新版本：$($INSTALL_DIR/flux_agent -V)"

  # 补齐内存上限（老节点升级时同样生效）
  ensure_service_memory_limit

  # 重启服务
  echo "🔄 重启服务..."
  systemctl start flux_agent
  
  echo "✅ 更新完成，服务已重新启动。"
}

# 卸载功能
uninstall_flux_agent() {
  echo "🗑️ 开始卸载 flux_agent..."
  
  read -p "确认卸载 flux_agent 吗？此操作将删除所有相关文件 (y/N): " confirm
  if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
    echo "❌ 取消卸载"
    return 0
  fi

  # 停止并禁用服务
  if systemctl list-units --full -all | grep -Fq "flux_agent.service"; then
    echo "🛑 停止并禁用服务..."
    systemctl stop flux_agent 2>/dev/null
    systemctl disable flux_agent 2>/dev/null
  fi

  # 删除服务文件
  if [[ -f "/etc/systemd/system/flux_agent.service" ]]; then
    rm -f "/etc/systemd/system/flux_agent.service"
    echo "🧹 删除服务文件"
  fi

  # 删除安装目录
  if [[ -d "$INSTALL_DIR" ]]; then
    rm -rf "$INSTALL_DIR"
    echo "🧹 删除安装目录: $INSTALL_DIR"
  fi

  # 重载 systemd
  systemctl daemon-reload

  echo "✅ 卸载完成"
}

# 主逻辑
main() {
  # 如果提供了命令行参数，直接执行安装
  if [[ -n "$SERVER_ADDR" && -n "$SECRET" ]]; then
    install_flux_agent
    delete_self
    exit 0
  fi

  # 显示交互式菜单
  while true; do
    show_menu
    read -p "请输入选项 (1-4): " choice
    
    case $choice in
      1)
        install_flux_agent
        delete_self
        exit 0
        ;;
      2)
        update_flux_agent
        delete_self
        exit 0
        ;;
      3)
        uninstall_flux_agent
        delete_self
        exit 0
        ;;
      4)
        echo "👋 退出脚本"
        delete_self
        exit 0
        ;;
      *)
        echo "❌ 无效选项，请输入 1-4"
        echo ""
        ;;
    esac
  done
}

# 执行主函数
main