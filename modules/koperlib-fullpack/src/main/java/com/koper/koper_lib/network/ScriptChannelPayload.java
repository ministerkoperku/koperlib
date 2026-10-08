package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// C2S — a free form channel a pack can talk on. koper.network.on_receive listens for it.
// payload is whatever string the sender put in; packs usually stuff json in there
public record ScriptChannelPayload(String channel, String data) implements CustomPacketPayload {

    public static final Type<ScriptChannelPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "script_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScriptChannelPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.channel(), 128);
            buf.writeUtf(p.data(), 32000); // a client can send this, so it stays bounded
        },
        buf -> new ScriptChannelPayload(buf.readUtf(128), buf.readUtf(32000))
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
