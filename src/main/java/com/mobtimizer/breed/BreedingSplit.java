package com.mobtimizer.breed;

import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Releases one member from a stack when a player feeds its host, so there is somebody
 * to breed with.
 *
 * <p><b>Why this is needed even though eligibility already protects courting animals.</b>
 * {@link com.mobtimizer.identity.StackEligibility} keeps babies, in-love animals and
 * animals on breeding cooldown out of stacks, which stops a courting animal being merged
 * away mid-courtship. A play-test showed that was necessary but not sufficient: breeding
 * still could not happen at all. The obstruction is structural, not a matter of identity
 * or eligibility - once a herd collapses into one host, that host is the <em>only</em>
 * interactable entity in it, because frozen members are neither rendered nor
 * right-clickable. Vanilla breeding needs two animals in love simultaneously, so with a
 * single visible animal it can never begin no matter what the stack rules say.
 *
 * <p><b>One member, not the whole stack.</b> Feeding releases exactly one partner and
 * leaves the rest stacked, which preserves vanilla's own two-feed flow rather than
 * short-cutting it: the player feeds the host, a partner appears, and the player still
 * has to feed that partner too. Unstacking the whole herd on a feed would work but would
 * defeat the entire point of the mod every time somebody held wheat.
 *
 * <p><b>Guards, and what each one prevents.</b> A split only happens when the target is
 * a stacked {@link Animal}, the held item is that species' own breeding food ({@code
 * Animal.isFood}, which every animal defines for itself, so this needs no per-species
 * list), and the animal could actually fall in love right now. That last guard is what
 * stops a player spamming wheat at an already-courting host from quietly dismantling the
 * farm one member per click - {@code canFallInLove()} is {@code inLove <= 0} in 26.2, so
 * it is checked alongside the baby and cooldown tests rather than relied on to cover
 * them.
 *
 * <p>The handler always returns {@link InteractionResult#PASS}: this releases a partner
 * and then gets out of the way, leaving vanilla to apply the actual feeding, love state,
 * item consumption and particles to the host exactly as it normally would. Nothing here
 * reimplements breeding.
 *
 * <p>Stack-aware breeding proper - one feed per member, a 100-stack fed 100 wheat
 * yielding 50 babies while respecting cooldowns - remains phase 3. This is only the
 * narrower phase 1 guarantee that the mod stops <em>preventing</em> vanilla breeding.
 */
public final class BreedingSplit {
    private BreedingSplit() {}

    /** Registered from {@code Mobtimizer.onInitialize()}. */
    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!level.isClientSide() && entity instanceof Mob mob) {
                splitPartnerForBreeding(mob, player.getItemInHand(hand));
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * Releases one member as a breeding partner if {@code held} is {@code mob}'s
     * breeding food and {@code mob} is a stacked animal that could fall in love right
     * now. Returns the released partner, or {@code null} when nothing was released -
     * which is the ordinary case for most interactions and never an error.
     *
     * <p>Visible for testing: {@code BreedingSplitGameTest} drives this directly rather
     * than through {@link UseEntityCallback}, the same way {@code DormancyGameTest}
     * drives {@code Dormancy} rather than the event that calls it.
     */
    public static @Nullable Mob splitPartnerForBreeding(Mob mob, ItemStack held) {
        if (!(mob instanceof Animal animal)) return null;
        if (!StackManager.isStacked(animal)) return null;
        if (!animal.isFood(held)) return null;

        // Mirrors StackEligibility's own age/love guards. An animal that cannot fall in
        // love right now has no use for a partner, and splitting one out anyway would
        // drain the stack a member per click.
        if (animal.isBaby()) return null;
        if (animal instanceof AgeableMob ageable && ageable.getAge() > 0) return null;
        if (!animal.canFallInLove()) return null;

        return StackManager.splitOne(animal);
    }
}
