package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// entities standing on one deck this tick, with their feet in the deck's own frame (3 floats each)
public record KontraStandersPayload(long body, int[] entities, float[] locals) implements CustomPacketPayload {
    public static final Type<KontraStandersPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kontra_standers"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KontraStandersPayload> CODEC = StreamCodec.of(
        (b, p) -> {
            b.writeLong(p.body);
            b.writeVarInt(p.entities.length);
            for (int i = 0; i < p.entities.length; i++) {
                b.writeVarInt(p.entities[i]);
                b.writeFloat(p.locals[i * 3]);
                b.writeFloat(p.locals[i * 3 + 1]);
                b.writeFloat(p.locals[i * 3 + 2]);
            }
        },
        b -> {
            long body = b.readLong();
            int n = b.readVarInt();
            if (n < 0 || n > 4096) throw new IllegalArgumentException("kontra standers count " + n);
            int[] ids = new int[n];
            float[] locals = new float[n * 3];
            for (int i = 0; i < n; i++) {
                ids[i] = b.readVarInt();
                locals[i * 3] = b.readFloat();
                locals[i * 3 + 1] = b.readFloat();
                locals[i * 3 + 2] = b.readFloat();
            }
            return new KontraStandersPayload(body, ids, locals);
        });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
