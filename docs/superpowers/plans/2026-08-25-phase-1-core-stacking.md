# Mobtimizer Phase 1 — Core Stacking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a working Fabric mod that merges same-kind mobs into one ticking entity carrying a member count, with the other members kept in the world as frozen entities.

**Architecture:** Vanilla mobs carry a Fabric data attachment holding the stack state. A throttled scanner merges eligible matching mobs once a crowd threshold is met; merged members are frozen via Mixins that skip their tick, collision and client tracking. No custom entity type exists, so loot tables, AI, commands, other mods and vanilla clients all keep working.

**Tech Stack:** Java 25, Fabric Loom 1.17-SNAPSHOT, Fabric Loader 0.19.3, Fabric API 0.158.0+26.2, official Mojang mappings, Mixin, Gson (bundled with Minecraft), JUnit 5 via `fabric-loader-junit`, Fabric Gametest.

## Global Constraints

- Minecraft **26.2**. Fabric Loader **0.19.3**. Loom **1.17-SNAPSHOT**. Fabric API **0.158.0+26.2**.
- Java **25** — Gradle toolchain pinned to 25; `fabric.mod.json` declares `"java": ">=25"`.
- **This machine has no JDK on PATH** — `java` is a Java 8 JRE and there is no `javac` or system `gradle`. The only JDK 25 present is the JetBrains Runtime bundled with Android Studio. **Every Gradle command must be run as:**

  ```bash
  JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <task>
  ```

  Running plain `./gradlew` will fail — Gradle's launcher cannot start on Java 8. Do not attempt to install a JDK; do not edit system environment variables. Use the prefix.
- **Official Mojang mappings.** Minecraft is unobfuscated from 26.1 onward and Yarn is discontinued. There is **no `mappings` line** in `build.gradle`, and Fabric API is a plain `implementation` dependency, **not** `modImplementation`. Use official names: `Mob`, `Animal`, `LivingEntity`, `ServerLevel`, `CompoundTag`, `Identifier`.
- **Server-side only.** No client source set, no `splitEnvironmentSourceSets()`, no client entrypoint. `"environment": "*"` so the integrated server in singleplayer also loads it.
- Mod id `mobtimizer`, package root `com.mobtimizer`, Maven group `com.mobtimizer`.
- **No new runtime dependencies.** Gson ships with Minecraft; use it rather than adding a config library.
- All Mixins live under `com.mobtimizer.mixin` and nowhere else, so version-fragile code stays quarantined.
- Every task ends with a passing `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build` and a commit.
- Spec of record: `docs/superpowers/specs/2026-08-25-mobtimizer-design.md`.

## Verified 26.2 API facts

Established empirically during Task 1 by disassembling the real
`minecraft-merged-deobf-26.2.jar` and building the unmodified `fabric-example-mod` 26.2
branch for comparison. **These override any contradicting memory, tutorial, or older
Fabric documentation.** Several are surprising; none are guesses.

| Fact | Detail |
|---|---|
| `ResourceLocation` **no longer exists** | Renamed to `Identifier`, same package: `net.minecraft.resources.Identifier`. Factory is `Identifier.fromNamespaceAndPath(ns, path)`. A grep of the whole jar finds no `ResourceLocation` class. |
| Entity type constants moved | `EntityType.COW` is gone. Constants live on a sibling holder class `net.minecraft.world.entity.EntityTypes` (plural), mirroring the existing `Blocks`/`Block` split. Use `EntityTypes.COW`. `EntityType<?>` remains the generic type. |
| `ResourceKey.location()` renamed | Now `ResourceKey.identifier()`. |
| Registry key lookup | `EntityType.getKey(EntityType<?>)` is the non-deprecated accessor. `builtInRegistryHolder()` still works but is `@Deprecated` and emits a warning. |
| Loom plugin id | Must be `net.fabricmc.fabric-loom`. The short `fabric-loom` id resolves to the legacy remap variant and fails with `Configuration 'mappings' has no dependencies`. |
| Mixin compatibility | Bundled Mixin is `sponge-mixin-0.17.3+mixin.0.8.7`; its `CompatibilityLevel` enum reaches `JAVA_25`, so the config's `JAVA_21` is valid. |
| Mob classes were repackaged | `Cow` is `net.minecraft.world.entity.animal.cow.Cow`, `Wolf` is `...animal.wolf.Wolf`, `Villager` is `...npc.villager.Villager`. Each mob family now has its own subpackage — check the real path rather than assuming `animal.X`. |
| Gametest source set | `src/gametest/java/`. There is no pre-existing example in this repo — the single game test seen in early build logs is vanilla's own bundled `always_pass`. |

### Entity serialization — and why `createWithContext` is mandatory

`Entity.saveWithoutId(CompoundTag)` **no longer exists**. Only `saveWithoutId(ValueOutput)`
remains. Verified in Task 4 against the deobfuscated jar. The call is:

```java
TagValueOutput output = TagValueOutput.createWithContext(
        ProblemReporter.DISCARDING, mob.level().registryAccess());
mob.saveWithoutId(output);
CompoundTag full = output.buildResult();
```

Imports: `net.minecraft.util.ProblemReporter`, `net.minecraft.world.level.storage.TagValueOutput`.

**`createWithoutContext` is not an acceptable substitute**, even though it looks simpler and
takes no registry. Task 4 proved by bytecode and a live experiment that without a registry
lookup, `RegistryFixedCodec`-backed fields — enchantments, and datapack-registry variants —
fail to encode and produce **no partial value**, which silently drops the *entire* parent
field. All of `equipment` disappears rather than just the enchantment inside it.

For this mod that is a correctness hole, not a cosmetic one: two mobs with different
enchantments would serialize identically, compare equal, and merge. An enchanted-armour zombie
would be laundered into a plain stack and its gear quietly destroyed. The registry access costs
nothing at the call site — `mob.level().registryAccess()` is already a `HolderLookup.Provider`.

### The Fabric attachment API

Verified in Task 5 against Fabric API 0.158.0+26.2.

**`AttachmentRegistry.builder()` is deprecated.** Use the identifier-first entry point:

```java
STACK = AttachmentRegistry.create(Mobtimizer.id("stack"), builder -> builder
        .persistent(MobStack.CODEC)
        .initializer(() -> MobStack.EMPTY));
```

The `Builder` handed to the consumer is the same type with the same methods, so behaviour is
unchanged. Never add `.syncWith(...)` — this mod is server-side.

**`getAttached` returns `null` even when an initializer is registered.** The initializer only
runs for `getAttachedOrCreate`. This is the trap in this API:

| Call | Behaviour |
|---|---|
| `getAttached(TYPE)` | `null` until something calls `setAttached` — the initializer does **not** fire |
| `getAttachedOrElse(TYPE, default)` | returns the default as-is, does not persist it |
| `getAttachedOrCreate(TYPE, supplier)` | persists the created value on first read |
| `getAttachedOrCreate(TYPE)` | throws if no initializer was registered |

**Read stack state with `getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY)`**, never
bare `getAttached`, or an unstacked mob NPEs. No cast to `AttachmentTarget` is needed — the
methods resolve directly on `Mob`/`Entity`.

Also note `MobtimizerAttachments.STACK` is null until `onInitialize()` runs. Fine for gametests
and real runs; a plain JUnit test touching it would need `register()` called manually.

### The `GameTestHelper.spawn` trap

**`GameTestHelper.spawn(...)` and its convenience overloads silently mark every mob they
spawn persistence-required**, so fixtures cannot despawn mid-test. Verified in Task 3 by
disassembling `GameTestEntityBuilder`.

This is a false-green trap for this mod specifically. `StackEligibility.canStack` excludes
persistence-required mobs, so a mob spawned the convenient way is ineligible **no matter what
the test is actually checking** — every "should not stack" assertion would pass for the wrong
reason, and only the positive case would fail. Any gametest touching eligibility, merging, or
stacking must spawn through:

```java
public final class GameTestMobs {
    private GameTestMobs() {}

    /**
     * Spawns a mob without the persistence flag that {@link GameTestHelper#spawn} sets.
     * canStack() excludes persistence-required mobs, so the convenience methods would make
     * every fixture ineligible regardless of the condition under test.
     */
    public static <E extends Entity> E spawnPlain(GameTestHelper helper, EntityType<E> type, BlockPos pos) {
        return helper.spawnEntity(type, pos).requirePersistence(false).spawn();
    }
}
```

Task 3 created this as a private method inside `StackEligibilityGameTest`. **The first task to
need it elsewhere must extract it to a shared `com.mobtimizer.gametest.GameTestMobs`** and
update Task 3's gametest to use it, rather than copying the method. Gametest code blocks below
already call `GameTestMobs.spawnPlain`.

Manage imports accordingly. Code blocks below import **both** `EntityType` and
`EntityTypes` because most need the constants and a few need the generic type;
**delete whichever your file does not actually use.** Leave no unused imports —
`EntityType<?>` as a parameter type needs `EntityType`; `EntityTypes.COW` needs
`EntityTypes`; `EntityType.getKey(EntityTypes.COW)` needs both.

