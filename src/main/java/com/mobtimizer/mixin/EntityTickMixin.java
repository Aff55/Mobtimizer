package com.mobtimizer.mixin;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@code ServerLevel.tickNonPassenger(Entity)} at its head for frozen
 * members, in AGGRESSIVE mode only.
 *
 * <p>Verified by disassembly of the 26.2 jar that this single method is
 * everything a per-entity tick dispatch does: {@code setOldPosAndRot()},
 * {@code tickCount++}, profiler bookkeeping, {@code Entity.tick()} itself, and
 * riding-passenger ticking. Cancelling at {@code HEAD} skips all of it in one
 * stable place, rather than needing to override {@code Entity.tick()} (which
 * every entity subclass overrides differently) or hook the caller.
 *
 * <p>This does not, on its own, stop a frozen member from despawning:
 * {@code ServerLevel}'s tick dispatcher calls {@code Entity.checkDespawn()}
 * (overridden by {@code Mob}) directly, before and independently of
 * {@code tickNonPassenger}. See {@link FrozenDespawnMixin}.
 */
@Mixin(ServerLevel.class)
public abstract class EntityTickMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$skipFrozenMembers(Entity entity, CallbackInfo ci) {
        if (!ConfigManager.get().freeze.isAggressive()) return;

        if (entity instanceof Mob mob && Dormancy.isFrozen(mob)) {
            ci.cancel();
        }
    }
}
