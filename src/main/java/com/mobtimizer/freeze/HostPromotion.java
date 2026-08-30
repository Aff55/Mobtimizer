package com.mobtimizer.freeze;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.display.StackNameplate;
import com.mobtimizer.stack.DormantStore;
import com.mobtimizer.stack.MobStack;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Fills the phase gap a real play-test found: Phase 1 shipped freeze/thaw and merge
 * without any death handling, because death was scheduled for phase 2. That gap meant
 * a stack's frozen members had no way to ever be released if the visible host died -
 * lava, fall damage, an arrow, or a player killing it left every member exactly as it
 * was: invisible, inert, non-ticking, untracked, with no host and no path back. A
 * permanent silent leak, reported by a user as "killing one baby killed all the
 * babies in the stack" - they were not dead, they were unreachable forever.
 *
 * <p>This brings forward phase 2's specified fix, not a stopgap: when a host dies, it
 * dies normally (its own loot and XP, untouched), and one frozen member wakes to take
 * its place as the new host, inheriting whatever remained of the stack, at the exact
 * position the old host died. Killing one cow kills exactly one cow.
 *
 * <p><b>Interception mechanism: {@link ServerLivingEntityEvents#AFTER_DEATH}, not a
 * Mixin.</b> Verified rather than assumed, the same way every injection point in this
 * package has been:
 * <ul>
 *   <li>It exists in the bundled Fabric API version - {@code
 *       net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH},
 *       part of {@code fabric-entity-events-v1} (a submodule of the {@code fabric-api}
 *       umbrella dependency this project already has - no new dependency).
 *   <li>It fires server-side, for mobs. Disassembly of {@code LivingEntity.die
 *       (DamageSource)} shows the entire loot/game-event/{@code broadcastEntityEvent}
 *       block - the exact call Fabric's Mixin injects before, to fire {@code
 *       AFTER_DEATH} - sits inside an {@code if (level instanceof ServerLevel)}
 *       branch; the client-side path returns before ever reaching it. {@code Mob}
 *       does not override {@code die()} anywhere in the checked hierarchy (confirmed
 *       for {@code Mob}, {@code Animal}, {@code PathfinderMob}, and {@code Cow}
 *       directly), so every mob's death runs this exact code, not some override that
 *       bypasses it.
 *   <li>It fires late enough. {@code die()}'s bytecode calls {@code
 *       dropAllDeathLoot(ServerLevel, DamageSource)} (which itself calls {@code
 *       dropExperience} - loot and XP both) at one offset, then reaches the {@code
 *       broadcastEntityEvent} call - {@code AFTER_DEATH}'s injection point - a few
 *       instructions later, strictly after. Both the "killer non-null" and "killer
 *       null" branches of {@code die()} converge on the same {@code
 *       broadcastEntityEvent} call before returning, so this fires exactly once per
 *       real death regardless of how it was caused.
 * </ul>
 * A Mixin was considered and rejected, per instruction: the target server has
 * Lithium installed, which is known to rewrite entity death/tick paths for
 * performance. A fresh Mixin into {@code die()} or an adjacent method would be
 * competing with Lithium's own rewrites with no track record; Fabric API's own Mixin
 * (which is what actually fires {@code AFTER_DEATH}) is exactly the kind of
 * widely-used, priority-conscious code Lithium is routinely run alongside without
 * conflict. Since the event demonstrably fires at the right time for the right
 * entities, there was no reason to take on that risk.
 *
 * <p><b>How promotion transfers state.</b> The dying host's position and rotation are
 * captured first, before anything else runs - {@code die()} itself never moves the
 * entity, so this is exactly where it died. Members are then tried one at a time,
 * LIFO (via {@link DormantStore#takeOne}, which already thaws whatever it returns -
 * visible, ticking-eligible, tracked-eligible, collidable again, for free, since
 * every freeze-suppressing Mixin in this package gates on the same {@link
 * Dormancy#isFrozen} check that {@code thaw} clears) until one comes back both
 * resolvable <em>and</em> genuinely alive, or the stack is exhausted. The chosen
 * member is snapped to the captured position (frozen members are already kept
 * co-located with their host by {@link Dormancy#followHost}, but only as of the last
 * scan - re-snapping here guarantees exactness regardless of any drift since then,
 * so the stack does not visibly teleport). Every remaining member is then moved onto
 * it via {@link DormantStore#transferMembers}, and the dead host's own {@code STACK}
 * attachment is cleared unconditionally - not left to whatever {@code takeOne}/{@code
 * transferMembers} happen to leave behind - so nothing is left pointing at a corpse.
 *
 * <p><b>Edge cases handled, and why:</b>
 * <ul>
 *   <li><b>The host has no members.</b> An ordinary, unstacked mob dying. The very
 *       first check - {@code stack.members().isEmpty()} - returns immediately,
 *       touching nothing. Vanilla death proceeds exactly as it would with this mod
 *       absent.
 *   <li><b>The chosen member cannot be resolved</b> (chunk trimmed, {@code /kill},
 *       another mod). {@code takeOne} already drops the stale id and returns
 *       {@code null} for it - the promotion loop simply continues to the next member
 *       rather than giving up, so one stale id never costs the rest of the stack.
 *   <li><b>Every member is unresolvable.</b> The loop terminates (it runs on {@code
 *       DormantStore.size(host)}, which strictly decreases every iteration, the same
 *       termination argument {@code takeAll} already relies on) with nothing
 *       promoted. The stack is genuinely gone; the host's attachment is cleared and
 *       the method returns cleanly - no loop, no exception, no resurrection attempt.
 *   <li><b>The whole stack dies at once</b> (an explosion, {@code /kill @e}). A
 *       frozen member's tick is suppressed, but external damage - explosions and
 *       commands both apply damage directly, not through the victim's own tick - is
 *       not, so a member can die in the same event batch as its host. {@code
 *       takeOne}'s resolve step only asks whether the entity is still findable, not
 *       whether it is still alive: a member whose own {@code die()} already ran (
 *       {@code dead} set, health {@literal <=} 0) but has not yet been removed
 *       (removal is deferred ~20 ticks by {@code tickDeath()}) would otherwise
 *       resolve successfully and get promoted - a corpse "promoted" to host, about to
 *       vanish itself. The promotion loop additionally requires {@code isAlive()}
 *       before accepting a candidate, which rejects exactly this case and moves on.
 *       <b>Correction found during testing:</b> an earlier version of this check was
 *       {@code isAlive() && !isDeadOrDying()}, written on the wrong assumption that
 *       {@code isAlive()} meant only "not removed" (true for the base {@code Entity}
 *       implementation, verified elsewhere in this package for an unrelated Mixin).
 *       {@code LivingEntity} overrides it to {@code !isRemoved() && getHealth() > 0}
 *       - confirmed by disassembly - which is already the exact negation of {@code
 *       isDeadOrDying()} ({@code getHealth() <= 0 || dead}) for any {@code Mob}. The
 *       {@code !isDeadOrDying()} half was therefore always redundant, not merely
 *       harmless: keeping it invited a reader to believe {@code isAlive()} alone was
 *       insufficient here, which is false. Simplified to {@code isAlive()} alone. If
 *       that member is later found to be the host of its own remaining stack (it
 *       never was one here, since it was never promoted), nothing about this
 *       mechanism prevents this same handler from running again for a genuine second
 *       host death in the same batch.
 * </ul>
 */
public final class HostPromotion {
    private HostPromotion() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register(HostPromotion::promoteOnHostDeath);
    }

    private static void promoteOnHostDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof Mob host)) return;

        MobStack stack = host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);
        if (stack.members().isEmpty()) return;

        double x = host.getX();
        double y = host.getY();
        double z = host.getZ();
        float yRot = host.getYRot();
        float xRot = host.getXRot();

        Mob promoted = null;
        while (promoted == null && DormantStore.INSTANCE.size(host) > 0) {
            Mob candidate = DormantStore.INSTANCE.takeOne(host);
            // isAlive() on a LivingEntity is !isRemoved() && getHealth() > 0 - this
            // single check already excludes a member that is itself dead/dying in
            // the same event batch as the host (see the class Javadoc's "whole
            // stack dies at once" case), with nothing more needed.
            if (candidate != null && candidate.isAlive()) {
                promoted = candidate;
            }
            // else: unresolvable, or itself dead/dying - cannot serve as the new
            // host either way. takeOne has already dropped its id from the
            // attachment, so the loop simply moves on to the next member.
        }

        if (promoted != null) {
            promoted.snapTo(x, y, z, yRot, xRot);
            DormantStore.INSTANCE.transferMembers(host, promoted);
            StackNameplate.refresh(promoted);
        }

        // Unconditional, not relied upon as a side effect of the calls above: makes
        // "nothing is left pointing at a corpse" true by construction rather than by
        // tracing through takeOne/transferMembers' internal draining behaviour.
        host.setAttached(MobtimizerAttachments.STACK, MobStack.EMPTY);
    }
}
