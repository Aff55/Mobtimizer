package com.mobtimizer.stack;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 */
public final class DormantStore implements MemberStore {
    public static final DormantStore INSTANCE = new DormantStore();

    private DormantStore() {}

    @Override
    public int size(Mob host) {
        return stackOf(host).members().size();
    }

    @Override
    public void add(Mob host, Mob member) {
        Dormancy.freeze(member, host);
        host.setAttached(MobtimizerAttachments.STACK, stackOf(host).withMember(member.getUUID()));
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
            // The entity vanished (chunk trimmed, /kill, another mod). Dropping the
            // stale id and reporting nothing is correct: the member no longer exists.
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

    private static MobStack stackOf(Mob host) {
        return host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);
    }

    private static @Nullable Mob resolve(Mob host, UUID id) {
        if (!(host.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(id);
        return entity instanceof Mob mob ? mob : null;
    }
}
