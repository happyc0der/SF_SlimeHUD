# SF_SlimeHUD 2.0.5 — unreleased candidate

A modernized SlimeHUD fork for **Slimefun Legacy** and Minecraft **1.21.11+** on Paper-family servers.

## Changes prepared for 2.0.5

- Identifies passable targets such as crops, redstone dust, and torches using their selection outline, and clears an obsolete label if block or dropped-item inspection fails.
- Runs inspection and display publication immediately when already on the correct region/entity owner, keeping the HUD responsive even at a one-tick update interval while retaining scheduled handoffs when needed.
- Cleans up the previous WIT display when its sneaking/visibility settings hand control back to the native HUD, validates WIT's selected renderer before delegating, and preserves the shared ActionBar during handoff.
- Keeps native HUD ray queries inside the current thread's owned chunks on Folia, including the extra area used to find dropped items. Long configured or integration-provided ranges are shortened at ownership boundaries while nearby blocks and items remain visible.
- Preserves the exact requested range when the entire query is owned, including ordinary Paper/Purpur operation.
- Clears stale labels when no safe ray or target exists. Block details continue to be read on their owning region and item details on their owning entity scheduler.
- Adds Paper/Purpur 26.3 runtime targets and an experimental Folia 26.3 target, with a native production-updater regression fixture, a released-code negative control, and restart/settings checks.
- Makes releases an explicit manual action after build and runtime checks for the same source commit. Pushes and pull requests validate candidates; existing tags and release assets cannot be overwritten by the release workflow.

This version has not been published. The coordinated Slimefun release and addon ZIP remain on hold. See [crouch/look and WIT verification](docs/hud-player-verification.md) for the connected-client comparison, observed target text, and restart checks. [Native HUD validation](docs/native-hud-validation.md) separately documents the region-ownership fixture and its limits.

## What changed in 2.0.4

- Keeps each player's recurring HUD updater alive if a runtime or linkage error occurs instead of allowing one bad update to permanently stop SlimeHUD.
- Guards asynchronous block and dropped-item inspection so addon/API failures cannot silently kill HUD updates.
- Makes the optional WIT bridge fail open: if WIT rendering throws, SlimeHUD immediately falls back to its native BossBar/ActionBar and continues retrying the bridge.
- Promotes WIT bridge failures from hidden FINE logging to a one-time actionable warning per player session.
- Expands `/slimehud status` with controller state, paused/running state, current target, last HUD error, and WIT fallback state.
- Restores generator information for energy generators that are not machine-process holders, including solar-style generators.
- Keeps machine progress, generator output, and stored-energy settings independent so disabling one does not suppress the others.
- Falls back to SlimeHUD's generic machine/energy handler if an addon's custom HUD callback throws, while retaining the addon callback as first priority when healthy.
- Moves runtime compatibility validation to Slimefun Legacy 4.1.61.

## What changed in 2.0.3

- Adds a compact preferred-tool symbol beside **vanilla block names**.
- Tool symbols are shown only for vanilla blocks handled by the vanilla provider.
- Normal Slimefun blocks/items never receive a vanilla tool symbol.
- Vanilla **Spawner** blocks and Slimefun **RepairedSpawner** are treated specially and report **Pickaxe of Containment** rather than a normal pickaxe.
- Addon spawner machines remain excluded, matching Slimefun Legacy's actual Pickaxe of Containment behavior.
- The held-tool check recognizes the actual Slimefun `PICKAXE_OF_CONTAINMENT` item for spawners.
- Symbols are configurable in `config.yml`, so servers with custom fonts/resource packs can replace the defaults.
- When What Is That? owns the visible HUD, SF_SlimeHUD can now provide the same vanilla block information through the WIT bridge while still respecting WIT's block enabled/blacklist rules.

Default vanilla markers:

- Pickaxe: `⛏`
- Axe: `🪓`
- Shovel: `🪏`
- Hoe: `⚒`
- Pickaxe of Containment: `⛏`

Example native display:

`Stone [⛏] | Tool: Pickaxe`

`Spawner [⛏] | Spawner: Zombie | Tool: Pickaxe of Containment`

The same containment marker is allowed for a Slimefun RepairedSpawner, but not for addon spawner machines.

The symbols are controlled by:

```yaml
vanilla:
  show-tool-symbol: true
  tool-symbols:
    pickaxe: "⛏"
    axe: "🪓"
    shovel: "🪏"
    hoe: "⚒"
    containment: "⛏"
```

Set any symbol to an empty string to hide only that tool marker. Custom resource-pack font glyphs can also be used here.

## What Is That? integration

WIT remains optional. With both plugins installed, WIT owns the visible BossBar/ActionBar while SF_SlimeHUD supplies Slimefun data. By default, SF_SlimeHUD also supplies vanilla block details so tool markers remain consistent:

```yaml
integrations:
  what-is-that:
    enabled: true
    use-slimehud-vanilla: true
```

WIT still controls whether block display is enabled and whether a vanilla block is allowed by WIT's block whitelist/blacklist.

### Looking at blocks and crouching

SlimeHUD's native display identifies vanilla blocks, registered Slimefun blocks, and dropped item stacks without requiring WIT. With the HUD enabled, point at the target; crouching also works. Native identification is always active while enabled, so standing up does not hide it. WIT's separate `core.mode: sneaking` setting controls WIT's own display; SlimeHUD resumes its native display when that mode stops WIT from rendering.

The default native display is BossBar, with an eight-block range and a five-tick update interval. Integrations can override player preferences: the current JustEnoughGuide adapter's default range is five blocks. WIT uses its own range and display choice while it owns rendering. Animal and other non-item entity details belong to WIT; the native SlimeHUD entity target is a dropped item.

Use `/slimehud status` to check whether the native controller is running, which target it sees, and whether WIT is active or native fallback is in use. `WIT bridge: not active` is normal when WIT is not installed. `/slimehud toggle` controls the native HUD and SlimeHUD's contribution to WIT; WIT's own enable/display preferences remain separate.

## Existing 2.0 features

- Java 21 bytecode with Java 21 and Java 25 compatibility builds.
- Paper, Purpur, Leaf, and Folia support targets.
- Native Slimefun machine progress, energy, cargo, and network information.
- Dropped-item targeting for vanilla and registered Slimefun items.
- Vanilla block details for redstone, crops, furnaces, brewing stands, containers, beacons, spawners, farmland, note blocks, and more.
- Persistent BossBar or ActionBar selection.
- Optional What Is That? bridge.
- Native JustEnoughGuide 2.1.65+ adapter support.
- PlaceholderAPI support.
- No InfinityLib runtime dependency.

## Player commands

- `/slimehud toggle`
- `/slimehud display bossbar`
- `/slimehud display actionbar`
- `/slimehud status`

Admin: `/slimehud reload`

## Output

The Maven build produces exactly:

`SF_SlimeHUD2.0.5.jar`

## Compatibility target

- Minecraft 1.21.11+
- Paper
- Purpur
- Leaf
- Folia
- Slimefun Legacy
- Optional What Is That? integration
- Optional SF_JustEnoughGuide 2.1.65+ native adapter

Folia 26.3 is currently an experimental target because the upstream runtime is ALPHA. Its regression check exercises real region ownership and the installed HUD updater; startup admission alone does not establish every gameplay or addon integration path.
