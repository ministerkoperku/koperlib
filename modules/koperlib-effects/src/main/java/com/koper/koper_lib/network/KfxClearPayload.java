package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxClearPayload() implements CustomPacketPayload {
    public static final Type<KfxClearPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_clear"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxClearPayload> CODEC = StreamCodec.of(
        (buf, p) -> {},
        buf -> new KfxClearPayload()
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
