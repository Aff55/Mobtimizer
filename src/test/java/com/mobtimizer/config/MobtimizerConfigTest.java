package com.mobtimizer.config;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobtimizerConfigTest {
    @BeforeAll
    static void beforeAll() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void denylistModeRejectsDenylistedAndPermitsEverythingElse() {
        MobtimizerConfig.Entities entities = new MobtimizerConfig.Entities();

        assertFalse(entities.isAllowed(EntityTypes.VILLAGER),
                "villager is denylisted by default");
        assertTrue(entities.isAllowed(EntityTypes.COW),
                "cow is not denylisted by default");
    }

    @Test
    void allowlistModePermitsOnlyWhatIsListed() {
        MobtimizerConfig.Entities entities = new MobtimizerConfig.Entities();
        entities.mode = "ALLOWLIST";
        entities.allowlist.add("minecraft:cow");

        assertTrue(entities.isAllowed(EntityTypes.COW),
                "cow is explicitly allowlisted");
        assertFalse(entities.isAllowed(EntityTypes.VILLAGER),
                "villager is not on the allowlist");
    }
}
