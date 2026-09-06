#!/usr/bin/env python3
"""Check real Paper/WorldEdit Litematica imports using the packaged server cache."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk21', type=Path, required=True)
    parser.add_argument('--jdk25', type=Path, required=True)
    parser.add_argument('--minecraft', help='Limit to one Paper version')
    parser.add_argument('--jobs', type=int, default=3, choices=range(1, 7))
    parser.add_argument('--plugin', type=Path)
    args = parser.parse_args()
    pins = dict(line.split('=',1) for line in (ROOT/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    version = '.'.join(pins['version'+part] for part in ['Major','Minor','Patch'])
    plugin = args.plugin or ROOT/f'bukkit/build/libs/SchematioConnector-Paper-{version}.jar'
    targets = re.findall(r'"([\d.]+)"', re.search(r'versions\(([^)]+)\)', (ROOT/'settings.gradle.kts').read_text())[1])
    targets = ['26.1.2' if mc == '26.1' else mc for mc in targets]
    if args.minecraft:
        assert args.minecraft in targets
        targets = [args.minecraft]
    def check(mc):
        cache = ROOT/f'build/release-readiness/servers/paper-{mc}-{plugin.stem}-worldedit'
        worldedit, = (cache/'plugins').glob('worldedit-*.jar')
        java = args.jdk25 if mc.startswith('26.') else args.jdk21
        log = ROOT/f'build/release-readiness/clipboard-{mc}.log'
        with log.open('w') as stream:
            result = subprocess.run([sys.executable,'scripts/smoke-clipboard.py','--server-cache',str(cache),'--plugin',str(plugin),
                '--worldedit',str(worldedit),'--java-home',str(java),'--name',f'release-{version}-{mc}'],cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        record = {'minecraft':mc,'passed':result.returncode==0,'log':str(log.relative_to(ROOT))}
        print(json.dumps(record),flush=True)
        return record
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(check,targets))
    (ROOT/'build/release-readiness/clipboard-matrix.json').write_text(json.dumps(results,indent=2)+'\n')
    return not all(r['passed'] for r in results)
if __name__ == '__main__':raise SystemExit(main())
