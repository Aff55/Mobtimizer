# Mobtimizer — Design Spec

**Date:** 2026-08-25
**Status:** Approved, pending implementation plan

## 1. Purpose

Mobtimizer reduces the performance cost of large mob populations by merging same-kind mobs
into a single ticking entity that carries a member count, while preserving vanilla behaviour
faithfully enough that farms keep working and produce honest rates.

Existing mob-stacking mods reduce entity count but reimplement vanilla mechanics badly:
breeding either doesn't work, doesn't scale with the count, or bypasses cooldowns; damage
handling ignores the damage source; and stacks under-report to the mob cap so the world
re-spawns the mobs the mod just merged away. Mobtimizer's goal is to be correct first and
fast second, on the grounds that a fast mod with wrong farm rates is not actually useful.

### Success criteria

1. A 100-cow farm ticks as one cow but breeds, dies and produces like 100 cows.
2. Feeding a 100-stack 100 wheat yields exactly 50 babies, respecting the 5-minute cooldown.
3. Removing the mod does not destroy mob populations (see §10).
4. Farm output rates are unchanged from unstacked vanilla, neither buffed nor nerfed.

### Non-goals

- Stacking tamed or owned mobs (wolves, cats, horses, parrots). These have individual
  identity; merging them loses player intent.
- Stacking villagers or wandering traders (trades, professions, gossip).
- Water and ambient mobs in v1 (squid, fish, bats). Cheap to add later, not a priority.
- Custom client rendering. v1 is server-side only.

## 2. Toolchain

| Component | Version |
|---|---|
| Minecraft | 26.2 ("Chaos Cubed", released 2026-06-16) |
| Loader | Fabric Loader 0.19.3 |
| Build | Fabric Loom 1.17-SNAPSHOT |
| Fabric API | 0.158.0+26.2 |
| Mappings | **Official Mojang mappings** |

Minecraft has been unobfuscated since 26.1 and Fabric has discontinued Yarn. All code and
Mixins target official Mojang names directly (`Cow`, `Animal`, `Mob`, `LivingEntity`,
`ServerLevel`), and official mappings now ship real parameter names. This is a meaningful
benefit for a Mixin-heavy mod: injection points are stable and readable.

Required Fabric API modules: `fabric-data-attachment-api-v1` (stack persistence),
`fabric-events-interaction-v0`, `fabric-command-api-v2`, `fabric-lifecycle-events-v1`,
`fabric-gametest-api-v1` (tests).

The mod is server-side. `fabric.mod.json` declares the client environment as unnecessary so
vanilla clients can connect to a server running it.

## 3. Core model

A stack is **one ordinary, fully-ticking vanilla mob** — the *host* — plus N frozen members.
A "stack of 100 cows" is one real `Cow` entity you can see, collide with and hit, plus 99
inert ones. The host is not a custom entity type and has no special class; it is a vanilla
mob with a Fabric data attachment.

This choice preserves compatibility with everything that already understands vanilla mobs:
loot tables, AI, pathfinding, spawn eggs, advancements, `/kill @e[type=cow]`, resource packs,
vanilla client rendering, and other mods.

### 3.1 State ownership

This is the most important structural rule in the design:

> **Static per-member state lives with the member. All time-advancing state is owned by the stack.**

Dormant members do not tick, so they cannot advance their own age, breeding cooldown or wool
regrow timer. If we left those on the members they would silently freeze, and a baby stack
would never grow up. So the stack owns every quantity that changes over time, stored as
run-length-encoded runs, and members own only things that don't change: variant, equipment,
potion effects.

The payoff is that the two storage backends (§4) differ **only** in where static state lives.
All mechanics — breeding, aging, cooldowns, production — are written once against the stack's
RLE runs and work identically in both modes.

### 3.2 Run-length encoding

Time-advancing state is stored as `(count, value)` runs, e.g. `ages = [(50, -23980), (1, -12000)]`.

Because every member's age advances by exactly 1 per tick, runs advance in lockstep and never
fragment on their own. 50 babies born on the same tick remain a single run indefinitely. Runs
only split when something touches an individual member — feeding one baby to speed its growth,
or shearing one sheep.

