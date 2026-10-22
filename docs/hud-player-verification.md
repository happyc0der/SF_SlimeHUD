# Crouch/look HUD and WIT verification — 2026-10-21

## Result and scope

The existing WIT integration is present, and SlimeHUD's native block and dropped-item identification works without WIT. Native identification is always active while enabled; crouching is supported but is not an activation requirement. WIT's `core.mode: sneaking` gates WIT's own renderer and hands display ownership back to native SlimeHUD when the player stands up.

Verification of the unpublished 2.0.5 source exposed concrete targeting, timing, and display-ownership defects. This change fixes them without changing saved preference paths, command permissions, item identities, the default display, or the default update interval. Publication and the aggregate addon ZIP remain held.

## Reproduced failures and fixes

| Scenario | Original master `9a83f5b` | Corrected candidate |
| --- | --- | --- |
| Look at young/mature wheat, redstone dust, or a torch | No target label in the connected client's BossBar packets | Selection-outline targeting identifies the actual block and supplies crop age/redstone power |
| Native update interval of one tick | Every deferred result became obsolete before publication; no stone label | Already-owned inspection/publication runs immediately; non-owned work still uses its scheduler |
| A block or dropped-item inspection throws after a previous target | Previous target name and details remain visible | Empty snapshot clears the obsolete label; older failures still cannot clear a newer target |
| Stand up after WIT rendered in sneaking mode | Two simultaneous BossBars: retained WIT bar and resumed native bar | WIT's previous display is removed once before native takeover |
| Invalid saved WIT display type | Native display suppressed although WIT has no registered renderer | Native fallback remains available until a valid WIT renderer is selected |
| Native BossBar cleanup or native-to-WIT ActionBar takeover | Native cleanup can send a blank packet on the shared ActionBar | Cleanup affects only the native channel and preserves a newly owned WIT ActionBar |

The WIT bridge also consumes failures for blocks it owns, so WIT's generic fallback does not compete with the native fallback. Repeated failed attempts do not repeatedly erase native ActionBar text. A successful WIT provider can recover ownership. Reload cleanup is bound to the prior display claim and cannot erase a newer one; quit and partial-hook failure cleanup retain no stale session registration.

## Connected-client evidence

The disposable server uses Paper 1.21.11 build 132, Java 25, Slimefun Legacy 4.1.76, and the matching WIT `1.9.5-mc.1.21.11` build for WIT cases. A real offline protocol client connects over loopback and records actual BossBar and ActionBar packets. The fixture places native blocks and registered Slimefun test blocks, orients the connected player, and exercises commands and sneaking state. It does not replace the installed production HUD implementation.

Both original and corrected JARs were tested with the same fixture. The corrected candidate passed 36 scenarios: 17 native, 16 WIT, and three after restarting the same saved world. All 68 acceptance assertions passed, including the original-code negative controls and unchanged candidate JAR checks. Observed output includes:

| Target or operation | Observed result |
| --- | --- |
| Stone, standing and crouching | `Stone [⛏]` and preferred-pickaxe detail |
| Young and mature wheat | `Wheat` with `Age: 1/7` and `Age: 7/7` |
| Redstone dust | `Redstone Wire` with `Power: 0/15` |
| Torch | `Torch` |
| Electric Furnace | `Electric Furnace`, `Idle`, and `0/64 J` |
| Solar Generator | `Solar Generator` and `☀ 2 J/t` |
| Twelve dropped diamonds | `Diamond` and `Stack: 12` |
| Native interval 1, 2, and 5 ticks | Stone remains identified at each setting |
| Look away while using ActionBar | Previous label clears |
| Native HUD toggle off/on | Display hides and returns |
| WIT crouch/stand transition | Exactly one appropriate BossBar after each transition |
| Invalid WIT type, followed by valid type | Native fallback, then WIT recovery |
| WIT enabled/disabled, type change, and reload | Display ownership recovers and no duplicate display remains |
| Restart with the saved ActionBar preference | Standing/crouching identification and look-away clearing still work |

The same-world restart preserved both the bytes and modification time of `player.yml`. Every test server exited normally with code zero, and the fixture/HUD error lists remained empty. [The reproducible connected-client harness](../scripts/player-hud-fixture/README.md) documents the exact commands and pinned dependencies. [The recorded evidence](verification/hud-player-verification.json) includes observed target text, acceptance assertions, original/candidate identities, and input checksums.

The raw client decoder handles both structured and literal-string NBT text components. An initial apparent missing WIT vanilla suffix was a decoder defect: the raw packets already contained the crop/tool details. No production change was made for that false signal.

### Exact tested inputs

- Original SlimeHUD source: `9a83f5b85efee68421d49959492477a0d8efd209`.
- Original local 2.0.5 JAR SHA-256: `43edc50c72a914b9025cf69c5f903b730234380a516fefde3373c99ba9e2742b`.
- Corrected local 2.0.5 JAR SHA-256: `69978c17b646d8a91845e9de0e635bfded3cd6f66697c34fdd251ece4f9ee85e` (73,039 bytes).
- Slimefun Legacy 4.1.76 source: `e7ba40469eae1aa54b4347af19e58341f492edcb`; JAR SHA-256: `e11d024fa3ed4aca3f891d61af61f2542ec62efeeda7a98646941247331da659`.
- Paper launcher SHA-256: `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`.

The candidate contains Java 21 class files and no JUnit, Mockito, Byte Buddy, or probe classes. WIT remains a soft dependency.

## Automated behavior checks

The full Maven package run passed 55 tests with no failures or errors. The new tests exercise production failure handling and reflected integration boundaries:

- Native failure and channel cleanup: nine tests. Original failure-cleanup controls had two expected failures; original channel-cleanup controls had four expected failures. Corrected behavior passes.
- WIT ownership, unavailable renderers, failed-render recovery, reload races, failed rehook, and quit cleanup: sixteen tests.
- Machine progress/energy independence, generator coverage outside process holders, and addon-handler priority/fallback/recovery: three tests.
- The existing twenty-seven settings preservation and ray-boundary tests remain passing.

These controlled tests complement the connected-client run. The existing native Folia ownership fixture continues to verify long-range ownership boundaries, nearby block/item preservation, negative controls, and restart receipts. Its intentionally synthetic player accessor now uses a test-only `CraftPlayer` wrapper backed by the actual native pig's handle and scheduler, allowing the unmodified server ownership API to check the real owner. The receipt requires handle identity, scheduler identity, and successful native ownership. The fixture compiles against the real Paper 1.21.11 native server JAR; all 13 receipt-control tests pass both normally and with optimized Python. This fixture is not a connected-client test; the exact Folia 26.3 native runtime remains a separate CI gate.

## Limits

The connected client verifies server-side display packets and target text, not a human screenshot, font appearance, Bedrock/Geyser translation, or AlbionMC's live installed configuration. It covers representative vanilla and registered Slimefun targets, not every addon callback or every ItemsAdder/Pylon/Rebar custom-block provider. Non-item entity information comes from WIT.

WIT's own native code and targeting are version-specific. The matching WIT build was tested on Paper 1.21.11; this does not establish every WIT gameplay path on Folia or another Minecraft version. Native SlimeHUD's separate Folia ownership and compile/runtime gates retain their own scope.

The disposable local environment logged an unrelated Mojang public-key DNS lookup failure. The runner records that separately from fixture/HUD errors; the successful HUD evidence must not be summarized as a completely error-free server log.
