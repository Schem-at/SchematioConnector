#!/usr/bin/env python3
"""Check the exact release jar set, metadata, nested jars, and bundled dependencies."""
import argparse
import hashlib
import io
import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
NATIVE_PLATFORMS = {'linux-x64', 'linux-arm64', 'macos-x64', 'macos-arm64', 'windows-x64'}
MOD_ID = re.compile(r"[a-z][a-z0-9_-]{1,63}\Z")


def props(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def inspect_mod(data, label, errors, found):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        bad = jar.testzip()
        if bad:
            errors.append(f"{label}: corrupt entry {bad}")
        meta = json.loads(jar.read("fabric.mod.json"))
        mod_id = meta.get("id", "")
        if not MOD_ID.fullmatch(mod_id):
            errors.append(f"{label}: invalid Fabric mod ID {mod_id!r}")
        if "${" in json.dumps(meta):
            errors.append(f"{label}: unexpanded metadata placeholder")
        found[mod_id] = meta
        for entrypoints in meta.get("entrypoints", {}).values():
            for entry in entrypoints:
                value = entry if isinstance(entry, str) else entry["value"]
                path = value.split("::")[0].replace(".", "/") + ".class"
                if path not in jar.namelist():
                    errors.append(f"{label}: missing entrypoint {value}")
        for nested in meta.get("jars", []):
            path = nested["file"]
            if path not in jar.namelist():
                errors.append(f"{label}: missing nested jar {path}")
            else:
                inspect_mod(jar.read(path), f"{label}!{path}", errors, found)
        return meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=pathlib.Path, help="Validate an already staged directory")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build/release-readiness/artifacts.json")
    args = parser.parse_args()
    config = props(ROOT / "gradle.properties")
    version = ".".join(config[f"version{k}"] for k in ("Major", "Minor", "Patch"))
    settings = (ROOT / "settings.gradle.kts").read_text()
    versions = re.findall(r'"([\d.]+)"', re.search(r'versions\(([^)]+)\)', settings)[1])
    expected = {f"SchematioConnector-Paper-{version}.jar": ROOT / "bukkit/build/libs"}
    expected.update({f"SchematioConnector-Fabric-mc{mc}-{version}.jar": ROOT / f"build/libs/{version}" for mc in versions})
    errors = []
    records = []
    if args.stage:
        actual = {p.name for p in args.stage.glob("*.jar")}
        if actual != set(expected):
            errors.append(f"Jar set mismatch: missing {sorted(set(expected)-actual)}, extra {sorted(actual-set(expected))}")
    for name, directory in expected.items():
        path = (args.stage or directory) / name
        if not path.is_file():
            errors.append(f"Missing release artifact: {path}")
            continue
        data = path.read_bytes()
        record = {"name": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                if not any(n.startswith("LICENSE") for n in jar.namelist()):
                    errors.append(f"{name}: missing project license")
                if "Fabric" in name:
                    found = {}
                    meta = inspect_mod(data, name, errors, found)
                    mc = name.split("-mc", 1)[1].rsplit(f"-{version}.jar", 1)[0]
                    pins = props(ROOT / f"fabric/versions/{mc}/gradle.properties")
                    expected_deps = {"minecraft": pins["mod.mc_compat"],
                                     "java": ">=25" if mc.startswith("26.") else ">=21",
                                     "fabric-language-kotlin": ">=" + config["deps.flk_min"]}
                    if meta["version"] != version:
                        errors.append(f"{name}: wrong version {meta['version']}")
                    for key, value in expected_deps.items():
                        if meta["depends"].get(key) != value:
                            errors.append(f"{name}: {key} must be {value}")
                    if meta.get("contact", {}).get("sources") != "https://github.com/Schem-at/SchematioConnector":
                        errors.append(f"{name}: wrong GitHub source link")
                    if "panellib" not in found:
                        errors.append(f"{name}: missing bundled panel-lib")
                    # HTTP and JWT dependencies must be included explicitly; Loom include is non-transitive.
                    required = ("jackson-databind", "jackson-core", "jackson-annotations", "httpclient", "httpcore", "httpmime", "nucleation")
                    nested_names = [e["file"] for e in meta.get("jars", [])]
                    for required_name in required:
                        if not any("/" + required_name + "-" in n for n in nested_names):
                            errors.append(f"{name}: missing bundled {required_name}")
                    record["nested_mod_count"] = len(found) - 1
                    native_jar = next(n for n in nested_names if "/nucleation-" in n)
                    with zipfile.ZipFile(io.BytesIO(jar.read(native_jar))) as native:
                        record["native_platforms"] = sorted({n.split("/")[1] for n in native.namelist() if n.startswith("native/") and not n.endswith("/")})
                        if set(record["native_platforms"]) != NATIVE_PLATFORMS:
                            errors.append(f"{name}: incomplete native platform bundle")
                else:
                    plugin = jar.read("plugin.yml").decode()
                    if not re.search(r"^version:\s*['\"]?" + re.escape(version) + r"['\"]?\s*$", plugin, re.M):
                        errors.append(f"{name}: plugin version mismatch")
                    if "${" in plugin:
                        errors.append(f"{name}: unexpanded plugin metadata")
                    entry = re.search(r"^main:\s*(\S+)", plugin, re.M)[1].replace(".", "/") + ".class"
                    if entry not in jar.namelist():
                        errors.append(f"{name}: missing plugin entrypoint")
                    record["native_platforms"] = sorted({n.split("/")[1] for n in jar.namelist() if n.startswith("native/") and not n.endswith("/")})
                    if set(record["native_platforms"]) != NATIVE_PLATFORMS:
                        errors.append(f"{name}: incomplete native platform bundle")
        except (KeyError, ValueError, StopIteration, zipfile.BadZipFile) as exc:
            errors.append(f"{name}: {exc}")
        records.append(record)
    report = {"version": version, "minecraft_versions": versions, "artifacts": records, "errors": errors, "passed": not errors}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    sums = "".join(f"{r['sha256']}  {r['name']}\n" for r in records)
    args.output.with_name("SHA256SUMS").write_text(sums)
    print(f"Checked {len(records)} of {len(expected)} artifacts for {version}.")
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Report: {args.output}")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
