package com.mobtimizer.display;

import com.mobtimizer.MobtimizerAttachments;
import com.mobtimizer.config.MobtimizerConfig;
import com.mobtimizer.config.ConfigManager;
import com.mobtimizer.stack.MobStack;
import com.mobtimizer.stack.StackManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;

/**
 * Shows a stack's member count using the host's own vanilla custom name, so the count is
 * visible to completely unmodified clients with no client-side mod required.
 *
 * <p><b>The trap this class is built around.</b> {@link
 * com.mobtimizer.identity.StackEligibility} rejects custom-named mobs, on the grounds
 * that a player-assigned name means "this one is special" and merging it away destroys
 * that intent. But the count label <em>is</em> a custom name. Set naively, it would make
 * its own host ineligible the instant it was applied, and every stack would silently
 * stop growing at exactly two members - no error, no warning, nothing in the log. The
 * {@code nameplateOwned} flag on {@link MobStack} is what separates a label this mod set
 * from one a player set; {@code StackEligibility} consults {@link #isModOwnedName} and
 * so ignores only our own.
 *
 * <p>Setting a name in code does not set {@code PersistenceRequired} - only the name tag
 * <em>item</em> does that - so labelling a stack does not accidentally make its mobs
 * immune to despawning.
 */
public final class StackNameplate {
    private StackNameplate() {}

    /**
     * Whether this mob's custom name was set by Mobtimizer rather than by a player.
     *
     * <p>Read straight off the persistent {@code STACK} attachment rather than by
     * pattern-matching the name text: a player is perfectly entitled to name a cow
     * "Cow ×4", and guessing from the string would then treat their name as ours and
     * quietly overwrite or delete it.
     */
    public static boolean isModOwnedName(Mob mob) {
        return mob.getAttachedOrElse(MobtimizerAttachments.STACK, MobStack.EMPTY).nameplateOwned();
    }

    /**
     * Brings the host's label into line with its current member count. Called from
     * {@link StackManager} after every merge, split and unstack.
     *
     * <p>Only ever clears a name it previously claimed ({@code nameplateOwned}), so a
     * player-named mob that never carried our label keeps its name untouched.
     */
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
