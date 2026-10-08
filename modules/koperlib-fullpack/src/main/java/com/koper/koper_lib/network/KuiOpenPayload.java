package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// server tells a client to open a kui screen. carries the bits the client needs to draw it
// so it doesn't depend on KuiBook being populated client-side (matters on dedicated servers).
// layout = widgets json (json mode) or regions json (texture mode). png = baked/custom image bytes (texture mode, else empty).
public record KuiOpenPayload(String id, String title, int w, int h, String mode, String layout, String state, byte[] png)
        implements CustomPacketPayload {
    public static final Type<KuiOpenPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kui_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KuiOpenPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.id);
            buf.writeUtf(p.title);
            buf.writeVarInt(p.w);
            buf.writeVarInt(p.h);
            buf.writeUtf(p.mode);
            buf.writeUtf(p.layout, 262144); // widgets or regions json
            buf.writeUtf(p.state, 32768);   // persisted widget values, json, "" if none
            buf.writeByteArray(p.png);      // png bytes for texture mode, empty otherwise
        },
        buf -> new KuiOpenPayload(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(),
                                  buf.readUtf(262144), buf.readUtf(32768), buf.readByteArray(2_000_000))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
