package com.mobtimizer.gametest;

import com.mobtimizer.breed.BreedingSplit;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Covers the last half of the breeding fix a play-test found.
 *
 * <p>{@code StackEligibility} was taught to keep babies, in-love animals and animals on
 * breeding cooldown out of stacks, which stops a courting animal being merged away
 * mid-courtship. That was necessary but not sufficient, and the play-test said so:
 * breeding still could not happen. The reason is structural rather than a matter of
 * eligibility - once a herd collapses into one host, the host is the <em>only</em>
 * interactable entity in it. Frozen members cannot be right-clicked. Vanilla breeding
 * needs two animals in love, so with one visible animal it can never start, no matter
 * what the identity key or the eligibility rules say.
 *
 * <p>{@link BreedingSplit} closes that by releasing exactly one member when a player
 * feeds a stacked animal its breeding food, so a partner exists to be fed second. That
 * deliberately preserves vanilla's own two-feed flow rather than short-cutting it:
 * feeding once yields a partner, and the player still has to feed that partner too.
 * Stack-aware breeding proper - one feed per member, 100 wheat yielding 50 babies - is
 * phase 3.
 */
public final class BreedingSplitGameTest {
    private static final BlockPos HOST_POS = new BlockPos(1, 2, 1);
    private static final BlockPos MEMBER_POS = new BlockPos(2, 2, 1);

    private static Cow stackOfTwo(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        helper.assertTrue(StackManager.merge(host, member), "setup sanity check: two plain cows must merge");
        return host;
    }

    @GameTest
    public void feedingAStackedCowReleasesAPartnerToBreedWith(GameTestHelper helper) {
        Cow host = stackOfTwo(helper);
        helper.assertTrue(StackManager.countOf(host) == 2, "setup sanity check: the stack should represent two cows");

        Mob partner = BreedingSplit.splitPartnerForBreeding(host, new ItemStack(Items.WHEAT));

        helper.assertTrue(partner != null, "feeding a stacked cow its breeding food must release a partner");
        helper.assertFalse(partner.isInvisible(), "the released partner must be visible - it is there to be fed next");
        helper.assertTrue(StackManager.countOf(host) == 1, "the host must have given up exactly one member, not the whole stack");
        helper.succeed();
    }

    @GameTest
    public void feedingSomethingThatIsNotBreedingFoodReleasesNothing(GameTestHelper helper) {
        Cow host = stackOfTwo(helper);

        helper.assertTrue(BreedingSplit.splitPartnerForBreeding(host, new ItemStack(Items.STONE)) == null,
                "a cow must not give up a member for an item it does not breed on");
        helper.assertTrue(StackManager.countOf(host) == 2, "the stack must be untouched");
        helper.succeed();
    }

    /**
     * Without this the stack would drain one member per feed while the host was already
     * courting - a player spamming wheat would quietly dismantle the whole farm.
     */
    @GameTest
    public void feedingACowThatIsAlreadyInLoveReleasesNothing(GameTestHelper helper) {
        Cow host = stackOfTwo(helper);
        host.setInLoveTime(600);

        helper.assertTrue(BreedingSplit.splitPartnerForBreeding(host, new ItemStack(Items.WHEAT)) == null,
                "an animal already in love must not release a second partner");
        helper.assertTrue(StackManager.countOf(host) == 2, "the stack must be untouched");
        helper.succeed();
    }

    /** An unstacked animal is already its own partner - there is nothing to release. */
    @GameTest
    public void feedingAnUnstackedCowReleasesNothing(GameTestHelper helper) {
        Cow lone = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);

        helper.assertTrue(BreedingSplit.splitPartnerForBreeding(lone, new ItemStack(Items.WHEAT)) == null,
                "a lone cow has no members to release");
        helper.succeed();
    }

    /** A parent still on its post-breeding cooldown cannot fall in love, so must not split. */
    @GameTest
    public void feedingACowOnBreedingCooldownReleasesNothing(GameTestHelper helper) {
        Cow host = stackOfTwo(helper);
        host.setAge(6000);

        helper.assertTrue(BreedingSplit.splitPartnerForBreeding(host, new ItemStack(Items.WHEAT)) == null,
                "a parent on cooldown cannot breed yet, so releasing a partner would be pointless churn");
        helper.assertTrue(StackManager.countOf(host) == 2, "the stack must be untouched");
        helper.succeed();
    }
}
