# Native HUD ownership regression

## Failure and fix

`PlayerWAILA` starts its update on the player's entity scheduler, but the native `World.rayTrace` call also searches an expanded box for dropped items. On Folia, a long per-player or configured range can extend that box into chunks the current thread does not own. Scheduling the later block/item inspection on its owner does not protect this initial query.

The control reproduced `IllegalStateException: Cannot getEntities asynchronously` on Folia 26.3 build 3 (`597e413`), with an origin at x=8.5 and a range of 508 blocks. Chunk x=32 was confirmed unowned immediately before the production query. The first failure is recorded by the real installed SlimeHUD updater, and a later local stone target confirms that updater recovery still works.

`HudRayTraceBounds` checks every chunk in the same expanded horizontal rectangle used by the native entity query. A fully owned ray keeps its exact distance. An unsafe long ray uses a bounded search for the last owned distance, preserving nearby targets. If even the origin's item-hit padding is unowned, the updater clears the display without making a world ray query. The existing region/entity callbacks still inspect the selected target and publish its snapshot back to the player owner.

The ownership API already exists on the Paper 1.21.11 floor:

- [Paper 1.21.11 Bukkit ownership API](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-api/src/main/java/org/bukkit/Bukkit.java)
- [Folia 26.3 build metadata](https://fill.papermc.io/v3/projects/folia/versions/26.3/builds)

## What the fixture proves

`scripts/folia_raytrace_probe.py` creates a new disposable loopback world, installs the requested core and addon JARs, and compiles a test-only probe outside the production plugin. It never replaces an existing fixture directory.

The probe uses synthetic `Player` accessors backed by an actual native pig's location and entity scheduler. Native `World`, block, item, region scheduler, and entity scheduler calls are real. The installed production `PlayerWAILA` bytecode is unchanged. Test-only allocation bypasses the constructor's connected-client BossBar registration; a display sink captures the resulting target label.

The candidate must:

- Render an ordinary nearby stone target at the default eight-block range.
- Avoid the control's ownership failure and clear the old label when a long ray points into empty space.
- Render nearby stone and dropped diamond targets while the same long range remains configured.
- On Paper/Purpur, miss a real owned stone target 24 blocks away at range eight, then render that same target at the configured long range. This detects an accidental eight-block cap.
- Shut down normally, repeat the checks after restarting the same world, and preserve the existing player settings file's exact bytes and modification time.

Every accepted boot must provide a fresh token, its exact mode, server software/Minecraft/build, enabled plugin versions, source commit, installed addon/core hashes, ownership observations and the complete production-update count. The probe also writes a separate shutdown receipt from `onDisable`; it must carry the same token, a completed probe and `Bukkit.isStopping() == true`. Exit code zero alone is insufficient. The runner waits for both the proof and its exact completion marker before stopping the server, checking the deadline before polling and before acceptance. Completion and lifecycle markers must appear in the native file log, which is copied per boot; redirected console output is also checked for bootstrap errors. The runner rejects missing fields, unexpected enable/runtime/disable errors in either log, early plugin disable, and any startup/probe/shutdown timeout. Only the specific native entity-query ownership diagnostic is permitted for the negative control.

The build job records the clean source commit/tree, probe source hashes and candidate JAR hash before runtime jobs start. The server resolver separately records its intended software/Minecraft/build and launcher hash before its first boot. The core is pinned to the public 4.1.75 JAR: SHA-256 `206cd1e54e81bf7d3968b2b2a4bd698f1271dca9dd474f2aebf4a7a195c1b060`, with both `git.source.commit` and `git.commit.id` equal to `7f702e3565a381aeeefd99555f2c8fe818858e9c`. The native runner checks those independent receipts before copying inputs, then compares original and installed JARs, receipts and source again before and after each boot. Expectations are never inferred from a successful probe's output.

Acceptance uses explicit checks that remain active with Python optimization. The separate receipt tests intentionally remove required fields and alter source, artifact, mode, ownership, range and shutdown evidence; CI runs those controls with both ordinary Python and `python -O`. These synthetic receipt tests validate rejection behavior and are separate from the native runtime evidence.

The released 2.0.4 negative control must fail in the production native-query phase with an ownership diagnostic, then recover its nearby display. CI pins that public JAR to SHA-256 `a0185cd2a3dddca3cb2a9d0b26a115764cea2a7b723717c4678fa81705c342c9`. Its 24 class files are identical to the previously audited canonical 2.0.4 bundle control; the archives are different files.

The local candidate was compiled against Paper 1.21.11, 26.1.2, 26.2, and stable 26.3 APIs. The new geometry tests cover positive and negative coordinates, diagonal query rectangles, item-hit padding, normalized non-horizontal directions, full-range preservation, and invalid rays. Existing settings tests remain part of the full test run.

## Running the native check

Build the candidate from a clean committed checkout and create its source receipt. Then create a disposable server template by running `scripts/runtime_smoke.sh` with the desired native server; the smoke script writes `server-receipt.json` before starting it. Its `cache/`, `libraries/`, `versions/`, and `server.jar` are reused without modifying that template. For example:

```bash
mvn -B -ntp clean package
python3 scripts/folia_raytrace_probe.py source-receipt \
  --addon target/SF_SlimeHUD2.0.5.jar \
  --expected-source "$(git rev-parse HEAD)" \
  --output target/SF_SlimeHUD2.0.5-source.json
bash scripts/runtime_smoke.sh folia 26.3 ANY target/SF_SlimeHUD2.0.5.jar
python3 scripts/folia_raytrace_probe.py run \
  --server-template build/runtime-smoke-folia-26.3 \
  --server-receipt build/runtime-smoke-folia-26.3/server-receipt.json \
  --source-receipt target/SF_SlimeHUD2.0.5-source.json \
  --expected-source "$(git rev-parse HEAD)" \
  --core build/runtime-smoke-folia-26.3/plugins/Slimefun-Legacy4.1.75.jar \
  --addon target/SF_SlimeHUD2.0.5.jar \
  --java-home "$JAVA_HOME" \
  --work-dir build/native-hud-folia-26.3 \
  --mode candidate --restart
```

Use `--mode control` with the pinned 2.0.4 JAR and the same candidate source receipt for the negative control. That receipt binds the harness source; the control addon is independently bound to its released checksum. Use `--mode single-region --restart` for Paper/Purpur; that mode requires the complete long ray to be owned and the real 24-block target to render. A different core input requires updating `SLIMEFUN_VERSION`, `SLIMEFUN_SHA256` and `SLIMEFUN_SOURCE` together before the smoke run.

The resulting `verification.json` contains the pre-bound expectations, separate boot and shutdown receipts, target labels, ownership evidence, original/installed hashes before and after each boot, settings preservation, and probe/log SHA-256 hashes. CI uploads those receipts and raw logs even when validation fails. Failed or timed-out runs do not produce a successful aggregate receipt.

## Scope and release status

The native fixture has zero connected player clients. It establishes the ownership fix and real block/item scheduling with an observed production target label. It does not establish network display packets, WIT rendering, moving-player region handoff, every Slimefun machine handler, or whole-addon integration compatibility. Those claims need separate representative player/integration tests.

Folia 26.3 build 3 is ALPHA. It is labeled experimental, although this focused native regression remains a required check for this fix. Paper/Purpur 26.3 are ordinary runtime targets. No new Folia support flag is added to any other addon.

2.0.5 is an unpublished candidate. The build workflow's `publish` input defaults to false. Pushes do not publish. A future explicit publication on `master` must pass the complete compile matrix and the reusable workflow's required runtime gates for the same source SHA, still be current master, and find both the new tag and release absent. Existing advisory legacy runtime lanes remain advisory; all new 26.3 native checks are required. Publication uses the runtime-tested artifact, creates a new tag atomically, and does not delete or overwrite historical tags/assets. If creation fails after the tag was created, the workflow stops for maintainer review instead of deleting or reusing the tag automatically.