Storage is therefore O(distinct values), not O(count). A 10,000-member stack costs about the
same as a 50-member one. Runs are merged whenever two become equal.

## 4. Storage backends

Members live behind a single `MemberStore` interface with two implementations.

| | Dormant (default) | Virtual (past threshold) |
|---|---|---|
| Members are | real frozen entities in the world | deduplicated NBT on the host |
| Eliminates | AI, pathfinding, collision, client tracking | all of that, plus entity count, memory, save size |
| Mob cap | correct with no extra work | requires a Mixin (§9) |
| On mod removal | **wakes up as N real mobs** | leaves 1 mob |

`storage.virtualSpillThreshold` (default 512) controls the switch. Set it to `-1` for
always-dormant or `0` for always-virtual.

**Virtual is a compression layer, not a second engine.** It stores one template NBT plus
per-member deltas, which is cheap precisely because the identity predicate (§5) guarantees
members are identical apart from the RLE-tracked fields. When a virtual-mode stack needs to
perform a member-specific operation, it rehydrates only the members it needs, runs ordinary
entity code, and re-compacts. There is exactly one implementation of every game mechanic.

Spilling is tail-first: the head of the stack stays dormant and operations always target the
head, so the common path never touches compacted storage.

## 5. Identity — what may merge

Two mobs merge only if they are *eligible* and share a *stack key*.

### 5.1 Eligibility

A mob is excluded from stacking entirely if any of these hold:

- has a player-assigned custom name (see §8.1 for how the count nameplate avoids this trap)
- is leashed
- is riding a vehicle, or has passengers
- is tamed / has an owner
- has `PersistenceRequired` set
- has `NoAI` set
- is invulnerable, or is a boss
- its entity type is denied by config (§11)

These all represent deliberate player setups. Merging them destroys intent.

### 5.2 Stack key

Rather than hand-writing a variant comparator per mob type — which silently fails for modded
mobs — the stack key is a hash of the mob's serialized NBT with a documented **ignore list**
removed. Everything not on the ignore list must match exactly.

Ignored (allowed to differ between members):

```
UUID, Pos, Motion, Rotation, FallDistance, HurtTime, HurtByTimestamp, DeathTime,
Health, Air, Fire, PortalCooldown, TicksFrozen, Brain,
Age, ForcedAge, InLove, LoveCause,        # owned by the stack as RLE runs
Sheared                                   # regrow timer is stack-owned, see 7.5
```

Everything else — entity type, variant/colour, age *class* (baby vs adult), equipment,
active potion effects, age-lock state (26.x golden dandelion), and any modded NBT — is part
of the key. This gives correct behaviour for modded mobs for free, and the ignore list serves
as explicit documentation of every field allowed to vary.

`Health` is deliberately ignored: a damaged mob merges in and is treated as full health. This
is an accepted inaccuracy (§13).

Keys are computed only during the throttled merge scan and cached on the entity, so NBT
serialization cost is bounded.

## 6. Merging

The merge scanner runs every `merge.scanIntervalTicks` (default 20) over entity-ticking
chunks only.

For each eligible unstacked mob it queries matching mobs within `merge.radius` (default 8.0).
A merge only occurs when at least `merge.crowdThreshold` (default 4) matching mobs are
present. Below that threshold mobs stay completely vanilla — three pet cows behave normally,
a 100-cow farm collapses into a stack.

`merge.maxMergesPerScan` (default 64) bounds per-tick work so a freshly loaded mega-farm
merges over several seconds instead of spiking one tick.

When a mob merges into a stack, its love state is absorbed into the stack's `loveCount` and
its age/cooldown values are added as RLE runs.

## 7. Behaviour

### 7.1 Damage

Damage is routed by source:

**Single-target → kills exactly 1 member.** Any source with a non-null direct entity that is
not an explosion: melee, projectiles, a single mob's attack.

**Area / environmental → kills the whole stack.** Classified by vanilla damage type tags
(`is_explosion`, `is_fire`, `is_fall`, `is_drowning`, `is_freezing`) plus suffocation,
cramming, starvation, lightning, void and `/kill`.

