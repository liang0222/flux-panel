"""Exercise installers in temporary directories. Only Compose config reaches the real Docker CLI."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DOCKER = shutil.which('docker')
FIXTURE_CLI = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys

args = sys.argv[1:]
command = Path(sys.argv[0]).name
base = Path(os.environ['FIXTURE'])
state = json.loads((base / 'fixture.json').read_text())

def record(event):
    with (base / 'events').open('a') as out:
        out.write(event + '\n')

def model(config, output=None):
    result = subprocess.run([os.environ['REAL_DOCKER'], *config, 'config', '--format', 'json'], capture_output=True)
    if result.returncode != 0:
        raise SystemExit(result.returncode)
    if output:
        (base / output).write_bytes(result.stdout)
    return json.loads(result.stdout)

if command == 'systemctl':
    action = args[0]
    active, enabled = base / 'active', base / 'enabled'
    record('systemctl:' + action)
    if action == 'is-active':
        sys.exit(not active.exists())
    if action == 'is-enabled':
        sys.exit(not enabled.exists())
    if action == 'show':
        print(123)
    elif action == 'stop':
        active.unlink(missing_ok=True)
    elif action == 'enable':
        enabled.touch()
    elif action == 'disable':
        enabled.unlink(missing_ok=True)
    elif action == 'start':
        if state.get('agent_health_fails') and (base / 'agent/flux_agent').read_text() == 'new binary':
            sys.exit(0)  # allow start, then fail the health probe
        active.touch()
    elif action != 'daemon-reload':
        sys.exit(42)
    sys.exit(0)
if command == 'curl':
    destination = args[args.index('-o') + 1]
    if destination != '/dev/null':
        if state.get('curl_failure'):
            sys.exit(22)
        data = ('bad bytes' if state.get('bad_digest') else 'new binary').encode()
        Path(destination).write_bytes(data)
    record('curl')
    sys.exit(0)

assert command == 'docker'
containers = state.get('containers', [])
if args[:2] == ['compose', 'version']:
    sys.exit(0)
if args[0] == 'compose':
    if 'config' in args:
        assert args[args.index('config') + 1:] in (['--format', 'json'], ['--quiet'])
        sys.exit(subprocess.run([os.environ['REAL_DOCKER'], *args]).returncode)
    for action in ('up', 'pull', 'rm', 'stop', 'build', 'ps'):
        if action in args:
            break
    else:
        sys.exit(42)
    record('compose:' + action)
    if action == 'pull':
        sys.exit(17 if state.get('pull_failure') else 0)
    if action == 'up':
        assert '--wait' in args and '--no-build' in args
        if args[1].startswith('--project-'):
            assert args[args.index('--pull') + 1] == 'never'
            current = model(args[:args.index('up')])
            rollback = current['services']['backend']['image'].startswith('flux-panel-rollback/')
            (base / ('rollback-model.json' if rollback else 'attempted-model.json')).write_text(json.dumps(current))
            sys.exit(17 if state.get('up_failure') and (not rollback or state.get('rollback_failure')) else 0)
    sys.exit(0)
if args[:2] == ['container', 'inspect']:
    if not containers:
        sys.exit(1)
    # Docker's health timestamps may change between preflight and cutover.
    for c in containers:
        c['State']['Health'] = {'pid': os.getpid()}
    print(json.dumps(containers))
elif args[0] == 'ps':
    assert args[1] == '-aq'
    if state.get('docker_failure'):
        sys.exit(17)
    if any('volume=sqlite_data' == x for x in args):
        rows = [c for c in containers if c['Name'] == '/springboot-backend']
        if state.get('shared_sqlite'):
            print('f' * 12)
    else:
        rows = [c for c in containers if all(x.split('=', 2)[2] == c['Config']['Labels'].get(x.split('=', 2)[1])
                for x in args if x.startswith('label='))]
    for c in rows:
        print(c['Id'][:12])
elif args[:2] == ['volume', 'ls']:
    if state.get('old_volume'):
        print('sqlite_data')
elif args[:2] in (['image', 'tag'], ['image', 'rm']):
    record('image:' + args[1])
elif args[0] in ('stop', 'rm'):
    assert args[-2:] == [x['Id'] for x in containers]
    record('docker:' + args[0])
else:
    sys.exit(42)
'''


class Installers(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='flux-installer-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.state = self.base / 'fixture.json'
        self.cli = self.bin / 'docker'
        self.cli.write_text(FIXTURE_CLI)
        self.cli.chmod(0o700)
        for command in ('systemctl', 'curl'):
            (self.bin / command).symlink_to(self.cli)
        for command, text in [('tcpkill', 'exit 0'), ('sleep', 'exit 0'), ('uname', 'echo x86_64')]:
            stub = self.bin / command
            stub.write_text('#!/bin/bash\n' + text + '\n')
            stub.chmod(0o700)
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(('COMPOSE_', 'DOCKER_', 'FLUX_', 'JWT_SECRET'))}
        self.env.update(FIXTURE=str(self.base), REAL_DOCKER=DOCKER,
                        PATH=f'{self.bin}:{os.environ["PATH"]}', FLUX_PANEL_VARIANT='v4')
        self.set_state()

    def set_state(self, **values):
        self.state.write_text(json.dumps(values))

    def events(self):
        path = self.base / 'events'
        return path.read_text().splitlines() if path.exists() else []

    def exec_script(self, args, input_text, ok, **overrides):
        result = subprocess.run(['bash', *args], input=input_text, cwd=self.base,
                                env={**self.env, **overrides}, capture_output=True, text=True, timeout=30)
        # Output is deliberately not echoed on failures; it could contain a secret regression.
        self.assertEqual(result.returncode == 0, ok, f'exit {result.returncode}; events {self.events()}')
        for key in self.fixture_secrets():
            self.assertNotIn(key, result.stdout + result.stderr, 'fixture secret was echoed')
        return result

    @staticmethod
    def fixture_secrets():
        return ('fixture123456789', 'Aa1234567890%$fixture-test-material-00000', 'fixture-agent-secret-123')

    def panel(self, choice, ok=True, **env):
        return self.exec_script([str(ROOT / 'panel_install.sh')], choice, ok, **env)

    def agent(self, choice, ok=True, **env):
        # Source script, setting paths only inside the harness. Stub systemctl/tcpkill/curl via PATH.
        (self.base / 'agent').mkdir(exist_ok=True)
        (self.base / 'unit').mkdir(exist_ok=True)
        digest = hashlib.sha256(b'new binary').hexdigest()
        return self.exec_script(['-c', '''source "$1"; INSTALL_DIR="$FIXTURE/agent";
            SERVICE_DIR="$FIXTURE/unit"; main''', '_', str(ROOT / 'install.sh')],
            choice, ok, FLUX_AGENT_SHA256=digest, **env)

    def legacy_panel(self, dual=False, strong=False, wrong_project=False, **state):
        name = 'v6' if dual else 'v4'
        template = (ROOT / f'docker-compose-{name}.yml').read_text()
        template = template.replace('${JWT_SECRET:?请先在 .env 设置随机 JWT_SECRET（openssl rand -hex 32）}', '${JWT_SECRET}')
        for role, default in [('BACKEND', 6365), ('FRONTEND', 6366)]:
            template = template.replace(f'${{{role}_BIND_IP-127.0.0.1}}:', '0.0.0.0:' if dual else '')
            template = template.replace(f'${{{role}_PORT:-{default}}}', f'${{{role}_PORT}}')
            template = template.replace(f'${{{role}_BIND_IPV6:-::1}}', '::')
        (self.base / 'docker-compose.yml').write_text(template)
        secret = self.fixture_secrets()[int(strong)]
        dotenv = f"JWT_SECRET='{secret}'\nBACKEND_PORT=16365\nFRONTEND_PORT=16366\nPANEL_ALLOWED_ORIGINS=https://example.test\n"
        (self.base / '.env').write_text(dotenv)
        containers = []
        for i, role, container_name, target, published in [(0, 'backend', 'springboot-backend', 6365, 16365),
                                                          (1, 'frontend', 'vite-frontend', 80, 16366)]:
            bindings = [{'HostIp': '0.0.0.0' if dual else '', 'HostPort': str(published)}]
            if dual:
                bindings += [{'HostIp': '::', 'HostPort': str(published)}]
            containers += [{
                'Id': str(i + 1) * 64, 'Name': '/' + container_name, 'Image': 'sha256:' + str(i + 3) * 64,
                'State': {'Running': True}, 'HostConfig': {'PortBindings': {f'{target}/tcp': bindings}},
                'Config': {'Env': ['JWT_SECRET=' + secret] if role == 'backend' else [], 'Labels': {
                    'com.docker.compose.project': 'historical-panel', 'com.docker.compose.service': role,
                    'com.docker.compose.project.config_files': str(self.base / 'docker-compose.yml'),
                    'com.docker.compose.project.working_dir': '/different/project' if wrong_project else str(self.base),
                    'com.docker.compose.oneoff': 'False',
                }},
                'Mounts': [{'Name': name, 'Destination': dest, 'Type': 'volume'}
                           for name, dest in [('sqlite_data', '/app/data'), ('backend_logs', '/app/logs')]]
                          if role == 'backend' else [],
            }]
        self.set_state(containers=containers, **state)
        return template, dotenv

    def legacy_agent(self, **state):
        for name in ('agent', 'unit'):
            (self.base / name).mkdir()
        (self.base / 'agent/flux_agent').write_text('old binary')
        (self.base / 'agent/config.json').write_text('{"config":true}')
        (self.base / 'agent/gost.json').write_text('{"historical":true}')
        (self.base / 'unit/flux_agent.service').write_text('[Service]\nExecStart=/unused/flux_agent\n')
        (self.base / 'active').touch()
        (self.base / 'enabled').touch()
        self.set_state(**state)

    def model(self, name):
        return json.loads((self.base / name).read_text())

    def test_compose_templates(self):
        for file in ('docker-compose-v4.yml', 'docker-compose-v6.yml',
                     'deploy/docker-compose.yml', 'deploy/docker-compose.registry.yml'):
            with self.subTest(file=file):
                cmd = [DOCKER, 'compose', '-p', 'test-config', '--env-file', '/dev/null', '-f', file, 'config']
                env = {**self.env, 'JWT_SECRET': '', 'PANEL_ALLOWED_ORIGINS': 'https://example.test'}
                missing = subprocess.run([*cmd, '--quiet'], cwd=ROOT, env=env, capture_output=True)
                self.assertNotEqual(missing.returncode, 0)
                env['JWT_SECRET'] = self.fixture_secrets()[1]
                result = subprocess.run([*cmd, '--format', 'json'], cwd=ROOT, env=env, capture_output=True)
                self.assertEqual(result.returncode, 0)
                model = json.loads(result.stdout)
                binds = ['127.0.0.1', '::1'] if '-v6' in file else ['127.0.0.1']
                for role in ('backend', 'frontend'):
                    service = model['services'][role]
                    self.assertEqual([p['host_ip'] for p in service['ports']], binds)
                    self.assertIn('healthcheck', service)
                self.assertEqual(model['services']['backend']['environment']['PANEL_ALLOWED_ORIGINS'], env['PANEL_ALLOWED_ORIGINS'])
                self.assertEqual(model['services']['backend']['scale'], 1)
                self.assertEqual({key: v['name'] for key, v in model['volumes'].items()},
                                 {'sqlite_data': 'sqlite_data', 'backend_logs': 'backend_logs'})

    def test_panel_install_v4(self):
        self.panel('1\n\n\n')
        self.assertEqual((self.base / '.env').stat().st_mode & 0o777, 0o600)
        model = self.model('attempted-model.json')['services']
        self.assertTrue(len(model['backend']['environment']['JWT_SECRET']) == 64)
        self.assertEqual(model['backend']['ports'][0]['host_ip'], '127.0.0.1')

    def test_standalone_panel_templates_match_repository(self):
        standalone = self.base / 'panel_install.sh'
        shutil.copyfile(ROOT / standalone.name, standalone)
        for variant in ('v4', 'v6'):
            with self.subTest(variant=variant):
                generated = self.base / 'generated.yaml'
                self.exec_script(['-c', 'source "$1"; panel_download_compose "$2" "$3"',
                                  '_', str(standalone), str(generated), variant], '', True)
                self.assertEqual(generated.read_bytes(), (ROOT / f'docker-compose-{variant}.yml').read_bytes())
        self.assertEqual(self.events(), [])

    def test_rebuild_requires_configured_secret(self):
        for name in ('rebuild.sh', 'docker-compose.yml', '.env.example'):
            shutil.copyfile(ROOT / 'deploy' / name, self.base / name)
        self.exec_script(['rebuild.sh'], '', False)
        self.assertEqual((self.base / '.env').stat().st_mode & 0o777, 0o600)
        self.exec_script(['rebuild.sh'], '', False)
        self.assertEqual(self.events(), [])
        (self.base / '.env').write_text("JWT_SECRET='" + self.fixture_secrets()[1] + "'\n")
        self.exec_script(['rebuild.sh'], '', True)
        self.assertEqual(self.events(), ['compose:build', 'compose:up', 'compose:ps'])

    def test_panel_install_failure(self):
        self.set_state(up_failure=True)
        self.panel('1\n\n\n', ok=False)
        self.assertFalse((self.base / '.env').exists())
        self.assertFalse((self.base / 'docker-compose.yml').exists())
        self.assertEqual(self.events(), ['compose:pull', 'compose:up', 'compose:rm'])

    def test_panel_migrate_weak_v4(self):
        self.legacy_panel()
        result = self.panel('2\n')
        self.assertIn('重新登录', result.stdout)
        model = self.model('attempted-model.json')
        self.assertEqual(model['name'], 'historical-panel')
        backend = model['services']['backend']
        self.assertTrue(len(backend['environment']['JWT_SECRET']) == 64)
        self.assertEqual(backend['environment']['PANEL_ALLOWED_ORIGINS'], 'https://example.test')
        self.assertEqual([(p.get('host_ip', ''), p['published']) for p in backend['ports']], [('', '16365')])

    def test_panel_migrate_strong_v6(self):
        self.legacy_panel(dual=True, strong=True)
        result = self.panel('2\n')
        self.assertNotIn('重新登录', result.stdout)
        backend = self.model('attempted-model.json')['services']['backend']
        self.assertTrue(backend['environment']['JWT_SECRET'] == self.fixture_secrets()[1].replace('$', '$$'))
        self.assertEqual([(p['host_ip'], p['published']) for p in backend['ports']],
                         [('0.0.0.0', '16365'), ('::', '16365')])

    def test_panel_update_rollback(self):
        original, dotenv = self.legacy_panel(up_failure=True, strong=True)
        self.panel('2\n', ok=False)
        self.assertEqual((self.base / 'docker-compose.yml').read_text(), original)
        self.assertTrue((self.base / '.env').read_text() == dotenv)
        rollback = self.model('rollback-model.json')['services']['backend']
        self.assertTrue(rollback['environment']['JWT_SECRET'] == self.fixture_secrets()[1].replace('$', '$$'))
        self.assertTrue(rollback['image'].startswith('flux-panel-rollback/'))
        self.assertEqual(self.events().count('compose:up'), 2)
        self.assertEqual(self.events().count('image:rm'), 0)

    def test_panel_failed_rollback_keeps_backups(self):
        original, dotenv = self.legacy_panel(up_failure=True, rollback_failure=True)
        result = self.panel('2\n', ok=False)
        self.assertIn('自动回滚未完成', result.stderr)
        backups = list(self.base.glob('.flux-panel.*'))
        self.assertEqual(len(backups), 1)
        self.assertTrue((backups[0] / 'old.env').read_text() == dotenv)
        self.assertEqual((backups[0] / 'old.yml').read_text(), original)

    def test_panel_bad_template_checksum(self):
        self.panel('1\n\n\n', ok=False, FLUX_COMPOSE_SHA256_V4='0' * 64)
        self.assertTrue(self.events())
        self.assertTrue(all(event == 'curl' for event in self.events()))
        self.assertFalse((self.base / '.env').exists())

    def test_panel_pull_failure_keeps_deployment(self):
        original, dotenv = self.legacy_panel(pull_failure=True)
        self.panel('2\n', ok=False)
        self.assertEqual((self.base / 'docker-compose.yml').read_text(), original)
        self.assertTrue((self.base / '.env').read_text() == dotenv)
        self.assertNotIn('compose:up', self.events())

    def test_panel_uninstall_owned(self):
        original, dotenv = self.legacy_panel()
        self.panel('3\ny\n')
        self.assertEqual(self.events(), ['docker:stop', 'docker:rm'])
        self.assertTrue((self.base / '.env').read_text() == dotenv)
        self.assertEqual((self.base / 'docker-compose.yml').read_text(), original)

    def test_panel_rejects_wrong_dir_and_shared_volume(self):
        self.legacy_panel(wrong_project=True)
        self.panel('3\ny\n', ok=False)
        self.legacy_panel(shared_sqlite=True)
        self.panel('2\n', ok=False)
        self.assertEqual(self.events(), [])

    def test_agent_install_and_escape(self):
        secret = 'fixture-agent-secret-123' + r'\"'
        self.agent(f'1\n[::1]:6365\n{secret}\n')
        config = self.base / 'agent/config.json'
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertTrue(json.loads(config.read_text()) == {'addr': '[::1]:6365', 'secret': secret})
        self.assertTrue((self.base / 'enabled').exists())

    def test_agent_update_retains_configuration(self):
        self.legacy_agent()
        self.agent('2\n')
        self.assertEqual((self.base / 'agent/flux_agent').read_text(), 'new binary')
        self.assertEqual((self.base / 'agent/config.json').read_text(), '{"config":true}')
        self.assertEqual((self.base / 'agent/gost.json').read_text(), '{"historical":true}')

    def test_agent_bad_digest_keeps_service(self):
        self.legacy_agent(bad_digest=True)
        self.agent('2\n', ok=False)
        self.assertEqual((self.base / 'agent/flux_agent').read_text(), 'old binary')
        self.assertNotIn('systemctl:stop', self.events())

    def test_agent_health_failure_restores_service(self):
        self.legacy_agent(agent_health_fails=True)
        self.agent('2\n', ok=False)
        self.assertEqual((self.base / 'agent/flux_agent').read_text(), 'old binary')
        self.assertEqual((self.base / 'agent/config.json').read_text(), '{"config":true}')
        self.assertTrue((self.base / 'active').exists())
        self.assertFalse((self.base / 'unit/flux_agent.service.d/99-flux-agent-memory.conf').exists())
        self.assertEqual(self.events().count('systemctl:start'), 2)


if __name__ == '__main__':
    if not DOCKER:
        raise SystemExit('Docker CLI with Compose is required for config validation')
    unittest.main(verbosity=2)
