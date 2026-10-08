package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// C2S — sneak+attack on kontraktion, tosses it up and spins it
public record KenderSelfRightPayload(long kontraId) implements CustomPacketPayload {

    public static final Type<KenderSelfRightPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_self_right"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderSelfRightPayload> CODEC = StreamCodec.of(
        (buf, p) -> buf.writeLong(p.kontraId()),
        buf -> new KenderSelfRightPayload(buf.readLong())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
