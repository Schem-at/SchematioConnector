#!/usr/bin/env python3
"""Exercise real Fabric/Paper/WorldEdit bridge packets with MC-Inspector.

Start scripts/BridgeBackend.java first. Uses only localhost, throwaway offline
players, and worlds under build/release-readiness. The backend fixture checks the
uploaded schematic contents. Real Schematio authentication is a separate check.
"""
import argparse
import base64
import importlib.util
import json
import pathlib
import shutil
import subprocess
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/release-readiness'
IPC = 'Packages.io.schemat.connector.fabric.client.ipc.'


def call(name, args=None):
    body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {'name': name, 'arguments': args or {}}}).encode()
    req = urllib.request.Request('http://127.0.0.1:38271/mcp', data=body, headers={'Content-Type': 'application/json'})
    result = json.load(urllib.request.urlopen(req, timeout=45))['result']
    if result.get('isError'):
        raise RuntimeError(result)
    if name == 'screenshot':
        return base64.b64decode(next(c['data'] for c in result['content'] if c['type'] == 'image'))
    text = next(c['text'] for c in result['content'] if c['type'] == 'text')
    try: return json.loads(text)
    except ValueError: return text


def js(code):
    return call('exec_script', {'code': code})['result']


def until(predicate, timeout=45):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last: return last
        except Exception as e: last = e
        time.sleep(.5)
    raise TimeoutError(str(last))


