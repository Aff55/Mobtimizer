package com.mobtimizer.stack;

import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Where a stack's non-host members live.
 *
 * <p>Phase 1 ships {@link DormantStore} only. Phase 5 adds a virtual backend
 * that compresses members to NBT; it implements this same interface so no game
 * mechanic needs a second code path.
 */
public interface MemberStore {
    int size(Mob host);

    /**
     * Adds {@code member} to {@code host}'s stack. Returns whether it was actually
     * added - {@code false} means a documented precondition below was violated and the
     * implementation defensively refused rather than mutating anything.
     *
     * <p>This return value exists so a caller's own precondition checks never have to be
     * trusted, by discipline alone, to stay in exact lockstep with whatever this method
     * defends against internally - that set of guards can grow (it already has once: see
     * Task 6's fix round, which added the first two checks below after shipping without
     * them) independently of any given caller. {@link StackManager#merge} propagates
     * this value directly for exactly that reason, rather than assuming its own checks
     * make every refusal path here unreachable and returning a hardcoded {@code true}.
     *
     * <p>The caller is responsible for all of the following; violating any of them
     * corrupts state silently unless the implementation defends against it:
     * <ul>
     *   <li>{@code member} is not {@code host} itself - a host must never appear in
     *       its own member list.
     *   <li>{@code member} is not itself a stack host - i.e. {@code member} has no
     *       members of its own. Freezing a host as someone else's member would orphan
     *       its own members permanently, since nothing ever revisits a frozen member's
     *       own attachment afterward.
     *   <li>{@code member} is not already a member of some <em>other</em> host -
     *       nothing here removes it from that other host's list, so the same id would
     *       end up live in two attachments at once.
     * </ul>
     * Implementations may detect and refuse some of these defensively (typically by
     * logging and returning {@code false} without mutating anything, not by throwing - a
     * caller mistake here should never be able to bring down a live server) but are not
     * required to catch all three; the third in particular is not cheaply detectable
     * without an index over every host.
     */
    boolean add(Mob host, Mob member);

    /**
     * Removes one member and returns it as a live, thawed mob. Returns null both when
     * the stack has no members and when the member that was removed could not be
     * resolved back to a live entity.
     */
    @Nullable Mob takeOne(Mob host);

    /** Removes and thaws every member. Used by unstack. */
    List<Mob> takeAll(Mob host);

    /**
     * Moves every member of {@code from}'s own stack directly onto {@code to},
     * preserving their frozen, co-located state rather than releasing them - this is
     * "re-parent," not "unstack then re-add." Used when one stack host is being folded
     * into another as an ordinary member (stack-to-stack merging).
     *
     * <p><b>Existing precondition still applies, and this method is what makes it hold
     * for the stack-to-stack case.</b> {@link #add}'s documented precondition that
     * {@code member} must not itself be a stack host exists specifically to prevent
     * orphaning that mob's own sub-members - nothing ever revisits a frozen member's
     * own attachment afterward, so freezing a still-populated host as someone else's
     * member would strand its members permanently. That guard must stay intact and
     * meaningful, not be relaxed: this method is the caller's tool for legitimately
     * satisfying it. A caller merging one stack host into another must call this first,
     * draining {@code from} down to zero members, so that {@code from} genuinely has no
     * members of its own by the time it is itself passed to {@link #add} as an ordinary
     * member - at which point {@code add}'s guard sees an empty list and passes
     * normally, exactly as it would for a mob that was never a host at all.
     *
     * <p>Each transferred member is individually removed from {@code from}'s attachment
     * before being added to {@code to}'s (not read once as a whole and cleared in bulk
     * afterward), so that at every point during this call each id is registered under
     * at most one host - never both, and only briefly neither (between the two writes
     * for that one id, which nothing else can observe mid-call since everything here
     * runs synchronously on the server thread).
     *
     * <p>A member id that no longer resolves to a live entity (its chunk merely
     * unloaded, or it is genuinely gone) is dropped rather than transferred - the same
     * accepted, self-healing behaviour {@link #takeOne} already has for exactly this
     * case, and the same reason: {@code Level#getEntity(UUID)} cannot tell "unloaded"
     * from "gone", and this store has no way to fix that from inside a single call.
     */
    void transferMembers(Mob from, Mob to);
}
