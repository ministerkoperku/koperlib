package com.koper.koper_lib.api.core;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// the bits of a bedrock mob's server state its resource pack reads through queries:
// variant, mark_variant, skin_id, flags like is_tamed, entity properties. json because packs
// invent their own property names
public record BedrockStanPayload(int entity, String state) implements CustomPacketPayload {
    public static final Type<BedrockStanPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koperlib_core", "bedrock_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BedrockStanPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, BedrockStanPayload::entity,
        ByteBufCodecs.STRING_UTF8, BedrockStanPayload::state,
        BedrockStanPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
