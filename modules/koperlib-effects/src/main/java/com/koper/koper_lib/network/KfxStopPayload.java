package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxStopPayload(long id) implements CustomPacketPayload {
    public static final Type<KfxStopPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_stop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxStopPayload> CODEC = StreamCodec.of(
        (buf, p) -> buf.writeLong(p.id),
        buf -> new KfxStopPayload(buf.readLong())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
