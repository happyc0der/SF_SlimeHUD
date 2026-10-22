# SF_SlimeHUD — native HUD for Slimefun Legacy

Native BossBar/ActionBar information for Minecraft **1.21.11+** on Paper/Purpur/Leaf and experimental Folia.

## Next maintenance release: 2.0.6 (candidate)

This candidate restores SlimeHUD's standalone native display, removes the WIT bridge and adds optional **crouch-only** mode. The initial candidate passed 39 unit tests and all 16 native runtime jobs; 2.0.6 remains unreleased until this exact version also passes its build and runtime gates.

```yaml
waila:
  disabled: false
  crouch-only: true # Optional; defaults to false
  default-display: bossbar
  tick-rate: 5
```

Holding Shift displays the block, machine or dropped item you look at. Releasing Shift hides the native HUD immediately and invalidates pending region/item lookups. A fresh inspection runs on the player's scheduler after crouching. With `crouch-only: false` (default), the native HUD remains always-on.

Per-player `/slimehud toggle`, saved `player.yml` preferences, global/world disable settings, and BossBar/ActionBar selection are preserved.

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

Publication requires a green multi-version build/runtime matrix, manual release authorization, and regeneration of the Slimefun Legacy addon bundle from the verified source.
