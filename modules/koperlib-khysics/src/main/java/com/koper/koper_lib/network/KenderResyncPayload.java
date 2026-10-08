package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// C2S — "my copy of this kontra doesn't hash the same as yours, send it again please".
// only fired after two bad stamps in a row so a spawn payload crossing the wire never triggers it.
public record KenderResyncPayload(long kontraId) implements CustomPacketPayload {

    public static final Type<KenderResyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_resync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderResyncPayload> CODEC = StreamCodec.of(
        (buf, p) -> buf.writeLong(p.kontraId()),
        buf -> new KenderResyncPayload(buf.readLong())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
