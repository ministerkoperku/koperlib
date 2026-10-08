package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record KfxAttachBetweenPayload(
    long id,
    int startEntityId, float startOx, float startOy, float startOz,
    int endEntityId, float endOx, float endOy, float endOz
) implements CustomPacketPayload {
    public static final Type<KfxAttachBetweenPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kfx_attach_between"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KfxAttachBetweenPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.id);
            buf.writeInt(p.startEntityId);
            buf.writeFloat(p.startOx); buf.writeFloat(p.startOy); buf.writeFloat(p.startOz);
            buf.writeInt(p.endEntityId);
            buf.writeFloat(p.endOx); buf.writeFloat(p.endOy); buf.writeFloat(p.endOz);
        },
        buf -> new KfxAttachBetweenPayload(buf.readLong(), buf.readInt(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readInt(),
            buf.readFloat(), buf.readFloat(), buf.readFloat())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
