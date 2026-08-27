package com.mobtimizer.mixin;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@code Mob.serverAiStep()} at its head for frozen members, in
 * CONSERVATIVE mode only. In AGGRESSIVE mode the whole tick is already cancelled
 * upstream by {@link EntityTickMixin}, so {@code serverAiStep()} never runs for a
 * frozen member anyway; this Mixin is CONSERVATIVE mode's entire reason to exist -
 * without it, {@code freeze.mode: CONSERVATIVE} would be a config knob that does
 * nothing observable.
 *
 * <p>Verified by disassembly: {@code Mob.serverAiStep()} is
 * {@code protected final void serverAiStep()} - sensing, target/goal selector
 * ticking, navigation, {@code customServerAiStep}, and move/look/jump control
 * ticking, i.e. exactly "goal selection and AI stepping". {@code final} does not
 * block a Mixin injection (Mixin rewrites the method's bytecode directly rather
 * than subclassing it), so the qualifier is not an obstacle here.
 *
 * <p>Left running in CONSERVATIVE mode: the base {@code Entity.tick()} (physics,
 * fire, potion effects, etc.) and {@code LivingEntity.pushEntities()} - the latter
 * is still made a no-op for a frozen member by {@link EntityCollisionMixin}, since
 * that Mixin is unconditional.
 */
@Mixin(Mob.class)
public abstract class FrozenAiMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$suppressFrozenAi(CallbackInfo ci) {
        // In aggressive mode the whole tick is already cancelled upstream.
        if (ConfigManager.get().freeze.isAggressive()) return;

        if (Dormancy.isFrozen((Mob) (Object) this)) {
            ci.cancel();
        }
    }
}
