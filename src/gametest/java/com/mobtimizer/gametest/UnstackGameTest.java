package com.mobtimizer.gametest;

import com.mobtimizer.command.MobtimizerCommand;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;

/**
 * Exercises {@link MobtimizerCommand}'s unstack helpers against a real {@code
 * ServerLevel}. The Brigadier wiring itself is not driven here - these are the methods
 * the command nodes delegate to, and they are where the behaviour worth pinning down
 * lives: finding stacked hosts in a live level and releasing their members.
 */
public final class UnstackGameTest {
    private static Cow stackOf(GameTestHelper helper, int total) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        for (int i = 1; i < total; i++) {
            Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1 + i, 2, 1));
            helper.assertTrue(StackManager.merge(host, member), "setup sanity check: matching cows must merge");
        }
        return host;
    }

    @GameTest
    public void unstackRestoresIndependentMobs(GameTestHelper helper) {
        Cow host = stackOf(helper, 3);
        helper.assertTrue(StackManager.countOf(host) == 3, "setup sanity check: the stack should represent three cows");

        int released = MobtimizerCommand.unstackAll(helper.getLevel());

        helper.assertTrue(released == 2, "two members should be released");
        helper.assertTrue(StackManager.countOf(host) == 1, "host is alone again");
        helper.assertFalse(StackManager.isStacked(host), "and should no longer report as stacked");
        helper.succeed();
    }

    /** Nothing stacked is not an error - it releases nothing and reports zero. */
    @GameTest
    public void unstackAllOnAnEmptyLevelReleasesNothing(GameTestHelper helper) {
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));

        helper.assertTrue(MobtimizerCommand.unstackAll(helper.getLevel()) == 0,
                "a level with no stacks should release nothing rather than fail");
        helper.succeed();
    }

    /**
     * The radius filter is measured from the host's own position. A stack well outside
     * the radius must be left completely alone - that is the whole point of "here"
     * rather than "all".
     */
    @GameTest
    public void unstackNearOnlyReleasesStacksInsideTheRadius(GameTestHelper helper) {
        Cow host = stackOf(helper, 3);

        int releasedFarAway = MobtimizerCommand.unstackNear(helper.getLevel(), host.position().add(500, 0, 0), 16.0);
        helper.assertTrue(releasedFarAway == 0, "a stack 500 blocks outside the radius must not be touched");
        helper.assertTrue(StackManager.countOf(host) == 3, "and must keep every member");

        int releasedNearby = MobtimizerCommand.unstackNear(helper.getLevel(), host.position(), 16.0);
        helper.assertTrue(releasedNearby == 2, "a stack inside the radius must be released");
        helper.assertTrue(StackManager.countOf(host) == 1, "leaving the host alone");
        helper.succeed();
    }
}