When a name in your brief's code contradicts this table, **the table wins** — the brief was
drafted before these were verified. If you hit a further rename not listed here, confirm it
against the real jar rather than guessing, and report it so later tasks inherit the finding.

## Out of scope for phase 1

Deferred deliberately; do not build these:

- `RunList` / RLE runs, ages, breeding cooldowns, wool timers — phase 3–4. Nothing in phase 1 reads them.
- `VirtualStore` and the spill threshold — phase 5. `MemberStore` gets an interface so phase 5 slots in, but only `DormantStore` is implemented.
- Damage routing, loot and XP merging — phase 2.
- Breeding, production, the mob-cap Mixin — phases 3, 4, 6.

The `MobStack` codec must use `optionalFieldOf` with defaults throughout so phase 3 can add fields without breaking existing saves.

## File structure

| File | Responsibility |
|---|---|
| `build.gradle`, `settings.gradle`, `gradle.properties` | Build config |
| `.gitattributes` | Force LF on `gradlew` so checkout doesn't break it |
| `src/main/resources/fabric.mod.json` | Mod metadata |
| `src/main/resources/mobtimizer.mixins.json` | Mixin config |
| `com/mobtimizer/Mobtimizer.java` | Entrypoint, wiring, logger |
| `com/mobtimizer/MobtimizerAttachments.java` | Attachment type registration |
| `com/mobtimizer/config/MobtimizerConfig.java` | Config schema with defaults |
| `com/mobtimizer/config/ConfigManager.java` | Load / save / reload |
| `com/mobtimizer/identity/StackEligibility.java` | `canStack(Mob)` predicate |
| `com/mobtimizer/identity/StackKey.java` | Identity value object |
| `com/mobtimizer/identity/StackKeyFactory.java` | NBT hashing + ignore list |
| `com/mobtimizer/stack/MobStack.java` | Attachment payload + codec |
| `com/mobtimizer/stack/MemberStore.java` | Storage interface |
| `com/mobtimizer/stack/DormantStore.java` | Frozen-entity backend |
| `com/mobtimizer/stack/StackManager.java` | Merge / split / count operations |
| `com/mobtimizer/freeze/Dormancy.java` | Freeze / thaw a member |
| `com/mobtimizer/mixin/*.java` | Tick, collision, tracking suppression |
| `com/mobtimizer/merge/MergeScanner.java` | Throttled scan + crowd gate |
| `com/mobtimizer/display/StackNameplate.java` | Nameplate + `nameplateOwned` |
| `com/mobtimizer/safety/VersionGuard.java` | Auto-unstack on MC version change |
| `com/mobtimizer/command/MobtimizerCommand.java` | `/mobtimizer` subcommands |

---

### Task 1: Project skeleton that builds and runs tests

**Files:**
- Create: `settings.gradle`, `gradle.properties`, `build.gradle`, `.gitattributes`
- Create: `src/main/resources/fabric.mod.json`, `src/main/resources/mobtimizer.mixins.json`
- Create: `src/main/java/com/mobtimizer/Mobtimizer.java`
- Test: `src/test/java/com/mobtimizer/BootstrapTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.mobtimizer.Mobtimizer.MOD_ID` (`String`, value `"mobtimizer"`), `Mobtimizer.LOGGER` (`org.slf4j.Logger`), and `Mobtimizer.id(String path)` returning `Identifier` — every later task uses these.

- [ ] **Step 1: Get the Gradle wrapper**

The wrapper contains a binary jar that cannot be written by hand. Copy it from the official example mod:

```bash
git clone --depth 1 -b 26.2 https://github.com/FabricMC/fabric-example-mod.git /tmp/fem
cp -r /tmp/fem/gradle /tmp/fem/gradlew /tmp/fem/gradlew.bat .
rm -rf /tmp/fem
```

- [ ] **Step 2: Write `.gitattributes`**

Without this, Git checkout on Windows converts `gradlew` to CRLF and it fails with `bad interpreter`.

```
* text=auto eol=lf
*.jar binary
*.png binary
gradlew text eol=lf
gradlew.bat text eol=crlf
```

- [ ] **Step 3: Write `settings.gradle`**

```groovy
pluginManagement {
    repositories {
        maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download JDK 25 on machines that don't have it, instead of failing.
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

rootProject.name = 'mobtimizer'
```

**Verify:** confirm the current `foojay-resolver-convention` version is `1.0.0`; if Gradle rejects it, use the version its error message suggests. If the plugin cannot be resolved at all, drop this block — the local JetBrains Runtime satisfies the toolchain on this machine regardless.

- [ ] **Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
# IntelliJ is not yet compatible with the configuration cache
org.gradle.configuration-cache=false

minecraft_version=26.2
loader_version=0.19.3
loom_version=1.17-SNAPSHOT

version=0.1.0
maven_group=com.mobtimizer
archives_base_name=mobtimizer

fabric_api_version=0.158.0+26.2
```

- [ ] **Step 5: Write `build.gradle`**

Note there is deliberately **no `mappings` line** and Fabric API uses `implementation`, not `modImplementation`. Both are consequences of Minecraft being unobfuscated since 26.1. Do not "fix" them.

```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
    id 'maven-publish'
}

version = project.version
group = project.maven_group

base {
    archivesName = project.archives_base_name
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

    testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}"
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "mobtimizer-test"
        enableGameTests = true
        eula = true
    }
}

test {
    useJUnitPlatform()
}

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") {
        expand "version": inputs.properties.version
    }
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 25
}
```

A toolchain is used rather than `sourceCompatibility`/`targetCompatibility` so the build declares
the JDK it needs and Gradle resolves it, instead of silently compiling against whatever JVM
happens to be running. Combined with the Foojay resolver in `settings.gradle`, a machine without
JDK 25 downloads it automatically rather than failing with a confusing compile error.

The fully-qualified plugin id is required. The short `fabric-loom` id still resolves, but to the legacy remap-oriented variant, which fails with `Configuration 'mappings' has no dependencies` on unobfuscated Minecraft. Verified in Task 1.

- [ ] **Step 6: Write `src/main/resources/fabric.mod.json`**

```json
{
	"schemaVersion": 1,
	"id": "mobtimizer",
	"version": "${version}",
	"name": "Mobtimizer",
	"description": "Merges same-kind mobs into a single ticking entity while preserving vanilla behaviour.",
	"authors": ["affan"],
	"license": "MIT",
	"environment": "*",
	"entrypoints": {
		"main": ["com.mobtimizer.Mobtimizer"]
	},
	"mixins": ["mobtimizer.mixins.json"],
	"depends": {
		"fabricloader": ">=0.19.3",
		"minecraft": "~26.2",
		"java": ">=25",
		"fabric-api": "*"
	}
}
```

- [ ] **Step 7: Write `src/main/resources/mobtimizer.mixins.json`**

The `mixins` array stays empty until Task 8. An empty array is valid and loads fine.

```json
{
	"required": true,
	"package": "com.mobtimizer.mixin",
	"compatibilityLevel": "JAVA_21",
	"injectors": {
		"defaultRequire": 1
	},
	"mixins": []
}
```

**Verify:** if the build rejects `JAVA_21`, raise it to the highest `JAVA_*` value the bundled Mixin accepts (check the enum in `org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel`). Mixin's compatibility level tracks class-file features, so it lags the JDK version and is expected to be lower than 25.

- [ ] **Step 8: Write the entrypoint**

```java
package com.mobtimizer;

import net.fabricmc.api.ModInitializer;
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
        LOGGER.info("Mobtimizer initialising");
    }
}
```

**Verify:** confirm `Identifier.fromNamespaceAndPath` exists in 26.2. If it was renamed, use whatever static factory `Identifier` exposes for a namespace+path pair.

- [ ] **Step 9: Write the failing harness test**

This proves the JUnit harness can reach registry-dependent Minecraft classes, which every later test depends on.

```java
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
```

- [ ] **Step 10: Run the build**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS, both tests green.

`EntityType.getKey` is the non-deprecated registry accessor; `builtInRegistryHolder()` also works but emits a deprecation warning, and a foundational test every later suite builds on should compile clean.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: Fabric project skeleton for MC 26.2"
```

---

### Task 2: Config

**Files:**
- Create: `src/main/java/com/mobtimizer/config/MobtimizerConfig.java`
- Create: `src/main/java/com/mobtimizer/config/ConfigManager.java`
- Test: `src/test/java/com/mobtimizer/config/ConfigManagerTest.java`

**Interfaces:**
- Consumes: `Mobtimizer.LOGGER`.
- Produces: `MobtimizerConfig` with public nested classes `Merge`, `Storage`, `Freeze`, `Display`, `Entities`, `Safety`; `MobtimizerConfig.Entities.isAllowed(EntityType<?>)` returning `boolean`; `ConfigManager.get()` returning `MobtimizerConfig`; `ConfigManager.load(Path)` and `ConfigManager.reload()` returning `void`.

