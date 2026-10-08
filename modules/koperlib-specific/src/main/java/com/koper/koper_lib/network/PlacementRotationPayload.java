package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PlacementRotationPayload(int turns, int axisTurns) implements CustomPacketPayload {

    public static final Type<PlacementRotationPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "placement_rotation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlacementRotationPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.turns());
                buf.writeVarInt(payload.axisTurns());
            },
            buf -> new PlacementRotationPayload(buf.readVarInt(), buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
