package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxDetachPayload(long id) implements CustomPacketPayload {
    public static final Type<KfxDetachPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_detach"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KfxDetachPayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeLong(payload.id),
        buf -> new KfxDetachPayload(buf.readLong())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
