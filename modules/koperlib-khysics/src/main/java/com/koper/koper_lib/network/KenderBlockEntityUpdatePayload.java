package com.koper.koper_lib.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KenderBlockEntityUpdatePayload(long kontraId, BlockPos localPos, CompoundTag tag)
        implements CustomPacketPayload {
    public static final Type<KenderBlockEntityUpdatePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_be_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KenderBlockEntityUpdatePayload> CODEC = StreamCodec.of(
        (buf, p) -> { buf.writeLong(p.kontraId); buf.writeBlockPos(p.localPos); buf.writeNbt(p.tag); },
        buf -> new KenderBlockEntityUpdatePayload(buf.readLong(), buf.readBlockPos(), buf.readNbt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
