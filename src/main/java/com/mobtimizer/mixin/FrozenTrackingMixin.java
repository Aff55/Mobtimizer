package com.mobtimizer.mixin;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops frozen members from being tracked (synced) to any client at all, in
 * AGGRESSIVE mode. Not requested by the original brief - the brief only specified
 * tick and collision suppression; the coordinator's review identified that AGGRESSIVE
 * mode's design also requires this third piece, since network sync of every frozen
 * member is a real slice of the per-tick cost this mod exists to remove, and
 * {@code Invisible} alone does not prevent it - an invisible entity still has its
 * position, rotation and entity-data broadcast to every nearby player every tick.
 *
 * <p>Verified by disassembly with the same rigour as {@code isPushable}, tracing the
 * real call chain rather than guessing at {@code ChunkMap}/{@code TrackedEntity}:
 * <ul>
 *   <li>{@code ChunkMap$TrackedEntity.updatePlayer(ServerPlayer)} - called from
 *       {@code ChunkMap.tick()} every tick for every currently-tracked-or-trackable
 *       entity, and once synchronously from {@code ChunkMap.addEntity(Entity)} - is
 *       the method that actually decides, per player, whether to keep or drop
 *       tracking. After its own distance check passes, it additionally calls
 *       {@code entity.broadcastToPlayer(player)}; only if <em>that</em> also returns
 *       {@code true} does the player get added to the entity's {@code seenBy} set
 *       and paired for updates. If it returns {@code false} for a player already in
 *       {@code seenBy}, {@code updatePlayer} calls {@code removePlayer(player)} on
 *       vanilla's own code path - a normal, clean "stop tracking" (the player gets a
 *       proper remove-entity packet; nothing is left dangling client-side).
 *   <li>{@code Entity.broadcastToPlayer(ServerPlayer)} is declared only on
 *       {@code Entity}, default body is an unconditional {@code return true}, and -
 *       checked the same way {@code isPushable} was, including {@code LivingEntity},
 *       {@code Mob}, and the same leaf-class/{@code Player} sample - is not
 *       overridden anywhere relevant. It is the single, purpose-built,
 *       per-entity-customisable seam for exactly this "should this player be told
 *       about me" decision, used from exactly one call site jar-wide.
 * </ul>
 * Returning {@code false} here therefore removes a frozen member from every
 * player's tracked set the next time {@code ChunkMap.tick()} runs (a player already
 * tracking it when it freezes sees it properly despawn within a tick or two, same as
 * {@code setInvisible(true)} already makes happen visually), and keeps it out of
 * every subsequent player's tracked set for as long as it stays frozen. Thawing
 * reverts to vanilla's default {@code true} automatically, with no extra bookkeeping,
 * since nothing here is persisted - the next {@code ChunkMap.tick()} re-adds it
 * normally.
 *
 * <p>Gated on {@code freeze.isAggressive()}, per the coordinator's instruction:
 * CONSERVATIVE mode keeps vanilla tracking, since it is meant as the compatibility
 * escape hatch and {@code setInvisible} is kept as its correct, working fallback
 * there regardless.
 */
@Mixin(Entity.class)
public abstract class FrozenTrackingMixin {
    @Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$frozenMembersAreNotTracked(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (!ConfigManager.get().freeze.isAggressive()) return;

        if ((Object) this instanceof Mob mob && Dormancy.isFrozen(mob)) {
            cir.setReturnValue(false);
        }
    }
}
