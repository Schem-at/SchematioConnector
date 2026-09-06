#!/usr/bin/env python3
"""Start an isolated packaged client, exercise preview capture, and close cleanly.

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
    parser.add_argument('--with-editors', action='store_true', help='Install pinned Axiom, Litematica and MaLiLib')
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
        run = output / ('clients-editors' if args.with_editors else 'clients-plain') / version
        run.mkdir(parents=True, exist_ok=True)
        passed = run / 'passed.txt'
        passed.unlink(missing_ok=True)
        log = run / 'gradle.log'
        config = run / 'config/schematioconnector/config.properties'
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text('api_endpoint=http://127.0.0.1:9/api/v1\ntrust_all_certificates=false\n')
        started = time.time()
        with log.open('w') as stream:
            process = subprocess.Popen(['./gradlew', f':fabric:{version}:runIntegrationClient',
                f'-PintegrationRunDir={run}', f'-PsmokeAgent={output / "client-smoke-agent.jar"}',
                f'-PwithAxiom={str(args.with_editors).lower()}', f'-PwithLitematica={str(args.with_editors).lower()}', '--console=plain'], cwd=ROOT, stdout=stream,
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
                  'checks': checks, 'packaged': True, 'editors': args.with_editors,
                  'integration': [line for line in text.splitlines() if 'SCHEMAT-INTEGRATION PASS' in line], 'seconds': round(time.time() - started, 1), 'log': str(log.relative_to(ROOT))}
        (run / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        results.append(result)
        print(json.dumps(result), flush=True)
    return not all(r['passed'] for r in results)


if __name__ == '__main__':
    raise SystemExit(main())
