package com.mobtimizer.gametest;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * The phase 1 wrap-up test: whether a stack still is one after the world reloads.
 *
 * <p>Persistence is the property nothing else here proves, and the one most likely to be
 * quietly broken - a stack that forgets its members on reload would look perfectly
 * healthy in every other test in this suite, right up until a player restarted their
 * server and found a farm full of invisible, inert, unreachable cows.
 *
 * <p>This reconstructs a genuinely new host entity from the saved tag rather than
 * loading the tag back into the still-live object, because those two things prove
 * different amounts. Loading into a live entity shows the NBT round-trips; only
 * discarding and rebuilding shows that a host recovered from disk can still find its
 * members. The members themselves are deliberately left alone in the level throughout -
 * that is exactly what a chunk reload does to them, and it is what makes
 * {@code countOf} resolving to 3 afterwards meaningful rather than tautological.
 *
 * <p>The discard is required, not tidiness: {@code saveWithoutId} writes the "UUID" key,
 * so the reconstructed host carries the original's UUID, and the entity manager refuses
 * to add an entity whose UUID is already known. Discarding first frees it, which is also
 * the order a real chunk reload uses - the old instance goes away, then a new one is
 * built from the saved tag.
 *
 * <p>Note the plan's own sample for this task used {@code Entity.load(CompoundTag)} and
 * {@code ProblemReporter.DISCARDING}; neither is right for 26.2, which is why it carried
 * an instruction to check against the ValueInput/ValueOutput API instead. A discarding
 * reporter would also have been the wrong choice here on purpose: this test wants to
 * hear about serialization problems, not swallow them.
 */
public final class PersistenceGameTest {
    private static final BlockPos HOST_POS = new BlockPos(1, 2, 1);

    @GameTest
    public void aStackStillHasItsMembersAfterTheHostIsRebuiltFromDisk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        for (int i = 1; i <= 2; i++) {
            Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1 + i, 2, 1));
            helper.assertTrue(StackManager.merge(host, member), "setup sanity check: matching cows must merge");
        }
        helper.assertTrue(StackManager.countOf(host) == 3, "setup sanity check: the stack should represent three cows");

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, level.registryAccess());
        host.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing a stack host should not report problems");

        host.discard();

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, level.registryAccess(), tag);
        Mob reloaded = (Mob) EntityType.create(EntityTypes.COW, input, level, EntitySpawnReason.LOAD)
                .orElseThrow(() -> new AssertionError("a stack host's own saved tag must reconstruct into an entity"));
        helper.assertTrue(loadProblems.isEmpty(), "reconstructing a stack host from its own saved tag should not report problems");
        helper.assertTrue(level.addFreshEntity(reloaded), "the reconstructed host must actually enter the level");

        helper.assertTrue(reloaded.getAttached(MobtimizerAttachments.STACK) != null,
                "the stack attachment must persist");
        helper.assertTrue(StackManager.countOf(reloaded) == 3,
                "member count must survive a save/load round trip - a reloaded host that cannot find its members "
                        + "leaves every one of them invisible, inert and unreachable forever");
        helper.assertTrue(StackManager.isStacked(reloaded), "and the rebuilt host must still report as stacked");

        helper.succeed();
    }

    /**
     * The other half of the same guarantee, from the member's side: a reloaded farm must
     * stay dormant rather than waking every member at once. Unstacking is then shown to
     * still work on the rebuilt host, which is what proves the members were genuinely
     * still reachable rather than merely still counted.
     */
    @GameTest
    public void reloadedMembersStayDormantAndCanStillBeReleased(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        helper.assertTrue(StackManager.merge(host, member), "setup sanity check: matching cows must merge");
        helper.assertTrue(Dormancy.isFrozen(member), "setup sanity check: a merged member is frozen");

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, level.registryAccess());
        host.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing a stack host should not report problems");

        host.discard();

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, level.registryAccess(), tag);
        Mob reloaded = (Mob) EntityType.create(EntityTypes.COW, input, level, EntitySpawnReason.LOAD)
                .orElseThrow(() -> new AssertionError("a stack host's own saved tag must reconstruct into an entity"));
        helper.assertTrue(loadProblems.isEmpty(), "reconstructing a stack host should not report problems");
        helper.assertTrue(level.addFreshEntity(reloaded), "the reconstructed host must actually enter the level");

        helper.assertTrue(Dormancy.isFrozen(member),
                "the member must still be dormant after its host was rebuilt - a reloaded farm must not wake at once");

        helper.assertTrue(StackManager.unstack(reloaded) == 1,
                "the rebuilt host must still be able to release its member, proving it was genuinely reachable");
        helper.assertFalse(Dormancy.isFrozen(member), "and the released member must be thawed");

        helper.succeed();
    }
}
