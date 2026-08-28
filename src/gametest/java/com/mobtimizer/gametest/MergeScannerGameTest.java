package com.mobtimizer.gametest;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.merge.MergeScanner;
import com.mobtimizer.stack.StackManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;

import java.util.List;

/**
 * Exercises {@link MergeScanner} against real spawned mobs and a real {@code
 * ServerLevel}.
 *
 * <p><b>Every test below that expects a scan to actually run forces {@code
 * merge.scanIntervalTicks} down to {@code 1} first.</b> This is not decoration: once
 * {@code Mobtimizer.onInitialize()} registers {@link MergeScanner#tick} against {@code
 * ServerTickEvents.END_LEVEL_TICK}, that method fires automatically, every real server
 * tick, for as long as this test server runs - including throughout this whole gametest
 * batch, in the background, independent of whichever test method happens to be running.
 * {@link MergeScanner}'s per-level tick counter is therefore shared, mutable, and
 * already accumulating from calls this test never made, well before any single
 * {@code @GameTest} method here reaches its own explicit {@link MergeScanner#tick}
 * call. A test that relied on the default {@code scanIntervalTicks = 20} and a single
 * explicit call would be gambling on however many ticks that ambient counter happened
 * to be into its own cycle at that exact moment - genuinely flaky, not merely
 * theoretically so. Forcing the interval to {@code 1} makes the very next call
 * unconditionally due regardless of that history: incrementing any starting count by at
 * least {@code 1} already satisfies {@code >= 1}. Every mutation is paired with a
 * {@code finally} restore, matching this project's existing convention for temporarily
 * patching {@link ConfigManager}'s shared config object (see {@code
 * DormancyGameTest#conservativeModeLetsTheBaseTickRunUnlikeAggressive}).
 *
 * <p><b>Assertions below check {@link #maxCountAmong}, not one specific mob's own
 * count.</b> An early draft of {@code atThresholdCowsMerge} asserted directly on the
 * first-spawned cow, copying the brief's own sample code - and failed under real TDD
 * evidence: the crowd legitimately merged into a single stack of five, but a
 * <em>different</em> cow among the five became the host, so the first-spawned one read
 * back as an ordinary, still-unmerged member (a frozen member's own {@code STACK}
 * attachment is never touched - only the host's is - so {@code StackManager.countOf} on
 * a member reads {@code 1}, same as an untouched mob). Nothing promises {@link
 * MergeScanner}'s outer candidate loop visits same-kind mobs in spawn order, and it
 * should not have to for correctness - which mob a crowd elects as host is an
 * implementation detail these tests must not overspecify. Checking the maximum count
 * across every mob in the crowd sidesteps that entirely: exactly one of them reads the
 * full merged count, however the scanner happened to pick its host.
 */