**Why mutable classes and not records:** Gson leaves a field untouched when the JSON omits it. With fields pre-initialized to defaults, a partial or older config file automatically fills in every missing key. Records would deserialize missing keys to `null`/`0`. This is a correctness requirement, not a style preference — do not convert these to records.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.config.ConfigManagerTest"`
Expected: FAIL — `MobtimizerConfig` and `ConfigManager` do not exist.

- [ ] **Step 3: Write `MobtimizerConfig`**

```java
package com.mobtimizer.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

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
        public List<String> denylist = new ArrayList<>(List.of(
                "minecraft:villager",
                "minecraft:wandering_trader"
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
```

- [ ] **Step 4: Write `ConfigManager`**

```java
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
```

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.config.ConfigManagerTest"`
Expected: PASS, all three.

Note the second test relies on Gson's partial-deserialization behaviour: `{"merge":{"crowdThreshold":9}}` replaces the whole `merge` object, so `radius` comes from `Merge`'s own field initialiser. If that assertion fails, Gson is constructing `Merge` without running initialisers — switch `ConfigManager` to deserialize into a `JsonObject` and merge key-by-key over the defaults.

- [ ] **Step 6: Wire it into the entrypoint**

In `Mobtimizer.onInitialize()`, before anything else:

```java
ConfigManager.load(net.fabricmc.loader.api.FabricLoader.getInstance()
        .getConfigDir().resolve("mobtimizer.json"));
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: config with defaults, partial-file merging and reload"
```

---

### Task 3: Stack eligibility

**Files:**
- Create: `src/main/java/com/mobtimizer/identity/StackEligibility.java`
- Test: `src/test/java/com/mobtimizer/identity/StackEligibilityTest.java`

**Interfaces:**
- Consumes: `ConfigManager.get()`, `MobtimizerConfig.Entities.isAllowed(EntityType<?>)`.
- Produces: `StackEligibility.canStack(Mob mob)` returning `boolean`. Later tasks call this and nothing else from this class.

This is a pure predicate with no world mutation, which makes it the cheapest place in the mod to be exhaustively correct.

- [ ] **Step 1: Verify the vanilla method names**

Before writing anything, open the decompiled 26.2 sources and confirm each of these exists with the expected meaning. Note the real name next to each:

| Intent | Expected in 26.2 |
|---|---|
| has a custom name | `Entity.hasCustomName()` |
| is leashed | `Leashable.isLeashed()` — may be on the `Leashable` interface rather than `Mob` |
| is riding something | `Entity.isPassenger()` |
| has riders | `Entity.isVehicle()` |
| is owned/tamed | `OwnableEntity` — confirm the owner accessor name |
| won't despawn | `Mob.isPersistenceRequired()` |
| AI disabled | `Mob.isNoAi()` |
| invulnerable | `Entity.isInvulnerable()` |

Adjust the code below to match what you find. Do not guess.

- [ ] **Step 2: Write the failing test**

Gametests cover the mob-state branches in Task 13, since constructing a live `Mob` in a unit test is not worth the fixture cost. Here we test the config branch, which is pure.

```java
package com.mobtimizer.identity;

import com.mobtimizer.config.MobtimizerConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StackEligibilityTest {
    @BeforeAll
    static void beforeAll() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void denylistBlocksListedTypes() {
        MobtimizerConfig.Entities entities = new MobtimizerConfig.Entities();
        assertFalse(entities.isAllowed(EntityTypes.VILLAGER));
        assertTrue(entities.isAllowed(EntityTypes.COW));
    }

    @Test
    void allowlistModeOnlyPermitsListedTypes() {
        MobtimizerConfig.Entities entities = new MobtimizerConfig.Entities();
        entities.mode = "ALLOWLIST";
        entities.allowlist.add("minecraft:cow");

        assertTrue(entities.isAllowed(EntityTypes.COW));
        assertFalse(entities.isAllowed(EntityTypes.PIG));
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.identity.StackEligibilityTest"`
Expected: FAIL — the class does not exist.

- [ ] **Step 4: Write `StackEligibility`**

`StackNameplate.isModOwnedName` does not exist until Task 11. Until then, the import will not compile — so create Task 11's class as a stub now with `isModOwnedName` returning `false`, and fill it in properly in Task 11. That keeps this task independently buildable.

```java
package com.mobtimizer.identity;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.display.StackNameplate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;

public final class StackEligibility {
    private StackEligibility() {}

    /**
     * Whether this mob may participate in stacking at all.
     *
     * <p>Every exclusion here represents a deliberate player setup — a named,
     * leashed, ridden or tamed mob is one the player singled out, and merging it
     * away destroys that intent.
     */
    public static boolean canStack(Mob mob) {
        if (!(mob.level() instanceof ServerLevel)) return false;
        if (mob.isRemoved() || !mob.isAlive()) return false;

        // A player-assigned name means "this one is special". The count nameplate
        // is also a custom name, so it must be excluded from this check or hosts
        // would silently become ineligible the moment they were labelled.
        if (mob.hasCustomName() && !StackNameplate.isModOwnedName(mob)) return false;

        if (mob.isLeashed()) return false;
        if (mob.isPassenger() || mob.isVehicle()) return false;
        if (mob instanceof OwnableEntity) return false;
        if (mob.isPersistenceRequired()) return false;
        if (mob.isNoAi()) return false;
        if (mob.isInvulnerable()) return false;

        return ConfigManager.get().entities.isAllowed(mob.getType());
    }
}
```

Excluding every `OwnableEntity` rather than only currently-owned ones is intentional: an untamed wolf that stacked and was then tamed would be a mess to unpick.

- [ ] **Step 5: Write the `StackNameplate` stub**

```java
package com.mobtimizer.display;

import net.minecraft.world.entity.Mob;

public final class StackNameplate {
    private StackNameplate() {}

    /** Filled in properly in Task 11. */
    public static boolean isModOwnedName(Mob mob) {
        return false;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.identity.StackEligibilityTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: stack eligibility predicate"
```

---

### Task 4: Stack identity key

**Files:**
- Create: `src/main/java/com/mobtimizer/identity/StackKey.java`
- Create: `src/main/java/com/mobtimizer/identity/StackKeyFactory.java`
- Test: `src/test/java/com/mobtimizer/identity/StackKeyFactoryTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `StackKey` (a record of `Identifier typeId, CompoundTag identity`) and `StackKeyFactory.of(Mob mob)` returning `StackKey`. `StackKey` has value equality.

**Why NBT hashing rather than per-type comparators:** hand-written variant comparators must be written once per mob type and silently return "these match" for any modded mob whose variant field nobody thought about. Serializing and diffing against an explicit ignore list inverts that: everything must match unless it is on a list you can read in one screen, and modded mobs work correctly with no per-type code.

**Do not use a hash int as the key.** Two different NBT compounds that collide would merge mobs that should never merge. `StackKey` holds the actual `CompoundTag`; record equality delegates to `CompoundTag.equals`, which is content-based.

- [ ] **Step 1: Verify how entities serialize in 26.2**

Mojang refactored entity serialization to a `ValueInput`/`ValueOutput` abstraction in the 1.21.6 era, so `Entity.saveWithoutId(CompoundTag)` may no longer exist. Open the decompiled `Entity` class and find the current way to serialize an entity to a `CompoundTag`.

Likely one of:
- `entity.saveWithoutId(CompoundTag)` — the older API, if it survived
- `TagValueOutput.createWithoutContext(...)` / `createWithContext(...)`, passed to `entity.saveWithoutId(ValueOutput)`, then reading the tag back off the output

Write down the exact call you find and use it in Step 3. Everything else in this task is unaffected.

- [ ] **Step 2: Write the failing test**

```java
package com.mobtimizer.identity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StackKeyFactoryTest {
    private static StackKey key(String type, CompoundTag identity) {
        return new StackKey(Identifier.parse(type), identity);
    }

    @Test
    void identicalIdentityTagsAreEqual() {
        CompoundTag a = new CompoundTag();
        a.putString("variant", "temperate");
        CompoundTag b = new CompoundTag();
        b.putString("variant", "temperate");

        assertEquals(key("minecraft:cow", a), key("minecraft:cow", b));
        assertEquals(key("minecraft:cow", a).hashCode(), key("minecraft:cow", b).hashCode());
    }

    @Test
    void differingVariantIsNotEqual() {
        CompoundTag a = new CompoundTag();
        a.putString("variant", "temperate");
        CompoundTag b = new CompoundTag();
        b.putString("variant", "cold");

        assertNotEquals(key("minecraft:cow", a), key("minecraft:cow", b));
    }

    @Test
    void differingTypeIsNotEqual() {
        CompoundTag empty = new CompoundTag();
        assertNotEquals(key("minecraft:cow", empty), key("minecraft:pig", empty));
    }

    @Test
    void everyIgnoredFieldIsStripped() {
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "temperate");
        for (String ignored : StackKeyFactory.IGNORED_KEYS) {
            tag.putString(ignored, "whatever");
        }

        CompoundTag stripped = StackKeyFactory.stripIgnored(tag);

        assertEquals(1, stripped.size(), "only 'variant' should survive stripping");
        for (String ignored : StackKeyFactory.IGNORED_KEYS) {
            assertFalse(stripped.contains(ignored), ignored + " should have been stripped");
        }
    }
}
```

**Verify:** `CompoundTag.size()` and `CompoundTag.contains(String)` — confirm these names in 26.2 and adjust if the NBT API changed.

- [ ] **Step 3: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.identity.StackKeyFactoryTest"`
Expected: FAIL — the classes do not exist.

