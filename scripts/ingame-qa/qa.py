#!/usr/bin/env python3
"""In-game QA harness: a local Paper server + the Fabric dev client driven through MC-Inspector,
against a local schemat.io (https://schemati.test) seeded by `php artisan qa:connector-fixtures`.

    qa.py fixtures [--rich-text] [--quickshare-failure] | fixtures --teardown
    qa.py env                                  key names in the fixture env file (never values)
    qa.py server start|stop|status|log         Paper + WorldEdit + this checkout's plugin
    qa.py server cmd "<command>"               console command over RCON, prints the reply
    qa.py client start [--token KEY]           dev client + MC-Inspector, signed in, joined
    qa.py client stop
    qa.py signin KEY                           sign the running client in with a fixture token
    qa.py call TOOL [JSON]                     any MC-Inspector tool
    qa.py click LABEL [--window W] [--hover]   click an ImGui item by label
    qa.py shot NAME                            screenshot to build/ingame-qa/shots/NAME.png
    qa.py run SCENARIO.json [...]              run scenarios, write a report + screenshots

Everything lands in build/ingame-qa/ of the checkout (see README.md). Secrets (tokens, the RCON
password) are written to files with mode 600 and never printed.
"""
import argparse
import base64
import datetime
import json
import os
import pathlib
import re
import secrets
import signal
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
# QA_CHECKOUT builds the client from another checkout (e.g. a worktree under review); output stays here.
ROOT = pathlib.Path(os.environ.get('QA_CHECKOUT', HERE.parents[1])).resolve()
OUT = HERE.parents[1] / 'build/ingame-qa'
ENV_FILE = pathlib.Path(os.environ.get('QA_ENV_FILE', pathlib.Path.home() / 'Documents/code/schemati/storage/app/qa/connector.env'))
CONTAINER = os.environ.get('QA_LARAVEL_CONTAINER', 'schemati-laravel.test-1')
INSPECTOR_ROOT = pathlib.Path(os.environ.get('MC_INSPECTOR_ROOT', pathlib.Path.home() / 'Documents/code/MC-Inspector'))
INSPECTOR_PORT = int(os.environ.get('SCHEMATIO_INSPECTOR_PORT', '38271'))
VERSION = os.environ.get('QA_MC_VERSION', '1.21.11')
SERVER_PORT = int(os.environ.get('QA_SERVER_PORT', '25577'))
RCON_PORT = int(os.environ.get('QA_RCON_PORT', '25578'))
SECRET_KEYS = re.compile(r'TOKEN|PASSWORD|SECRET')


# ---- fixture env --------------------------------------------------------------------------------

def env_values():
    if not ENV_FILE.is_file():
        sys.exit(f'No fixture env at {ENV_FILE}. Run: qa.py fixtures (or set QA_ENV_FILE).')
    return {k.strip(): v.strip() for k, v in (l.split('=', 1) for l in ENV_FILE.read_text().splitlines() if '=' in l)}


def substitute(value, env):
    """${KEY} from the env file in scenario strings; secret keys are refused so they never reach a report."""
    if isinstance(value, str):
        def repl(m):
            key = m.group(1)
            if SECRET_KEYS.search(key):
                raise ValueError(f'scenario may not reference secret {key}')
            return env.get(key, m.group(0))
        return re.sub(r'\$\{([A-Z0-9_]+)\}', repl, value)
    if isinstance(value, list):
        return [substitute(v, env) for v in value]
    if isinstance(value, dict):
        return {k: substitute(v, env) for k, v in value.items()}
    return value


def write_private(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(text)
    os.chmod(path, 0o600)


# ---- MC-Inspector -------------------------------------------------------------------------------

def mcp(tool, args=None, timeout=120):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                       'params': {'name': tool, 'arguments': args or {}}}).encode()
    req = urllib.request.Request(f'http://127.0.0.1:{INSPECTOR_PORT}/mcp', data=body,
                                 headers={'Content-Type': 'application/json'})
    res = json.load(urllib.request.urlopen(req, timeout=timeout))
    if 'error' in res:
        raise RuntimeError(f'{tool}: {res["error"]}')
    return res['result']


