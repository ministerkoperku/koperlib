package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// S2C — sent when a kontraktion is removed (landed or destroyed)
public record KenderRemovePayload(long kontraId) implements CustomPacketPayload {

    public static final Type<KenderRemovePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderRemovePayload> CODEC = StreamCodec.of(
        (buf, p) -> buf.writeLong(p.kontraId()),
        buf -> new KenderRemovePayload(buf.readLong())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
