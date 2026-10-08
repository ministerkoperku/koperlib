package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ReloadResourcesPayload() implements CustomPacketPayload {
    public static final Type<ReloadResourcesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "reload_resources"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReloadResourcesPayload> CODEC = StreamCodec.of(
        (buf, payload) -> { /* no data */ },
        buf -> new ReloadResourcesPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
