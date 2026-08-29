package com.mobtimizer.identity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StackKeyFactoryTest {
    private static StackKey key(String type, CompoundTag identity) {
        return new StackKey(Identifier.parse(type), identity);
    }

    private static CompoundTag attribute(String id, double base) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putDouble("base", base);
        return tag;
    }

    private static CompoundTag modifier(String id, double amount, String operation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putDouble("amount", amount);
        tag.putString("operation", operation);
        return tag;
    }

    private static CompoundTag withAttributes(CompoundTag... attributes) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag entry : attributes) {
            list.add(entry);
        }
        tag.put(StackKeyFactory.ATTRIBUTES_KEY, list);
        return tag;
    }

    @Test
    void identicalIdentityTagsAreEqual() {
        CompoundTag a = new CompoundTag();
        a.putString("variant", "temperate");
        CompoundTag b = new CompoundTag();
        b.putString("variant", "temperate");

        assertEquals(key("minecraft:cow", a), key("minecraft:cow", b));
        assertEquals(key("minecraft:cow", a).hashCode(), key("minecraft:cow", b).hashCode());
    }

    @Test
    void differingVariantIsNotEqual() {
        CompoundTag a = new CompoundTag();
        a.putString("variant", "temperate");
        CompoundTag b = new CompoundTag();
        b.putString("variant", "cold");

        assertNotEquals(key("minecraft:cow", a), key("minecraft:cow", b));
    }

    @Test
    void differingTypeIsNotEqual() {
        CompoundTag empty = new CompoundTag();
        assertNotEquals(key("minecraft:cow", empty), key("minecraft:pig", empty));
    }

    @Test
    void everyIgnoredFieldIsStripped() {
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "temperate");
        for (String ignored : StackKeyFactory.IGNORED_KEYS) {
            tag.putString(ignored, "whatever");
        }

        CompoundTag stripped = StackKeyFactory.stripIgnored(tag);

        assertEquals(1, stripped.size(), "only 'variant' should survive stripping");
        for (String ignored : StackKeyFactory.IGNORED_KEYS) {
            assertFalse(stripped.contains(ignored), ignored + " should have been stripped");
        }
    }

    /**
     * Play-test fix: a naturally-spawned mob's {@code minecraft:follow_range} always
     * carries a {@code minecraft:random_spawn_bonus} modifier with a per-mob random
     * amount (see {@link StackKeyFactory#stripRandomSpawnAttributeNoise}). Once that
     * modifier is the only thing in the list, the whole attribute entry must disappear -
     * not survive as a bare {@code {id, base}} - since a mob that was never touched at
     * all has no entry for it whatsoever.
     */
    @Test
    void randomSpawnModifierIsStrippedAndTheNowEmptyEntryDisappearsEntirely() {
        CompoundTag followRange = attribute("minecraft:follow_range", 16.0);
        ListTag modifiers = new ListTag();
        modifiers.add(modifier("minecraft:random_spawn_bonus", 0.0512, "add_multiplied_base"));
        followRange.put("modifiers", modifiers);

        ListTag stripped = StackKeyFactory.stripIgnored(withAttributes(followRange))
                .getList(StackKeyFactory.ATTRIBUTES_KEY).orElseThrow();

        assertTrue(stripped.isEmpty(), "an attribute whose only modifier was random-spawn noise must vanish "
                + "entirely, not survive as a bare {id, base}");
    }

    /** The convergence property itself, stated directly rather than only implied. */
    @Test
    void aStrippedRandomSpawnModifierConvergesWithNeverHavingHadTheAttributeAtAll() {
        CompoundTag followRangeWithNoise = attribute("minecraft:follow_range", 16.0);
        ListTag modifiers = new ListTag();
        modifiers.add(modifier("minecraft:random_spawn_bonus", -0.0317, "add_multiplied_base"));
        followRangeWithNoise.put("modifiers", modifiers);

        CompoundTag neverTouched = withAttributes();

        assertEquals(StackKeyFactory.stripIgnored(neverTouched), StackKeyFactory.stripIgnored(withAttributes(followRangeWithNoise)));
    }

    /**
     * The narrowing itself: the fix must not become a repeat of the {@code
     * fabric:attachments} over-broad mistake. A genuinely third-party modifier on the
     * same attribute Mojang's own randomness touches must survive.
     */
    @Test
    void nonRandomModifierOnAnAttributeSurvivesStripping() {
        CompoundTag maxHealth = attribute("minecraft:max_health", 20.0);
        ListTag modifiers = new ListTag();
        modifiers.add(modifier("othermod:level_up_bonus", 10.0, "add_value"));
        maxHealth.put("modifiers", modifiers);

        ListTag stripped = StackKeyFactory.stripIgnored(withAttributes(maxHealth))
                .getList(StackKeyFactory.ATTRIBUTES_KEY).orElseThrow();

        assertEquals(1, stripped.size(), "a genuinely third-party modifier must keep its whole attribute entry");
        assertTrue(stripped.getCompound(0).orElseThrow().contains("modifiers"),
                "the surviving third-party modifier's list must not be dropped");
    }

    /** A random-spawn modifier sitting alongside a real one must remove only the former. */
    @Test
    void mixedRandomAndThirdPartyModifiersKeepOnlyTheThirdPartyOne() {
        CompoundTag maxHealth = attribute("minecraft:max_health", 20.0);
        ListTag modifiers = new ListTag();
        modifiers.add(modifier("minecraft:leader_zombie_bonus", 3.0, "add_multiplied_total"));
        modifiers.add(modifier("othermod:level_up_bonus", 10.0, "add_value"));
        maxHealth.put("modifiers", modifiers);

        ListTag strippedAttributes = StackKeyFactory.stripIgnored(withAttributes(maxHealth))
                .getList(StackKeyFactory.ATTRIBUTES_KEY).orElseThrow();
        ListTag survivingModifiers = strippedAttributes.getCompound(0).orElseThrow()
                .getList("modifiers").orElseThrow();

        assertEquals(1, survivingModifiers.size(), "only the random-spawn modifier should have been removed");
        assertEquals("othermod:level_up_bonus",
                survivingModifiers.getCompound(0).orElseThrow().getString("id").orElse(null));
    }

    /**
     * {@code Zombie.randomizeReinforcementsChance()} sets {@code
     * minecraft:spawn_reinforcements}'s own {@code base} directly - not a modifier - so
     * it needs its own removal, not just the modifier-list pass. With no other modifier
     * on it, the entry must disappear entirely once its randomized base is gone.
     */
    @Test
    void spawnReinforcementsBaseIsRemovedAndTheEntryDisappearsWithNoOtherModifiers() {
        CompoundTag tag = withAttributes(attribute("minecraft:spawn_reinforcements", 0.0731));

        ListTag stripped = StackKeyFactory.stripIgnored(tag).getList(StackKeyFactory.ATTRIBUTES_KEY).orElseThrow();

        assertTrue(stripped.isEmpty(), "spawn_reinforcements' randomized base must be removed, and the now-empty "
                + "entry must disappear entirely rather than survive as a bare {id}");
    }

    /**
     * The other half of the narrowing: an attribute with a {@code base} and no {@code
     * modifiers} key at all - the shape of a deliberate third-party {@code
     * setBaseValue} call unrelated to any known random-spawn id - must be left
     * completely alone. This method cannot tell that shape apart from a construction-time
     * default from the NBT alone, so it never touches one on the strength of a guess.
     */
    @Test
    void aBareBaseOnlyAttributeWithNoModifiersIsLeftCompletelyAlone() {
        CompoundTag tag = withAttributes(attribute("minecraft:movement_speed", 0.3));

        ListTag stripped = StackKeyFactory.stripIgnored(tag).getList(StackKeyFactory.ATTRIBUTES_KEY).orElseThrow();

        assertEquals(1, stripped.size(), "an attribute with a base and no modifiers list must never be removed");
        assertEquals(0.3, stripped.getCompound(0).orElseThrow().getDouble("base").orElse(-1.0), 0.0001);
    }
}
