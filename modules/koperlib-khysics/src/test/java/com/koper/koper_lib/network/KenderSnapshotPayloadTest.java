package com.koper.koper_lib.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KenderSnapshotPayloadTest {
    @Test void ordinaryGeometryRoundTripsWithSparseTags() {
        var tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        tag.putInt("counter", 42);
        var p = new KenderSpawnPayload(123, new float[]{1, 2, 3}, new float[]{0, .5f, 0, .866f},
            new int[]{4, 9, 4}, new float[]{-.25f, .5f, 0, .75f, .5f, 0, 1.75f, .5f, 0},
            new int[]{0,0,0, 1,0,0, 2,0,0}, new CompoundTag[]{null, tag, null});
        roundTrip(p);
    }

    @Test void extremeCoordinatesAndFloatOverridesRemainExact() {
        var p = new KenderSpawnPayload(123, new float[]{Float.NaN, Float.NaN, Float.NaN}, new float[]{0,0,0,1},
            new int[]{1,2,3}, new float[]{-0f, 0, .125f, Float.intBitsToFloat(0x7fc01234), 3.25f, -.75f, 123.456f, -22, 8},
            new int[]{Integer.MIN_VALUE, -1, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, Integer.MIN_VALUE, 0, -500, 100},
            new CompoundTag[3]);
        roundTrip(p);
    }

    @Test void emptyBodyRoundTrips() {
        roundTrip(new KenderSpawnPayload(99, new float[3], new float[]{0,0,0,1}, new int[0], new float[0], new int[0], new CompoundTag[0]));
    }

    @Test void variedOffsetsRoundTripWithoutRoundingLoss() {
        var random = new java.util.Random(263);
        int n = 250;
        int[] states = new int[n], locals = new int[n * 3];
        float[] offsets = new float[n * 3];
        for (int i = 0; i < n; i++) {
            states[i] = random.nextInt(20);
            for (int axis = 0; axis < 3; axis++) {
                locals[i*3+axis] = random.nextInt();
                offsets[i*3+axis] = Float.intBitsToFloat(random.nextInt());
            }
        }
        roundTrip(new KenderSpawnPayload(89, new float[3], new float[]{0,0,0,1}, states, offsets, locals, new CompoundTag[n]));
    }

    @Test void uniformHundredThousandBlocksStayBelowOneMegabyte() {
        int n = 100_000;
        int[] states = new int[n], locals = new int[n * 3];
        float[] offsets = new float[n * 3];
        for (int i = 0; i < n; i++) {
            states[i] = 7;
            locals[i*3] = i % 100;
            locals[i*3+1] = (i / 100) % 100;
            locals[i*3+2] = i / 10_000;
            for (int axis = 0; axis < 3; axis++) offsets[i*3+axis] = locals[i*3+axis] - .25f;
        }
        var p = new KenderSpawnPayload(88, new float[3], new float[]{0,0,0,1}, states, offsets, locals, new CompoundTag[n]);
        var buf = buffer();
        try {
            KenderSnapshotPayload.CODEC.encode(buf, new KenderSnapshotPayload(321, p));
            assertTrue(buf.readableBytes() < 1_000_000, "snapshot is " + buf.readableBytes() + " bytes");
            assertGeometry(p, KenderSnapshotPayload.CODEC.decode(buf).geometry());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test void malformedCountsFailBeforeAllocation() {
        final var oversized = header(Integer.MAX_VALUE);
        try { assertThrows(RuntimeException.class, () -> KenderSnapshotPayload.CODEC.decode(oversized)); }
        finally { oversized.release(); }
        final var negative = header(-1);
        try { assertThrows(RuntimeException.class, () -> KenderSnapshotPayload.CODEC.decode(negative)); }
        finally { negative.release(); }
    }

    @Test void invalidPaletteAndTagIndicesFail() {
        var palette = header(1);
        try {
            palette.writeVarInt(2); palette.writeVarInt(1); palette.writeVarInt(2);
            palette.writeZero(40);
            assertThrows(RuntimeException.class, () -> KenderSnapshotPayload.CODEC.decode(palette));
        } finally { palette.release(); }
        for (int bad : new int[]{2, -1}) {
            var badIndex = header(2);
            try {
                badIndex.writeVarInt(2); badIndex.writeVarInt(1); badIndex.writeVarInt(2);
                badIndex.writeZero(12); badIndex.writeVarInt(bad); badIndex.writeZero(8); badIndex.writeVarInt(0);
                assertThrows(DecoderException.class, () -> KenderSnapshotPayload.CODEC.decode(badIndex));
            } finally { badIndex.release(); }
        }
        var index = header(1);
        try {
            index.writeVarInt(1); index.writeVarInt(1); index.writeZero(12);
            index.writeVarInt(0); index.writeVarInt(0); index.writeVarInt(0); index.writeByte(8);
            index.writeVarInt(0);
            assertThrows(RuntimeException.class, () -> KenderSnapshotPayload.CODEC.decode(index));
        } finally { index.release(); }
        var tags = header(1);
        try {
            tags.writeVarInt(1); tags.writeVarInt(1); tags.writeZero(12);
            tags.writeVarInt(0); tags.writeVarInt(0); tags.writeVarInt(0); tags.writeByte(0);
            tags.writeVarInt(1); tags.writeVarInt(1); tags.writeNbt(new CompoundTag());
            assertThrows(RuntimeException.class, () -> KenderSnapshotPayload.CODEC.decode(tags));
        } finally { tags.release(); }
    }

    private static RegistryFriendlyByteBuf header(int n) {
        var buf = buffer();
        buf.writeLong(321); buf.writeLong(88); buf.writeZero(28); buf.writeVarInt(n);
        return buf;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void roundTrip(KenderSpawnPayload p) {
        var buf = buffer();
        try {
            KenderSnapshotPayload.CODEC.encode(buf, new KenderSnapshotPayload(321, p));
            var decoded = KenderSnapshotPayload.CODEC.decode(buf);
            assertEquals(321, decoded.serverTick());
            assertGeometry(p, decoded.geometry());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    private static void assertGeometry(KenderSpawnPayload expected, KenderSpawnPayload actual) {
        assertEquals(expected.kontraId(), actual.kontraId());
        assertRawFloats(expected.pos(), actual.pos());
        assertRawFloats(expected.rot(), actual.rot());
        assertArrayEquals(expected.blockStateIds(), actual.blockStateIds());
        assertArrayEquals(expected.locals(), actual.locals());
        assertRawFloats(expected.offsets(), actual.offsets());
        assertArrayEquals(expected.blockEntityTags(), actual.blockEntityTags());
    }

    private static void assertRawFloats(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "float " + i);
    }
}
