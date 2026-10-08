package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxUpdatePayload(
    long id,
    float sx, float sy, float sz,
    float ex, float ey, float ez
) implements CustomPacketPayload {
    public static final Type<KfxUpdatePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxUpdatePayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.id);
            buf.writeFloat(p.sx); buf.writeFloat(p.sy); buf.writeFloat(p.sz);
            buf.writeFloat(p.ex); buf.writeFloat(p.ey); buf.writeFloat(p.ez);
        },
        buf -> new KfxUpdatePayload(buf.readLong(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
