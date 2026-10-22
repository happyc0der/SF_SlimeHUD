#!/usr/bin/env python3
"""Prepare a fresh localhost-only server and compile the real-client HUD fixture.

This is a test harness, not a production installation or server updater. The caller
supplies the core and HUD JARs. Paper and WIT inputs are checksum pinned below.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.parse
import zipfile

HERE = Path(__file__).resolve().parent
PAPER_NAME = "paper-1.21.11-132.jar"
PAPER_SHA256 = "5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"
PAPER_URL = f"https://fill-data.papermc.io/v1/objects/{PAPER_SHA256}/{PAPER_NAME}"
WIT_NAME = "WIT-1.9.5-mc.1.21.11.jar"
WIT_SHA256 = "d5dbb218c1de733786c35f18e3b43f44295e54bb1ecbd2c2dae40df535d57dfc"
WIT_URL = f"https://cdn.modrinth.com/data/bRQBjsAB/versions/CWSl11QK/{WIT_NAME}"


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise RuntimeError(detail)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download(url: str, expected: str, destination: Path) -> None:
    subprocess.run([
        "curl", "--fail", "--location", "--silent", "--show-error",
        "--connect-timeout", "30", "--max-time", "180",
        "--user-agent", "SF_SlimeHUD-player-fixture/1.0",
        "--output", str(destination), url,
    ], check=True, timeout=200)
    require(digest(destination) == expected, f"Downloaded checksum mismatch: {destination.name}")


def java_options(java_home: Path) -> list[str]:
    result = [str(java_home / "bin/java"), "-Xms256M", "-Xmx1G",
              "-Dterminal.jline=false", "-Dterminal.ansi=false"]
    truststore = Path("/etc/ssl/certs/java/cacerts")
    if truststore.is_file():
        result.append(f"-Djavax.net.ssl.trustStore={truststore}")
    proxy = urllib.parse.urlsplit(os.environ.get("HTTPS_PROXY", ""))
    if proxy.hostname:
        for scheme in ("http", "https"):
            result.extend([f"-D{scheme}.proxyHost={proxy.hostname}",
                           f"-D{scheme}.proxyPort={proxy.port or 80}"])
    return result


def bootstrap(template: Path, java_home: Path) -> None:
    native_log = template / "logs/latest.log"
    process = None
    with (template / "bootstrap-console.log").open("w") as output:
        try:
            process = subprocess.Popen(
                [*java_options(java_home), "-jar", "server.jar", "--nogui"],
                cwd=template, stdin=subprocess.PIPE, stdout=output,
                stderr=subprocess.STDOUT, text=True,
            )
            deadline = time.monotonic() + 180
            while True:
                require(process.poll() is None, "Bootstrap server exited before readiness")
                require(time.monotonic() < deadline, "Bootstrap server timed out")
                if native_log.is_file() and "Done (" in native_log.read_text():
                    break
                time.sleep(0.2)
            require("Enabling Slimefun" in native_log.read_text(), "Core did not enable")
            process.stdin.write("stop\n")
            process.stdin.flush()
            process.wait(timeout=60)
            require(process.returncode == 0, "Bootstrap server failed normal shutdown")
        finally:
            if process is not None and process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=15)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("work-dir", "java-home", "core", "addon"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--core-sha256", required=True)
    parser.add_argument("--core-source", required=True)
    args = parser.parse_args()
    work, java_home, core, addon = map(Path.resolve, (args.work_dir, args.java_home, args.core, args.addon))
    require(not work.exists(), "Refusing to replace an existing work directory")
    require(digest(core) == args.core_sha256, "Input core checksum mismatch")
    with zipfile.ZipFile(core) as jar:
        properties = dict(line.split("=", 1) for line in jar.read("git.properties").decode().splitlines()
                          if "=" in line and not line.startswith("#"))
    require(properties.get("git.source.commit") == args.core_source, "Input core source mismatch")
    require(addon.is_file(), "Input HUD JAR missing")
    work.mkdir(parents=True)
    downloads = work / "downloads"
    downloads.mkdir()
    shutil.copy2(addon, downloads / "SF_SlimeHUD2.0.5-verified-candidate.jar")
    download(PAPER_URL, PAPER_SHA256, downloads / PAPER_NAME)
    download(WIT_URL, WIT_SHA256, downloads / WIT_NAME)
    with zipfile.ZipFile(downloads / WIT_NAME) as jar:
        (downloads / "wit-config.yml").write_bytes(jar.read("config.yml"))
    template = work / "template"
    (template / "plugins/Slimefun").mkdir(parents=True)
    shutil.copy2(downloads / PAPER_NAME, template / "server.jar")
    shutil.copy2(core, template / "plugins/Slimefun.jar")
    (template / "plugins/Slimefun/config.yml").write_text(
        "options:\n  auto-update: false\nstability:\n  item-doctor:\n    enabled: false\n")
    (template / "plugins/bStats").mkdir()
    (template / "plugins/bStats/config.yml").write_text("enabled: false\n")
    (template / "eula.txt").write_text("eula=true\n")
    (template / "server.properties").write_text(
        "online-mode=false\nserver-ip=127.0.0.1\nserver-port=25591\nlevel-name=world\n"
        "level-type=minecraft:flat\n"
        'generator-settings={"biome":"minecraft:plains","features":false,"lakes":false,'
        '"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}],"structure_overrides":[]}\n'
        "generate-structures=false\nmax-players=1\nview-distance=2\nsimulation-distance=2\n"
        "spawn-protection=0\npause-when-empty-seconds=-1\nenable-query=false\nenable-rcon=false\n"
        "enforce-secure-profile=false\nallow-flight=true\n")
    (template / "bukkit.yml").write_text("settings:\n  allow-end: false\n")
    print("Bootstrapping the pinned Paper server on localhost", flush=True)
    bootstrap(template, java_home)
    classes = work / "fixture-classes"
    classes.mkdir()
    classpath = [core, addon, *sorted((template / "libraries").rglob("*.jar"))]
    subprocess.run([
        str(java_home / "bin/javac"), "--release", "21", "-cp", os.pathsep.join(map(str, classpath)),
        "-d", str(classes), str(HERE / "SlimeHudClientProbe.java"),
    ], check=True, timeout=90)
    shutil.copy2(HERE / "plugin.yml", classes / "plugin.yml")
    fixture = downloads / "SlimeHudClientProbe.jar"
    subprocess.run([str(java_home / "bin/jar"), "cf", str(fixture), "-C", str(classes), "."], check=True)
    receipt = {
        "paper": {"version": "1.21.11", "build": 132, "url": PAPER_URL, "sha256": PAPER_SHA256},
        "wit": {"version": "1.9.5-mc.1.21.11", "url": WIT_URL, "sha256": WIT_SHA256},
        "core": {"source": args.core_source, "sha256": args.core_sha256},
        "fixture_sha256": digest(fixture),
        "fixture_source_sha256": digest(HERE / "SlimeHudClientProbe.java"),
        "protocol_dependency_lock_sha256": digest(HERE / "package-lock.json"),
    }
    (work / "inputs.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Prepared {work}", flush=True)


if __name__ == "__main__":
    main()
