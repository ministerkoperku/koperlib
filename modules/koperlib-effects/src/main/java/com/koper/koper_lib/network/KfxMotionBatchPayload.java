package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record KfxMotionBatchPayload(List<Motion> motions) implements CustomPacketPayload {
    public static final int MAX_MOTIONS = 128;
    public static final Type<KfxMotionBatchPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_motion_batch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KfxMotionBatchPayload> CODEC = StreamCodec.of(
        KfxMotionBatchPayload::write, KfxMotionBatchPayload::read);

    public KfxMotionBatchPayload {
        if (motions == null || motions.size() > MAX_MOTIONS) {
            throw new IllegalArgumentException("KFX motion batch exceeds " + MAX_MOTIONS);
        }
        motions = List.copyOf(motions);
    }

    private static void write(RegistryFriendlyByteBuf buf, KfxMotionBatchPayload payload) {
        buf.writeVarInt(payload.motions.size());
        for (Motion it : payload.motions) {
            buf.writeLong(it.id);
            buf.writeFloat(it.sx); buf.writeFloat(it.sy); buf.writeFloat(it.sz);
            buf.writeFloat(it.ex); buf.writeFloat(it.ey); buf.writeFloat(it.ez);
        }
    }

    private static KfxMotionBatchPayload read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_MOTIONS) throw new IllegalArgumentException("bad KFX motion batch size " + size);
        var motions = new ArrayList<Motion>(size);
        for (int i = 0; i < size; i++) {
            motions.add(new Motion(buf.readLong(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return new KfxMotionBatchPayload(motions);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Motion(long id, float sx, float sy, float sz, float ex, float ey, float ez) {}
}
