# Mobtimizer

A Fabric mod for Minecraft 26.2 that merges same-kind mobs into a single ticking entity
carrying a member count, cutting the performance cost of large farms without changing how
those farms behave.

The distinguishing goal is correctness. A 100-cow stack breeds, dies and produces like 100
real cows: feeding it 100 wheat yields exactly 50 babies, per-member breeding cooldowns are
enforced, environmental damage kills the whole stack while a sword kills one member, and
stacks report their full count to the mob cap so farm rates stay honest.

By default merged members remain in the world as frozen entities, so removing the mod
restores every mob rather than leaving you with one.

## Status

Phase 1 complete: mobs merge, members are kept as frozen entities, counts show on hover,
and `/mobtimizer unstack` restores everything. Damage routing, breeding and production
scaling are phases 2–4 — see [the design spec](docs/superpowers/specs/2026-08-25-mobtimizer-design.md).

Breeding works the vanilla way rather than the scaled way: feeding a stacked animal
releases one member as a partner, so an ordinary two-animal breed still happens. Feeding
a 100-stack does not yet yield 50 babies — that is phase 3.

## Commands

| | |
|---|---|
| `/mobtimizer unstack all` | Release every stacked mob in the current level |
| `/mobtimizer unstack here [radius]` | Release stacks within `radius` blocks (default 16) |
| `/mobtimizer unstack type <id>` | Release stacks of one entity type, e.g. `minecraft:cow` |
| `/mobtimizer stats` | Report how many mobs are held in how many stacks |
| `/mobtimizer reload` | Re-read the config file |

All require permission level 2 (gamemasters). Uninstalling the mod is also safe on its
own: members are real entities the whole time, so they simply wake up.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |

Server-side only. Vanilla clients can connect to a server running it.
