package com.mobtimizer.gametest;

import com.mobtimizer.freeze.Dormancy;
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
     * Formerly {@code stackIntoStackIsRefused}, pinning the phase-1 "never merge a
     * stack into a stack" rule. A post-approval review of Task 9 traced a real design
     * gap that rule caused: a farm's stacks could then only ever grow by simultaneous
     * crowding, so on a breeding farm - where mobs mature one at a time - separate
     * stacks would accumulate forever instead of consolidating, undercutting the mod's
     * whole purpose. The human ruled to fix it: stack-to-stack merging is now allowed,
     * transferring the absorbed stack's members onto the new host first (see {@link
     * StackManager#merge}'s Javadoc) so {@link com.mobtimizer.stack.DormantStore#add}'s
     * own "member must not itself be a stack host" guard - which exists to prevent
     * orphaning sub-members and must stay intact - is satisfied honestly rather than
     * relaxed. This test now pins the corrected behaviour: hostA (a stack of 2) merges
     * into hostB successfully, and hostA's own former member ends up under hostB, not
     * abandoned under the now-empty hostA.
     */
    @GameTest
    public void stackMergingIntoALooseHostTransfersItsMemberAndSucceeds(GameTestHelper helper) {
        Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow memberOfA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        helper.assertTrue(StackManager.merge(hostA, memberOfA), "setup sanity check: hostA should gain a member");

        Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 3));
        helper.assertTrue(StackManager.merge(hostB, hostA),
                "hostA (a stack of 2) must be absorbable into hostB, transferring its member rather than being refused");

        helper.assertTrue(StackManager.countOf(hostB) == 3,
                "hostB must now represent all three cows: itself, hostA, and hostA's former member");
        helper.assertTrue(StackManager.countOf(hostA) == 1,
                "hostA must read as an ordinary stack of only itself now - its own member list was drained, not left behind");
        helper.assertTrue(Dormancy.isFrozen(hostA), "hostA itself is now a frozen member of hostB");
        helper.assertTrue(Dormancy.isFrozen(memberOfA), "hostA's former member must still be frozen, now under hostB");
        helper.assertTrue(memberOfA.position().distanceTo(hostB.position()) < 0.001,
                "hostA's transferred former member must be co-located with its new host, hostB, not left at hostA's old position");

        helper.succeed();
    }

    /**
     * The other half of the fix: both sides of the merge are already multi-member
     * stacks, not just one. Total mob count must be conserved across the merge - five
     * cows before, five cows represented by hostB afterwards - proving {@code
     * transferMembers} moves every one of hostA's members, not just the first.
     */
    @GameTest
    public void twoMultiMemberStacksMergeWithConservedTotalCount(GameTestHelper helper) {
        Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow itsOwnMember = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
        helper.assertTrue(StackManager.merge(hostB, itsOwnMember), "setup sanity check: hostB should gain a member");
        helper.assertTrue(StackManager.countOf(hostB) == 2, "setup sanity check: hostB is a stack of 2");

        Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1));
        Cow memberA1 = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 2));
        Cow memberA2 = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 3));
        helper.assertTrue(StackManager.merge(hostA, memberA1), "setup sanity check: hostA should gain its first member");
        helper.assertTrue(StackManager.merge(hostA, memberA2), "setup sanity check: hostA should gain its second member");
        helper.assertTrue(StackManager.countOf(hostA) == 3, "setup sanity check: hostA is a stack of 3");

        helper.assertTrue(StackManager.merge(hostB, hostA), "two multi-member stacks must be mergeable");

        helper.assertTrue(StackManager.countOf(hostB) == 5,
                "hostB must now represent all five cows (its original 2 plus hostA's original 3)");
        helper.assertTrue(StackManager.countOf(hostA) == 1, "hostA must read as drained down to just itself");
        helper.assertTrue(memberA1.position().distanceTo(hostB.position()) < 0.001,
                "hostA's first former member must be co-located with the new host");
        helper.assertTrue(memberA2.position().distanceTo(hostB.position()) < 0.001,
                "hostA's second former member must be co-located with the new host too, not just the first one transferred");

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
