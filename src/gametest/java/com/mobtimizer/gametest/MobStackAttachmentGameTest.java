package com.mobtimizer.gametest;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.stack.MobStack;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.UUID;

/**
 * Exercises {@link MobtimizerAttachments#STACK} against a real spawned mob.
 *
 * <p>{@code MobStackTest} proves {@code MobStack.CODEC} round-trips through {@code
 * JsonOps}, which says nothing about whether the attachment this codec is registered
 * under actually reaches an entity's saved NBT and comes back on load - that depends
 * on {@link MobtimizerAttachments#register()} being wired correctly into the real
 * entity save/load path, which no unit test can reach. This class drives that path
 * directly: serialize a mob exactly as {@code StackKeyFactory#rawSerialize} does, then
 * load the resulting tag back and check what survived.
 *
 * <p>Every round trip below reuses the same live mob for the save and the load
 * (mutating its attachment in between where needed) rather than loading one mob's tag
 * into a second live mob. {@code saveWithoutId} writes the "UUID" key, so loading one
 * mob's full tag into a second, already-spawned mob would leave two live entities
 * sharing a UUID in the same level - an identity collision this mod's own
 * {@code StackKeyFactory} treats as significant. Reusing one entity avoids that
 * entirely while still exercising the real save/load path.
 */
public final class MobStackAttachmentGameTest {
    private static final BlockPos POS = new BlockPos(1, 2, 1);

    /**
     * {@code AttachmentType.initializer()} is only realised by {@code
     * getAttachedOrCreate}/{@code getAttachedOrSet}, which also persist the created
     * value. Plain {@code getAttached} returns {@code null} until something has
     * actually called {@code setAttached}, even though {@link MobtimizerAttachments}
     * registers an initializer. Later tasks reading a host's stack must go through
     * {@code getAttachedOrElse}/{@code getAttachedOrCreate}, not assume {@code
     * getAttached} is never null - this test pins down that exact behaviour.
     */
    @GameTest
    public void unsetAttachmentReadsAsAbsentNotTheInitializerDefault(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);

        helper.assertTrue(cow.getAttached(MobtimizerAttachments.STACK) == null,
                "a mob nothing has ever attached to should read as absent, not silently equal to the initializer's default");
        helper.assertTrue(cow.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY) == MobStack.EMPTY,
                "getAttachedOrElse should hand back the caller's default without attaching or persisting it");
        helper.succeed();
    }

    @GameTest
    public void stackAttachmentSurvivesSaveAndLoad(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        MobStack original = MobStack.EMPTY
                .withMember(UUID.randomUUID())
                .withMember(UUID.randomUUID())
                .withNameplateOwned(true);
        cow.setAttached(MobtimizerAttachments.STACK, original);

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, cow.level().registryAccess());
        cow.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing the cow should not report problems");
        helper.assertTrue(tag.contains(AttachmentTarget.NBT_ATTACHMENT_KEY),
                "a persistent attachment that was actually set should be written under fabric's attachment NBT key");

        // Overwrite the live attachment first so the assertion below can only pass
        // because load() actually restored it from the tag, not because it was left
        // untouched the whole time.
        cow.setAttached(MobtimizerAttachments.STACK, MobStack.EMPTY);

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, cow.level().registryAccess(), tag);
        cow.load(input);
        helper.assertTrue(loadProblems.isEmpty(), "loading the cow's own just-saved tag should not report problems");

        helper.assertTrue(original.equals(cow.getAttached(MobtimizerAttachments.STACK)),
                "the stack attachment must survive a real entity save/load round trip, including its member list and nameplateOwned flag");
        helper.succeed();
    }

    @GameTest
    public void mobWithNoAttachmentSetRoundTripsWithoutManufacturingOne(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, cow.level().registryAccess());
        cow.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing the cow should not report problems");
        helper.assertFalse(tag.contains(AttachmentTarget.NBT_ATTACHMENT_KEY),
                "a mob with no attachment ever set should not gain one just by being saved");

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, cow.level().registryAccess(), tag);
        cow.load(input);
        helper.assertTrue(loadProblems.isEmpty(), "loading the cow's own just-saved tag should not report problems");

        helper.assertTrue(cow.getAttached(MobtimizerAttachments.STACK) == null,
                "loading a tag with no attachment data must not manufacture one from the registered initializer");
        helper.succeed();
    }
}
