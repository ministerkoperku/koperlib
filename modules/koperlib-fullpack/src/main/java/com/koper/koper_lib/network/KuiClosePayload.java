package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// koper.gui.close(player) -> close a plain kui screen client-side (menus close server-side via closeContainer)
public record KuiClosePayload() implements CustomPacketPayload {
    public static final Type<KuiClosePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kui_close"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KuiClosePayload> CODEC =
        StreamCodec.unit(new KuiClosePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
