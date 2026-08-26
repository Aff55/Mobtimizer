package com.mobtimizer.gametest;

import com.mobtimizer.identity.StackEligibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Exercises {@link StackEligibility#canStack} against real spawned mobs.
 *
 * <p>{@code canStack} takes a live {@code Mob}, so its branches cannot be reached from a plain
 * unit test without either constructing a real entity (not worth mocking/faking) or running one
 * inside an actual server tick. This class is that real coverage; {@code MobtimizerConfigTest}
 * separately covers {@code MobtimizerConfig.Entities.isAllowed} in isolation.
 */
public final class StackEligibilityGameTest {
    private static final BlockPos POS = new BlockPos(1, 2, 1);

    /**
     * The convenience {@link GameTestHelper#spawn} methods mark every mob they spawn
     * persistence-required by default, so test fixtures cannot despawn mid-test. Left in place,
     * that would make {@code canStack} false for every mob spawned below regardless of which
     * condition a test claims to isolate — including the "plain" positive case. Going through the
     * builder to turn it off is what makes each test below actually test one condition.
     */
    private static <E extends Entity> E spawnPlain(GameTestHelper helper, EntityType<E> type, BlockPos pos) {
        return helper.spawnEntity(type, pos).requirePersistence(false).spawn();
    }

    @GameTest
    public void plainCowCanStack(GameTestHelper helper) {
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);

        helper.assertTrue(StackEligibility.canStack(cow), "a plain cow should be stackable");
        helper.succeed();
    }

    @GameTest
    public void namedCowCannotStack(GameTestHelper helper) {
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);
        cow.setCustomName(Component.literal("Bessie"));

        helper.assertFalse(StackEligibility.canStack(cow), "a player-named cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void noAiCowCannotStack(GameTestHelper helper) {
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);
        cow.setNoAi(true);

        helper.assertFalse(StackEligibility.canStack(cow), "a NoAI cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void invulnerableCowCannotStack(GameTestHelper helper) {
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);
        cow.setInvulnerable(true);

        helper.assertFalse(StackEligibility.canStack(cow), "an invulnerable cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void persistenceRequiredCowCannotStack(GameTestHelper helper) {
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);
        cow.setPersistenceRequired();

        helper.assertFalse(StackEligibility.canStack(cow), "a persistence-required cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void wolfCannotStack(GameTestHelper helper) {
        // Untamed on purpose: canStack excludes every OwnableEntity, not just currently-owned
        // ones, so a fresh, never-tamed wolf must already be ineligible.
        Wolf wolf = spawnPlain(helper, EntityTypes.WOLF, POS);

        helper.assertFalse(StackEligibility.canStack(wolf), "any OwnableEntity, tamed or not, should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void denylistedVillagerCannotStack(GameTestHelper helper) {
        Villager villager = spawnPlain(helper, EntityTypes.VILLAGER, POS);

        helper.assertFalse(StackEligibility.canStack(villager), "villagers are denylisted by the default config");
        helper.succeed();
    }

    @GameTest
    public void ridingMobsCannotStack(GameTestHelper helper) {
        Cow carrier = spawnPlain(helper, EntityTypes.COW, POS);
        Cow rider = spawnPlain(helper, EntityTypes.COW, POS.above());
        // force = true: a cow cannot normally mount another cow, and forcing past that check is
        // fine here since only the resulting isPassenger/isVehicle flags matter for this test.
        rider.startRiding(carrier, true, true);

        helper.assertTrue(rider.isPassenger(), "setup sanity check: rider should be riding");
        helper.assertTrue(carrier.isVehicle(), "setup sanity check: carrier should have a passenger");
        helper.assertFalse(StackEligibility.canStack(rider), "a mob riding something should not be stackable");
        helper.assertFalse(StackEligibility.canStack(carrier), "a mob carrying a passenger should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void leashedCowCannotStack(GameTestHelper helper) {
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);
        Cow holder = spawnPlain(helper, EntityTypes.COW, POS.above());
        cow.setLeashedTo(holder, true);

        helper.assertTrue(cow.isLeashed(), "setup sanity check: cow should be leashed");
        helper.assertFalse(StackEligibility.canStack(cow), "a leashed cow should not be stackable");
        helper.succeed();
    }
}
