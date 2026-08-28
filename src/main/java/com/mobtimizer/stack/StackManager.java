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
     * <p><b>Guard consistency with {@link DormantStore#add}.</b> That store defends
     * two of {@link MemberStore#add}'s three documented preconditions itself - self-add
     * and member-is-itself-a-host - by refusing and logging ERROR rather than mutating
     * anything. This method's own checks are not merely similar to those two guards,
     * they are exactly equivalent to them: {@code host == member} is the same fact
     * {@code add} re-derives via UUID equality (two distinct live {@code Mob} instances
     * never share a UUID), and {@link #isStacked}{@code (member)} - {@code
     * countOf(member) > 1} - is true if and only if {@code member}'s own {@code STACK}
     * attachment has a non-empty member list, which is precisely {@code add}'s
     * member-is-a-host check. So whenever this method reaches the {@code
     * DormantStore.INSTANCE.add} call below, neither of the store's guards can fire:
     * the mutation always actually happens today.
     *
     * <p>That equivalence is proven, not assumed - but it is still a fact about
     * <em>today's</em> two guards, maintained by two call sites agreeing, not by the
     * compiler. Rather than lean on it forever, this method propagates {@code add}'s own
     * return value instead of hardcoding {@code true} once it decides to attempt the
     * mutation: if the store's guards are ever extended independently of this method (as
     * they already were once, in Task 6's own fix round), a caller here starts getting an
     * honest {@code false} automatically, not a return value that silently drifts out of
     * sync with what the store actually did.
     *
     * <p>The third precondition - {@code member} must not already belong to some
     * <em>other</em> host - is explicitly left unenforced at the store, per {@link
     * MemberStore#add}'s Javadoc: cheaply detecting it there would need an index over
     * every host's member list, which does not exist. It costs nothing extra here,
     * though: {@link Dormancy#isFrozen} is an O(1) read of the mover's own attachment,
     * and in this codebase the only writers of that attachment are {@code
     * DormantStore.add} (sets it) and {@code takeOne}/{@code takeAll} (clear it) - so a
     * mob reads frozen if and only if some host currently claims it, without needing to
     * know which one. Skipping this check would let an already-claimed member get
     * re-added under a second host: {@code add} has no guard against it (both of its
     * checks read {@code member}'s own {@code STACK} attachment, which a plain frozen
     * member never has), so the second host's {@code withMember} would happily insert
     * the same UUID again - now double-booked, with both hosts' own {@code takeOne}/
     * {@code takeAll} eventually resolving and thawing the same live entity
     * independently. The identical check on {@code host} additionally refuses to let an
     * already-claimed mob be (mis)used as a brand-new host, which would silently create
     * a "stack" that can never do anything with its own members: a frozen mob never
     * ticks (the freeze Mixins gate on {@link Dormancy#isFrozen}), so it could never
     * run whatever future scanner or combat logic a real host needs to run.
     */
    public static boolean merge(Mob host, Mob member) {
        if (host == member) return false;
        if (!StackEligibility.canStack(host) || !StackEligibility.canStack(member)) return false;
        if (isStacked(member)) return false; // never merge a stack into a stack in phase 1
        if (Dormancy.isFrozen(host) || Dormancy.isFrozen(member)) return false; // already claimed elsewhere
        if (!StackKeyFactory.of(host).equals(StackKeyFactory.of(member))) return false;

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