def mcp_text(result):
    return ''.join(c.get('text', '') for c in result.get('content', []) if c.get('type') == 'text')


def mcp_json(tool, args=None):
    r = mcp(tool, args)
    text = mcp_text(r)
    if r.get('isError'):
        raise RuntimeError(text)
    try:
        return json.loads(text)
    except ValueError:
        return text


def js(code):
    """exec_script; returns its `result`. Never call ImGui natively from here (it asserts outside a frame)."""
    out = mcp_json('exec_script', {'code': code})
    return out.get('result') if isinstance(out, dict) else out


def inspector_up():
    try:
        urllib.request.urlopen(f'http://127.0.0.1:{INSPECTOR_PORT}/health', timeout=1)
        return True
    except (urllib.error.URLError, OSError):
        return False


def screenshot(path, scale=0.5):
    r = mcp('screenshot', {'scale': scale})
    img = next(c for c in r['content'] if c.get('type') == 'image')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(base64.b64decode(img['data']))
    return path


def wait_ticks(n):
    mcp('wait_ticks', {'ticks': n})


# ---- Paper server -------------------------------------------------------------------------------

def server_dir():
    return OUT / f'server-{VERSION}'


def rcon_password():
    p = OUT / 'rcon.secret'
    if not p.is_file():
        write_private(p, secrets.token_hex(16))
    return p.read_text().strip()


def rcon(command, timeout=10):
    def packet(req_id, kind, body):
        data = struct.pack('<ii', req_id, kind) + body.encode() + b'\x00\x00'
        return struct.pack('<i', len(data)) + data

    def read(sock):
        size = struct.unpack('<i', recv_exact(sock, 4))[0]
        data = recv_exact(sock, size)
        req_id, kind = struct.unpack('<ii', data[:8])
        return req_id, data[8:-2].decode(errors='replace')

    def recv_exact(sock, n):
        buf = b''
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError('RCON closed the connection')
            buf += chunk
        return buf

    with socket.create_connection(('127.0.0.1', RCON_PORT), timeout=timeout) as s:
        s.sendall(packet(1, 3, rcon_password()))
        if read(s)[0] == -1:
            raise PermissionError('RCON login refused (delete build/ingame-qa/rcon.secret and restart the server)')
        s.sendall(packet(2, 2, command))
        return re.sub('§.', '', read(s)[1])


def server_running():
    try:
        rcon('list', timeout=2)
        return True
    except OSError:
        return False


def write_server_config(env):
    d = server_dir()
    d.mkdir(parents=True, exist_ok=True)
    props = d / 'server.properties'
    existing = {}
    if props.is_file():
        existing = dict(l.split('=', 1) for l in props.read_text().splitlines() if '=' in l and not l.startswith('#'))
    existing.update({
        # Offline mode: the dev client has no Mojang session. Loopback only.
        'online-mode': 'false', 'server-ip': '127.0.0.1', 'server-port': str(SERVER_PORT),
        'enable-rcon': 'true', 'rcon.port': str(RCON_PORT), 'rcon.password': rcon_password(),
        'broadcast-rcon-to-ops': 'false',
        # Calm, well-lit, flat world for screenshots.
        'difficulty': 'peaceful', 'gamemode': 'creative', 'force-gamemode': 'true',
        'level-type': 'minecraft\\:flat', 'generate-structures': 'false', 'spawn-protection': '0',
        'spawn-monsters': 'false', 'spawn-animals': 'false', 'motd': 'Schematio in-game QA',
    })
    write_private(props, ''.join(f'{k}={v}\n' for k, v in sorted(existing.items())))
    plugin = d / 'plugins/SchematioConnector/config.yml'
    write_private(plugin, f'api-endpoint: "{env["SCHEMATIO_BASE_URL"]}/api/v1"\n'
                          f'community-token: "{env.get("PLUGIN_TOKEN", "")}"\n'
                          'trust-all-certificates: true\n')