def health():
    return urllib.request.urlopen('http://127.0.0.1:38271/health', timeout=1).status == 200


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('versions', nargs='+')
    parser.add_argument('--inspector-root', type=pathlib.Path, required=True)
    parser.add_argument('--jdk21', type=pathlib.Path, required=True)
    parser.add_argument('--jdk25', type=pathlib.Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('server_smoke', ROOT / 'scripts/smoke-server.py')
    downloads = importlib.util.module_from_spec(spec); spec.loader.exec_module(downloads)
    records = []
    for version in args.versions:
        paper = '26.1.2' if version == '26.1' else version
        java = (args.jdk25 if version.startswith('26.') else args.jdk21) / 'bin/java'
        cache = next((OUT / 'servers').glob(f'paper-{paper}-*-worldedit'))
        run = OUT / 'bridge' / f'server-{version}'
        run.mkdir(parents=True, exist_ok=True)
        for name in ['server.jar', 'eula.txt']:
            shutil.copy2(cache / name, run / name)
        for name in ['libraries', 'cache', 'versions']:
            if (cache / name).is_dir(): shutil.copytree(cache / name, run / name, dirs_exist_ok=True)
        plugins = run / 'plugins'; plugins.mkdir(exist_ok=True)
        shutil.copy2(ROOT / 'bukkit/build/libs/SchematioConnector-Paper-1.3.3.jar', plugins)
        we_id = '2YDdVDmG' if version.startswith('1.21.') else 'F5ea2ov3'
        we = downloads.json_url(f'https://api.modrinth.com/v2/version/{we_id}')
        asset = next(f for f in we['files'] if f['primary'])
        for stale in plugins.glob('worldedit-*.jar'): stale.unlink()
        downloads.download(asset['url'], plugins / asset['filename'])
        cfg = plugins / 'SchematioConnector/config.yml'; cfg.parent.mkdir(exist_ok=True)
        cfg.write_text('api-endpoint: http://127.0.0.1:38272/api/v1\ncommunity-token: smoke.community.token\ndisabled-commands: []\n')
        (run / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
        server_log = run / 'console.log'
        client_log = OUT / f'bridge-client-{version}.log'
        server = client = None
        record = {'minecraft': version, 'paper': paper, 'worldedit': we['version_number'], 'passed': False, 'checks': []}
        with server_log.open('w') as slog, client_log.open('w') as clog:
            try:
                server = subprocess.Popen([str(java), '-XX:ActiveProcessorCount=2', '-Xms256M', '-Xmx1536M', '-jar', 'server.jar', 'nogui'], cwd=run, stdin=subprocess.PIPE, stdout=slog, stderr=subprocess.STDOUT, text=True)
                until(lambda: 'Done (' in server_log.read_text(), 120)
                assert 'WorldEdit integration enabled' in server_log.read_text()
                client = subprocess.Popen(['./gradlew', f':fabric:{version}:runClient', '-I', 'scripts/client-smoke.init.gradle', '-Dschematio.smoke.interactive=true', f'-Dschematio.inspector.root={args.inspector_root}', '-Dschematio.smoke.endpoint=http://127.0.0.1:38272/api/v1', '--console=plain'], cwd=ROOT, stdout=clog, stderr=subprocess.STDOUT)
                until(health, 180)
                call('packets_capture', {'paused': False, 'exclude': ['move_player|move_entity|chunk|light_update|keep_alive|time']})
                call('connect_server', {'address': '127.0.0.1:25576'})
                until(lambda: call('get_state')['inWorld'])
                until(lambda: js(f'String({IPC}ServerSession.INSTANCE.getTrust())') == 'VERIFIED')
                nonce = js(f'Packages.java.util.Arrays.toString({IPC}ServerSession.INSTANCE.getNonce())')
                record['checks'].append('signed attestation verified')
                name = call('get_state')['player']['name']
                server.stdin.write(f'op {name}\ngamemode creative {name}\ntp {name} 0 -63 0\nsetblock 0 -63 0 air\nsetblock 1 -63 0 air\n'); server.stdin.flush()
                call('wait_ticks', {'ticks': 30})
                call('run_command', {'command': '/schematio'})
                until(lambda: 'schematioconnector:browse' in call('get_state')['panellib']['openPanels'])
                record['checks'].append('server command opened client browser')
                js(f'''var cb = new JavaAdapter(Packages.kotlin.jvm.functions.Function2, {{invoke:function(state, detail) {{Packages.java.lang.System.setProperty("schematio.bridge.load", String(state)); return Packages.kotlin.Unit.INSTANCE;}}}}); {IPC}ServerIpc.INSTANCE.sendLoadRequest(Packages.io.schemat.connector.core.ipc.LoadRefType.SCHEMATIC, "bridge-fixture", "", cb)''')
                until(lambda: js('Packages.java.lang.System.getProperty("schematio.bridge.load")') == 'OK')
                assert js('level.getBlockState(new Packages.net.minecraft.core.BlockPos(0,-63,0)).isAir()') == 'true'
                record['checks'].append('reference loaded into clipboard without changing world')
                call('run_command', {'command': '//paste'})
                until(lambda: 'minecraft:stone' in js('String(level.getBlockState(new Packages.net.minecraft.core.BlockPos(0,-63,0)))'))
                assert 'axis=x' in js('String(level.getBlockState(new Packages.net.minecraft.core.BlockPos(1,-63,0)))')
                record['checks'].append('explicit paste preserved stone and directional log')
                js(f'''var status = new JavaAdapter(Packages.kotlin.jvm.functions.Function2, {{invoke:function(state, detail) {{return Packages.kotlin.Unit.INSTANCE;}}}}); var draft = new JavaAdapter(Packages.kotlin.jvm.functions.Function1, {{invoke:function(id) {{Packages.java.lang.System.setProperty("schematio.bridge.draft", String(id));return Packages.kotlin.Unit.INSTANCE;}}}}); {IPC}ServerIpc.INSTANCE.sendUploadClipboard(status,draft)''')
                until(lambda: js('Packages.java.lang.System.getProperty("schematio.bridge.draft")') == 'bridge-draft')
                record['checks'].append('WorldEdit clipboard serialized and returned a draft')
                call('disconnect')
                assert js(f'String({IPC}ServerSession.INSTANCE.getTrust())') == 'NONE'
                call('connect_server', {'address': '127.0.0.1:25576'})
                until(lambda: js(f'String({IPC}ServerSession.INSTANCE.getTrust())') == 'VERIFIED')
                assert js(f'Packages.java.util.Arrays.toString({IPC}ServerSession.INSTANCE.getNonce())') != nonce
                record['checks'].append('reconnect rotated nonce and verified a new attestation')
                packets = call('packets_list', {'type': 'custom_payload', 'limit': 100})
                (run / 'bridge-packets.json').write_text(json.dumps(packets, indent=2) + '\n')
                record['passed'] = True
            except Exception as error:
                record['error'] = repr(error)
            finally:
                if client:
                    try: js('mc.stop(); true')
                    except Exception: pass
                    try: record['client_exit'] = client.wait(timeout=20)
                    except subprocess.TimeoutExpired: client.terminate(); record['client_exit'] = -1
                if server:
                    if server.poll() is None:
                        server.stdin.write('stop\n'); server.stdin.flush()
                    try: record['server_exit'] = server.wait(timeout=30)
                    except subprocess.TimeoutExpired: server.terminate(); record['server_exit'] = -1
                record['passed'] = record['passed'] and record.get('client_exit') == 0 and record.get('server_exit') == 0
                (run / 'bridge-result.json').write_text(json.dumps(record, indent=2) + '\n')
                records.append(record)
                print(json.dumps(record), flush=True)
    return not all(r['passed'] for r in records)


if __name__ == '__main__':
    raise SystemExit(main())
