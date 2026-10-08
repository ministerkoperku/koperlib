package com.koper.koper_lib.kodel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// C2S: the client's ray went through a damage box on one of this entity's bones
public record KodelBoneHitPayload(int entityId, String bone, float damageMultiplier) implements CustomPacketPayload {
    public static final Type<KodelBoneHitPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "kodel_bone_hit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KodelBoneHitPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId());
            buf.writeUtf(p.bone(), 128);
            buf.writeFloat(p.damageMultiplier());
        },
        buf -> new KodelBoneHitPayload(buf.readVarInt(), buf.readUtf(128), buf.readFloat())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
