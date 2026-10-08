package com.koper.koper_lib.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KenderBlockEventPayload(long kontraId, BlockPos localPos, int eventId, int eventData)
        implements CustomPacketPayload {
    public static final Type<KenderBlockEventPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_block_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KenderBlockEventPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.kontraId);
            buf.writeBlockPos(p.localPos);
            buf.writeVarInt(p.eventId);
            buf.writeVarInt(p.eventData);
        },
        buf -> new KenderBlockEventPayload(buf.readLong(), buf.readBlockPos(), buf.readVarInt(), buf.readVarInt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
