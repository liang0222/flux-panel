#!/bin/bash
# 在面板自己的 Compose 目录运行，使用 Compose v2/v5、Python 3 和 OpenSSL。
# 优先使用邻接模板；内置同版本模板允许单文件分发。所有来源均校验固定摘要。
COMPOSE_REPO=liang0222/flux-panel
COMPOSE_REF=${FLUX_COMPOSE_REF:-refs/tags/2.0.7-beta-perf}
PANEL_HEALTH_TIMEOUT=${FLUX_PANEL_HEALTH_TIMEOUT:-270}

panel_error() { echo "❌ $*" >&2; return 1; }

panel_init() {
  command -v docker >/dev/null && docker compose version >/dev/null 2>&1 ||
    { panel_error '需要支持 config --format json 和 up --wait 的 Docker Compose。'; return 1; }
  command -v python3 >/dev/null || { panel_error '需要 Python 3 来校验 Compose 配置和容器归属。'; return 1; }
  PANEL_DIR=$(pwd -P) || return 1
  PANEL_FILE="$PANEL_DIR/docker-compose.yml"
  PANEL_ENV="$PANEL_DIR/.env"
  if [[ -L $PANEL_FILE || -L $PANEL_ENV ]]; then
    panel_error '请在 Compose 和 .env 普通文件所在目录运行。'
    return 1
  fi
  [[ $PANEL_HEALTH_TIMEOUT =~ ^[1-9][0-9]{0,3}$ ]] || panel_error '健康检查时间配置无效。'
}

panel_lock() {
  if ! mkdir "$PANEL_DIR/.flux-panel.lock" 2>/dev/null; then
    panel_error '此目录已有面板操作在运行。如确认为异常退出，请先人工检查遗留锁和恢复目录。'
    return 1
  fi
}

panel_compose() {
  local file="$1" env_file="$2"
  shift 2
  # 始终使用明确的项目、路径及文件；部署参数来自指定 env 文件，避免 shell 值覆盖回滚/迁移配置。
  env -u JWT_SECRET -u FRONTEND_PORT -u BACKEND_PORT -u FRONTEND_BIND_IP -u BACKEND_BIND_IP \
    -u FRONTEND_BIND_IPV6 -u BACKEND_BIND_IPV6 -u FLUX_BACKEND_IMAGE -u FLUX_FRONTEND_IMAGE \
    -u PANEL_ALLOWED_ORIGINS COMPOSE_PROFILES= COMPOSE_REMOVE_ORPHANS=false COMPOSE_IGNORE_ORPHANS=false \
    docker compose --project-name "$PANEL_PROJECT" --project-directory "$PANEL_DIR" \
    --env-file "$env_file" -f "$file" "$@"
}

panel_sha256() {
  if command -v sha256sum >/dev/null; then
    sha256sum < "$1" | cut -d ' ' -f 1
  else
    shasum -a 256 < "$1" | cut -d ' ' -f 1
  fi
}

