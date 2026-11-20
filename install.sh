#!/bin/bash

# 摘要来自相应 GitHub Release 资产元数据，固定在脚本内；镜像站只负责传输。
AGENT_REPO="liang0222/flux-panel"
AGENT_RELEASE_TAG="2.0.7-beta-perf"
UPSTREAM_REPO="bqlpfy/flux-panel"
UPSTREAM_RELEASE_TAG="2.0.7-beta"
INSTALL_DIR="/etc/flux_agent"
SERVICE_DIR="/etc/systemd/system"
MEM_LIMIT="${FLUX_AGENT_MEMLIMIT:-512MiB}"

get_architecture() {
  case $(uname -m) in
    x86_64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    *) echo "❌ 当前系统架构没有受支持的节点二进制。" >&2; return 1 ;;
  esac
}

sha256_of() {
  if command -v sha256sum >/dev/null; then
    sha256sum < "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 < "$1" | cut -d ' ' -f 1
  fi
}

download_agent() {
  local dest="$1" arch primary upstream base expected actual url index
  arch=$(get_architecture) || return 1
  case "$arch" in
    amd64)
      primary=e7fa5ef2e8ce17dac333d16895e5463d3be784fd9411d1649097040a7a918da7
      upstream=cf76a81f0952a91f1013a8a8b425380444895e5460407cb6e1d89d54f7f323e4 ;;
    arm64)
      primary=1c364276517d26d84fe64355f8133db5f6ab89a634028ed31caf6fa55f658602
      upstream=a63b3141e5da5c4d2b800a18d3deeab7290b1d5ee6c45c3b1a30d8d0437d3704 ;;
  esac
  if [[ -n ${FLUX_AGENT_SHA256:-} ]]; then
    [[ $FLUX_AGENT_SHA256 =~ ^[0-9a-f]{64}$ ]] || {
      echo "❌ FLUX_AGENT_SHA256 必须是独立核验的 64 位小写十六进制摘要。" >&2; return 1;
    }
    primary=$FLUX_AGENT_SHA256
  fi
  base="${FLUX_AGENT_RELEASE_BASE:-https://github.com/${AGENT_REPO}/releases/download}"
  local -a urls=("${base%/}/${AGENT_RELEASE_TAG}/gost-$arch"
                 "https://github.com/${UPSTREAM_REPO}/releases/download/${UPSTREAM_RELEASE_TAG}/gost-$arch")
  for index in 0 1; do
    expected=$primary
    if [[ $index == 1 ]]; then
      expected=$upstream
      # 自定义发布资产的运营方选择了另一份产物，失败时保留现有版本。
      [[ -z ${FLUX_AGENT_SHA256:-} ]] || break
    fi
    local -a sources=("${urls[index]}")
    if [[ ${urls[index]} == https://github.com/* ]]; then
      sources+=("https://ghfast.top/${urls[index]}")
    fi
    for url in "${sources[@]}"; do
      rm -f -- "$dest" || return 1
      if curl --proto '=https' --proto-redir '=https' -fsSL --connect-timeout 12 --max-time 300 \
          -o "$dest" -- "$url" 2>/dev/null && [[ -s "$dest" ]]; then
        actual=$(sha256_of "$dest") || return 1
        if [[ $actual == "$expected" ]]; then
          echo "✅ 节点二进制 SHA-256 校验通过。"
          return 0
        fi
        echo "⚠️ 节点二进制摘要不匹配，跳过该下载源。" >&2
      fi
    done
  done
  rm -f -- "$dest"
  echo "❌ 未取得通过摘要校验的节点二进制。" >&2
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


# 安装参数只写到权限 600 的 JSON 文件。终端交互不回显密钥。
get_config_params() {
  if [[ -z ${SERVER_ADDR:-} ]]; then
    read -r -p "服务器地址: " SERVER_ADDR || return 1
  fi
  if [[ -z ${SECRET:-} ]]; then
    read -rs -p "密钥: " SECRET || return 1
    printf '\n'
  fi
  SERVER_ADDR="${SERVER_ADDR#http://}"
  SERVER_ADDR="${SERVER_ADDR#https://}"
  SERVER_ADDR="${SERVER_ADDR%%/*}"
  if ! [[ $SERVER_ADDR =~ ^(\[[0-9a-fA-F:%.]+\]|[a-zA-Z0-9_.-]+):([0-9]{1,5})$ ]] ||
     (( 10#${BASH_REMATCH[2]} < 1 || 10#${BASH_REMATCH[2]} > 65535 )); then
    echo "❌ 请使用 IP 或域名:后端端口，IPv6 地址须带方括号。" >&2
    return 1
  fi
  if [[ -z $SECRET || $SECRET =~ [[:cntrl:]] ]]; then
    echo "❌ 节点密钥缺失或包含控制字符。" >&2
    return 1
  fi
}

json_string() {
  local escaped="$1"
  escaped=${escaped//\\/\\\\}
  escaped=${escaped//\"/\\\"}
  printf '"%s"' "$escaped"
}

# 观察 PID 持续存活，避免把 systemd 的短暂 active 当作启动成功。
wait_for_agent() {
  local attempt pid last_pid= stable=0
  for ((attempt=0; attempt<20; attempt++)); do
    if systemctl is-active --quiet flux_agent &&
       pid=$(systemctl show -p MainPID --value flux_agent) && [[ $pid =~ ^[1-9][0-9]*$ ]]; then
      if [[ $pid == "$last_pid" ]]; then
        stable=$((stable + 1))
        [[ $stable == 2 ]] && return 0
      else
        last_pid=$pid
        stable=0
      fi
    else
      last_pid=
      stable=0
    fi
    sleep 1
  done
  echo "❌ 节点进程未通过启动检查，请查看本机 flux_agent 日志。" >&2
  return 1
}

# 备份和临时文件位于同一文件系统，单个文件以 rename 原子替换。
agent_restore_file() {
  local backup="$1" destination="$2"
  if [[ -e $backup || -L $backup ]]; then
    mv -f -- "$backup" "$destination"
  else
    rm -f -- "$destination"
  fi
}

# 子 shell 的 EXIT trap 同时覆盖命令失败和 INT/TERM。回滚失败保留现场。
agent_transaction() (
  umask 077
  local mode="$1" work='' unit_work='' changed=0 stopped=0 old_active=0 enabled_here=0
  local write_config=0 write_gost=0 write_unit=0 write_limit=0 rc=0 keep=0
  local svc="$SERVICE_DIR/flux_agent.service" dropdir="$SERVICE_DIR/flux_agent.service.d"
  local limit_file="$dropdir/99-flux-agent-memory.conf"
  command -v flock >/dev/null || { echo "节点安装需要 flock（util-linux）。" >&2; exit 1; }
  if [[ $mode == update && ! -f $INSTALL_DIR/flux_agent ]]; then
    echo "❌ flux_agent 未安装，请先选择安装。" >&2
    exit 1
  fi
  mkdir -p -- "$INSTALL_DIR" || exit 1
  exec 9>"$INSTALL_DIR/.install.lock" || exit 1
  flock -n 9 || { echo "❌ 同一节点有另一个安装操作正在运行。" >&2; exit 1; }
  agent_finish() {
    rc=$?
    trap - EXIT
    trap '' INT TERM
    if [[ $rc != 0 && $changed == 1 ]]; then
      echo "⚠️ 节点操作失败，正在恢复旧版本。" >&2
      if systemctl stop flux_agent; then
        if (( enabled_here )); then systemctl disable flux_agent || keep=1; fi
        agent_restore_file "$work/old-agent" "$INSTALL_DIR/flux_agent" || keep=1
        if ((write_config)); then agent_restore_file "$work/old-config" "$INSTALL_DIR/config.json" || keep=1; fi
        if ((write_gost)); then rm -f -- "$INSTALL_DIR/gost.json" || keep=1; fi
        if ((write_unit)); then agent_restore_file "$unit_work/old-unit" "$svc" || keep=1; fi
        if ((write_limit)); then agent_restore_file "$unit_work/old-limit" "$limit_file" || keep=1; fi
        systemctl daemon-reload || keep=1
      else
        keep=1
      fi
    fi
    if [[ $rc != 0 && $old_active == 1 ]] && (( changed || stopped )); then
      if ((keep == 0)); then
        { systemctl start flux_agent && wait_for_agent; } || keep=1
      fi
    fi
    if ((keep)); then
      echo "❌ 节点自动回滚未完成，备份位于 $work 和 $unit_work。请先恢复旧服务。" >&2
    else
      if [[ -n $work ]]; then rm -rf -- "$work"; fi
      if [[ -n $unit_work ]]; then rm -rf -- "$unit_work"; fi
    fi
    exit "$rc"
  }
  trap agent_finish EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  work=$(mktemp -d "$INSTALL_DIR/.upgrade.XXXXXX") || exit 1
  unit_work=$(mktemp -d "$SERVICE_DIR/.flux-agent.XXXXXX") || exit 1
  if [[ ! $MEM_LIMIT =~ ^(off|[0-9]+(B|KiB|MiB|GiB|TiB)?)$ ]]; then
    echo "❌ GOMEMLIMIT 格式无效。" >&2; exit 1
  fi
  download_agent "$work/agent" || exit 1
  chmod 0755 "$work/agent" || exit 1
  if [[ -e $INSTALL_DIR/flux_agent || -L $INSTALL_DIR/flux_agent ]]; then
    cp -pP -- "$INSTALL_DIR/flux_agent" "$work/old-agent" || exit 1
  fi
  if [[ $mode == install ]]; then
    write_config=1
    if [[ -e $INSTALL_DIR/config.json || -L $INSTALL_DIR/config.json ]]; then
      cp -pP -- "$INSTALL_DIR/config.json" "$work/old-config" || exit 1
    fi
    printf '{\n  "addr": %s,\n  "secret": %s\n}\n' \
      "$(json_string "$SERVER_ADDR")" "$(json_string "$SECRET")" > "$work/config.json" || exit 1
    if [[ ! -e $INSTALL_DIR/gost.json && ! -L $INSTALL_DIR/gost.json ]]; then
      write_gost=1
      printf '{}\n' > "$work/gost.json" || exit 1
    fi
  fi
  if [[ ! -f $svc ]]; then
    if [[ $mode == update || -L $svc ]]; then
      echo "❌ 既有节点缺少预期的 systemd 单元。" >&2; exit 1
    fi
    write_unit=1
    cat > "$unit_work/unit" <<EOF || exit 1
[Unit]
Description=Flux_agent Proxy Service
After=network.target

[Service]
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/flux_agent
Environment="GOMEMLIMIT=$MEM_LIMIT"
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
    chmod 0644 "$unit_work/unit" || exit 1
  elif [[ -n ${FLUX_AGENT_MEMLIMIT:-} ]] ||
       { ! grep -q 'GOMEMLIMIT=' "$svc" && ! grep -rqs 'GOMEMLIMIT=' "$dropdir"; }; then
    write_limit=1
    if [[ -e $limit_file || -L $limit_file ]]; then
      cp -pP -- "$limit_file" "$unit_work/old-limit" || exit 1
    fi
    printf '[Service]\nEnvironment="GOMEMLIMIT=%s"\n' "$MEM_LIMIT" > "$unit_work/limit" || exit 1
    chmod 0644 "$unit_work/limit" || exit 1
  fi

  if systemctl is-active --quiet flux_agent; then old_active=1; fi
  if [[ -f $svc ]] || ((old_active)); then
    stopped=1
    systemctl stop flux_agent || exit 1
  fi
  changed=1
  mv -f -- "$work/agent" "$INSTALL_DIR/flux_agent" || exit 1
  if ((write_config)); then mv -f -- "$work/config.json" "$INSTALL_DIR/config.json" || exit 1; fi
  if ((write_gost)); then mv -f -- "$work/gost.json" "$INSTALL_DIR/gost.json" || exit 1; fi
  if ((write_unit)); then mv -f -- "$unit_work/unit" "$svc" || exit 1; fi
  if ((write_limit)); then
    mkdir -p -- "$dropdir" && mv -f -- "$unit_work/limit" "$limit_file" || exit 1
  fi
  systemctl daemon-reload || exit 1
  if [[ $mode == install ]] && ! systemctl is-enabled --quiet flux_agent; then
    enabled_here=1
    systemctl enable flux_agent || exit 1
  fi
  systemctl start flux_agent && wait_for_agent || exit 1
)

install_flux_agent() {
  get_config_params && agent_transaction install || return 1
  check_and_install_tcpkill
  echo "✅ 节点安装完成，服务已启动。配置目录: $INSTALL_DIR"
  if curl -fsS -m 6 -o /dev/null -- "http://$SERVER_ADDR/flow/test" 2>/dev/null; then
    echo "✅ 面板后端可达。"
  else
    echo "⚠️ 节点已启动，面板连通性检查失败。请检查后端绑定地址及安全组。"
  fi
}

update_flux_agent() {
  agent_transaction update || return 1
  echo "✅ 节点更新完成，服务已通过启动检查。"
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
  if [[ -f "${SERVICE_DIR}/flux_agent.service" ]]; then
    rm -f "${SERVICE_DIR}/flux_agent.service"
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

main() {
  local opt choice
  while getopts "a:s:" opt; do
    case $opt in
      a) SERVER_ADDR="$OPTARG" ;;
      s) SECRET="$OPTARG" ;;
      *) return 1 ;;
    esac
  done
  if [[ -n ${SERVER_ADDR:-} && -n ${SECRET:-} ]]; then
    install_flux_agent
    return
  fi
  while true; do
    show_menu
    read -r -p "请输入选项 (1-4): " choice || return 1
    case $choice in
      1) install_flux_agent; return $? ;;
      2) update_flux_agent; return $? ;;
      3) uninstall_flux_agent; return $? ;;
      4) return 0 ;;
      *) echo "❌ 无效选项，请输入 1-4" ;;
    esac
  done
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
