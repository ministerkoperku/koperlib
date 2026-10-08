package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxPropertiesPayload(long id, int color, int color2, float radius, float thickness)
        implements CustomPacketPayload {
    public static final Type<KfxPropertiesPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_properties"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxPropertiesPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.id);
            buf.writeInt(p.color);
            buf.writeInt(p.color2);
            buf.writeFloat(p.radius);
            buf.writeFloat(p.thickness);
        },
        buf -> new KfxPropertiesPayload(buf.readLong(), buf.readInt(), buf.readInt(),
            buf.readFloat(), buf.readFloat())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