This split is what makes farms work. A hundred real cows in lava all die, so a stack in lava
must all die; a sword swing hits exactly one cow, so it kills exactly one member.

Unknown or modded damage sources default to single-target
(`damage.unknownSourceKillsWholeStack: false`) because the safe failure mode is killing too
few, not wiping a farm.

**Host promotion.** When single-target damage kills the host, the host entity object survives.
The mod drops one mob's loot and XP, consumes one member from the store, copies that member's
RLE state onto the host, and resets its health. Keeping the same entity avoids visual flicker
and avoids churning entity IDs other mods may be tracking. This is behaviourally identical
because the identity predicate guarantees members are interchangeable.

**Whole-stack loot.** The loot table is rolled once per member so random drops vary correctly,
then identical stacks are merged into as few `ItemEntity` drops as possible — spawning 100
item entities would undo the optimization. Rolls are capped at `damage.maxLootRolls`
(default 1000); beyond that the mod rolls the cap and scales the result proportionally.

XP is summed and spawned as the smallest number of orbs that can carry the total.

### 7.2 Hostile attack damage

A stack of hostile mobs deals scaled damage capped at
`damage.hostileDamageMaxMultiplier` (default 3.0). Faithful N× damage from a 50-zombie stack
would one-shot any player through netherite; 1× would make stacked grinders far safer than
real ones. The cap is a deliberate compromise and is configurable.

### 7.3 Breeding

The stack carries `loveCount`, capped at the number of members not on cooldown.

- A plain right-click with a valid food consumes 1 item and increments `loveCount` by 1.
- Shift-right-click spends as much of the held stack as the mob can currently accept.
- Whenever `loveCount >= 2`, it spends 2 and produces 1 baby, places 2 members on cooldown,
  and awards a normal 1–7 XP roll.

Therefore 100 feeds on a 100-stack produce 50 babies; 99 feeds produce 49 babies and leave one
member standing in love mode; and a stack of 1 fed twice produces nothing, exactly like a lone
vanilla cow.

**Cooldown** is enforced per member as RLE runs of `(count, expiryGameTime)`, default 6000
ticks, matching vanilla. Without this a stack would breed faster than real mobs, which would
turn a performance mod into a cheat mod. Configurable via `breeding.enforceCooldown`.

Babies form their own stack, keyed as babies and therefore never merging into the adult stack.

### 7.4 Growth

Baby growth in Java is deterministic: `age` starts at −24000 and increments by 1 per tick,
maturing at 0. It is not random. Feeding a baby is the only thing that desynchronizes members,
reducing that member's *remaining* time by 10% and splitting it into its own run.

The baby stack advances all age runs each stack tick. When a run reaches age >= 0 the whole
run graduates at once: those members are removed from the baby stack, thawed as adults, and
picked up by the normal merge scanner which folds them into the adult stack. Graduation is
O(runs), not O(count), and reuses existing merge machinery rather than special-casing.

### 7.5 Production

- **Chicken eggs.** A vanilla chicken lays every 6000–12000 ticks. The stack draws the number
  of eggs laid per interval from the equivalent distribution for N chickens and drops them as
  merged item stacks. Statistically identical to N real chickens, without N timers.
- **Milking.** Vanilla milking has no cost and no cooldown — one cow can be milked forever —
  so a stack needs no special handling at all. The only addition is shift-click to fill every
  empty bucket in the player's inventory, which is pure convenience and not a buff.
- **Mooshroom stew.** Same as milking: free and repeatable, with a bulk variant.
- **Shearing sheep.** Destructive, so it consumes one member: drops 1–3 wool, marks that
  member sheared, and adds it to the wool-regrow runs.
- **Shearing mooshrooms.** Destructive and transformative: splits one member out of the
  mooshroom stack, drops 5 mushrooms, and the resulting cow merges into a cow stack.

Wool regrow is an approximation. Real sheep regrow wool by eating grass, which dormant members
cannot do. Each sheared member instead regrows on an independent randomized timer averaging
`production.woolRegrowTicks` (default 4000), gated on the host standing on grass. Flagged in
§13.

## 8. Display

The count is shown using the host's vanilla custom name, formatted per
`display.format` (default `%s ×%d`, producing `Cow ×100`).

