"""Negative controls for evidence acceptance; these synthetic receipts are not native runtime evidence."""
from copy import deepcopy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import folia_raytrace_probe as probe


TOKEN = "a" * 32
SOURCE = "1" * 40


def expected(mode="candidate"):
    return {"mode": mode, "source_commit": SOURCE, "addon_sha256": "2" * 64,
            "addon_version": "2.0.4" if mode == "control" else "2.0.6",
            "server": {"family": "paper" if mode == "single-region" else "folia", "minecraft": "26.3",
                       "build": "3", "core": {"version": "4.1.75", "sha256": "3" * 64}}}


def valid_proof(mode="candidate"):
    value = expected(mode)
    proof = {"schema": 2, "status": "PASS", "mode": mode, "boot_token": TOKEN, "source_commit": SOURCE,
             "connected_player": False, "installed_addon_sha256": value["addon_sha256"],
             "installed_core_sha256": value["server"]["core"]["sha256"], "slimehud_version": value["addon_version"],
             "slimefun_version": "4.1.75", "minecraft_version": "26.3", "native_entity_owned": True,
             "player_accessor_native_handle_matches_owner": True,
             "player_accessor_scheduler_matches_owner": True, "player_accessor_owned": True,
             "crossing_outcome_matches_expectation": True, "local_display_recovers": True,
             "server_name": value["server"]["family"].title(), "server": "26.3-3-597e413 (MC: 26.3)",
             "java": "25.0.4.1", "owner_thread": "Native owner thread", "fixture": "Synthetic player accessors (schema test)",
             "local_block_rendered": "Stone", "production_updates": {"control": 3, "candidate": 5, "single-region": 7}[mode],
             "owned_update_checks": {"control": 3, "candidate": 5, "single-region": 7}[mode],
             "origin": {"x": 8.5, "y": 129.265, "z": 8.5}, "configured_range": 508,
             "last_failure_before_recovery": 12 if mode == "control" else 0,
             "last_failure_after_recovery": 12 if mode == "control" else 0,
             "production_updater_failure": probe.CONTROL_DIAGNOSTIC + ": schema control" if mode == "control" else ""}
    if mode != "control":
        proof.update({"empty_long_ray_cleared": True, "long_range_nearby_block_rendered": "Stone",
                      "long_range_nearby_item_rendered": "Diamond"})
    if mode == "single-region":
        proof.update({"single_region_long_ray_owned": True, "far_target_owned": True, "far_target_distance": 24.0,
                      "default_range_far_target_absent": True, "extended_range_far_target_rendered": "Stone"})
    else:
        proof.update({"first_unowned_chunk": {"x": 32, "z": 0}, "unowned_target_confirmed_before_query": True})
    return proof


def valid_shutdown(mode="candidate"):
    value = expected(mode)
    return {"schema": 2, "status": "PASS", "mode": mode, "boot_token": TOKEN, "source_commit": SOURCE,
            "server_stopping": True, "probe_finished": True, "installed_addon_sha256": value["addon_sha256"],
            "installed_core_sha256": value["server"]["core"]["sha256"]}


def valid_console(mode="candidate"):
    version = expected(mode)["addon_version"]
    lines = ["Enabling Slimefun v4.1.75", f"Enabling SlimeHUD v{version}", "Enabling SlimeHudRegionProbe v1.0",
             "Done (1.0s)", f"SLIMEHUD_REGION_PROBE_PASS {mode} {TOKEN}", "Stopping server",
             "Disabling SlimeHudRegionProbe v1.0", f"SLIMEHUD_REGION_PROBE_SHUTDOWN {TOKEN} true",
             f"Disabling SlimeHUD v{version}", "Disabling Slimefun v4.1.75"]
    return "".join("[12:00:00 INFO]: " + line + "\n" for line in lines)


def plugin_jar(path, name, version, source=SOURCE, ordinary_commit=SOURCE):
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("plugin.yml", f"name: {name}\nversion: '{version}'\n")
        jar.writestr("git.properties", f"git.source.commit={source}\ngit.commit.id={ordinary_commit}\n")


