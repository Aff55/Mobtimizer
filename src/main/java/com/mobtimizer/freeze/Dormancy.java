package com.mobtimizer.freeze;

import com.mobtimizer.MobtimizerAttachments;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Freezing and thawing individual stack members.
 *
 * <p>A frozen member stays a real entity in the world - that is what makes removing
 * the mod survivable - but is made inert: no tick, no collision, no despawn, no
 * sound, no rendering, and parked on the host so it travels with the stack. Ticking,
 * collision and despawn are suppressed by the Mixins in {@code com.mobtimizer.mixin},
 * all of which gate on {@link #isFrozen}; this class only owns the mod-side
 * attachment and the handful of vanilla flags that are cheap to toggle directly.
 *
 * <p>{@code setNoAi()} is deliberately not used, and neither is
 * {@code setPersistenceRequired()}: both are one-way persisted vanilla flags with no
 * public unsetter, so removing the mod would leave every former member permanently
 * AI-less and non-despawning. Freezing instead lives entirely in mod-owned state
 * (the {@link MobtimizerAttachments#FROZEN} attachment) plus Mixins that disappear
 * cleanly when the mod does.
 *
 * <p><b>Known residue:</b> {@code Silent} and {@code NoGravity} are, unlike {@code
 * setNoAi}, ordinary synced entity flags with public setters in both directions -
 * but disassembly of {@code Entity.saveWithoutId}/{@code load} shows both are
 * nonetheless conditionally written to and read from NBT (when {@code true}), so a
 * member frozen at the moment the world was last saved keeps {@code Silent: true}
 * and {@code NoGravity: true} in its NBT even after this mod is removed - there is
 * no more code left to ever call {@link #thaw}. This is real, verified residue, but
 * materially narrower than {@code setNoAi}/{@code setPersistenceRequired}: it is
 * only ever visible (silent, floating) rather than a silent behavioural trap, and
 * only manifests for a member that happens to be frozen, not thawed, at the last
 * save before uninstalling. {@code Invisible} has no NBT backing at all - it never
 * round-trips even through an ordinary save/load while the mod stays installed,
 * meaning a reloaded frozen member is briefly visible again until something
 * re-applies {@code setInvisible(true)}. Both are documented here rather than
 * silently accepted; see the Task 7 report for the full analysis.
 */
public final class Dormancy {
    private Dormancy() {}

    public static boolean isFrozen(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.FROZEN, Boolean.FALSE);
    }

    public static void freeze(Mob member, Mob host) {
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.TRUE);

        member.setInvisible(true);
        member.setSilent(true);
        member.setNoGravity(true);
        member.setDeltaMovement(Vec3.ZERO);
        member.snapTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
    }

    public static void thaw(Mob member) {
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.FALSE);

        member.setInvisible(false);
        member.setSilent(false);
        member.setNoGravity(false);
    }

    /** Keeps frozen members travelling with their host. Called from the merge scanner each scan. */
    public static void followHost(Mob member, Mob host) {
        if (member.distanceToSqr(host) > 0.001) {
            member.snapTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
        }
    }
}
