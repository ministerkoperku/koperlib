package com.koper.koper_lib.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;

// Exact client-side hit in kontra-local coordinates. Every block action uses this wire shape.
public record KenderHitRef(long kontraId, BlockPos localPos, int localDirection,
                           float hitX, float hitY, float hitZ) {

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(kontraId);
        buf.writeBlockPos(localPos);
        buf.writeByte(localDirection);
        buf.writeFloat(hitX);
        buf.writeFloat(hitY);
        buf.writeFloat(hitZ);
    }

    public static KenderHitRef read(RegistryFriendlyByteBuf buf) {
        return new KenderHitRef(buf.readLong(), buf.readBlockPos(), buf.readUnsignedByte(),
            buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static final float MAX_REACH = 2.501f;

    public boolean hasFiniteBlockPoint() {
        return Float.isFinite(hitX) && Float.isFinite(hitY) && Float.isFinite(hitZ)
            // a block's shape may reach out of its own cell (a model drawn a cell forward). the real
            // check against that shape is in KoperPhys.resolveGridHit, this is only the sanity cap
            && Math.abs(hitX) <= MAX_REACH && Math.abs(hitY) <= MAX_REACH && Math.abs(hitZ) <= MAX_REACH;
    }
}
