package com.mobtimizer.gametest;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.identity.StackKeyFactory;
import com.mobtimizer.stack.MobStack;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;
import java.util.UUID;

/**
 * Exercises {@link StackKeyFactory#of} against real spawned mobs.
 *
 * <p>{@code of} takes a live {@code Mob} and serializes it through the entity save
 * path, so the risk it exists to guard against - two mobs that must not merge
 * comparing equal, or two mobs that should merge being kept apart by transient state
 * - can only be reached with a real entity, not hand-built NBT. {@code
 * StackKeyFactoryTest} separately covers {@code StackKey} equality and {@code
 * stripIgnored} in isolation.
 */
public final class StackKeyFactoryGameTest {
    private static final BlockPos POS = new BlockPos(1, 2, 1);

    /**
     * Simulates a real third-party mod's own persistent attachment - same shape as
     * {@code MobtimizerAttachments.FROZEN} (a persistent {@code Boolean}), just
     * registered under a namespace that is deliberately not {@code Mobtimizer.MOD_ID} -
     * for {@link #thirdPartyModAttachmentStillBlocksMerging}. {@code AttachmentRegistry}
     * backs this with a plain, always-open {@code Map<Identifier, AttachmentType<?>>}
     * (confirmed by disassembly of {@code AttachmentRegistryImpl.register}: an
     * unconditional {@code Map.put}, no lifecycle gating), so creating it here in a
     * static field - rather than through a dedicated mod-init entrypoint like {@code
     * Mobtimizer.onInitialize} uses for the real attachments - is safe: it registers the
     * moment this test class is first loaded, which is always before any {@code @GameTest}
     * method on it runs.
     */
    private static final AttachmentType<Boolean> SIMULATED_THIRD_PARTY_ATTACHMENT =
            AttachmentRegistry.create(Identifier.fromNamespaceAndPath("thirdpartymod", "affinity"),
                    builder -> builder.persistent(Codec.BOOL).initializer(() -> Boolean.FALSE));

    @GameTest
    public void identicalMobsProduceEqualKeys(GameTestHelper helper) {
        Zombie a = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        Zombie b = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());

