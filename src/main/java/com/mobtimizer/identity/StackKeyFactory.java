package com.mobtimizer.identity;

import com.mobtimizer.Mobtimizer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.Set;

public final class StackKeyFactory {
    /**
     * NBT keys allowed to differ between members of one stack.
     *
     * <p>This list is the mod's contract: anything not named here must match
     * exactly for two mobs to merge, including any field added by another mod.
     * Age, love and shear state appear because the stack owns them as
     * time-advancing state; Health appears because a damaged mob merges in and
     * is treated as full health, an accepted inaccuracy recorded in the spec.
     *
     * <p>Every entry here is the literal NBT key as written by {@code Entity}/{@code
     * LivingEntity}/{@code AgeableMob}/{@code Animal} in 26.2, verified against the
     * deobfuscated jar one at a time - see task-4-report.md's audit table. 26.2 uses a
     * mixed naming convention (most keys are PascalCase, but a few, like {@code
     * fall_distance} and the {@code last_hurt_by_*}/{@code ticks_since_last_hurt_by_mob}
     * family, are snake_case), so entries cannot be pattern-matched from the old names -
     * each was individually confirmed present in real serialized NBT. A name that
     * matches nothing is a silent no-op: {@code CompoundTag.remove} on an absent key
     * does nothing and reports no error, so a stale entry here does not fail loudly, it
     * just quietly stops working. {@code fall_distance} and {@code OnGround} are
     * transient physics state, same family as {@code Pos}/{@code Motion}/{@code
     * Rotation}. The {@code last_hurt_by_mob}/{@code ticks_since_last_hurt_by_mob} pair
     * (with player and memory-time siblings) replaced the old single {@code
     * HurtByTimestamp} key; {@code ticks_since_last_hurt_by_mob} is recomputed as
     * {@code tickCount - lastHurtByMobTimestamp} on every save, so once populated it
     * drifts on every tick - a mob that has ever been hit by another mob must still be
     * able to merge, so this pair has to be ignored, not just its stale predecessor.
     */
    public static final Set<String> IGNORED_KEYS = Set.of(
            "UUID", "Pos", "Motion", "Rotation", "OnGround", "fall_distance",
            "HurtTime", "last_hurt_by_player", "last_hurt_by_player_memory_time",
            "last_hurt_by_mob", "ticks_since_last_hurt_by_mob", "DeathTime", "Health",
            "Air", "Fire", "PortalCooldown", "TicksFrozen", "Brain",
            "Age", "ForcedAge", "InLove", "LoveCause", "Sheared",
            "CustomName", "CustomNameVisible"
    );

    private StackKeyFactory() {}

    public static StackKey of(Mob mob) {
        CompoundTag full = rawSerialize(mob);
        return new StackKey(EntityType.getKey(mob.getType()), stripIgnored(full));
    }

    /**
     * Serializes {@code mob} to NBT via the real entity save path, with nothing
     * stripped.
     *
     * <p>Entity serialization moved off {@code Entity.saveWithoutId(CompoundTag)} onto
     * the ValueInput/ValueOutput abstraction in the 1.21.6 era; the {@code CompoundTag}
     * overload no longer exists in 26.2 (verified against the deobfuscated jar - {@code
     * Entity} declares only {@code saveWithoutId(ValueOutput)}). {@code TagValueOutput}
     * is the NBT-backed {@code ValueOutput}, and its {@code buildResult()} reads the
     * {@code CompoundTag} back off it once saving is done.
     *
     * <p>{@code createWithContext} (not {@code createWithoutContext}) is required, not
     * a stylistic choice. Fields backed by a datapack registry - a Cow's {@code
     * Holder<CowVariant>}, an enchanted item's {@code Holder<Enchantment>} - are written
     * through codecs (e.g. {@code RegistryFixedCodec}) that only resolve against {@code
     * RegistryOps}; {@code createWithoutContext} backs the output with plain {@code
     * NbtOps} instead. Verified against the jar: {@code RegistryFixedCodec.encode}
     * returns a bare {@code DataResult.error} with no partial value when {@code ops}
     * isn't a {@code RegistryOps}, and {@code ValueOutput.store()} has nothing to fall
     * back to on such an error, so a silently-discarding reporter would drop the
     * *entire* field being stored - not just the unencodable part - never reaching the
     * tag. For equipment that means the whole {@code "equipment"} key vanishing
     * whenever any piece carries an enchantment, which would make two differently
     * enchanted mobs compare equal. Confirmed empirically by temporarily switching this
     * method to {@code createWithoutContext}: {@code StackKeyFactoryGameTest
     * #differentlyEnchantedEquipmentIsNotTreatedAsIdentical} then fails while every
     * other test there still passes. The provider costs nothing extra to obtain:
     * {@code mob.level()} is non-null by construction ({@code Entity}'s package is
     * {@code org.jspecify.annotations.NullMarked}; {@code Entity.level}/{@code level()}
     * /{@code setLevel(Level)} all carry no {@code @Nullable}, unlike e.g. the
     * neighbouring {@code vehicle} field, which does; the field is set exactly once,
     * from the {@code Entity(EntityType, Level)} constructor's non-nullable parameter,
     * and never reassigned to null) - and every {@code Level} carries its {@code
     * RegistryAccess} (which implements {@code HolderLookup.Provider}).
     *
     * <p>Visible for testing: {@code StackKeyFactoryGameTest} uses this directly to
     * check that specific keys {@link #IGNORED_KEYS} relies on actually exist in real
     * serialized NBT, which {@link #of}'s already-stripped result cannot show.
     */
    public static CompoundTag rawSerialize(Mob mob) {
        ProblemReporter.Collector problems = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(problems, mob.level().registryAccess());
        mob.saveWithoutId(output);
        CompoundTag full = output.buildResult();

        // A reporter that silently discarded failures here would recreate, for any
        // *other* codec that ever fails under registry context (another mod's, or a
        // future Mojang change), the exact "field silently vanishes, different mobs
        // compare equal" failure this class exists to prevent - just with no trace of
        // it happening. This class is the mod's central safety mechanism, so it must be
        // loud when its own assumptions break, even though that path is untested here
        // (nothing in this codebase can currently make a codec fail under context).
        if (!problems.isEmpty()) {
            Mobtimizer.LOGGER.warn(
                    "StackKeyFactory failed to fully serialize a {} for stack identity; "
                            + "it may now wrongly compare equal to a mob it should not:\n{}",
                    EntityType.getKey(mob.getType()), problems.getReport());
        }

        return full;
    }

    /** Visible for testing. */
    public static CompoundTag stripIgnored(CompoundTag tag) {
        CompoundTag copy = tag.copy();
        for (String key : IGNORED_KEYS) {
            copy.remove(key);
        }
        return copy;
    }
}
