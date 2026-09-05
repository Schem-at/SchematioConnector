#!/usr/bin/env python3
"""Start an isolated development client, exercise preview capture, and close cleanly.

Requires a desktop/OpenGL session and JDK 21 + 25. No account is used. The agent
checks actual Minecraft model tessellation, PNG readback, transparency, and UI
initialization/cleanup. It does not verify Mojang sign-in or authenticated uploads.
"""
import argparse
import json
import os
import pathlib
import signal
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('versions', nargs='+')
    parser.add_argument('--jdk', type=pathlib.Path, required=True, help='JDK 21+ home for compiling the test agent')
    args = parser.parse_args()
    output = ROOT / 'build/release-readiness'
    classes = output / 'client-agent'
    classes.mkdir(parents=True, exist_ok=True)
    manifest = output / 'client-agent-manifest.mf'
    manifest.write_text('Premain-Class: ClientSmokeAgent\n\n')
    subprocess.run([str(args.jdk / 'bin/javac'), '-d', str(classes), 'scripts/ClientSmokeAgent.java'], cwd=ROOT, check=True)
    subprocess.run([str(args.jdk / 'bin/jar'), '--create', '--file', str(output / 'client-smoke-agent.jar'),
                    '--manifest', str(manifest), '-C', str(classes), '.'], cwd=ROOT, check=True)
    results = []
    for version in args.versions:
        run = output / 'clients' / version
        run.mkdir(parents=True, exist_ok=True)
        passed = run / 'passed.txt'
        passed.unlink(missing_ok=True)
        log = output / f'client-{version}.log'
        started = time.time()
        with log.open('w') as stream:
            process = subprocess.Popen(['./gradlew', f':fabric:{version}:runClient', '-I',
                'scripts/client-smoke.init.gradle', '--console=plain'], cwd=ROOT, stdout=stream,
                stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=300)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=15)
                code = -1
        text = log.read_text()
        checks = [line.split('SCHEMAT-SMOKE PASS ', 1)[1] for line in text.splitlines() if 'SCHEMAT-SMOKE PASS ' in line]
        result = {'minecraft': version, 'exit_code': code, 'passed': code == 0 and passed.exists() and len(checks) == 4,
                  'checks': checks, 'seconds': round(time.time() - started, 1), 'log': str(log.relative_to(ROOT))}
        (run / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        results.append(result)
        print(json.dumps(result), flush=True)
    return not all(r['passed'] for r in results)


if __name__ == '__main__':
    raise SystemExit(main())
