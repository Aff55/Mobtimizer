package com.mobtimizer.identity;

import com.mobtimizer.Mobtimizer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
     * <p><b>{@code sound_variant}</b> was found while building the realistic-spawn
     * gametest coverage for the {@code attributes} fix below - not part of that fix
     * itself, but the same class of bug and caught by the very same missing-{@code
     * finalizeSpawn} gap. {@code Cow.finalizeSpawn} calls {@code
     * CowSoundVariants.pickRandomSoundVariant(RegistryAccess, RandomSource)} and stores
     * the result in this flat, synced-entity-data-backed key - confirmed by disassembly,
     * and independently by two realistically-spawned cows in the same biome variant
     * ending up with different {@code sound_variant} values ({@code minecraft:classic}
     * vs {@code minecraft:moody}). It picks which ambient/hurt/death/step sound set the
     * cow uses - cosmetic only, no gameplay effect - so it belongs in this list on the
     * same grounds as {@code CustomName}. Left out, it would have silently broken the
     * exact realistic-spawn merge test this fix set out to add. Not confirmed for any
     * other mob type; if a future phase adds realistic-spawn testing for a species with
     * its own sound-variant-style mechanic, check for the same pattern rather than
     * assuming this one entry covers it.
     *
     * <p><b>{@code LeftHanded}</b> was found the same way, one build later: {@code
     * Mob.finalizeSpawn} itself - the same method that adds {@code random_spawn_bonus} -
     * unconditionally rolls {@code random.nextFloat() < 0.05f} and calls {@code
     * setLeftHanded} with the result (confirmed by disassembly). At a 5% chance per mob,
     * two independently-spawned mobs mismatch roughly 9.5% of the time - rare enough that
     * an early manual run of the realistic-spawn gametests below passed clean, then a
     * later run failed on exactly this field with no other change. Purely cosmetic (which
     * arm a mob favors in its attack animation), so it belongs here on the same grounds as
     * {@code sound_variant}. Left out, every realistic-spawn test in this file would have
     * been flaky at roughly a 1-in-10 rate instead of reliably green.
     *
     * <p><b>{@code fabric:attachments} and {@code attributes} are handled separately from
     * this flat list</b> - see {@link #FABRIC_ATTACHMENTS_KEY}/{@link #stripOwnAttachments}
     * and {@link #ATTRIBUTES_KEY}/{@link #stripRandomSpawnAttributeNoise} below - because
     * both need surgery inside a nested list or compound, not a top-level removal. Neither
     * is named here.
     */
    public static final Set<String> IGNORED_KEYS = Set.of(
            "UUID", "Pos", "Motion", "Rotation", "OnGround", "fall_distance",
            "HurtTime", "last_hurt_by_player", "last_hurt_by_player_memory_time",
            "last_hurt_by_mob", "ticks_since_last_hurt_by_mob", "DeathTime", "Health",
            "Air", "Fire", "PortalCooldown", "TicksFrozen", "Brain",
            "Age", "ForcedAge", "InLove", "LoveCause", "Sheared",
            "CustomName", "CustomNameVisible", "sound_variant", "LeftHanded"
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

    /**
     * The NBT key {@code LivingEntity} writes its {@code AttributeMap} under - a list of
     * {@code {id, base, modifiers: [{id, amount, operation}]}} compounds, one per
     * registered attribute. See {@link #stripRandomSpawnAttributeNoise} for why this
     * needs the same nested surgery as {@link #FABRIC_ATTACHMENTS_KEY}, not a flat
     * removal or a blanket ignore.
     */
    public static final String ATTRIBUTES_KEY = "attributes";

    /**
     * Modifier ids Mojang applies at spawn time with a per-mob random {@code amount},
     * found by disassembling {@code Mob.finalizeSpawn} and {@code
     * Zombie.handleAttributes} rather than guessed - see {@link
     * #stripRandomSpawnAttributeNoise}'s Javadoc for the full account of where each one
     * comes from and under what condition.
     */
    private static final Set<String> RANDOM_SPAWN_MODIFIER_IDS = Set.of(
            "minecraft:random_spawn_bonus", "minecraft:zombie_random_spawn_bonus", "minecraft:leader_zombie_bonus"
    );

    /**
     * The one random-spawn case found that is not expressed as a modifier at all -
     * {@code Zombie.randomizeReinforcementsChance()} calls {@code
     * AttributeInstance.setBaseValue} directly. See {@link
     * #stripRandomSpawnAttributeNoise}. The registry id, confirmed against real
     * serialized NBT, is {@code minecraft:spawn_reinforcements} - note this does not
     * match the Java field name {@code Attributes.SPAWN_REINFORCEMENTS_CHANCE}, which is
     * exactly why this was verified against a real dump rather than assumed from the
     * constant's name.
     */
    private static final String SPAWN_REINFORCEMENTS_ATTRIBUTE_ID = "minecraft:spawn_reinforcements";

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
     * {@link #FABRIC_ATTACHMENTS_KEY} down to this mod's own entries via {@link
     * #stripOwnAttachments}, then narrows {@link #ATTRIBUTES_KEY} down to non-random-spawn
     * modifiers via {@link #stripRandomSpawnAttributeNoise}.
     */
    public static CompoundTag stripIgnored(CompoundTag tag) {
        CompoundTag copy = tag.copy();
        for (String key : IGNORED_KEYS) {
            copy.remove(key);
        }
        stripOwnAttachments(copy);
        stripRandomSpawnAttributeNoise(copy);
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

    /**
     * Removes only Mojang's own random-spawn attribute noise from {@link
     * #ATTRIBUTES_KEY}, leaving every base value and every other modifier - including
     * any other mod's - identity-bearing, per the same reasoning as {@link
     * #stripOwnAttachments}: a blanket ignore of the whole field was considered and
     * rejected, since it would let a genuinely buffed mob (a levelling mod, a difficulty
     * mod) silently merge into a plain stack and lose its buff.
     *
     * <p><b>Found by a live play-test, not by this project's own gametest suite.</b> A
     * user reported that naturally-spawned adult cows and sheep never merged with
     * anything, while player-bred babies merged fine. Every naturally-spawned mob's
     * {@code attributes} list carries a {@code minecraft:random_spawn_bonus} modifier on
     * {@code minecraft:follow_range} with an {@code amount} that is a fresh random double
     * on every spawn - confirmed by disassembling {@code Mob.finalizeSpawn}: {@code
     * RandomSource.triangle(0, 0.11485)}, added via {@code
     * AttributeInstance.addPermanentModifier} unless already present. Since that field was
     * never in {@link #IGNORED_KEYS}, every naturally-spawned mob had a unique identity
     * key and could never merge with anything else. Bred babies go through {@code
     * Animal.finalizeSpawnChildFromBreeding}, which never calls {@code finalizeSpawn} at
     * all (confirmed by disassembly - it only calls {@code setAge}), so they never receive
     * the modifier and matched each other - exactly the reported symptom. This project's
     * own gametest suite could not have caught it: {@code GameTestHelper} spawning never
     * calls {@code finalizeSpawn} either, so every test mob was structurally as
     * unrepresentative as a bred baby. Task 4's original review flagged {@code
     * attributes} as unignored and dismissed the risk with "freshly-spawned vanilla mobs
     * pack to an empty attribute list" - true in the harness, false in a world.
     * {@code StackKeyFactoryGameTest#realisticallySpawnedCowsWithRandomAttributeNoiseStillMerge}
     * and {@code #realisticallySpawnedZombiesWithRandomAttributeNoiseStillMerge} close that gap
     * by calling {@code finalizeSpawn} explicitly, the same way {@link
     * com.mobtimizer.merge.MergeScanner} and every real spawn path in a live world does.
     *
     * <p><b>The full list of random-spawn modifier ids, found by disassembly of {@code
     * Mob.finalizeSpawn} and {@code Zombie.handleAttributes}/{@code
     * Zombie.randomizeReinforcementsChance} - not guessed:</b>
     * <ul>
     *   <li>{@code minecraft:random_spawn_bonus} - applied by the base {@code
     *       Mob.finalizeSpawn} to every mob's {@code minecraft:follow_range}
     *       (unconditionally, guarded only against double-adding on reload). {@code
     *       Zombie.handleAttributes} separately reuses this exact same id on {@code
     *       minecraft:knockback_resistance}, unconditionally, for every Zombie (and its
     *       subclasses, which do not override {@code handleAttributes}).
     *   <li>{@code minecraft:zombie_random_spawn_bonus} - Zombie-only, applied to {@code
     *       minecraft:follow_range}, conditionally (only when {@code
     *       random.nextDouble() * 1.5 * difficultyScaler > 1.0}).
     *   <li>{@code minecraft:leader_zombie_bonus} - Zombie-only, applied to both {@code
     *       minecraft:spawn_reinforcements} and {@code minecraft:max_health},
     *       conditionally (the "leader zombie" roll, up to a 5% chance scaled by
     *       difficulty).
     * </ul>
     *
     * <p><b>One case found that is not a modifier at all, and so cannot be stripped by
     * id the same way.</b> {@code Zombie.randomizeReinforcementsChance()} - called
     * unconditionally from {@code handleAttributes} - calls {@code
     * AttributeInstance.setBaseValue(random.nextDouble() * 0.1)} directly on {@code
     * minecraft:spawn_reinforcements}. That mutates the attribute's own {@code base}
     * field, not a modifier, so a naturally-spawned zombie's {@code
     * minecraft:spawn_reinforcements} entry is unique to it with no modifier id to key a
     * strip on - confirmed against real serialized NBT, where that entry is exactly
     * {@code {id, base}} with no {@code modifiers} key at all in the common case. Since
     * this is still one of Mojang's own random-spawn values, not a third party's, {@link
     * #SPAWN_REINFORCEMENTS_ATTRIBUTE_ID}'s {@code base} field is removed by this method
     * too - narrower than ignoring the whole entry (any other mod's modifier on this same
     * attribute, if one is ever added, still survives the pass below unless its id is
     * also in {@link #RANDOM_SPAWN_MODIFIER_IDS}).
     *
     * <p><b>The empty-list trap, same shape as {@link #stripOwnAttachments}'s, one level
     * deeper - plus a second trap underneath it.</b> A fresh {@code AttributeInstance}
     * with no permanent modifiers writes no {@code modifiers} key at all, so once the
     * last random-spawn modifier is removed from an attribute's {@code modifiers} list,
     * that key must be removed entirely too, not left behind as an empty list.
     *
     * <p>That alone is not enough, though - confirmed by deliberately reproducing the
     * failure directly: a never-touched zombie's raw {@code attributes} list is {@code []}
     * (empty - no {@code minecraft:max_health} entry at all, since Mojang only writes an
     * attribute entry when it currently carries a modifier or had its base explicitly set,
     * never merely because a mob's construction-time base differs from some other
     * default - confirmed the same way for {@code minecraft:follow_range} on both Cow
     * (base 16) and Zombie (base 35), which are <em>also</em> both absent when
     * untouched). But a zombie that was given a {@code minecraft:leader_zombie_bonus}
     * modifier on {@code minecraft:max_health} - the real, if rare, "leader zombie" spawn
     * roll - and then had that one modifier stripped by the loop above ends up with
     * {@code {id: "minecraft:max_health", base: 20.0}}: a whole extra <em>entry</em> a
     * never-touched zombie simply does not have, even after its only modifier is gone.
     * Leaving that residual entry behind would reproduce the original cap in a third,
     * still subtler form - now keyed on "was this mob ever the rare kind that picks up any
     * modifier here," rather than on the modifier's random amount.
     *
     * <p>So an attribute entry is dropped <em>entirely</em> once nothing remains that could
     * have justified Mojang writing it in the first place: no {@code modifiers} key
     * survives, <em>and</em> the only reason it might have existed - a {@code modifiers}
     * list that is now fully stripped, or (for {@link #SPAWN_REINFORCEMENTS_ATTRIBUTE_ID})
     * its own explicitly-set {@code base}, now removed - has been eliminated by this
     * method itself. An attribute that never had a {@code modifiers} key at all and is not
     * {@code spawn_reinforcements} is left completely untouched, entry and all: this
     * method has no way to tell a bare {@code {id, base}} apart from a real, deliberate
     * third-party {@code setBaseValue} call on some unrelated attribute (the coordinator's
     * own confirmed example - a {@code minecraft:movement_speed} entry with a base and no
     * modifiers, on an attribute this method never touches), so it never removes one on
     * the strength of a guess. The one place this stays an accepted, narrow trade-off
     * (same category as {@code Health}'s in {@link #IGNORED_KEYS}, not the same category
     * as the blanket {@code fabric:attachments} mistake): a third party that both adds a
     * genuine base change <em>and</em> a modifier to the exact same attribute this method
     * already knows is random-spawn-touched (i.e. one of {@code follow_range}, {@code
     * knockback_resistance}, {@code max_health}, {@code spawn_reinforcements}) would have
     * that base change dropped along with the entry. Every case actually found and tested
     * clears that bar. {@code
     * StackKeyFactoryGameTest#aStrippedLeaderZombieBonusConvergesWithAMobThatNeverHadOne}
     * pins this exact scenario down without needing the rare random roll that would
     * otherwise make a gametest for it flaky.
     */
    private static void stripRandomSpawnAttributeNoise(CompoundTag tag) {
        tag.getList(ATTRIBUTES_KEY).ifPresent(attributes -> {
            // Iterate backwards: this loop can remove entries by index, and doing so
            // while walking forward would skip the entry that shifts into a just-vacated
            // slot. Walking backward means every not-yet-visited index is unaffected by
            // a later removal.
            for (int i = attributes.size() - 1; i >= 0; i--) {
                CompoundTag attribute = attributes.getCompoundOrEmpty(i);
                boolean isSpawnReinforcements =
                        SPAWN_REINFORCEMENTS_ATTRIBUTE_ID.equals(attribute.getString("id").orElse(""));
                boolean hadModifiers = attribute.contains("modifiers");

                if (isSpawnReinforcements) {
                    attribute.remove("base");
                }

                boolean modifiersSurvive = false;
                if (hadModifiers) {
                    ListTag modifiers = attribute.getList("modifiers").orElseGet(ListTag::new);
                    ListTag kept = new ListTag();
                    for (int j = 0; j < modifiers.size(); j++) {
                        CompoundTag modifier = modifiers.getCompoundOrEmpty(j);
                        if (!RANDOM_SPAWN_MODIFIER_IDS.contains(modifier.getString("id").orElse(""))) {
                            kept.add(modifier);
                        }
                    }

                    if (kept.isEmpty()) {
                        attribute.remove("modifiers");
                    } else {
                        attribute.put("modifiers", kept);
                        modifiersSurvive = true;
                    }
                }

                if (!modifiersSurvive && (isSpawnReinforcements || hadModifiers)) {
                    attributes.remove(i);
                } else {
                    attributes.set(i, attribute);
                }
            }

            tag.put(ATTRIBUTES_KEY, attributes);
        });
    }
}
