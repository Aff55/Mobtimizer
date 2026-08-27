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
 * all of which gate on {@link #isFrozen}; {@code FrozenFlagsMixin} does the same for
 * {@code isSilent()}/{@code isNoGravity()} (see below). This class owns the mod-side
 * attachment and the one vanilla flag ({@code Invisible}) that cannot be handled the
 * same way.
 *
 * <p>{@code setNoAi()} is deliberately not used, and neither is
 * {@code setPersistenceRequired()}: both are one-way persisted vanilla flags with no
 * public unsetter, so removing the mod would leave every former member permanently
 * AI-less and non-despawning.
 *
 * <p><b>{@code Silent} and {@code NoGravity} are mod-owned, not vanilla-owned.</b>
 * {@code freeze}/{@code thaw} deliberately do <em>not</em> call
 * {@code setSilent}/{@code setNoGravity} any more. Disassembly of
 * {@code Entity.saveWithoutId}/{@code load} showed both flags round-trip through NBT
 * whenever {@code true}, which meant a member frozen (not thawed) at the moment the
 * world was last saved kept both flags set in its NBT forever after this mod was
 * removed - a real "outlives the mod" violation, the same failure class
 * {@code setNoAi} is rejected for. {@code FrozenFlagsMixin} now overrides
 * {@code isSilent()}/{@code isNoGravity()} to report {@code true} whenever
 * {@link #isFrozen} is true, without this class ever writing to either flag - and
 * additionally strips both keys back out of a frozen member's saved NBT, since
 * {@code saveWithoutId} decides what to write by calling those same overridden
 * getters (see {@code FrozenFlagsMixin}'s Javadoc for why the getter override alone
 * was not sufficient, and the narrow trade-off the NBT-stripping half accepts: a mob
 * that was already legitimately silent/gravity-less for an unrelated reason before
 * being frozen loses that original value if the world is saved and reloaded while it
 * is still frozen, coming back as the vanilla default once thawed). Freeze-then-thaw
 * with no save/load in between never touches the real underlying value at all, so it
 * is preserved exactly.
 *
 * <p><b>{@code Invisible} cannot be handled the same way, and still uses the vanilla
 * setter.</b> This is not an oversight: {@code Entity.setInvisible} writes to
 * {@code SynchedEntityData} (the {@code DATA_SHARED_FLAGS_ID} byte), which is the
 * mechanism that gets broadcast to tracking clients; {@code isInvisible()} only
 * reads that same table back. Client-side rendering decides whether to draw an
 * entity from its <em>own</em> synced copy of that flag - a Mixin on the getter runs
 * only on the server and is never consulted when the server decides what to put in a
 * sync packet ({@code SynchedEntityData.packDirty()}/{@code getNonDefaultValues()}
 * read the table's stored values directly, never the {@code isInvisible()} wrapper
 * method). Overriding only the getter would make the server's own logic believe a
 * frozen member is invisible while every client keeps rendering it normally - the
 * mod's core "the other members are hidden" behaviour would break outright, for
 * every frozen member, all the time, which is strictly worse than the narrow
 * residue being traded away. {@code Silent} and {@code NoGravity} do not have this
 * problem: {@code isNoGravity()} gates a server-authoritative position computation
 * ({@code Entity.getGravity()}) whose *result* - not the flag - reaches clients via
 * ordinary position sync, since mobs are always server-positioned; {@code isSilent()}
 * gates whether the server ever sends a sound packet at all
 * ({@code Entity.playSound}), so suppressing it server-side is sufficient with
 * nothing left for a client to be told. {@code Invisible} has no such indirection:
 * the flag itself is the effect.
 *
 * <p>This leaves {@code Invisible} with the same narrow, already-documented residue
 * as before: a member frozen (not thawed) at the moment of the last save keeps
 * {@code Invisible} unset in a different way - it actually has the opposite problem,
 * since {@code Invisible} has no NBT backing at all and so does not even survive an
 * ordinary reload with the mod installed (a reloaded frozen member is briefly
 * visible again until something re-applies {@code setInvisible(true)}; tracked as a
 * follow-up, not fixed here). See the Task 7 report for the full analysis of both.
 */
public final class Dormancy {
    private Dormancy() {}

    public static boolean isFrozen(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.FROZEN, Boolean.FALSE);
    }

    public static void freeze(Mob member, Mob host) {
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.TRUE);

        // Silent/NoGravity are handled by FrozenFlagsMixin overriding the getters -
        // deliberately not set here. See this class's Javadoc.
        member.setInvisible(true);
        member.setDeltaMovement(Vec3.ZERO);
        member.snapTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
    }

    public static void thaw(Mob member) {
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.FALSE);

        member.setInvisible(false);
    }

    /** Keeps frozen members travelling with their host. Called from the merge scanner each scan. */
    public static void followHost(Mob member, Mob host) {
        if (member.distanceToSqr(host) > 0.001) {
            member.snapTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
        }
    }
}
