package com.mobtimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

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

    /**
     * Like {@link #spawnPlain}, but also runs the mob through {@code
     * finalizeSpawn(EntitySpawnReason.NATURAL, ...)} - the call every real natural spawn
     * in a live world goes through, and which {@code GameTestHelper.spawnEntity} never
     * makes on its own. That gap is not cosmetic: {@code Mob.finalizeSpawn} (and, for
     * Zombie and its subclasses, {@code Zombie.handleAttributes}) attaches attribute
     * modifiers with a per-mob random {@code amount} - see {@code
     * StackKeyFactory#stripRandomSpawnAttributeNoise}'s Javadoc for the full list, found
     * by disassembly, not guessed. A mob from {@link #spawnPlain} alone never carries any
     * of that and so is exactly as unrepresentative of a real spawn as a player-bred
     * baby, which skips {@code finalizeSpawn} entirely - the precise gap that let a real
     * play-test bug (naturally-spawned adults never merging with anything) ship invisibly
     * through this project's entire existing gametest suite. Reach for this helper, not
     * {@code spawnPlain}, whenever a test's whole point is that two mobs should or should
     * not merge, so the fixture matches what a live world actually hands the mod.
     *
     * <p>Real {@code finalizeSpawn} logic can itself roll a mob's baby state (Zombie does,
     * independently of {@code Age}/{@code ForcedAge} - confirmed the hard way: an
     * automated test comparing two realistically-spawned zombies failed intermittently,
     * roughly 1 run in 4, until traced to exactly this). A test that needs two
     * definitely-same-age mobs should call {@code setBaby(...)} on the result explicitly
     * to pin that down, rather than assume this helper already controls for it.
     */
    public static <E extends Mob> E spawnRealistic(GameTestHelper helper, EntityType<E> type, BlockPos pos) {
        E mob = spawnPlain(helper, type, pos);
        mob.finalizeSpawn(helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
        return mob;
    }
}
