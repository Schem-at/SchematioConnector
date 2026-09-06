#!/usr/bin/env python3
"""Run the six in-game bridge checks with a temporary local backend and MC-Inspector."""
import argparse
from pathlib import Path
import os
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('versions',nargs='*')
    p.add_argument('--jdk21',type=Path,required=True)
    p.add_argument('--jdk25',type=Path,required=True)
    p.add_argument('--inspector-root',type=Path,required=True)
    args=p.parse_args()
    pins=dict(line.split('=',1) for line in (ROOT/'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    version='.'.join(pins['version'+part] for part in ['Major','Minor','Patch'])
    targets=args.versions or re.findall(r'"([\d.]+)"',re.search(r'versions\(([^)]+)\)',(ROOT/'settings.gradle.kts').read_text())[1])
    plugin=ROOT/f'bukkit/build/libs/SchematioConnector-Paper-{version}.jar'
    out=ROOT/'build/release-readiness/bridge-backend';out.mkdir(parents=True,exist_ok=True)
    gson=next((ROOT/'build/release-readiness/servers').rglob('gson-*.jar'))
    command=[str(args.jdk25/'bin/java'),'--class-path',os.pathsep.join([str(plugin),str(gson)]),'scripts/BridgeBackend.java',str(out)]
    with (out/'console.log').open('w') as log:
        backend=subprocess.Popen(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if backend.poll() is not None:raise RuntimeError('Bridge fixture failed to start; see its console.log')
                try:
                    urllib.request.urlopen('http://127.0.0.1:38272/.well-known/schematio-keys.json',timeout=1).close();break
                except OSError:time.sleep(.1)
            else:raise TimeoutError('Bridge fixture did not become ready')
            return subprocess.call([sys.executable,'scripts/smoke-bridge.py',*targets,'--jdk21',str(args.jdk21),'--jdk25',str(args.jdk25),'--inspector-root',str(args.inspector_root),'--run-name','release-bridge'],cwd=ROOT)
        finally:
            backend.terminate()
            try:backend.wait(timeout=10)
            except subprocess.TimeoutExpired:backend.kill();backend.wait()
if __name__=='__main__':raise SystemExit(main())
