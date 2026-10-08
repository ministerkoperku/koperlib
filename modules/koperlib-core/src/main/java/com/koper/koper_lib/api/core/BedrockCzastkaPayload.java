package com.koper.koper_lib.api.core;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// server says "play this bedrock particle effect here". fullpack's addon scripts send it, kodel's
// particle engine plays it. lives in core so neither of them has to know the other
public record BedrockCzastkaPayload(String effect, double x, double y, double z) implements CustomPacketPayload {
    public static final Type<BedrockCzastkaPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koperlib_core", "bedrock_particle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BedrockCzastkaPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, BedrockCzastkaPayload::effect,
        ByteBufCodecs.DOUBLE, BedrockCzastkaPayload::x,
        ByteBufCodecs.DOUBLE, BedrockCzastkaPayload::y,
        ByteBufCodecs.DOUBLE, BedrockCzastkaPayload::z,
        BedrockCzastkaPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