`CustomNameVisible` is left **false**, so the name renders only when the player's crosshair is
on the mob. This keeps a large farm from becoming a wall of floating text while still letting
you check any stack by looking at it. `display.alwaysVisible` flips this for players who want
at-a-glance counts.

### 8.1 The nameplate trap

Eligibility (§5.1) excludes custom-named mobs, and the count nameplate *is* a custom name.
Left unhandled, the mod would set a nameplate on a host and thereby make that host permanently
ineligible for further merging — the stack would silently stop growing.

The attachment therefore carries a `nameplateOwned` flag, and `StackEligibility` ignores names
it owns. This is called out explicitly because it is a self-inflicted bug that would be easy
to introduce and hard to diagnose. It has a dedicated gametest.

Note that `setCustomName` in code does not set `PersistenceRequired`; only the name tag *item*
does. So the nameplate does not accidentally make mobs persistent.

## 9. Mob cap correctness

**A stack must report its full member count to spawn-cap and density checks.**

If 100 cows merge into 1 entity and the spawn logic sees 1, it will spawn 100 more, which
merge, which frees the cap again. The world fills with mobs and the optimization eats itself.
For hostile grinders the mob cap *is* the mechanism governing farm rates, so under-reporting
silently changes every farm's output.

In **dormant** mode this is free: members are still real entities and vanilla counts them
correctly with no code from us. This is a significant argument for dormant being the default.

In **virtual** mode a Mixin into the spawn-density and mob-cap counting paths adds the stack's
member count. Because this is only needed for virtual mode, it is deferred to the last
implementation phase.

## 10. Graceful degradation

Fabric pins mods to a Minecraft version, so upgrading to 26.3 means the game refuses to launch
until Mobtimizer is removed. A user then loads their world without the mod. This is the normal
upgrade path, not an edge case, so it must be survivable.

**Dormant mode survives it inherently.** Members are real entities in the save file; with the
freeze code no longer running, they simply wake up. No user action, no data loss.

Additional safety nets, active in both modes:

- The mod records the Minecraft version the world last ran under. On load, a mismatch triggers
  an automatic full unstack before anything else touches the world
  (`safety.autoUnstackOnVersionChange`, default true).
- `/mobtimizer unstack all | here [radius] | <entityType>` for manual control.

Virtual mode's exposure is bounded by the spill threshold: only stacks larger than 512 are at
risk, and only if the user removes the mod without running an unstack.

## 11. Configuration

Gson-backed JSON at `config/mobtimizer.json`, hot-reloadable via `/mobtimizer reload`.
Gson ships with Minecraft, so this adds no dependency.

```json
{
  "merge":      { "enabled": true, "radius": 8.0, "crowdThreshold": 4,
                  "scanIntervalTicks": 20, "maxMergesPerScan": 64 },
  "storage":    { "virtualSpillThreshold": 512 },
  "freeze":     { "mode": "AGGRESSIVE" },
  "damage":     { "unknownSourceKillsWholeStack": false,
                  "hostileDamageMaxMultiplier": 3.0, "maxLootRolls": 1000 },
  "breeding":   { "enabled": true, "enforceCooldown": true, "cooldownTicks": 6000,
                  "bulkFeedOnShift": true },
  "production": { "scaleChickenEggs": true, "bulkMilk": true, "woolRegrowTicks": 4000 },
  "display":    { "enabled": true, "format": "%s ×%d", "alwaysVisible": false },
  "entities":   { "mode": "DENYLIST",
                  "denylist": ["minecraft:villager", "minecraft:wandering_trader"],
                  "allowlist": [] },
  "safety":     { "autoUnstackOnVersionChange": true }
}
```

Freeze mode `AGGRESSIVE` skips the entity tick entirely, disables collision and suppresses
client tracking. `CONSERVATIVE` disables AI and goals but lets the base entity tick run, as a
fallback if a mod conflict appears.

## 12. Module map

Each module has one purpose, a clear interface, and no reach into another's internals.

