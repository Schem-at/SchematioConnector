#!/usr/bin/env python3
"""Validate every supported target with incremental builds and bounded server jobs.

Default: tests, packaged artifact inspection, and 12 real server starts.
--clients adds real packaged client rendering with and without optional editors.
Requires a desktop/OpenGL session for clients; stores logs and a JSON report.
"""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/release-readiness'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk21', type=Path, required=True)
    parser.add_argument('--jdk25', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=3, choices=range(1, 7))
    parser.add_argument('--clients', action='store_true')
    parser.add_argument('--skip-build', action='store_true', help='Validate existing jars; their metadata is still checked')
    args = parser.parse_args()
    pins = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    version = '.'.join(pins['version' + part] for part in ['Major', 'Minor', 'Patch'])
    targets = re.findall(r'"([\d.]+)"', re.search(r'versions\(([^)]+)\)', (ROOT / 'settings.gradle.kts').read_text())[1])
    OUT.mkdir(parents=True, exist_ok=True)
    results = []

    def run(label, command):
        started = time.monotonic()
        log = OUT / (label + '.log')
        with log.open('w') as stream:
            process = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        result = {'check': label, 'passed': process.returncode == 0, 'seconds': round(time.monotonic() - started, 1), 'log': str(log.relative_to(ROOT))}
        print(json.dumps(result), flush=True)
        return result

    if not args.skip_build:
        results.append(run('matrix-build', ['./gradlew', ':core:test', ':bukkit:build', ':fabric:buildAllVersions', '--console=plain',
            f'-Dorg.gradle.java.installations.paths={args.jdk21},{args.jdk25}']))
    results.append(run('matrix-artifacts', [sys.executable, 'scripts/verify-release.py']))
    if all(r['passed'] for r in results):
        plugin = ROOT / f'bukkit/build/libs/SchematioConnector-Paper-{version}.jar'
        results.append(run('matrix-native', [str(args.jdk25 / 'bin/java'), '--class-path', str(plugin), 'scripts/NativeSmoke.java']))
        jobs = []
        for mc in targets:
            java = (args.jdk25 if mc.startswith('26.') else args.jdk21) / 'bin/java'
            for platform in ['fabric', 'paper']:
                runtime = '26.1.2' if mc == '26.1' and platform == 'paper' else mc
                jar = plugin if platform == 'paper' else ROOT / f'build/libs/{version}/SchematioConnector-Fabric-mc{mc}-{version}.jar'
                command = [sys.executable, 'scripts/smoke-server.py', platform, runtime, '--jar', str(jar), '--java', str(java)]
                if platform == 'paper': command.append('--worldedit')
                jobs.append((f'matrix-{platform}-{mc}', command))
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            for result in pool.map(lambda job: run(*job), jobs): results.append(result)
        results.append(run('matrix-clipboard', [sys.executable, 'scripts/smoke-clipboard-matrix.py', '--jdk21', str(args.jdk21), '--jdk25', str(args.jdk25), '--jobs', str(args.jobs)]))
        if args.clients:
            for editors in [False, True]:
                results.append(run('matrix-client-' + ('editors' if editors else 'plain'), [sys.executable, 'scripts/smoke-client.py', *targets,
                    '--jdk', str(args.jdk21), *(['--with-editors'] if editors else [])]))
    report = {'version': version, 'targets': targets, 'passed': all(r['passed'] for r in results), 'checks': results}
    (OUT / 'matrix.json').write_text(json.dumps(report, indent=2) + '\n')
    return not report['passed']


if __name__ == '__main__':
    raise SystemExit(main())
