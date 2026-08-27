package com.mobtimizer;

import com.mobtimizer.stack.MobStack;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

public final class MobtimizerAttachments {
    public static AttachmentType<MobStack> STACK;

    /**
     * Whether a mob is a frozen stack member. Persistent so a member stays frozen
     * across a save/load cycle - see {@link com.mobtimizer.freeze.Dormancy}. Absent
     * (not just {@code false}) on every mob that has never been stacked, exactly
     * like {@link #STACK}.
     */
    public static AttachmentType<Boolean> FROZEN;

    private MobtimizerAttachments() {}

    public static void register() {
        // AttachmentRegistry.builder() is deprecated in favour of this Identifier-first
        // entry point; the Builder it hands to the consumer is the same type with the
        // same persistent()/initializer() methods, so behaviour is unchanged.
        STACK = AttachmentRegistry.create(Mobtimizer.id("stack"), builder -> builder
                .persistent(MobStack.CODEC)
                .initializer(() -> MobStack.EMPTY));

        FROZEN = AttachmentRegistry.create(Mobtimizer.id("frozen"), builder -> builder
                .persistent(Codec.BOOL)
                .initializer(() -> Boolean.FALSE));
    }
}