- [ ] **Step 4: Write `StackKey`**

```java
package com.mobtimizer.identity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * Identity of a stack: two mobs may merge only if their keys are equal.
 *
 * <p>Holds the stripped identity tag itself rather than a hash of it, because a
 * hash collision would merge mobs that must never merge. Record equality
 * delegates to {@link CompoundTag#equals}, which compares by content.
 */
public record StackKey(Identifier typeId, CompoundTag identity) {
}
```

- [ ] **Step 5: Write `StackKeyFactory`**

Replace the serialization call in `of()` with whatever you confirmed in Step 1.

```java
package com.mobtimizer.identity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;

import java.util.Set;

public final class StackKeyFactory {
    /**
     * NBT keys allowed to differ between members of one stack.
     *
     * <p>This list is the mod's contract: anything not named here must match
     * exactly for two mobs to merge, including any field added by another mod.
     * Age, love and shear state appear because the stack owns them as
     * time-advancing state; Health appears because a damaged mob merges in and
     * is treated as full health, an accepted inaccuracy recorded in the spec.
     */
    public static final Set<String> IGNORED_KEYS = Set.of(
            "UUID", "Pos", "Motion", "Rotation", "FallDistance",
            "HurtTime", "HurtByTimestamp", "DeathTime", "Health",
            "Air", "Fire", "PortalCooldown", "TicksFrozen", "Brain",
            "Age", "ForcedAge", "InLove", "LoveCause", "Sheared",
            "CustomName", "CustomNameVisible"
    );

    private StackKeyFactory() {}

    public static StackKey of(Mob mob) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, mob.level().registryAccess());
        mob.saveWithoutId(output);
        CompoundTag full = output.buildResult();
        return new StackKey(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()), stripIgnored(full));
    }

    /** Visible for testing. */
    public static CompoundTag stripIgnored(CompoundTag tag) {
        CompoundTag copy = tag.copy();
        for (String key : IGNORED_KEYS) {
            copy.remove(key);
        }
        return copy;
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.identity.StackKeyFactoryTest"`
Expected: PASS, all four.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: NBT-based stack identity key with explicit ignore list"
```

---

### Task 5: Stack state attachment

**Files:**
- Create: `src/main/java/com/mobtimizer/stack/MobStack.java`
- Create: `src/main/java/com/mobtimizer/MobtimizerAttachments.java`
- Test: `src/test/java/com/mobtimizer/stack/MobStackTest.java`

**Interfaces:**
- Consumes: `Mobtimizer.id(String)`.
- Produces: `MobStack` (record: `List<UUID> members`, `boolean nameplateOwned`) with `MobStack.CODEC`, `MobStack.EMPTY`, `memberCount()` returning `int` (members plus the host), `withMember(UUID)`, `withoutMember(UUID)`, `withNameplateOwned(boolean)`. Also `MobtimizerAttachments.STACK` of type `AttachmentType<MobStack>` and `MobtimizerAttachments.register()`.

**Codec rule:** every field uses `optionalFieldOf` with a default. Phase 3 adds age and cooldown runs, and existing saves must keep loading.

- [ ] **Step 1: Write the failing test**

```java
package com.mobtimizer.stack;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MobStackTest {
    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());

    @Test
    void memberCountIncludesTheHost() {
        assertEquals(1, MobStack.EMPTY.memberCount(), "a host with no members is a stack of 1");
        assertEquals(3, new MobStack(List.of(A, B), false).memberCount());
    }

    @Test
    void withMemberIsImmutable() {
        MobStack one = MobStack.EMPTY.withMember(A);
        MobStack two = one.withMember(B);

        assertEquals(2, one.memberCount(), "the original must not be mutated");
        assertEquals(3, two.memberCount());
    }

    @Test
    void withoutMemberRemovesExactlyOne() {
        MobStack stack = new MobStack(List.of(A, B), false).withoutMember(A);

        assertEquals(2, stack.memberCount());
        assertFalse(stack.members().contains(A));
        assertTrue(stack.members().contains(B));
    }

    @Test
    void codecRoundTrips() {
        MobStack original = new MobStack(List.of(A, B), true);

        var encoded = MobStack.CODEC.encodeStart(JsonOps.INSTANCE, original)
                .getOrThrow(IllegalStateException::new);
        MobStack decoded = MobStack.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(IllegalStateException::new);

        assertEquals(original, decoded);
    }

    @Test
    void codecToleratesMissingOptionalFields() {
        // Phase 3 adds fields; old saves must still load. Prove the pattern now.
        var json = com.google.gson.JsonParser.parseString("{}");
        MobStack decoded = MobStack.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow(IllegalStateException::new);

        assertEquals(MobStack.EMPTY, decoded);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.stack.MobStackTest"`
Expected: FAIL — `MobStack` does not exist.

- [ ] **Step 3: Write `MobStack`**

```java
package com.mobtimizer.stack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * State attached to a stack host.
 *
 * <p>{@code members} holds the frozen members only; the host itself is not in
 * the list, so a stack of 100 has 99 entries. {@code nameplateOwned} records
 * that the host's custom name was set by this mod rather than a player, which
 * keeps the count label from making the host ineligible for further merging.
 *
 * <p>Every codec field is optional with a default so later phases can add
 * fields without invalidating existing saves.
 */
public record MobStack(List<UUID> members, boolean nameplateOwned) {
    public static final MobStack EMPTY = new MobStack(List.of(), false);

    public static final Codec<MobStack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(MobStack::members),
            Codec.BOOL.optionalFieldOf("nameplate_owned", false).forGetter(MobStack::nameplateOwned)
    ).apply(instance, MobStack::new));

    public MobStack {
        members = List.copyOf(members);
    }

    /** Total mobs represented, including the host. */
    public int memberCount() {
        return members.size() + 1;
    }

    public MobStack withMember(UUID member) {
        List<UUID> next = new ArrayList<>(members);
        next.add(member);
        return new MobStack(next, nameplateOwned);
    }

    public MobStack withoutMember(UUID member) {
        List<UUID> next = new ArrayList<>(members);
        next.remove(member);
        return new MobStack(next, nameplateOwned);
    }

    public MobStack withNameplateOwned(boolean owned) {
        return new MobStack(members, owned);
    }
}
```

**Verify:** `UUIDUtil.CODEC` — confirm the class and constant name in 26.2. If absent, use `Codec.STRING.xmap(UUID::fromString, UUID::toString)`.

- [ ] **Step 4: Write `MobtimizerAttachments`**

```java
package com.mobtimizer;

import com.mobtimizer.stack.MobStack;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

public final class MobtimizerAttachments {
    public static AttachmentType<MobStack> STACK;

    private MobtimizerAttachments() {}

    public static void register() {
        STACK = AttachmentRegistry.<MobStack>builder()
                .persistent(MobStack.CODEC)
                .initializer(() -> MobStack.EMPTY)
                .buildAndRegister(Mobtimizer.id("stack"));
    }
}
```

Do **not** add `.syncWith(...)`. The mod is server-side and nothing is sent to clients.

Call `MobtimizerAttachments.register()` from `Mobtimizer.onInitialize()`, after `ConfigManager.load(...)`.

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.mobtimizer.stack.MobStackTest"`
Expected: PASS, all five.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: MobStack attachment with forward-compatible codec"
```

---

### Task 6: Member store

**Files:**
- Create: `src/main/java/com/mobtimizer/stack/MemberStore.java`
- Create: `src/main/java/com/mobtimizer/stack/DormantStore.java`
- Test: `src/test/java/com/mobtimizer/stack/DormantStoreTest.java`

**Interfaces:**
- Consumes: `MobStack`, `MobtimizerAttachments.STACK`.
- Produces: interface `MemberStore` with `int size(Mob host)`, `void add(Mob host, Mob member)`, `@Nullable Mob takeOne(Mob host)`, `List<Mob> takeAll(Mob host)`; and `DormantStore.INSTANCE`.

`MemberStore` exists so phase 5 can add `VirtualStore` behind the same interface. Phase 1 implements only `DormantStore`. Do not add methods speculatively for virtual storage.

- [ ] **Step 1: Write `MemberStore`**

```java
package com.mobtimizer.stack;

import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Where a stack's non-host members live.
 *
 * <p>Phase 1 ships {@link DormantStore} only. Phase 5 adds a virtual backend
 * that compresses members to NBT; it implements this same interface so no game
 * mechanic needs a second code path.
 */
public interface MemberStore {
    int size(Mob host);

    void add(Mob host, Mob member);

    /** Removes one member and returns it as a live, thawed mob, or null if the stack has none. */
    @Nullable Mob takeOne(Mob host);

    /** Removes and thaws every member. Used by unstack. */
    List<Mob> takeAll(Mob host);
}
```

- [ ] **Step 2: Write the failing gametest**

Members are real entities, so this needs a live level. Put it in the gametest source set created by `configureTests` (`src/gametest/java/...` — confirm the exact directory Loom generates).

```java
package com.mobtimizer.stack;

import com.mobtimizer.MobtimizerAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class DormantStoreGameTest {
    @GameTest
    public void addThenTakeOneRoundTrips(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

        DormantStore.INSTANCE.add(host, member);
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 1, "member should be stored");
        helper.assertTrue(host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).memberCount() == 2,
                "host plus one member is a stack of 2");

        var taken = DormantStore.INSTANCE.takeOne(host);
        helper.assertTrue(taken != null, "takeOne should return the member");
        helper.assertTrue(DormantStore.INSTANCE.size(host) == 0, "store should now be empty");
        helper.assertTrue(DormantStore.INSTANCE.takeOne(host) == null, "empty store returns null");

        helper.succeed();
    }
}
```

**Verify:** `GameTestHelper.spawn(EntityType, x, y, z)` and `helper.assertTrue(boolean, String)` — confirm the exact signatures in 26.2 and adjust.

- [ ] **Step 3: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: FAIL — `DormantStore` does not exist.

- [ ] **Step 4: Write `DormantStore`**

`Dormancy` does not exist until Task 7. Create it as a stub now with empty `freeze`/`thaw` bodies so this task builds, then fill it in during Task 7.

```java
package com.mobtimizer.stack;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps members in the world as frozen entities.
 *
 * <p>This is why removing the mod is survivable: the members are ordinary
 * entities in the save file, so with the freeze Mixins no longer running they
 * simply wake up.
 */
