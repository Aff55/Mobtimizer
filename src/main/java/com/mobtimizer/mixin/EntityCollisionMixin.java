package com.mobtimizer.mixin;

import com.mobtimizer.freeze.Dormancy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels {@code isPushable()} to {@code false} for frozen members, regardless of
 * {@code freeze.mode} - a frozen member must never be shoved out of position by
 * whatever is still ticking around it.
 *
 * <p>Targets {@link LivingEntity}, <b>not</b> {@code Entity}: disassembly shows
 * {@code LivingEntity.isPushable()} completely overrides {@code Entity.isPushable()}
 * without ever calling {@code super}, and {@code Mob} does not override it again -
 * so every {@code Mob} instance runs {@code LivingEntity}'s copy. An {@code Entity}
 * -level injection (as an earlier draft of this Mixin, and this task's brief, had
 * it) would resolve and load without error, but would silently never fire for any
 * mob - exactly the "looks right but is wrong" failure this task exists to catch.
 *
 * <p>Also verified by disassembly why this is the correct seam at all:
 * {@code LivingEntity.pushEntities()} calls {@code Level.getPushableEntities(this,
 * bb)}, which builds its candidate list with {@code EntitySelector.pushableBy(this)}
 * - whose lambda checks {@code candidate.isPushable()} first, before any team/rule
 * logic. Returning {@code false} here removes a frozen member from that candidate
 * list, which is what stops anything else from pushing it; it does not depend on
 * the frozen member's own tick running.
 */
@Mixin(LivingEntity.class)
public abstract class EntityCollisionMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$frozenMembersDoNotPush(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Mob mob && Dormancy.isFrozen(mob)) {
            cir.setReturnValue(false);
        }
    }
}
