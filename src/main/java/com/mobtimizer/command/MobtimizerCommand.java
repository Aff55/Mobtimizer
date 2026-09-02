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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * {@code /mobtimizer} - the escape hatch. Whatever else goes wrong, an operator can
 * always put every mob back the way it was.
 *
 * <p><b>Permission API correction.</b> The obvious {@code source.hasPermission(2)} does
 * not exist in 26.2 - {@code CommandSourceStack} carries a {@code PermissionSet} instead
 * (via {@code permissions()}), and the idiomatic predicate is
 * {@code Commands.hasPermission(PermissionCheck)}, with {@code Commands.LEVEL_GAMEMASTERS}
 * as the named constant for what used to be level 2. Verified by disassembly rather than
 * assumed: {@code PermissionProviderCheck} implements {@code Predicate<T>}, and
 * {@code CommandSourceStack} implements {@code ExecutionCommandSource}, which extends
 * {@code PermissionSetSupplier} - so it drops straight into {@code requires}.
 *
 * <p>The entity type is taken as a plain string and resolved against the registry rather
 * than using {@code ResourceArgument}, which would require threading a
 * {@code CommandBuildContext} through here. That costs tab-completion but keeps the
 * signature simple and cannot break when the argument-type API shifts between versions.
 */
public final class MobtimizerCommand {
    private MobtimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mobtimizer")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
