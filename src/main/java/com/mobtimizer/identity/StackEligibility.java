package com.mobtimizer.identity;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.display.StackNameplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;

public final class StackEligibility {
    private StackEligibility() {}

    /**
     * Whether this mob may participate in stacking at all.
     *
     * <p>Every exclusion here represents a deliberate player setup — a named,
     * leashed, ridden or tamed mob is one the player singled out, and merging it
     * away destroys that intent.
     */
    public static boolean canStack(Mob mob) {
        if (!(mob.level() instanceof ServerLevel)) return false;
        if (mob.isRemoved() || !mob.isAlive()) return false;

        // A player-assigned name means "this one is special". The count nameplate
        // is also a custom name, so it must be excluded from this check or hosts
        // would silently become ineligible the moment they were labelled.
        if (mob.hasCustomName() && !StackNameplate.isModOwnedName(mob)) return false;

        if (mob.isLeashed()) return false;
        if (mob.isPassenger() || mob.isVehicle()) return false;
        if (mob instanceof OwnableEntity) return false;
        if (mob.isPersistenceRequired()) return false;
        if (mob.isNoAi()) return false;
        if (mob.isInvulnerable()) return false;

        return ConfigManager.get().entities.isAllowed(mob.getType());
    }
}
