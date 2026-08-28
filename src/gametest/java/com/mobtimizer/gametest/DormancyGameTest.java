package com.mobtimizer.gametest;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.identity.StackEligibility;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

import java.lang.reflect.Method;

/**
 * Exercises {@link Dormancy} and the freeze Mixins against real spawned mobs and a
 * real {@code ServerLevel}. As with {@code DormantStoreGameTest}, none of this can
 * be a plain unit test: it depends on the real entity tick dispatcher, the real
 * despawn check, the real push-candidate filter, and a real NBT save/load round
 * trip, none of which exist outside a live level.
 */
public final class DormancyGameTest {
    private static final BlockPos HOST_POS = new BlockPos(1, 2, 1);
    private static final BlockPos MEMBER_POS = new BlockPos(3, 2, 3);

    @GameTest
    public void freezeThenThawRestoresTheMob(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);

        Dormancy.freeze(member, host);

        helper.assertTrue(Dormancy.isFrozen(member), "member should report frozen");
        helper.assertTrue(member.isSilent(), "frozen members must not make noise");
        helper.assertTrue(member.isInvisible(), "frozen members must not be rendered");
        helper.assertTrue(member.isNoGravity(), "frozen members must not fall");
        helper.assertTrue(member.position().distanceTo(host.position()) < 0.001,
                "frozen members are moved onto the host");

        Dormancy.thaw(member);

        helper.assertFalse(Dormancy.isFrozen(member), "member should report thawed");
        helper.assertFalse(member.isInvisible(), "thawed members are visible again");
        helper.assertFalse(member.isSilent(), "thawed members make noise again");
        helper.assertFalse(member.isNoGravity(), "thawed members are affected by gravity again");

