package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxAttachPayload(
    long id,
    int entityId,
    float ox, float oy, float oz,
    float ex, float ey, float ez,
    boolean endRelative
) implements CustomPacketPayload {
    public static final Type<KfxAttachPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_attach"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxAttachPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.id);
            buf.writeInt(p.entityId);
            buf.writeFloat(p.ox); buf.writeFloat(p.oy); buf.writeFloat(p.oz);
            buf.writeFloat(p.ex); buf.writeFloat(p.ey); buf.writeFloat(p.ez);
            buf.writeBoolean(p.endRelative);
        },
        buf -> new KfxAttachPayload(buf.readLong(), buf.readInt(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readBoolean())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
