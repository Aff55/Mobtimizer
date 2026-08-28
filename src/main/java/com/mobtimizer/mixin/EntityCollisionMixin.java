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
 * without ever calling {@code super}. An {@code Entity}-level injection (as an
 * earlier draft of this Mixin, and this task's brief, had it) would resolve and
 * load without error, but would silently never fire for any mob - exactly the
 * "looks right but is wrong" failure this task exists to catch.
 *
 * <p>Also verified by disassembly why this is the correct seam at all:
 * {@code LivingEntity.pushEntities()} calls {@code Level.getPushableEntities(this,
 * bb)}, which builds its candidate list with {@code EntitySelector.pushableBy(this)}
 * - whose lambda checks {@code candidate.isPushable()} first, before any team/rule
 * logic. Returning {@code false} here removes a frozen member from that candidate
 * list, which is what stops anything else from pushing it; it does not depend on
 * the frozen member's own tick running.
 *
 * <p><b>Correction (Task 7 review, second round): "and {@code Mob} does not override
 * it again - so every {@code Mob} instance runs {@code LivingEntity}'s copy" - the
 * previous version of this Javadoc - is false.</b> A reflection scan over every
 * class in {@code net.minecraft.world.entity.**}, disassembling each
 * {@code isPushable()} override, found two real bypasses beneath {@code LivingEntity}:
 * {@code AbstractHorse.isPushable()} (all horses, donkeys, mules, skeleton and
 * zombie horses - {@code return !isVehicle();}) and {@code Parrot.isPushable()}
 * ({@code return true;} unconditionally) are both complete, independent overrides
 * with no {@code super} call, confirmed by disassembly the same way {@code
 * LivingEntity}'s own bypass of {@code Entity} was confirmed. A frozen horse or
 * parrot would stay fully pushable; this Mixin's own bytecode never runs for
 * either. ({@code Creaking} and {@code Warden} also override {@code isPushable()},
 * but both call {@code super} via {@code invokespecial}, which resolves to this
 * Mixin's copy - those two are safe.)
 *
 * <p>This is currently unreachable in practice only because
 * {@link com.mobtimizer.identity.StackEligibility#canStack} excludes every
 * {@code OwnableEntity} - which covers {@code AbstractHorse} and {@code Parrot}
 * completely - for an unrelated reason (a tameable mob is a deliberate player
 * setup). <b>This Mixin does not know that and must not be trusted alone</b>: it
 * covers every {@code Mob} that does not itself override {@code isPushable()}
 * beneath {@code LivingEntity}, nothing more. Coverage of horses and parrots is
 * {@code StackEligibility}'s responsibility, not this class's, and per-class Mixins
 * for the current bypass list were deliberately not added here - that would chase a
 * hierarchy that could grow without warning; excluding the whole {@code
 * OwnableEntity} interface at the eligibility layer is the boundary that actually
 * holds. See {@code DormancyGameTest#horsesBypassCollisionSuppressionSoEligibilityMustKeepExcludingThem}
 * for the test that turns this from a comment into something that fails loudly if
 * that exclusion is ever relaxed.
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
