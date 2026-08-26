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
