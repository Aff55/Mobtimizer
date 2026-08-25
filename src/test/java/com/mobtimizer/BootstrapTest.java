package com.mobtimizer;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BootstrapTest {
    @BeforeAll
    static void beforeAll() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void modIdIsCorrect() {
        assertEquals("mobtimizer", Mobtimizer.MOD_ID);
    }

    @Test
    void registriesAreReachable() {
        assertEquals("cow", EntityType.getKey(EntityTypes.COW).getPath());
    }
}
