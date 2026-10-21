# Connected-client HUD regression fixture

This harness connects a real offline Java protocol client to a disposable Paper
1.21.11 server. It records BossBar and ActionBar packets from the installed
production HUD JAR. The fixture plugin only creates known test targets, orients
the player, changes the polling interval, and logs state. It does not replace the
HUD updater or renderer.

The server binds only to localhost. Ports 25591 and 25592 must be free. Run the
cases sequentially. Use a fresh work directory; preparation refuses to overwrite
an existing directory. The `restart` case intentionally reuses the same saved
candidate-native world, player settings, and installed JAR.

## Requirements

- Java 25, Python 3, Node.js 22+/npm, and curl (Node 24.19.0 was used for the recorded run).
- A baseline HUD JAR built from commit
  `9a83f5b85efee68421d49959492477a0d8efd209` and the candidate HUD JAR.
- The core JAR whose source and checksum are supplied below. The recorded run used
  Slimefun Legacy 4.1.76, source
  `e7ba40469eae1aa54b4347af19e58341f492edcb`, SHA-256
  `e11d024fa3ed4aca3f891d61af61f2542ec62efeeda7a98646941247331da659`.

`prepare.py` downloads checksum-pinned Paper build 132 and published WIT
1.9.5-mc.1.21.11, bootstraps a flat disposable world, and compiles the fixture.
The dependency lock pins the protocol client dependencies. Test dependencies,
servers, and fixture classes never enter the distributed plugin JAR.

## Commands

Run from the repository root, substituting the four input paths:

```bash
HUD_JAVA_HOME=/path/to/jdk-25
HUD_CORE=/path/to/Slimefun-Legacy4.1.76.jar
HUD_BASELINE=/path/to/baseline/SF_SlimeHUD2.0.5.jar
HUD_CANDIDATE=/path/to/candidate/SF_SlimeHUD2.0.5.jar
HUD_WORK_DIR=/path/to/new-disposable-hud-verification

npm ci --prefix scripts/player-hud-fixture --ignore-scripts --no-audit --no-fund

python3 scripts/player-hud-fixture/prepare.py \
  --work-dir "$HUD_WORK_DIR" --java-home "$HUD_JAVA_HOME" \
  --core "$HUD_CORE" --addon "$HUD_CANDIDATE" \
  --core-source e7ba40469eae1aa54b4347af19e58341f492edcb \
  --core-sha256 e11d024fa3ed4aca3f891d61af61f2542ec62efeeda7a98646941247331da659

node scripts/player-hud-fixture/run-probe.js \
  --work-dir "$HUD_WORK_DIR" --java-home "$HUD_JAVA_HOME" \
  --label baseline-native --addon "$HUD_BASELINE" --mode native
node scripts/player-hud-fixture/run-probe.js \
  --work-dir "$HUD_WORK_DIR" --java-home "$HUD_JAVA_HOME" \
  --label baseline-wit --addon "$HUD_BASELINE" --mode wit
node scripts/player-hud-fixture/run-probe.js \
  --work-dir "$HUD_WORK_DIR" --java-home "$HUD_JAVA_HOME" \
  --label candidate-native --addon "$HUD_CANDIDATE" --mode native
node scripts/player-hud-fixture/run-probe.js \
  --work-dir "$HUD_WORK_DIR" --java-home "$HUD_JAVA_HOME" \
  --label candidate-wit --addon "$HUD_CANDIDATE" --mode wit
node scripts/player-hud-fixture/run-probe.js \
  --work-dir "$HUD_WORK_DIR" --java-home "$HUD_JAVA_HOME" \
  --label candidate-native --addon "$HUD_CANDIDATE" --mode restart

python3 scripts/player-hud-fixture/verify-results.py --work-dir "$HUD_WORK_DIR"
```

Each run records raw packets, observed display state, input hashes, normal server
shutdown, and errors. The verifier requires the old-code negative controls and
all candidate cases. The first candidate run saves ActionBar as the player's
preference; the restart requires that preference to work and `player.yml` bytes
and modification time to remain unchanged.

## Scope and limitations

The recorded comparison has 36 candidate scenarios and 68 acceptance assertions.
It covers vanilla solid/passable blocks, crop ages, redstone power, idle electric
furnace energy, solar generation, dropped-item counts, polling rates 1/2/5,
player toggles, display choices, WIT crouch/stand ownership, invalid WIT display
fallback, WIT reload, and persistent preference restoration.

This verifies server output to a connected protocol client. It does not inspect
graphical client rendering or resource-pack fonts, Bedrock/Geyser output, every
addon handler, or multiplayer Folia region handoff. Native 26.3 ownership tests
remain a separate fixture.

The recorded local environment could not resolve Mojang's authlib public-key
endpoint. The full logs retain that startup network error; the verifier permits
only that specifically identified environment error and rejects HUD/fixture
failures. A result from this harness is not a claim that the entire server boot
was free from external-service errors.
