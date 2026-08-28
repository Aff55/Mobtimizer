package com.mobtimizer.gametest;

import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Exercises {@link StackManager} - the single entry point that composes {@link
 * com.mobtimizer.identity.StackEligibility}, {@link com.mobtimizer.identity.StackKeyFactory}
 * and {@link com.mobtimizer.stack.DormantStore} - against real spawned mobs. As with the
 * lower-level stores it wraps, none of this can be a plain unit test: {@code merge}
 * reads real entity state ({@code canStack}, NBT-derived stack keys, the {@code STACK}/
 * {@code FROZEN} attachments) that only exists on a live {@code Mob} in a running level.
 */
public final class StackManagerGameTest {
    @GameTest
    public void mergeIncrementsCount(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

        helper.assertTrue(StackManager.merge(host, member), "matching cows should merge");
        helper.assertTrue(StackManager.countOf(host) == 2, "count should be 2");
        helper.succeed();
    }

    @GameTest
    public void differentTypesDoNotMerge(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        var pig = GameTestMobs.spawnPlain(helper, EntityTypes.PIG, new BlockPos(2, 2, 1));

        helper.assertFalse(StackManager.merge(host, pig), "a pig must not join a cow stack");
        helper.assertTrue(StackManager.countOf(host) == 1, "count should be unchanged");
        helper.succeed();
    }

    @GameTest
    public void unstackReleasesEveryMember(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1)));

        helper.assertTrue(StackManager.countOf(host) == 3, "three cows before unstack");
        helper.assertTrue(StackManager.unstack(host) == 2, "two members should be released");
        helper.assertTrue(StackManager.countOf(host) == 1, "host remains as a stack of 1");
        helper.succeed();
    }

    /**
     * {@code StackEligibility.canStack} already has its own dedicated gametest suite;
     * this only needs to prove {@code merge} actually consults it for one representative
     * exclusion, rather than, say, checking stack keys first and never reaching
     * eligibility at all.
     */
    @GameTest
    public void namedMobFailsEligibilityAndDoesNotMerge(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow named = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        named.setCustomName(Component.literal("Bessie"));

        helper.assertFalse(StackManager.merge(host, named), "a player-named cow should not be foldable into a stack");
        helper.assertTrue(StackManager.countOf(host) == 1, "host count must be unchanged by the refused merge");
        helper.assertTrue(StackManager.countOf(named) == 1, "the named cow must not itself read as merged");
        helper.succeed();
    }

    /**
     * Same entity type on both sides, unlike {@link #differentTypesDoNotMerge} - this is
     * the case that actually exercises {@code StackKeyFactory} comparison rather than the
     * cheaper {@code EntityType} mismatch. Reuses the armoured-vs-plain zombie pair
     * {@code StackKeyFactoryGameTest#armoredMobDiffersFromPlainMob} already verified
     * produces different keys.
     */
    @GameTest
    public void sameSpeciesDifferentEquipmentDoesNotMerge(GameTestHelper helper) {
        Zombie host = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, new BlockPos(1, 2, 1));
        Zombie armored = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, new BlockPos(2, 2, 1));
        armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));

        helper.assertFalse(StackManager.merge(host, armored),
                "an armoured zombie must not merge with a plain one despite sharing an entity type");
        helper.assertTrue(StackManager.countOf(host) == 1, "host count must be unchanged by the refused merge");
        helper.succeed();
    }

    @GameTest
    public void splitOneOnAnUnstackedHostReturnsNullAndDoesNotCorruptState(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));

        helper.assertTrue(StackManager.splitOne(host) == null, "a host with no members has nothing to split off");
        helper.assertTrue(StackManager.countOf(host) == 1, "the host must still read as a stack of only itself");
        helper.succeed();
    }

    /**
     * Pins the phase-1 "never merge a stack into a stack" rule the brief calls out by
     * name: the scanner is expected to only ever offer loose mobs as {@code member}, so
     * this never arises in normal play, but {@code StackManager} is the entry point that
     * has to hold the line if it ever is called that way regardless.
     */
    @GameTest
    public void stackIntoStackIsRefused(GameTestHelper helper) {
        Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow memberOfA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        helper.assertTrue(StackManager.merge(hostA, memberOfA), "setup sanity check: hostA should gain a member");

        Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 3));
        helper.assertFalse(StackManager.merge(hostB, hostA),
                "hostA is already a stack of 2 and must not be folded into hostB as a single member");
        helper.assertTrue(StackManager.countOf(hostB) == 1, "hostB must be unaffected by the refused merge");
        helper.assertTrue(StackManager.countOf(hostA) == 2, "hostA's own stack must be untouched");
        helper.succeed();
    }

    /**
     * The invariant that matters most for this whole class: {@code merge}'s return value
     * must always match whether a mob was actually added. {@code DormantStore.add} has no
     * guard against re-claiming a member that already belongs to a different host - by
     * design, per its own Javadoc, since detecting that cheaply would need an index over
     * every host's member list - so nothing below the {@code StackManager} layer stops
     * this on its own. Without a check here, this would merge hostA's already-frozen
     * member into hostB too: {@code canStack} does not look at the {@code FROZEN}
     * attachment, {@code isStacked(member)} only looks at {@code member}'s <em>own</em>
     * member list (empty - a plain member is not itself a host), and {@code
     * StackKeyFactory} would still find the two cows identical, so every check {@code
     * merge} runs would pass and {@code add} would happily double-book the UUID.
     */
    @GameTest
    public void mergingAMemberAlreadyClaimedByAnotherHostIsRefused(GameTestHelper helper) {
        Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        helper.assertTrue(StackManager.merge(hostA, member), "setup sanity check: the first merge should succeed");

        Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 3));
        helper.assertFalse(StackManager.merge(hostB, member),
                "a member already dormant under hostA must not also be claimed by hostB");
        helper.assertTrue(StackManager.countOf(hostB) == 1, "hostB must not gain a member it never actually got");
        helper.assertTrue(StackManager.countOf(hostA) == 2, "hostA's own stack must be untouched by the refused merge");
        helper.succeed();
    }

    /**
     * The symmetric half of {@link #mergingAMemberAlreadyClaimedByAnotherHostIsRefused}:
     * an already-claimed mob must not be usable as a brand new host either. Without this
     * check, {@code add(member, loose)} would succeed - {@code member}'s own {@code
     * STACK} attachment is empty, so neither of {@code DormantStore.add}'s guards fire -
     * silently producing a "host" that is itself frozen and so can never tick, meaning
     * whatever future logic drives a real host would never run for {@code loose} either.
     */
    @GameTest
    public void mergingUsingAnAlreadyClaimedMobAsHostIsRefused(GameTestHelper helper) {
        Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        helper.assertTrue(StackManager.merge(hostA, member), "setup sanity check: the first merge should succeed");

        Cow loose = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 3));
        helper.assertFalse(StackManager.merge(member, loose),
                "a mob already frozen as someone else's member must not be usable as a new host");
        helper.assertTrue(StackManager.countOf(loose) == 1, "the loose cow must remain unmerged");
        helper.assertTrue(StackManager.countOf(hostA) == 2, "hostA's own stack must be untouched");
        helper.succeed();
    }
}
