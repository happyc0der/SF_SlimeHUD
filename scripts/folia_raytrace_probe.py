#!/usr/bin/env python3
"""Inspect the installed HUD updater on a disposable native server, without a player client."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("server-template", "core", "addon", "java-home", "work-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--mode", choices=("control", "candidate", "single-region"), required=True)
    parser.add_argument("--restart", action="store_true", help="Repeat the probe after a clean restart of the same world")
    args = parser.parse_args()
    root = args.work_dir.resolve()
    root.mkdir(parents=True, exist_ok=False)
    template = args.server_template.resolve()
    for name in ("cache", "libraries", "versions"):
        shutil.copytree(template / name, root / name)
    shutil.copy2(template / "server.jar", root / "server.jar")
    (root / "plugins/Slimefun").mkdir(parents=True)
    shutil.copy2(args.core.resolve(), root / "plugins/Slimefun.jar")
    shutil.copy2(args.addon.resolve(), root / "plugins/SlimeHUD.jar")
    (root / "plugins/Slimefun/config.yml").write_text("options:\n  auto-update: false\nstability:\n  item-doctor:\n    enabled: false\n")
    player_settings = root / "plugins/SlimeHUD/player.yml"
    player_settings.parent.mkdir()
    player_settings.write_text(
        "# Existing preferences and unknown extension data must survive untouched.\n"
        "9ecaee77-09b3-4b6b-ad23-a5ecb2fba29a:\n  display: actionbar\n  waila: false\n"
        "  extension-data: preserved\nfixture-extension:\n  language: '日本語'\n", encoding="utf-8")
    settings_bytes = player_settings.read_bytes()
    settings_mtime = player_settings.stat().st_mtime_ns
    (root / "config").mkdir()
    (root / "config/paper-global.yml").write_text("threaded-regions:\n  threads: 2\n")
    (root / "eula.txt").write_text("eula=true\n")
    (root / "server.properties").write_text(
        "online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nlevel-name=hud-region-fixture\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nmax-players=1\nview-distance=2\n"
        "simulation-distance=2\nspawn-protection=0\npause-when-empty-seconds=-1\n"
        "enable-query=false\nenable-rcon=false\n")
    (root / "bukkit.yml").write_text("settings:\n  allow-end: false\n")
    java = args.java_home.resolve() / "bin/java"
    fixture = Path(__file__).resolve().parent / "folia-raytrace-fixture"
    classes = root / "probe-classes"
    classes.mkdir()
    classpath = [str(root / "plugins/Slimefun.jar"), str(root / "plugins/SlimeHUD.jar")]
    classpath += [str(p) for p in (root / "libraries").rglob("*.jar")]
    with (root / "compile.log").open("w") as log:
        subprocess.run([str(args.java_home.resolve() / "bin/javac"), "--release", "21", "-cp", ":".join(classpath),
                        "-d", str(classes), *map(str, sorted(fixture.glob("*.java")))],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
    shutil.copy2(fixture / "plugin.yml", classes / "plugin.yml")
    subprocess.run([str(args.java_home.resolve() / "bin/jar"), "cf", str(root / "plugins/SlimeHudRegionProbe.jar"),
                    "-C", str(classes), "."], check=True)
    proof_path = root / "plugins/SlimeHudRegionProbe/proof.json"
    process = None
    boots = []
    try:
        for boot in range(2 if args.restart else 1):
            console = root / ("console.log" if boot == 0 else "restart-console.log")
            proof_path.unlink(missing_ok=True)
            with console.open("w") as log:
                process = subprocess.Popen([str(java), "-Xms256M", "-Xmx768M", "-Dterminal.jline=false", "-Dterminal.ansi=false",
                                            "-jar", "server.jar", "--nogui"], cwd=root, stdin=subprocess.PIPE,
                                           stdout=log, stderr=subprocess.STDOUT, text=True)
                deadline = time.monotonic() + 180
                while "Done (" not in console.read_text():
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Native server failed to start; inspect " + str(console))
                    time.sleep(0.25)
                print(f"Native server ready (boot {boot + 1}); running production HUD ownership probe", flush=True)
                process.stdin.write("hudregionprobe " + args.mode + "\n")
                process.stdin.flush()
                deadline = time.monotonic() + 45
                while not proof_path.is_file():
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Native HUD probe did not finish; inspect " + str(console))
                    time.sleep(0.25)
                proof = json.loads(proof_path.read_text())
                assert proof["status"] == "PASS", proof
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=60)
                assert process.returncode == 0
            assert player_settings.read_bytes() == settings_bytes, "Existing player settings changed"
            assert player_settings.stat().st_mtime_ns == settings_mtime, "Unchanged settings were rewritten"
            proof.update({"boot": boot + 1, "normal_shutdown_exit_code": process.returncode,
                          "player_settings_unchanged": True, "console_sha256": digest(console)})
            boots.append(proof)
        proof = dict(boots[-1])
        proof.update({"core_sha256": digest(args.core.resolve()), "addon_sha256": digest(args.addon.resolve()),
                      "server_launcher_sha256": digest(root / "server.jar"), "boots": boots,
                      "probe_source_sha256": digest(fixture / "SlimeHudRegionProbe.java"),
                      "probe_jar_sha256": digest(root / "plugins/SlimeHudRegionProbe.jar"),
                      "player_settings_sha256": digest(player_settings), "work_dir": str(root)})
        (root / "verification.json").write_text(json.dumps(proof, indent=2) + "\n")
        print(json.dumps(proof, indent=2), flush=True)
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


if __name__ == "__main__":
    main()
