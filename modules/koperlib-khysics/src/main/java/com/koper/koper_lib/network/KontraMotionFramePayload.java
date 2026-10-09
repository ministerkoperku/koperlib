package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public record KontraMotionFramePayload(long body, String dimension, long epoch, long sequence,
                                      Vec3 local, Vec3 linearVelocity, Vec3 angularVelocity, Vec3 localCenterOfMass, boolean correction, boolean vacuum) implements CustomPacketPayload {
    public static final Type<KontraMotionFramePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "relative_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KontraMotionFramePayload> CODEC = StreamCodec.of(
        (b,p) -> { b.writeLong(p.body); b.writeUtf(p.dimension,256); b.writeLong(p.epoch); b.writeLong(p.sequence);
            KontraMotionPayload.writeVec(b,p.local); KontraMotionPayload.writeVec(b,p.linearVelocity); KontraMotionPayload.writeVec(b,p.angularVelocity); KontraMotionPayload.writeVec(b,p.localCenterOfMass); b.writeBoolean(p.correction); b.writeBoolean(p.vacuum); },
        b -> new KontraMotionFramePayload(b.readLong(),b.readUtf(256),b.readLong(),b.readLong(),
            KontraMotionPayload.readVec(b),KontraMotionPayload.readVec(b),KontraMotionPayload.readVec(b),KontraMotionPayload.readVec(b),b.readBoolean(),b.readBoolean()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
