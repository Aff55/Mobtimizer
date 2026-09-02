# Mobtimizer

A Fabric mod for Minecraft 26.2 that merges same-kind mobs into a single ticking entity
carrying a member count, cutting the performance cost of large farms without changing how
those farms behave.

Merged members stay in the world as real, frozen entities rather than being deleted and
counted. That is the design's central bet: removing the mod gives you back every mob,
instead of leaving you with one cow where a farm used to be.

## Status

**Phase 1 complete.** Mobs merge, members are kept as frozen entities, counts show on
hover, breeding still works, and `/mobtimizer unstack` restores everything.

Phase 1 is about making stacking correct and safe, not about scaling every mechanic. The
full design — where a 100-cow stack breeds, dies and produces like 100 real cows — spans
six phases; see the [design spec](docs/superpowers/specs/2026-08-25-mobtimizer-design.md).

| Works now | Not yet |
|---|---|
| Same-kind mobs merge once a crowd forms | Damage routing by source (phase 2) |
| Members stay real, frozen entities | Scaled breeding — 100 wheat → 50 babies (phase 3) |
| Count shown on hover | Scaled production: milk, wool, eggs (phase 4) |
| Babies and breeding animals stay unstacked | Virtual (non-entity) storage for huge stacks (phase 5) |
| Vanilla two-animal breeding | Mob-cap reporting (phase 6) |
| Host death promotes a member | |
| Survives world reload | |
| `/mobtimizer` commands | |

Verified in play-testing on cows and sheep.

### How breeding behaves today

Feeding a stacked animal releases **one** member as a partner and puts *that* animal in
love — never the stack itself. Feed it, feed the partner, and they breed exactly like two
loose animals. Both parents then sit out their normal cooldown unstacked, and rejoin the
herd automatically once it expires. The calf stays loose until it grows up.

Feeding a 100-stack does not yet yield 50 babies. That is phase 3.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.158.0+26.2 |
| Java | 25+ |

Works on a server with completely vanilla clients connected — the count label uses the
mob's ordinary custom name, so nothing client-side is required. It also runs fine in
single-player.

## Installation

