package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// koper.gui.hud(player, id, show) -> add/remove a layout drawn as a screen overlay (not a Screen).
// show=false drops it (layout/coords then ignored).
public record KuiHudPayload(String id, boolean show, String layout, int x, int y) implements CustomPacketPayload {
    public static final Type<KuiHudPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kui_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KuiHudPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.id);
            buf.writeBoolean(p.show);
            buf.writeUtf(p.layout, 262144);
            buf.writeVarInt(p.x);
            buf.writeVarInt(p.y);
        },
        buf -> new KuiHudPayload(buf.readUtf(), buf.readBoolean(), buf.readUtf(262144), buf.readVarInt(), buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
