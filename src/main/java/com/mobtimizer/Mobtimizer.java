package com.mobtimizer;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.freeze.HostPromotion;
import com.mobtimizer.merge.MergeScanner;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
        HostPromotion.register();

        // END_LEVEL_TICK, not END_WORLD_TICK - that constant does not exist in Fabric
        // API 0.158.0. See MergeScanner's class Javadoc for the disassembly-confirmed
        // detail (fires once per loaded level per server tick).
        ServerTickEvents.END_LEVEL_TICK.register(MergeScanner::tick);

        LOGGER.info("Mobtimizer initialising");
    }
}
