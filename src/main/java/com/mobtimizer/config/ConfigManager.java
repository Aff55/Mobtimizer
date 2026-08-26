package com.mobtimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mobtimizer.Mobtimizer;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile MobtimizerConfig current = new MobtimizerConfig();
    private static Path path;

    private ConfigManager() {}

    public static MobtimizerConfig get() {
        return current;
    }

    public static void load(Path configFile) {
        path = configFile;
        current = loadFrom(configFile);
    }

    public static void reload() {
        if (path != null) {
            current = loadFrom(path);
        }
    }

    /** Visible for tests: reads {@code file}, writing defaults if absent or unreadable. */
    public static MobtimizerConfig loadFrom(Path file) {
        MobtimizerConfig config = new MobtimizerConfig();

        if (Files.exists(file)) {
            try {
                // Gson leaves fields untouched when the JSON omits them, so anything
                // missing keeps the default already assigned in the field initialiser.
                MobtimizerConfig parsed = GSON.fromJson(Files.readString(file), MobtimizerConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } catch (Exception e) {
                Mobtimizer.LOGGER.warn("Could not read {}, using defaults", file, e);
                return config;
            }
        }

        save(file, config);
        return config;
    }

    private static void save(Path file, MobtimizerConfig config) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, GSON.toJson(config));
        } catch (Exception e) {
            Mobtimizer.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
