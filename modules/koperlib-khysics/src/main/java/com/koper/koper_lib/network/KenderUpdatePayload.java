package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// S2C — sent every server tick with all active kontraktion transforms
// serverTick keeps client render interpolation on a stable 20Hz timeline.
public record KenderUpdatePayload(long serverTick, long[] ids, float[][] transforms) implements CustomPacketPayload {

    public static final Type<KenderUpdatePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderUpdatePayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.serverTick());
            buf.writeInt(p.ids().length);
            for (int i = 0; i < p.ids().length; i++) {
                buf.writeLong(p.ids()[i]);
                for (float f : p.transforms()[i]) buf.writeFloat(f);
            }
        },
        buf -> {
            long serverTick = buf.readLong();
            int n = buf.readInt();
            long[] ids = new long[n];
            // 8 floats per kontra: pos3, rot4, flags (bit-ish: aligned = 1.0)
            float[][] t = new float[n][8];
            for (int i = 0; i < n; i++) {
                ids[i] = buf.readLong();
                for (int j = 0; j < 8; j++) t[i][j] = buf.readFloat();
            }
            return new KenderUpdatePayload(serverTick, ids, t);
        }
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