public final class DormantStore implements MemberStore {
    public static final DormantStore INSTANCE = new DormantStore();

    private DormantStore() {}

    @Override
    public int size(Mob host) {
        return stackOf(host).members().size();
    }

    @Override
    public void add(Mob host, Mob member) {
        Dormancy.freeze(member, host);
        host.setAttached(MobtimizerAttachments.STACK, stackOf(host).withMember(member.getUUID()));
    }

    @Override
    public @Nullable Mob takeOne(Mob host) {
        MobStack stack = stackOf(host);
        if (stack.members().isEmpty()) {
            return null;
        }

        UUID id = stack.members().getLast();
        host.setAttached(MobtimizerAttachments.STACK, stack.withoutMember(id));

        Mob member = resolve(host, id);
        if (member == null) {
            // The entity vanished (chunk trimmed, /kill, another mod). Dropping the
            // stale id and reporting nothing is correct: the member no longer exists.
            return null;
        }
        Dormancy.thaw(member);
        return member;
    }

    @Override
    public List<Mob> takeAll(Mob host) {
        List<Mob> thawed = new ArrayList<>();
        Mob next;
        while ((next = takeOne(host)) != null) {
            thawed.add(next);
        }
        return thawed;
    }

    private static MobStack stackOf(Mob host) {
        return host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);
    }

    private static @Nullable Mob resolve(Mob host, UUID id) {
        if (!(host.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(id);
        return entity instanceof Mob mob ? mob : null;
    }
}
```

Note `takeAll` loops on `takeOne` rather than returning early on null: `takeOne` returns null both for "empty" and for "that member's entity is gone", and in the latter case the id has already been removed, so the loop still terminates and skips the corpse correctly.

- [ ] **Step 5: Write the `Dormancy` stub**

```java
package com.mobtimizer.freeze;

import net.minecraft.world.entity.Mob;

public final class Dormancy {
    private Dormancy() {}

    /** Filled in during Task 7. */
    public static void freeze(Mob member, Mob host) {}

    /** Filled in during Task 7. */
    public static void thaw(Mob member) {}

    /** Filled in during Task 7. */
    public static boolean isFrozen(Mob mob) {
        return false;
    }
}
```

- [ ] **Step 6: Run the build**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: MemberStore interface and dormant backend"
```

---

### Task 7: Dormancy and freeze Mixins

**Files:**
- Modify: `src/main/java/com/mobtimizer/freeze/Dormancy.java` (replace the stub)
- Create: `src/main/java/com/mobtimizer/mixin/EntityTickMixin.java`
- Create: `src/main/java/com/mobtimizer/mixin/EntityCollisionMixin.java`
- Modify: `src/main/resources/mobtimizer.mixins.json`
- Test: gametest `src/gametest/java/com/mobtimizer/freeze/DormancyGameTest.java`

**Interfaces:**
- Consumes: `MobtimizerAttachments`, `Mobtimizer.id`.
- Produces: `Dormancy.freeze(Mob member, Mob host)`, `Dormancy.thaw(Mob member)`, `Dormancy.isFrozen(Mob)` returning `boolean`, and `MobtimizerAttachments.FROZEN` of type `AttachmentType<Boolean>`.

This is the riskiest task in the phase. It is the only one that changes vanilla control flow, and a wrong injection point here fails loudly at load rather than silently, which is the good case. Work slowly and verify each target.

- [ ] **Step 1: Verify the injection targets**

Open the decompiled 26.2 sources and record the exact names before writing any Mixin:

1. **Where a server ticks a non-passenger entity.** Look in `ServerLevel` for a method along the lines of `tickNonPassenger(Entity)`. Note its exact name and descriptor.
2. **Whether an entity can be pushed.** Look in `Entity` for `isPushable()` and in `LivingEntity` for how push/collision is gated. Note what you find.
3. **Whether an entity can be collided with.** Look for `Entity.canBeCollidedWith()` or equivalent.

Cancelling at the head of the server's per-entity tick is preferred over overriding `Entity.tick`, because it skips the surrounding bookkeeping too and it is a single, stable injection point.

- [ ] **Step 2: Add the frozen attachment**

In `MobtimizerAttachments`, add alongside `STACK`:

```java
public static AttachmentType<Boolean> FROZEN;
```

and inside `register()`:

```java
FROZEN = AttachmentRegistry.create(Mobtimizer.id("frozen"), builder -> builder
        .persistent(Codec.BOOL)
        .initializer(() -> Boolean.FALSE));
```

Persisting this matters: a frozen member must still be frozen after a save/load cycle, or a reloaded farm would wake every member at once.

- [ ] **Step 3: Write the failing gametest**

```java
package com.mobtimizer.freeze;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class DormancyGameTest {
    @GameTest
    public void freezeThenThawRestoresTheMob(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 3));

        Dormancy.freeze(member, host);

        helper.assertTrue(Dormancy.isFrozen(member), "member should report frozen");
        helper.assertTrue(member.isSilent(), "frozen members must not make noise");
        helper.assertTrue(member.isInvisible(), "frozen members must not be rendered");
        helper.assertTrue(member.position().distanceTo(host.position()) < 0.001,
                "frozen members are moved onto the host");

        Dormancy.thaw(member);

        helper.assertFalse(Dormancy.isFrozen(member), "member should report thawed");
        helper.assertFalse(member.isInvisible(), "thawed members are visible again");
        helper.assertFalse(member.isSilent(), "thawed members make noise again");

        helper.succeed();
    }
}
```

- [ ] **Step 4: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: FAIL — the stub does nothing, so `isFrozen` returns false.

- [ ] **Step 5: Write `Dormancy`**

```java
package com.mobtimizer.freeze;

import com.mobtimizer.MobtimizerAttachments;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Freezing and thawing individual stack members.
 *
 * <p>A frozen member stays a real entity in the world — that is what makes
 * removing the mod survivable — but is made inert: no tick, no collision, no
 * sound, no rendering, and parked on the host so it travels with the stack.
 */
public final class Dormancy {
    private Dormancy() {}

    public static boolean isFrozen(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.FROZEN, Boolean.FALSE);
    }

    public static void freeze(Mob member, Mob host) {
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.TRUE);

        member.setInvisible(true);
        member.setSilent(true);
        member.setNoGravity(true);
        member.setDeltaMovement(Vec3.ZERO);
        member.moveTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
    }

    public static void thaw(Mob member) {
        member.setAttached(MobtimizerAttachments.FROZEN, Boolean.FALSE);

        member.setInvisible(false);
        member.setSilent(false);
        member.setNoGravity(false);
    }

    /** Keeps frozen members travelling with their host. Called from the merge scanner each scan. */
    public static void followHost(Mob member, Mob host) {
        if (member.distanceToSqr(host) > 0.001) {
            member.moveTo(host.getX(), host.getY(), host.getZ(), host.getYRot(), host.getXRot());
        }
    }
}
```

Note `setNoAi` is deliberately **not** used. It is a persisted vanilla flag, and if the mod were removed every member would stay AI-less forever — exactly the failure mode dormant storage exists to avoid. Freezing must live entirely in mod-owned state and Mixins so that removing the mod removes the freeze.

- [ ] **Step 6: Write the tick Mixin**

Replace `ServerLevel`/`tickNonPassenger` with what you confirmed in Step 1.

```java
package com.mobtimizer.mixin;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class EntityTickMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$skipFrozenMembers(Entity entity, CallbackInfo ci) {
        if (!ConfigManager.get().freeze.isAggressive()) return;

        if (entity instanceof Mob mob && Dormancy.isFrozen(mob)) {
            ci.cancel();
        }
    }
}
```

In `CONSERVATIVE` mode this injector does nothing and `FrozenAiMixin` (next step) takes over
instead, letting the base entity tick run while suppressing only the AI. That mode exists as
an escape hatch if another mod turns out to depend on frozen members ticking.

- [ ] **Step 7: Write the collision Mixin**

Dense clumps of animals pushing each other is one of the largest costs in a farm, so this carries real weight beyond tidiness.

```java
package com.mobtimizer.mixin;

import com.mobtimizer.freeze.Dormancy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$frozenMembersDoNotPush(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Mob mob && Dormancy.isFrozen(mob)) {
            cir.setReturnValue(false);
        }
    }
}
```

- [ ] **Step 8: Write the conservative-mode AI Mixin**

Without this, `freeze.mode: CONSERVATIVE` would be a config knob that does nothing. This
suppresses goal selection and AI stepping while letting the base entity tick run, which is
the escape hatch if another mod turns out to depend on frozen members ticking.

```java
package com.mobtimizer.mixin;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.freeze.Dormancy;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class FrozenAiMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void mobtimizer$suppressFrozenAi(CallbackInfo ci) {
        // In aggressive mode the whole tick is already cancelled upstream.
        if (ConfigManager.get().freeze.isAggressive()) return;

        if (Dormancy.isFrozen((Mob) (Object) this)) {
            ci.cancel();
        }
    }
}
```

**Verify:** `Mob.serverAiStep()` — confirm the name in 26.2. If it differs, target whatever method drives goal selection and AI stepping each tick.

- [ ] **Step 9: Register the Mixins**

```json
	"mixins": [
		"EntityCollisionMixin",
		"EntityTickMixin",
		"FrozenAiMixin"
	]
```

- [ ] **Step 10: Run the gametest**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS.

If the build fails with a Mixin target resolution error, the method name from Step 1 is wrong. Re-check the descriptor — an `@Inject` that cannot find its target fails at load, which is the intended safety net.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: dormancy with tick and collision suppression"
```

---

### Task 8: Stack manager

**Files:**
- Create: `src/main/java/com/mobtimizer/stack/StackManager.java`
- Test: gametest `src/gametest/java/com/mobtimizer/stack/StackManagerGameTest.java`

**Interfaces:**
- Consumes: `DormantStore.INSTANCE`, `MobStack`, `StackEligibility.canStack`, `StackKeyFactory.of`, `StackNameplate`.
- Produces: `StackManager.merge(Mob host, Mob member)` returning `boolean`, `StackManager.splitOne(Mob host)` returning `@Nullable Mob`, `StackManager.unstack(Mob host)` returning `int` (mobs released), `StackManager.countOf(Mob)` returning `int`.

- [ ] **Step 1: Write the failing gametest**

```java
package com.mobtimizer.stack;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class StackManagerGameTest {
    @GameTest
    public void mergeIncrementsCount(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        Cow member = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));

        helper.assertTrue(StackManager.merge(host, member), "matching cows should merge");
        helper.assertTrue(StackManager.countOf(host) == 2, "count should be 2");
        helper.succeed();
    }

    @GameTest
    public void differentTypesDoNotMerge(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        var pig = GameTestMobs.spawnPlain(helper, EntityTypes.PIG, new BlockPos(2, 2, 1));

        helper.assertFalse(StackManager.merge(host, pig), "a pig must not join a cow stack");
        helper.assertTrue(StackManager.countOf(host) == 1, "count should be unchanged");
        helper.succeed();
    }

    @GameTest
    public void unstackReleasesEveryMember(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1)));

        helper.assertTrue(StackManager.countOf(host) == 3, "three cows before unstack");
        helper.assertTrue(StackManager.unstack(host) == 2, "two members should be released");
        helper.assertTrue(StackManager.countOf(host) == 1, "host remains as a stack of 1");
        helper.succeed();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: FAIL — `StackManager` does not exist.

