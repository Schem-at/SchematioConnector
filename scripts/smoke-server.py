#!/usr/bin/env python3
"""Boot a packaged release in an isolated localhost server; retain logs and inputs.

This checks mod discovery, entrypoints, commands, and clean shutdown. It does not
test the client UI or a player's clipboard. No existing worlds/config are used.
Requires Java 21 (1.21.x) or 25 (26.x). Set --java to select the executable.
"""
import argparse
import hashlib
import json
import pathlib
import queue
import shutil
import subprocess
import threading
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
AGENT = "SchematioConnector-release-check/1.0 (https://github.com/Schem-at/SchematioConnector)"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()


def json_url(url):
    return json.loads(fetch(url))


def download(url, target, sha256=None):
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        data = fetch(url)
        if sha256 and hashlib.sha256(data).hexdigest() != sha256:
            raise ValueError(f"Checksum mismatch: {target.name}")
        target.write_bytes(data)
    if sha256 and hashlib.sha256(target.read_bytes()).hexdigest() != sha256:
        raise ValueError(f"Checksum mismatch: {target.name}")


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", choices=["fabric", "paper"])
    parser.add_argument("minecraft")
    parser.add_argument("--jar", type=pathlib.Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--worldedit", action="store_true")
    args = parser.parse_args()
    artifact = args.jar.resolve(strict=True)
    variant = "worldedit" if args.worldedit else "minimal"
    case = f"{args.platform}-{args.minecraft}-{artifact.stem}-{variant}"
    run = ROOT / "build/release-readiness/servers" / case
    run.mkdir(parents=True, exist_ok=True)
    mods = run / ("mods" if args.platform == "fabric" else "plugins")
    mods.mkdir(exist_ok=True)
    shutil.copy2(artifact, mods / artifact.name)
    inputs = {"artifact": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
              "platform": args.platform, "minecraft": args.minecraft, "worldedit": args.worldedit}
    if args.platform == "fabric":
        props = properties(ROOT / "gradle.properties")
        pins = properties(ROOT / f"fabric/versions/{args.minecraft}/gradle.properties")
        loader = props["deps.fabric_loader"]
        installer = "1.1.2"
        server_url = f"https://meta.fabricmc.net/v2/versions/loader/{args.minecraft}/{loader}/{installer}/server/jar"
        api = pins["deps.fabric_api"]
        flk = props["deps.flk"]
        for group, name, version in [("net/fabricmc/fabric-api", "fabric-api", api),
                                      ("net/fabricmc", "fabric-language-kotlin", flk)]:
            filename = f"{name}-{version}.jar"
            download(f"https://maven.fabricmc.net/{group}/{name}/{version}/{filename}", mods / filename)
        inputs.update(loader=loader, installer=installer, fabric_api=api, fabric_language_kotlin=flk)
        download(server_url, run / "server.jar")
    else:
        builds = json_url(f"https://fill.papermc.io/v3/projects/paper/versions/{args.minecraft}/builds")
        build = next((b for b in builds if b["channel"] == "STABLE"), builds[0])
        server = build["downloads"]["server:default"]
        download(server["url"], run / "server.jar", server["checksums"]["sha256"])
        inputs.update(paper_build=build["id"], server_sha256=server["checksums"]["sha256"])
    if args.worldedit:
        loader = "fabric" if args.platform == "fabric" else "paper"
        from urllib.parse import urlencode
        versions = json_url("https://api.modrinth.com/v2/project/worldedit/version?" + urlencode({
            "loaders": json.dumps([loader]), "game_versions": json.dumps([args.minecraft])}))
        if not versions:
            raise ValueError(f"No WorldEdit release for {loader} {args.minecraft}")
        release = next((v for v in versions if v["version_type"] == "release"), versions[0])
        asset = next(f for f in release["files"] if f["primary"])
        download(asset["url"], mods / asset["filename"])
        inputs["worldedit_version"] = release["version_number"]
    (run / "inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
    # Matches the repository's runServer local test EULA setup.
    (run / "eula.txt").write_text("eula=true\n")
    (run / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\n"
        "level-type=minecraft:flat\ngenerator-settings={\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1}],\"biome\":\"minecraft:plains\"}\n"
        "generate-structures=false\nview-distance=2\n"
        "simulation-distance=2\nmax-players=2\nspawn-protection=0\n")
    cmd = [args.java, "-XX:ActiveProcessorCount=2", "-Xms256M", "-Xmx1536M", "-jar", "server.jar", "nogui"]
    lines = queue.Queue()
    proc = subprocess.Popen(cmd, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)

    def read_output():
        for line in proc.stdout:
            lines.put(line)

    threading.Thread(target=read_output, daemon=True).start()
    ready = False
    initialized = False
    deadline = time.monotonic() + args.timeout
    stop_at = None
    all_lines = []
    with (run / "console.log").open("w") as log:
        while proc.poll() is None or not lines.empty():
            if time.monotonic() > deadline:
                proc.terminate()
                break
            try:
                line = lines.get(timeout=0.2)
            except queue.Empty:
                line = ""
            if line:
                log.write(line)
                log.flush()
                all_lines.append(line)
                if "Schematio Connector initialized!" in line or "SchematioConnector enabled!" in line:
                    initialized = True
                if 'Done (' in line and 'For help, type' in line and not ready:
                    ready = True
                    proc.stdin.write("schematio info\n")
                    proc.stdin.flush()
                    stop_at = time.monotonic() + 3
            if stop_at is not None and time.monotonic() >= stop_at:
                proc.stdin.write("stop\n")
                proc.stdin.flush()
                stop_at = None
    try:
        code = proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
        code = proc.wait()
    # Classloading errors can disable a plugin while the server still reaches Done.
    failures = [line.strip() for line in all_lines if any(marker in line for marker in (
        "Error occurred while enabling Schematio", "Could not load 'plugins/",
        "Incompatible mods found", "NoClassDefFoundError", "NoSuchMethodError",
        "Mixin apply for mod schematioconnector failed", "Exception in server tick loop"))]
    result = dict(inputs, started=ready, initialized=initialized, exit_code=code,
                  failures=failures, passed=ready and initialized and code == 0 and not failures)
    (run / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    print(f"Log: {run / 'console.log'}", flush=True)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
