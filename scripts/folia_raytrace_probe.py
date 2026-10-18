#!/usr/bin/env python3
"""Inspect the installed HUD updater on a disposable native server, without a player client."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import zipfile


REPOSITORY = Path(__file__).resolve().parent.parent
PROBE_FILES = ("scripts/folia_raytrace_probe.py", "scripts/folia-raytrace-fixture/SlimeHudRegionProbe.java",
               "scripts/folia-raytrace-fixture/plugin.yml")
CONTROL_SHA256 = "a0185cd2a3dddca3cb2a9d0b26a115764cea2a7b723717c4678fa81705c342c9"
CONTROL_DIAGNOSTIC = ("native HUD update: IllegalStateException - Thread failed main thread check: "
                      "Cannot getEntities asynchronously")


def require(condition: bool, detail: str) -> None:
    # Acceptance checks must also run under python -O; do not replace this with assert.
    if not condition:
        raise RuntimeError(detail)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_hashes(bound_files: dict[Path, str]) -> dict[str, str]:
    actual = {str(path): digest(path) for path in bound_files}
    require(actual == {str(path): sha for path, sha in bound_files.items()}, "Original or installed input changed during validation")
    return actual


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"Expected a JSON object: {path}")
    return value


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def source_identity(expected_source: str) -> dict:
    require(re.fullmatch(r"[0-9a-f]{40}", expected_source) is not None, "Expected an exact source commit")
    def git(*arguments: str) -> str:
        return subprocess.check_output(["git", *arguments], cwd=REPOSITORY, text=True).strip()
    require(git("rev-parse", "HEAD") == expected_source, "Checkout differs from the expected source commit")
    require(subprocess.run(["git", "diff", "--quiet", "HEAD"], cwd=REPOSITORY).returncode == 0,
            "Tracked source differs from the expected clean commit")
    return {"source_commit": expected_source, "source_tree": git("rev-parse", "HEAD^{tree}"),
            "probe_sources": {name: digest(REPOSITORY / name) for name in PROBE_FILES}}


def plugin_version(path: Path, expected_name: str) -> str:
    with zipfile.ZipFile(path) as jar:
        descriptor = jar.read("plugin.yml").decode("utf-8")
    fields = dict(re.findall(r"^(name|version):\s*([^\r\n]+)$", descriptor, re.MULTILINE))
    fields = {key: value.strip().strip("\"'") for key, value in fields.items()}
    require(fields.get("name") == expected_name, f"Wrong installed plugin: {path}")
    require(bool(fields.get("version")), f"Missing plugin version: {path}")
    return fields["version"]


def verify_core(path: Path, expected: dict) -> None:
    require(digest(path) == expected["sha256"], "Core JAR differs from the pre-bound input hash")
    require(plugin_version(path, "Slimefun") == expected["version"], "Wrong core descriptor version")
    with zipfile.ZipFile(path) as jar:
        properties = dict(line.split("=", 1) for line in jar.read("git.properties").decode().splitlines()
                          if "=" in line and not line.startswith("#"))
    for key in ("git.source.commit", "git.commit.id"):
        require(properties.get(key) == expected["source_commit"], f"Core {key} differs from its expected source")


def source_receipt(args: argparse.Namespace) -> None:
    receipt = {"schema": 1, **source_identity(args.expected_source),
               "addon_sha256": digest(args.addon), "addon_version": plugin_version(args.addon, "SlimeHUD")}
    require(receipt["addon_version"] == "2.0.5", "Expected the unpublished 2.0.5 candidate")
    write_json(args.output, receipt)


def server_receipt(args: argparse.Namespace) -> None:
    core = {"sha256": args.core_sha256, "source_commit": args.core_source, "version": args.core_version}
    require(re.fullmatch(r"[0-9a-f]{64}", core["sha256"]) is not None, "Expected a pinned core SHA-256")
    require(re.fullmatch(r"[0-9a-f]{40}", core["source_commit"]) is not None, "Expected a pinned core source")
    verify_core(args.core, core)
    require(args.build.isdecimal(), "Expected a numeric native server build")
    write_json(args.output, {"schema": 1, "family": args.family, "minecraft": args.minecraft,
                            "build": args.build, "channel": args.channel,
                            "server_launcher_sha256": digest(args.server), "core": core})


def expectations(args: argparse.Namespace) -> dict:
    source = read_json(args.source_receipt)
    server = read_json(args.server_receipt)
    require(source.get("schema") == 1 and server.get("schema") == 1, "Unsupported input receipt schema")
    for key, value in source_identity(args.expected_source).items():
        require(source.get(key) == value, f"Candidate build receipt does not match checkout: {key}")
    require(source.get("addon_version") == "2.0.5", "Wrong candidate version in build receipt")
    family = server.get("family")
    require(family in ("paper", "purpur", "folia") and server.get("minecraft") == "26.3",
            "This focused native fixture requires Paper/Purpur/Folia 26.3")
    require(isinstance(server.get("build"), str) and server["build"].isdecimal(), "Missing native build")
    require((args.mode == "single-region") == (family in ("paper", "purpur")), "Mode does not match native platform")
    addon_hash = CONTROL_SHA256 if args.mode == "control" else source.get("addon_sha256")
    require(isinstance(addon_hash, str) and re.fullmatch(r"[0-9a-f]{64}", addon_hash) is not None,
            "Missing expected addon SHA-256")
    require(digest(args.addon) == addon_hash, "Addon JAR differs from the pre-bound build/control hash")
    addon_version = "2.0.4" if args.mode == "control" else "2.0.5"
    require(plugin_version(args.addon, "SlimeHUD") == addon_version, "Wrong addon descriptor version")
    verify_core(args.core, server["core"])
    require(digest(args.server_template / "server.jar") == server.get("server_launcher_sha256"),
            "Server launcher differs from the pre-start server receipt")
    return {"schema": 2, "mode": args.mode, "source_commit": args.expected_source,
            "source_tree": source["source_tree"], "probe_sources": source["probe_sources"],
            "addon_sha256": addon_hash, "addon_version": addon_version, "server": server,
            "source_receipt_sha256": digest(args.source_receipt), "server_receipt_sha256": digest(args.server_receipt)}


def validate_proof(proof: dict, expected: dict, token: str) -> None:
    mode = expected["mode"]
    server = expected["server"]
    equal = {"schema": 2, "status": "PASS", "mode": mode, "boot_token": token,
             "source_commit": expected["source_commit"], "connected_player": False,
             "installed_addon_sha256": expected["addon_sha256"], "installed_core_sha256": server["core"]["sha256"],
             "slimehud_version": expected["addon_version"], "slimefun_version": server["core"]["version"],
             "minecraft_version": server["minecraft"], "native_entity_owned": True,
             "crossing_outcome_matches_expectation": True, "local_display_recovers": True}
    for key, value in equal.items():
        require(type(proof.get(key)) is type(value) and proof[key] == value, f"Missing or incorrect proof field: {key}")
    require(proof.get("server_name", "").lower() == server["family"], "Observed software differs from expected platform")
    version_pattern = re.escape(server["minecraft"] + "-" + server["build"] + "-") + r"[0-9a-f]+(?: |$)"
    require(re.match(version_pattern, proof.get("server", "")) is not None, "Observed native build differs from expected build")
    require(re.match(r"25(?:\.|$)", proof.get("java", "")) is not None, "Expected the Java 25 native runtime")
    require(isinstance(proof.get("owner_thread"), str) and bool(proof["owner_thread"]), "Missing owner thread observation")
    require(isinstance(proof.get("fixture"), str) and "Synthetic player accessors" in proof["fixture"], "Missing fixture scope")
    require("stone" in proof.get("local_block_rendered", "").lower(), "Missing ordinary local target")
    updates = {"control": 3, "candidate": 5, "single-region": 7}[mode]
    for key in ("production_updates", "owned_update_checks"):
        require(type(proof.get(key)) is int and proof[key] == updates, f"Incomplete native update sequence: {key}")
    origin = proof.get("origin", {})
    require(isinstance(origin, dict) and all(type(origin.get(axis)) in (int, float) and math.isfinite(origin[axis])
                                          for axis in ("x", "y", "z")), "Missing finite native origin")
    require(origin["x"] == 8.5 and origin["z"] == 8.5, "Unexpected native fixture origin")
    before = proof.get("last_failure_before_recovery")
    after = proof.get("last_failure_after_recovery")
    require(type(before) is int and type(after) is int and before == after, "Recovery changed the failure timestamp")
    failure = proof.get("production_updater_failure")
    if mode == "control":
        require(isinstance(failure, str) and failure.startswith(CONTROL_DIAGNOSTIC), "Control did not reproduce the specific ownership defect")
        require(before > 0, "Control did not record its expected failure")
    else:
        require(failure == "" and before == 0, "Candidate recorded a production updater failure")
        require(proof.get("empty_long_ray_cleared") is True, "Empty long ray retained a stale target")
        require("stone" in proof.get("long_range_nearby_block_rendered", "").lower(), "Missing long-range nearby block")
        require("diamond" in proof.get("long_range_nearby_item_rendered", "").lower(), "Missing long-range nearby item")
    if mode == "single-region":
        require(proof.get("single_region_long_ray_owned") is True and proof.get("configured_range") == 508,
                "Missing full-range ownership observation")
        require(proof.get("far_target_owned") is True and proof.get("default_range_far_target_absent") is True,
                "Missing owned far-target/default-range control")
        require(type(proof.get("far_target_distance")) in (int, float) and proof["far_target_distance"] == 24.0,
                "Far target was not the real 24-block target")
        require("stone" in proof.get("extended_range_far_target_rendered", "").lower(), "Native range beyond eight blocks was lost")
    else:
        chunk = proof.get("first_unowned_chunk", {})
        require(isinstance(chunk, dict) and type(chunk.get("x")) is int and type(chunk.get("z")) is int,
                "Missing first unowned chunk observation")
        require(chunk["x"] > 0 and chunk["z"] == 0 and proof.get("unowned_target_confirmed_before_query") is True,
                "Long ray did not point into a confirmed unowned chunk")
        require(proof.get("configured_range") == math.ceil(chunk["x"] * 16.0 - origin["x"] + 4.0),
                "Configured range did not cross the observed ownership boundary")


def validate_shutdown(receipt: dict, expected: dict, token: str) -> None:
    values = {"schema": 2, "status": "PASS", "mode": expected["mode"], "boot_token": token,
              "source_commit": expected["source_commit"], "server_stopping": True, "probe_finished": True,
              "installed_addon_sha256": expected["addon_sha256"], "installed_core_sha256": expected["server"]["core"]["sha256"]}
    for key, value in values.items():
        require(type(receipt.get(key)) is type(value) and receipt[key] == value, f"Missing or incorrect shutdown field: {key}")


def await_native_proof(path: Path, console: Path, expected: dict, token: str, process, timeout: float = 45) -> dict:
    deadline = time.monotonic() + timeout
    marker = "SLIMEHUD_REGION_PROBE_PASS " + expected["mode"] + " " + token
    while True:
        if process.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError("Native HUD proof and completion marker did not both arrive in time; inspect " + str(console))
        if path.is_file():
            proof = read_json(path)
            validate_proof(proof, expected, token)
            # The receipt rename precedes the logger call. Do not stop the server in between them.
            if marker in console.read_text():
                require(process.poll() is None and time.monotonic() <= deadline,
                        "Native HUD completion arrived after the deadline or server exit")
                return proof
        time.sleep(0.1)


def validate_console(console: str, expected: dict, token: str) -> None:
    marker = "SLIMEHUD_REGION_PROBE_PASS " + expected["mode"] + " " + token
    require(console.count(marker) == 1, "Missing unique native completion marker")
    stopped = console.find("Stopping server")
    require(0 <= console.find("Done (") < console.find(marker) < stopped, "Missing ordered normal shutdown")
    for name, version in (("Slimefun", expected["server"]["core"]["version"]),
                          ("SlimeHUD", expected["addon_version"]), ("SlimeHudRegionProbe", "1.0")):
        enabled = f"Enabling {name} v{version}"
        disabled = f"Disabling {name} v{version}"
        require(console.count(enabled) == 1 and console.find(enabled) < console.find(marker), f"Plugin did not enable: {name}")
        require(console.count(disabled) == 1 and console.find(disabled) > stopped, f"Plugin disabled early or did not shut down: {name}")
    require(console.count("SLIMEHUD_REGION_PROBE_SHUTDOWN " + token + " true") == 1, "Missing native stopping marker")
    reject_runtime_errors(console, expected)


def reject_runtime_errors(console: str, expected: dict) -> None:
    errors = re.compile(r"(?:ERROR|SEVERE)\]|Error occurred while (?:enabling|disabling)|Exception in thread|"
                        r"generated an exception|(?:Exception|Error):|SLIMEHUD_REGION_PROBE_FAIL")
    entries = re.split(r"(?=^\[\d{2}:\d{2}:\d{2}[^\n]*\])", console, flags=re.MULTILINE)
    for entry in entries:
        if errors.search(entry):
            expected_control = (expected["mode"] == "control" and
                                "Thread failed main thread check: Cannot getEntities asynchronously" in entry and
                                "io.github.schntgaispock.slimehud.waila.PlayerWAILA.update" in entry)
            require(expected_control, "Unexpected native enable/runtime/disable error: " + entry[:600])


def validate_logs(native: str, console: str, expected: dict, token: str) -> None:
    validate_console(native, expected, token)
    reject_runtime_errors(console, expected)


def run(args: argparse.Namespace) -> None:
    expected = expectations(args)
    root = args.work_dir.resolve()
    root.mkdir(parents=True, exist_ok=False)
    template = args.server_template.resolve()
    write_json(root / "expectations.json", expected)
    for label in ("source", "server"):
        shutil.copy2(getattr(args, label + "_receipt"), root / (label + "-receipt.json"))
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
        "level-type=minecraft:flat\n"
        'generator-settings={"biome":"minecraft:plains","features":false,"lakes":false,"layers":'
        '[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
        '{"block":"minecraft:grass_block","height":1}],"structure_overrides":[]}\n'
        "generate-structures=false\nmax-players=1\nview-distance=2\n"
        "simulation-distance=2\nspawn-protection=0\npause-when-empty-seconds=-1\n"
        "enable-query=false\nenable-rcon=false\n")
    (root / "bukkit.yml").write_text("settings:\n  allow-end: false\n")
    java = args.java_home.resolve() / "bin/java"
    fixture = Path(__file__).resolve().parent / "folia-raytrace-fixture"
    classes = root / "probe-classes"
    classes.mkdir()
    classpath = [str(root / "plugins/Slimefun.jar"), str(root / "plugins/SlimeHUD.jar")]
    classpath += [str(p) for p in sorted((root / "libraries").rglob("*.jar"))]
    with (root / "compile.log").open("w") as log:
        subprocess.run([str(args.java_home.resolve() / "bin/javac"), "--release", "21", "-cp", ":".join(classpath),
                        "-d", str(classes), *map(str, sorted(fixture.glob("*.java")))],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=90)
    shutil.copy2(fixture / "plugin.yml", classes / "plugin.yml")
    probe_jar = root / "plugins/SlimeHudRegionProbe.jar"
    subprocess.run([str(args.java_home.resolve() / "bin/jar"), "cf", str(probe_jar), "-C", str(classes), "."], check=True)
    bound_files = {args.core.resolve(): expected["server"]["core"]["sha256"], args.addon.resolve(): expected["addon_sha256"],
                   template / "server.jar": expected["server"]["server_launcher_sha256"],
                   root / "plugins/Slimefun.jar": expected["server"]["core"]["sha256"],
                   root / "plugins/SlimeHUD.jar": expected["addon_sha256"],
                   root / "server.jar": expected["server"]["server_launcher_sha256"], probe_jar: digest(probe_jar),
                   args.source_receipt.resolve(): expected["source_receipt_sha256"],
                   args.server_receipt.resolve(): expected["server_receipt_sha256"]}
    for name in ("expectations.json", "source-receipt.json", "server-receipt.json"):
        bound_files[root / name] = digest(root / name)

    def verify_inputs() -> dict:
        for key, value in source_identity(args.expected_source).items():
            require(expected[key] == value, f"Source changed during native validation: {key}")
        return verify_hashes(bound_files)

    probe_folder = root / "plugins/SlimeHudRegionProbe"
    proof_path = probe_folder / "proof.json"
    shutdown_path = probe_folder / "shutdown.json"
    native_log = root / "logs/latest.log"
    process = None
    boots = []
    try:
        for boot in range(2 if args.restart else 1):
            console = root / ("console.log" if boot == 0 else "restart-console.log")
            proof_path.unlink(missing_ok=True)
            shutdown_path.unlink(missing_ok=True)
            native_log.unlink(missing_ok=True)
            token = secrets.token_hex(16)
            write_json(probe_folder / "request.json", {"mode": args.mode, "boot_token": token,
                       "source_commit": expected["source_commit"], "addon_sha256": expected["addon_sha256"],
                       "core_sha256": expected["server"]["core"]["sha256"]})
            before_hashes = verify_inputs()
            with console.open("w") as log:
                process = subprocess.Popen([str(java), "-Xms256M", "-Xmx768M", "-Dterminal.jline=false", "-Dterminal.ansi=false",
                                            "-jar", "server.jar", "--nogui"], cwd=root, stdin=subprocess.PIPE,
                                           stdout=log, stderr=subprocess.STDOUT, text=True)
                deadline = time.monotonic() + 180
                while True:
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Native server failed to start; inspect " + str(console))
                    if native_log.is_file() and "Done (" in native_log.read_text():
                        require(process.poll() is None and time.monotonic() <= deadline,
                                "Native startup completed after the deadline or server exit")
                        break
                    time.sleep(0.25)
                print(f"Native server ready (boot {boot + 1}); running production HUD ownership probe", flush=True)
                process.stdin.write("hudregionprobe " + args.mode + "\n")
                process.stdin.flush()
                proof = await_native_proof(proof_path, native_log, expected, token, process)
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=60)  # A timeout fails, even if cleanup later obtains exit code zero.
                require(process.returncode == 0, "Native server did not exit normally")
            shutdown = read_json(shutdown_path)
            validate_shutdown(shutdown, expected, token)
            # Native logging can outlive its redirected console appender. Preserve and require the
            # complete native file log, while also scanning stdout for earlier bootstrap errors.
            saved_native_log = root / f"boot-{boot + 1}-server.log"
            shutil.copy2(native_log, saved_native_log)
            validate_logs(saved_native_log.read_text(), console.read_text(), expected, token)
            after_hashes = verify_inputs()
            require(player_settings.read_bytes() == settings_bytes, "Existing player settings changed")
            require(player_settings.stat().st_mtime_ns == settings_mtime, "Unchanged settings were rewritten")
            write_json(root / f"boot-{boot + 1}-proof.json", proof)
            write_json(root / f"boot-{boot + 1}-shutdown.json", shutdown)
            proof.update({"boot": boot + 1, "normal_shutdown_exit_code": process.returncode, "shutdown": shutdown,
                          "installed_inputs_before": before_hashes, "installed_inputs_after": after_hashes,
                          "player_settings_unchanged": True, "console_sha256": digest(console),
                          "server_log_sha256": digest(saved_native_log)})
            boots.append(proof)
        proof = {"status": "PASS", "expectations": expected, "boots": boots, "probe_jar_sha256": digest(probe_jar),
                 "player_settings_sha256": digest(player_settings), "work_dir": str(root)}
        write_json(root / "verification.json", proof)
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    source = commands.add_parser("source-receipt", help="Bind a just-built candidate to its clean source before runtime jobs")
    for name in ("addon", "output"):
        source.add_argument("--" + name, type=Path, required=True)
    source.add_argument("--expected-source", required=True)
    source.set_defaults(action=source_receipt)
    server = commands.add_parser("server-receipt", help="Record downloaded native/core identities before any server boot")
    for name in ("server", "core", "output"):
        server.add_argument("--" + name, type=Path, required=True)
    for name in ("minecraft", "build", "channel", "core-sha256", "core-source", "core-version"):
        server.add_argument("--" + name, required=True)
    server.add_argument("--family", choices=("paper", "purpur", "folia"), required=True)
    server.set_defaults(action=server_receipt)
    native = commands.add_parser("run", help="Require complete, correctly bound native evidence for every boot")
    for name in ("server-template", "core", "addon", "java-home", "work-dir", "source-receipt", "server-receipt"):
        native.add_argument("--" + name, type=Path, required=True)
    native.add_argument("--expected-source", required=True)
    native.add_argument("--mode", choices=("control", "candidate", "single-region"), required=True)
    native.add_argument("--restart", action="store_true", help="Repeat the probe after a clean restart of the same world")
    native.set_defaults(action=run)
    args = parser.parse_args()
    args.action(args)


if __name__ == "__main__":
    main()
