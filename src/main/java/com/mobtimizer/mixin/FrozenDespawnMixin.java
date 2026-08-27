package com.mobtimizer.mixin;

import com.mobtimizer.freeze.Dormancy;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@code Mob.checkDespawn()} at its head for frozen members, regardless of
 * {@code freeze.mode} - a frozen member must never despawn, in either mode.
 *
 * <p>Not brief-listed as a file to create, but required by the brief's own
 * "Required: keep frozen members loaded and alive" section: a member popped from
 * {@code DormantStore} that has actually despawned is indistinguishable from one
 * whose chunk is merely unloaded, and is silently lost either way.
 *
 * <p>Verified by disassembly to be necessary in addition to, not covered by,
 * {@link EntityTickMixin}: {@code ServerLevel}'s per-entity tick dispatcher (the
 * lambda inside {@code ServerLevel.tick(BooleanSupplier)}) calls
 * {@code entity.checkDespawn()} directly and unconditionally, strictly before and
 * independently of the {@code tickNonPassenger} call that {@code EntityTickMixin}
 * cancels. Cancelling the tick alone does not stop this call.
 *
 * <p>Also verified by disassembly that {@code Mob.checkDespawn()} - not
 * {@code Mob.removeWhenFarAway(double)} - is the correct single seam:
 * {@code removeWhenFarAway} is only one internal helper {@code checkDespawn} calls
 * on two of its several exit paths (the peaceful-difficulty instant removal at the
 * very top of the method does not go through it at all), so cancelling
 * {@code checkDespawn} itself is what actually covers every path, including that
 * one.
 *
 * <p>Deliberately not {@code Mob.setPersistenceRequired()}: that is a one-way
 * persisted vanilla flag with no public unsetter. Using it would mean thawing could
 * never restore a member's original persistence, and removing the mod would leave
 * every former member permanently non-despawning - the same failure
 * {@code setNoAi} is rejected for. A Mixin disappears cleanly when the mod does; a
 * persisted NBT flag does not.
 */
@Mixin(Mob.class)
public abstract class FrozenDespawnMixin {
    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$frozenMembersDoNotDespawn(CallbackInfo ci) {
        if (Dormancy.isFrozen((Mob) (Object) this)) {
            ci.cancel();
        }
    }
}
