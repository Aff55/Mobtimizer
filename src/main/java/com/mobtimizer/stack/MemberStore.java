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

    void add(Mob host, Mob member);

    /** Removes one member and returns it as a live, thawed mob, or null if the stack has none. */
    @Nullable Mob takeOne(Mob host);

    /** Removes and thaws every member. Used by unstack. */
    List<Mob> takeAll(Mob host);
}
