package com.mobtimizer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {
    @Test
    void missingFileWritesDefaults(@TempDir Path dir) {
        Path file = dir.resolve("mobtimizer.json");
        MobtimizerConfig config = ConfigManager.loadFrom(file);

        assertTrue(Files.exists(file), "a missing config should be created on disk");
        assertEquals(4, config.merge.crowdThreshold);
        assertEquals(8.0, config.merge.radius);
        assertFalse(config.display.alwaysVisible, "nameplates are hover-only by default");
    }

    @Test
    void partialFileKeepsDefaultsForMissingKeys(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mobtimizer.json");
        Files.writeString(file, "{\"merge\":{\"crowdThreshold\":9}}");

        MobtimizerConfig config = ConfigManager.loadFrom(file);

        assertEquals(9, config.merge.crowdThreshold, "explicit key should win");
        assertEquals(8.0, config.merge.radius, "omitted key should keep its default");
        assertEquals(512, config.storage.virtualSpillThreshold, "omitted section should keep defaults");
    }

    @Test
    void malformedFileFallsBackToDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mobtimizer.json");
        Files.writeString(file, "{ this is not json");

        MobtimizerConfig config = ConfigManager.loadFrom(file);

        assertEquals(4, config.merge.crowdThreshold);
    }
}