# 内置模板以根目录 Compose 为准；部署测试校验两种模板的完整字节。
panel_embedded_compose() {
  local variant="$1"
  python3 -I -c '
import sys
variant = sys.argv[1]
text = sys.stdin.read()
if variant == "v6":
    lines = []
    for line in text.splitlines(keepends=True):
        lines.append(line)
        if "_BIND_IP-127.0.0.1}" in line:
            lines.append(line.replace("${BACKEND_BIND_IP-127.0.0.1}", "[${BACKEND_BIND_IPV6:-::1}]")
                             .replace("${FRONTEND_BIND_IP-127.0.0.1}", "[${FRONTEND_BIND_IPV6:-::1}]"))
        elif line == "    driver: bridge\n":
            lines.append("    enable_ipv6: true\n")
        elif line == "        - subnet: 172.20.0.0/16\n":
            lines.append("        - subnet: fd00:dead:beef::/48\n")
    text = "".join(lines)
sys.stdout.write(text)
' "$variant" <<'FLUX_COMPOSE_TEMPLATE'
services:
  backend:
    image: ${FLUX_BACKEND_IMAGE:-ahmadplayz721/flux-panel-backend:2.0.7-beta}
    # SQLite 统计任务只允许单实例；同一数据卷不得再挂载给其他后端。
    scale: 1
    container_name: springboot-backend
    restart: unless-stopped
    environment:
      DB_PATH: /app/data/gost.db
      JWT_SECRET: ${JWT_SECRET:?请先在 .env 设置随机 JWT_SECRET（openssl rand -hex 32）}
      PANEL_ALLOWED_ORIGINS: ${PANEL_ALLOWED_ORIGINS:-}
      LOG_DIR: /app/logs
      JAVA_OPTS: "-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:TrimNativeHeapInterval=15000 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
    ports:
      - "${BACKEND_BIND_IP-127.0.0.1}:${BACKEND_PORT:-6365}:6365"
    volumes:
      - backend_logs:/app/logs
      - sqlite_data:/app/data
    networks:
      - gost-network
    stop_grace_period: 30s
    stop_signal: SIGTERM
    # 日志轮转：原配置使用默认 json-file 驱动且不限制大小（LogConfig.Config 为空），
    # 容器日志会随运行时间无限增长。生产实测 springboot-backend 的 json 日志已达 55MB
    # 且没有任何上限。10MB x 3 = 单容器最多 30MB，保留最近 3 份用于故障追踪。
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    # 可选内存上限（默认注释，避免硬限制掩盖问题或触发 OOMKilled）：
    # 需同时保证 -Xmx512m + Metaspace 后仍有余量，建议不小于 900m。
    # mem_limit: 900m
    healthcheck:
      test: ["CMD", "sh", "-c", "wget --no-verbose --tries=1 --spider http://localhost:6365/flow/test || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

  frontend:
    image: ${FLUX_FRONTEND_IMAGE:-ahmadplayz721/flux-panel-frontend:2.0.7-beta}
    container_name: vite-frontend
    restart: unless-stopped
    ports:
      - "${FRONTEND_BIND_IP-127.0.0.1}:${FRONTEND_PORT:-6366}:80"
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://127.0.0.1/"]
      interval: 10s
      timeout: 5s
      retries: 6
    depends_on:
      backend:
        condition: service_healthy
    networks:
      - gost-network
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"


volumes:
  sqlite_data:
    name: sqlite_data
    driver: local
  backend_logs:
    name: backend_logs
    driver: local


networks:
  gost-network:
    name: gost-network
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16
FLUX_COMPOSE_TEMPLATE
}

panel_download_compose() {
  local file="$1" variant="$2" name="docker-compose-$2.yml" expected actual url mirror script_dir
  if [[ $variant == v4 ]]; then
    expected=${FLUX_COMPOSE_SHA256_V4:-b253e091bc9acf0f2824b14efa57678a6d74df7b4187f43c1932eee06f8839f8}
  else
    expected=${FLUX_COMPOSE_SHA256_V6:-ee7334ef788ed3a6d45e4496a39f8c5bf40bcfca0c63ce1de4e5d5a543b971c5}
  fi
  [[ $expected =~ ^[0-9a-f]{64}$ && $COMPOSE_REF =~ ^[A-Za-z0-9._/-]+$ ]] ||
    { panel_error 'Compose ref 或 SHA-256 无效。'; return 1; }
  script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P) || return 1
  if [[ -f $script_dir/$name ]] && cp -- "$script_dir/$name" "$file" &&
     actual=$(panel_sha256 "$file") && [[ $actual == "$expected" ]]; then
    return 0
  fi
  if panel_embedded_compose "$variant" > "$file" &&
     actual=$(panel_sha256 "$file") && [[ $actual == "$expected" ]]; then
    return 0
  fi
  url="https://raw.githubusercontent.com/$COMPOSE_REPO/$COMPOSE_REF/$name"
  for mirror in "$url" "https://ghfast.top/$url"; do
    rm -f -- "$file" || return 1
    if curl --proto '=https' --proto-redir '=https' -fsSL --connect-timeout 10 --max-time 45 \
        -o "$file" -- "$mirror" 2>/dev/null && actual=$(panel_sha256 "$file") && [[ $actual == "$expected" ]]; then
      return 0
    fi
  done
  panel_error '未取得通过固定 SHA-256 校验的 Compose 模板。'
}

