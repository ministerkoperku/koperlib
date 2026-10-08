package com.koper.koper_lib.network;

import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2IntLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KenderSnapshotPayload(long serverTick, KenderSpawnPayload geometry) implements CustomPacketPayload {
    public static final Type<KenderSnapshotPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KenderSnapshotPayload> CODEC =
        StreamCodec.of(KenderSnapshotPayload::encode, KenderSnapshotPayload::decode);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void encode(RegistryFriendlyByteBuf buf, KenderSnapshotPayload snapshot) {
        var p = snapshot.geometry();
        int n = p.blockStateIds().length;
        if (n > Integer.MAX_VALUE / 3 || p.pos().length != 3 || p.rot().length != 4
                || p.offsets().length != n * 3 || p.locals().length != n * 3 || p.blockEntityTags() == null)
            throw new IllegalArgumentException("invalid geometry arrays");
        buf.writeLong(snapshot.serverTick());
        buf.writeLong(p.kontraId());
        writeFloats(buf, p.pos());
        writeFloats(buf, p.rot());
        buf.writeVarInt(n);
        var palette = new Int2IntLinkedOpenHashMap();
        palette.defaultReturnValue(-1);
        for (int state : p.blockStateIds()) {
            if (state < 0) throw new IllegalArgumentException("negative block state id");
            if (!palette.containsKey(state)) palette.put(state, palette.size());
        }
        buf.writeVarInt(palette.size());
        for (int state : palette.keySet()) buf.writeVarInt(state);
        float[] base = new float[3];
        if (n > 0) for (int axis = 0; axis < 3; axis++) base[axis] = p.offsets()[axis] - p.locals()[axis];
        writeFloats(buf, base);
        int[] previous = new int[3];
        for (int i = 0; i < n; i++) {
            if (palette.size() > 1) buf.writeVarInt(palette.get(p.blockStateIds()[i]));
            int flags = 0;
            for (int axis = 0; axis < 3; axis++) {
                int local = p.locals()[i * 3 + axis];
                int delta = local - previous[axis];
                buf.writeVarInt((delta << 1) ^ (delta >> 31));
                previous[axis] = local;
                // Most hulls share one fractional frame. Micro blocks and rounding outliers keep their exact bits.
                if (Float.floatToRawIntBits(p.offsets()[i * 3 + axis]) != Float.floatToRawIntBits(local + base[axis]))
                    flags |= 1 << axis;
            }
            buf.writeByte(flags);
            for (int axis = 0; axis < 3; axis++)
                if ((flags & (1 << axis)) != 0) buf.writeInt(Float.floatToRawIntBits(p.offsets()[i * 3 + axis]));
        }
        int tags = 0;
        for (int i = 0; i < Math.min(n, p.blockEntityTags().length); i++) if (p.blockEntityTags()[i] != null) tags++;
        buf.writeVarInt(tags);
        for (int i = 0; i < Math.min(n, p.blockEntityTags().length); i++) {
            if (p.blockEntityTags()[i] == null) continue;
            buf.writeVarInt(i);
            buf.writeNbt(p.blockEntityTags()[i]);
        }
    }

    private static KenderSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        long tick = buf.readLong(), id = buf.readLong();
        float[] pos = readFloats(buf, 3), rot = readFloats(buf, 4);
        int n = buf.readVarInt();
        // Every cell needs at least three coordinate bytes and one override flag.
        if (n < 0 || n > Integer.MAX_VALUE / 3 || n > buf.readableBytes() / 4)
            throw new DecoderException("invalid geometry cell count: " + n);
        int size = buf.readVarInt();
        if (size < 0 || size > n || (n > 0 && size == 0) || size > buf.readableBytes())
            throw new DecoderException("invalid geometry palette size: " + size);
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = buf.readVarInt();
            if (palette[i] < 0) throw new DecoderException("negative block state id");
        }
        float[] base = readFloats(buf, 3);
        if (n > buf.readableBytes() / 4) throw new DecoderException("truncated geometry cells");
        int[] states = new int[n], locals = new int[n * 3];
        float[] offsets = new float[n * 3];
        int[] previous = new int[3];
        for (int i = 0; i < n; i++) {
            int index = size > 1 ? buf.readVarInt() : 0;
            if (index < 0 || index >= size) throw new DecoderException("invalid geometry palette index: " + index);
            states[i] = palette[index];
            for (int axis = 0; axis < 3; axis++) {
                int delta = buf.readVarInt();
                previous[axis] += (delta >>> 1) ^ -(delta & 1);
                locals[i * 3 + axis] = previous[axis];
                offsets[i * 3 + axis] = previous[axis] + base[axis];
            }
            int flags = buf.readUnsignedByte();
            if ((flags & ~7) != 0) throw new DecoderException("invalid geometry override flags: " + flags);
            for (int axis = 0; axis < 3; axis++)
                if ((flags & (1 << axis)) != 0) offsets[i * 3 + axis] = Float.intBitsToFloat(buf.readInt());
        }
        int count = buf.readVarInt();
        if (count < 0 || count > n || count > buf.readableBytes()) throw new DecoderException("invalid geometry tag count: " + count);
        var tags = new CompoundTag[n];
        int last = -1;
        for (int i = 0; i < count; i++) {
            int index = buf.readVarInt();
            if (index <= last || index >= n) throw new DecoderException("invalid geometry tag index: " + index);
            last = index;
            tags[index] = buf.readNbt();
            if (tags[index] == null) throw new DecoderException("missing geometry tag");
        }
        return new KenderSnapshotPayload(tick, new KenderSpawnPayload(id, pos, rot, states, offsets, locals, tags));
    }

    private static void writeFloats(RegistryFriendlyByteBuf buf, float[] values) {
        for (float value : values) buf.writeInt(Float.floatToRawIntBits(value));
    }

    private static float[] readFloats(RegistryFriendlyByteBuf buf, int length) {
        float[] values = new float[length];
        for (int i = 0; i < length; i++) values[i] = Float.intBitsToFloat(buf.readInt());
        return values;
    }
}
