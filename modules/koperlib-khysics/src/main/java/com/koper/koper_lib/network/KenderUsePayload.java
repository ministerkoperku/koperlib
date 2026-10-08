package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KenderUsePayload(KenderHitRef hit) implements CustomPacketPayload {
    public static final Type<KenderUsePayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_use"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KenderUsePayload> CODEC = StreamCodec.of(
        (buf, p) -> p.hit.write(buf),
        buf -> new KenderUsePayload(KenderHitRef.read(buf))
    );

    public KenderUsePayload(long kontraId, net.minecraft.core.BlockPos localPos, int localDirection,
                            float hitX, float hitY, float hitZ) {
        this(new KenderHitRef(kontraId, localPos, localDirection, hitX, hitY, hitZ));
    }

    public long kontraId() { return hit.kontraId(); }
    public net.minecraft.core.BlockPos localPos() { return hit.localPos(); }
    public int localDirection() { return hit.localDirection(); }
    public float hitX() { return hit.hitX(); }
    public float hitY() { return hit.hitY(); }
    public float hitZ() { return hit.hitZ(); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
