package com.mobtimizer.identity;

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
     */
    public static final Set<String> IGNORED_KEYS = Set.of(
            "UUID", "Pos", "Motion", "Rotation", "FallDistance",
            "HurtTime", "HurtByTimestamp", "DeathTime", "Health",
            "Air", "Fire", "PortalCooldown", "TicksFrozen", "Brain",
            "Age", "ForcedAge", "InLove", "LoveCause", "Sheared",
            "CustomName", "CustomNameVisible"
    );

    private StackKeyFactory() {}

    public static StackKey of(Mob mob) {
        // Entity serialization moved off Entity.saveWithoutId(CompoundTag) onto the
        // ValueInput/ValueOutput abstraction in the 1.21.6 era; the CompoundTag overload
        // no longer exists in 26.2 (verified against the deobfuscated jar - Entity
        // declares only saveWithoutId(ValueOutput)). TagValueOutput is the NBT-backed
        // ValueOutput, and its buildResult() reads the CompoundTag back off it once
        // saving is done.
        //
        // createWithContext (not createWithoutContext) is required, not a stylistic
        // choice. Fields backed by a datapack registry - a Cow's Holder<CowVariant>, an
        // enchanted item's Holder<Enchantment> - are written through codecs (e.g.
        // RegistryFixedCodec) that only resolve against RegistryOps; createWithoutContext
        // backs the output with plain NbtOps instead. Verified against the jar:
        // RegistryFixedCodec.encode returns a bare DataResult.error with no partial
        // value when ops isn't a RegistryOps, and ValueOutput.store() has nothing to
        // fall back to on such an error, so the DISCARDING reporter swallows it and the
        // *entire* field being stored - not just the unencodable part - silently never
        // reaches the tag. For equipment that means the whole "equipment" key vanishing
        // whenever any piece carries an enchantment, which would make two differently
        // enchanted mobs compare equal. Confirmed empirically by temporarily switching
        // this line to createWithoutContext: StackKeyFactoryGameTest
        // #differentlyEnchantedEquipmentIsNotTreatedAsIdentical then fails while every
        // other test here still passes. The provider costs nothing extra to obtain:
        // every Mob has a Level, and every Level carries its RegistryAccess (which
        // implements HolderLookup.Provider).
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mob.level().registryAccess());
        mob.saveWithoutId(output);
        CompoundTag full = output.buildResult();

        return new StackKey(EntityType.getKey(mob.getType()), stripIgnored(full));
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
