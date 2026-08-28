package com.mobtimizer.stack;

import com.mobtimizer.Mobtimizer;
import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps members in the world as frozen entities.
 *
 * <p>This is why removing the mod is survivable: the members are ordinary
 * entities in the save file, so with the freeze Mixins no longer running they
 * simply wake up.
 *
 * <p><b>Known limitation:</b> {@code resolve} (backing both {@link #takeOne} and
 * {@link #takeAll}) can only ask {@code Level#getEntity(UUID)} whether a member's
 * entity is currently resident in memory. That lookup is backed by
 * {@code PersistentEntitySectionManager}'s in-memory {@code EntityLookup}, which is
 * populated and emptied as chunks load and unload - it has no way to distinguish "this
 * entity's chunk is merely unloaded right now" from "this entity is permanently gone".
 * A member whose chunk has simply unloaded therefore resolves exactly like one that
 * was actually destroyed: its id is silently dropped and nothing is returned for it,
 * even though the entity itself is still fully intact on disk. This class cannot fix
 * that from inside these methods - correctness here depends on members staying loaded
 * and co-located with their host so the two cases never have to be told apart in
 * practice. Keeping that true is Task 7's responsibility (freezing co-locates a member
 * with its host and suppresses its natural despawn) and Task 9's (periodic
 * re-co-location), not this store's.
 */
public final class DormantStore implements MemberStore {
    public static final DormantStore INSTANCE = new DormantStore();

    private DormantStore() {}

    @Override
    public int size(Mob host) {
        return stackOf(host).members().size();
    }

    /**
     * Refuses two of {@link MemberStore#add}'s three documented preconditions rather
     * than trusting the caller: adding a host to itself, and adding a mob that is
     * itself already a stack host (which would orphan that mob's own members
     * permanently, since nothing ever revisits a frozen member's own attachment). Both
     * are logged at {@code ERROR}, leave every attachment untouched, and report
     * {@code false} - refusing loudly gets the diagnostic value of a caller bug without
     * letting an exception out of merge orchestration, which runs on the server thread
     * and cannot afford to take the server down over it.
     *
     * <p>The third precondition - {@code member} already belongs to a <em>different</em>
     * host - is not checked here. Detecting it cheaply would require an index over
     * every host's member list, which does not exist and is not worth building for
     * this; it stays a documented caller responsibility only, per
     * {@link MemberStore#add}.
     */
    @Override
    public boolean add(Mob host, Mob member) {
        if (member.getUUID().equals(host.getUUID())) {
            Mobtimizer.LOGGER.error(
                    "Refusing to stack {} ({}) as a member of itself",
                    EntityType.getKey(host.getType()), host.getUUID());
            return false;
        }

        MobStack memberOwnStack = stackOf(member);
        if (!memberOwnStack.members().isEmpty()) {
            Mobtimizer.LOGGER.error(
                    "Refusing to add {} ({}) to {} ({})'s stack: it is itself a stack host "
                            + "with {} member(s) of its own that would be orphaned",
                    EntityType.getKey(member.getType()), member.getUUID(),
                    EntityType.getKey(host.getType()), host.getUUID(),
                    memberOwnStack.members().size());
            return false;
        }

        Dormancy.freeze(member, host);
        host.setAttached(MobtimizerAttachments.STACK, stackOf(host).withMember(member.getUUID()));
        return true;
    }

    /**
     * {@code id} is removed from the attachment unconditionally, before {@code resolve}
     * even runs - not only once {@code resolve} confirms the entity is still there.
     * That order is load-bearing, not incidental: {@code resolve} has no side effects
     * of its own (it is a pure lookup), so the only thing that actually matters for
     * correctness is that the removal happens on <em>every</em> path, including the one
     * where the entity is gone. If removal were made conditional on {@code resolve}
     * succeeding instead, a vanished member's id would never leave the list - and since
     * it would still be sitting at {@code getLast()}, it would be the id every
     * subsequent call keeps retrying and failing on, permanently shadowing every real,
     * still-alive member behind it. Unconditional removal is what makes a vanished
     * member self-healing instead of a stuck, un-diagnosable leak.
     */
    @Override
    public @Nullable Mob takeOne(Mob host) {
        MobStack stack = stackOf(host);
        if (stack.members().isEmpty()) {
            return null;
        }

        UUID id = stack.members().getLast();
        host.setAttached(MobtimizerAttachments.STACK, stack.withoutMember(id));

        Mob member = resolve(host, id);
        if (member == null) {
            // The entity is unresolvable: its chunk is unloaded, it despawned
            // naturally, the chunk was trimmed, it was killed, or another mod removed
            // it. Dropping the stale id and reporting nothing is correct for every case
            // where the entity is actually gone - but resolve() cannot tell "merely
            // unloaded" apart from "gone" (see the class Javadoc), so this path is only
            // truly safe as long as members stay loaded and co-located with their host.
            return null;
        }
        Dormancy.thaw(member);
        return member;
    }

    /**
     * Deliberately loops on {@link #size}, not on {@code takeOne(host) != null}.
     *
     * <p>{@code takeOne} returns null both when the store is genuinely empty and when
     * the specific member it just popped has vanished - and a stale member can be
     * anywhere in the list, not only last. If the loop instead stopped the moment
     * {@code takeOne} returned null, a single vanished member would silently end the
     * whole drain: every real, still-alive member added earlier than that stale one
     * (LIFO, so "earlier" means "later in the takeOne order") would be abandoned,
     * un-thawed, and left attached to {@code host} forever, even though {@code takeAll}
     * is documented to remove and thaw every member. That is exactly the kind of
     * un-diagnosable leak this store exists to avoid, and it reproduces on every run,
     * not just intermittently: see {@code DormantStoreGameTest
     * #takeAllSkipsAVanishedMemberAndStillReturnsTheRest}, which adds a real member
     * before a member that is then made to vanish - putting the corpse at the LIFO
     * tail, i.e. exactly where {@code takeOne} looks first - and fails against a
     * {@code while ((next = takeOne(host)) != null)} version of this method.
     *
     * <p>{@code size} is safe to loop on because {@code takeOne} unconditionally
     * removes one id from the attachment on every call where the store is non-empty,
     * whether or not that id resolves - so this loop is guaranteed to make progress and
     * terminates in exactly the stack's starting member count, regardless of how many
     * stale ids it passes through.
     */
    @Override
    public List<Mob> takeAll(Mob host) {
        List<Mob> thawed = new ArrayList<>();
        while (size(host) > 0) {
            Mob next = takeOne(host);
            if (next != null) {
                thawed.add(next);
            }
        }
        return thawed;
    }

    /**
     * Re-parents every member of {@code from} onto {@code to} one at a time: remove
     * from {@code from}'s attachment, resolve, then {@link #add} to {@code to} - never
     * read {@code from}'s whole list once and clear it in bulk afterward. That ordering
     * is what keeps each id registered under at most one host at every point during
     * this call, including if something unexpected interrupted the loop partway: the
     * ids already processed are cleanly under {@code to} only, the ids not yet reached
     * are still cleanly under {@code from} only - never both. See {@link
     * MemberStore#transferMembers} for the full contract, in particular why the caller
     * must run this before folding {@code from} itself in as an ordinary member.
     *
     * <p>Reuses {@link #add} rather than writing {@code to}'s attachment directly, so a
     * transferred member goes through the exact same freeze/position-snap path as a
     * freshly-added one (its {@code add}-time guards cannot fire here: a transferred
     * member is by construction a plain frozen mob with an empty member list of its
     * own, never itself a host, so {@code add} always succeeds and needs no separate
     * error handling beyond what it already logs internally on the paths that cannot be
     * reached from here).
     */
    @Override
    public void transferMembers(Mob from, Mob to) {
        for (UUID id : stackOf(from).members()) {
            from.setAttached(MobtimizerAttachments.STACK, stackOf(from).withoutMember(id));

            Mob transferred = resolve(from, id);
            if (transferred != null) {
                add(to, transferred);
            }
            // else: unresolvable (chunk unloaded / already gone) - dropped, same
            // accepted limitation as takeOne above; see MemberStore#transferMembers.
        }
    }

    private static MobStack stackOf(Mob host) {
        return host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);
    }

    private static @Nullable Mob resolve(Mob host, UUID id) {
        if (!(host.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(id);
        return entity instanceof Mob mob ? mob : null;
    }
}
