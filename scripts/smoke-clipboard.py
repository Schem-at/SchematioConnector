#!/usr/bin/env python3
"""Load and paste a generated Litematica v7 on a real, isolated Paper/WE or FAWE server."""
import argparse
import gzip
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def fixture():
    """Two regions, including a negative position and a chest with block-entity NBT."""
    def string(value):
        data = value.encode()
        return struct.pack('>H', len(data)) + data

    def payload(kind, value):
        if kind == 3: return struct.pack('>i', value)
        if kind == 4: return struct.pack('>q', value)
        if kind == 8: return string(value)
        if kind == 9:
            child, items = value
            return bytes([child]) + struct.pack('>i', len(items)) + b''.join(payload(child, item) for item in items)
        if kind == 10:
            return b''.join(bytes([k]) + string(name) + payload(k, v) for name, (k, v) in value.items()) + b'\0'
        if kind == 12: return struct.pack('>i', len(value)) + b''.join(struct.pack('>q', v) for v in value)
        raise ValueError(kind)

    def xyz(x, y, z): return (10, {n: (3, v) for n, v in zip('xyz', (x, y, z))})

    def region(position, size, palette, states, tiles=()):
        return (10, {
            'Position': xyz(*position), 'Size': xyz(*size),
            'BlockStatePalette': (9, (10, palette)), 'BlockStates': (12, [states]),
            'TileEntities': (9, (10, tiles)), 'Entities': (9, (10, [])),
            'PendingBlockTicks': (9, (10, [])), 'PendingFluidTicks': (9, (10, [])),
        })

    air = {'Name': (8, 'minecraft:air')}
    root = {
        'Version': (3, 7), 'SubVersion': (3, 1), 'MinecraftDataVersion': (3, 4440),
        'Metadata': (10, {'Name': (8, 'Clipboard regression'), 'Author': (8, 'SchematioConnector tests'),
            'Description': (8, ''), 'RegionCount': (3, 2), 'TotalBlocks': (3, 3), 'TotalVolume': (3, 3),
            'EnclosingSize': xyz(4, 2, 4), 'TimeCreated': (4, 0), 'TimeModified': (4, 0)}),
        'Regions': (10, {
            'negative': region((-2, 0, -1), (2, 1, 1), [air,
                {'Name': (8, 'minecraft:stone')},
                {'Name': (8, 'minecraft:oak_log'), 'Properties': (10, {'axis': (8, 'x')})}], 9),
            'chest': region((1, 1, 2), (1, 1, 1), [air,
                {'Name': (8, 'minecraft:chest'), 'Properties': (10, {'facing': (8, 'east'), 'type': (8, 'single'), 'waterlogged': (8, 'false')})}], 1,
                [{'id': (8, 'minecraft:chest'), 'x': (3, 0), 'y': (3, 0), 'z': (3, 0),
                    'CustomName': (8, '{"text":"Regression chest"}'), 'Items': (9, (10, []))}]),
        }),
    }
    return gzip.compress(b'\x0a\0\0' + payload(10, root), mtime=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-cache', type=Path, required=True, help='A previously downloaded Paper server with server.jar and libraries/')
    parser.add_argument('--plugin', type=Path, required=True)
    parser.add_argument('--worldedit', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--expect-litematic-failure', action='store_true')
    args = parser.parse_args()
    if not args.name.replace('-', '').replace('.', '').isalnum(): parser.error('name must be a single directory name')
    run = ROOT / 'build/clipboard-smoke' / args.name
    run.mkdir(parents=True, exist_ok=True)
    for name in ['server.jar', 'cache', 'versions', 'libraries']:
        source = args.server_cache.resolve() / name
        dest = run / name
        if source.exists() and not dest.exists(): dest.symlink_to(source, target_is_directory=source.is_dir())
    plugins = run / 'plugins'
    plugins.mkdir(exist_ok=True)
    shutil.copy2(args.plugin, plugins / 'SchematioConnector.jar')
    shutil.copy2(args.worldedit, plugins / 'WorldEdit.jar')
    (run / 'eula.txt').write_text('eula=true\n')
    (run / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n')
    config = plugins / 'SchematioConnector/config.yml'
    config.parent.mkdir(exist_ok=True)
    config.write_text('api-endpoint: http://127.0.0.1:1/api/v1\ncommunity-token: ""\n')
    (run / 'fixture.litematic').write_bytes(fixture())
    classes = run / 'probe-classes'
    classes.mkdir(exist_ok=True)
    classpath = [args.plugin.resolve(), args.worldedit.resolve(), *sorted((run / 'libraries').rglob('*.jar'))]
    subprocess.run([str(args.java_home / 'bin/javac'), '-proc:none', '-cp', os.pathsep.join(map(str, classpath)), '-d', str(classes), str(ROOT / 'scripts/ClipboardProbe.java')], check=True)
    with zipfile.ZipFile(plugins / 'ClipboardProbe.jar', 'w') as jar:
        jar.write(classes / 'ClipboardProbe.class', 'ClipboardProbe.class')
        jar.writestr('plugin.yml', 'name: ClipboardProbe\nversion: 1\nmain: ClipboardProbe\napi-version: "1.21"\ndepend: [SchematioConnector, WorldEdit]\n')
    log = run / 'console.log'
    with log.open('w') as output:
        subprocess.run([str(args.java_home / 'bin/java'), '-XX:ActiveProcessorCount=2', '-Xms256M', '-Xmx1536M',
            f'-Dclipboard.expectFailure={str(args.expect_litematic_failure).lower()}', '-jar', 'server.jar', 'nogui'], cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=180, check=True)
    content = log.read_text()
    for line in content.splitlines():
        if any(s in line for s in ['CLIPBOARD_', 'Format ', 'Auto-detection', 'Failed to load schematic', 'Caused by:', 'AssertionError']): print(line)
    print('Full server log:', log)
    marker = 'CLIPBOARD_REPRODUCED:' if args.expect_litematic_failure else 'CLIPBOARD_PASS:'
    if marker not in content or 'CLIPBOARD_FAIL' in content: raise SystemExit(1)


if __name__ == '__main__': main()
