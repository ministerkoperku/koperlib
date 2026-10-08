package com.koper.koper_lib.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KenderGridBlockUpdatePayload(long kontraId, BlockPos localPos, int blockStateId)
        implements CustomPacketPayload {
    public static final Type<KenderGridBlockUpdatePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_grid_block_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KenderGridBlockUpdatePayload> CODEC = StreamCodec.of(
        (buf, p) -> { buf.writeLong(p.kontraId); buf.writeBlockPos(p.localPos); buf.writeVarInt(p.blockStateId); },
        buf -> new KenderGridBlockUpdatePayload(buf.readLong(), buf.readBlockPos(), buf.readVarInt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