- [ ] **Step 3: Write `StackManager`**

```java
package com.mobtimizer.stack;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.display.StackNameplate;
import com.mobtimizer.identity.StackEligibility;
import com.mobtimizer.identity.StackKeyFactory;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/** The single entry point for changing a stack's membership. */
public final class StackManager {
    private StackManager() {}

    public static int countOf(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).memberCount();
    }

    public static boolean isStacked(Mob mob) {
        return countOf(mob) > 1;
    }

    /** Folds {@code member} into {@code host}. Returns false if they are not compatible. */
    public static boolean merge(Mob host, Mob member) {
        if (host == member) return false;
        if (!StackEligibility.canStack(host) || !StackEligibility.canStack(member)) return false;
        if (isStacked(member)) return false; // never merge a stack into a stack in phase 1
        if (!StackKeyFactory.of(host).equals(StackKeyFactory.of(member))) return false;

        DormantStore.INSTANCE.add(host, member);
        StackNameplate.refresh(host);
        return true;
    }

    /** Releases exactly one member as an independent mob, or null if the host is alone. */
    public static @Nullable Mob splitOne(Mob host) {
        Mob released = DormantStore.INSTANCE.takeOne(host);
        StackNameplate.refresh(host);
        return released;
    }

    /** Releases every member. Returns how many mobs were freed. */
    public static int unstack(Mob host) {
        int released = DormantStore.INSTANCE.takeAll(host).size();
        StackNameplate.refresh(host);
        return released;
    }
}
```

Refusing to merge a stack into a stack keeps phase 1 simple and costs nothing: the scanner always merges loose mobs into a host, so stack-into-stack never arises in normal play.

- [ ] **Step 4: Extend the `StackNameplate` stub**

Add to the Task 3 stub so this compiles; Task 11 implements it:

```java
    /** Filled in properly in Task 11. */
    public static void refresh(Mob host) {}
```

- [ ] **Step 5: Run the gametests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS, all three.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: StackManager merge, split and unstack"
```

---

### Task 9: Merge scanner

**Files:**
- Create: `src/main/java/com/mobtimizer/merge/MergeScanner.java`
- Modify: `src/main/java/com/mobtimizer/Mobtimizer.java` (register the tick callback)
- Test: gametest `src/gametest/java/com/mobtimizer/merge/MergeScannerGameTest.java`

**Interfaces:**
- Consumes: `StackManager.merge`, `StackEligibility.canStack`, `StackKeyFactory.of`, `ConfigManager.get()`, `Dormancy.followHost`.
- Produces: `MergeScanner.tick(ServerLevel level)`.

- [ ] **Step 1: Write the failing gametest**

The crowd gate is the behaviour most worth locking down — it is what keeps three pet cows from fusing.

```java
package com.mobtimizer.merge;

