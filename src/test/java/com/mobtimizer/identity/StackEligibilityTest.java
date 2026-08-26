package com.mobtimizer.identity;

import com.mobtimizer.config.MobtimizerConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackEligibilityTest {
    @BeforeAll
    static void beforeAll() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void denylistBlocksListedTypes() {
        MobtimizerConfig.Entities entities = new MobtimizerConfig.Entities();
        assertFalse(entities.isAllowed(EntityTypes.VILLAGER));
        assertTrue(entities.isAllowed(EntityTypes.COW));
    }

    @Test
    void allowlistModeOnlyPermitsListedTypes() {
        MobtimizerConfig.Entities entities = new MobtimizerConfig.Entities();
        entities.mode = "ALLOWLIST";
        entities.allowlist.add("minecraft:cow");

        assertTrue(entities.isAllowed(EntityTypes.COW));
        assertFalse(entities.isAllowed(EntityTypes.PIG));
    }
}
