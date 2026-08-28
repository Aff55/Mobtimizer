package com.mobtimizer.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

public final class MobtimizerConfig {
    public Merge merge = new Merge();
    public Storage storage = new Storage();
    public Freeze freeze = new Freeze();
    public Display display = new Display();
    public Entities entities = new Entities();
    public Safety safety = new Safety();

    public static final class Merge {
        public boolean enabled = true;
        public double radius = 8.0;
        public int crowdThreshold = 4;
        public int scanIntervalTicks = 20;
        public int maxMergesPerScan = 64;
    }

    public static final class Storage {
        /** -1 = always dormant, 0 = always virtual. Virtual arrives in phase 5. */
        public int virtualSpillThreshold = 512;
    }

    public static final class Freeze {
        public String mode = "AGGRESSIVE";

        public boolean isAggressive() {
            return !"CONSERVATIVE".equalsIgnoreCase(mode);
        }
    }

    public static final class Display {
        public boolean enabled = true;
        public String format = "%s ×%d";
        /** false = the count shows only when the player looks at the mob. */
        public boolean alwaysVisible = false;
    }

    public static final class Entities {
        public String mode = "DENYLIST";
        // wither/ender_dragon are defence in depth: StackEligibility.canStack already
        // excludes both by explicit instanceof (no general "is a boss" check exists to
        // hook in 26.2 - see that class's Javadoc), but modded bosses have no such
        // hard-coded exclusion and will always need denylisting here instead.
        public List<String> denylist = new ArrayList<>(List.of(
                "minecraft:villager",
                "minecraft:wandering_trader",
                "minecraft:wither",
                "minecraft:ender_dragon"
        ));
        public List<String> allowlist = new ArrayList<>();

        public boolean isAllowed(EntityType<?> type) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            if ("ALLOWLIST".equalsIgnoreCase(mode)) {
                return allowlist.contains(id);
            }
            return !denylist.contains(id);
        }
    }

    public static final class Safety {
        public boolean autoUnstackOnVersionChange = true;
    }
}
