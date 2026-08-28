package com.mobtimizer.gametest;

import com.mobtimizer.identity.StackEligibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.boss.wither.WitherBoss;
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

    @GameTest
    public void plainCowCanStack(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);

        helper.assertTrue(StackEligibility.canStack(cow), "a plain cow should be stackable");
        helper.succeed();
    }

    @GameTest
    public void namedCowCannotStack(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        cow.setCustomName(Component.literal("Bessie"));

        helper.assertFalse(StackEligibility.canStack(cow), "a player-named cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void noAiCowCannotStack(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        cow.setNoAi(true);

        helper.assertFalse(StackEligibility.canStack(cow), "a NoAI cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void invulnerableCowCannotStack(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        cow.setInvulnerable(true);

        helper.assertFalse(StackEligibility.canStack(cow), "an invulnerable cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void persistenceRequiredCowCannotStack(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        cow.setPersistenceRequired();

        helper.assertFalse(StackEligibility.canStack(cow), "a persistence-required cow should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void wolfCannotStack(GameTestHelper helper) {
        // Untamed on purpose: canStack excludes every OwnableEntity, not just currently-owned
        // ones, so a fresh, never-tamed wolf must already be ineligible.
        Wolf wolf = GameTestMobs.spawnPlain(helper, EntityTypes.WOLF, POS);

        helper.assertFalse(StackEligibility.canStack(wolf), "any OwnableEntity, tamed or not, should not be stackable");
        helper.succeed();
    }

    @GameTest
    public void denylistedVillagerCannotStack(GameTestHelper helper) {
        Villager villager = GameTestMobs.spawnPlain(helper, EntityTypes.VILLAGER, POS);

        helper.assertFalse(StackEligibility.canStack(villager), "villagers are denylisted by the default config");
        helper.succeed();
    }

    /**
     * Restores the design spec's dropped "is invulnerable, or is a boss" rule.
     * {@code WitherBoss.checkDespawn()} is a complete override with no {@code super}
     * call (confirmed by disassembly: peaceful-difficulty discard only, none of
     * {@code Mob}'s distance-based logic), so {@code FrozenDespawnMixin} never fires
     * for it - a frozen Wither member would be silently discarded the instant a
     * player switches the world to Peaceful. Excluded by explicit {@code instanceof}
     * in {@code StackEligibility}, not a general "is a boss" check: 26.2 has no
     * entity-type tag, shared interface, or common method for it (see that class's
     * Javadoc for what was checked), and {@code WitherBoss}/{@code EnderDragon} don't
     * even share a boss mechanism with each other, let alone something a general
     * check could hook. {@code minecraft:wither}/{@code minecraft:ender_dragon} are
     * also in the default denylist as defence in depth.
     *
     * <p>{@code EnderDragon} shares the same override-with-no-super shape but is not
     * separately covered here: spawning one in a gametest brings in dimension/fight-
     * coordinator machinery this test has no need to exercise, and the exclusion is
     * one shared line in {@code canStack} - proving it for {@code WitherBoss} proves
     * the line runs; a second {@code instanceof} in an already-passing test would not
     * add coverage of anything the first doesn't already reach.
     */
    @GameTest
    public void witherCannotStack(GameTestHelper helper) {
        WitherBoss wither = GameTestMobs.spawnPlain(helper, EntityTypes.WITHER, POS);

        helper.assertFalse(StackEligibility.canStack(wither),
                "a Wither must not be stackable - it silently bypasses FrozenDespawnMixin, "
                        + "so freezing it would risk losing it on a Peaceful-difficulty switch");
        helper.succeed();
    }

    @GameTest
    public void ridingMobsCannotStack(GameTestHelper helper) {
        Cow carrier = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow rider = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
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
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow holder = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        cow.setLeashedTo(holder, true);

        helper.assertTrue(cow.isLeashed(), "setup sanity check: cow should be leashed");
        helper.assertFalse(StackEligibility.canStack(cow), "a leashed cow should not be stackable");
        helper.succeed();
    }
}
