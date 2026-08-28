package com.mobtimizer.identity;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.display.StackNameplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;

public final class StackEligibility {
    private StackEligibility() {}

    /**
     * Whether this mob may participate in stacking at all.
     *
     * <p>Every exclusion here represents a deliberate player setup — a named,
     * leashed, ridden or tamed mob is one the player singled out, and merging it
     * away destroys that intent.
     *
     * <p>The design spec's eligibility rule is "is invulnerable, or is a boss" -
     * originally implemented as invulnerable-only, restored here after Task 7's
     * review found the boss half had been dropped along the way and nothing stopped
     * a Wither from being frozen: {@link com.mobtimizer.mixin.FrozenDespawnMixin}
     * does not reach {@code WitherBoss.checkDespawn()} (a complete override with no
     * {@code super} call), so a frozen Wither member is silently discarded the
     * instant a player switches the world to Peaceful.
     *
     * <p><b>Boss detection is two explicit {@code instanceof} checks, not a general
     * rule - because 26.2 has no general rule to hook.</b> Checked, in this order,
     * before writing anything: {@code EntityTypeTags} for a boss tag (none exists -
     * confirmed by disassembling {@code EntityTypeTags}, no boss-related field or
     * string constant anywhere in it); a shared interface or method on
     * {@code EntityType}/{@code Mob}/{@code LivingEntity}/{@code MobCategory} (none
     * of the four declares anything boss-related); and a common
     * {@code ServerBossEvent}-based signal (there isn't one to share: {@code
     * WitherBoss} owns a private {@code ServerBossEvent bossEvent} field, but {@code
     * EnderDragon}'s own class does not reference {@code BossEvent} anywhere in its
     * bytecode at all in this version - its boss bar is evidently managed by
     * something else entirely, external to the mob). The two known vanilla bosses
     * share no structural marker with each other, let alone a general one a modded
     * boss could be expected to also carry, so {@code EntityTypeTags} et al. offer
     * nothing to check against. Explicit types is not a fallback chosen for lack of
     * looking harder; it is the only thing actually there. Modded bosses have no
     * general hook here either and must go through the denylist, same as any other
     * modded entity this mod doesn't recognise a category for.
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
        if (mob instanceof WitherBoss || mob instanceof EnderDragon) return false;

        return ConfigManager.get().entities.isAllowed(mob.getType());
    }
}
