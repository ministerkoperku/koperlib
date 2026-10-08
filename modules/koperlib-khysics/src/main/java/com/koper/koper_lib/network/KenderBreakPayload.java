package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// C2S — client wants to break a block from a kontraktion
public record KenderBreakPayload(
    KenderHitRef hit
) implements CustomPacketPayload {

    public static final Type<KenderBreakPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_break"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderBreakPayload> CODEC = StreamCodec.of(
        (buf, p) -> p.hit.write(buf),
        buf -> new KenderBreakPayload(KenderHitRef.read(buf))
    );

    public KenderBreakPayload(long kontraId, int localX, int localY, int localZ,
                              int localDirection, float hitX, float hitY, float hitZ) {
        this(new KenderHitRef(kontraId, new net.minecraft.core.BlockPos(localX, localY, localZ),
            localDirection, hitX, hitY, hitZ));
    }

    public long kontraId() { return hit.kontraId(); }
    public int localX() { return hit.localPos().getX(); }
    public int localY() { return hit.localPos().getY(); }
    public int localZ() { return hit.localPos().getZ(); }
    public int localDirection() { return hit.localDirection(); }
    public float hitX() { return hit.hitX(); }
    public float hitY() { return hit.hitY(); }
    public float hitZ() { return hit.hitZ(); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