import com.mobtimizer.stack.StackManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class MergeScannerGameTest {
    @GameTest
    public void belowThresholdNothingMerges(GameTestHelper helper) {
        // Default crowdThreshold is 4; three cows must stay individual.
        Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));

        MergeScanner.tick(helper.getLevel());

        helper.assertTrue(StackManager.countOf(a) == 1, "three cows are below the crowd threshold");
        helper.succeed();
    }

    @GameTest
    public void atThresholdCowsMerge(GameTestHelper helper) {
        Cow a = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 2));
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 3));
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1));
        GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 2));

        MergeScanner.tick(helper.getLevel());

        helper.assertTrue(StackManager.countOf(a) > 1, "five nearby cows should form a stack");
        helper.succeed();
    }
}
```

**Verify:** `GameTestHelper.getLevel()` — confirm the accessor name for the test's `ServerLevel`.

- [ ] **Step 2: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: FAIL — `MergeScanner` does not exist.

- [ ] **Step 3: Write `MergeScanner`**

```java
package com.mobtimizer.merge;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.config.MobtimizerConfig;
import com.mobtimizer.freeze.Dormancy;
import com.mobtimizer.identity.StackEligibility;
import com.mobtimizer.identity.StackKey;
import com.mobtimizer.identity.StackKeyFactory;
import com.mobtimizer.stack.MobStack;
import com.mobtimizer.stack.StackManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MergeScanner {
    private static int tickCounter;

    private MergeScanner() {}

    public static void tick(ServerLevel level) {
        MobtimizerConfig config = ConfigManager.get();
        if (!config.merge.enabled) return;

        if (++tickCounter < config.merge.scanIntervalTicks) return;
        tickCounter = 0;

        int budget = config.merge.maxMergesPerScan;

        for (Mob candidate : collectCandidates(level)) {
            if (budget <= 0) break;
            if (!StackEligibility.canStack(candidate)) continue;
            if (Dormancy.isFrozen(candidate)) continue;
            if (candidate.isRemoved()) continue;

            budget -= mergeAround(candidate, config);
        }

        keepMembersWithHosts(level);
    }

    /**
     * Merges eligible neighbours into {@code host}, but only once enough of them
     * are present. Below the crowd threshold mobs are left completely vanilla,
     * so a few pet animals never fuse.
     */
    private static int mergeAround(Mob host, MobtimizerConfig config) {
        StackKey key = StackKeyFactory.of(host);
        AABB box = host.getBoundingBox().inflate(config.merge.radius);

        List<Mob> matches = new ArrayList<>();
        for (Mob other : host.level().getEntitiesOfClass(Mob.class, box)) {
            if (other == host) continue;
            if (other.getType() != host.getType()) continue;
            if (Dormancy.isFrozen(other)) continue;
            if (StackManager.isStacked(other)) continue;
            if (!StackEligibility.canStack(other)) continue;
            if (!StackKeyFactory.of(other).equals(key)) continue;
            matches.add(other);
        }

        // +1 counts the host itself towards the crowd.
        if (matches.size() + 1 < config.merge.crowdThreshold) return 0;

        int merged = 0;
        for (Mob other : matches) {
            if (StackManager.merge(host, other)) merged++;
        }
        return merged;
    }

    private static List<Mob> collectCandidates(ServerLevel level) {
        List<Mob> candidates = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && !Dormancy.isFrozen(mob)) {
                candidates.add(mob);
            }
        }
        return candidates;
    }

    /** Frozen members are parked on their host so a stack travels as one. */
    private static void keepMembersWithHosts(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof Mob host)) continue;
            if (!StackManager.isStacked(host)) continue;

            MobStack stack = host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);
            for (UUID id : stack.members()) {
                if (level.getEntity(id) instanceof Mob member) {
                    Dormancy.followHost(member, host);
                }
            }
        }
    }
}
```

**Verify:** `ServerLevel.getAllEntities()` and `Level.getEntitiesOfClass(Class, AABB)` — confirm both names in 26.2.

**Known limitation to record, not fix here:** `getAllEntities()` walks every entity in the level each scan. At the default 1-second interval that is acceptable and it keeps phase 1 simple, but it is the first thing to optimize if profiling shows the scanner itself costing time. Leave a comment saying so; do not pre-optimize it now.

- [ ] **Step 4: Register the tick callback**

In `Mobtimizer.onInitialize()`:

```java
net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK
        .register(MergeScanner::tick);
```

**Verify:** confirm `ServerTickEvents.END_WORLD_TICK` exists in Fabric API 0.158.0 and takes a `ServerLevel`.

- [ ] **Step 5: Run the gametests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS, both.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: throttled merge scanner with crowd gating"
```

---

### Task 10: Manual play-test checkpoint

**Files:** none.

This is the first point where the mod does something visible. Run it before layering more on top — a gametest passing and a farm feeling right are different things.

- [ ] **Step 1: Launch the game**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew runServer` (or `runClient` if Loom generated one).

- [ ] **Step 2: Test the crowd gate**

In a creative world, spawn 3 cows close together. Confirm they behave normally and do not merge. Spawn 2 more. Within about a second they should collapse into one visible cow.

- [ ] **Step 3: Confirm the members really are still there**

Run `/data get entity @e[type=cow,limit=1] ` on the host and confirm the `mobtimizer:stack` attachment lists member UUIDs. Confirm `/kill @e[type=cow]` removes all of them, host and frozen members alike — proving they are genuinely still entities.

- [ ] **Step 4: Confirm dormancy survives a reload**

Save and quit, reload the world, and confirm the stack is still merged and the members are still invisible and inert rather than all waking up.

- [ ] **Step 5: Record what you found**

If anything misbehaves, fix it now and add a gametest reproducing it before moving on. Do not carry a known bug into Task 11.

- [ ] **Step 6: Commit any fixes**

```bash
git add -A
git commit -m "fix: issues found in first play-test"
```

---

### Task 11: Nameplate

**Files:**
- Modify: `src/main/java/com/mobtimizer/display/StackNameplate.java` (replace the stub)
- Test: gametest `src/gametest/java/com/mobtimizer/display/StackNameplateGameTest.java`

**Interfaces:**
- Consumes: `StackManager.countOf`, `ConfigManager.get()`, `MobtimizerAttachments.STACK`.
- Produces: `StackNameplate.refresh(Mob host)`, `StackNameplate.isModOwnedName(Mob)` returning `boolean`. Both are already called from Tasks 3 and 8.

**The trap this task exists to avoid:** eligibility rejects custom-named mobs, and the count label *is* a custom name. Set it naively and every host becomes permanently ineligible the moment it is labelled — the stack silently stops growing, with no error anywhere. The `nameplateOwned` flag on `MobStack` is the fix, and the second gametest below is the regression guard.

- [ ] **Step 1: Write the failing gametest**

```java
package com.mobtimizer.display;

import com.mobtimizer.stack.StackManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class StackNameplateGameTest {
    @GameTest
    public void labelShowsTheCountAndIsHoverOnly(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));

        helper.assertTrue(host.hasCustomName(), "a stack should be labelled");
        helper.assertTrue(host.getCustomName().getString().contains("2"), "label should show the count");
        helper.assertFalse(host.isCustomNameVisible(), "label is hover-only by default");
        helper.succeed();
    }

    @GameTest
    public void modOwnedLabelDoesNotBlockFurtherMerging(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));
        // The host is now custom-named by us. It must still accept new members.
        helper.assertTrue(StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1))),
                "a mod-owned nameplate must not make the host ineligible");
        helper.assertTrue(StackManager.countOf(host) == 3, "count should reach 3");
        helper.succeed();
    }

    @GameTest
    public void unstackedHostLosesItsLabel(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));
        StackManager.unstack(host);

        helper.assertFalse(host.hasCustomName(), "a stack of 1 should have no label");
        helper.succeed();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: FAIL — the stub does nothing.

- [ ] **Step 3: Write `StackNameplate`**

```java
package com.mobtimizer.display;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.config.MobtimizerConfig;
import com.mobtimizer.stack.MobStack;
import com.mobtimizer.stack.StackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;

/**
 * Shows the member count using the host's vanilla custom name, so the count is
 * visible to unmodified clients with no client-side mod.
 */
public final class StackNameplate {
    private StackNameplate() {}

    /**
     * Whether this mob's custom name was set by Mobtimizer rather than a player.
     *
     * <p>{@code StackEligibility} rejects player-named mobs. Without this check
     * the count label would make its own host ineligible, and stacks would
     * silently stop growing.
     */
    public static boolean isModOwnedName(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).nameplateOwned();
    }

    public static void refresh(Mob host) {
        MobtimizerConfig config = ConfigManager.get();
        MobStack stack = host.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY);

        if (!config.display.enabled) return;

        int count = StackManager.countOf(host);

        if (count <= 1) {
            if (stack.nameplateOwned()) {
                host.setCustomName(null);
                host.setCustomNameVisible(false);
                host.setAttached(MobtimizerAttachments.STACK, stack.withNameplateOwned(false));
            }
            return;
        }

        String label = String.format(
                config.display.format,
                host.getType().getDescription().getString(),
                count);

        host.setCustomName(Component.literal(label));
        host.setCustomNameVisible(config.display.alwaysVisible);
        host.setAttached(MobtimizerAttachments.STACK, stack.withNameplateOwned(true));
    }
}
```

Setting the name in code does not set `PersistenceRequired` — only the name tag *item* does that — so labelling a stack does not accidentally make its mobs immune to despawning.

- [ ] **Step 4: Run the gametests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS, all three.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: hover-only count nameplate with mod-owned name tracking"
```

---

### Task 12: Commands and version guard

**Files:**
- Create: `src/main/java/com/mobtimizer/command/MobtimizerCommand.java`
- Create: `src/main/java/com/mobtimizer/safety/VersionGuard.java`
- Modify: `src/main/java/com/mobtimizer/Mobtimizer.java`
- Test: gametest `src/gametest/java/com/mobtimizer/command/UnstackGameTest.java`

**Interfaces:**
- Consumes: `StackManager.unstack`, `StackManager.isStacked`, `StackManager.countOf`, `ConfigManager`.
- Produces: `MobtimizerCommand.register(CommandDispatcher<CommandSourceStack>)`, `MobtimizerCommand.unstackAll(ServerLevel)` returning `int`, `MobtimizerCommand.unstackNear(ServerLevel, Vec3, double)` returning `int`, `VersionGuard.onServerStarted(MinecraftServer)`.

Commands delivered: `/mobtimizer unstack all`, `/mobtimizer unstack here [radius]`, `/mobtimizer unstack type <id>`, `/mobtimizer stats`, `/mobtimizer reload`.

`VersionGuard` is the safety net from spec §10: if the world was last opened on a different Minecraft version, unstack everything before anything else touches it.

- [ ] **Step 1: Write the failing gametest**

```java
package com.mobtimizer.command;

