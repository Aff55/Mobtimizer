package com.mobtimizer.merge;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.config.MobtimizerConfig;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.identity.StackEligibility;
import com.mobtimizer.identity.StackKey;
import com.mobtimizer.identity.StackKeyFactory;
import com.mobtimizer.stack.MobStack;
import com.mobtimizer.stack.StackManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodically scans a level for crowded, same-kind mobs and folds them into stacks.
 *
 * <p>Registered once from {@code Mobtimizer.onInitialize()} against {@code
 * ServerTickEvents.END_LEVEL_TICK}. The brief this class was built from named {@code
 * END_WORLD_TICK}; that constant does not exist in Fabric API 0.158.0 - confirmed by
 * disassembling {@code ServerTickEvents} in {@code fabric-lifecycle-events-v1}, which
 * declares only {@code START_SERVER_TICK}/{@code END_SERVER_TICK} (whole-server, no
 * level) and {@code START_LEVEL_TICK}/{@code END_LEVEL_TICK} (per level, callback
 * {@code EndLevelTick.onEndTick(ServerLevel)}). {@code END_LEVEL_TICK} is the one that
 * matches this class's actual signature and fires once per <em>loaded level</em> per
 * server tick - which is why every piece of state this class keeps between calls is
 * keyed by {@link ServerLevel} rather than global: a real server normally has at least
 * three levels (Overworld, Nether, End) all ticking every server tick, each producing
 * its own call here.
 *
 * <p><b>The crowd gate is the feature, not an optimisation.</b> {@link #mergeAround}
 * only merges once a host's same-kind, eligible, unstacked, unfrozen neighbours within
 * {@code merge.radius} - plus the host itself - together represent at least {@code
 * merge.crowdThreshold} mobs. Below that, mobs are left completely untouched: a couple
 * of pet animals standing near each other must never fuse. The host's own contribution
 * is weighed by its true size ({@link StackManager#countOf}), not counted as a flat 1 -
 * see {@link #mergeAround}'s own Javadoc for why that matters.
 */
public final class MergeScanner {
    /**
     * Ticks elapsed since each level's last scan.
     *
     * <p>Keyed per {@link ServerLevel} rather than a single shared counter, because
     * {@code END_LEVEL_TICK} fires once per loaded level per server tick (see the class
     * Javadoc). A single shared counter would have every level's call incrementing the
     * same value, so {@code scanIntervalTicks} would stop meaning "every N ticks of
     * this level" and instead mean "every N ticks total, split across however many
     * levels happen to be loaded" - under-scanning every individual level relative to
     * what the config promises, and unevenly so, since which level "wins" a given
     * rollover depends on tick-registration order rather than anything level-specific.
     * Keying per level makes each level's own scan cadence independent of how many
     * other levels are loaded, matching what the config name actually reads as
     * promising. {@link ServerLevel} does not override {@code equals}/{@code
     * hashCode} (confirmed by disassembly - neither it nor {@code Level} declares
     * either), so this map keys correctly on identity, which is exactly what is wanted:
     * one entry per distinct dimension for as long as the server runs it.
     */
    private static final Map<ServerLevel, Integer> ticksSinceScan = new HashMap<>();

    private MergeScanner() {}

    public static void tick(ServerLevel level) {
        MobtimizerConfig config = ConfigManager.get();
        if (!config.merge.enabled) return;
        if (!dueToScan(level, config)) return;

        int budget = config.merge.maxMergesPerScan;

        for (Mob candidate : collectCandidates(level)) {
            if (budget <= 0) break;
            if (!StackEligibility.canStack(candidate)) continue;
            // Re-checked despite collectCandidates already excluding frozen mobs: an
            // earlier candidate processed earlier in *this* loop may have just frozen
            // this one as its own new member, which the snapshot collectCandidates
            // took at the top of this call could not have known about yet.
            if (Dormancy.isFrozen(candidate)) continue;
            if (candidate.isRemoved()) continue;

            budget -= mergeAround(candidate, config, budget);
        }

        keepMembersWithHosts(level);
    }

    /**
     * True once {@code level} has accumulated {@code merge.scanIntervalTicks} calls
     * since its last scan; resets that level's own count to zero as a side effect of
     * returning true. Every level accumulates independently - see {@link
     * #ticksSinceScan}.
     */
    private static boolean dueToScan(ServerLevel level, MobtimizerConfig config) {
        int ticks = ticksSinceScan.merge(level, 1, Integer::sum);
        if (ticks < config.merge.scanIntervalTicks) {
            return false;
        }
        ticksSinceScan.put(level, 0);
        return true;
    }

    /**
     * Merges eligible neighbours into {@code host}, but only once enough of them are
     * present. Below the crowd threshold mobs are left completely vanilla, so a few pet
     * animals never fuse. Returns how many merges actually succeeded, which is what the
     * caller's budget accounting must subtract - not how many were attempted, since
     * {@link StackManager#merge} can refuse - so a crowd of already-claimed or otherwise
     * ineligible neighbours can never silently burn through {@code maxMergesPerScan}
     * while accomplishing nothing.
     *
     * <p><b>The host's own contribution is weighed by its true size, not a flat 1.</b>
     * An existing multi-member host is itself only one <em>entity</em> nearby a fresh
     * candidate's crowd query, but it represents however many mobs {@link
     * StackManager#countOf} reports - a lone new arrival standing next to an existing
     * 50-cow host is a crowd of 51, not "the host (1) plus the newcomer (1) = 2". A flat
     * {@code + 1} for the host would silently refuse to grow an already-formed stack one
     * mob at a time, which matters on any farm where mobs mature individually rather
     * than crowding in all at once. {@code matches} itself does not need the same
     * treatment yet: it is still filtered to unstacked neighbours only, so every entry
     * represents exactly one mob and {@code matches.size()} is already accurate.
     *
     * <p>{@code remainingBudget} bounds how many of this crowd's members are actually
     * merged in this one call, independent of the crowd-threshold decision above, which
     * always looks at the crowd's true size rather than a budget-truncated one - whether
     * a crowd counts as "crowded enough to start merging" has nothing to do with how
     * much of this scan's budget happens to be left by the time the outer loop reaches
     * it. Without this cap, a single oversized crowd - exactly the case this mod exists
     * for, a farm with far more same-kind mobs packed inside one {@code merge.radius}
     * than {@code maxMergesPerScan} - would merge every eligible neighbour in one call
     * regardless of the budget the outer loop is tracking, making {@code
     * maxMergesPerScan} bound nothing for the one scenario it matters most for. Any
     * crowd members left over once the cap is hit are simply picked up on a later scan;
     * nothing about them is lost or marked, since they remain ordinary unmerged mobs
     * until they actually merge.
     */
    private static int mergeAround(Mob host, MobtimizerConfig config, int remainingBudget) {
        StackKey key = StackKeyFactory.of(host);
        AABB box = host.getBoundingBox().inflate(config.merge.radius);

        List<Mob> matches = new ArrayList<>();
        for (Mob other : host.level().getEntities(EntityTypeTest.forClass(Mob.class), box, o -> o != host)) {
            if (other.getType() != host.getType()) continue;
            if (Dormancy.isFrozen(other)) continue;
            if (StackManager.isStacked(other)) continue;
            if (!StackEligibility.canStack(other)) continue;
            if (!StackKeyFactory.of(other).equals(key)) continue;
            matches.add(other);
        }

        int crowdSize = StackManager.countOf(host) + matches.size();
        if (crowdSize < config.merge.crowdThreshold) return 0;

        int merged = 0;
        for (Mob other : matches) {
            if (merged >= remainingBudget) break;
            if (StackManager.merge(host, other)) merged++;
        }
        return merged;
    }

    /**
     * Snapshots every non-frozen {@link Mob} currently loaded in {@code level}.
     *
     * <p><b>Known limitation, left alone deliberately:</b> {@code
     * ServerLevel.getAllEntities()} walks every entity in the level, every scan - an
     * O(n) cost in the level's total entity count, not just in however many mobs are
     * actually crowded. At the default one-second interval ({@code
     * scanIntervalTicks = 20}) this is acceptable and keeps phase 1 simple, but it is
     * the first thing to optimise if profiling ever shows the scanner itself costing
     * meaningful time - e.g. by indexing mobs spatially (a per-chunk or per-region mob
     * list maintained incrementally) so a scan only visits mobs, or by scanning a
     * rotating subset of loaded chunks each pass instead of the whole level at once.
     * Neither is attempted here.
     */
    private static List<Mob> collectCandidates(ServerLevel level) {
        List<Mob> candidates = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && !Dormancy.isFrozen(mob)) {
                candidates.add(mob);
            }
        }
        return candidates;
    }

    /**
     * Re-snaps every stack's frozen members onto their host's current position.
     *
     * <p>This is what keeps a stack travelling as one as its host wanders, and it is
     * load-bearing for correctness, not merely cosmetic. {@link
     * com.mobtimizer.stack.DormantStore}'s own Javadoc explains that {@code
     * Level.getEntity(UUID)} cannot distinguish "this member's chunk is merely
     * unloaded right now" from "this member is permanently gone" - a member whose
     * chunk has simply unloaded resolves exactly like one that was actually destroyed,
     * and {@code takeOne} then silently drops its id, orphaning an otherwise intact
     * invisible mob forever. A frozen member never ticks and never moves under its own
     * power - freezing parks it at a fixed point and nothing else moves it once frozen
     * - so without this pass a wandering host would eventually leave its members
     * sitting in a chunk that unloads independently of the host's own. Running this
     * once per scan interval rather than every tick is enough: chunk unload happens
     * well after a chunk stops being referenced, comfortably longer than the default
     * one-second interval.
     */
    private static void keepMembersWithHosts(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof Mob host)) continue;
            if (!StackManager.isStacked(host)) continue;

            MobStack stack = host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);
            for (UUID id : stack.members()) {
                if (level.getEntity(id) instanceof Mob member) {
                    Dormancy.followHost(member, host);
                }
            }
        }
    }
}
