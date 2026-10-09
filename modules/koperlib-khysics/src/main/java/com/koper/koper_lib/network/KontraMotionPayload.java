package com.koper.koper_lib.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public record KontraMotionPayload(long body, String dimension, long epoch, long sequence,
                                 Vec3 local, float yaw, float pitch) implements CustomPacketPayload {
    public static final Type<KontraMotionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("koper_lib", "relative_motion"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KontraMotionPayload> CODEC = StreamCodec.of(
        (b,p) -> { b.writeLong(p.body); b.writeUtf(p.dimension, 256); b.writeLong(p.epoch); b.writeLong(p.sequence);
            writeVec(b,p.local); b.writeFloat(p.yaw); b.writeFloat(p.pitch); },
        b -> new KontraMotionPayload(b.readLong(), b.readUtf(256), b.readLong(), b.readLong(), readVec(b), b.readFloat(), b.readFloat()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    static void writeVec(RegistryFriendlyByteBuf b, Vec3 v) { b.writeDouble(v.x); b.writeDouble(v.y); b.writeDouble(v.z); }
    static Vec3 readVec(RegistryFriendlyByteBuf b) { return new Vec3(b.readDouble(),b.readDouble(),b.readDouble()); }
}
