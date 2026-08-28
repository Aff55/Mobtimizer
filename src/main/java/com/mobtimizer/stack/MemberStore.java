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
}
