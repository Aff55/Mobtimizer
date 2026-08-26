package com.mobtimizer;

import com.mobtimizer.stack.MobStack;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

public final class MobtimizerAttachments {
    public static AttachmentType<MobStack> STACK;

    private MobtimizerAttachments() {}

    public static void register() {
        // AttachmentRegistry.builder() is deprecated in favour of this Identifier-first
        // entry point; the Builder it hands to the consumer is the same type with the
        // same persistent()/initializer() methods, so behaviour is unchanged.
        STACK = AttachmentRegistry.create(Mobtimizer.id("stack"), builder -> builder
                .persistent(MobStack.CODEC)
                .initializer(() -> MobStack.EMPTY));
    }
}
