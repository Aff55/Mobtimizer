package com.mobtimizer.identity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StackKeyFactoryTest {
    private static StackKey key(String type, CompoundTag identity) {
        return new StackKey(Identifier.parse(type), identity);
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
}