You need **two** jars in your `mods` folder: Mobtimizer and
[Fabric API](https://modrinth.com/mod/fabric-api). Fabric API is not bundled.

### Single-player / client

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Download `mobtimizer-0.1.0.jar` from the
   [Releases page](https://github.com/Aff55/Mobtimizer/releases).
3. Drop it and Fabric API into your `mods` folder:
   - **Vanilla launcher** — `%APPDATA%\.minecraft\mods` (Windows),
     `~/Library/Application Support/minecraft/mods` (macOS), `~/.minecraft/mods` (Linux)
   - **PrismLauncher / MultiMC** — right-click the instance → *Edit* → *Mods* → *Add file*
   - **Modrinth / CurseForge app** — the instance's own `mods` folder
4. Launch the Fabric profile for 26.2.

Confirm it loaded: the log prints `Mobtimizer initialising`, and the mod appears in Mod
Menu if you have it.

### Dedicated server

1. Install the Fabric server launcher for 26.2.
2. Put `mobtimizer-0.1.0.jar` and Fabric API in the server's `mods` folder.
3. Restart the server.

Clients need nothing.

### Building from source

Requires a JDK 25. No JDK on your `PATH`? Point `JAVA_HOME` at one — for example the JBR
bundled with Android Studio:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build
```

The jar lands in `build/libs/mobtimizer-0.1.0.jar`.

> Take the plain `mobtimizer-0.1.0.jar` — **not** `mobtimizer-0.1.0-sources.jar`, which
> sits beside it and contains no compiled classes. Fabric will recognise the sources jar
> as a mod because it carries a `fabric.mod.json`, then fail to find any code in it.

`./gradlew runClient` launches a dev client with the mod already loaded, which is usually
faster than building and copying while iterating.

### Mod icon

`fabric.mod.json` points at `src/main/resources/assets/mobtimizer/icon.png`. Drop a
square PNG there (128×128 or 256×256) and rebuild; Mod Menu picks it up automatically.
Until that file exists you will see a harmless `broken icon` warning in the log.

## Commands

| | |
|---|---|
| `/mobtimizer unstack all` | Release every stacked mob in the current level |
| `/mobtimizer unstack here [radius]` | Release stacks within `radius` blocks (default 16) |
| `/mobtimizer unstack type <id>` | Release stacks of one entity type, e.g. `minecraft:cow` |
| `/mobtimizer stats` | Report how many mobs are held in how many stacks |
| `/mobtimizer reload` | Re-read the config file |

All require permission level 2 (gamemasters).

## Configuration

`config/mobtimizer.json`, created on first run. `/mobtimizer reload` applies changes
without a restart.

| Setting | Default | What it does |
|---|---|---|
| `merge.enabled` | `true` | Master switch for merging |
| `merge.radius` | `8.0` | How far apart mobs can be and still merge |
| `merge.crowdThreshold` | `4` | Mobs required before merging starts (see below) |
| `merge.scanIntervalTicks` | `20` | Ticks between merge scans — higher is cheaper |
| `merge.maxMergesPerScan` | `64` | Cap on merges per scan, to spread out the work |
| `display.enabled` | `true` | Show the count label at all |
| `display.format` | `"%s ×%d"` | Label format: mob name, then count |
| `display.alwaysVisible` | `false` | `true` shows the label without hovering |
| `freeze.mode` | `"AGGRESSIVE"` | `AGGRESSIVE` skips member ticks entirely; `CONSERVATIVE` only suppresses AI |
| `entities.mode` | `"DENYLIST"` | `DENYLIST` stacks everything except the list; `ALLOWLIST` stacks only the list |
| `entities.denylist` | villager, wandering trader, wither, ender dragon | Never stacked |
| `entities.allowlist` | empty | Used only in `ALLOWLIST` mode |
| `safety.autoUnstackOnVersionChange` | `true` | Unstack everything if the world was last opened on a different MC version |
| `storage.virtualSpillThreshold` | `512` | Reserved for phase 5; unused today |

### The crowd threshold

`crowdThreshold` is a gameplay feature, not a tuning knob: below it, nothing merges at
all, so a couple of pet animals standing together never fuse. At the default of `4`,
merging starts at the fourth mob.

It counts **true mob count**, not entities — a neighbour that is already a stack of 3
counts as 3. So one 3-stack plus one loose cow reaches 4 and merges.

### What never stacks

Beyond the denylist, a mob is left alone if it is named, leashed, tamed or ownable,
riding or ridden, persistent, has no AI, is invulnerable, is a boss, is a **baby**, is
**in love**, or is on a **breeding cooldown**. Every one of those represents either a
deliberate player setup or a mechanic that would break if the mob were frozen.

## Uninstalling

Delete the jar. Members are real entities the whole time, so they simply wake up on the
next load — no migration step, no lost mobs.

For a clean exit, run `/mobtimizer unstack all` in each dimension first. That removes the
mod's leftovers immediately rather than leaving frozen members to wake on their own.

## Known issues

- **Existing stacks made before 0.1.0 may contain babies.** Fixed going forward, but
  calves already inside a stack stay there. `/mobtimizer unstack all` clears them.
- **A stack loses one member per breeding pair.** Expected — breeding needs two real
  animals. They rejoin after their cooldown.
- **`/mobtimizer stats` says "1 stacks".** Cosmetic.
- **`realisticallySpawnedZombiesWithRandomAttributeNoiseStillMerge` is occasionally
  flaky**, roughly 1 run in 4–10. A random spawn-time field on zombies is not yet in
  `StackKeyFactory.IGNORED_KEYS`; the same class of bug as the documented `LeftHanded`
  case. Affects the test suite, not gameplay.
- **Untested with other mods that rewrite entity ticking.** Lithium in particular is
  known to rewrite death and tick paths. `HostPromotion` deliberately uses Fabric's
  `AFTER_DEATH` event rather than a Mixin to reduce the risk, but this has not been
  verified under load.

## Development

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build
```

Runs 103 gametests against a real server plus the unit tests. Gametests share one level,
so any level-wide assertion must be scoped by position or it will pick up other tests'
entities.

## Credits

Created and maintained by **Aff55**.

## License

[MIT](LICENSE). Copyright (c) 2026 Aff55.
