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

In design. See [the design spec](docs/superpowers/specs/2026-08-25-mobtimizer-design.md).

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |

Server-side only. Vanilla clients can connect to a server running it.