public class MergeScannerGameTest {
    @GameTest
    public void belowThresholdNothingMerges(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            // Default crowdThreshold is 4; three cows must stay individual.
            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c) == 1, "three cows are below the crowd threshold");
            // "Below threshold" must mean completely vanilla, not merely uncounted -
            // the crowd gate is the feature (see the task brief), so this checks the
            // stronger claim directly rather than inferring it from count alone.
            helper.assertFalse(Dormancy.isFrozen(a), "a mob below the crowd threshold must stay completely vanilla");
            helper.assertFalse(Dormancy.isFrozen(b), "a mob below the crowd threshold must stay completely vanilla");
            helper.assertFalse(Dormancy.isFrozen(c), "a mob below the crowd threshold must stay completely vanilla");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * The brief's own sample test, kept at five cows against the default threshold of
     * four - genuinely above threshold, not exactly at it; {@link
     * #exactlyAtThresholdMerges} pins the precise boundary separately. See this class's
     * Javadoc for why the assertion checks {@link #maxCountAmong} rather than one named
     * cow.
     */
    @GameTest
    public void atThresholdCowsMerge(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow d = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
            Cow e = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 2));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c, d, e) > 1, "five nearby cows should form a stack");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * The other half of the boundary the task asked to pin down exactly: {@code
     * atThresholdCowsMerge} above is one mob past the threshold, this one is exactly at
     * it - host plus three neighbours, {@code crowdThreshold}'s default. Also asserts
     * full absorption (every one of the four ends up counted, not just "more than one"),
     * per Task 8's postmortem on why a three-or-more-mob test matters: a two-mob check
     * cannot tell "merged everyone" apart from "merged only the second one," and this
     * scanner's own crowd loop merges multiple candidates per host in one pass, which a
     * weaker {@code > 1} assertion would not catch if it silently stopped after just
     * one.
     */
    @GameTest
    public void exactlyAtThresholdMerges(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow d = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c, d) == 4,
                    "exactly at the crowd threshold (host + 3 neighbours), all four must merge into one stack");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * Two independent crowds of different kinds, each individually at the crowd
     * threshold, co-located closely enough that a scanner blind to entity kind would
     * treat them as one crowd of eight. Each kind must form its own separate stack of
     * exactly four - proving cows and pigs never fold into each other regardless of how
     * close together they stand.
     */
    @GameTest
    public void differentKindsInSameCrowdDoNotMergeIntoEachOther(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow cowA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow cowB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow cowC = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow cowD = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
            var pigA = GameTestMobs.spawnPlain(helper, EntityTypes.PIG, new BlockPos(2, 2, 2));
            var pigB = GameTestMobs.spawnPlain(helper, EntityTypes.PIG, new BlockPos(2, 2, 3));
            var pigC = GameTestMobs.spawnPlain(helper, EntityTypes.PIG, new BlockPos(3, 2, 1));
            var pigD = GameTestMobs.spawnPlain(helper, EntityTypes.PIG, new BlockPos(3, 2, 2));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(cowA, cowB, cowC, cowD) == 4,
                    "the four cows must merge into their own stack of four");
            helper.assertTrue(maxCountAmong(pigA, pigB, pigC, pigD) == 4,
                    "the four pigs must merge into their own stack of four, not the cows' stack");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * Six cows in one crowd - well past the threshold of four - with {@code
     * maxMergesPerScan} temporarily lowered to two. Without capping the merge loop
     * inside a single crowd (not just across crowds, which the brief's own code already
     * got right by counting {@code StackManager.merge}'s successes rather than
     * attempts), one oversized crowd would absorb every eligible neighbour in a single
     * call regardless of the budget the outer loop is tracking - exactly the scenario
     * {@code maxMergesPerScan} exists to bound, since a farm packing more same-kind mobs
     * into one {@code merge.radius} than the configured budget is this mod's entire
     * reason to exist, not an edge case.
     */
    @GameTest
    public void maxMergesPerScanBoundsWorkWithinASingleCrowd(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        int originalBudget = ConfigManager.get().merge.maxMergesPerScan;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;
            ConfigManager.get().merge.maxMergesPerScan = 2;

            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow d = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
            Cow e = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 2));
            Cow f = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 3));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c, d, e, f) == 3,
                    "a budget of 2 must cap this single six-cow crowd at host + 2 merges, not absorb all five neighbours");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
            ConfigManager.get().merge.maxMergesPerScan = originalBudget;
        }
        helper.succeed();
    }

    /**
     * {@code merge.enabled = false} must short-circuit before even reading the scan
     * interval - so unlike every other test here, this one deliberately does not force
     * {@code scanIntervalTicks}: {@code MergeScanner.tick} must return before ever
     * touching it, and this test asserts exactly that early return rather than merely
     * relying on throttling to produce the same-looking "nothing happened" outcome.
     */
    @GameTest
    public void mergeEnabledFalseDisablesScanningCompletely(GameTestHelper helper) {
        boolean originalEnabled = ConfigManager.get().merge.enabled;
        try {
            ConfigManager.get().merge.enabled = false;

            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow d = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c, d) == 1,
                    "merge.enabled = false must disable the scanner completely, even for an otherwise-qualifying crowd");
        } finally {
            ConfigManager.get().merge.enabled = originalEnabled;
        }
        helper.succeed();
    }

    /**
     * {@code scanIntervalTicks} set far out of reach: even several explicit calls must
     * not scan. Proves the throttle actually suppresses scanning, not merely that this
     * particular crowd happens not to merge - the same crowd shape merges reliably in
     * {@link #exactlyAtThresholdMerges}.
     */
    @GameTest
    public void belowScanIntervalNothingHappens(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1_000_000;

            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow d = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

            MergeScanner.tick(helper.getLevel());
            MergeScanner.tick(helper.getLevel());
            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c, d) == 1,
                    "a scan must not run at all before the configured interval elapses, however many ticks arrive");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * The other end of {@link #belowScanIntervalNothingHappens}: once a level has
     * accumulated at least {@code scanIntervalTicks} calls, the very next one must
     * actually scan.
     */
    @GameTest
    public void atScanIntervalTheScanRuns(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow b = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow c = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow d = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(a, b, c, d) > 1,
                    "once due, the very next call must actually scan and merge a qualifying crowd");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * {@code MergeScanner.tick} must maintain co-location, not just form stacks - {@code
     * DormantStore}'s own Javadoc explains why this matters: a frozen member whose chunk
     * merely unloads resolves identically to one that was destroyed, and gets silently
     * dropped rather than ever released. A frozen member never moves under its own
     * power, so a wandering host is the only thing that can create that gap; {@code
     * MergeScanner.tick} closing it is what {@link
     * com.mobtimizer.freeze.Dormancy#followHost} being called from this scanner is for.
     * Sets the stack up directly via {@code StackManager.merge} rather than through a
     * scanned crowd, so this test isolates the co-location pass from crowd formation -
     * and uses two members, not one, so both are proven re-collected, not just whichever
     * happens to be first in the stack's member list.
     */
    @GameTest
    public void followHostKeepsAMovedHostsMemberCoLocated(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow memberOne = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        Cow memberTwo = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1));
        helper.assertTrue(StackManager.merge(host, memberOne), "setup sanity check: the first merge should succeed");
        helper.assertTrue(StackManager.merge(host, memberTwo), "setup sanity check: the second merge should succeed");

        host.snapTo(host.getX() + 4, host.getY(), host.getZ() + 4, host.getYRot(), host.getXRot());
        helper.assertTrue(memberOne.position().distanceTo(host.position()) > 0.001,
                "setup sanity check: the host's move should have left the first member behind");
        helper.assertTrue(memberTwo.position().distanceTo(host.position()) > 0.001,
                "setup sanity check: the host's move should have left the second member behind too");

        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;
            MergeScanner.tick(helper.getLevel());
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }

        helper.assertTrue(memberOne.position().distanceTo(host.position()) < 0.001,
                "MergeScanner.tick must re-co-locate a frozen member onto its moved host");
        helper.assertTrue(memberTwo.position().distanceTo(host.position()) < 0.001,
                "MergeScanner.tick must re-co-locate every frozen member, not just the first");
        helper.succeed();
    }

    /**
     * Post-approval review of Task 9 traced a real design gap: the crowd check counted
     * an existing host as a flat {@code 1} regardless of its true size, so a single
     * loose mob wandering next to an already-formed 4-member host would read the crowd
     * as only {@code 1 (host) + 1 (newcomer) = 2}, well under the default threshold of
     * 4, and never merge - even though the farm obviously already has plenty of crowd.
     * Fixed by weighing the host with {@link StackManager#countOf} instead of a flat
     * {@code 1}. Order-independent by construction: only one other root entity exists
     * here (the pre-built host, hiding its three frozen members; the newcomer), so
     * regardless of which one the scanner elects as the surviving host, the single
     * available match always fully absorbs the other - see this class's Javadoc for why
     * a single-match crowd can never be ambiguous about how much merges, only about
     * which side ends up as host.
     */
    @GameTest
    public void looseMobIsAbsorbedByAnExistingMultiMemberHostThroughTheScanner(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow memberA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow memberB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow memberC = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
            helper.assertTrue(StackManager.merge(host, memberA), "setup sanity check");
            helper.assertTrue(StackManager.merge(host, memberB), "setup sanity check");
            helper.assertTrue(StackManager.merge(host, memberC), "setup sanity check");
            helper.assertTrue(StackManager.countOf(host) == 4, "setup sanity check: host is already a stack of 4");

            Cow newcomer = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 2));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(host, memberA, memberB, memberC, newcomer) == 5,
                    "a single loose mob must be absorbed by an existing 4-member host - the crowd check must count "
                            + "the host's true size (4), not a flat 1, or this crowd wrongly reads as only 2 and "
                            + "never merges");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * The other ordering the same review traced: two independently-formed 2-cow stacks
     * standing near each other. Each is a single *entity* to the other's crowd query
     * (its own member is frozen and invisible to it), so an un-weighted {@code
     * matches.size()} would read this crowd as {@code 1 (host) + 1 (other stack,
     * counted as one entity) = 2} - well under threshold - even though the two stacks
     * together represent 4 real mobs. Fixed by weighing every entry in {@code matches}
     * by {@link StackManager#countOf} too, not just the host - see {@link
     * com.mobtimizer.merge.MergeScanner#mergeAround}'s Javadoc. Order-independent for
     * the same single-match reason as {@link
     * #looseMobIsAbsorbedByAnExistingMultiMemberHostThroughTheScanner}.
     */
    @GameTest
    public void twoIndependentlyFormedStacksCombineThroughTheScannerByTrueWeight(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow memberA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            helper.assertTrue(StackManager.merge(hostA, memberA), "setup sanity check");

            Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
            Cow memberB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 2));
            helper.assertTrue(StackManager.merge(hostB, memberB), "setup sanity check");

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(hostA, memberA, hostB, memberB) == 4,
                    "two independently-formed 2-cow stacks standing near each other must combine into one stack of "
                            + "4 - counting entities instead of true represented size would read this crowd as only "
                            + "2 and refuse to merge");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * Three separately-formed 2-cow stacks (true weight 2 each), budget lowered to
     * exactly 2 - chosen so the result is deterministic regardless of which stack the
     * scanner elects as host or in what order it considers the other two, unlike a
     * two-stack version of this test would be. Weighted accounting: the very first
     * absorption (weight 2) exactly exhausts the budget, blocking the second regardless
     * of which one went first - by symmetry, every one of the three has the same
     * weight, so exactly one of the two non-host stacks ever gets absorbed, no matter
     * which stack ends up hosting or which order its matches are visited in. That
     * leaves the host at a true size of 4 and the third, un-absorbed stack untouched at
     * 2 - a maximum count of 4 among all six mobs, always.
     *
     * <p>Flat accounting (counting successful merge *calls*, not their size) would
     * instead always let both absorptions through: a budget of "2" comfortably covers 2
     * successful calls regardless of their individual weight, leaving the host at 6 and
     * nothing untouched. The two schemes are cleanly distinguishable here with no
     * dependency on iteration order.
     */
    @GameTest
    public void stackToStackMergeChargesItsTrueWeightAgainstTheBudget(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        int originalBudget = ConfigManager.get().merge.maxMergesPerScan;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;
            ConfigManager.get().merge.maxMergesPerScan = 2;

            Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow memberA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            helper.assertTrue(StackManager.merge(hostA, memberA), "setup sanity check");

            Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
            Cow memberB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 2));
            helper.assertTrue(StackManager.merge(hostB, memberB), "setup sanity check");

            Cow hostC = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            Cow memberC = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 3));
            helper.assertTrue(StackManager.merge(hostC, memberC), "setup sanity check");

            MergeScanner.tick(helper.getLevel());

            int total = maxCountAmong(hostA, memberA, hostB, memberB, hostC, memberC);
            helper.assertTrue(total == 4,
                    "a budget of 2 must be exactly exhausted by absorbing one 2-cow stack (true weight 2), blocking "
                            + "a second absorption of equal size regardless of order - if this reads 6, both "
                            + "absorptions went through, meaning the budget was charged flat successes (\"2 calls\") "
                            + "rather than each merge's true size");
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
            ConfigManager.get().merge.maxMergesPerScan = originalBudget;
        }
        helper.succeed();
    }

    /**
     * The coordinator's explicit "verify rather than assume": a stack-to-stack
     * absorption's transferred sub-members are already frozen and co-located with
     * their <em>old</em> host at the moment they move to the new one. This confirms
     * they end up co-located with the new host too, through the real scanner entry
     * point - {@code keepMembersWithHosts} runs unconditionally at the end of every
     * {@code tick()} that scans at all, after the merge loop, so it picks up a
     * same-scan transfer's freshly-updated membership immediately, not a scan later.
     * Order-independent: whichever of hostA/hostB the scanner elects as the surviving
     * host, every other mob in the crowd - including hostA's own former members,
     * transferred as part of the absorption - must end up next to it.
     */
    @GameTest
    public void transferredMembersFollowTheirNewHostAfterAScannerDrivenAbsorption(GameTestHelper helper) {
        int originalInterval = ConfigManager.get().merge.scanIntervalTicks;
        try {
            ConfigManager.get().merge.scanIntervalTicks = 1;

            Cow hostA = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
            Cow memberA1 = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
            Cow memberA2 = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
            helper.assertTrue(StackManager.merge(hostA, memberA1), "setup sanity check");
            helper.assertTrue(StackManager.merge(hostA, memberA2), "setup sanity check");

            Cow hostB = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

            MergeScanner.tick(helper.getLevel());

            helper.assertTrue(maxCountAmong(hostA, hostB) == 4, "setup sanity check: the merge must have happened");

            Mob winner = StackManager.countOf(hostA) > 1 ? hostA : hostB;
            for (Mob mob : List.of(hostA, hostB, memberA1, memberA2)) {
                if (mob == winner) continue;
                helper.assertTrue(mob.position().distanceTo(winner.position()) < 0.001,
                        "every absorbed mob, including a transferred sub-member, must be co-located with the final "
                                + "host after a scanner-driven absorption");
            }
        } finally {
            ConfigManager.get().merge.scanIntervalTicks = originalInterval;
        }
        helper.succeed();
    }

    /**
     * Order-independent alternative to checking one named mob's own count - see this
     * class's Javadoc for why that matters. Exactly one mob in a merged crowd reads the
     * full count (the one the scanner happened to choose as host); every other member
     * reads {@code 1}, since a frozen member's own {@code STACK} attachment is never
     * touched.
     */
    private static int maxCountAmong(Mob... mobs) {
        int max = 0;
        for (Mob mob : mobs) {
            max = Math.max(max, StackManager.countOf(mob));
        }
        return max;
    }
}
