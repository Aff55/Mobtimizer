package com.mobtimizer.gametest;

import com.mobtimizer.display.StackNameplate;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;

/**
 * Exercises {@link StackNameplate} against real spawned mobs.
 *
 * <p>The count label is shown through the host's own vanilla custom name, so an
 * unmodified client sees it with no client-side mod. That choice creates the trap this
 * class mostly exists to guard: {@code StackEligibility} rejects custom-named mobs,
 * because a player-assigned name means "this one is special" - and the label <em>is</em>
 * a custom name. Set naively, every host would become permanently ineligible the moment
 * it was labelled, and stacks would silently stop growing with no error anywhere. The
 * {@code nameplateOwned} flag on {@code MobStack} distinguishes a label this mod set
 * from one a player set, and {@link #modOwnedLabelDoesNotBlockFurtherMerging} is the
 * regression guard for it.
 */
public final class StackNameplateGameTest {
    private static Cow hostWithMembers(GameTestHelper helper, int extraMembers) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        for (int i = 0; i < extraMembers; i++) {
            Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2 + i, 2, 1));
            helper.assertTrue(StackManager.merge(host, member), "setup sanity check: matching cows must merge");
        }
        return host;
    }

    @GameTest
    public void labelShowsTheCountAndIsHoverOnly(GameTestHelper helper) {
        Cow host = hostWithMembers(helper, 1);

        helper.assertTrue(host.hasCustomName(), "a stack should be labelled");
        helper.assertTrue(host.getCustomName().getString().contains("2"), "label should show the count");
        helper.assertFalse(host.isCustomNameVisible(), "label is hover-only by default");
        helper.succeed();
    }

    /**
     * The regression guard for this task's whole reason to exist: a host carrying the
     * mod's own label must still be eligible to absorb more members.
     */
    @GameTest
    public void modOwnedLabelDoesNotBlockFurtherMerging(GameTestHelper helper) {
        Cow host = hostWithMembers(helper, 1);
        helper.assertTrue(host.hasCustomName(), "setup sanity check: the host must actually be labelled by now");
        helper.assertTrue(StackNameplate.isModOwnedName(host), "the label must be recognised as this mod's own");

        Cow third = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1));
        helper.assertTrue(StackManager.merge(host, third), "a mod-owned nameplate must not make the host ineligible");
        helper.assertTrue(StackManager.countOf(host) == 3, "count should reach 3");
        helper.assertTrue(host.getCustomName().getString().contains("3"), "the label must follow the new count");
        helper.succeed();
    }

    @GameTest
    public void unstackedHostLosesItsLabel(GameTestHelper helper) {
        Cow host = hostWithMembers(helper, 1);
        StackManager.unstack(host);

        helper.assertFalse(host.hasCustomName(), "a stack of 1 should have no label");
        helper.assertFalse(StackNameplate.isModOwnedName(host), "and should no longer claim to own a nameplate");
        helper.succeed();
    }

    /**
     * A player-assigned name is not ours to clear or overwrite. An unstacked mob that a
     * player named must keep that name, and must not be reported as mod-owned - which is
     * also what keeps {@code StackEligibility} correctly rejecting it.
     */
    @GameTest
    public void aPlayerNamedMobIsNotTreatedAsModOwned(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        cow.setCustomName(net.minecraft.network.chat.Component.literal("Bessie"));

        helper.assertFalse(StackNameplate.isModOwnedName(cow), "a player's name must never read as mod-owned");

        StackNameplate.refresh(cow);
        helper.assertTrue(cow.hasCustomName(), "refreshing an unstacked, player-named mob must not strip its name");
        helper.assertTrue(cow.getCustomName().getString().equals("Bessie"), "and must leave the name exactly as the player set it");
        helper.succeed();
    }
}
