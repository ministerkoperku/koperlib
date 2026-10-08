package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KenderPickPayload(KenderHitRef hit, boolean includeData) implements CustomPacketPayload {
    public static final Type<KenderPickPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_pick"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KenderPickPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            payload.hit.write(buf);
            buf.writeBoolean(payload.includeData);
        },
        buf -> new KenderPickPayload(KenderHitRef.read(buf), buf.readBoolean())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
