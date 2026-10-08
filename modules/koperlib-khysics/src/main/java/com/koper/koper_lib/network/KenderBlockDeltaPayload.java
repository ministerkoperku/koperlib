package com.koper.koper_lib.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// S2C — ONE block appeared or vanished on a kontra. adding or breaking a single block used to
// re-broadcast the whole body: every offset, every state id, and the full NBT of every block entity
// on board, to everyone in the dimension. the client then rebuilt its render data from scratch and
// ran BlockEntity.loadStatic on every last chest. place one create tank, create fires a burst of
// changes while it merges, and you eat that several times over. that's the lag.
public record KenderBlockDeltaPayload(
    long kontraId,
    BlockPos local,      // the server's key, same one the spawn payload ships
    int blockStateId,
    float ox, float oy, float oz,
    boolean removed,
    CompoundTag blockEntityTag
) implements CustomPacketPayload {

    public static final Type<KenderBlockDeltaPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_block_delta"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderBlockDeltaPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.kontraId());
            buf.writeInt(p.local().getX()); buf.writeInt(p.local().getY()); buf.writeInt(p.local().getZ());
            buf.writeInt(p.blockStateId());
            buf.writeFloat(p.ox()); buf.writeFloat(p.oy()); buf.writeFloat(p.oz());
            buf.writeBoolean(p.removed());
            buf.writeBoolean(p.blockEntityTag() != null);
            if (p.blockEntityTag() != null) buf.writeNbt(p.blockEntityTag());
        },
        buf -> {
            long id = buf.readLong();
            BlockPos local = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            int stateId = buf.readInt();
            float ox = buf.readFloat(), oy = buf.readFloat(), oz = buf.readFloat();
            boolean removed = buf.readBoolean();
            CompoundTag tag = buf.readBoolean() ? buf.readNbt() : null;
            return new KenderBlockDeltaPayload(id, local, stateId, ox, oy, oz, removed, tag);
        }
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
