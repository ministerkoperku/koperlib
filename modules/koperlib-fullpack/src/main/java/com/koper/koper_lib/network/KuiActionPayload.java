package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client tells the server a widget was poked. server stores the value + fires the gui's lua handler.
// action ∈ click | toggle | slider | input
public record KuiActionPayload(String guiId, String widget, String action, float value, String text, boolean checked)
        implements CustomPacketPayload {
    public static final Type<KuiActionPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kui_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KuiActionPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUtf(p.guiId);
            buf.writeUtf(p.widget);
            buf.writeUtf(p.action);
            buf.writeFloat(p.value);
            buf.writeUtf(p.text, 1024);
            buf.writeBoolean(p.checked);
        },
        buf -> new KuiActionPayload(buf.readUtf(), buf.readUtf(), buf.readUtf(),
                                    buf.readFloat(), buf.readUtf(1024), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
