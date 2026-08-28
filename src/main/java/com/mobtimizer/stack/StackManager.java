package com.mobtimizer.stack;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.display.StackNameplate;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.identity.StackEligibility;
import com.mobtimizer.identity.StackKeyFactory;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/** The single entry point for changing a stack's membership. */
public final class StackManager {
    private StackManager() {}

    public static int countOf(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).memberCount();
    }

    public static boolean isStacked(Mob mob) {
        return countOf(mob) > 1;
    }

    /**
     * Folds {@code member} into {@code host}. Returns false if they are not compatible.
     *
     * <p><b>{@code member} may itself already be a stack host - stack-to-stack merging
     * is allowed,</b> not refused as phase 1 originally shipped it. A flat refusal here
     * meant a farm's stacks could only ever grow by simultaneous crowding: a single mob
     * maturing into eligibility next to an already-formed stack could never join it (it
     * has nothing to crowd with - the existing stack's own members are already frozen
     * and invisible to a fresh crowd count), and two independently-formed stacks could
     * never combine even standing right next to each other. When {@code member} is
     * itself a host, this method transfers its members onto {@code host} first via
     * {@link DormantStore#transferMembers}, <em>before</em> {@code member} itself is
     * added to {@code host} as an ordinary member below.
     *
     * <p><b>That ordering is what keeps {@link DormantStore#add}'s "member must not
     * itself be a stack host" guard intact and meaningful, rather than relaxing it.</b>
     * That guard exists specifically to prevent orphaning a still-populated host's
     * sub-members - nothing ever revisits a frozen member's own attachment again, so
     * freezing a busy host as someone else's member would strand its members
     * permanently. Draining first means that by the time {@code add} runs below,
     * {@code member}'s own list is genuinely, verifiably empty: {@code add}'s guard
     * sees exactly the same "not a stack host" mob it would see for a plain loose
     * member, and passes normally rather than needing to be told to look the other way.
     *
     * <p><b>Guard consistency with {@code add}, updated for the above.</b> {@code host
     * == member} is still exactly equivalent to add's self-add guard (two distinct live
     * {@code Mob} instances never share a UUID). The member-is-itself-a-host guard is no
     * longer made unreachable by an equivalent refusal here (there is no such refusal
     * any more); instead it is satisfied by construction, since the transfer above
     * always empties {@code member}'s list before {@code add} is reached. Either way,
     * neither of the store's two guards can fire when called from here today - but that
     * remains a fact about how this method is written, not one the compiler enforces,
     * which is why it still propagates {@code add}'s own return value below instead of
     * hardcoding {@code true}: if the store's guards are ever extended independently of
     * this method (as they already were once, in Task 6's own fix round), or if this
     * method's own ordering is ever changed carelessly, a caller here starts getting an
     * honest {@code false} automatically rather than a stale {@code true}.
     *
     * <p>The third precondition - {@code member} must not already belong to some
     * <em>other</em> host - is explicitly left unenforced at the store, per {@link
     * MemberStore#add}'s Javadoc: cheaply detecting it there would need an index over
     * every host's member list, which does not exist. It costs nothing extra here,
     * though: {@link Dormancy#isFrozen} is an O(1) read of the mover's own attachment,
     * and in this codebase the only writers of that attachment are {@code
     * DormantStore.add} (sets it) and {@code takeOne}/{@code takeAll}/{@code
     * transferMembers} (clear it) - so a mob reads frozen if and only if some host
     * currently claims it, without needing to know which one. Skipping this check would
     * let an already-claimed member get re-added under a second host: {@code add} has no
     * guard against it (both of its checks read {@code member}'s own {@code STACK}
     * attachment, which a plain frozen member never has), so the second host's
     * {@code withMember} would happily insert the same UUID again - now double-booked.
     * The identical check on {@code host} additionally refuses to let an already-claimed
     * mob be (mis)used as a brand-new host. Both halves of this check keep working
     * unchanged for the stack-to-stack case: a host - however many members it has - is
     * by definition never itself frozen, and a frozen mob is by definition never itself
     * a host with members to transfer, so "is this mob frozen" and "is this mob a stack
     * host" never overlap for the same mob and there is nothing here for the
     * stack-to-stack path to interact badly with.
     */
    public static boolean merge(Mob host, Mob member) {
        if (host == member) return false;
        if (!StackEligibility.canStack(host) || !StackEligibility.canStack(member)) return false;
        if (Dormancy.isFrozen(host) || Dormancy.isFrozen(member)) return false; // already claimed elsewhere
        if (!StackKeyFactory.of(host).equals(StackKeyFactory.of(member))) return false;

        if (isStacked(member)) {
            DormantStore.INSTANCE.transferMembers(member, host);
        }

        boolean added = DormantStore.INSTANCE.add(host, member);
        StackNameplate.refresh(host);
        return added;
    }

    /** Releases exactly one member as an independent mob, or null if the host is alone. */
    public static @Nullable Mob splitOne(Mob host) {
        Mob released = DormantStore.INSTANCE.takeOne(host);
        StackNameplate.refresh(host);
        return released;
    }

    /** Releases every member. Returns how many mobs were freed. */
    public static int unstack(Mob host) {
        int released = DormantStore.INSTANCE.takeAll(host).size();
        StackNameplate.refresh(host);
        return released;
    }
}
