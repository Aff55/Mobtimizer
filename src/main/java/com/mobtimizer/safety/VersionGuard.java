package com.mobtimizer.safety;

import com.mobtimizer.Mobtimizer;
import com.mobtimizer.command.MobtimizerCommand;
import com.mobtimizer.config.ConfigManager;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unstacks everything when a world is opened on a different Minecraft version.
 *
 * <p>Fabric pins mods to a Minecraft version, so upgrading the game means removing the
 * mod to launch at all. Dormant members survive that on their own - keeping them as real
 * entities is precisely what makes removing the mod survivable - but this makes the
 * transition explicit rather than implicit, and covers virtual storage once phase 5 adds
 * it, where members are no longer real entities and could not survive on their own.
 *
 * <p>The marker file is read and written defensively. A world whose marker is missing,
 * unreadable or corrupt is treated as "no previous version recorded", which unstacks
 * nothing: failing to read the marker must never be worse than never having written one.
 * A failure to write it is likewise logged rather than thrown, since a server that
 * started successfully should not be brought down by a bookkeeping file.
 *
 * <p>{@code WorldVersion.name()} - not {@code getName()}, which does not exist in 26.2
 * (confirmed by disassembly: {@code WorldVersion} declares {@code id()} and
 * {@code name()}).
 */
public final class VersionGuard {
    private static final String FILE_NAME = "mobtimizer-version.txt";

    private VersionGuard() {}

    public static void onServerStarted(MinecraftServer server) {
        if (!ConfigManager.get().safety.autoUnstackOnVersionChange) return;

        String current = SharedConstants.getCurrentVersion().name();
        Path marker = server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);

        String previous = null;
        try {
            if (Files.exists(marker)) {
                previous = Files.readString(marker).trim();
            }
        } catch (Exception e) {
            Mobtimizer.LOGGER.warn("Could not read {}", marker, e);
        }

        if (previous != null && !previous.equals(current)) {
            Mobtimizer.LOGGER.info("World last opened on {}, now {} - unstacking everything", previous, current);
            for (var level : server.getAllLevels()) {
                int released = MobtimizerCommand.unstackAll(level);
                if (released > 0) {
                    Mobtimizer.LOGGER.info("Released {} mobs in {}", released, level.dimension().identifier());
                }
            }
        }

        try {
            Files.writeString(marker, current);
        } catch (Exception e) {
            Mobtimizer.LOGGER.warn("Could not write {}", marker, e);
        }
    }
}
