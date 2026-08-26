package com.mobtimizer.stack;

import com.mojang.serialization.JsonOps;
import net.minecraft.core.UUIDUtil;
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

    /**
     * Review finding on Task 5: {@code withMember} appended unconditionally, so
     * {@code EMPTY.withMember(A).withMember(A)} produced {@code members=[A, A]} and a
     * memberCount of 3 for what is really one distinct extra mob. The human ruling was
     * to make this a silent no-op - no logging, no exception - which this test pins
     * down directly, including that the no-op returns the exact same instance.
     */
    @Test
    void withMemberIgnoresADuplicateUuid() {
        MobStack stack = MobStack.EMPTY.withMember(A);
        MobStack same = stack.withMember(A);

        assertSame(stack, same, "adding an already-present UUID must be a genuine no-op");
        assertEquals(2, same.memberCount(), "adding the same UUID twice must not double-count it");
        assertEquals(List.of(A), same.members());
    }

    /**
     * The compact constructor de-duplicates too, not just {@code withMember}: this is
     * what protects a future direct {@code new MobStack(...)} call (or a Phase 3
     * mutator that forgets to check) from reintroducing the same bug from another
     * angle. See {@link #codecDecodingDeduplicatesMembers} for the saved-data case.
     */
    @Test
    void constructorDeduplicatesMembers() {
        MobStack stack = new MobStack(List.of(A, A, B), false);

        assertEquals(3, stack.memberCount(), "constructing directly with a duplicate UUID must not double-count it");
        assertEquals(List.of(A, B), stack.members());
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

    /**
     * A hand-edited or otherwise corrupted save might list the same UUID twice.
     * Decoding it must dedupe rather than throw or hand back a MobStack that still
     * contains the duplicate - the scenario the review finding worried about under
     * "a future direct new MobStack(...) call" applies just as much to the codec's
     * own constructor reference.
     */
    @Test
    void codecDecodingDeduplicatesMembers() {
        var encodedMembers = UUIDUtil.CODEC.listOf()
                .encodeStart(JsonOps.INSTANCE, List.of(A, A, B))
                .getOrThrow(IllegalStateException::new);
        var json = com.google.gson.JsonParser.parseString("{\"members\": " + encodedMembers + "}");

        MobStack decoded = MobStack.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow(IllegalStateException::new);

        assertEquals(3, decoded.memberCount(), "a duplicate UUID in saved data must not survive decoding as a double-count");
        assertEquals(List.of(A, B), decoded.members());
    }
}
