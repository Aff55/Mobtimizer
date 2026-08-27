package com.mobtimizer.mixin;

import com.mobtimizer.freeze.Dormancy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports {@code true} from {@code isSilent()}/{@code isNoGravity()} for frozen
 * members, unconditionally (both modes) - without either flag ever surviving in a
 * frozen member's saved NBT. {@code Dormancy.freeze}/{@code thaw} deliberately no
 * longer call {@code setSilent}/{@code setNoGravity}; see that class's Javadoc for
 * why (disassembly showed both flags conditionally round-trip through NBT, so
 * calling the setters left real "outlives the mod" residue - a member frozen, not
 * thawed, at last save kept both flags set forever after the mod was removed).
 *
 * <p>Targets {@link Entity}: verified by disassembly that both getters are declared
 * only there, with no override anywhere in {@code LivingEntity}, {@code Mob}, or the
 * subclasses sampled for the {@code isPushable} check in {@code EntityCollisionMixin}
 * - unlike {@code isPushable}, there is no wrong-target risk here.
 *
 * <p>Verified overriding the getters is sufficient, not just risk-free, by tracing
 * what each one actually gates, since neither result is itself broadcast to clients
 * the way {@code Invisible} is:
 * <ul>
 *   <li>{@code isNoGravity()} is read by {@code Entity.getGravity()}
 *       ({@code isNoGravity() ? 0.0 : getDefaultGravity()}), which drives the
 *       server-authoritative position computation for every non-player entity.
 *       Mobs are always server-positioned - clients only interpolate toward
 *       reported positions - so a motionless server-side entity reaches clients via
 *       ordinary position sync with no need for the raw flag itself to be networked.
 *   <li>{@code isSilent()} is checked by {@code Entity.playSound(SoundEvent,F,F)}
 *       (and the single-arg overload, and other direct call sites such as the
 *       lava-damage hurt sound) <em>before</em> the sound-broadcasting
 *       {@code Level.playSound(...)} call - suppressing it server-side means the
 *       packet is simply never sent, leaving nothing for a client to be told.
 * </ul>
 * {@code isInvisible()} is deliberately not given the same treatment here: its
 * effect <em>is</em> the networked flag (client rendering reads its own synced
 * copy), so a getter-only override cannot make anything actually invisible to a
 * remote player - {@code Dormancy} keeps calling {@code setInvisible} for that one.
 *
 * <p><b>The {@code saveWithoutId} injection below is not optional.</b> An earlier
 * version of this Mixin only overrode the two getters and assumed that, since
 * neither setter is called any more, nothing could reach NBT. That assumption was
 * wrong and was caught by this class's own gametest
 * ({@code DormancyGameTest#freezingNeverWritesSilentOrNoGravityToNbt} failed
 * against it): disassembly of {@code Entity.saveWithoutId} shows it decides whether
 * to write {@code "Silent"}/{@code "NoGravity"} by calling {@code this.isSilent()}/
 * {@code this.isNoGravity()} - the exact virtual methods this Mixin overrides - not
 * by reading the underlying field directly. Serialization sees the same overridden
 * {@code true} as everything else and duly writes it out, reintroducing the exact
 * residue the getter override was meant to remove, just via a different path. The
 * fix is to let vanilla save normally and then strip both keys back out for a
 * frozen member, right before {@code saveWithoutId} returns
 * ({@code ValueOutput.discard(String)}, the same method vanilla's own passenger-list
 * handling in this method uses for an equivalent "written speculatively, remove if
 * it shouldn't have been" case).
 *
 * <p>Trade-off accepted, not overlooked: if a mob happens to already be
 * legitimately silent or gravity-less for some unrelated reason <em>before</em>
 * being frozen, and the world is saved and reloaded while it is still frozen, that
 * original {@code true} is not written this time either (this Mixin cannot tell "the
 * mob's own value" apart from "true only because it is frozen" - both read the same
 * way), so it comes back as the vanilla default ({@code false}) once thawed. This
 * only matters for a mob with an unusual pre-existing reason to be silent/gravity-
 * less (ordinary Cows/Zombies/etc. have none), and is a narrower, in-normal-
 * operation nuance rather than the "outlives the mod entirely" failure class being
 * fixed - accepted rather than chasing it with yet another piece of persisted
 * mod state.
 */
@Mixin(Entity.class)
public abstract class FrozenFlagsMixin {
    @Inject(method = "isSilent", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$frozenMembersAreSilent(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Mob mob && Dormancy.isFrozen(mob)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isNoGravity", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$frozenMembersHaveNoGravity(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Mob mob && Dormancy.isFrozen(mob)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void mobtimizer$dontPersistFrozenFlags(ValueOutput output, CallbackInfo ci) {
        if ((Object) this instanceof Mob mob && Dormancy.isFrozen(mob)) {
            output.discard("Silent");
            output.discard("NoGravity");
        }
    }
}
