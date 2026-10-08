package com.koper.koper_lib.api.core;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// server says "entity x plays this bedrock animation" (entity.playAnimation, /playanimation).
// empty strings = not given. kodel's actors play it on top of what the entity is doing
public record BedrockAnimPayload(int entity, String animation, String nextState, float blendOut, String stop, String controller)
        implements CustomPacketPayload {
    public static final Type<BedrockAnimPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koperlib_core", "bedrock_anim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BedrockAnimPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, BedrockAnimPayload::entity,
        ByteBufCodecs.STRING_UTF8, BedrockAnimPayload::animation,
        ByteBufCodecs.STRING_UTF8, BedrockAnimPayload::nextState,
        ByteBufCodecs.FLOAT, BedrockAnimPayload::blendOut,
        ByteBufCodecs.STRING_UTF8, BedrockAnimPayload::stop,
        ByteBufCodecs.STRING_UTF8, BedrockAnimPayload::controller,
        BedrockAnimPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
