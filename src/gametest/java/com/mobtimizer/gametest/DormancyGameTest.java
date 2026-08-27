package com.mobtimizer.gametest;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

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
        helper.assertTrue(member.position().distanceTo(host.position()) < 0.001,
                "frozen members are moved onto the host");

        Dormancy.thaw(member);

        helper.assertFalse(Dormancy.isFrozen(member), "member should report thawed");
        helper.assertFalse(member.isInvisible(), "thawed members are visible again");
        helper.assertFalse(member.isSilent(), "thawed members make noise again");

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
     * Answers the brief's own instruction to check what actually round-trips
     * through save/load rather than assume. Disassembly of
     * {@code Entity.saveWithoutId}/{@code load} shows {@code Silent} and
     * {@code NoGravity} are conditionally written to and read from NBT whenever
     * true, exactly like a persistent attachment - so they survive this round trip
     * for the same reason the attachment does. {@code Invisible} has no NBT
     * backing at all and does not survive even though the mod stays installed
     * throughout this test; seeing that gap confirmed here, in a real save/load
     * round trip, is more convincing than trusting the disassembly alone.
     *
     * <p>The behavioural half of the requirement - a reloaded farm must not wake
     * every member at once - still holds even though {@code Invisible} does not
     * round-trip: every Mixin here gates on {@link Dormancy#isFrozen}, which reads
     * the attachment this test proves does survive, so a reloaded member stays
     * non-ticking, non-pushable and non-despawning regardless. Only its visibility
     * regresses.
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
        // load() actually restored it from the tag, exactly as
        // MobStackAttachmentGameTest does for the STACK attachment.
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.FALSE);
        member.setInvisible(false);
        member.setSilent(false);
        member.setNoGravity(false);

        ProblemReporter.Collector loadProblems = new ProblemReporter.Collector();
        ValueInput input = TagValueInput.create(loadProblems, member.level().registryAccess(), tag);
        member.load(input);
        helper.assertTrue(loadProblems.isEmpty(), "loading the frozen member's own just-saved tag should not report problems");

        helper.assertTrue(Dormancy.isFrozen(member),
                "the FROZEN attachment must survive a real save/load round trip - a reloaded farm must not wake every member at once");
        helper.assertTrue(member.isSilent(), "Silent is written to NBT whenever true, so it round-trips");
        helper.assertTrue(member.isNoGravity(), "NoGravity is written to NBT whenever true, so it round-trips");
        helper.assertFalse(member.isInvisible(),
                "Invisible has no NBT backing at all, so it does NOT round-trip - a reloaded frozen member is "
                        + "briefly visible again until something re-applies Dormancy's visual state");

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
}
