package com.mobtimizer.gametest;

import com.mobtimizer.identity.StackKeyFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
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
}
