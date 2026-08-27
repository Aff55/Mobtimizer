package com.mobtimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Shared spawn helper for gametests.
 *
 * <p>The convenience {@link GameTestHelper#spawn} methods mark every mob they create
 * persistence-required by default, so test fixtures cannot despawn mid-test. Left in
 * place, that would make {@code StackEligibility#canStack} false for every mob spawned
 * that way regardless of which condition a test claims to isolate - including an
 * otherwise "plain" positive case - and would make spawned mobs unrepresentative of the
 * ones that actually reach the merge path. Going through the builder to turn
 * persistence off is what makes a spawned mob usable as a plain, stackable fixture.
 */
public final class GameTestMobs {
    private GameTestMobs() {}

    public static <E extends Entity> E spawnPlain(GameTestHelper helper, EntityType<E> type, BlockPos pos) {
        return helper.spawnEntity(type, pos).requirePersistence(false).spawn();
    }
}