import com.mobtimizer.stack.StackManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class UnstackGameTest {
    @GameTest
    public void unstackRestoresIndependentMobs(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1)));

        int released = MobtimizerCommand.unstackAll(helper.getLevel());

        helper.assertTrue(released == 2, "two members should be released");
        helper.assertTrue(StackManager.countOf(host) == 1, "host is alone again");
        helper.succeed();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: FAIL — `MobtimizerCommand` does not exist.

- [ ] **Step 3: Write `MobtimizerCommand`**

```java
package com.mobtimizer.command;

import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.stack.StackManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class MobtimizerCommand {
    private MobtimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mobtimizer")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("unstack")
                        .then(Commands.literal("all").executes(ctx ->
                                report(ctx.getSource(), unstackAll(ctx.getSource().getLevel()))))
                        .then(Commands.literal("here")
                                .executes(ctx -> report(ctx.getSource(), unstackNear(
                                        ctx.getSource().getLevel(), ctx.getSource().getPosition(), 16.0)))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 256))
                                        .executes(ctx -> report(ctx.getSource(), unstackNear(
                                                ctx.getSource().getLevel(),
                                                ctx.getSource().getPosition(),
                                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                        .then(Commands.literal("type")
                                .then(Commands.argument("id", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String raw = StringArgumentType.getString(ctx, "id");
                                            Identifier id = Identifier.tryParse(raw);
                                            EntityType<?> type = id == null ? null
                                                    : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                                            if (type == null) {
                                                ctx.getSource().sendFailure(
                                                        Component.literal("Unknown entity type: " + raw));
                                                return 0;
                                            }
                                            return report(ctx.getSource(), unstackWhere(
                                                    ctx.getSource().getLevel(),
                                                    mob -> mob.getType() == type));
                                        }))))
                .then(Commands.literal("stats").executes(ctx -> {
                    int stacks = 0;
                    int mobs = 0;
                    for (var entity : ctx.getSource().getLevel().getAllEntities()) {
                        if (entity instanceof Mob mob && StackManager.isStacked(mob)) {
                            stacks++;
                            mobs += StackManager.countOf(mob);
                        }
                    }
                    int finalStacks = stacks;
                    int finalMobs = mobs;
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            finalMobs + " mobs held in " + finalStacks + " stacks"), false);
                    return stacks;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    ConfigManager.reload();
                    ctx.getSource().sendSuccess(() -> Component.literal("Config reloaded"), true);
                    return 1;
                })));
    }

    /** Visible for tests. Returns the number of members released. */
    public static int unstackAll(ServerLevel level) {
        return unstackWhere(level, mob -> true);
    }

    public static int unstackNear(ServerLevel level, Vec3 center, double radius) {
        double radiusSqr = radius * radius;
        return unstackWhere(level, mob -> mob.position().distanceToSqr(center) <= radiusSqr);
    }

    private static int unstackWhere(ServerLevel level, Predicate<Mob> filter) {
        // Collect first: unstacking mutates the level's entity set while we iterate.
        List<Mob> hosts = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && StackManager.isStacked(mob) && filter.test(mob)) {
                hosts.add(mob);
            }
        }

        int released = 0;
        for (Mob host : hosts) {
            released += StackManager.unstack(host);
        }
        return released;
    }

    private static int report(CommandSourceStack source, int released) {
        source.sendSuccess(() -> Component.literal("Unstacked " + released + " mobs"), true);
        return released;
    }
}
```

The entity type is taken as a plain string and resolved against the registry rather than using
`ResourceArgument`, which would require threading a `CommandBuildContext` through `register`.
The string form costs tab-completion but keeps the signature simple and cannot break when the
argument-type API shifts between versions.

**Verify:** `CommandSourceStack.getLevel()`, `getPosition()`, `sendSuccess(Supplier<Component>, boolean)`, `sendFailure(Component)`, `hasPermission(int)`, and `BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier)` — confirm each in 26.2.

- [ ] **Step 4: Write `VersionGuard`**

```java
package com.mobtimizer.safety;

import com.mobtimizer.Mobtimizer;
import com.mobtimizer.command.MobtimizerCommand;
import com.mobtimizer.config.ConfigManager;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unstacks everything when the world is opened on a different Minecraft version.
 *
 * <p>Fabric pins mods to a Minecraft version, so upgrading the game means
 * removing the mod to launch at all. Dormant members survive that on their own,
 * but this makes the transition explicit and covers virtual storage in phase 5.
 */
public final class VersionGuard {
    private static final String FILE_NAME = "mobtimizer-version.txt";

    private VersionGuard() {}

    public static void onServerStarted(MinecraftServer server) {
        if (!ConfigManager.get().safety.autoUnstackOnVersionChange) return;

        String current = SharedConstants.getCurrentVersion().getName();
        Path marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(FILE_NAME);

        String previous = null;
        try {
            if (Files.exists(marker)) {
                previous = Files.readString(marker).trim();
            }
        } catch (Exception e) {
            Mobtimizer.LOGGER.warn("Could not read {}", marker, e);
        }

        if (previous != null && !previous.equals(current)) {
            Mobtimizer.LOGGER.info("World last opened on {}, now {} — unstacking everything",
                    previous, current);
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
```

**Verify:** `SharedConstants.getCurrentVersion().getName()`, `MinecraftServer.getWorldPath(LevelResource)` and `LevelResource.ROOT` — confirm each in 26.2.

- [ ] **Step 5: Wire both into the entrypoint**

```java
net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT
        .register((dispatcher, registry, environment) -> MobtimizerCommand.register(dispatcher));

net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
        .register(VersionGuard::onServerStarted);
```

- [ ] **Step 6: Run the gametest**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: /mobtimizer commands and version-change unstack guard"
```

---

### Task 13: Persistence gametest and phase wrap-up

**Files:**
- Create: gametest `src/gametest/java/com/mobtimizer/stack/PersistenceGameTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new.

Persistence is the one property nothing so far proves, and it is the one most likely to be quietly broken — a stack that forgets its members on reload would look fine in every other test.

- [ ] **Step 1: Write the persistence gametest**

```java
package com.mobtimizer.stack;

import com.mobtimizer.MobtimizerAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.cow.Cow;

public class PersistenceGameTest {
    @GameTest
    public void stackStateSurvivesSerialization(GameTestHelper helper) {
        Cow host = GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(1, 2, 1));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(2, 2, 1)));
        StackManager.merge(host, GameTestMobs.spawnPlain(helper, EntityTypes.COW, new BlockPos(3, 2, 1)));

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, host.level().registryAccess());
        host.saveWithoutId(output);
        CompoundTag saved = output.buildResult();

        Cow reloaded = EntityTypes.COW.create(helper.getLevel(), null);
        reloaded.load(saved);

        helper.assertTrue(reloaded.getAttached(MobtimizerAttachments.STACK) != null,
                "the stack attachment must persist");
        helper.assertTrue(StackManager.countOf(reloaded) == 3,
                "member count must survive a save/load round trip");
        helper.succeed();
    }
}
```

**Verify:** `EntityType.create(Level, ...)` and `Entity.load(CompoundTag)` — both may use the new `ValueInput` API confirmed in Task 4. Adjust to match.

- [ ] **Step 2: Run the full suite**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build`
Expected: PASS — every unit test and gametest.

- [ ] **Step 3: Second manual play-test**

Build a pen with 30 cows. Confirm:
- they collapse to one visible cow labelled `Cow ×30` when you look at it
- the label is invisible when you are not looking at it
- `/mobtimizer stats` reports 30 mobs in 1 stack
- `/mobtimizer unstack all` gives back 30 individual cows
- leaving and re-entering the world preserves the stack
- F3 entity count reflects the merge

- [ ] **Step 4: Update the README status section**

Replace the "In design" status with:

```markdown
## Status

Phase 1 complete: mobs merge, members are kept as frozen entities, counts show on hover,
and `/mobtimizer unstack` restores everything. Damage routing, breeding and production
scaling are phases 2–4 — see [the design spec](docs/superpowers/specs/2026-08-25-mobtimizer-design.md).
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: stack persistence across save/load; document phase 1 status"
```

---

## Phase 1 done when

- `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew build` passes with every unit test and gametest green
- 30 cows in a pen render as one labelled cow and tick as one
- `/mobtimizer unstack all` returns exactly 30 individual cows
- stacks survive a world reload
- removing the mod jar and reloading the world returns all 30 cows to normal — the property the whole dormant design exists to provide

That last check is the one worth doing by hand before calling the phase finished. It is the mod's core safety promise and nothing in the automated suite can prove it.