        helper.assertTrue(StackKeyFactory.of(a).equals(StackKeyFactory.of(b)),
                "two plain zombies should share a stack key despite differing UUID and position");
        helper.succeed();
    }

    @GameTest
    public void armoredMobDiffersFromPlainMob(GameTestHelper helper) {
        Zombie plain = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        Zombie armored = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());
        armored.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));

        helper.assertFalse(StackKeyFactory.of(plain).equals(StackKeyFactory.of(armored)),
                "a diamond-armoured zombie must not share a key with a plain zombie");
        helper.succeed();
    }

    /**
     * Item id alone already tells a diamond chestplate apart from no chestplate,
     * registry or no registry, so this pair does not by itself prove
     * {@code createWithContext} is necessary - see
     * {@link #differentlyEnchantedEquipmentIsNotTreatedAsIdentical} for the pair that
     * actually forces the choice. Kept anyway as the direct, obvious regression guard
     * for the scenario this task is named after.
     */
    @GameTest
    public void enchantedEquipmentDiffersFromUnenchantedEquipment(GameTestHelper helper) {
        Holder<Enchantment> protection = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.PROTECTION);

        Zombie plain = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        plain.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));

        Zombie enchanted = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());
        ItemStack enchantedChestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
        enchantedChestplate.enchant(protection, 1);
        enchanted.setItemSlot(EquipmentSlot.CHEST, enchantedChestplate);

        helper.assertFalse(StackKeyFactory.of(plain).equals(StackKeyFactory.of(enchanted)),
                "an enchanted chestplate must not share a key with an unenchanted one of the same item");
        helper.succeed();
    }

    /**
     * The pair that actually forces {@code createWithContext} over {@code
     * createWithoutContext} in {@link StackKeyFactory#of}. An enchantment is stored as
     * a {@code Holder<Enchantment>} resolved through a datapack registry
     * (backed by {@code RegistryFixedCodec}); encoding one without a
     * {@code HolderLookup.Provider} does not degrade gracefully to "just the id" - it
     * fails outright with no partial value, and because that failure propagates up
     * through the equipment map's codec it drops the entire {@code equipment} key from
     * the tag, not merely the enchantment. Two mobs enchanted two different ways would
     * both lose their whole {@code equipment} key that way and compare equal - a much
     * larger hole than one missing field. Verified directly: this test fails against
     * {@code createWithoutContext} and passes against {@code createWithContext}.
     */
    @GameTest
    public void differentlyEnchantedEquipmentIsNotTreatedAsIdentical(GameTestHelper helper) {
        Holder<Enchantment> protection = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.PROTECTION);
        Holder<Enchantment> fireProtection = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FIRE_PROTECTION);

        Zombie withProtection = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        ItemStack protectionChestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
        protectionChestplate.enchant(protection, 1);
        withProtection.setItemSlot(EquipmentSlot.CHEST, protectionChestplate);

        Zombie withFireProtection = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());
        ItemStack fireProtectionChestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
        fireProtectionChestplate.enchant(fireProtection, 1);
        withFireProtection.setItemSlot(EquipmentSlot.CHEST, fireProtectionChestplate);

        helper.assertFalse(StackKeyFactory.of(withProtection).equals(StackKeyFactory.of(withFireProtection)),
                "two differently-enchanted chestplates must not share a key");
        helper.succeed();
    }

    @GameTest
    public void damagedMobStillMatchesFullHealthMob(GameTestHelper helper) {
        Cow full = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow damaged = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        damaged.setHealth(1.0f);

        helper.assertTrue(StackKeyFactory.of(full).equals(StackKeyFactory.of(damaged)),
                "Health is on the ignore list: a damaged cow should still share a key with a full-health one");
        helper.succeed();
    }

    /**
     * Task 4 review Finding 1/4: {@code OnGround} was missing from {@code IGNORED_KEYS}
     * entirely, and {@code FallDistance} matched nothing real ({@code Entity} writes
     * {@code fall_distance}), so on a real farm - mobs constantly landing, jumping, or
     * standing on uneven terrain - two otherwise-identical mobs would routinely fail to
     * merge for no reason visible in any log. This is the test that would have caught
     * both: it sets the two real fields directly (no need to wait out real physics
     * ticks) and checks the keys still match.
     */
    @GameTest
    public void transientPhysicsStateDoesNotBlockMerging(GameTestHelper helper) {
        Cow airborne = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        airborne.setOnGround(false);
        airborne.fallDistance = 5.0;

        Cow grounded = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        grounded.setOnGround(true);
        grounded.fallDistance = 0.0;

        helper.assertTrue(StackKeyFactory.of(airborne).equals(StackKeyFactory.of(grounded)),
                "OnGround and fall distance are transient physics state and must not block merging");
        helper.succeed();
    }

    /**
     * Task 4 review Finding 1/4: the old single {@code HurtByTimestamp} ignore-list
     * entry matched nothing real; 26.2 instead writes {@code last_hurt_by_mob} plus
     * {@code ticks_since_last_hurt_by_mob}, computed fresh on every save as {@code
     * tickCount - lastHurtByMobTimestamp}. Left unignored, that value drifts by one on
     * every tick that passes after a hit, so a mob that has ever been attacked by
     * another mob could never again match a mob that has not - permanently, not just
     * for a moment. This test reproduces exactly that: both cows get hit by the same
     * attacker, but {@code hitLongAgo}'s clock is wound forward afterward so its
     * "ticks since" value is a real, large, different number from {@code hitJustNow}'s
     * zero - modelling two mobs hit at genuinely different moments in a running world,
     * not merely "hit vs. never hit."
     */
    @GameTest
    public void beingHitByAnotherMobAtDifferentTimesDoesNotBlockMerging(GameTestHelper helper) {
        Zombie attacker = GameTestMobs.spawnPlain(helper, EntityTypes.ZOMBIE, POS.above().above());

        Cow hitLongAgo = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        hitLongAgo.setLastHurtByMob(attacker);
        hitLongAgo.tickCount += 100;

        Cow hitJustNow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        hitJustNow.setLastHurtByMob(attacker);

        helper.assertTrue(hitLongAgo.tickCount != hitJustNow.tickCount,
                "setup sanity check: the two hits must be recorded at different simulated ticks");
        helper.assertTrue(StackKeyFactory.of(hitLongAgo).equals(StackKeyFactory.of(hitJustNow)),
                "two mobs each hit by another mob at a different time must still share a stack key");
        helper.succeed();
    }

    /**
     * Task 4 review Finding 4: {@code StackKeyFactoryTest#everyIgnoredFieldIsStripped}
     * can only prove {@code stripIgnored} removes strings that are already in {@code
     * IGNORED_KEYS} - it puts each entry onto the tag itself, so a wrong key name is
     * structurally invisible to it. This test instead serializes a real Cow through
     * {@link StackKeyFactory#rawSerialize} - the same, un-stripped path {@code of} uses
     * internally - and checks that every {@code IGNORED_KEYS} entry this Cow always
     * writes unconditionally is actually present under that exact name. A future
     * Minecraft update renaming one of these keys again fails loudly here instead of
     * quietly turning back into a no-op.
     *
     * <p>Deliberately excluded below, each for a reason that is itself specific state
     * this test does not set up:
     * <ul>
     *   <li>{@code LoveCause} - only written once the cow has been bred/fed.
     *   <li>{@code CustomName}/{@code CustomNameVisible} - only written once the mob has
     *       a custom name (and {@code StackEligibility.canStack} excludes named mobs
     *       from stacking entirely regardless).
     *   <li>{@code TicksFrozen} - only written once {@code getTicksFrozen() > 0}, i.e.
     *       after standing in powder snow.
     *   <li>{@code last_hurt_by_player}/{@code last_hurt_by_player_memory_time}/
     *       {@code last_hurt_by_mob}/{@code ticks_since_last_hurt_by_mob} - only written
     *       once the mob has been hit; covered instead by
     *       {@link #beingHitByAnotherMobAtDifferentTimesDoesNotBlockMerging}, which is
     *       the test that actually matters for this family (the bug was that the value
     *       drifts, not merely that the key can be absent).
     *   <li>{@code Sheared} - Sheep-specific; a Cow never writes it at all.
     * </ul>
     */
    @GameTest
    public void everyUnconditionalIgnoredKeyExistsInRealSerializedNbt(GameTestHelper helper) {
        Cow cow = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        CompoundTag raw = StackKeyFactory.rawSerialize(cow);

        List<String> unconditional = List.of(
                "UUID", "Pos", "Motion", "Rotation", "OnGround", "fall_distance",
                "HurtTime", "DeathTime", "Health", "Air", "Fire", "PortalCooldown",
                "Brain", "Age", "ForcedAge", "InLove"
        );
        for (String key : unconditional) {
            helper.assertTrue(StackKeyFactory.IGNORED_KEYS.contains(key),
                    "'" + key + "' is missing from IGNORED_KEYS");
            helper.assertTrue(raw.contains(key),
                    "'" + key + "' was not found in a real serialized Cow - this IGNORED_KEYS entry may be stale");
        }
        helper.succeed();
    }

    /**
     * Task 8 finding: a mob's raw serialized tag has no {@code fabric:attachments} key
     * at all until the first time something calls {@code setAttached} on it with an
     * explicit value - confirmed empirically by diffing a plain cow's tag against the
     * same cow's tag right after {@code StackManager.merge} gives it its first member.
     * Left unhandled, that key would have made a host's stack key change the instant it
     * gained its first member (a plain, never-touched mob never has the key), so every
     * later merge attempt into that same host would compare unequal and be refused
     * forever after - silently capping every stack at exactly two mobs, regardless of
     * species, variant or config. Sets the attachment directly here, bypassing {@code
     * DormantStore}/{@code StackManager} entirely, to isolate the regression to {@code
     * StackKeyFactory} alone.
     *
     * <p>Also pins the empty-compound trap in {@code stripOwnAttachments} explicitly,
     * not just implicitly through the final equality check: a mob carrying only this
     * mod's own attachments must have {@code fabric:attachments} disappear entirely once
     * stripped, not survive as an empty compound - an empty compound would still make
     * this mob's stripped tag differ from a plain mob's, which lacks the key altogether,
     * silently reproducing the original two-mob cap in a subtler, harder-to-diagnose form.
     */
    @GameTest
    public void explicitlySetAttachmentDoesNotBlockMerging(GameTestHelper helper) {
        Cow plain = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow withAttachment = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        withAttachment.setAttached(MobtimizerAttachments.STACK, MobStack.EMPTY.withMember(UUID.randomUUID()));

        helper.assertFalse(StackKeyFactory.rawSerialize(plain).contains(StackKeyFactory.FABRIC_ATTACHMENTS_KEY),
                "setup sanity check: a mob nothing has ever attached anything to must have no fabric:attachments key");
        helper.assertTrue(StackKeyFactory.rawSerialize(withAttachment).contains(StackKeyFactory.FABRIC_ATTACHMENTS_KEY),
                "setup sanity check: explicitly setting an attachment must actually produce a fabric:attachments key");

        CompoundTag strippedWithAttachment = StackKeyFactory.stripIgnored(StackKeyFactory.rawSerialize(withAttachment));
        helper.assertFalse(strippedWithAttachment.contains(StackKeyFactory.FABRIC_ATTACHMENTS_KEY),
                "a mob carrying only this mod's own attachments must have fabric:attachments removed entirely once "
                        + "stripped, not survive as an empty compound - an empty compound would still differ from a "
                        + "plain mob's stripped tag, which lacks the key altogether");

        helper.assertTrue(StackKeyFactory.of(plain).equals(StackKeyFactory.of(withAttachment)),
                "a mob with an explicitly-set STACK attachment must still share a key with an otherwise identical "
                        + "plain mob - otherwise a host could never accept a second member");
        helper.succeed();
    }

    /**
     * Task 8 review finding: the fix above must not overcorrect into stripping the
     * entire {@code fabric:attachments} compound - that would silently exempt any OTHER
     * mod's persistent attachment from stack identity too, directly contradicting this
     * class's own contract ("anything not named here must match exactly for two mobs to
     * merge, including any field added by another mod"). A levelling mod, a taming mod,
     * a variant mod - anything storing identity-relevant state in a Fabric attachment
     * rather than raw NBT - would have that difference silently ignored, and two mobs
     * that must not merge would merge; this mod exists specifically to run on heavily
     * modded servers, so that is exactly the environment where it would bite. Sets
     * {@link #SIMULATED_THIRD_PARTY_ATTACHMENT} on only one of two otherwise-identical
     * cows and asserts they do not share a key - the assertion proving third-party
     * attachments still participate in stack identity. Without this test, a future
     * simplification back to stripping the whole key would pass every other test here.
     */
    @GameTest
    public void thirdPartyModAttachmentStillBlocksMerging(GameTestHelper helper) {
        Cow plain = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS);
        Cow withThirdPartyAttachment = GameTestMobs.spawnPlain(helper, EntityTypes.COW, POS.above());
        withThirdPartyAttachment.setAttached(SIMULATED_THIRD_PARTY_ATTACHMENT, Boolean.TRUE);

        CompoundTag rawWithThirdParty = StackKeyFactory.rawSerialize(withThirdPartyAttachment);
        helper.assertTrue(rawWithThirdParty.getCompound(StackKeyFactory.FABRIC_ATTACHMENTS_KEY)
                        .map(attachments -> attachments.contains("thirdpartymod:affinity"))
                        .orElse(false),
                "setup sanity check: setting a third-party attachment must actually produce a nested entry for it");

        helper.assertFalse(StackKeyFactory.of(plain).equals(StackKeyFactory.of(withThirdPartyAttachment)),
                "a mob carrying another mod's persistent attachment must not share a key with an otherwise "
                        + "identical mob that lacks it - only Mobtimizer's own attachments may be excluded "
                        + "from stack identity");
        helper.succeed();
    }
}
