package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;

// S2C — sent once when a kontraktion is born
// tells client what blocks + offsets live in it (block state ids are registry ints)
public record KenderSpawnPayload(
    long kontraId,
    float[] pos,          // [cx, cy, cz] initial physics center
    float[] rot,          // [qx, qy, qz, qw] initial rotation
    int[] blockStateIds,  // parallel to offsets — Block.getId(state) — raw registry int
    float[] offsets,      // flat [ox0,oy0,oz0, ox1,oy1,oz1, ...] relative to center
    int[] locals,         // flat [lx0,ly0,lz0, ...] — the SERVER's key for each block, verbatim
    CompoundTag[] blockEntityTags
) implements CustomPacketPayload {

    public KenderSpawnPayload(long kontraId, float[] pos, float[] rot, int[] blockStateIds, float[] offsets) {
        this(kontraId, pos, rot, blockStateIds, offsets, roundedLocals(offsets),
            new CompoundTag[blockStateIds.length]);
    }

    // only for the odd caller that has no grid entry to ask — rounding is what we're moving away from
    private static int[] roundedLocals(float[] offsets) {
        int[] locals = new int[offsets.length];
        for (int i = 0; i < offsets.length; i++) locals[i] = Math.round(offsets[i]);
        return locals;
    }

    public static final Type<KenderSpawnPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_spawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderSpawnPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.kontraId());
            for (float f : p.pos()) buf.writeFloat(f);
            for (float f : p.rot()) buf.writeFloat(f);
            int n = p.blockStateIds().length;
            buf.writeInt(n);
            for (int id : p.blockStateIds()) buf.writeInt(id);
            for (float f : p.offsets()) buf.writeFloat(f);
            for (int l : p.locals()) buf.writeInt(l);
            for (int i = 0; i < n; i++) {
                CompoundTag tag = i < p.blockEntityTags().length ? p.blockEntityTags()[i] : null;
                buf.writeBoolean(tag != null);
                if (tag != null) buf.writeNbt(tag);
            }
        },
        buf -> {
            long id = buf.readLong();
            float[] pos = { buf.readFloat(), buf.readFloat(), buf.readFloat() };
            float[] rot = { buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat() };
            int n = buf.readInt();
            int[] stateIds = new int[n];
            for (int i = 0; i < n; i++) stateIds[i] = buf.readInt();
            float[] offsets = new float[n * 3];
            for (int i = 0; i < n * 3; i++) offsets[i] = buf.readFloat();
            int[] locals = new int[n * 3];
            for (int i = 0; i < n * 3; i++) locals[i] = buf.readInt();
            CompoundTag[] blockEntityTags = new CompoundTag[n];
            for (int i = 0; i < n; i++) if (buf.readBoolean()) blockEntityTags[i] = buf.readNbt();
            return new KenderSpawnPayload(id, pos, rot, stateIds, offsets, locals, blockEntityTags);
        }
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
