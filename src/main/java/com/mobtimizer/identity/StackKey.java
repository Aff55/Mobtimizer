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