        helper.succeed();
    }

    /**
     * Calls {@code ServerLevel.tickNonPassenger} directly - the exact method
     * {@link com.mobtimizer.mixin.EntityTickMixin} injects into - rather than
     * waiting on the natural tick loop, so the assertion is both precise and
     * immediate. {@code Entity.tickCount} is public and is unconditionally
     * incremented by that method unless cancelled (confirmed by disassembly), which
     * makes it a direct, load-bearing observable of "did this entity's tick
     * actually run" rather than a proxy for it.
     *
     * <p>Runs the same mob through all three states - ticking, frozen, thawed - so
     * the "frozen" assertion cannot pass vacuously (e.g. because gametest mobs
     * never tick at all): the same {@code tickNonPassenger} call is shown to work
     * both before freezing and after thawing.
     */
    @GameTest
    public void aFrozenMemberDoesNotTickInAggressiveMode(GameTestHelper helper) {
        helper.assertTrue(ConfigManager.get().freeze.isAggressive(),
                "setup sanity check: this test assumes the default AGGRESSIVE mode");

        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);

        int beforeFreeze = member.tickCount;
        helper.getLevel().tickNonPassenger(member);
        helper.assertTrue(member.tickCount == beforeFreeze + 1,
                "control: before freezing, this mob's tick must run normally");

        Dormancy.freeze(member, host);
        int whileFrozen = member.tickCount;
        helper.getLevel().tickNonPassenger(member);
        helper.assertTrue(member.tickCount == whileFrozen,
                "a frozen member's tick must be skipped entirely in AGGRESSIVE mode");

        Dormancy.thaw(member);
        int afterThaw = member.tickCount;
        helper.getLevel().tickNonPassenger(member);
        helper.assertTrue(member.tickCount == afterThaw + 1,
                "once thawed, the member must tick normally again");

        helper.succeed();
    }

    /**
     * Pins down the correction this task made to the brief's own sample Mixin:
     * {@code LivingEntity.isPushable()} fully overrides {@code Entity.isPushable()}
     * (confirmed by disassembly), so a Mixin on {@code Entity} - which is what an
     * earlier draft, and the brief, specified - would resolve and load without
     * error but silently never fire for any {@code Mob}. This test would have
     * caught that: it fails if {@link com.mobtimizer.mixin.EntityCollisionMixin}
     * targets the wrong class.
     */
    @GameTest
    public void aFrozenMemberIsNotPushable(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);

        helper.assertTrue(member.isPushable(), "setup sanity check: an ordinary cow must be pushable");

        Dormancy.freeze(member, host);
        helper.assertFalse(member.isPushable(), "a frozen member must not be pushable");

        Dormancy.thaw(member);
        helper.assertTrue(member.isPushable(), "a thawed member must be pushable again");

        helper.succeed();
    }

    /**
     * Cow will not serve for this one: disassembly (confirmed empirically - an
     * earlier version of this test used Cow throughout and its own sanity check
     * failed) shows {@code Animal.removeWhenFarAway(double)} unconditionally
     * returns {@code false}, so no Animal ever takes the instant-despawn path
     * inside {@code checkDespawn()} regardless of distance - farm animals simply
     * do not despawn from distance in vanilla. {@code Mob.removeWhenFarAway}
     * itself (used by non-Animal mobs such as Zombie) is unconditionally
     * {@code true}, and Zombie's own hierarchy (Enemy, Monster, PathfinderMob)
     * does not override it again, so Zombie is used here instead specifically
     * because it despawns the ordinary way.
     *
     * <p>Zombie is {@code MobCategory.MONSTER}; placing a real player 500 blocks
     * from a non-persistence-required zombie and calling {@code checkDespawn()}
     * directly removes it unconditionally, well past any category's instant
     * despawn distance - no waiting on {@code noActionTime} or a random chance
     * required. The unfrozen {@code control} zombie proves the scenario really
     * would despawn a normal mob, so the frozen assertion below is not vacuous.
     *
     * <p>Uses the deprecated-for-removal {@code makeMockServerPlayerInLevel()}
     * deliberately, not by oversight: it is the only helper of the three
     * mock-player methods on {@code GameTestHelper} that actually registers the
     * player into the level's player list, which {@code Level.getNearestPlayer}
     * (and so {@code checkDespawn()}) reads directly - confirmed empirically by
     * swapping in the non-deprecated {@code makeMockServerPlayer(GameType)} and
     * watching this test's own sanity check fail, because that player is never
     * found. Suppressed rather than left as a bare warning; worth revisiting if a
     * future Minecraft version actually removes the method.
     */
    @GameTest
    @SuppressWarnings("removal")
    public void aFrozenMemberDoesNotDespawnWhenFarFromEveryPlayer(GameTestHelper helper) {
        Zombie host = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, HOST_POS);
        Zombie member = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, MEMBER_POS);
        Zombie control = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, MEMBER_POS.above());

        Dormancy.freeze(member, host);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.snapTo(0.0, 2.0, 0.0, 0.0f, 0.0f);
        member.snapTo(500.0, 2.0, 0.0, 0.0f, 0.0f);
        control.snapTo(500.0, 2.0, 0.0, 0.0f, 0.0f);

        control.checkDespawn();
        helper.assertTrue(control.isRemoved(),
                "setup sanity check: an unfrozen mob this far from the only player must despawn");

        member.checkDespawn();
        helper.assertFalse(member.isRemoved(),
                "a frozen member must not despawn under conditions that just despawned an identical unfrozen mob");

        helper.succeed();
    }

    /**
     * Mirrors {@link #aFrozenMemberDoesNotTickInAggressiveMode}, but in the other
     * mode: proves {@code freeze.mode: CONSERVATIVE} actually changes behaviour
     * rather than being a decorative config knob. Restores the config in a
     * {@code finally} block - {@code ConfigManager} is process-wide static state
     * shared with every other gametest, and each {@code @GameTest} method here runs
     * to completion in a single synchronous call on the single-threaded server
     * tick loop, so the mutate-then-restore is not observable by any other test.
     */
    @GameTest
    public void conservativeModeLetsTheBaseTickRunUnlikeAggressive(GameTestHelper helper) {
        String originalMode = ConfigManager.get().freeze.mode;
        try {
            ConfigManager.get().freeze.mode = "CONSERVATIVE";

            Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
            Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
            Dormancy.freeze(member, host);

            int before = member.tickCount;
            helper.getLevel().tickNonPassenger(member);
            helper.assertTrue(member.tickCount == before + 1,
                    "CONSERVATIVE mode must let the base tick run even for a frozen member - "
                            + "only serverAiStep is suppressed, not the whole tick");
        } finally {
            ConfigManager.get().freeze.mode = originalMode;
        }

        helper.succeed();
    }

    /**
     * Proves {@link com.mobtimizer.mixin.FrozenAiMixin}'s cancellation logic is
     * correct, not just that {@code serverAiStep} resolves as an injection target -
     * load-time target validation only proves the name exists, and says nothing
     * about whether the {@code if}-condition guarding {@code ci.cancel()} is the
     * right way round. Since this Mixin is CONSERVATIVE mode's entire reason to
     * exist, a logic inversion would ship with every other test in this file still
     * green.
     *
     * <p>{@code serverAiStep()} is {@code protected}, so it cannot be called
     * directly from this package the way {@code checkDespawn()} and
     * {@code tickNonPassenger()} are called elsewhere in this file - reflection is
     * used only for the method call itself. The observable does <em>not</em> need
     * reflection: {@code noActionTime} (incremented unconditionally at the very top
     * of {@code serverAiStep()}, confirmed by disassembly) is {@code protected} on
     * {@code LivingEntity} but has a public getter, {@code getNoActionTime()} - a
     * meaningfully less fragile probe than reading a private field directly, since
     * it is public API that would only change with an explicit, visible signature
     * change rather than incidental field renaming.
     *
     * <p>Reflection does not bypass the Mixin: Fabric/Mixin rewrites
     * {@code Mob.serverAiStep()}'s actual bytecode at class-load time, before any
     * caller - direct, reflective, or otherwise - ever obtains a reference to the
     * method, so an invocation through {@code Method.invoke} runs the exact same
     * transformed code a real tick would.
     */
    @GameTest
    public void frozenAiMixinOnlyCancelsServerAiStepInConservativeMode(GameTestHelper helper) throws ReflectiveOperationException {
        Method serverAiStep = Mob.class.getDeclaredMethod("serverAiStep");
        serverAiStep.setAccessible(true);

        helper.assertTrue(ConfigManager.get().freeze.isAggressive(),
                "setup sanity check: this test assumes the default AGGRESSIVE mode for its first half");

        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        Dormancy.freeze(member, host);

        int beforeAggressive = member.getNoActionTime();
        serverAiStep.invoke(member);
        helper.assertTrue(member.getNoActionTime() == beforeAggressive + 1,
                "FrozenAiMixin must not cancel serverAiStep in AGGRESSIVE mode - EntityTickMixin already skips the "
                        + "whole tick upstream so this path is never reached there in practice, but the Mixin's own "
                        + "mode check must still be correct if anything ever calls serverAiStep directly");

        String originalMode = ConfigManager.get().freeze.mode;
        try {
            ConfigManager.get().freeze.mode = "CONSERVATIVE";

            int whileFrozen = member.getNoActionTime();
            serverAiStep.invoke(member);
            helper.assertTrue(member.getNoActionTime() == whileFrozen,
                    "FrozenAiMixin must actually cancel serverAiStep for a frozen member in CONSERVATIVE mode - "
                            + "noActionTime must not advance");

            Dormancy.thaw(member);
            int afterThaw = member.getNoActionTime();
            serverAiStep.invoke(member);
            helper.assertTrue(member.getNoActionTime() == afterThaw + 1,
                    "once thawed, serverAiStep must run normally again even in CONSERVATIVE mode");
        } finally {
            ConfigManager.get().freeze.mode = originalMode;
        }

        helper.succeed();
    }

    /**
     * Pins the regression this task's fix-up was made for as an executable check,
     * not a comment: {@code Dormancy.freeze} must never write {@code Silent} or
     * {@code NoGravity} to NBT at all, so neither can possibly survive after this
     * mod is removed. An earlier version of {@code Dormancy} called
     * {@code setSilent(true)}/{@code setNoGravity(true)}, and disassembly of
     * {@code Entity.saveWithoutId} showed both are conditionally written whenever
     * true - meaning a member frozen (not thawed) at the moment of the last save
     * kept both flags set in its NBT forever after the mod was removed. Freezing no
     * longer touches either setter at all; {@link com.mobtimizer.mixin.FrozenFlagsMixin}
     * makes the getters read {@code true} instead, computed fresh from the
     * {@code FROZEN} attachment every call.
     *
     * <p>{@code Invisible} is asserted absent too, but for a different, unrelated
     * reason, not because {@code Dormancy} stops setting it - it still calls
     * {@code setInvisible(true)} deliberately (see {@code Dormancy}'s Javadoc).
     * Disassembly of {@code Entity}/{@code LivingEntity}'s full save/load pipeline
     * found no NBT key backing {@code Invisible} at all, under any name - vanilla
     * itself never persists this flag, independent of anything this mod does. An
     * earlier draft of this test wrongly asserted the opposite (that {@code
     * Invisible} <em>would</em> be present, reasoning only from "the setter is still
     * called") and failed against this real tag - corrected here to match what
     * disassembly and this test both actually show, not what seemed to follow from
     * the setter alone.
     */
    @GameTest
    public void freezingNeverWritesSilentOrNoGravityToNbt(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        Dormancy.freeze(member, host);

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, member.level().registryAccess());
        member.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing the frozen member should not report problems");

        helper.assertFalse(tag.contains("Silent"),
                "freezing must never write Silent to NBT - nothing must be left for the mod's removal to leave behind");
        helper.assertFalse(tag.contains("NoGravity"),
                "freezing must never write NoGravity to NBT - nothing must be left for the mod's removal to leave behind");
        helper.assertFalse(tag.contains("Invisible"),
                "Invisible has no NBT backing at all in vanilla, independent of Dormancy - setInvisible(true) is still "
                        + "called, but nothing about that call is ever written to NBT under any key");

        helper.succeed();
    }

    /**
     * The other half of the same fix: a mob that happened to already be silent or
     * gravity-less <em>before</em> being frozen, for some entirely unrelated reason,
     * must read that way again after thawing - its real value was never touched, so
     * there is nothing for {@code thaw} to have clobbered. This would fail against
     * the old setter-based {@code Dormancy}, which unconditionally called
     * {@code setSilent(false)}/{@code setNoGravity(false)} in {@code thaw} and would
     * have silently overwritten a legitimate pre-existing {@code true}.
     */
    @GameTest
    public void thawingDoesNotClobberAPreExistingSilentOrNoGravityValue(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        member.setSilent(true);
        member.setNoGravity(true);

        Dormancy.freeze(member, host);
        helper.assertTrue(member.isSilent(), "a frozen member reads silent regardless of its own prior value");
        helper.assertTrue(member.isNoGravity(), "a frozen member reads no-gravity regardless of its own prior value");

        Dormancy.thaw(member);

        helper.assertTrue(member.isSilent(),
                "thaw must not clobber a Silent value that was already true before freezing - Dormancy never calls setSilent");
        helper.assertTrue(member.isNoGravity(),
                "thaw must not clobber a NoGravity value that was already true before freezing - Dormancy never calls setNoGravity");

        helper.succeed();
    }

    /**
     * Answers the brief's own instruction to check what actually round-trips
     * through save/load rather than assume. {@code Silent}/{@code NoGravity} now
     * read {@code true} again after a real reload for a completely different reason
     * than before: not because either flag round-trips through NBT (they no longer
     * do - see {@link #freezingNeverWritesSilentOrNoGravityToNbt}), but because
     * {@code FrozenFlagsMixin} recomputes both from the {@code FROZEN} attachment,
     * which does persist, on every call.
     *
     * <p>This test calls {@code load(ValueInput)} directly on the same still-live
     * entity object, the same way {@code MobStackAttachmentGameTest} does for the
     * {@code STACK} attachment - deliberately, to isolate "does the NBT round trip
     * restore the right in-memory state" from anything tracking/network-related.
     * That direct call does not go through entity registration, so it does not fire
     * {@code ServerEntityEvents.ENTITY_LOAD} and {@code isInvisible()} correctly
     * still reads {@code false} here - {@link #frozenMemberStaysInvisibleAcrossARealReload}
     * is the one that exercises the real path {@link Dormancy#register} fixes, by
     * actually removing and reconstructing the entity the way a real chunk
     * unload/reload does.
     *
     * <p>The behavioural half of the requirement - a reloaded farm must not wake
     * every member at once - already held before that fix, and still does: every
     * Mixin here gates on {@link Dormancy#isFrozen}, which reads the attachment this
     * test proves does survive, so a reloaded member stays non-ticking, non-pushable
     * and non-despawning regardless of whether anything has re-applied its
     * visibility yet.
     */
    @GameTest
    public void frozenAttachmentSurvivesSaveAndLoad(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        Dormancy.freeze(member, host);

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, member.level().registryAccess());
        member.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing the frozen member should not report problems");

        // Overwrite live state first so the assertions below can only pass because
        // load() actually restored the FROZEN attachment from the tag, exactly as
        // MobStackAttachmentGameTest does for the STACK attachment. Clobbering the
        // attachment alone is enough for isSilent()/isNoGravity() too, now that
        // FrozenFlagsMixin computes both from it rather than from separately-set
        // vanilla state.
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.FALSE);
        member.setInvisible(false);

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, member.level().registryAccess(), tag);
        member.load(input);
        helper.assertTrue(loadProblems.isEmpty(), "loading the frozen member's own just-saved tag should not report problems");

        helper.assertTrue(Dormancy.isFrozen(member),
                "the FROZEN attachment must survive a real save/load round trip - a reloaded farm must not wake every member at once");
        helper.assertTrue(member.isSilent(), "isSilent() must read true again - FrozenFlagsMixin recomputes it from the reloaded FROZEN attachment");
        helper.assertTrue(member.isNoGravity(), "isNoGravity() must read true again - FrozenFlagsMixin recomputes it from the reloaded FROZEN attachment");
        helper.assertFalse(member.isInvisible(),
                "Invisible has no NBT backing at all, so a direct load() call does NOT restore it on its own - "
                        + "see frozenMemberStaysInvisibleAcrossARealReload for the real entity-reload path, "
                        + "where Dormancy.register()'s ENTITY_LOAD listener does restore it");

        helper.succeed();
    }

    @GameTest
    public void followHostSnapsAFrozenMemberBackOntoAMovedHost(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        Dormancy.freeze(member, host);

        host.snapTo(host.getX() + 4, host.getY(), host.getZ() + 4, host.getYRot(), host.getXRot());
        helper.assertTrue(member.position().distanceTo(host.position()) > 0.001,
                "setup sanity check: the member should have been left behind by the host's move");

        Dormancy.followHost(member, host);

        helper.assertTrue(member.position().distanceTo(host.position()) < 0.001,
                "followHost must snap a frozen member back onto its host once it has wandered");

        helper.succeed();
    }

    /**
     * The real reload path {@code frozenAttachmentSurvivesSaveAndLoad} deliberately
     * does not exercise: rather than calling {@code load()} directly on the
     * still-live original entity, this discards it and reconstructs a fresh entity
     * from its saved tag via {@code EntityType.loadEntityRecursive} - the same
     * vanilla utility real chunk deserialization uses - then adds that fresh entity
     * to the level with {@code addFreshEntity}, exactly as a real chunk reload would.
     * A mock player positioned at the member confirms {@code onTrackingStart} (and so
     * {@code ServerEntityEvents.ENTITY_LOAD}) actually fires - without a nearby
     * player there would be nothing to broadcast to and this test would prove
     * nothing.
     *
     * <p>Without {@link Dormancy#register}, this fails: the reconstructed entity's
     * {@code FROZEN} attachment survives (proven separately), but {@code Invisible}
     * does not, so it would read visible immediately after being added back to the
     * level - the reload-visibility bug the coordinator's review identified.
     */
    @GameTest
    @SuppressWarnings("removal")
    public void frozenMemberStaysInvisibleAcrossARealReload(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);
        Dormancy.freeze(member, host);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.snapTo(member.getX(), member.getY(), member.getZ(), 0.0f, 0.0f);

        ProblemReporter.Collector saveProblems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(saveProblems, member.level().registryAccess());
        member.saveWithoutId(output);
        CompoundTag tag = output.buildResult();
        helper.assertTrue(saveProblems.isEmpty(), "serializing the frozen member should not report problems");

        member.discard();

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, member.level().registryAccess(), tag);
        // The explicit-EntityType overload is required here: saveWithoutId (as the
        // name says) never writes the entity-type "id" key that the tag-only
        // overload would need to read back, since a caller who already serialized
        // this way already knows the type by construction.
        Entity reconstructed = EntityType.loadEntityRecursive(
                EntityTypes.COW, input, helper.getLevel(), EntitySpawnReason.LOAD, EntityProcessor.NOP);
        helper.assertTrue(loadProblems.isEmpty(), "reconstructing the frozen member from its own tag should not report problems");
        helper.assertTrue(reconstructed instanceof Mob, "setup sanity check: the reconstructed entity should be a Mob");

        boolean added = helper.getLevel().addFreshEntity(reconstructed);
        helper.assertTrue(added, "setup sanity check: the reconstructed member should be added to the level");
        helper.assertTrue(Dormancy.isFrozen((Mob) reconstructed),
                "setup sanity check: the reconstructed member's FROZEN attachment should have survived the round trip");

        helper.assertTrue(reconstructed.isInvisible(),
                "a frozen member reconstructed from disk and re-added to a level with a nearby player must be "
                        + "invisible immediately - this is the reload-visibility bug ServerEntityEvents.ENTITY_LOAD fixes");

        helper.succeed();
    }

    /**
     * Calls {@code Entity.broadcastToPlayer(ServerPlayer)} directly - the exact
     * method {@link com.mobtimizer.mixin.FrozenTrackingMixin} overrides - on both an
     * ordinary host and a frozen member, matching how every other Mixin in this file
     * is tested (direct production-method calls: {@code isPushable}, {@code
     * checkDespawn}, {@code isSilent}, {@code isNoGravity}).
     *
     * <p>A {@code ChunkMap}-level version of this test was attempted first, asserting
     * against {@code ChunkMap.isTrackedByAnyPlayer(Entity)} per the coordinator's
     * suggestion, deferred a few ticks via {@code helper.runAfterDelay} to let a real
     * {@code ChunkMap.tick()} run (that method is {@code protected} and cannot be
     * invoked directly from a gametest). It failed even its own sanity check - the
     * ordinary host never showed as tracked either - and a diagnostic pinned down
     * why: {@code chunkMap.isChunkTracked(player, ...)} was {@code true} (the mock
     * player's position does resolve to the right chunk) but
     * {@code isTrackedByAnyPlayer} was {@code false} for both entities, meaning no
     * {@code ChunkMap$TrackedEntity} was ever created for either one in the first
     * place. {@code onTrackingStart} - and so {@code ChunkMap.addEntity} - fires from
     * a chunk-tracking ticket that a real client establishes by reporting its view
     * distance during login; {@code GameTestHelper.makeMockServerPlayerInLevel()}'s
     * fake connection never goes through that exchange, so no such ticket exists to
     * track against, regardless of how it's positioned. That same diagnostic's direct
     * calls to {@code broadcastToPlayer} - {@code host}: {@code true}, frozen
     * {@code member}: {@code false} - proved the Mixin's own logic is exactly
     * correct; the harness's mock player is simply not capable of exercising the
     * surrounding {@code ChunkMap} machinery end-to-end. Direct calls are therefore
     * the correct level to test at here, not a fallback.
     */
    @GameTest
    @SuppressWarnings("removal")
    public void aFrozenMemberIsNotBroadcastToAnyPlayerInAggressiveMode(GameTestHelper helper) {
        helper.assertTrue(ConfigManager.get().freeze.isAggressive(),
                "setup sanity check: this test assumes the default AGGRESSIVE mode");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, MEMBER_POS);

        helper.assertTrue(host.broadcastToPlayer(player), "setup sanity check: an ordinary host must broadcast normally");
        helper.assertTrue(member.broadcastToPlayer(player), "setup sanity check: an ordinary member must broadcast normally before freezing");

        Dormancy.freeze(member, host);

        helper.assertTrue(host.broadcastToPlayer(player), "the host itself is never frozen and must keep broadcasting normally");
        helper.assertFalse(member.broadcastToPlayer(player),
                "a frozen member must not be broadcast to any player in AGGRESSIVE mode");

        Dormancy.thaw(member);
        helper.assertTrue(member.broadcastToPlayer(player), "a thawed member must broadcast normally again");

        helper.succeed();
    }

    /**
     * Turns {@link com.mobtimizer.mixin.EntityCollisionMixin}'s documented dependency
     * on {@link StackEligibility} into something that fails loudly instead of a
     * comment nobody re-reads. {@code AbstractHorse.isPushable()} is a complete
     * override with no {@code super} call (confirmed by disassembly:
     * {@code return !isVehicle();}, entirely independent of {@code LivingEntity}'s
     * copy the Mixin overrides), so freezing a horse directly - bypassing
     * eligibility the same way this file's other tests bypass it to isolate a
     * single Mixin - leaves it fully pushable. That gap is only closed today
     * because {@code StackEligibility.canStack} excludes every {@code OwnableEntity},
     * which covers horses (and parrots, sharing the same shape) completely, for an
     * unrelated reason.
     *
     * <p>Both assertions matter together: the first pins today's actual safety net
     * (eligibility), so this test fails immediately - pointing straight at this
     * comment - if someone ever relaxes the {@code OwnableEntity} exclusion; the
     * second demonstrates exactly what such a change would expose, by proving the
     * collision Mixin itself does nothing for this type regardless of eligibility.
     */
    @GameTest
    public void horsesBypassCollisionSuppressionSoEligibilityMustKeepExcludingThem(GameTestHelper helper) {
        Horse horse = GameTestMobs.spawnPlain(helper, EntityTypes.HORSE, MEMBER_POS);
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, HOST_POS);

        helper.assertFalse(StackEligibility.canStack(horse),
                "a horse must stay ineligible to stack - if this ever changes, EntityCollisionMixin will NOT "
                        + "protect it, since AbstractHorse.isPushable() bypasses it completely (see below)");

        Dormancy.freeze(horse, host);
        helper.assertTrue(horse.isPushable(),
                "AbstractHorse.isPushable() completely overrides LivingEntity's copy with no super call, so a "
                        + "frozen horse stays pushable regardless of freezing - this type is only safe today because "
                        + "StackEligibility keeps it from ever being frozen in the first place");

        helper.succeed();
    }
}
