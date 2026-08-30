package com.mobtimizer.gametest;

import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Exercises {@link com.mobtimizer.freeze.HostPromotion} - the fix for a real play-test
 * bug ("killing one baby killed all the babies in the stack": the members were not
 * dead, they were leaked - invisible, inert, and permanently unreachable, since Phase
 * 1 had no death handling at all). Uses {@link GameTestMobs#spawnRealistic} throughout,
 * not {@link GameTestMobs#spawnPlain}: the bug that preceded this one (naturally-spawned
 * adults never merging) existed precisely because earlier tests in this suite used
 * fixtures no real world actually produces, and every test here spans at least three
 * mobs, per the same reasoning.
 *
 * <p>Every test kills mobs with {@code Entity.kill(ServerLevel)} - confirmed by
 * disassembly to be the exact method the vanilla {@code /kill} command uses, routing
 * through {@code LivingEntity}'s override into a real {@code hurtServer} call with
 * lethal damage, not a shortcut - so {@code ServerLivingEntityEvents.AFTER_DEATH}
 * fires for real, the same way it would from lava, fall damage, an arrow, or a player.
 *
 * <p><b>{@code GameTestHelper.getEntities(EntityType)} already excludes a dying
 * corpse, by design - not merely as an artefact of {@code LivingEntity.tickDeath()}'s
 * ~20-tick removal delay.</b> Found the hard way, not assumed: an earlier version of
 * this suite additionally filtered by {@code isAlive() && !isDeadOrDying()}, which
 * failed its own setup checks (killing a lone, unstacked cow reported zero entities,
 * not one) with no promotion logic involved at all. Disassembling {@code
 * GameTestHelper}'s bootstrap methods traced its {@code getEntities(EntityType)} and
 * {@code getEntities(EntityType, BlockPos, double)} overloads to a shared predicate
 * that is a direct {@code Entity::isAlive} method reference - and {@code
 * LivingEntity.isAlive()} (confirmed separately by disassembly) overrides the base
 * {@code Entity} implementation to require {@code getHealth() > 0}, not just
 * {@code !isRemoved()}. So a cow with 0 health is excluded from this query the
 * instant it dies, well before removal - and {@code isAlive()}/{@code isDeadOrDying()}
 * are therefore already mutually exclusive for a {@code Mob}; the extra
 * {@code !isDeadOrDying()} added nothing and was removed.
 *
 * <p>This matters for what "no ghosts" actually has to check here. Since the harness
 * already hides anything not {@code isAlive()}, every cow {@code getEntities} returns
 * post-death is definitionally alive - including a perfectly ordinary, correctly
 * re-parented frozen member of the new host, which is invisible <em>and alive by
 * design</em>, not a ghost. A ghost is specifically an alive, frozen mob that no
 * host's own bookkeeping accounts for. The test that matters most below does not
 * merely count survivors or scan for "alive and invisible" (which a legitimate frozen
 * member would trip): it cross-checks the promoted host's own reported member count
 * against the world's real, independently-observed alive-cow count, which is exactly
 * the comparison the original bug would fail - the attachment and the world
 * disagreeing is the entire bug.
 *
 * <p>Not separately tested here: that the dying host's own loot and XP are untouched.
 * {@code HostPromotion}'s Javadoc already establishes this structurally, by
 * disassembly - the {@code AFTER_DEATH} injection point in {@code die()} is strictly
 * after the {@code dropAllDeathLoot}/{@code dropExperience} calls in the bytecode, so
 * this handler running at all cannot have affected whether or what they did. A
 * runtime test would have to assert on cow loot-table RNG output (0-2 leather is
 * possible per roll) to confirm what disassembly already guarantees deterministically;
 * that trade would add flakiness risk for strictly weaker evidence, not stronger.
 */
public final class HostPromotionGameTest {
    private static final BlockPos HOST_POS = new BlockPos(1, 2, 1);
    private static final BlockPos MEMBER_POS_1 = new BlockPos(3, 2, 1);
    private static final BlockPos MEMBER_POS_2 = new BlockPos(1, 2, 3);

    /** See the class Javadoc: already excludes a dying/dead cow, not just a removed one. */
    private static List<Cow> aliveCows(GameTestHelper helper) {
        return helper.getEntities(EntityTypes.COW);
    }

    /**
     * The test that matters most, per instruction: not merely that the count comes
     * out right, but that the promoted host's own bookkeeping accounts for every
     * other real, alive cow - the exact cross-check the original bug fails, since the
     * attachment and the world had silently disagreed.
     */
    @GameTest
    public void killingAStackHostPromotesAMemberAndLeavesNoGhosts(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, HOST_POS);
        Cow memberA = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_1);
        Cow memberB = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_2);
        helper.assertTrue(StackManager.merge(host, memberA), "setup sanity check: memberA should merge");
        helper.assertTrue(StackManager.merge(host, memberB), "setup sanity check: memberB should merge");
        helper.assertTrue(StackManager.countOf(host) == 3, "setup sanity check: stack of 3 before the host dies");

        host.kill(helper.getLevel());

        List<Cow> alive = aliveCows(helper);
        helper.assertTrue(alive.size() == 2,
                "killing one cow must leave exactly count-1 real cows alive, not fewer (a lost member) or the same (nothing died)");

        List<Cow> unfrozen = alive.stream().filter(cow -> !Dormancy.isFrozen(cow)).toList();
        helper.assertTrue(unfrozen.size() == 1, "exactly one promoted, unfrozen host must exist among the survivors");
        Cow promoted = unfrozen.get(0);

        // The ghost check: the promoted host's own count must exactly match the
        // world's real alive-cow count. If a member were left frozen but unreachable
        // from any host - alive, invisible, and uncounted, exactly the original bug -
        // the world would report more alive cows than the host's attachment does.
        helper.assertTrue(StackManager.countOf(promoted) == alive.size(),
                "the promoted host's own reported count must match the world's real alive-cow count exactly - "
                        + "a mismatch here is precisely the ghost this fix prevents");

        helper.succeed();
    }

    @GameTest
    public void thePromotedMemberIsVisibleTickingAndAtTheDeadHostsPosition(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, HOST_POS);
        Cow member = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_1);
        helper.assertTrue(StackManager.merge(host, member), "setup sanity check: member should merge");

        Vec3 deathPosition = host.position();
        host.kill(helper.getLevel());

        List<Cow> alive = aliveCows(helper);
        helper.assertTrue(alive.size() == 1, "setup sanity check: exactly one promoted survivor");
        Cow promoted = alive.get(0);

        helper.assertFalse(promoted.isInvisible(), "the promoted member must be visible");
        helper.assertFalse(Dormancy.isFrozen(promoted), "the promoted member must no longer read as frozen");
        helper.assertTrue(promoted.isPushable(), "the promoted member must be collidable again");

        int beforeTick = promoted.tickCount;
        helper.getLevel().tickNonPassenger(promoted);
        helper.assertTrue(promoted.tickCount == beforeTick + 1, "the promoted member must actually tick again, not just report unfrozen");

        helper.assertTrue(promoted.position().distanceTo(deathPosition) < 0.001,
                "the promoted member must end up exactly at the dead host's position - a stack that teleports on death would be wrong");

        helper.succeed();
    }

    @GameTest
    public void killingAnUnstackedMobIsUnaffected(GameTestHelper helper) {
        Cow lone = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, HOST_POS);
        helper.assertTrue(StackManager.countOf(lone) == 1, "setup sanity check: lone cow is unstacked");

        lone.kill(helper.getLevel());

        helper.assertTrue(lone.isDeadOrDying(), "an unstacked mob must still die normally when killed");
        helper.assertTrue(aliveCows(helper).isEmpty(),
                "with the only cow now dying - and so excluded from getEntities' isAlive() filter - "
                        + "nothing else should have appeared; killing an unstacked mob must not create or promote anything");
        helper.assertTrue(helper.getLevel().getEntity(lone.getUUID()) == lone,
                "the original cow must still be the same, unreplaced entity, merely dying normally");

        helper.succeed();
    }

    @GameTest
    public void aStackWhoseMembersAreAllUnresolvableDiesCleanly(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, HOST_POS);
        Cow memberA = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_1);
        Cow memberB = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_2);
        helper.assertTrue(StackManager.merge(host, memberA), "setup sanity check: memberA should merge");
        helper.assertTrue(StackManager.merge(host, memberB), "setup sanity check: memberB should merge");

        // Simulate both members being genuinely gone - chunk trimmed, /kill,
        // another mod - the same precondition DormantStoreGameTest already
        // exercises at the store layer.
        memberA.discard();
        memberB.discard();

        host.kill(helper.getLevel()); // must not throw, loop forever, or resurrect anything

        helper.assertTrue(aliveCows(helper).isEmpty(),
                "with every member already gone, nothing should be promoted - the stack is genuinely over");

        helper.succeed();
    }

    /**
     * "Do not lose the rest of the stack because one id went stale." {@code takeOne}
     * pops {@code members().getLast()} first, so merging {@code stale} <em>after</em>
     * {@code resolvable} puts it first in line for the promotion loop to try - and
     * discarding it out from under the attachment right before the host dies is what
     * actually exercises the loop moving on, rather than merely having a second
     * member present that is never reached.
     */
    @GameTest
    public void aStaleMemberIdDoesNotLoseTheRestOfTheStack(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, HOST_POS);
        Cow resolvable = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_1);
        Cow stale = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_2);
        helper.assertTrue(StackManager.merge(host, resolvable), "setup sanity check: resolvable should merge first");
        helper.assertTrue(StackManager.merge(host, stale), "setup sanity check: stale should merge second, so it is tried first");

        stale.discard();

        host.kill(helper.getLevel());

        List<Cow> alive = aliveCows(helper);
        helper.assertTrue(alive.size() == 1, "the still-real member must survive promotion despite the stale id ahead of it");
        helper.assertTrue(alive.get(0) == resolvable, "the specific survivor must be the member that was never discarded");
        helper.assertFalse(Dormancy.isFrozen(resolvable),
                "the survivor must actually be promoted (thawed), not merely still alive as an untouched frozen ghost - "
                        + "a fixture that stayed frozen would still be 'alive' and pass the two checks above for the wrong reason");

        helper.succeed();
    }

    /**
     * The "whole stack dies at once" case from a single explosion or {@code /kill @e}:
     * a member can die in the same event batch as its host, since damage from an
     * external source (unlike a frozen member's own suppressed tick) is applied
     * directly, not through the victim's tick. Such a member is not yet
     * {@code isRemoved()} (removal is deferred ~20 ticks by its own death animation)
     * but is {@code isDeadOrDying()} - killing it first and then immediately killing
     * the host reproduces that exact window without needing an actual explosion.
     *
     * <p>Note this member is <em>not</em> {@code isAlive()} at this point (health is
     * 0), so it does not appear in {@link #aliveCows}'s result either - the setup
     * check below deliberately does not use {@code isAlive()} for that reason (see
     * the class Javadoc), checking {@code isRemoved()}/{@code isDeadOrDying()}
     * directly on the held reference instead.
     */
    @GameTest
    public void aMemberAlreadyDyingWhenTheHostDiesIsNotPromoted(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, HOST_POS);
        Cow healthy = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_1);
        Cow dying = GameTestMobs.spawnRealistic(helper, EntityTypes.COW, MEMBER_POS_2);
        helper.assertTrue(StackManager.merge(host, healthy), "setup sanity check: healthy should merge first");
        helper.assertTrue(StackManager.merge(host, dying), "setup sanity check: dying-to-be should merge second, so it is tried first");

        dying.kill(helper.getLevel());
        helper.assertTrue(!dying.isRemoved() && dying.isDeadOrDying(),
                "setup sanity check: the member must be in the dying-but-not-yet-removed window this test targets");

        host.kill(helper.getLevel());

        List<Cow> alive = aliveCows(helper);
        helper.assertTrue(alive.size() == 1, "exactly the healthy member must survive as the new host");
        helper.assertTrue(alive.get(0) == healthy, "the already-dying member must never be the one promoted, even though it was tried first");
        helper.assertFalse(Dormancy.isFrozen(healthy),
                "the survivor must actually be promoted (thawed), not merely still alive and frozen for its own unrelated reasons");

        helper.succeed();
    }
}
