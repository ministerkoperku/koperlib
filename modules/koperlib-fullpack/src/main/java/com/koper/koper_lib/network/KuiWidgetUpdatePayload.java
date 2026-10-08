package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// koper.gui.set(widget, value) -> server -> client: poke a widget on whatever kui screen/hud is open
public record KuiWidgetUpdatePayload(String widget, String value) implements CustomPacketPayload {
    public static final Type<KuiWidgetUpdatePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kui_wset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KuiWidgetUpdatePayload> CODEC = StreamCodec.of(
        (buf, p) -> { buf.writeUtf(p.widget); buf.writeUtf(p.value, 32768); },
        buf -> new KuiWidgetUpdatePayload(buf.readUtf(), buf.readUtf(32768))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
