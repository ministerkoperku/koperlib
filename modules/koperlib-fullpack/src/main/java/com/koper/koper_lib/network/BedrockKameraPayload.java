package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// bedrock's /camera and player.camera for one player: {"a":"set"|"clear"|"fade", ...} (BedrockKamera reads it)
public record BedrockKameraPayload(String json) implements CustomPacketPayload {
    public static final Type<BedrockKameraPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "bedrock_camera"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BedrockKameraPayload> CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, BedrockKameraPayload::json, BedrockKameraPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