class ProofContractTests(unittest.TestCase):
    def test_complete_receipts_pass_for_each_mode(self):
        for mode in ("control", "candidate", "single-region"):
            with self.subTest(mode=mode):
                probe.validate_proof(valid_proof(mode), expected(mode), TOKEN)
                probe.validate_shutdown(valid_shutdown(mode), expected(mode), TOKEN)
                probe.validate_console(valid_console(mode), expected(mode), TOKEN)

    def test_missing_proof_fields_fail_even_when_status_says_pass(self):
        for mode in ("control", "candidate", "single-region"):
            for key in valid_proof(mode):
                with self.subTest(mode=mode, missing=key):
                    incomplete = valid_proof(mode)
                    del incomplete[key]
                    with self.assertRaises(RuntimeError):
                        probe.validate_proof(incomplete, expected(mode), TOKEN)

    def test_wrong_identity_ownership_or_incomplete_phases_fail(self):
        changes = {"mode": "control", "boot_token": "b" * 32, "source_commit": "f" * 40,
                   "installed_addon_sha256": "f" * 64, "installed_core_sha256": "f" * 64,
                   "server_name": "Purpur", "server": "26.3-4-597e413 (MC: 26.3)", "minecraft_version": "26.2",
                   "slimehud_version": "2.0.4", "native_entity_owned": False, "unowned_target_confirmed_before_query": False,
                   "player_accessor_native_handle_matches_owner": False,
                   "player_accessor_scheduler_matches_owner": False, "player_accessor_owned": False,
                   "owned_update_checks": 4, "production_updates": 4, "last_failure_after_recovery": 1,
                   "production_updater_failure": "unexpected", "empty_long_ray_cleared": False, "configured_range": 8}
        for key, value in changes.items():
            with self.subTest(field=key):
                invalid = valid_proof()
                invalid[key] = value
                with self.assertRaises(RuntimeError):
                    probe.validate_proof(invalid, expected(), TOKEN)

    def test_other_control_failure_or_nearby_only_range_does_not_pass(self):
        invalid = valid_proof("control")
        invalid["production_updater_failure"] = "native HUD update: IllegalStateException - unrelated region issue"
        with self.assertRaises(RuntimeError):
            probe.validate_proof(invalid, expected("control"), TOKEN)
        for key, value in (("far_target_distance", 4.0), ("extended_range_far_target_rendered", ""),
                           ("default_range_far_target_absent", False), ("far_target_owned", False)):
            with self.subTest(field=key):
                invalid = valid_proof("single-region")
                invalid[key] = value
                with self.assertRaises(RuntimeError):
                    probe.validate_proof(invalid, expected("single-region"), TOKEN)

    def test_missing_or_stale_shutdown_receipts_fail(self):
        for key in valid_shutdown():
            with self.subTest(missing=key):
                invalid = valid_shutdown()
                del invalid[key]
                with self.assertRaises(RuntimeError):
                    probe.validate_shutdown(invalid, expected(), TOKEN)
        for key, value in (("boot_token", "b" * 32), ("server_stopping", False), ("probe_finished", False)):
            invalid = valid_shutdown()
            invalid[key] = value
            with self.assertRaises(RuntimeError):
                probe.validate_shutdown(invalid, expected(), TOKEN)

    def test_both_proof_and_completion_marker_are_required_before_stop(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt, console = root / "proof.json", root / "console.log"
            running = SimpleNamespace(poll=lambda: None)
            console.write_text(valid_console())
            with self.assertRaises(RuntimeError):
                probe.await_native_proof(receipt, console, expected(), TOKEN, running, timeout=0)
            probe.write_json(receipt, valid_proof())
            console.write_text(valid_console().replace("SLIMEHUD_REGION_PROBE_PASS", "WRONG_MARKER"))
            with self.assertRaises(RuntimeError):
                probe.await_native_proof(receipt, console, expected(), TOKEN, running, timeout=0)
            console.write_text(valid_console())
            self.assertEqual(probe.await_native_proof(receipt, console, expected(), TOKEN, running), valid_proof())

    def test_late_proof_or_late_marker_cannot_pass_after_the_deadline(self):
        with tempfile.TemporaryDirectory() as temporary:
            receipt, console = Path(temporary) / "proof.json", Path(temporary) / "console.log"
            probe.write_json(receipt, valid_proof())
            console.write_text(valid_console())
            running = SimpleNamespace(poll=lambda: None)
            # Already beyond the deadline when the newly arrived files are first inspected.
            with patch.object(probe.time, "monotonic", side_effect=[0.0, 2.0]), self.assertRaises(RuntimeError):
                probe.await_native_proof(receipt, console, expected(), TOKEN, running, timeout=1)
            # Deadline expires while a proof/marker is being read and validated.
            with patch.object(probe.time, "monotonic", side_effect=[0.0, 0.5, 2.0]), self.assertRaises(RuntimeError):
                probe.await_native_proof(receipt, console, expected(), TOKEN, running, timeout=1)

    def test_plugin_disable_runtime_and_missing_shutdown_errors_fail(self):
        console = valid_console()
        mutations = [console.replace("Stopping server", "Forced exit"),
                     console.replace("Disabling SlimeHUD v2.0.6", ""),
                     "[12:00:00 INFO]: Disabling SlimeHUD v2.0.6\n" + console,
                     console + "[12:00:01 ERROR]: Error occurred while disabling SlimeHUD\n",
                     console + "[12:00:01 ERROR]: A runtime error\n",
                     console + "[12:00:01 WARN]: java.lang.IllegalStateException: unexpected callback\n"]
        for index, invalid in enumerate(mutations):
            with self.subTest(case=index), self.assertRaises(RuntimeError):
                probe.validate_console(invalid, expected(), TOKEN)

    def test_only_the_specific_control_error_is_permitted(self):
        diagnostic = ("[12:00:01 ERROR]: Thread failed main thread check: Cannot getEntities asynchronously\n"
                      " at io.github.schntgaispock.slimehud.waila.PlayerWAILA.update(PlayerWAILA.java:1)\n")
        probe.validate_console(valid_console("control") + diagnostic, expected("control"), TOKEN)
        with self.assertRaises(RuntimeError):
            probe.validate_console(valid_console() + diagnostic, expected(), TOKEN)
        with self.assertRaises(RuntimeError):
            probe.validate_console(valid_console("control") + diagnostic + "[12:00:02 ERROR]: unrelated error\n", expected("control"), TOKEN)

    def test_native_file_log_and_bootstrap_console_keep_error_rejection(self):
        native = valid_console().replace("[12:00:00 INFO]", "[12:00:00] [Server thread/INFO]")
        partial_console = "[12:00:00 INFO]: Bootstrap completed\n"
        probe.validate_logs(native, partial_console, expected(), TOKEN)
        with self.assertRaises(RuntimeError):
            probe.validate_logs(native + "[12:00:01] [Server thread/ERROR]: Disable failure\n", partial_console, expected(), TOKEN)
        with self.assertRaises(RuntimeError):
            probe.validate_logs(native, "[12:00:00 ERROR]: Bootstrap failure\n", expected(), TOKEN)
        for incomplete in (native.replace("SLIMEHUD_REGION_PROBE_PASS", "MISSING_MARKER"), native.replace(TOKEN, "b" * 32)):
            with self.assertRaises(RuntimeError):
                probe.validate_logs(incomplete, valid_console(), expected(), TOKEN)

    def test_mutated_original_and_installed_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            files = [Path(temporary) / "input.jar", Path(temporary) / "installed.jar"]
            for path in files:
                path.write_bytes(b"expected original bytes")
            hashes = {path: probe.digest(path) for path in files}
            probe.verify_hashes(hashes)
            for path in files:
                path.write_bytes(b"unexpected replacement bytes")
                with self.assertRaises(RuntimeError):
                    probe.verify_hashes(hashes)
                path.write_bytes(b"expected original bytes")

    def test_both_core_source_properties_must_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "core.jar"
            plugin_jar(path, "Slimefun", "4.1.75", ordinary_commit="f" * 40)
            with self.assertRaises(RuntimeError):
                probe.verify_core(path, {"sha256": probe.digest(path), "version": "4.1.75", "source_commit": SOURCE})

    def test_source_artifact_and_server_receipts_are_required_before_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            addon, core, server = root / "addon.jar", root / "core.jar", root / "server.jar"
            plugin_jar(addon, "SlimeHUD", "2.0.6")
            plugin_jar(core, "Slimefun", "4.1.75")
            server.write_bytes(b"pinned native launcher")
            identity = {"source_commit": SOURCE, "source_tree": "4" * 40, "probe_sources": {"fixture.java": "5" * 64}}
            source = {"schema": 1, **identity, "addon_version": "2.0.6", "addon_sha256": probe.digest(addon)}
            native = {"schema": 1, "family": "folia", "minecraft": "26.3", "build": "3", "channel": "ALPHA",
                      "server_launcher_sha256": probe.digest(server),
                      "core": {"sha256": probe.digest(core), "source_commit": SOURCE, "version": "4.1.75"}}
            args = SimpleNamespace(source_receipt=root / "source.json", server_receipt=root / "native.json",
                                   expected_source=SOURCE, mode="candidate", addon=addon, core=core, server_template=root)
            probe.write_json(args.source_receipt, source)
            probe.write_json(args.server_receipt, native)
            with patch.object(probe, "source_identity", return_value=identity):
                probe.expectations(args)
                for field, value in (("source_commit", "f" * 40), ("probe_sources", {}), ("addon_sha256", "f" * 64)):
                    invalid = deepcopy(source)
                    invalid[field] = value
                    probe.write_json(args.source_receipt, invalid)
                    with self.subTest(field=field), self.assertRaises(RuntimeError):
                        probe.expectations(args)
                probe.write_json(args.source_receipt, source)
                for field, value in (("family", "paper"), ("server_launcher_sha256", "f" * 64), ("build", None)):
                    invalid = deepcopy(native)
                    invalid[field] = value
                    probe.write_json(args.server_receipt, invalid)
                    with self.subTest(field=field), self.assertRaises(RuntimeError):
                        probe.expectations(args)


if __name__ == "__main__":
    unittest.main()