# 根据两个原有容器的标签识别真实 Compose 项目。固定名称只用作初始查找。
# 从 Docker 取得的完整 JSON 只保存在 umask 077 的工作目录内，绝不输出到终端。
panel_identify_existing() {
  local work="$1" ids backend frontend
  [[ -f $PANEL_FILE && ! -L $PANEL_FILE && -f $PANEL_ENV && ! -L $PANEL_ENV ]] || { panel_error '当前目录缺少现有 Compose 或 .env。'; return 1; }
  docker container inspect springboot-backend vite-frontend > "$work/containers.json" 2>/dev/null ||
    { panel_error '需要找到两个既有面板容器才能核验归属。'; return 1; }
  PANEL_PROJECT=$(python3 -I - "$work/containers.json" "$PANEL_DIR" "$PANEL_FILE" <<'PY'
import json, re, sys
containers = json.load(open(sys.argv[1]))
expected = {'backend': '/springboot-backend', 'frontend': '/vite-frontend'}
projects = set()
for container in containers:
    labels = container['Config']['Labels'] or {}
    service = labels.get('com.docker.compose.service')
    assert expected.pop(service, None) == container['Name']
    assert labels.get('com.docker.compose.project.working_dir') == sys.argv[2]
    assert labels.get('com.docker.compose.project.config_files') == sys.argv[3]
    assert labels.get('com.docker.compose.oneoff', '').lower() == 'false'
    project = labels.get('com.docker.compose.project', '')
    assert re.fullmatch(r'[a-z0-9][a-z0-9_-]*', project)
    projects.add(project)
assert not expected and len(projects) == 1
print(projects.pop())
PY
  ) 2>/dev/null || { panel_error '容器标签与当前目录、配置或服务不匹配，未执行变更。'; return 1; }
  ids=$(docker ps -aq --filter "label=com.docker.compose.project=$PANEL_PROJECT" \
    --filter 'label=com.docker.compose.oneoff=False' --filter 'label=com.docker.compose.service=backend') || return 1
  backend=$(panel_container_id "$work" backend) || return 1
  [[ $ids == "${backend:0:12}" || $ids == "$backend" ]] || { panel_error '项目存在额外后端或归属变化。'; return 1; }
  ids=$(docker ps -aq --filter "label=com.docker.compose.project=$PANEL_PROJECT" \
    --filter 'label=com.docker.compose.oneoff=False' --filter 'label=com.docker.compose.service=frontend') || return 1
  frontend=$(panel_container_id "$work" frontend) || return 1
  [[ $ids == "${frontend:0:12}" || $ids == "$frontend" ]] || { panel_error '项目存在额外前端或归属变化。'; return 1; }
  ids=$(docker ps -aq --filter 'volume=sqlite_data') || return 1
  [[ $ids == "${backend:0:12}" || $ids == "$backend" ]] ||
    panel_error 'SQLite 数据卷未由现有后端独占，请确认单实例部署。'
}

panel_container_id() {
  python3 -I - "$1/containers.json" "$2" <<'PY'
import json, sys
for item in json.load(open(sys.argv[1])):
    if item['Config']['Labels']['com.docker.compose.service'] == sys.argv[2]:
        print(item['Id'])
        break
else:
    sys.exit(1)
PY
}

# 维护固定的 SQLite/日志卷命名和单后端约束。直接 Compose 部署保留同一约束。
panel_validate_model() {
  local file="$1" env_file="$2" model="$3"
  panel_compose "$file" "$env_file" config --format json > "$model" 2>/dev/null ||
    { panel_error 'Compose 配置解析失败，请检查模板和 .env。'; return 1; }
  python3 -I - "$model" <<'PY' 2>/dev/null || { panel_error '配置须为预期的双服务、命名卷及单个 SQLite 后端。'; return 1; }
import json, sys
model = json.load(open(sys.argv[1]))
services = model['services']
assert set(services) == {'backend', 'frontend'}
backend, frontend = services['backend'], services['frontend']
assert backend['container_name'] == 'springboot-backend' and frontend['container_name'] == 'vite-frontend'
for service in (backend, frontend):
    assert service.get('scale', 1) == service.get('deploy', {}).get('replicas', 1) == 1
    assert service.get('image') and not service.get('build') and not service.get('profiles')
assert backend['environment']['DB_PATH'] == '/app/data/gost.db'
assert len(backend['volumes']) == 2
assert {x['target']: x['source'] for x in backend['volumes'] if x['type'] == 'volume'} == {
    '/app/data': 'sqlite_data', '/app/logs': 'backend_logs',
}
assert all(model['volumes'][x]['name'] == x for x in ('sqlite_data', 'backend_logs'))
PY
}

