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
import net.minecraft.server.level.ServerPlayer;

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

    private static Cow stackOfThree(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        for (int i = 1; i <= 2; i++) {
            Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1 + i, 2, 1));
            helper.assertTrue(StackManager.merge(host, member), "setup sanity check: matching cows must merge");
        }
        return host;
    }

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

        Mob partner = BreedingSplit.feedForBreeding(host, new ItemStack(Items.WHEAT), null);

        helper.assertTrue(partner != null, "feeding a stacked cow its breeding food must release a partner");
        helper.assertFalse(partner.isInvisible(), "the released partner must be visible - it is there to be fed next");
        helper.assertTrue(StackManager.countOf(host) == 1, "the host must have given up exactly one member, not the whole stack");
        helper.succeed();
    }

    @GameTest
    public void feedingSomethingThatIsNotBreedingFoodReleasesNothing(GameTestHelper helper) {
        Cow host = stackOfTwo(helper);

        helper.assertTrue(BreedingSplit.feedForBreeding(host, new ItemStack(Items.STONE), null) == null,
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

        helper.assertTrue(BreedingSplit.feedForBreeding(host, new ItemStack(Items.WHEAT), null) == null,
                "an animal already in love must not release a second partner");
        helper.assertTrue(StackManager.countOf(host) == 2, "the stack must be untouched");
        helper.succeed();
    }

    /** An unstacked animal is already its own partner - there is nothing to release. */
    @GameTest
    public void feedingAnUnstackedCowReleasesNothing(GameTestHelper helper) {
        Cow lone = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);

        helper.assertTrue(BreedingSplit.feedForBreeding(lone, new ItemStack(Items.WHEAT), null) == null,
                "a lone cow has no members to release");
        helper.succeed();
    }

    /** A parent still on its post-breeding cooldown cannot fall in love, so must not split. */
    @GameTest
    public void feedingACowOnBreedingCooldownReleasesNothing(GameTestHelper helper) {
        Cow host = stackOfTwo(helper);
        host.setAge(6000);

        helper.assertTrue(BreedingSplit.feedForBreeding(host, new ItemStack(Items.WHEAT), null) == null,
                "a parent on cooldown cannot breed yet, so releasing a partner would be pointless churn");
        helper.assertTrue(StackManager.countOf(host) == 2, "the stack must be untouched");
        helper.succeed();
    }

    /**
     * The bug a second play-test found: the love was landing on the <em>stack</em>.
     * Feeding a host released a partner but then let vanilla put the host itself into
     * love mode - and the host represents every member behind it, so a herd of 40 cows
     * was courting as one entity. Worse, the second feed found the host already in love,
     * refused to split, and simply re-fed the stack.
     *
     * <p>Love now goes to the released partner and the stack is left alone, so each feed
     * hands the player one ordinary courting cow, exactly as feeding two loose cows
     * would.
     */
    @GameTest
    @SuppressWarnings("removal")
    public void feedingAStackPutsTheSplitPartnerInLoveAndNeverTheStack(GameTestHelper helper) {
        Cow host = stackOfThree(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack wheat = new ItemStack(Items.WHEAT, 8);

        Mob first = BreedingSplit.feedForBreeding(host, wheat, player);

        helper.assertTrue(first != null, "feeding a stacked cow must release a partner");
        helper.assertTrue(((Cow) first).isInLove(), "the released partner is the one that should be courting");
        helper.assertFalse(host.isInLove(), "the stack itself must never fall in love - it stands for every member behind it");
        helper.assertTrue(StackManager.countOf(host) == 2, "exactly one member should have left");
        helper.succeed();
    }

    /**
     * The direct regression guard for "the second feed re-fed the stack instead of
     * splitting again". Two feeds must yield two courting cows, which is what actually
     * lets them breed.
     */
    @GameTest
    @SuppressWarnings("removal")
    public void asecondFeedReleasesASecondPartnerRatherThanFeedingTheStack(GameTestHelper helper) {
        Cow host = stackOfThree(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack wheat = new ItemStack(Items.WHEAT, 8);

        Mob first = BreedingSplit.feedForBreeding(host, wheat, player);
        Mob second = BreedingSplit.feedForBreeding(host, wheat, player);

        helper.assertTrue(first != null && second != null, "two feeds must release two partners");
        helper.assertTrue(first != second, "and they must be two different cows");
        helper.assertTrue(((Cow) first).isInLove() && ((Cow) second).isInLove(), "both must be courting, or they cannot breed");
        helper.assertFalse(host.isInLove(), "the stack still must not be in love");
        helper.assertTrue(StackManager.countOf(host) == 1, "both partners should have left the stack");
        helper.succeed();
    }

    /**
     * Feeding costs wheat, exactly as it does for a loose cow.
     *
     * <p>Driven with a {@code null} feeder rather than the mock player on purpose:
     * {@code ItemStack.consume} skips the shrink entirely for a holder with infinite
     * materials, and {@code makeMockServerPlayerInLevel}'s player turns out to be one -
     * an earlier version of this test asserted against that player and failed, which is
     * correct creative-mode behaviour rather than a defect. A null holder exercises the
     * survival path deterministically, with no dependence on the harness's choice of
     * game mode.
     */
    @GameTest
    public void feedingAStackConsumesOneItem(GameTestHelper helper) {
        Cow host = stackOfThree(helper);
        ItemStack wheat = new ItemStack(Items.WHEAT, 8);

        helper.assertTrue(BreedingSplit.feedForBreeding(host, wheat, null) != null,
                "setup sanity check: the feed must actually release a partner");

        helper.assertTrue(wheat.getCount() == 7, "exactly one wheat should have been consumed, not zero and not the whole stack");
        helper.succeed();
    }

    /** The other half of that contract: a creative feeder keeps their wheat. */
    @GameTest
    @SuppressWarnings("removal")
    public void feedingAStackInCreativeConsumesNothing(GameTestHelper helper) {
        Cow host = stackOfThree(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(player.hasInfiniteMaterials(), "setup sanity check: the harness's mock player is a creative one");
        ItemStack wheat = new ItemStack(Items.WHEAT, 8);

        helper.assertTrue(BreedingSplit.feedForBreeding(host, wheat, player) != null,
                "a creative player must still be able to release a partner");

        helper.assertTrue(wheat.getCount() == 8, "a creative feeder must not lose wheat");
        helper.succeed();
    }

    /**
     * The end of the cycle the play-test asked for: a parent that has finished its
     * post-breeding cooldown is ordinary again, so the merge scanner can fold it back
     * into the herd with no special-casing anywhere.
     */
    @GameTest
    public void aParentWhoseCooldownExpiredCanRejoinAStack(GameTestHelper helper) {
        Cow host = stackOfThree(helper);
        Cow parent = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(4, 2, 1));

        parent.setAge(6000);
        helper.assertFalse(StackManager.merge(host, parent), "a parent still on cooldown must stay out of the stack");

        parent.setAge(0);
        helper.assertTrue(StackManager.merge(host, parent), "once the cooldown expires it must be able to rejoin");
        helper.succeed();
    }
}
