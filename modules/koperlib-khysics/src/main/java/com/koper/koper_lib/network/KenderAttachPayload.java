package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// C2S — client wants to attach a block to a kontraktion
// fired when player right-clicks while KenderTargeting has a physics hit
public record KenderAttachPayload(
    long kontraId,
    float offX, float offY, float offZ,  // body-local offset where new block goes
    int blockStateId                      // Block.getId(state) — what to place
) implements CustomPacketPayload {

    public static final Type<KenderAttachPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kender_attach"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KenderAttachPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.kontraId());
            buf.writeFloat(p.offX()); buf.writeFloat(p.offY()); buf.writeFloat(p.offZ());
            buf.writeInt(p.blockStateId());
        },
        buf -> new KenderAttachPayload(
            buf.readLong(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readInt()
        )
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
