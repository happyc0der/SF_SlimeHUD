# SF_SlimeHUD — native HUD for Slimefun Legacy

Native BossBar/ActionBar information for Minecraft **1.21.11+** on Paper/Purpur/Leaf and experimental Folia.

## Next maintenance release: 2.0.7 (candidate)

This update corrects the generated first-run settings and clarifies player toggling. The v2.0.6 addon ZIP already includes standalone SlimeHUD and persistent opt-in; the upstream standalone GitHub release still lists v2.0.5. This candidate is not published until fresh verification passes.

```yaml
waila:
  disabled: false
  default-enabled: false # New players start with their HUD off
  crouch-only: true # Default; Shift-only display
  default-display: bossbar
  tick-rate: 5
```

Holding Shift displays the block, machine or dropped item you look at. Releasing Shift hides the native HUD immediately and invalidates pending region/item lookups. A fresh inspection runs on the player's scheduler after crouching. With `crouch-only: false` (optional override), the native HUD remains visible while standing.

Players without a saved HUD choice start **opted out**. Use `/slimehud toggle` to opt in (and again to turn it back off); the choice persists in `player.yml` across restarts. Players with an explicitly saved `waila: true` stay opted in. Opted-out players do not allocate native HUD controllers or repeating display tasks.

Per-player `/slimehud toggle`, saved `player.yml` preferences, global/world disable settings, and BossBar/ActionBar selection remain supported. To permit players to opt in, keep `waila.disabled: false` even when `waila.default-enabled: false`.

## Native functionality

- Slimefun machine progress, energy generation/storage, cargo channels and network sizes
- Vanilla block details: crops, redstone, spawners and tool suggestions
- Dropped vanilla/Slimefun item identification
- Native JustEnoughGuide range/vanilla overrides, independent of WIT

## Commands

- `/slimehud toggle` — enable or disable
- `/slimehud display bossbar` / `/slimehud display actionbar`
- `/slimehud status` — controller, target and native visibility mode
- `/slimehud reload` — reload configuration (admin)

Requires Slimefun Legacy; Java 21 bytecode and baseline Paper 1.21.11 support. Folia 26.3 remains experimental. The historical WIT tests and verification are archived under `docs/`; WIT is no longer a runtime dependency.

A fresh installation creates config.yml with global disable OFF, player default OFF, and crouch-only ON. The plugin logs the effective settings and configuration path on startup. `/slimehud status` reports the installed version, global-disable flag, default-enabled flag and whether the player's opt-in choice is saved. If the server says `waila.disabled: true`, it is a global administrator lock, not the player opt-in default.

Publication requires a green multi-version build/runtime matrix, a new version number, and regeneration of the Slimefun Legacy addon ZIP from the verified source.
