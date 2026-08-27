package com.mobtimizer.freeze;

import com.mobtimizer.MobtimizerAttachments;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
 * <p><b>Correction to an earlier version of this Javadoc:</b> {@code Invisible} does
 * <em>not</em> outlive the mod - it has no NBT backing at all (confirmed by disassembly
 * of {@code Entity}'s full save/load pipeline: no string constant for it exists
 * anywhere in that code, unlike {@code Silent}/{@code NoGravity}/{@code Glowing}/
 * {@code Invulnerable}, which do), so nothing about it can be left behind after
 * removal - there is simply nothing saved to leave behind. Combined with the
 * Silent/NoGravity fix above, <b>nothing this mod sets survives its own removal.</b>
 *
 * <p>What {@code Invisible} having no NBT backing <em>does</em> cause is a different
 * bug, during normal operation with the mod still installed: a frozen member comes
 * back visible after an ordinary world reload, and - since a frozen member never
 * ticks - nothing would otherwise notice and re-hide it. {@link #register} closes
 * this by listening for {@link ServerEntityEvents#ENTITY_LOAD} and re-applying
 * {@code setInvisible(true)} to any entity that loads back in already frozen.
 */
public final class Dormancy {
    private Dormancy() {}

    /**
     * Wires up the load-time fix-up that keeps a frozen member hidden across a
     * reload. Called once from {@code Mobtimizer.onInitialize()}.
     *
     * <p>{@code ServerEntityEvents.ENTITY_LOAD} lives in
     * {@code net.fabricmc.fabric.api.event.lifecycle.v1} - not
     * {@code net.fabricmc.fabric.api.entity.event.v1}, which has no such class - and
     * is already on the classpath via this project's existing {@code fabric-api}
     * dependency (fabric-lifecycle-events-v1), so this adds no new runtime
     * dependency. Verified by disassembly, not assumed: its underlying Mixin injects
     * at the {@code TAIL} of {@code ServerLevel$EntityCallbacks.onTrackingStart(Entity)}
     * - the vanilla {@code LevelCallback} hook that fires whenever an entity (freshly
     * spawned <em>or</em> reconstructed from a chunk's saved NBT - both go through the
     * same {@code addFreshEntity}-style registration path) becomes eligible to be sent
     * to nearby players. Also confirmed {@code onTrackingStart} does not itself
     * synchronously send any per-player packets (it only registers the entity with
     * {@code ServerChunkCache}/{@code ChunkMap}; the actual spawn/sync packets are
     * built later, in {@code ChunkMap}'s own per-tick tracking pass), so this
     * listener's {@code setInvisible(true)} call lands before any client could ever
     * be told the entity is visible - not just soon after.
     */
    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob && isFrozen(mob)) {
                mob.setInvisible(true);
            }
        });
    }

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