panel_pick_variant() {
  if [[ -n ${FLUX_PANEL_VARIANT:-} ]]; then
    case $FLUX_PANEL_VARIANT in v4|v6) printf '%s\n' "$FLUX_PANEL_VARIANT"; return;; esac
    return 1
  fi
  # IPv6 on the host does not guarantee that the Docker bridge supports IPv6.
  # Existing upgrades choose the variant from inspected port bindings instead.
  echo v4
}

panel_port() {
  [[ $1 =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 ))
}

panel_new_env() {
  local file="$1" frontend backend secret
  read -r -p '前端端口（默认 6366）: ' frontend || return 1
  read -r -p '后端端口（默认 6365）: ' backend || return 1
  frontend=${frontend:-6366}
  backend=${backend:-6365}
  if ! panel_port "$frontend" || ! panel_port "$backend" || ((10#$frontend == 10#$backend)); then
    panel_error '请输入两个不同的有效 TCP 端口。'; return 1
  fi
  frontend=$((10#$frontend))
  backend=$((10#$backend))
  secret=$(openssl rand -hex 32) && [[ $secret =~ ^[0-9a-f]{64}$ ]] || return 1
  printf 'JWT_SECRET=%s\nFRONTEND_PORT=%s\nBACKEND_PORT=%s\n' "$secret" "$frontend" "$backend" > "$file"
}

# 将现有运行容器的已发布端口迁移到新模板。保留 .env 的其他内容；只补旧模板忽略的绑定。
# Python 不打印任何密钥，也不执行 .env 文件中的内容。
panel_migrate_env() {
  local work="$1"
  python3 -I - "$work" <<'PY' 2>/dev/null || { panel_error '端口、SQLite 卷或旧环境配置不符合标准拓扑，请先备份并核对部署。'; return 1; }
import ipaddress, json, pathlib, re, subprocess, sys
work = pathlib.Path(sys.argv[1])
model = json.loads((work / 'old-model.json').read_text())
containers = json.loads((work / 'containers.json').read_text())
env = (work / 'old.env').read_text()
replacements = {}
expected_ports = {}
dual_stack = False
for c in containers:
    role = c['Config']['Labels']['com.docker.compose.service']
    port_key = '6365/tcp' if role == 'backend' else '80/tcp'
    bindings = c['HostConfig']['PortBindings'] or {}
    assert list(bindings) == [port_key] and bindings[port_key]
    addresses = {}
    host_ports = set()
    for b in bindings[port_key]:
        host = ipaddress.ip_address(b['HostIp'] or '0.0.0.0')
        assert host.version not in addresses and not getattr(host, 'scope_id', None)
        addresses[host.version] = str(host) if b['HostIp'] else ''
        host_ports.add(int(b['HostPort']))
    assert len(host_ports) == 1 and 4 in addresses and all(1 <= p <= 65535 for p in host_ports)
    key = role.upper()
    published = str(host_ports.pop())
    replacements[f'{key}_PORT'] = published
    replacements[f'{key}_BIND_IP'] = addresses[4]
    expected_ports[role] = [[address, published] for address in addresses.values()]
    if 6 in addresses:
        replacements[f'{key}_BIND_IPV6'] = addresses[6]
        dual_stack = True
    if role == 'backend':
        mounts = {m['Destination']: m for m in c['Mounts']}
        for destination, name in (('/app/data', 'sqlite_data'), ('/app/logs', 'backend_logs')):
            assert mounts[destination]['Type'] == 'volume' and mounts[destination]['Name'] == name
if dual_stack:
    assert all(f'{s}_BIND_IPV6' in replacements for s in ('FRONTEND', 'BACKEND'))
backend = next(c for c in containers if c['Config']['Labels']['com.docker.compose.service'] == 'backend')
active_env = dict(kv.split('=', 1) for kv in backend['Config'].get('Env', []) if '=' in kv)
# 以实际容器环境为准。若运行配置偏离文件，先人工协调外部注入来源，避免无意改变认证。
rendered = str(model['services']['backend']['environment'].get('JWT_SECRET') or '')
original = active_env.get('JWT_SECRET', '')
# Compose config serializes literal dollar signs as $$ so its output can be parsed again.
assert rendered == original.replace('$', '$$')
secret = original.strip()
# 与后端最低长度、占位符及重复字符判据保持一致。旧脚本的 16 位值会轮换。
if len(secret.encode()) < 32 or len(set(secret)) < 2 or secret.lower() == 'change-me-to-a-random-string':
    replacements['JWT_SECRET'] = subprocess.check_output(['openssl', 'rand', '-hex', '32'], text=True).strip()
    assert re.fullmatch(r'[0-9a-f]{64}', replacements['JWT_SECRET'])
    (work / 'rotated').touch()
(work / 'expected-deployment.json').write_text(json.dumps({
    'ports': expected_ports,
    'new_secret': replacements.get('JWT_SECRET', original).replace('$', '$$'),
    'old_secret': rendered,
}))
# 只替换旧文件中的普通单行赋值。非标准 JWT 或端口配置需先人工迁移。
lines = env.splitlines(keepends=True)
pending = dict(replacements)
for i, line in enumerate(lines):
    match = re.match(r'^\s*(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=\s*(.*)$', line)
    if match and match[1] in replacements:
        value = match[2]
        if value.startswith(("'", '"')):
            assert re.fullmatch(r"(['\"])(?:[^\\]|\\.)*\1(?:\s*#.*)?\s*", value)
        key = match[1]
        lines[i] = f'{key}={replacements[key]}\n'
        pending.pop(key, None)
with (work / 'new.env').open('w') as out:
    out.write(''.join(lines))
    if pending:
        out.write('\n# Flux 面板迁移：保留既有端口与绑定。\n')
        for key, value in pending.items():
            out.write(f'{key}={value}\n')
(work / 'variant').write_text('v6' if dual_stack else 'v4')
PY
}

# 更新和回滚的配置都须匹配从现有容器检查得到的端口和相应密钥。
panel_validate_migration() {
  python3 -I - "$1" "$2" "$3" <<'PY' 2>/dev/null || { panel_error '生成的配置与现有端口或预期认证设置不一致，未执行变更。'; return 1; }
import ipaddress, json, pathlib, sys
work = pathlib.Path(sys.argv[1])
expected = json.loads((work / 'expected-deployment.json').read_text())
services = json.loads((work / sys.argv[2]).read_text())['services']
for role, target in (('backend', 6365), ('frontend', 80)):
    actual = []
    for binding in services[role]['ports']:
        assert binding['target'] == target and binding['protocol'] == 'tcp'
        host = binding.get('host_ip') or ''
        actual.append([str(ipaddress.ip_address(host)) if host else '', str(binding['published'])])
    assert sorted(actual) == sorted(expected['ports'][role])
actual_secret = services['backend']['environment'].get('JWT_SECRET') or ''
assert actual_secret == expected[sys.argv[3] + '_secret']
PY
}

# 将回滚固定在已运行的内容 ID，而不是可被 pull 覆盖的镜像标签。
panel_save_images() {
  local work="$1" token="${1##*.}" backend_tag frontend_tag
  backend_tag="flux-panel-rollback/backend:${token,,}"
  frontend_tag="flux-panel-rollback/frontend:${token,,}"
  python3 -I - "$work" "$backend_tag" "$frontend_tag" <<'PY' 2>/dev/null || return 1
import json, pathlib, re, subprocess, sys
work = pathlib.Path(sys.argv[1])
tags = dict(zip(('backend','frontend'), sys.argv[2:]))
for c in json.loads((work / 'containers.json').read_text()):
    role = c['Config']['Labels']['com.docker.compose.service']
    image = c['Image']
    assert re.fullmatch('sha256:[a-f0-9]{64}', image)
    subprocess.run(['docker','image','tag',image,tags[role]], check=True)
    with (work / 'rollback-tags').open('a') as f:
        f.write(tags[role] + '\n')
# 回滚时仍使用同一个 -f 绝对路径，以免改变 Compose 容器的 config_files 标签。
# config --format json already escapes literal dollar signs for round-tripping.
model = json.loads((work / 'old-model.json').read_text())
for role, tag in tags.items():
    model['services'][role]['image'] = tag
(work / 'rollback.yml').write_text(json.dumps(model))
stopped = [c['Config']['Labels']['com.docker.compose.service'] for c in json.loads((work / 'containers.json').read_text()) if not c['State']['Running']]
(work / 'old-stopped-services').write_text(''.join(x + '\n' for x in stopped))
PY
}

# 排除健康探针、重启时间等会随时间变化的 inspect 字段。
panel_same_containers() {
  python3 -I - "$1/old-containers.json" "$1/containers.json" <<'PY' 2>/dev/null
import json, sys

def snapshot(path):
    fields = ('Id', 'Name', 'Image', 'Config', 'HostConfig', 'Mounts')
    return {c['Id']: [*(c[field] for field in fields), c['State']['Running']]
            for c in json.load(open(path))}

sys.exit(snapshot(sys.argv[1]) != snapshot(sys.argv[2]))
PY
}

panel_new_owner() {
  local conflicts
  # Docker 返回错误时立即停工，不把 daemon 不可达解释为“没有对象”。
  conflicts=$(docker ps -aq --filter "label=com.docker.compose.project=$PANEL_PROJECT") || return 1
  [[ -z $conflicts ]] || { panel_error '所选 Compose 项目已经有容器。'; return 1; }
  conflicts=$(docker ps -aq --filter 'name=^/springboot-backend$' --filter 'name=^/vite-frontend$') || return 1
  [[ -z $conflicts ]] || { panel_error '检测到同名面板容器，请在其部署目录使用更新。'; return 1; }
  conflicts=$(docker volume ls -q --filter 'name=^sqlite_data$' --filter 'name=^backend_logs$') || return 1
  [[ -z $conflicts ]] || panel_error '检测到历史命名卷。请先确认归属并用原 Compose 恢复，不自动接管历史数据。'
}

panel_remove_containers() {
  local work="$1" backend frontend
  backend=$(panel_container_id "$work" backend) && frontend=$(panel_container_id "$work" frontend) || return 1
  docker stop --time 30 "$backend" "$frontend" && docker rm "$backend" "$frontend"
}

panel_transaction() (
  umask 077
  local mode="$1" work='' changed=0 start_attempted=0 failed_rollback=0 rc=0 variant tag service
  panel_init && panel_lock || exit 1
  panel_finish() {
    rc=$?
    trap - EXIT
    trap '' INT TERM
    if ((rc != 0 && changed)); then
      echo '⚠️ 面板操作失败，正在恢复旧配置和运行状态。' >&2
      if [[ $mode == update ]]; then
        cp -- "$work/old.env" "$work/restore.env" && mv -f -- "$work/restore.env" "$PANEL_ENV" || failed_rollback=1
        if ((failed_rollback == 0 && start_attempted)); then
          if cp -- "$work/rollback.yml" "$work/restore.yml" && mv -f -- "$work/restore.yml" "$PANEL_FILE" &&
             panel_compose "$PANEL_FILE" "$PANEL_ENV" up -d --no-build --pull never --wait \
               --wait-timeout "$PANEL_HEALTH_TIMEOUT" >/dev/null 2>&1; then
            while IFS= read -r service; do
              panel_compose "$PANEL_FILE" "$PANEL_ENV" stop "$service" >/dev/null 2>&1 || failed_rollback=1
            done < "$work/old-stopped-services"
          else
            failed_rollback=1
          fi
        fi
        cp -p -- "$work/old.yml" "$work/restore-original.yml" &&
          mv -f -- "$work/restore-original.yml" "$PANEL_FILE" || failed_rollback=1
      else
        if ((start_attempted)); then
          panel_compose "$PANEL_FILE" "$PANEL_ENV" rm -s -f backend frontend >/dev/null 2>&1 || failed_rollback=1
        fi
        if ((failed_rollback == 0)); then
          rm -f -- "$PANEL_FILE" "$PANEL_ENV" || failed_rollback=1
        fi
      fi
    fi
    if ((failed_rollback)); then
      echo "❌ 自动回滚未完成，核对旧配置、镜像记录和运行状态：$work" >&2
    elif [[ -n $work ]]; then
      if [[ -f $work/rollback-tags && ( $rc == 0 || $start_attempted == 0 ) ]]; then
        while IFS= read -r tag; do
          docker image rm "$tag" >/dev/null 2>&1 || true
        done < "$work/rollback-tags"
      fi
      rm -rf -- "$work"
    fi
    rmdir -- "$PANEL_DIR/.flux-panel.lock" 2>/dev/null || true
    exit "$rc"
  }
  trap panel_finish EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  work=$(mktemp -d "$PANEL_DIR/.flux-panel.XXXXXX") || exit 1
  if [[ $mode == uninstall ]]; then
    panel_identify_existing "$work" || exit 1
    local confirm
    read -r -p "确认移除项目 $PANEL_PROJECT 的面板容器？保留卷和配置 (y/N): " confirm || exit 1
    [[ $confirm == [yY] ]] || exit 0
    # 再核验一次，且使用已核验的不可变容器 ID，绝不 down 其他服务、删除卷或镜像。
    panel_identify_existing "$work" && panel_validate_model "$PANEL_FILE" "$PANEL_ENV" "$work/model.json" &&
      panel_remove_containers "$work" || exit 1
    echo '✅ 已移除面板容器，保留两个命名卷、项目配置和镜像。'
    exit 0
  fi
  if [[ $mode == install ]]; then
    [[ ! -e $PANEL_FILE && ! -e $PANEL_ENV ]] || { panel_error '当前目录已有配置，请使用更新或在空目录安装。'; exit 1; }
    PANEL_PROJECT=${FLUX_PANEL_PROJECT:-flux-panel}
    [[ $PANEL_PROJECT =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { panel_error 'Compose 项目名格式无效。'; exit 1; }
    panel_new_owner || exit 1
    variant=$(panel_pick_variant) || exit 1
    panel_new_env "$work/new.env" || exit 1
  else
    panel_identify_existing "$work" || exit 1
    cp -p -- "$PANEL_FILE" "$work/old.yml" && cp -- "$PANEL_ENV" "$work/old.env" &&
      chmod 600 "$work/old.env" || exit 1
    panel_validate_model "$PANEL_FILE" "$PANEL_ENV" "$work/old-model.json" &&
      panel_migrate_env "$work" || exit 1
    variant=$(<"$work/variant")
    cp -- "$work/containers.json" "$work/old-containers.json" || exit 1
    panel_save_images "$work" &&
      panel_validate_model "$work/rollback.yml" "$work/old.env" "$work/rollback-model.json" &&
      panel_validate_migration "$work" rollback-model.json old ||
      { panel_error '未能验证回滚配置及旧镜像，未执行变更。'; exit 1; }
  fi
  panel_download_compose "$work/new.yml" "$variant" &&
    panel_validate_model "$work/new.yml" "$work/new.env" "$work/new-model.json" || exit 1
  if [[ $mode == update ]]; then
    panel_validate_migration "$work" new-model.json new || exit 1
  fi
  echo '⬇️ 预拉取镜像，现有面板继续运行。'
  panel_compose "$work/new.yml" "$work/new.env" pull --quiet >/dev/null 2>&1 ||
    { panel_error '拉取镜像失败，现有容器未修改。'; exit 1; }
  if [[ $mode == update ]]; then
    local saved_project="$PANEL_PROJECT"
    panel_identify_existing "$work" && [[ $saved_project == "$PANEL_PROJECT" ]] || exit 1
    cmp -s "$PANEL_FILE" "$work/old.yml" && cmp -s "$PANEL_ENV" "$work/old.env" &&
      panel_same_containers "$work" ||
      { panel_error '拉取镜像期间部署文件已变化，请重新执行。'; exit 1; }
  else
    panel_new_owner && [[ ! -e $PANEL_FILE && ! -L $PANEL_FILE && ! -e $PANEL_ENV && ! -L $PANEL_ENV ]] || exit 1
  fi
  changed=1
  chmod 644 "$work/new.yml" && mv -f -- "$work/new.yml" "$PANEL_FILE" &&
    chmod 600 "$work/new.env" && mv -f -- "$work/new.env" "$PANEL_ENV" || exit 1
  start_attempted=1
  panel_compose "$PANEL_FILE" "$PANEL_ENV" up -d --no-build --pull never --wait \
    --wait-timeout "$PANEL_HEALTH_TIMEOUT" >/dev/null 2>&1 ||
    { panel_error '面板未通过启动或健康检查。'; exit 1; }
  if [[ -f $work/rotated ]]; then
    echo '⚠️ 已轮换旧 JWT 密钥，请重新登录面板。'
  fi
  if [[ $mode == install ]]; then
    echo '✅ 面板已就绪。新装默认只绑定回环；外部用户请配置 TLS 反代，外部节点直连须显式设置可信监听地址。'
  else
    echo '✅ 面板更新完成，两个服务均通过健康检查。'
  fi
)

main() {
  local choice
  while true; do
    printf '%s\n' '1. 安装面板' '2. 更新面板' '3. 卸载面板（保留数据）' '4. 退出'
    read -r -p '请输入选项 (1-4): ' choice || return 1
    case "$choice" in
      1) panel_transaction install; return $? ;;
      2) panel_transaction update; return $? ;;
      3) panel_transaction uninstall; return $? ;;
      4) return 0 ;;
      *) echo '❌ 无效选项，请输入 1-4。' ;;
    esac
  done
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  main "$@"
fi
