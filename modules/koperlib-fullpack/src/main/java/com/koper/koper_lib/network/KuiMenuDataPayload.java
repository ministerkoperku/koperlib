package com.koper.koper_lib.network;

import com.koper.koper_lib.kui.KuiMenuData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// sent just before vanilla's open-screen packet so the client has the layout/png ready when it builds the menu
public record KuiMenuDataPayload(KuiMenuData data) implements CustomPacketPayload {
    public static final Type<KuiMenuDataPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kui_menu_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KuiMenuDataPayload> CODEC =
        StreamCodec.composite(KuiMenuData.CODEC, KuiMenuDataPayload::data, KuiMenuDataPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
