package com.mobtimizer;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mobtimizer implements ModInitializer {
    public static final String MOD_ID = "mobtimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ConfigManager.load(FabricLoader.getInstance().getConfigDir().resolve("mobtimizer.json"));
        MobtimizerAttachments.register();
        Dormancy.register();
        LOGGER.info("Mobtimizer initialising");
    }
}
