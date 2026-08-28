package com.mobtimizer.gametest;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.stack.DormantStore;
import com.mobtimizer.stack.MobStack;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;

import java.util.List;
import java.util.UUID;

/**
 * Exercises {@link DormantStore} against real spawned mobs.
 *
 * <p>Every method here mutates or reads real entity state - the {@code STACK}
 * attachment, {@code Level#getEntity(UUID)} - none of which exists outside a live
 * level, so this cannot be a plain unit test. There is deliberately no
 * {@code DormantStoreTest} unit test alongside this one: with no mocking library in
 * this project and no way to construct a real {@code Mob} outside a running level, a
 * "unit" test here could only assert against fakes and would prove nothing about the
 * real entity-manager behaviour this class exists to get right.
 */
public final class DormantStoreGameTest {
    private static final BlockPos POS = new BlockPos(1, 2, 1);

    @GameTest
    public void addThenTakeOneRoundTrips(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());

        helper.assertTrue(DormantStore.INSTANCE.add(host, member), "add should report success");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 1, "member should be stored");
        helper.assertTrue(host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).memberCount() == 2,
                "host plus one member is a stack of 2");

        Mob taken = DormantStore.INSTANCE.takeOne(host);
        helper.assertTrue(taken == member, "takeOne should hand back the same member entity that was added");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0, "store should now be empty");
        helper.assertTrue(DormantStore.INSTANCE.takeOne(host) == null, "empty store returns null");

        helper.succeed();
    }

    @GameTest
    public void freshHostHasAnEmptyStore(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);

        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0,
                "a host nothing has ever been added to must read as having no members");
        helper.assertTrue(DormantStore.INSTANCE.takeOne(host) == null, "takeOne on an empty store returns null");
        helper.assertTrue(DormantStore.INSTANCE.takeAll(host).isEmpty(),
                "takeAll on an empty store returns an empty list, not null");

        helper.succeed();
    }

    /**
     * Design point: {@code takeOne} pops {@code MobStack.members().getLast()} off a
     * first-insertion-order list, i.e. members come back LIFO, not FIFO. This pins that
     * down directly so a future refactor toward {@code getFirst()} fails loudly here
     * instead of only showing up as a subtle reordering nobody notices.
     */
    @GameTest
    public void takeOneReturnsTheMostRecentlyAddedMemberFirst(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow first = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        Cow second = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above().above());

        DormantStore.INSTANCE.add(host, first);
        DormantStore.INSTANCE.add(host, second);

        Mob taken = DormantStore.INSTANCE.takeOne(host);
        helper.assertTrue(taken == second, "the most recently added member must come back first");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 1, "the earlier member should remain in the store");

        helper.succeed();
    }

    @GameTest
    public void takeAllReturnsEveryMemberLifoAndEmptiesTheStore(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow first = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        Cow second = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above().above());

        DormantStore.INSTANCE.add(host, first);
        DormantStore.INSTANCE.add(host, second);

        List<Mob> taken = DormantStore.INSTANCE.takeAll(host);

        helper.assertTrue(taken.size() == 2, "both members should be returned");
        helper.assertTrue(taken.get(0) == second, "takeAll drains LIFO: most recently added comes back first");
        helper.assertTrue(taken.get(1) == first, "then the earlier-added member");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0, "the store must be empty after takeAll");
        helper.assertTrue(host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).memberCount() == 1,
                "only the host remains once every member has been taken");

        helper.succeed();
    }

    /**
     * Design point: a member's entity can vanish out from under the store - chunk
     * trimmed, {@code /kill}, another mod - without {@code DormantStore} ever being
     * told. A synthetic UUID that was never spawned reproduces exactly the precondition
     * that matters here (an id present in the attachment with nothing in the world
     * behind it) fully deterministically, sidestepping any question of how or when a
     * real removal becomes visible to {@code Level#getEntity} - {@code resolve} only
     * ever consults that one lookup, so it cannot tell "never existed" apart from
     * "existed and is now gone", and neither should this test need to.
     *
     * <p>{@code takeOne} must report this as "no member" (null) while also dropping the
     * dead id from the attachment - not leave it stuck at the tail of the list forever,
     * which would make every real member still behind it in the list permanently
     * unreachable too, even though they still exist.
     */
    @GameTest
    public void takeOneDropsAStaleIdAndReturnsNullWhenTheMemberEntityIsGone(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow real = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        UUID vanished = UUID.randomUUID();

        DormantStore.INSTANCE.add(host, real);
        host.setAttached(MobtimizerAttachments.STACK,
                host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).withMember(vanished));
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 2, "setup sanity check: two ids on record");

        Mob takenForVanished = DormantStore.INSTANCE.takeOne(host);
        helper.assertTrue(takenForVanished == null, "a member id with no backing entity must resolve to null, not throw");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 1,
                "the stale id must be dropped from the attachment even though nothing was returned for it");

        Mob takenForReal = DormantStore.INSTANCE.takeOne(host);
        helper.assertTrue(takenForReal == real,
                "the still-real member that was behind the stale one in the list must still be reachable afterwards");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0, "store should now be empty");

        helper.succeed();
    }

    /**
     * Same precondition as {@link #takeOneDropsAStaleIdAndReturnsNullWhenTheMemberEntityIsGone},
     * but through {@code takeAll}: this is the test that catches {@code takeAll}
     * looping on {@code takeOne(host) != null} instead of on {@link DormantStore#size}.
     * The vanished id sits at the tail (added last), so it is what the first internal
     * {@code takeOne} call inside {@code takeAll} hits - a loop that stops the moment
     * that call returns null would return an empty list here and leave the real member
     * stranded in the store, still attached to {@code host} and never thawed. See
     * {@link DormantStore#takeAll} for why the fix is a loop on {@code size}, not on
     * {@code takeOne}'s return value.
     */
    @GameTest
    public void takeAllSkipsAVanishedMemberAndStillReturnsTheRest(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow real = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        UUID vanished = UUID.randomUUID();

        DormantStore.INSTANCE.add(host, real);
        host.setAttached(MobtimizerAttachments.STACK,
                host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).withMember(vanished));

        List<Mob> taken = DormantStore.INSTANCE.takeAll(host);

        helper.assertTrue(taken.size() == 1, "the vanished id must not appear as a null entry or otherwise inflate the result");
        helper.assertTrue(taken.get(0) == real, "the one real member must still come back");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0,
                "takeAll must fully drain the store even with a stale id mixed in, not stop early on its null");

        helper.succeed();
    }

    /**
     * Guards a real misuse path from {@link com.mobtimizer.stack.MemberStore#add}'s
     * documented preconditions: {@code add(host, host)} would otherwise insert the
     * host's own UUID into its own member list, and a later {@code takeOne} would
     * resolve that id straight back to {@code host} and call
     * {@code Dormancy.thaw(host)} on it. {@code DormantStore} refuses this rather than
     * trusting the caller; this pins down that the refusal leaves the store exactly as
     * it was, not partially applied.
     */
    @GameTest
    public void addRefusesToAddAHostAsItsOwnMemberAndLeavesTheStoreUnchanged(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);

        helper.assertFalse(DormantStore.INSTANCE.add(host, host), "a refused self-add must report false");

        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0,
                "a refused self-add must not change the store's size");
        helper.assertTrue(host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).memberCount() == 1,
                "a refused self-add must leave the host as a stack of only itself");

        helper.succeed();
    }

    /**
     * Guards the other cheaply-detectable misuse path from
     * {@link com.mobtimizer.stack.MemberStore#add}'s preconditions: adding a mob that
     * is itself already a stack host would freeze it as someone else's member and
     * orphan its own members permanently, since nothing ever revisits a frozen
     * member's own attachment afterward. Confirms the refusal touches neither the
     * target host's store nor the would-be member's own, pre-existing one.
     */
    @GameTest
    public void addRefusesAMemberThatIsItselfAStackHostAndLeavesBothStoresUnchanged(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow busyHost = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        Cow itsOwnMember = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above().above());
        DormantStore.INSTANCE.add(busyHost, itsOwnMember);
        helper.assertTrue(DormantStore.INSTANCE.size(busyHost) == 1, "setup sanity check: busyHost already has a member");

        helper.assertFalse(DormantStore.INSTANCE.add(host, busyHost),
                "refusing to add a mob that is itself a stack host must report false");

        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0,
                "refusing to add a mob that is itself a stack host must not change the target host's size");
        helper.assertTrue(DormantStore.INSTANCE.size(busyHost) == 1,
                "busyHost's own member list must be untouched by the refused add");

        helper.succeed();
    }

    /**
     * Task 9's fix round added stack-to-stack merging to {@link
     * com.mobtimizer.stack.StackManager#merge}, which now drains an absorbed stack's
     * members onto the new host via {@link #transferMembers} <em>before</em> calling
     * {@link #add} - specifically so that add's guard above is never asked to look the
     * other way. This confirms that guard is not just theoretically still present but
     * still actually load-bearing: a caller that skips the transfer step and calls
     * {@code add} directly with a still-populated host as the member - exactly what
     * {@code StackManager.merge} no longer does, but nothing about {@code add} itself
     * prevents a different caller from trying - is still refused. Builds {@code
     * busyHost}'s stack through the real {@code StackManager.merge} path (not a direct
     * {@code add} call), tying this directly to the new capability rather than testing
     * {@code add} in isolation from it.
     */
    @GameTest
    public void addStillRefusesAStackHostAsAMemberEvenThoughMergeNowSupportsStackToStack(GameTestHelper helper) {
        Cow busyHost = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow itsOwnMember = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        StackManager.merge(busyHost, itsOwnMember);
        helper.assertTrue(DormantStore.INSTANCE.size(busyHost) == 1,
                "setup sanity check: busyHost has a member via the real merge path");

        Cow newHost = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above().above());
        helper.assertFalse(DormantStore.INSTANCE.add(newHost, busyHost),
                "add() itself must still refuse a still-populated stack host as a member, even now that merge() "
                        + "supports absorbing one - by calling transferMembers first, not by relaxing this guard");
        helper.assertTrue(DormantStore.INSTANCE.size(newHost) == 0,
                "the refused direct add must not change newHost's size");
        helper.assertTrue(DormantStore.INSTANCE.size(busyHost) == 1,
                "busyHost's own member list must be untouched by the refused direct add");

        helper.succeed();
    }

    /**
     * Mirrors {@link #takeOneDropsAStaleIdAndReturnsNullWhenTheMemberEntityIsGone} for
     * the new {@link #transferMembers}: a member id that no longer resolves to a live
     * entity (chunk trimmed, {@code /kill}, another mod) must not corrupt the transfer
     * or throw. It is dropped - the same accepted, self-healing behaviour {@code
     * takeOne} already has - while every other, still-resolvable member is still moved
     * onto the new host correctly. Answers, with a real test rather than analysis
     * alone, one of the ordering-under-failure questions raised in review: can a member
     * end up owned by neither host, or by both, if something goes wrong mid-transfer?
     * Here: owned by neither (the vanished id is gone from both attachments
     * afterwards) - never both, since {@link #transferMembers} removes each id from
     * {@code from} before it is ever added to {@code to}, one id at a time, rather than
     * reading {@code from}'s whole list once and clearing it in bulk at the end.
     */
    @GameTest
    public void transferMembersDropsAVanishedMemberAndStillMovesTheRest(GameTestHelper helper) {
        Cow from = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow real = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        UUID vanished = UUID.randomUUID();

        DormantStore.INSTANCE.add(from, real);
        from.setAttached(MobtimizerAttachments.STACK,
                from.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).withMember(vanished));
        helper.assertTrue(DormantStore.INSTANCE.size(from) == 2, "setup sanity check: two ids on record before transfer");

        Cow to = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above().above());
        DormantStore.INSTANCE.transferMembers(from, to);

        helper.assertTrue(DormantStore.INSTANCE.size(from) == 0,
                "from must end up fully drained - the vanished id must not be left behind either");
        helper.assertTrue(DormantStore.INSTANCE.size(to) == 1,
                "the vanished id must not inflate to's count - only the one real member should have moved");
        helper.assertTrue(to.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).members().contains(real.getUUID()),
                "the real member must actually be the one that moved to `to`");

        helper.succeed();
    }
}