| Module | Responsibility | Depends on |
|---|---|---|
| `identity` | `StackKey.of(Mob)`, `StackEligibility.canStack(Mob)` — pure, no world access | — |
| `stack` | `MobStack`, `MemberStore`, `DormantStore`, `VirtualStore`, RLE runs, spill logic | `identity` |
| `freeze` | Dormancy: freeze/thaw plus all tick, collision and tracking Mixins | `stack` |
| `merge` | Throttled scanner, crowd gating, candidate search | `identity`, `stack` |
| `damage` | `DamageClassifier`, kill-one vs kill-all routing, loot and XP merging | `stack` |
| `lifecycle` | Breeding math, cooldowns, baby graduation, egg rate scaling | `stack` |
| `interaction` | Feed (incl. bulk), milk, shear, split-on-leash/nametag | `stack`, `lifecycle` |
| `display` | Nameplate formatting and the `nameplateOwned` flag | `stack` |
| `config` | Gson config load/reload | — |
| `command` | `/mobtimizer` subcommands | `stack`, `config` |
| `safety` | Version-change detection and auto-unstack | `stack` |

All Mixins live in `freeze` and `damage`. Quarantining the risky, version-fragile code in two
packages keeps the rest of the mod ordinary Java that can be tested without a game running.

## 13. Accepted inaccuracies

Stated explicitly so they are deliberate choices rather than latent bugs:

1. **Damaged mobs heal on merge.** `Health` is on the ignore list. Low impact for farm animals;
   a small exploit surface for hostiles, mitigated by grinder mobs dying to environmental
   damage anyway.
2. **Wool regrow is timer-based**, not driven by grass-eating (§7.5).
3. **Hostile stack damage is capped** rather than faithfully N× (§7.2).
4. **Members share the host's position.** Dormant mobs do not wander individually.
5. **Aggressive freezing may confuse mods that iterate entities** and find mobs that exist but
   never move. `CONSERVATIVE` mode exists as an escape hatch.
6. **Virtual mode past the spill threshold does not survive mod removal** (§10).

## 14. Testing

**JUnit** via `fabric-loader-junit`, no game required. These cover the parts most likely to
harbour bugs, and all of them are pure functions:

- `StackKeyTest` — identical mobs share a key; differing variant, equipment or effects do not;
  every ignore-list field provably does not affect the key.
- `BreedingMathTest` — 100 feeds → 50 babies; 99 → 49 + 1 love; a stack of 1 → 0 babies;
  cooldown gating caps `loveCount` correctly.
- `RunLengthTest` — compact/expand round-trips; lockstep advancement keeps runs merged;
  feeding one baby splits exactly one member out.
- `DamageClassifierTest` — every vanilla damage type routes to the correct branch; unknown
  sources default to single-target.

**Gametests** via `fabric-gametest-api-v1`:

- merging occurs at the crowd threshold and not below it
- stacks survive save/load and chunk unload/reload
- sword kills 1 member; lava kills the whole stack with merged loot
- `/mobtimizer unstack` restores exactly N real entities
- the mod-owned nameplate does not block further merging (§8.1)
- a graduated baby lands in the adult stack

Development follows TDD: the pure modules are written test-first, since the breeding math and
RLE round-trip are exactly where correctness matters and where the existing mods went wrong.

## 15. Implementation phases

Each phase is independently shippable and leaves the mod in a working state.

1. **Core stacking** — `identity`, `stack` (dormant only), `freeze`, `merge`, `display`,
   `command` unstack, `config`, `safety`. Delivers the performance win and is useful alone.
2. **Damage** — source classification, kill-one vs kill-all, host promotion, merged loot and XP.
3. **Breeding** — love counting, per-member cooldowns, baby stacks, graduation.
4. **Production** — chicken eggs, bulk milk/stew, shearing, wool regrow.
5. **Virtual storage** — the `VirtualStore` backend and spill threshold.
6. **Virtual mob cap** — the spawn-density Mixin, needed only once virtual exists.

Virtual storage is deliberately last. Dormant mode gets mob cap correctness for free, so
phases 1–4 never need the Mixin, and the risky compression layer lands on top of mechanics
that are already tested and known-good.

## 16. Repository

Git, pushed to GitHub during development. Branch per phase, `main` kept working.
