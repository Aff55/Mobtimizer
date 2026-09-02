package com.mobtimizer.breed;

import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Makes feeding a stack behave like feeding one ordinary animal: a member steps out of
 * the stack, and that member is the one that falls in love.
 *
 * <p><b>Why a stack cannot simply be fed.</b> Once a herd collapses into one host, the
 * host is the only interactable entity in it - frozen members are neither rendered nor
 * right-clickable. Vanilla breeding needs two animals in love at once, so with a single
 * visible animal it can never begin, no matter what the stack rules say.
 *
 * <p><b>Why the love must go to the released member, not the host.</b> A first version
 * of this class released a partner and then returned {@code PASS}, letting vanilla apply
 * the love to the entity that was actually clicked - the host. That is wrong in a way
 * the gametests did not catch but a play-test did immediately: the host stands for every
 * member behind it, so a forty-cow stack ended up courting as a single entity. It also
 * broke the second feed, because the host was now in love and the guard below refused to
 * split again - so the player's second wheat was simply fed to the stack, and no second
 * partner ever appeared to breed with the first.
 *
 * <p>So the interaction is handled outright instead: split a member, put <em>that</em>
 * member in love, consume one item, and report {@link InteractionResult#SUCCESS} so
 * vanilla does not also feed the host. Two feeds therefore yield two ordinary courting
 * cows standing next to a stack that never entered love at all - exactly what feeding
 * two loose cows would have done.
 *
 * <p><b>How the cycle closes.</b> Nothing here needs to put the parents back. Once they
 * breed, vanilla puts both on the {@code setAge(6000)} cooldown, and
 * {@link com.mobtimizer.identity.StackEligibility} excludes any animal whose age is
 * above zero, so they stay loose and tick it down like ordinary cows. When it reaches
 * zero they become eligible again and the merge scanner folds them back into the herd on
 * its next pass, with no special-casing and no bookkeeping of its own. The calf stays
 * loose until it grows up, for the same reason.
 *
 * <p>Stack-aware breeding proper - one feed covering every member, a 100-stack fed 100
 * wheat yielding 50 babies while respecting cooldowns - remains phase 3. This is the
 * narrower phase 1 guarantee that the mod stops <em>preventing</em> vanilla breeding.
 */
public final class BreedingSplit {
    private BreedingSplit() {}

    /** Registered from {@code Mobtimizer.onInitialize()}. */
    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (level.isClientSide() || !(entity instanceof Mob mob)) return InteractionResult.PASS;

            if (feedForBreeding(mob, player.getItemInHand(hand), player) == null) {
                return InteractionResult.PASS;
            }

            // Handled outright: returning PASS here would let vanilla feed the host as
            // well, putting the whole stack into love on top of the partner we just
            // released.
            return InteractionResult.SUCCESS;
        });
    }

    /**
     * Releases one member as a breeding partner, puts that member in love, and consumes
     * one item from {@code held}. Returns the released partner, or {@code null} when
     * nothing was released - the ordinary outcome for most interactions, never an error.
     *
     * <p>Visible for testing: {@code BreedingSplitGameTest} drives this directly rather
     * than through {@link UseEntityCallback}, the same way {@code DormancyGameTest}
     * drives {@code Dormancy} rather than the event that calls it.
     */
    public static @Nullable Mob feedForBreeding(Mob mob, ItemStack held, @Nullable Player feeder) {
        if (!canReleasePartnerFor(mob, held)) return null;

        Mob partner = StackManager.splitOne(mob);
        if (partner == null) return null;

        if (partner instanceof Animal courting) {
            if (feeder != null) {
                courting.setInLove(feeder);
            } else {
                courting.setInLoveTime(600);
            }
        }

        // Respects creative mode on its own: ItemStack.consume checks
        // hasInfiniteMaterials before shrinking.
        held.consume(1, feeder);
        return partner;
    }

    /**
     * Whether feeding {@code mob} should release a partner at all.
     *
     * <p>The age and love guards mirror {@link
     * com.mobtimizer.identity.StackEligibility}'s. The love check matters less than it
     * did now that the host is never put in love by this class, but it still holds the
     * line if a host reaches love some other way - a third-party mod, or a pre-existing
     * world - and it keeps the rule stated in one obvious place rather than resting on
     * an invariant established elsewhere.
     */
    private static boolean canReleasePartnerFor(Mob mob, ItemStack held) {
        if (!(mob instanceof Animal animal)) return false;
        if (!StackManager.isStacked(animal)) return false;
        if (!animal.isFood(held)) return false;
        if (animal.isBaby()) return false;
        if (animal instanceof AgeableMob ageable && ageable.getAge() > 0) return false;
        return animal.canFallInLove();
    }
}
