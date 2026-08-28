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
     *
     * <p><b>{@code fabric:attachments} is handled separately from this flat list</b> - see
     * {@link #FABRIC_ATTACHMENTS_KEY} and {@link #stripOwnAttachments} below - because it
     * needs surgery inside a nested compound, not a top-level removal. It is not named
     * here.
     */
    public static final Set<String> IGNORED_KEYS = Set.of(
            "UUID", "Pos", "Motion", "Rotation", "OnGround", "fall_distance",
            "HurtTime", "last_hurt_by_player", "last_hurt_by_player_memory_time",
            "last_hurt_by_mob", "ticks_since_last_hurt_by_mob", "DeathTime", "Health",
            "Air", "Fire", "PortalCooldown", "TicksFrozen", "Brain",
            "Age", "ForcedAge", "InLove", "LoveCause", "Sheared",
            "CustomName", "CustomNameVisible"
    );

    /**
     * The nested compound Fabric's attachment API persists every mod's persistent
     * attachments under, keyed inside by each attachment's own id (e.g. {@code
     * mobtimizer:stack}). Confirmed by disassembly of {@code
     * AttachmentSerializingImpl.serializeAttachmentData} in {@code
     * fabric-data-attachment-api-v1} (wired into {@code Entity.saveWithoutId} via a
     * Mixin), and independently by diffing a plain mob's raw serialized tag against the
     * same mob's tag right after it gains its first stack member: the key is entirely
     * absent until then. See {@link #stripOwnAttachments} for why this cannot just be
     * added to {@link #IGNORED_KEYS} as a flat entry.
     */
    public static final String FABRIC_ATTACHMENTS_KEY = "fabric:attachments";

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

    /**
     * Visible for testing. Strips every flat {@link #IGNORED_KEYS} entry, then narrows
     * {@link #FABRIC_ATTACHMENTS_KEY} down to this mod's own entries via
     * {@link #stripOwnAttachments}.
     */
    public static CompoundTag stripIgnored(CompoundTag tag) {
        CompoundTag copy = tag.copy();
        for (String key : IGNORED_KEYS) {
            copy.remove(key);
        }
        stripOwnAttachments(copy);
        return copy;
    }

    /**
     * Removes only Mobtimizer's own entries from the nested {@link
     * #FABRIC_ATTACHMENTS_KEY} compound, leaving every other mod's persistent
     * attachments in place so they still participate in stack identity comparison.
     *
     * <p>An earlier version of this fix stripped the whole {@code fabric:attachments}
     * key. That over-corrected: it silently exempted <em>any</em> other mod's persistent
     * attachment from stack identity too, directly contradicting this class's own
     * contract at the top of the file - "anything not named here must match exactly...
     * including any field added by another mod." A levelling mod, a taming mod, a
     * variant mod - anything storing identity-relevant state in a Fabric attachment
     * rather than raw NBT - would have that difference silently ignored, and two mobs
     * that must not merge would merge. This mod exists specifically to run on heavily
     * modded servers, so that is exactly the environment where it would bite.
     * {@code StackKeyFactoryGameTest#thirdPartyModAttachmentStillBlocksMerging} pins this
     * down directly: a mob carrying an attachment registered under a namespace that is
     * deliberately not {@code Mobtimizer.MOD_ID} must not compare equal to an otherwise
     * identical mob that lacks it.
     *
     * <p>Removal is keyed off {@link Mobtimizer#MOD_ID} rather than naming {@code
     * mobtimizer:stack}/{@code mobtimizer:frozen} individually in a third place (the
     * other two being {@link com.mobtimizer.MobtimizerAttachments} itself and this
     * class's own {@code IGNORED_KEYS} Javadoc before this fix). Every attachment this
     * mod registers goes through {@link Mobtimizer#id}, which always produces an id
     * namespaced {@code mobtimizer}, so any attachment phase 3 adds is excluded
     * automatically by this same prefix check with no further change here.
     *
     * <p><b>The empty-compound trap.</b> If removing our own keys empties the nested
     * compound entirely - true today, since a mob is never both a host and a frozen
     * member at once - the {@link #FABRIC_ATTACHMENTS_KEY} entry itself must also be
     * removed, not left behind as an empty compound. Leaving it would reproduce the
     * original two-mob cap in a subtler form: a host carrying only this mod's own
     * attachments would end up with an empty {@code fabric:attachments} key that a loose,
     * never-touched mob simply does not have, so the two would still compare unequal for
     * a reason that has nothing to do with any third-party mod.
     * {@code StackKeyFactoryGameTest#explicitlySetAttachmentDoesNotBlockMerging} pins
     * this exact case down.
     */
    private static void stripOwnAttachments(CompoundTag tag) {
        tag.getCompound(FABRIC_ATTACHMENTS_KEY).ifPresent(attachments -> {
            String ownPrefix = Mobtimizer.MOD_ID + ":";
            for (String key : Set.copyOf(attachments.keySet())) {
                if (key.startsWith(ownPrefix)) {
                    attachments.remove(key);
                }
            }

            if (attachments.isEmpty()) {
                tag.remove(FABRIC_ATTACHMENTS_KEY);
            } else {
                tag.put(FABRIC_ATTACHMENTS_KEY, attachments);
            }
        });
    }
}