def server_start(args):
    if server_running():
        print('server already running'); return
    env = env_values()
    write_server_config(env)
    java = '25' if VERSION.startswith('26') or VERSION == '1.21.11' else '21'
    log = (OUT / 'server.log').open('w')
    p = subprocess.Popen(['./gradlew', ':bukkit:runServer', f'-PpaperRunVersion={VERSION}', f'-PpaperRunJava={java}',
                          f'-PpaperRunDir={server_dir()}', '--console=plain'],
                         cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    (OUT / 'server.pid').write_text(str(p.pid))
    deadline = time.time() + 600
    while time.time() < deadline:
        if p.poll() is not None:
            sys.exit(f'server exited ({p.returncode}); see {OUT / "server.log"}')
        if server_running():
            break
        time.sleep(3)
    else:
        sys.exit(f'server did not answer RCON in 10 min; see {OUT / "server.log"}')
    for cmd in ('gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule doMobSpawning false',
                'time set day', 'weather clear'):
        rcon(cmd)
    print(f'server up on 127.0.0.1:{SERVER_PORT} ({server_dir()})')


def server_stop(args):
    if server_running():
        try:
            rcon('stop')
        except OSError:
            pass
    pid_file = OUT / 'server.pid'
    if pid_file.is_file():
        pid = int(pid_file.read_text())
        for _ in range(60):
            try:
                os.kill(pid, 0)
            except OSError:
                break
            time.sleep(1)
        else:
            os.killpg(pid, signal.SIGTERM)
        pid_file.unlink()
    print('server stopped')


# ---- dev client ---------------------------------------------------------------------------------

def client_start(args):
    env = env_values()
    if not inspector_up():
        log = (OUT / 'client.log')
        log.parent.mkdir(parents=True, exist_ok=True)
        cmd = ['./gradlew', f':fabric:{VERSION}:runClient', '-I', str(HERE.parent / 'client-smoke.init.gradle'),
               '-Dschematio.smoke.interactive=true', '-Dschematio.smoke.client-group=ingame-qa',
               f'-Dschematio.inspector.root={INSPECTOR_ROOT}', f'-Dschematio.inspector.port={INSPECTOR_PORT}',
               f'-Dschematio.smoke.endpoint={env["SCHEMATIO_BASE_URL"]}/api/v1', '-Dschematio.smoke.trust-local=true',
               f'-Dschematio.smoke.username={args.user or env.get(args.token.replace("MOD_TOKEN_", "") + "_NAME", "QaPlayer")}',
               '--console=plain']
        p = subprocess.Popen(cmd, cwd=ROOT, stdout=log.open('w'), stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL, start_new_session=True)
        (OUT / 'client.pid').write_text(str(p.pid))
        deadline = time.time() + 900
        while not inspector_up():
            if p.poll() is not None or time.time() > deadline:
                sys.exit(f'client did not start; see {log}')
            time.sleep(3)
    for _ in range(90):
        if mcp_json('get_state').get('screen') == 'TitleScreen':
            break
        time.sleep(2)
    # Quiet, predictable frames: no sound, GUI scale 3, fixed window, no pause on focus loss.
    js('mc.options.guiScale().set(java.lang.Integer.valueOf(3)); mc.options.pauseOnLostFocus = false;'
       'mc.options.getSoundSourceOptionInstance(Packages.net.minecraft.sounds.SoundSource.MASTER).set(java.lang.Double.valueOf(0.0));'
       'mc.options.save(); "ok"')
    js('Packages.org.lwjgl.glfw.GLFW.glfwSetWindowSize(%s, 1440, 880); "ok"' % window_handle_js())
    signin(args.token)
    if not args.no_join:
        mcp('connect_server', {'address': f'127.0.0.1:{SERVER_PORT}'})
        for _ in range(60):
            if mcp_json('get_state').get('inWorld'):
                break
            time.sleep(2)
        else:
            sys.exit('client did not join the server')
        wait_ticks(40)
        js('mc.getTutorial().setStep(Packages.net.minecraft.client.tutorial.TutorialSteps.NONE); mc.getToastManager().clear(); "ok"')
        hover_in_place()
    print('client ready')


def window_handle_js():
    newer = tuple(int(p) for p in VERSION.split('.')) >= (1, 21, 9)
    return 'mc.getWindow().handle()' if newer else 'mc.getWindow().getWindow()'


def hover_in_place():
    """Fly so screenshots never catch the player falling."""
    js('var a = mc.player.getAbilities(); a.flying = true; mc.player.onUpdateAbilities(); mc.player.setDeltaMovement(0,0,0); "ok"')


def client_stop(args):
    try:
        js('mc.stop(); "ok"')
    except Exception:
        pass
    pid_file = OUT / 'client.pid'
    if pid_file.is_file():
        pid = int(pid_file.read_text())
        time.sleep(5)
        try:
            os.killpg(pid, signal.SIGTERM)
        except OSError:
            pass
        pid_file.unlink()
    print('client stopped')


def signin(key):
    """Point the mod's session at a fixture token (dev only: the client has no Mojang session to exchange)."""
    token = env_values()[key]
    if not re.fullmatch(r'[A-Za-z0-9._-]+', token):
        sys.exit(f'{key} does not look like a token')
    code = '''
var mod = Packages.io.schemat.connector.fabric.client.SchematioClientMod.Companion.getInstance();
var s = Packages.io.schemat.connector.core.modapi.PlayerSession.Companion.fromToken("%s");
var am = mod.getAuthManager();
var f = am.getClass().getDeclaredField("session"); f.setAccessible(true); f.set(am, s);
var e = am.getClass().getDeclaredField("authError"); e.setAccessible(true); e.set(am, null);
var svc = mod.getServices();
var fn = new Packages.kotlin.jvm.functions.Function2({ invoke: function (scope, cont) { return svc.refreshMe(true, cont); } });
Packages.kotlinx.coroutines.BuildersKt.launch(svc.getScope(), Packages.kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
    Packages.kotlinx.coroutines.CoroutineStart.DEFAULT, fn);
"" + am.isAuthenticated();
''' % token
    ok = js(code)
    print(f'signed in as {key}: {ok}')


# ---- scenarios ----------------------------------------------------------------------------------

def open_panel(panel_id):
    js('mc.setScreen(null); Packages.dev.harrison.panellib.PanelLib.INSTANCE.api().panel("%s").open(); "ok"' % panel_id)


def find_item(label, window=None):
    items = mcp_json('imgui_items', {'window': window} if window else {})['items']
    if label.startswith('##'):
        return next((i for i in items if i['rawLabel'].endswith(label)), None)
    q = label.lower()
    for pick in (lambda i: i['label'].lower() == q or i['rawLabel'] == label,
                 lambda i: i['label'].lower().startswith(q), lambda i: q in i['label'].lower()):
        hit = [i for i in items if pick(i)]
        if hit:
            return hit[0]
    return None


def chat_lines(pattern):
    """Chat lines matching [pattern], newest first (reads the chat HUD; chat is not in the game log)."""
    text = js('var c = mc.gui.getChat(); var f = c.getClass().getDeclaredField("allMessages"); f.setAccessible(true);'
              'var l = f.get(c); var out = []; for (var i = 0; i < l.size(); i++) out.push(l.get(i).content().getString());'
              'out.join("\\n")') or ''
    return [l for l in text.split('\n') if re.search(pattern, l)]


def run_step(step, run_dir, env):
    """One scenario step -> (ok, detail). Steps: see README.md."""
    step = substitute(step, env)
    if 'signin' in step:
        if not step['signin'].startswith('MOD_TOKEN_'):
            raise ValueError('signin takes a MOD_TOKEN_* key name')
        signin(step['signin'])
        wait_ticks(20)
        return True, step['signin']
    if 'close_panels' in step:
        js('var api = Packages.dev.harrison.panellib.PanelLib.INSTANCE.api(); var mods = api.getMods();'
           'for (var i = 0; i < mods.size(); i++) { var ps = mods.get(i).getPanels();'
           ' for (var j = 0; j < ps.size(); j++) if (ps.get(j).isOpen()) ps.get(j).close(); } "ok"')
        wait_ticks(5)
        return True, 'all panels closed'
    if 'open_panel' in step:
        open_panel(step['open_panel']); wait_ticks(step.get('wait', 20))
        return True, step['open_panel']
    if 'click' in step:
        out = mcp_json('imgui_click', {k: v for k, v in (('label', step['click']), ('window', step.get('window')),
                                                         ('index', step.get('index')), ('hover', step.get('hover'))) if v is not None})
        wait_ticks(step.get('wait', 10))
        return True, f'{out["item"]["window"]} / {out["item"]["label"]}'
    if 'expect_item' in step:
        hit = find_item(step['expect_item'], step.get('window'))
        want = step.get('present', True)
        return (hit is not None) == want, (f'{hit["window"]} / {hit["label"]}' if hit else 'not drawn')
    if 'expect_js' in step:
        got = js(step['expect_js'])
        if isinstance(got, str) and not isinstance(step['equals'], str):
            try:
                got = json.loads(got)  # exec_script stringifies booleans and numbers
            except ValueError:
                pass
        return got == step['equals'], f'{got!r}'
    if 'command' in step:
        # Player chat/command; with expect_chat, wait for a NEW chat line matching the regex.
        pattern = step.get('expect_chat')
        before = len(chat_lines(pattern)) if pattern else 0
        mcp('run_command', {'command': step['command']})
        if not pattern:
            wait_ticks(step.get('wait', 20)); return True, step['command']
        deadline = time.time() + step.get('timeout', 30)
        while time.time() < deadline:
            lines = chat_lines(pattern)
            if len(lines) > before:
                return True, lines[0][:160]
            time.sleep(1)
        return False, f'no new chat line matching {pattern!r}'
    if 'server' in step:
        return True, rcon(step['server']).strip()[:200]
    if 'tool' in step:
        out = mcp(step['tool'], step.get('args', {}))
        return not out.get('isError'), mcp_text(out)[:200]
    if 'wait' in step:
        wait_ticks(step['wait']); return True, f'{step["wait"]} ticks'
    if 'shot' in step:
        if step.get('hide_hud'):
            js('mc.options.hideGui = true; "ok"'); wait_ticks(2)
        path = screenshot(run_dir / f'{step["shot"]}.png', step.get('scale', 0.5))
        if step.get('hide_hud'):
            js('mc.options.hideGui = false; "ok"')
        return True, path.name
    raise ValueError(f'unknown step {sorted(step)}')


def run_scenarios(args):
    env = env_values()
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    failures = 0
    for path in args.scenarios:
        scenario = json.loads(pathlib.Path(path).read_text())
        name = scenario.get('name', pathlib.Path(path).stem)
        run_dir = OUT / 'runs' / f'{stamp}-{name}'
        run_dir.mkdir(parents=True, exist_ok=True)
        rows = []
        for i, step in enumerate(scenario['steps'], 1):
            title = step.get('check') or step.get('title') or next(iter(k for k in step if k not in ('check', 'title')))
            try:
                ok, detail = run_step({k: v for k, v in step.items() if k not in ('check', 'title')}, run_dir, env)
            except Exception as e:  # report and keep going: one broken step should not hide the rest
                ok, detail = False, f'{type(e).__name__}: {e}'
            detail = ' '.join(str(detail).split())
            rows.append((i, title, ok, detail, 'check' in step))
            print(f'{"PASS" if ok else "FAIL"}  {i:>2}  {title}  {detail}')
            if not ok and step.get('stop_on_fail', True) and 'check' not in step:
                break
        failed = [r for r in rows if not r[2]]
        failures += len(failed)
        lines = [f'# {name}', '', scenario.get('description', ''), '',
                 f'Run {stamp}, Minecraft {VERSION}, {len(rows) - len(failed)}/{len(rows)} steps passed.', '',
                 '| # | Step | Result | Detail |', '|---|---|---|---|']
        lines += [f'| {i} | {t} | {"PASS" if ok else "**FAIL**"} | {d.replace("|", "/")} |' for i, t, ok, d, _ in rows]
        shots = sorted(p.name for p in run_dir.glob('*.png'))
        if shots:
            lines += ['', '## Screenshots', ''] + [f'![{s}]({s})' for s in shots]
        (run_dir / 'report.md').write_text('\n'.join(lines) + '\n')
        print(f'report: {run_dir / "report.md"}')
    sys.exit(1 if failures else 0)


# ---- CLI ----------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    fx = sub.add_parser('fixtures'); fx.add_argument('--rich-text', action='store_true')
    fx.add_argument('--quickshare-failure', action='store_true'); fx.add_argument('--teardown', action='store_true')
    sub.add_parser('env')
    sv = sub.add_parser('server'); sv.add_argument('action', choices=['start', 'stop', 'status', 'log', 'cmd'])
    sv.add_argument('command', nargs='?')
    cl = sub.add_parser('client'); cl.add_argument('action', choices=['start', 'stop'])
    cl.add_argument('--token', default='MOD_TOKEN_MEMBER'); cl.add_argument('--user')
    cl.add_argument('--no-join', action='store_true')
    si = sub.add_parser('signin'); si.add_argument('key')
    ca = sub.add_parser('call'); ca.add_argument('tool'); ca.add_argument('args', nargs='?', default='{}')
    ck = sub.add_parser('click'); ck.add_argument('label'); ck.add_argument('--window'); ck.add_argument('--hover', action='store_true')
    sh = sub.add_parser('shot'); sh.add_argument('name')
    rn = sub.add_parser('run'); rn.add_argument('scenarios', nargs='+')
    a = ap.parse_args()

    if a.cmd == 'fixtures':
        flags = ['--teardown'] if a.teardown else [f for f, on in (('--rich-text', a.rich_text),
                                                                   ('--quickshare-failure', a.quickshare_failure)) if on]
        r = subprocess.run(['docker', 'exec', CONTAINER, 'php', 'artisan', 'qa:connector-fixtures', *flags],
                           capture_output=True, text=True)
        # The command prints names, not tokens; still mask anything token-shaped.
        print(re.sub(r'eyJ[\w.-]+', '<token>', r.stdout + r.stderr).strip())
        sys.exit(r.returncode)
    elif a.cmd == 'env':
        print(' '.join(sorted(env_values())))
        print(f'({ENV_FILE})')
    elif a.cmd == 'server':
        if a.action == 'start': server_start(a)
        elif a.action == 'stop': server_stop(a)
        elif a.action == 'status': print('running' if server_running() else 'stopped')
        elif a.action == 'log': print(''.join((OUT / 'server.log').read_text().splitlines(True)[-40:]))
        else: print(rcon(a.command or 'list'))
    elif a.cmd == 'client':
        client_start(a) if a.action == 'start' else client_stop(a)
    elif a.cmd == 'signin':
        signin(a.key)
    elif a.cmd == 'call':
        r = mcp(a.tool, json.loads(a.args))
        for c in r.get('content', []):
            if c.get('type') == 'image':
                print(screenshot(OUT / 'shots' / f'{a.tool}-{int(time.time())}.png'))
            else:
                print(c.get('text', '')[:6000])
        sys.exit(1 if r.get('isError') else 0)
    elif a.cmd == 'click':
        print(json.dumps(mcp_json('imgui_click', {k: v for k, v in (('label', a.label), ('window', a.window),
                                                                     ('hover', a.hover or None)) if v is not None}), indent=1))
    elif a.cmd == 'shot':
        print(screenshot(OUT / 'shots' / f'{a.name}.png'))
    elif a.cmd == 'run':
        run_scenarios(a)


if __name__ == '__main__':
    main()
