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
