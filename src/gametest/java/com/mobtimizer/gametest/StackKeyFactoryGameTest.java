package com.mobtimizer.gametest;

import com.mobtimizer.identity.StackKeyFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

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
     * Every mob the convenience {@code GameTestHelper.spawn} methods create is marked
     * persistence-required by default; going through the builder to turn that off keeps
     * these mobs representative of ones that would actually reach the merge path.
     */
    private static <E extends Entity> E spawnPlain(GameTestHelper helper, EntityType<E> type, BlockPos pos) {
        return helper.spawnEntity(type, pos).requirePersistence(false).spawn();
    }

    @GameTest
    public void identicalMobsProduceEqualKeys(GameTestHelper helper) {
        Zombie a = spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        Zombie b = spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());

        helper.assertTrue(StackKeyFactory.of(a).equals(StackKeyFactory.of(b)),
                "two plain zombies should share a stack key despite differing UUID and position");
        helper.succeed();
    }

    @GameTest
    public void armoredMobDiffersFromPlainMob(GameTestHelper helper) {
        Zombie plain = spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        Zombie armored = spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());
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

        Zombie plain = spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        plain.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));

        Zombie enchanted = spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());
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

        Zombie withProtection = spawnPlain(helper, EntityTypes.ZOMBIE, POS);
        ItemStack protectionChestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
        protectionChestplate.enchant(protection, 1);
        withProtection.setItemSlot(EquipmentSlot.CHEST, protectionChestplate);

        Zombie withFireProtection = spawnPlain(helper, EntityTypes.ZOMBIE, POS.above());
        ItemStack fireProtectionChestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
        fireProtectionChestplate.enchant(fireProtection, 1);
        withFireProtection.setItemSlot(EquipmentSlot.CHEST, fireProtectionChestplate);

        helper.assertFalse(StackKeyFactory.of(withProtection).equals(StackKeyFactory.of(withFireProtection)),
                "two differently-enchanted chestplates must not share a key");
        helper.succeed();
    }

    @GameTest
    public void damagedMobStillMatchesFullHealthMob(GameTestHelper helper) {
        Cow full = spawnPlain(helper, EntityTypes.COW, POS);
        Cow damaged = spawnPlain(helper, EntityTypes.COW, POS.above());
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
        Cow airborne = spawnPlain(helper, EntityTypes.COW, POS);
        airborne.setOnGround(false);
        airborne.fallDistance = 5.0;

        Cow grounded = spawnPlain(helper, EntityTypes.COW, POS.above());
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
        Zombie attacker = spawnPlain(helper, EntityTypes.ZOMBIE, POS.above().above());

        Cow hitLongAgo = spawnPlain(helper, EntityTypes.COW, POS);
        hitLongAgo.setLastHurtByMob(attacker);
        hitLongAgo.tickCount += 100;

        Cow hitJustNow = spawnPlain(helper, EntityTypes.COW, POS.above());
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
        Cow cow = spawnPlain(helper, EntityTypes.COW, POS);
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
}
